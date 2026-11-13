//! Starting, watching and stopping one java application.
//!
//! Replaces `internal/app.go`.

use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use walkdir::WalkDir;

use crate::env::EnvironmentVars;
use crate::logging;
use crate::profile::Profile;

/// Java main class used to ask the database which computation applications are
/// configured for this container.
pub const LIST_COMP_APPS: &str = "mil.usace.army.opendcs.support.ListCompApps";

/// How long a java application gets to react to SIGTERM before it is killed.
/// Container runtimes normally allow PID 1 30 seconds, leave room to stop
/// every application we are supervising.
pub const SHUTDOWN_GRACE: Duration = Duration::from_secs(20);

/// One java application, started as a child process of this program.
pub struct TsdbApp {
    pub profile: Profile,
    install_dir: PathBuf,
    user_dir: PathBuf,
    app_class: String,
    class_path: String,
    child: Option<Child>,
    /// Threads copying java's output. They finish on their own when java
    /// closes its stdout/stderr; joining them could block on a grandchild that
    /// inherited the pipes, so they are only kept alive.
    _output_pumps: Vec<JoinHandle<()>>,
}

impl TsdbApp {
    /// Collects everything needed to start `app_class` for `profile`.
    pub fn new(profile: Profile, env: &EnvironmentVars, app_class: &str) -> Result<Self> {
        Ok(Self {
            profile,
            install_dir: env.install_dir.clone(),
            user_dir: env.user_dir.clone(),
            app_class: app_class.to_string(),
            class_path: build_class_path(&env.user_dir, &env.install_dir)?,
            child: None,
            _output_pumps: Vec::new(),
        })
    }

    /// Starts the application in the background.
    pub fn start(&mut self) -> Result<()> {
        let prop_file = write_prop_file(
            &self.profile,
            &self.install_dir,
            &self.user_dir,
            &self.class_path,
        )?;
        let java = find_java()?;
        let mut command = java_command(&java, &prop_file, &self.app_class, &self.profile);

        logging::app_event(
            "info",
            &self.profile.office,
            &self.profile.app_name,
            "Starting application",
        );
        logging::log(format_args!("Command was {}", describe_command(&command)));

        let mut child = command
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .with_context(|| format!("unable to start {}", self.label()))?;

        // The Go version copied java's stdout through as unlabelled lines
        // because the applications emit their own structured log records.
        let stdout = child
            .stdout
            .take()
            .context("java stdout pipe was not created")?;
        let stderr = child
            .stderr
            .take()
            .context("java stderr pipe was not created")?;
        self._output_pumps = vec![
            pump_output(stdout, &self.profile, "info"),
            pump_output(stderr, &self.profile, "error"),
        ];
        self.child = Some(child);

        logging::log(format_args!("App started"));
        Ok(())
    }

    /// Whether the java process is still running. Reaps it once it has exited,
    /// replacing the `active` flag the Go version set from a waiter goroutine.
    pub fn is_running(&mut self) -> bool {
        let Some(mut child) = self.child.take() else {
            return false;
        };
        match child.try_wait() {
            Ok(None) => {
                self.child = Some(child);
                true
            }
            Ok(Some(status)) => {
                if !status.success() {
                    logging::app_event(
                        "error",
                        &self.profile.office,
                        &self.profile.app_name,
                        &format!("App failed with {status}"),
                    );
                }
                logging::log(format_args!("App {} exited with {status}", self.label()));
                false
            }
            Err(err) => {
                logging::log(format_args!(
                    "App {} could not be waited on: {err}",
                    self.label()
                ));
                false
            }
        }
    }

    /// Asks java to shut down and only kills it if it ignores the request.
    pub fn shutdown(&mut self) {
        let Some(mut child) = self.child.take() else {
            return;
        };
        let label = self.label();

        if let Ok(Some(_)) = child.try_wait() {
            return;
        }

        let pid = child.id();
        logging::log(format_args!("Sending SIGTERM to {label} (pid {pid})"));
        // SAFETY: kill(2) is async-signal-safe and the pid is our own child.
        if unsafe { libc::kill(pid as libc::pid_t, libc::SIGTERM) } != 0 {
            let err = std::io::Error::last_os_error();
            logging::log(format_args!("Unable to signal {label} (pid {pid}): {err}"));
        }

        let deadline = Instant::now() + SHUTDOWN_GRACE;
        while Instant::now() < deadline {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) => std::thread::sleep(Duration::from_millis(100)),
                Err(err) => {
                    logging::log(format_args!(
                        "Unable to wait for {label} (pid {pid}): {err}"
                    ));
                    break;
                }
            }
        }

        logging::log(format_args!(
            "{label} did not stop within {}s, killing it",
            SHUTDOWN_GRACE.as_secs()
        ));
        let _ = child.kill();
        let _ = child.wait();
    }

    /// `OFFICE/appName`, the label the Go version used in its log messages.
    pub fn label(&self) -> String {
        format!("{}/{}", self.profile.office, self.profile.app_name)
    }
}

