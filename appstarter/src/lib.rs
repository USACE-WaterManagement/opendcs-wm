//! Starts the OpenDCS java applications a container is configured for and keeps
//! them running.
//!
//! This is the library half of the crate, `src/main.rs` is the command line
//! wrapper around it. It is a library so the tests in `tests/` can exercise the
//! pieces that talk to java and write files without a database, a java runtime
//! or the OpenDCS jars.
//!
//! The program:
//!
//! 1. reads the OpenDCS environment ([`env`]),
//! 2. writes a `decodes.properties` profile per application ([`profile`]),
//! 3. asks the database which computation applications belong to this image
//!    ([`applist`]),
//! 4. starts one java process per application and copies its output ([`app`]),
//! 5. exits once every application has exited, so the container runtime
//!    replaces the pod.
//!
//! Rust replacement for the Go `appstarter` program. See `README.md` for the
//! behaviour differences that matter to an operator.

pub mod app;
pub mod applist;
pub mod env;
pub mod logging;
pub mod profile;
