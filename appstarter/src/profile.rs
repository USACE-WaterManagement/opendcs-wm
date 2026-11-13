//! Turning the `decodes.properties` template into one profile file per
//! application.
//!
//! Replaces `internal/profile.go`.

use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use anyhow::{Context, Result};
use minijinja::{context, AutoEscape, Environment, UndefinedBehavior};
use regex::Regex;
use serde::Serialize;

use crate::env::EnvironmentVars;

/// The template the Go version embedded with `//go:embed`. District specific
/// variants can be baked into an image by replacing this file at build time.
pub const PROFILE_TEMPLATE: &str = include_str!("../templates/decodes.properties.template");

/// A rendered profile plus the information java needs to use it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Profile {
    /// Path of the rendered file, as passed to java's `-P`.
    pub profile_file: PathBuf,
    pub app_name: String,
    pub office: String,
}

/// Renders one profile per application name.
pub fn get_profiles(
    env: &EnvironmentVars,
    template: &str,
    out_dir: &Path,
    app_names: &[String],
) -> Result<Vec<Profile>> {
    app_names
        .iter()
        .map(|app_name| render_profile(env, template, out_dir, app_name))
        .collect()
}

/// Renders `template` for `app_name` into `<out_dir>/<app_name>.profile`.
pub fn render_profile(
    env: &EnvironmentVars,
    template: &str,
    out_dir: &Path,
    app_name: &str,
) -> Result<Profile> {
    let profile_file = out_dir.join(format!("{app_name}.profile"));
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        // The Go version opened these without O_TRUNC and left the tail of the
        // previous render behind.
        .truncate(true)
        .mode(0o644)
        .open(&profile_file)
        .with_context(|| format!("unable to create {}", profile_file.display()))?;

    file.write_all(render(template, env)?.as_bytes())
        .with_context(|| format!("unable to write {}", profile_file.display()))?;
    file.flush()
        .with_context(|| format!("unable to finish writing {}", profile_file.display()))?;

    Ok(Profile {
        profile_file,
        app_name: app_name.to_string(),
        office: env.office.clone(),
    })
}

/// The values a template can reference. Field names keep the Go template's
/// spelling so existing templates render unchanged.
#[derive(Serialize)]
struct TemplateEnv<'a> {
    #[serde(rename = "DatabaseType")]
    database_type: &'a str,
    #[serde(rename = "DatabaseUrl")]
    database_url: &'a str,
    #[serde(rename = "DatabaseAuth")]
    database_auth: &'a str,
    #[serde(rename = "DatabaseDriver")]
    database_driver: &'a str,
    #[serde(rename = "DataTypeStandard")]
    data_type_standard: &'a str,
    #[serde(rename = "KeyGenerator")]
    key_generator: &'a str,
    #[serde(rename = "ApplicationName")]
    application_name: &'a str,
    #[serde(rename = "Office")]
    office: &'a str,
    #[serde(rename = "InstallDir")]
    install_dir: String,
    #[serde(rename = "UserDir")]
    user_dir: String,
}

/// OpenDCS ships and districts write Go `text/template` files, where the
/// current context is written `{{ .Env.DatabaseType }}`. Jinja has no leading
/// dot, so drop it and hand the rest to minijinja, which understands the other
/// Go constructs (`{{ if }}`, `{{ for }}`... ok, `{{ range }}` aside).
fn normalize_go_template(template: &str) -> std::borrow::Cow<'_, str> {
    static DOTTED_ACTION: OnceLock<Regex> = OnceLock::new();
    let dotted_action = DOTTED_ACTION.get_or_init(|| {
        // Group 1 is the `{{` / `{%` opener, group 2 any whitespace control
        // marker, both of which are kept while the leading dot is dropped.
        Regex::new(r"\{([{%])([-+~]?\s*)\.").expect("static pattern must compile")
    });
    dotted_action.replace_all(template, "{$1$2")
}

/// Renders the profile template for one environment.
///
/// Public so `tests/profile.rs` can pin the exact bytes of a profile;
/// `render_profile` is what the program itself calls.
pub fn render(template: &str, env: &EnvironmentVars) -> Result<String> {
    let template = normalize_go_template(template);
    let context = context! {
        Env => TemplateEnv {
            database_type: &env.database_type,
            database_url: &env.database_url,
            database_auth: &env.database_auth,
            database_driver: &env.database_driver,
            data_type_standard: &env.data_type_standard,
            key_generator: &env.key_generator,
            application_name: &env.application_name,
            office: &env.office,
            install_dir: env.install_dir.display().to_string(),
            user_dir: env.user_dir.display().to_string(),
        },
        Office => &env.office,
    };

    let mut jinja = Environment::new();
    // Profiles are properties files, nothing here should be html escaped.
    jinja.set_auto_escape_callback(|_| AutoEscape::None);
    // Go's text/template wrote the template out as it was found, including a
    // trailing newline, and refused to render a template that mentioned a
    // field that doesn't exist. Match both.
    jinja.set_keep_trailing_newline(true);
    jinja.set_undefined_behavior(UndefinedBehavior::Strict);
    jinja
        .render_str(&template, context)
        .context("unable to render the profile template")
}
