//! Asking the database which computation applications this container runs.
//!
//! Replaces `internal/applist.go`.

use std::collections::BTreeMap;

use anyhow::{Context, Result};
use serde::Deserialize;

use crate::env::EnvironmentVars;
use crate::logging;
use crate::profile::Profile;

/// Property that pins a computation application to a given container image.
const CWBI_APP: &str = "cwbi-app";

/// A computation application as reported by
/// `mil.usace.army.opendcs.support.ListCompApps`.
///
/// Unknown properties are ignored and every field has a default, so a utility
/// that reports less than this still parses, like Go's `json.Unmarshal` did.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CompApp {
    pub app_id: i64,
    pub app_name: String,
    pub comment: String,
    pub manual_editing_app: bool,
    pub properties: BTreeMap<String, String>,
}

/// The applications this container should run.
///
/// `list_comp_apps` runs the java utility and returns what it wrote to stdout;
/// it is a parameter so this can be tested without a java runtime.
pub fn active_apps<F>(
    utility: &Profile,
    env: &EnvironmentVars,
    list_comp_apps: F,
) -> Result<Vec<String>>
where
    F: FnOnce(&Profile, &EnvironmentVars) -> Result<String>,
{
    let output = list_comp_apps(utility, env)?;
    let apps: Vec<CompApp> = serde_json::from_str(output.trim()).context(
        "unable to read the computation application list, \
         expected a json array from ListCompApps",
    )?;

    let mut names: Vec<String> = apps
        .iter()
        .filter(|app| {
            app.properties.get(CWBI_APP).map(String::as_str) == Some(env.application_name.as_str())
        })
        .map(|app| app.app_name.clone())
        .collect();

    if names.is_empty() {
        // Nothing is configured for this image, so run the application the
        // image is named after.
        logging::log(format_args!(
            "No computation application is tagged {CWBI_APP}={}, using it as the application name",
            env.application_name
        ));
        names.push(env.application_name.clone());
    } else {
        logging::log(format_args!(
            "Found {} applications for {}: {}",
            names.len(),
            env.application_name,
            names.join(", ")
        ));
    }

    Ok(names)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

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
        let names = active_apps(&utility(), &environment(), |_, _| {
            Ok(LIST_OUTPUT.to_string())
        })
        .unwrap();

        assert_eq!(names, vec!["SPK_Flow_Shearwave".to_string()]);
    }

    #[test]
    fn the_configured_application_name_is_the_fallback() {
        let no_matches =
            active_apps(&utility(), &environment(), |_, _| Ok("[]".to_string())).unwrap();

        assert_eq!(no_matches, vec!["compproc".to_string()]);
    }

    #[test]
    fn output_from_a_newer_utility_still_parses() {
        // Missing optional fields and fields this version doesn't know about.
        let json = r#"[{"appName": "SPK_Late", "extra": {"new": 1},
                       "properties": {"cwbi-app": "compproc"}}]"#;

        let names = active_apps(&utility(), &environment(), |_, _| Ok(json.to_string())).unwrap();

        assert_eq!(names, vec!["SPK_Late".to_string()]);
    }

    #[test]
    fn a_failure_to_list_applications_is_reported() {
        let err = active_apps(&utility(), &environment(), |_, _| {
            anyhow::bail!("utility exited with exit status: 1")
        })
        .unwrap_err();
        assert!(err.to_string().contains("exit status"));

        // Logging or a prompt in front of the json is a hard failure, the same
        // way it was in Go, because silently starting the wrong application is
        // worse.
        let err = active_apps(&utility(), &environment(), |_, _| {
            Ok("Connected to database\n[]".to_string())
        })
        .unwrap_err();
        assert!(err.to_string().contains("json array"));
    }
}
