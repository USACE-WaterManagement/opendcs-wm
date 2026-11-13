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

fn render(template: &str, env: &EnvironmentVars) -> Result<String> {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

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

    /// Replacement for `TestProfiles` from the Go implementation, minus the
    /// java call, which lives in `applist`.
    #[test]
    fn profiles_are_created_for_every_app() {
        let out = tempfile::tempdir().unwrap();
        let env = environment();
        let apps = ["compproc".to_string(), "compdepends".to_string()];

        let profiles = get_profiles(&env, PROFILE_TEMPLATE, out.path(), &apps).unwrap();

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
    /// this module's `environment()` builds, into
    /// `tests/data/utility.profile`. It is kept as a fixture so the port cannot
    /// quietly change a single byte of a profile.
    #[test]
    fn renders_exactly_what_the_go_version_rendered() {
        let golden = include_str!("../tests/data/utility.profile.golden");
        assert_eq!(render(PROFILE_TEMPLATE, &environment()).unwrap(), golden);
    }

    #[test]
    fn the_shipped_template_renders_every_value() {
        let rendered = render(PROFILE_TEMPLATE, &environment()).unwrap();

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
        let env = environment();
        let rendered = render(
            "office={{ .Office }}\napp={{.Env.ApplicationName}}\ndrive={{- .Env.DatabaseDriver}}\n",
            &env,
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

        render_profile(&env, PROFILE_TEMPLATE, out.path(), "utility").unwrap();

        assert!(!fs::read_to_string(&path)
            .unwrap()
            .contains("should not be left behind"));
    }

    #[test]
    fn unknown_template_variables_are_reported() {
        let err = render("{{ .Env.DoesNotExist }}", &environment()).unwrap_err();
        assert!(err.to_string().contains("profile template"));
    }
}
