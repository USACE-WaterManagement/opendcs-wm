//! Output plumbing.
//!
//! The Go original used two channels and this port keeps them the same so the
//! container's existing log pipeline keeps working:
//!
//! * java output is copied verbatim, one line at a time, to **stdout**.
//! * appstarter's own diagnostics go to **stderr** with the timestamp prefix Go's
//!   `log` package adds (`2026/11/13 09:04:05 `). Lifecycle events of the
//!   controlled applications are emitted there as single line JSON objects:
//!   `{"app":"SPK/compproc","level":"info","msg":"Starting application"}`.

use std::fmt::Arguments;
use std::io::{LineWriter, Stderr, Stdout, Write};
use std::sync::{Mutex, OnceLock};

use serde::Serialize;
use time::OffsetDateTime;

#[derive(Serialize)]
struct LogMessage<'a> {
    app: &'a str,
    level: &'a str,
    msg: &'a str,
}

fn stderr() -> &'static Mutex<LineWriter<Stderr>> {
    static ERR: OnceLock<Mutex<LineWriter<Stderr>>> = OnceLock::new();
    ERR.get_or_init(|| Mutex::new(LineWriter::new(std::io::stderr())))
}

fn stdout() -> &'static Mutex<LineWriter<Stdout>> {
    static OUT: OnceLock<Mutex<LineWriter<Stdout>>> = OnceLock::new();
    OUT.get_or_init(|| Mutex::new(LineWriter::new(std::io::stdout())))
}

/// Go's `log` package writes its lines through to stderr, every line gets
/// another newline added, and lines from the log pumping threads interleave
/// with lines from the rest of the program. All of that is handled here.
fn write_line(sink: &Mutex<LineWriter<std::io::Stderr>>, line: &str) {
    let mut sink = sink.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    // A failed write to our own stderr/stdout is not something we can recover
    // from, and the Go version ignored it too.
    let _ = sink.write_all(line.as_bytes());
    let _ = sink.write_all(b"\n");
}

/// `YYYY-MM-DD HH:MM:SS ` prefix, the same shape Go's `log` package produced.
fn timestamp() -> String {
    let now = OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc());
    format!(
        "{:04}/{:02}/{:02} {:02}:{:02}:{:02} ",
        now.year(),
        u8::from(now.month()),
        now.day(),
        now.hour(),
        now.minute(),
        now.second()
    )
}

/// appstarter's own diagnostic message, replaces Go's `log.Printf`.
pub fn log(args: Arguments<'_>) {
    write_line(
        stderr(),
        &format!("{}{}", timestamp(), one_line(&args.to_string())),
    );
}

/// One line of output produced by a java process, replaces Go's
/// `fmt.Println(buffer.Text())`.
pub fn java_output(line: &str) {
    let mut out = stdout()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _ = out.write_all(line.as_bytes());
    let _ = out.write_all(b"\n");
}

/// Lifecycle event for one of the applications we supervise, replaces Go's
/// `log_message`.
pub fn app_event(level: &str, office: &str, app_name: &str, msg: &str) {
    let message = LogMessage {
        app: &format!("{office}/{app_name}"),
        level,
        msg,
    };
    match serde_json::to_string(&message) {
        Ok(json) => write_line(stderr(), &format!("{}{json}", timestamp())),
        Err(err) => log(format_args!(
            "{}: error creating log message: {err}",
            message.app
        )),
    }
}

/// The Go version passed messages that already ended in a newline to
/// `log.Printf`, which produced blank lines in the container log. Keep events
/// on a single line instead.
fn one_line(msg: &str) -> &str {
    msg.trim_end_matches(['\n', '\r'])
}

/// Best effort flush of everything we have buffered.
pub fn flush() {
    if let Ok(mut out) = stdout().lock() {
        let _ = out.flush();
    }
    if let Ok(mut err) = stderr().lock() {
        let _ = err.flush();
    }
}
