//! Tests for `appstarter::app`, the parts that have to be exactly right for
//! java: the class path, the `@argfile` and the command line.

use std::fs;
use std::path::{Path, PathBuf};

use appstarter::app;
use appstarter::profile::Profile;

fn profile_in(dir: &Path) -> Profile {
    Profile {
        profile_file: dir.join("compproc.profile"),
        app_name: "compproc".to_string(),
        office: "SPK".to_string(),
    }
}

fn touch(dir: &Path, name: &str) {
    let path = dir.join(name);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).unwrap();
    }
    fs::write(path, "not really a jar").unwrap();
}

#[test]
fn jars_are_found_in_the_user_dir_first_then_the_install_dir() {
    let user = tempfile::tempdir().unwrap();
    let install = tempfile::tempdir().unwrap();
    touch(user.path(), "dep/b.jar");
    touch(user.path(), "dep/a.jar");
    touch(user.path(), "dep/notajar.txt");
    touch(install.path(), "dep/z.jar");

    let class_path = app::build_class_path(user.path(), install.path()).unwrap();
    let jars: Vec<&str> = class_path.split(':').collect();

    assert_eq!(
        jars,
        vec![
            user.path().join("dep/a.jar").display().to_string(),
            user.path().join("dep/b.jar").display().to_string(),
            install.path().join("dep/z.jar").display().to_string(),
        ]
    );
}

#[test]
fn a_missing_directory_is_reported_instead_of_panicking() {
    let user = tempfile::tempdir().unwrap();
    let err = app::build_class_path(user.path(), Path::new("/does/not/exist")).unwrap_err();
    assert!(err.to_string().contains("Directory doesn't exist"));
}

#[test]
fn the_options_file_matches_what_java_needs() {
    let dir = tempfile::tempdir().unwrap();
    let profile = profile_in(dir.path());

    let path = app::write_prop_file(
        &profile,
        Path::new("/tmp/installDir"),
        Path::new("/tmp/userDir"),
        "/user/a.jar:/install/b.jar",
    )
    .unwrap();

    assert_eq!(path, dir.path().join("args-SPK-compproc"));
    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        concat!(
            "-cp /user/a.jar:/install/b.jar\n",
            "-Xms300m\n",
            "-Xmx300m\n",
            "-DDCSTOOL_HOME=/tmp/installDir\n",
            "-DDCSTOOL_USERDIR=/tmp/userDir\n",
            "-DDECODES_INSTALL_DIR=/tmp/installDir\n",
            "-DAPP_NAME=compproc\n",
            "-Dlogback.configurationFile=/opt/opendcs/logback.xml\n",
            "-DLOG_LEVEL=INFO\n",
        )
    );
}

#[test]
fn a_shorter_render_clears_the_previous_options() {
    let dir = tempfile::tempdir().unwrap();
    let profile = profile_in(dir.path());
    app::write_prop_file(
        &profile,
        Path::new("/a/very/long/install/directory"),
        Path::new("/tmp/userDir"),
        "a.jar:b.jar:c.jar",
    )
    .unwrap();

    app::write_prop_file(&profile, Path::new("/short"), Path::new("/tmp/userDir"), "").unwrap();

    let contents = fs::read_to_string(app::prop_file_path(&profile)).unwrap();
    assert!(contents.starts_with("-cp \n"));
    assert!(!contents.contains("very/long"));
}

#[test]
fn the_command_line_matches_the_go_version() {
    let profile = Profile {
        profile_file: PathBuf::from("compproc.profile"),
        app_name: "compproc".to_string(),
        office: "SPK".to_string(),
    };

    let command = app::java_command(
        Path::new("/usr/bin/java"),
        Path::new("args-SPK-compproc"),
        "mil.usace.army.opendcs.cwms.compcomp.CompProcessor",
        &profile,
    );

    assert_eq!(
        app::describe_command(&command),
        "/usr/bin/java @args-SPK-compproc \
         mil.usace.army.opendcs.cwms.compcomp.CompProcessor \
         -P compproc.profile -a compproc"
    );
}
