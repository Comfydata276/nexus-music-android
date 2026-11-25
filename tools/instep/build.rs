//! Takes the Windows app's own source, by path, and lays out the parts this tool runs so
//! they compile without the rest of the app (its window, its player). Nothing is rewritten:
//! what is compiled is the Windows text itself, cut at known lines. If a cut can't be found
//! (the Windows code has moved on), the build fails and says which.
//!
//! * `subsonic.rs`, `added.rs` and `plays.rs` whole.
//! * `sync.rs` from its top to where the part that drives the window begins (`impl
//!   Controller`): the record, the merges, and `exchange` with what it calls.
//! * From `config.rs` only `ListPrefs`, `Played` and `write_atomically`.
//!
//! From the release after 0.40.3 the record, the merges and what a server's answers are read into are in the
//! Windows app's library (`core/`, beside `src/`), which this tool depends on as the app
//! does (Cargo.toml here); the files above name them from there. Then `sync.rs` above its
//! cut is `exchange` and what it calls, and `ListPrefs` is the library's. A Windows checkout
//! from before the library is still compiled as it was (`split` below says which it is).
//!
//! From the version after 0.40.4 the exchange itself is decided in the library
//! (`nexus_core::step`), and `sync.rs` above its cut is the app's asking of the server:
//! `look` and `send`. Such a checkout is "stepped" (the `stepped` cfg, which main.rs goes
//! by): it can play a whole household (`instep household`), and has no `pc` mode of its own,
//! since the PC it would play is the code under test. A checkout at 0.40.4 or before has
//! `pc`, and is what the household's older PC is run from.

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

    // Whether the Windows source has its library beside it, holding the record and merges.
    let core = src.join("../core/src");
    let split = core.join("sync.rs").is_file();
    if split {
        // That it still holds what this tool is here to run.
        let library = read("../core/src/sync.rs") + &read("../core/src/notes.rs");
        for needed in ["pub fn merge(", "pub fn merge_history(", "pub fn merge_plays(", "fn merge_sources(", "pub struct Shared", "pub struct ListPrefs", "pub fn merge_notes("] {
            assert!(library.contains(needed), "core/src/sync.rs and notes.rs: `{needed}` is no longer there");
        }
    }

    for whole in ["subsonic.rs", "plays.rs"] {
        write(&out, whole, &without_inner_docs(&read(whole)));
    }
    // added.rs names the library's `merge_notes` only for its own tests: the tool's way in.
    // (The file names it itself only under `cfg(test)`, and this program is never built as a
    // test: see `test = false` in Cargo.toml.)
    let door = if split { "\n/// (The tool's.) The library's merge of two devices' notes.\npub use nexus_core::notes::merge_notes;\n" } else { "" };
    write(&out, "added.rs", &(without_inner_docs(&read("added.rs")) + door));

    // sync.rs: everything before the part that needs the window.
    let sync = read("sync.rs");
    let end = sync.find("\nimpl Controller {").expect("sync.rs: `impl Controller {` not found");
    let head: String = without_inner_docs(&sync[..end])
        .lines()
        // The window and the controller aren't here, and nothing above the cut uses them.
        .filter(|line| !line.starts_with("use slint") && !line.starts_with("use crate::controller"))
        // What sync.rs takes from the library for its own use, the tool reaches through it
        // (`sync::merge`, `sync::Shared`): the same items, made visible, nothing changed.
        .map(|line| if split && line.starts_with("use nexus_core::sync::{") { format!("pub {line}\n") } else { format!("{line}\n") })
        .collect();
    // Whether the exchange is the library's (see the top).
    let stepped = head.contains("pub fn look(");
    println!("cargo::rustc-check-cfg=cfg(stepped)");
    if stepped {
        println!("cargo::rustc-cfg=stepped");
    }
    let above: &[&str] = if stepped {
        &["pub fn look(", "pub fn send(", "pub struct Holds", "use nexus_core::sync::{"]
    } else if split {
        &["fn exchange(", "fn find_or_create(", "pub use nexus_core::sync::{", "merge, merge_history, merge_plays"]
    } else {
        &["pub fn merge(", "pub fn merge_history(", "pub fn merge_plays(", "fn merge_sources(", "fn exchange(", "fn find_or_create(", "pub struct Shared"]
    };
    for needed in above {
        assert!(head.contains(needed), "sync.rs: `{needed}` is no longer above `impl Controller`");
    }
    // The tool's way in to what is private there. Added lines, not changed ones: they call
    // the Windows functions as they are.
    let stepped_doors = r#"
/// (The tool's.) What the tool names through this module, from the library where it now is.
pub use nexus_core::sync::{LONG_AGO, Shared, VERSION, merge, merge_history};

/// (The tool's.) Windows' own limits and layout number.
pub const TOOL_LIMITS: (u32, usize, usize, usize, usize, i64) = (VERSION, HISTORY_LENGTH, PLAYS_SHARED, nexus_core::handover::QUEUE_BEHIND, nexus_core::handover::QUEUE_AHEAD, LONG_AGO);
"#;
    let doors = r#"
/// (The tool's.) Windows' `exchange`, with what it came back with.
pub fn tool_exchange(client: &Client, known: Option<String>, local: Shared) -> ApiResult<(String, Shared)> {
    exchange(client, known, local, HashMap::new()).map(|outcome| (outcome.playlist, outcome.shared))
}

/// (The tool's.) Windows' own limits and layout number.
pub const TOOL_LIMITS: (u32, usize, usize, usize, usize, i64) = (VERSION, HISTORY_LENGTH, PLAYS_SHARED, QUEUE_BEHIND, QUEUE_AHEAD, LONG_AGO);
"#;
    write(&out, "sync.rs", &(head + if stepped { stepped_doors } else { doors }));

    // config.rs: three items.
    let config = read("config.rs");
    let mut parts = String::from("use std::fs;\nuse std::path::Path;\nuse serde::{Deserialize, Serialize};\nuse crate::subsonic::Song;\n\n");
    if split {
        parts.push_str("pub use nexus_core::sync::ListPrefs;\n\n");
    } else {
        // Nothing of the stand-in library is used; this says so, for Cargo's sake.
        parts.push_str("use nexus_core as _;\n\n");
    }
    let wanted: &[&str] = if split { &["pub struct Played {", "pub fn write_atomically("] } else { &["pub struct ListPrefs {", "pub struct Played {", "pub fn write_atomically("] };
    for marker in wanted {
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
