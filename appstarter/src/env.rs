//! The OpenDCS related environment variables appstarter needs.
//!
//! Replaces `internal/opendcs_env.go`.

use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

pub const USERDIR: &str = "DCSTOOL_USERDIR";
pub const DATABASE_TYPE: &str = "DATABASE_TYPE";
pub const DATABASE_URL: &str = "DATABASE_URL";
pub const DATABASE_AUTH: &str = "DB_AUTH";
pub const DATABASE_DRIVER: &str = "DATABASE_DRIVER";
pub const OFFICE: &str = "CWMS_OFFICE";
pub const DATATYPE_STANDARD: &str = "DATATYPE_STANDARD";
pub const KEY_GENERATOR: &str = "KEYGENERATOR";
pub const APPLICATION_NAME: &str = "APPLICATION_NAME";
pub const DCSTOOL_HOME: &str = "DCSTOOL_HOME";

/// Used when `DB_AUTH` is not set. Tells OpenDCS where to look for the
/// credentials instead of having them written into a properties file.
const DEFAULT_DATABASE_AUTH: &str =
    "env-auth-source:username=DATABASE_USERNAME,password=DATABASE_PASSWORD";

/// Everything appstarter reads out of the container environment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvironmentVars {
    pub database_type: String,
    pub database_url: String,
    pub database_auth: String,
    pub database_driver: String,
    pub office: String,
    pub data_type_standard: String,
    pub key_generator: String,
    pub application_name: String,

    /// `DCSTOOL_HOME`
    pub install_dir: PathBuf,
    /// `DCSTOOL_USERDIR`
    pub user_dir: PathBuf,
}

impl std::fmt::Display for EnvironmentVars {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "DatabaseType={} DatabaseUrl={} DatabaseDriver={} Office={} \
             DataTypeStandard={} KeyGenerator={} ApplicationName={} \
             InstallDir={} UserDir={} DatabaseAuth={}",
            self.database_type,
            self.database_url,
            self.database_driver,
            self.office,
            self.data_type_standard,
            self.key_generator,
            self.application_name,
            self.install_dir.display(),
            self.user_dir.display(),
            // The auth string only ever names variables, never secrets, but it
            // still has no business ending up in a log we don't control.
            if self.database_auth.is_empty() {
                "<unset>"
            } else {
                "<set>"
            },
        )
    }
}

impl EnvironmentVars {
    /// Reads the current process environment, this is what the program uses.
    pub fn current() -> Result<Self> {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    /// Reads from an arbitrary source so the mapping can be tested without
    /// mutating global process state.
    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> Result<Self> {
        let database_auth = match get(DATABASE_AUTH) {
            Some(auth) if !auth.is_empty() => auth,
            _ => DEFAULT_DATABASE_AUTH.to_string(),
        };

        let env = Self {
            database_type: get(DATABASE_TYPE).unwrap_or_default(),
            database_url: get(DATABASE_URL).unwrap_or_default(),
            database_auth,
            database_driver: get(DATABASE_DRIVER).unwrap_or_default(),
            office: get(OFFICE).unwrap_or_default(),
            data_type_standard: get(DATATYPE_STANDARD).unwrap_or_default(),
            key_generator: get(KEY_GENERATOR).unwrap_or_default(),
            application_name: get(APPLICATION_NAME).unwrap_or_default(),
            install_dir: PathBuf::from(get(DCSTOOL_HOME).unwrap_or_default()),
            user_dir: PathBuf::from(get(USERDIR).unwrap_or_default()),
        };

        env.validate()?;
        Ok(env)
    }

    fn validate(&self) -> Result<()> {
        require_dir(&self.install_dir, DCSTOOL_HOME, "Install Directory")?;
        require_dir(&self.user_dir, USERDIR, "User Directory")
    }
}

fn require_dir(path: &Path, variable: &str, description: &str) -> Result<()> {
    if path.as_os_str().is_empty() {
        bail!("{description} doesn't exist in this environment: {variable} is not set");
    }
    let display = path.display();
    let metadata = std::fs::metadata(path)
        .with_context(|| format!("{description} doesn't exist in this environment: {display}"))?;
    if !metadata.is_dir() {
        bail!("{description} doesn't exist in this environment: {display} is not a directory");
    }
    Ok(())
}
