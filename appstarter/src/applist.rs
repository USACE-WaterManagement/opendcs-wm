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
