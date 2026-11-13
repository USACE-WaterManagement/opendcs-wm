//! Tests for `appstarter::env`, the environment mapping.
//!
//! Replacement for the Go implementation's `opendcs_env_test.go`. They drive
//! `EnvironmentVars::from_lookup` with a map instead of setting process
//! environment variables, so they stay valid when cargo runs tests in parallel.

use std::collections::HashMap;

use appstarter::env::{
    EnvironmentVars, APPLICATION_NAME, DATABASE_AUTH, DATABASE_DRIVER, DATABASE_TYPE, DATABASE_URL,
    DATATYPE_STANDARD, DCSTOOL_HOME, KEY_GENERATOR, OFFICE, USERDIR,
};

/// A complete, valid environment, plus the directories it points at.
fn test_environment() -> HashMap<String, String> {
    let install_dir = tempfile::tempdir().unwrap();
    let user_dir = tempfile::tempdir().unwrap();
    let values = HashMap::from([
        (
            DCSTOOL_HOME.to_string(),
            install_dir.path().display().to_string(),
        ),
        (USERDIR.to_string(), user_dir.path().display().to_string()),
        (DATABASE_TYPE.to_string(), "XML".to_string()),
        (DATABASE_URL.to_string(), "jdbc:test".to_string()),
        (
            DATABASE_DRIVER.to_string(),
            "org.opendcs.testing".to_string(),
        ),
        (DATATYPE_STANDARD.to_string(), "CWMS".to_string()),
        (DATABASE_AUTH.to_string(), "noop".to_string()),
        (OFFICE.to_string(), "SPK".to_string()),
        (KEY_GENERATOR.to_string(), "fake".to_string()),
        (APPLICATION_NAME.to_string(), "compproc".to_string()),
    ]);
    // The directories only have to exist while the environment is read, but
    // leaking them keeps every test independent of cleanup order.
    std::mem::forget(install_dir);
    std::mem::forget(user_dir);
    values
}

fn lookup<'a>(values: &'a HashMap<String, String>) -> impl Fn(&str) -> Option<String> + 'a {
    move |name: &str| values.get(name).cloned()
}

#[test]
fn environment_is_correct() {
    let values = test_environment();
    let env = EnvironmentVars::from_lookup(lookup(&values)).unwrap();

    assert_eq!(env.application_name, "compproc");
    assert_eq!(env.data_type_standard, "CWMS");
    assert_eq!(env.database_auth, "noop");
    assert_eq!(env.database_driver, "org.opendcs.testing");
    assert_eq!(env.database_type, "XML");
    assert_eq!(env.database_url, "jdbc:test");
    assert_eq!(env.key_generator, "fake");
    assert_eq!(env.office, "SPK");
    assert_eq!(env.install_dir.display().to_string(), values[DCSTOOL_HOME]);
    assert_eq!(env.user_dir.display().to_string(), values[USERDIR]);
}

#[test]
fn missing_auth_falls_back_to_the_env_var_source() {
    let mut values = test_environment();
    values.insert(DATABASE_AUTH.to_string(), String::new());

    let env = EnvironmentVars::from_lookup(lookup(&values)).unwrap();

    assert_eq!(
        env.database_auth,
        "env-auth-source:username=DATABASE_USERNAME,password=DATABASE_PASSWORD"
    );
}

#[test]
fn missing_directories_are_an_error() {
    let mut values = test_environment();
    values.remove(DCSTOOL_HOME);
    assert!(EnvironmentVars::from_lookup(lookup(&values))
        .unwrap_err()
        .to_string()
        .contains("Install Directory"));

    let mut values = test_environment();
    values.insert(USERDIR.to_string(), "/does/not/exist".to_string());
    assert!(EnvironmentVars::from_lookup(lookup(&values))
        .unwrap_err()
        .to_string()
        .contains("User Directory"));
}
