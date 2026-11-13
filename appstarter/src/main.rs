//! appstarter starts the OpenDCS java applications this container is
//! configured for and keeps them running.
//!
//! Command line wrapper around the library in `src/lib.rs`, which holds the
//! behaviour and is what the tests in `tests/` drive.

use std::path::Path;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};
use clap::Parser;

use appstarter::app::TsdbApp;
use appstarter::profile::Profile;
use appstarter::{app, applist, env, logging, profile};

/// How often the supervisor looks at its children.
const POLL_INTERVAL: Duration = Duration::from_secs(5);

/// Profile and java options files are written into the working directory, which
/// is what the Go version did and what the container images expect.
const OUTPUT_DIR: &str = "";

#[derive(Parser, Debug)]
#[command(
    name = "appstarter",
    version,
    about = "Start and supervise the OpenDCS java applications in this container"
)]
struct Cli {
    /// Java main class to start for every application, for example
    /// mil.usace.army.cwms.compcomp.CompProcessor
    #[arg(value_name = "MAIN_CLASS")]
    main_class: String,

    /// Any further arguments the container runtime passes are ignored, matching
    /// the original program which only ever read argv[1].
    #[arg(value_name = "IGNORED", trailing_var_arg = true)]
    ignored: Vec<String>,
}

fn main() -> ExitCode {
    let cli = Cli::parse();

    // The Go version used godotenv.Overload, so a .env file wins over the
    // environment it was started with.
    if let Err(err) = dotenvy::from_filename_override(".env") {
        logging::log(format_args!(
            "No .env file, assuming current environment is correct. {err}"
        ));
    }

    let shutdown = install_signal_handlers();
    let result = run(&cli, &shutdown);
    logging::flush();

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            for (index, cause) in err.chain().enumerate() {
                let prefix = if index == 0 {
                    String::new()
                } else {
                    "  caused by: ".to_string()
                };
                logging::log(format_args!("{prefix}{cause}"));
            }
            ExitCode::FAILURE
        }
    }
}

fn run(cli: &Cli, shutdown: &AtomicBool) -> Result<()> {
    if !cli.ignored.is_empty() {
        logging::log(format_args!(
            "Ignoring argument(s) after the main class: {}",
            cli.ignored.join(" ")
        ));
    }

    let environment = env::EnvironmentVars::current().context("Invalid OpenDCS environment")?;
    println!("Type -> {}", environment.database_type);
    println!("Env -> {environment}");
    println!("Main Class -> {}", cli.main_class);

    // The utility profile is only used to talk to the database and list what
    // should be running, but it is written out like every other profile.
    let utility = profile::render_profile(
        &environment,
        profile::PROFILE_TEMPLATE,
        Path::new(OUTPUT_DIR),
        "utility",
    )
    .context("Unable to create the utility profile")?;

    let app_names = applist::active_apps(&utility, &environment, |profile, environment| {
        app::run_and_capture(profile, environment, app::LIST_COMP_APPS)
    })
    .context("Unable to determine which applications to start")?;

    let profiles = profile::get_profiles(
        &environment,
        profile::PROFILE_TEMPLATE,
        Path::new(OUTPUT_DIR),
        &app_names,
    )
    .context("Unable to create the application profiles")?;
    logging::log(format_args!(
        "Starting {} profile(s): {}",
        profiles.len(),
        profiles
            .iter()
            .map(|profile| profile.profile_file.display().to_string())
            .collect::<Vec<_>>()
            .join(" ")
    ));

    let mut apps = start_apps(&profiles, &environment, &cli.main_class, shutdown);
    supervise(&mut apps, shutdown)
}

/// Starts every profile, keeping the ones that came up. A single application
/// that refuses to start must not take the container down while the others are
/// still working.
fn start_apps(
    profiles: &[Profile],
    environment: &env::EnvironmentVars,
    app_class: &str,
    shutdown: &AtomicBool,
) -> Vec<TsdbApp> {
    let mut apps = Vec::with_capacity(profiles.len());
    for profile in profiles {
        if shutdown.load(Ordering::Relaxed) {
            logging::log(format_args!(
                "Shutdown requested, not starting any more applications"
            ));
            break;
        }
        let started =
            TsdbApp::new(profile.clone(), environment, app_class).and_then(|mut instance| {
                instance.start()?;
                Ok(instance)
            });
        match started {
            Ok(instance) => apps.push(instance),
            Err(err) => logging::app_event(
                "error",
                &profile.office,
                &profile.app_name,
                &format!("Unable to start: {err:#}"),
            ),
        }
    }
    apps
}

/// The Go version's 5 second poll loop: report what is still alive and give up
/// once nothing is left.
fn supervise(apps: &mut Vec<TsdbApp>, shutdown: &AtomicBool) -> Result<()> {
    loop {
        if shutdown.load(Ordering::Relaxed) {
            logging::log(format_args!("Shutdown requested, stopping applications"));
            stop_all(apps);
            return Ok(());
        }

        apps.retain_mut(|instance| {
            if instance.is_running() {
                true
            } else {
                logging::log(format_args!("App {} has died", instance.label()));
                false
            }
        });
        logging::log(format_args!("Have {} apps running", apps.len()));
        if apps.is_empty() {
            bail!("All controlled applications have exited.");
        }

        sleep(until_next_poll(shutdown));
    }
}

fn stop_all(apps: &mut [TsdbApp]) {
    for instance in apps.iter_mut() {
        instance.shutdown();
    }
}

/// Waits for the next poll, returning early when a shutdown signal arrives so a
/// container stop isn't delayed by the poll interval.
fn until_next_poll(shutdown: &AtomicBool) -> Duration {
    let deadline = Instant::now() + POLL_INTERVAL;
    loop {
        if shutdown.load(Ordering::Relaxed) {
            return Duration::ZERO;
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Duration::ZERO;
        }
        sleep(std::cmp::min(remaining, Duration::from_millis(200)));
    }
}

fn sleep(duration: Duration) {
    if !duration.is_zero() {
        std::thread::sleep(duration);
    }
}

/// This program is PID 1 in its container, so it has to pass a stop request on
/// to the java applications instead of leaving them to be killed when the
/// container goes away.
fn install_signal_handlers() -> Arc<AtomicBool> {
    let shutdown = Arc::new(AtomicBool::new(false));
    for signal in [signal_hook::consts::SIGTERM, signal_hook::consts::SIGINT] {
        if let Err(err) = signal_hook::flag::register(signal, Arc::clone(&shutdown)) {
            logging::log(format_args!("Unable to handle signal {signal}: {err}"));
        }
    }
    shutdown
}