/// Every jar under the user directory followed by every jar under the install
/// directory, in the platform's java class path format.
fn build_class_path(user_dir: &Path, install_dir: &Path) -> Result<String> {
    let mut jars: Vec<String> = Vec::new();
    for root in [user_dir, install_dir] {
        if !root.is_dir() {
            bail!(
                "Directory doesn't exist in this environment: {}",
                root.display()
            );
        }
        // sort_by_file_name matches filepath.WalkDir's lexical order so the
        // class path, and therefore the java behavior, is reproducible.
        for entry in WalkDir::new(root)
            .sort_by_file_name()
            .into_iter()
            .filter_map(|entry| match entry {
                Ok(entry) => Some(entry),
                Err(err) => {
                    // One unreadable directory is not a reason to lose the
                    // whole class path.
                    logging::log(format_args!("Unable to scan for jars: {err}"));
                    None
                }
            })
        {
            if !entry.file_type().is_file() {
                continue;
            }
            if entry.path().extension().is_some_and(|ext| ext == "jar") {
                jars.push(entry.path().display().to_string());
            }
        }
    }
    Ok(jars.join(":"))
}

/// Name of the java `@argfile` for one profile. It is written next to the
/// profile, which is the working directory unless configured otherwise.
fn prop_file_path(profile: &Profile) -> PathBuf {
    profile
        .profile_file
        .parent()
        .unwrap_or(Path::new(""))
        .join(format!("args-{}-{}", profile.office, profile.app_name))
}

/// Writes the java options file, `-cp` plus the system properties OpenDCS
/// expects. The file is what keeps the command line under the OS limit.
fn write_prop_file(
    profile: &Profile,
    install_dir: &Path,
    user_dir: &Path,
    class_path: &str,
) -> Result<PathBuf> {
    let path = prop_file_path(profile);
    let mut props_file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        // The Go version left the tail of the previous contents behind.
        .truncate(true)
        .mode(0o644)
        .open(&path)
        .with_context(|| format!("unable to create {}", path.display()))?;

    writeln!(props_file, "-cp {class_path}").context("unable to write class path")?;
    writeln!(props_file, "-Xms300m").context("unable to write initial heap")?;
    writeln!(props_file, "-Xmx300m").context("unable to write maximum heap")?;
    // Uncomment if you need to connect VisualVM when this is running in a docker container.
    // writeln!(props_file, "-Dcom.sun.management.jmxremote")?;
    // writeln!(props_file, "-Dcom.sun.management.jmxremote.port=9010")?;
    // writeln!(props_file, "-Dcom.sun.management.jmxremote.local.only=false")?;
    // writeln!(props_file, "-Dcom.sun.management.jmxremote.authenticate=false")?;
    // writeln!(props_file, "-Dcom.sun.management.jmxremote.ssl=false")?;
    let properties = [
        ("DCSTOOL_HOME", install_dir.display().to_string()),
        ("DCSTOOL_USERDIR", user_dir.display().to_string()),
        ("DECODES_INSTALL_DIR", install_dir.display().to_string()),
        ("APP_NAME", profile.app_name.clone()),
        (
            "logback.configurationFile",
            "/opt/opendcs/logback.xml".to_string(),
        ),
        ("LOG_LEVEL", "INFO".to_string()),
    ];
    for (name, value) in properties {
        writeln!(props_file, "-D{name}={value}")
            .with_context(|| format!("unable to write -D{name}"))?;
    }

    Ok(path)
}

fn java_command(java: &Path, prop_file: &Path, app_class: &str, profile: &Profile) -> Command {
    let mut command = Command::new(java);
    command
        .arg(format!("@{}", prop_file.display()))
        .arg(app_class)
        .arg("-P")
        .arg(&profile.profile_file)
        .arg("-a")
        .arg(&profile.app_name);
    command
}

/// Renders a command the way Go's `exec.Cmd.String()` did, for the logs.
fn describe_command(command: &Command) -> String {
    let mut parts = vec![command.get_program().to_string_lossy().into_owned()];
    parts.extend(command.get_args().map(|arg| {
        let arg = arg.to_string_lossy();
        if arg.chars().any(char::is_whitespace) {
            format!("'{arg}'")
        } else {
            arg.into_owned()
        }
    }));
    parts.join(" ")
}

fn find_java() -> Result<PathBuf> {
    let path = std::env::var_os("PATH").context("PATH is not set, unable to find java")?;
    for dir in std::env::split_paths(&path) {
        if dir.as_os_str().is_empty() {
            continue;
        }
        let candidate = dir.join("java");
        if is_executable(&candidate) {
            logging::log(format_args!("Found java at '{}'", candidate.display()));
            return Ok(candidate);
        }
    }
    bail!("Unable to find java on the PATH")
}

