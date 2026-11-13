# appstarter

Runs the OpenDCS java applications this container is configured for, and keeps
them running. One container can host several offices and several compproc
instances, which OpenDCS does not handle on its own yet, so this program is the
container entrypoint until it does.

Rust port of the original Go program. It is a drop in replacement: same
command line, same profile files, same `decodes.properties` output, same log
channels.

## What it does

1. Loads `.env` from the working directory if present (it overrides the
   environment it was started with, like the Go version did).
2. Reads the OpenDCS environment, see the table below.
3. Renders `templates/decodes.properties.template` into `utility.profile` and
   runs `mil.usace.army.opendcs.support.ListCompApps` against it.
4. Keeps the computation applications whose `cwbi-app` property equals
   `APPLICATION_NAME`. If none are tagged, `APPLICATION_NAME` is used as the
   only application.
5. Renders one profile per application, `<appName>.profile`.
6. For each profile writes a java options file, `args-<OFFICE>-<appName>`, with
   the class path (every `*.jar` under `DCSTOOL_USERDIR`, then every `*.jar`
   under `DCSTOOL_HOME`) and the system properties OpenDCS expects, then starts
   `java @args-<OFFICE>-<appName> <MainClass> -P <profile> -a <appName>`.
7. Copies each application's output as it arrives and checks every 5 seconds
   whether they are still alive. Once every application has exited it exits
   with a failure status so the container runtime replaces the pod.

## Usage

```sh
appstarter <MainClass>
```

`<MainClass>` is the java class to start, for example
`mil.usace.army.cwms.compcomp.CompProcessor` for compproc. Arguments after the
class are ignored, the container runtime sometimes appends its own.

### Environment

| Variable | Used for | Required |
| --- | --- | --- |
| `DCSTOOL_HOME` | OpenDCS install directory, searched for jars | yes, must exist |
| `DCSTOOL_USERDIR` | District user directory, searched for jars first | yes, must exist |
| `CWMS_OFFICE` | Office written into profiles and used in file names | yes |
| `APPLICATION_NAME` | `cwbi-app` tag this image serves, e.g. `compproc` | yes |
| `DATABASE_TYPE` | `EditDatabaseType` in the profile | yes |
| `DATABASE_URL` | `EditDatabaseLocation` in the profile | yes |
| `DATABASE_DRIVER` | `jdbcDriverClass` in the profile | yes |
| `DATATYPE_STANDARD` | `SiteNameTypePreference` and `dataTypeStdPreference` | yes |
| `DB_AUTH` | `dbAuthFile` in the profile | no, defaults to `env-auth-source:username=DATABASE_USERNAME,password=DATABASE_PASSWORD` |
| `KEYGENERATOR` | available to templates as `.Env.KeyGenerator` | no |

## Output

* Application output is copied verbatim to **stdout**, the applications emit
  their own structured log records.
* appstarter's own messages go to **stderr**, timestamped
  (`2026/11/13 09:04:05 `), with lifecycle events as JSON:
  `{"app":"SPK/compproc","level":"info","msg":"Starting application"}`.
* Profiles and java options files are written into the working directory, which
  is where the Go version wrote them and where the images expect them.

## Building

```sh
cargo build --release     # target/release/appstarter
cargo test                # unit tests plus end to end tests against a fake java
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

The container image builds this crate in the `appstarter_builder` stage of the
top level `Dockerfile` and copies the binary to `/appstarter`, which is the
entrypoint of the `apps` image.

## Notes for anyone changing it

* Profiles are written by minijinja, but the template is a Go `text/template`
  file: `{{ .Env.DatabaseType }}`. Leading dots are stripped before rendering so
  existing district templates keep working, and rendering fails loudly on an
  unknown variable, the way Go did.
* `tests/data/utility.profile.golden` is the byte for byte output of the Go
  implementation for the test environment. If the rendered profile changes, that
  test tells you.
* `tests/end_to_end.rs` runs the real binary against a fake `java` on the PATH,
  so supervision, shutdown and file layout are covered without a database.
* As PID 1 in the container this process handles SIGTERM and SIGINT and asks its
  java children to stop, giving them 20 seconds before killing them.

## Differences from the Go version

Behaviour a reviewer should know about:

* A single application that fails to start no longer takes the container down;
  the others keep running, and it is still fatal when nothing is left running.
* Dead applications are pruned correctly. The Go loop removed the wrong entries
  while iterating, which could drop a running application or leave a dead one.
* Applications are started once. The Go version started the `ListCompApps`
  utility twice, once in the background and once to capture its output.
* Stopping the container sends SIGTERM to the java processes instead of letting
  them be killed when it goes away.
* Options and profile files are truncated before being rewritten. The Go version
  left the tail of the previous contents behind, which produced a corrupt
  `args-<OFFICE>-<app>` file whenever a shorter one was written over a longer
  one.
* The class path is sorted by file name, so it is reproducible between runs.
* Errors are reported as messages with a non-zero exit code instead of panics.
* `DB_AUTH` is not echoed into the startup summary; it is still written into the
  profiles, which is where OpenDCS reads it from.

Known limitations, unchanged from the Go version:

* Applications are not restarted. A computation application that exits is
  reported and forgotten; the container only comes back when the last one goes
  and the runtime replaces the pod.
* Grandchildren that java leaves behind are reparented to this process, which is
  PID 1, and are not reaped. Java applications normally wait for their own
  subprocesses, so this has not been a problem, but it is what a real init would
  handle.
