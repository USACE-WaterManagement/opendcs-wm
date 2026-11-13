//! End to end tests: run the real binary against a fake `java`, so profiles,
//! the class path, child processes, supervision and shutdown are all covered
//! without a database or the OpenDCS jars.

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

/// How long to wait for the binary to notice something. The supervisor polls
/// every 5 seconds, so this has to be comfortably above that.
const TIMEOUT: Duration = Duration::from_secs(20);

struct Harness {
    root: tempfile::TempDir,
    workdir: PathBuf,
    markers: PathBuf,
    stdout: PathBuf,
    stderr: PathBuf,
}

impl Harness {
    /// `script` becomes the body of a fake `java` that appstarter will find on
    /// its PATH. It is called as `java @argfile <class> -P <profile> -a <app>`.
    fn new(script: &str) -> Self {
        let root = tempfile::tempdir().expect("tempdir");
        let bin = root.path().join("bin");
        for dir in [
            &root.path().join("work"),
            &root.path().join("markers"),
            &bin,
        ] {
            fs::create_dir_all(dir).expect("create dir");
        }
        // One jar in each directory, so the class path has to span both.
        write(&root.path().join("user/dep/district.jar"), "user jar");
        write(&root.path().join("install/dep/opendcs.jar"), "install jar");

        let mut fake_java = String::from("#!/bin/sh\nPATH=/usr/bin:/bin\nexport PATH\n");
        fake_java.push_str(script);
        let java = bin.join("java");
        write(&java, &fake_java);
        fs::set_permissions(&java, fs::Permissions::from_mode(0o755)).unwrap();

        Self {
            workdir: root.path().join("work"),
            markers: root.path().join("markers"),
            stdout: root.path().join("stdout.log"),
            stderr: root.path().join("stderr.log"),
            root,
        }
    }

    /// Starts appstarter with the environment the compproc container image gets.
    fn start(&self) -> Child {
        let mut command = Command::new(env!("CARGO_BIN_EXE_appstarter"));
        command
            .arg("mil.usace.army.cwms.compcomp.CompProcessor")
            .current_dir(&self.workdir)
            .env("PATH", self.root.path().join("bin"))
            .env("DCSTOOL_HOME", self.root.path().join("install"))
            .env("DCSTOOL_USERDIR", self.root.path().join("user"))
            .env("DATABASE_TYPE", "CWMS-Oracle")
            .env("DATABASE_URL", "jdbc:oracle:thin:@//db:1521/CWMS")
            .env("DATABASE_DRIVER", "oracle.jdbc.driver.OracleDriver")
            .env("DATATYPE_STANDARD", "CWMS")
            .env("CWMS_OFFICE", "SPK")
            .env("KEYGENERATOR", "decodes.sql.OracleSequenceKeyGenerator")
            .env("APPLICATION_NAME", "compproc")
            .env("MARKERS", &self.markers)
            .stdin(Stdio::null())
            .stdout(Stdio::from(
                fs::File::create(&self.stdout).expect("stdout file"),
            ))
            .stderr(Stdio::from(
                fs::File::create(&self.stderr).expect("stderr file"),
            ));
        command.spawn().expect("appstarter should start")
    }

    fn stdout(&self) -> String {
        fs::read_to_string(&self.stdout).unwrap_or_default()
    }

    fn stderr(&self) -> String {
        fs::read_to_string(&self.stderr).unwrap_or_default()
    }

    /// Waits for a fake java process to have written `name` and returns it.
    fn wait_for_marker(&self, name: &str) -> String {
        let marker = self.markers.join(name);
        wait_until(TIMEOUT, || fs::read_to_string(&marker).unwrap_or_default()).unwrap_or_else(
            || {
                panic!(
                    "never saw marker {name}\nstdout:\n{}\nstderr:\n{}",
                    self.stdout(),
                    self.stderr()
                )
            },
        )
    }