fn is_executable(path: &Path) -> bool {
    fs::metadata(path)
        .map(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
        .unwrap_or(false)
}

/// Runs a java application to completion and returns everything it wrote to
/// stdout. Used to ask the database what to start, where stdout is data and
/// therefore captured while diagnostics stay on our stderr.
pub fn run_and_capture(
    profile: &Profile,
    env: &EnvironmentVars,
    app_class: &str,
) -> Result<String> {
    let class_path = build_class_path(&env.user_dir, &env.install_dir)?;
    let prop_file = write_prop_file(profile, &env.install_dir, &env.user_dir, &class_path)?;
    let java = find_java()?;
    let mut command = java_command(&java, &prop_file, app_class, profile);
    logging::log(format_args!("Command was {}", describe_command(&command)));

    let output = command
        .stdin(Stdio::null())
        .stderr(Stdio::inherit())
        .output()
        .with_context(|| format!("unable to run {app_class}"))?;
    if !output.status.success() {
        bail!(
            "{app_class} failed with {} and said: {}",
            output.status,
            String::from_utf8_lossy(&output.stdout).trim()
        );
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Copies java's output line by line to our own stdout, then reports that the
/// stream ended. Replaces Go's `redirectPipe`.
fn pump_output<R: std::io::Read + Send + 'static>(
    pipe: R,
    profile: &Profile,
    level: &'static str,
) -> JoinHandle<()> {
    let office = profile.office.clone();
    let app_name = profile.app_name.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(pipe).lines() {
            match line {
                Ok(line) => logging::java_output(line.trim_end_matches('\r')),
                Err(err) => {
                    logging::log(format_args!(
                        "Unable to read {office}/{app_name} output: {err}"
                    ));
                    break;
                }
            }
        }
        logging::app_event(level, &office, &app_name, "Log output terminated.");
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile_in(dir: &Path) -> Profile {
        Profile {
            profile_file: dir.join("compproc.profile"),
            app_name: "compproc".to_string(),
            office: "SPK".to_string(),
        }
    }

    fn touch(dir: &Path, name: &str) {
        let path = dir.join(name);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(path, "not really a jar").unwrap();
    }

    #[test]
    fn jars_are_found_in_the_user_dir_first_then_the_install_dir() {
        let user = tempfile::tempdir().unwrap();
        let install = tempfile::tempdir().unwrap();
        touch(user.path(), "dep/b.jar");
        touch(user.path(), "dep/a.jar");
        touch(user.path(), "dep/notajar.txt");
        touch(install.path(), "dep/z.jar");

        let class_path = build_class_path(user.path(), install.path()).unwrap();
        let jars: Vec<&str> = class_path.split(':').collect();

        assert_eq!(
            jars,
            vec![
                user.path().join("dep/a.jar").display().to_string(),
                user.path().join("dep/b.jar").display().to_string(),
                install.path().join("dep/z.jar").display().to_string(),
            ]
        );
    }

    #[test]
    fn a_missing_directory_is_reported_instead_of_panicking() {
        let user = tempfile::tempdir().unwrap();
        let err = build_class_path(user.path(), Path::new("/does/not/exist")).unwrap_err();
        assert!(err.to_string().contains("Directory doesn't exist"));
    }

    #[test]
    fn the_options_file_matches_what_java_needs() {
        let dir = tempfile::tempdir().unwrap();
        let profile = profile_in(dir.path());

        let path = write_prop_file(
            &profile,
            Path::new("/tmp/installDir"),
            Path::new("/tmp/userDir"),
            "/user/a.jar:/install/b.jar",
        )
        .unwrap();

        assert_eq!(path, dir.path().join("args-SPK-compproc"));
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            concat!(
                "-cp /user/a.jar:/install/b.jar\n",
                "-Xms300m\n",
                "-Xmx300m\n",
                "-DDCSTOOL_HOME=/tmp/installDir\n",
                "-DDCSTOOL_USERDIR=/tmp/userDir\n",
                "-DDECODES_INSTALL_DIR=/tmp/installDir\n",
                "-DAPP_NAME=compproc\n",
                "-Dlogback.configurationFile=/opt/opendcs/logback.xml\n",
                "-DLOG_LEVEL=INFO\n",
            )
        );
    }

    #[test]
    fn a_shorter_render_clears_the_previous_options() {
        let dir = tempfile::tempdir().unwrap();
        let profile = profile_in(dir.path());
        write_prop_file(
            &profile,
            Path::new("/a/very/long/install/directory"),
            Path::new("/tmp/userDir"),
            "a.jar:b.jar:c.jar",
        )
        .unwrap();

        write_prop_file(&profile, Path::new("/short"), Path::new("/tmp/userDir"), "").unwrap();

        let contents = fs::read_to_string(prop_file_path(&profile)).unwrap();
        assert!(contents.starts_with("-cp \n"));
        assert!(!contents.contains("very/long"));
    }

    #[test]
    fn the_command_line_matches_the_go_version() {
        let profile = Profile {
            profile_file: PathBuf::from("compproc.profile"),
            app_name: "compproc".to_string(),
            office: "SPK".to_string(),
        };

        let command = java_command(
            Path::new("/usr/bin/java"),
            Path::new("args-SPK-compproc"),
            "mil.usace.army.opendcs.cwms.compcomp.CompProcessor",
            &profile,
        );

        assert_eq!(
            describe_command(&command),
            "/usr/bin/java @args-SPK-compproc \
             mil.usace.army.opendcs.cwms.compcomp.CompProcessor \
             -P compproc.profile -a compproc"
        );
    }
}
