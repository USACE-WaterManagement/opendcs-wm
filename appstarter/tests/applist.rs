//! Tests for `appstarter::applist`, choosing which computation applications this
//! container runs.
//!
//! Replacement for the Go implementation's use of `ListCompApps` output. The
//! utility itself is a parameter, so these run without java or a database;
//! `tests/end_to_end.rs` covers calling it for real.

use std::path::PathBuf;

use appstarter::applist;
use appstarter::env::EnvironmentVars;
use appstarter::profile::Profile;

fn environment() -> EnvironmentVars {
    EnvironmentVars {
        database_type: "XML".to_string(),
        database_url: "jdbc:test".to_string(),
        database_auth: "noop".to_string(),
        database_driver: "org.opendcs.testing".to_string(),
        office: "SPK".to_string(),
        data_type_standard: "CWMS".to_string(),
        key_generator: "fake".to_string(),
        application_name: "compproc".to_string(),
        install_dir: PathBuf::from("/tmp/installDir"),
        user_dir: PathBuf::from("/tmp/userDir"),
    }
}

fn utility() -> Profile {
    Profile {
        profile_file: PathBuf::from("utility.profile"),
        app_name: "utility".to_string(),
        office: "SPK".to_string(),
    }
}

/// The real output of `ListCompApps`, trimmed to the interesting parts.
const LIST_OUTPUT: &str = r#"[
    {
        "appId": 1234,
        "appName": "SPK_Flow_Shearwave",
        "comment": "river conditions",
        "manualEditingApp": false,
        "properties": {"cwbi-app": "compproc", "schedule": "PT15M"}
    },
    {
        "appId": 1235,
        "appName": "SPK_Shearwave",
        "comment": "",
        "manualEditingApp": true,
        "properties": {"cwbi-app": "compdepends", "pool": "main"}
    },
    {
        "appId": 1236,
        "appName": "SPK_Ccp",
        "comment": "unlabelled",
        "manualEditingApp": false,
        "properties": {}
    }
]"#;

#[test]
fn only_applications_tagged_for_this_image_are_started() {
    let names = applist::active_apps(&utility(), &environment(), |_, _| {
        Ok(LIST_OUTPUT.to_string())
    })
    .unwrap();

    assert_eq!(names, vec!["SPK_Flow_Shearwave".to_string()]);
}

#[test]
fn the_configured_application_name_is_the_fallback() {
    let no_matches =
        applist::active_apps(&utility(), &environment(), |_, _| Ok("[]".to_string())).unwrap();

    assert_eq!(no_matches, vec!["compproc".to_string()]);
}

#[test]
fn output_from_a_newer_utility_still_parses() {
    // Missing optional fields and fields this version doesn't know about.
    let json = r#"[{"appName": "SPK_Late", "extra": {"new": 1},
                   "properties": {"cwbi-app": "compproc"}}]"#;

    let names =
        applist::active_apps(&utility(), &environment(), |_, _| Ok(json.to_string())).unwrap();

    assert_eq!(names, vec!["SPK_Late".to_string()]);
}

#[test]
fn a_failure_to_list_applications_is_reported() {
    let err = applist::active_apps(&utility(), &environment(), |_, _| {
        anyhow::bail!("utility exited with exit status: 1")
    })
    .unwrap_err();
    assert!(err.to_string().contains("exit status"));

    // Logging or a prompt in front of the json is a hard failure, the same way
    // it was in Go, because silently starting the wrong application is worse.
    let err = applist::active_apps(&utility(), &environment(), |_, _| {
        Ok("Connected to database\n[]".to_string())
    })
    .unwrap_err();
    assert!(err.to_string().contains("json array"));
}