    fn stderr_contains_soon(&self, needle: &str) -> bool {
        wait_until(TIMEOUT, || {
            if self.stderr().contains(needle) {
                Some(1)
            } else {
                None
            }
        })
        .is_some()
    }

    fn work_file(&self, name: &str) -> String {
        fs::read_to_string(self.workdir.join(name)).unwrap_or_default()
    }
}

fn write(path: &Path, contents: &str) {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).expect("create parent");
    }
    fs::write(path, contents).expect("write file");
}

/// Polls `check` until it produces something that isn't the default value.
fn wait_until<T: Default + PartialEq>(
    timeout: Duration,
    mut check: impl FnMut() -> T,
) -> Option<T> {
    let deadline = Instant::now() + timeout;
    loop {
        let value = check();
        if value != T::default() {
            return Some(value);
        }
        if Instant::now() >= deadline {
            return None;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Reports two computation applications, only one of which is tagged for this
/// image, then starts each application it is asked to start.
const FAKE_JAVA: &str = r#"
# real java expands @argfile itself, here it just has to be understood
argfile=$(printf '%s' "$1" | sed 's/^@//')
class="$2"
profile="$4"
app="$6"

if [ "$class" = "mil.usace.army.opendcs.support.ListCompApps" ]; then
    cp=$(sed -n 's/^-cp //p' "$argfile")
    printf 'app=%s\ncp=%s\n' "$app" "$cp" > "$MARKERS/list-args"
    printf '[{"appName": "SPK_Flow", "appId": 1, "properties": {"cwbi-app": "compproc"}}, {"appName": "SPK_Ccp", "appId": 2, "properties": {"cwbi-app": "other-image"}}]'
    exit 0
fi

echo "started $app using $profile"
echo "fake java complaining on stderr about $app" >&2
echo "$class" > "$MARKERS/$app"
"#;

#[test]
fn starts_one_application_per_configured_computation_application() {
    let harness = Harness::new(FAKE_JAVA);
    let mut child = harness.start();

    // The lister runs first, against the utility profile, and it needs the
    // jars from both the user and the install directory.
    let list_args = harness.wait_for_marker("list-args");
    assert!(list_args.contains("app=utility"), "{list_args}");
    assert!(
        list_args.contains("district.jar:") && list_args.contains("install/dep/opendcs.jar"),
        "class path should span both jar directories, was: {list_args}"
    );

    // Then the one application tagged for this image.
    assert_eq!(
        harness.wait_for_marker("SPK_Flow").trim(),
        "mil.usace.army.cwms.compcomp.CompProcessor"
    );
    let stdout = harness.stdout();
    assert!(
        stdout.contains("started SPK_Flow using SPK_Flow.profile"),
        "{stdout}"
    );
    assert!(
        stdout.contains("fake java complaining on stderr about SPK_Flow"),
        "java's stderr was not copied: {stdout}"
    );
    assert!(
        !harness.markers.join("SPK_Ccp").exists(),
        "an application belonging to another image was started"
    );

    // Profiles and java options files are written into the working directory.
    let profile = harness.work_file("SPK_Flow.profile");
    assert!(
        profile.contains("EditDatabaseType=CWMS-Oracle"),
        "{profile}"
    );
    assert!(profile.contains("CwmsOfficeId=SPK"), "{profile}");
    assert!(profile.contains("dbOfficeId=SPK"), "{profile}");
    assert!(profile.contains("jdbcDriverClass=oracle.jdbc.driver.OracleDriver"));
    let options = harness.work_file("args-SPK-SPK_Flow");
    assert!(options.contains("-DAPP_NAME=SPK_Flow"), "{options}");
    assert!(options.contains("-DDCSTOOL_USERDIR="), "{options}");
    assert!(harness
        .work_file("utility.profile")
        .contains("EditDatabaseType"));

    // The fake java exits, so appstarter has to give up too, that is what tells
    // the container runtime to replace the pod.
    let status = child.wait().expect("wait");
    assert!(!status.success(), "appstarter outlived its applications");
    let stderr = harness.stderr();
    assert!(
        stderr.contains("Found 1 applications for compproc: SPK_Flow"),
        "selection not logged: {stderr}"
    );
    assert!(
        stderr.contains(r#"{"app":"SPK/SPK_Flow","level":"info","msg":"Starting application"}"#),
        "no structured start event: {stderr}"
    );
    assert!(
        stderr.contains("App SPK/SPK_Flow has died"),
        "death not reported: {stderr}"
    );
    assert!(
        stderr.contains("All controlled applications have exited."),
        "no fatal message: {stderr}"
    );
}

#[test]
fn supervises_until_every_application_is_gone() {
    // A second application that dies immediately must not take the good one
    // down with it.
    let harness = Harness::new(
        r#"
class="$2"
app="$6"
if [ "$class" = "mil.usace.army.opendcs.support.ListCompApps" ]; then
    printf '[{"appName": "SPK_Good", "appId": 1, "properties": {"cwbi-app": "compproc"}}, {"appName": "SPK_Bad", "appId": 2, "properties": {"cwbi-app": "compproc"}}]'
    exit 0
fi
if [ "$app" = "SPK_Bad" ]; then
    echo "cannot start" >&2
    exit 1
fi
echo "running"
trap 'echo stopped > "$MARKERS/SPK_Good-stopped"; exit 0' TERM
echo alive > "$MARKERS/SPK_Good"
while [ ! -f "$MARKERS/SPK_Good-stopped" ]; do sleep 0.1; done
"#,
    );
    let mut child = harness.start();

    harness.wait_for_marker("SPK_Good");
    assert!(
        harness.stderr_contains_soon("App SPK/SPK_Bad has died"),
        "the dead application was not reported: {}",
        harness.stderr()
    );
    assert!(
        harness.stderr_contains_soon("Have 1 apps running"),
        "the living application was dropped: {}",
        harness.stderr()
    );

    // SAFETY: signalling our own child.
    unsafe { libc::kill(child.id() as libc::pid_t, libc::SIGTERM) };
    let status = child.wait().expect("wait");
    assert!(status.success(), "appstarter exited with {status}");
    assert_eq!(
        harness.wait_for_marker("SPK_Good-stopped").trim(),
        "stopped",
        "the surviving application was killed instead of asked to stop"
    );
    assert!(
        !harness.stderr().contains("killing it"),
        "the application had to be killed: {}",
        harness.stderr()
    );
}

#[test]
fn an_application_that_ignores_sigterm_is_killed() {
    let harness = Harness::new(
        r#"
class="$2"
if [ "$class" = "mil.usace.army.opendcs.support.ListCompApps" ]; then
    printf '[{"appName": "SPK_Stubborn", "appId": 1, "properties": {"cwbi-app": "compproc"}}]'
    exit 0
fi
trap '' TERM
echo alive > "$MARKERS/SPK_Stubborn"
while :; do sleep 0.1; done
"#,
    );
    let mut child = harness.start();
    harness.wait_for_marker("SPK_Stubborn");

    // SAFETY: signalling our own child.
    unsafe { libc::kill(child.id() as libc::pid_t, libc::SIGTERM) };

    // Waits out the 20 second grace period, so this is the slow test.
    let exited = wait_until(Duration::from_secs(40), || {
        child.try_wait().ok().flatten().map(|_| 1)
    });
    assert!(
        exited.is_some(),
        "appstarter never killed the stuck application"
    );
    let _ = child.wait();
    assert!(
        harness
            .stderr()
            .contains("did not stop within 20s, killing it"),
        "no kill message: {}",
        harness.stderr()
    );
}

#[test]
fn a_missing_java_is_reported_rather_than_panicking() {
    let harness = Harness::new(FAKE_JAVA);
    fs::remove_file(harness.root.path().join("bin").join("java")).unwrap();

    let status = harness.start().wait().expect("wait");
    assert!(!status.success());
    assert!(
        harness.stderr().contains("Unable to find java"),
        "{}",
        harness.stderr()
    );
}
