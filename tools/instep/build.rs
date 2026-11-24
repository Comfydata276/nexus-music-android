//! Takes the Windows app's own source, by path, and lays out the parts this tool runs so
//! they compile without the rest of the app (its window, its player). Nothing is rewritten:
//! what is compiled is the Windows text itself, cut at known lines. If a cut can't be found
//! (the Windows code has moved on), the build fails and says which.
//!
//! * `subsonic.rs`, `added.rs` and `plays.rs` whole.
//! * `sync.rs` from its top to where the part that drives the window begins (`impl
//!   Controller`): the record, the merges, and `exchange` with what it calls.
//! * From `config.rs` only `ListPrefs`, `Played` and `write_atomically`.

use std::path::{Path, PathBuf};

fn main() {
    let src = PathBuf::from(std::env::var("NEXUS_WINDOWS_SRC").unwrap_or_else(|_| "/win/src".into()));
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    println!("cargo:rerun-if-env-changed=NEXUS_WINDOWS_SRC");
    let read = |name: &str| {
        let path = src.join(name);
        println!("cargo:rerun-if-changed={}", path.display());
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("can't read the Windows app's {}: {e}", path.display()))
    };

    for whole in ["subsonic.rs", "added.rs", "plays.rs"] {
        write(&out, whole, &without_inner_docs(&read(whole)));
    }

    // sync.rs: everything before the part that needs the window.
    let sync = read("sync.rs");
    let end = sync.find("\nimpl Controller {").expect("sync.rs: `impl Controller {` not found");
    let head: String = without_inner_docs(&sync[..end])
        .lines()
        // The window and the controller aren't here, and nothing above the cut uses them.
        .filter(|line| !line.starts_with("use slint") && !line.starts_with("use crate::controller"))
        .map(|line| format!("{line}\n"))
        .collect();
    for needed in ["pub fn merge(", "pub fn merge_history(", "pub fn merge_plays(", "fn merge_sources(", "fn exchange(", "fn find_or_create(", "pub struct Shared"] {
        assert!(head.contains(needed), "sync.rs: `{needed}` is no longer above `impl Controller`");
    }
    // The tool's way in to what is private there. Added lines, not changed ones: they call
    // the Windows functions as they are.
    let doors = r#"
/// (The tool's.) Windows' `exchange`, with what it came back with.
pub fn tool_exchange(client: &Client, known: Option<String>, local: Shared) -> ApiResult<(String, Shared)> {
    exchange(client, known, local, HashMap::new()).map(|outcome| (outcome.playlist, outcome.shared))
}

/// (The tool's.) Windows' own limits and layout number.
pub const TOOL_LIMITS: (u32, usize, usize, usize, usize, i64) = (VERSION, HISTORY_LENGTH, PLAYS_SHARED, QUEUE_BEHIND, QUEUE_AHEAD, LONG_AGO);
"#;
    write(&out, "sync.rs", &(head + doors));

    // config.rs: three items.
    let config = read("config.rs");
    let mut parts = String::from("use std::fs;\nuse std::path::Path;\nuse serde::{Deserialize, Serialize};\nuse crate::subsonic::Song;\n\n");
    for marker in ["pub struct ListPrefs {", "pub struct Played {", "pub fn write_atomically("] {
        parts.push_str(&item(&config, marker));
        parts.push('\n');
    }
    write(&out, "config.rs", &parts);
}

fn write(out: &Path, name: &str, text: &str) {
    std::fs::write(out.join(name), text).unwrap();
}

/// A file's text without its `//!` lines, which can't sit inside the module it is put in.
fn without_inner_docs(text: &str) -> String {
    text.lines().filter(|line| !line.starts_with("//!")).map(|line| format!("{line}\n")).collect()
}

/// One item of a file: the line that begins with `marker`, the comments and attributes
/// straight above it, and everything down to the `}` that closes it at the left margin.
fn item(text: &str, marker: &str) -> String {
    let lines: Vec<&str> = text.lines().collect();
    let at = lines.iter().position(|line| line.starts_with(marker)).unwrap_or_else(|| panic!("config.rs: `{marker}` not found"));
    let mut from = at;
    while from > 0 && (lines[from - 1].starts_with("///") || lines[from - 1].starts_with("#[")) {
        from -= 1;
    }
    let to = (at..lines.len()).find(|&i| lines[i] == "}").unwrap_or_else(|| panic!("config.rs: the end of `{marker}` not found"));
    lines[from..=to].iter().map(|line| format!("{line}\n")).collect()
}
