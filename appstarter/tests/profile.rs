//! Tests for `appstarter::profile`, turning the `decodes.properties` template
//! into one profile file per application.
//!
//! Replacement for the Go implementation's `profile_test.go`, split so that
//! rendering can be tested without a java runtime to ask the database what to
//! start.

use std::fs;
use std::path::PathBuf;

use appstarter::env::EnvironmentVars;
use appstarter::profile::{self, Profile};

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

#[test]
fn profiles_are_created_for_every_app() {
    let out = tempfile::tempdir().unwrap();
    let env = environment();
    let apps = ["compproc".to_string(), "compdepends".to_string()];

    let profiles =
        profile::get_profiles(&env, profile::PROFILE_TEMPLATE, out.path(), &apps).unwrap();

    assert_eq!(profiles.len(), apps.len());
    for (profile, app_name) in profiles.iter().zip(apps) {
        assert_eq!(profile.office, "SPK");
        assert_eq!(profile.app_name, app_name);
        assert_eq!(
            profile.profile_file,
            out.path().join(format!("{app_name}.profile"))
        );
        let contents = fs::read_to_string(&profile.profile_file).unwrap();
        assert!(
            contents.contains("CwmsOfficeId=SPK"),
            "profile for {app_name} was not rendered: {contents}"
        );
    }
}

/// The Go implementation rendered the same template, with the same values
/// `environment()` builds, into `tests/data/utility.profile.golden`. It is kept
/// as a fixture so the port cannot quietly change a single byte of a profile.
#[test]
fn renders_exactly_what_the_go_version_rendered() {
    let golden = include_str!("data/utility.profile.golden");
    assert_eq!(
        profile::render(profile::PROFILE_TEMPLATE, &environment()).unwrap(),
        golden
    );
}

#[test]
fn the_shipped_template_renders_every_value() {
    let rendered = profile::render(profile::PROFILE_TEMPLATE, &environment()).unwrap();

    assert!(
        !rendered.contains("{{"),
        "unrendered placeholder:\n{rendered}"
    );
    for expected in [
        "EditDatabaseType=XML",
        "EditDatabaseLocation=jdbc:test",
        "SiteNameTypePreference=CWMS",
        "jdbcDriverClass=org.opendcs.testing",
        "dbOfficeId=SPK",
        "CwmsOfficeId=SPK",
        "dataTypeStdPreference=CWMS",
        "dbAuthFile=noop",
    ] {
        assert!(rendered.contains(expected), "missing {expected}");
    }
    // Values that are not placeholders must survive untouched.
    for expected in [
        "routingStatusDir=$DCSTOOL_USERDIR/routstat",
        "sqlDateFormat=yyyy-MM-dd HH\\:mm\\:ss",
        "SqlKeyGenerator=decodes.sql.OracleSequenceKeyGenerator",
    ] {
        assert!(rendered.contains(expected), "mangled {expected}");
    }
}

#[test]
fn go_style_placeholders_are_accepted_with_and_without_spaces() {
    let rendered = profile::render(
        "office={{ .Office }}\napp={{.Env.ApplicationName}}\ndrive={{- .Env.DatabaseDriver}}\n",
        &environment(),
    )
    .unwrap();

    assert_eq!(
        rendered,
        "office=SPK\napp=compproc\ndrive=org.opendcs.testing\n"
    );
}

#[test]
fn re_rendering_replaces_the_previous_contents() {
    let out = tempfile::tempdir().unwrap();
    let env = environment();
    let path = out.path().join("utility.profile");
    fs::write(&path, "a very long line that should not be left behind\n").unwrap();

    profile::render_profile(&env, profile::PROFILE_TEMPLATE, out.path(), "utility").unwrap();

    assert!(!fs::read_to_string(&path)
        .unwrap()
        .contains("should not be left behind"));
}

#[test]
fn unknown_template_variables_are_reported() {
    let err = profile::render("{{ .Env.DoesNotExist }}", &environment()).unwrap_err();
    assert!(err.to_string().contains("profile template"));
}

/// The `Profile` a rendered file is handed back as, checked here so the type
/// stays honest about what java is given.
#[test]
fn a_profile_points_java_at_the_rendered_file() {
    let out = tempfile::tempdir().unwrap();
    let env = environment();

    let rendered =
        profile::render_profile(&env, profile::PROFILE_TEMPLATE, out.path(), "compproc").unwrap();

    assert_eq!(
        rendered,
        Profile {
            profile_file: out.path().join("compproc.profile"),
            app_name: "compproc".to_string(),
            office: "SPK".to_string(),
        }
    );
}
