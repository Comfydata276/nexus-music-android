//! The Windows app's code for keeping devices in step, run here.
//!
//!   instep cases <file>      writes what Windows' `merge`, `merge_history`, `merge_plays`,
//!                            `merge_notes` and serde make of a list of inputs: the cases the
//!                            phone's Kotlin is held to (app/src/test/resources/instep-cases.json)
//!   instep reads <file>      the other way: reads texts the phone's Kotlin wrote, and says
//!                            whether Windows reads each back to the record it was written from
//!   instep pc ...            plays the PC: Windows' own `exchange`, against a server
//!   instep peek ...          the sync playlist and its comment, byte for byte, changing nothing
//!   instep plant ...         puts a text of your choosing in the comment (an unreadable one,
//!                            a newer layout), or makes a second sync playlist
//!   instep queue-save / queue-get    the server's saved queue, as Windows saves and reads it
//!
//! The modules below are the Windows app's own files, compiled from its source by path (see
//! build.rs). Nothing of them is copied into this repository.

#![allow(dead_code, unused_imports)]

mod subsonic {
    include!(concat!(env!("OUT_DIR"), "/subsonic.rs"));
}
mod config {
    include!(concat!(env!("OUT_DIR"), "/config.rs"));
}
mod added {
    include!(concat!(env!("OUT_DIR"), "/added.rs"));
}
mod plays {
    include!(concat!(env!("OUT_DIR"), "/plays.rs"));
}
mod sync {
    include!(concat!(env!("OUT_DIR"), "/sync.rs"));
}

use serde_json::{Value, json};
use sync::Shared;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = args.first().map(String::as_str).unwrap_or("");
    let rest = &args[args.len().min(1)..];
    let status = match mode {
        "cases" => cases(rest.first().expect("the file to write")),
        "reads" => reads(rest.first().expect("the file to read")),
        "pc" => pc(&Options::of(rest)),
        "peek" => peek(&Options::of(rest)),
        "plant" => plant(&Options::of(rest)),
        "queue-save" => queue_save(&Options::of(rest)),
        "queue-get" => queue_get(&Options::of(rest)),
        _ => {
            eprintln!("usage: instep cases <file> | reads <file> | pc | peek | plant | queue-save | queue-get  (see tools/instep/README.md)");
            2
        }
    };
    std::process::exit(status);
}

// ---- the cases ------------------------------------------------------------------------------

/// The time the cases are merged at.
const NOW: i64 = 1_795_000_000;
const KEEP_REMOVED: i64 = 90 * 24 * 3600;

fn record(text: &str) -> Shared {
    serde_json::from_str(text).unwrap_or_else(|e| panic!("a case's input isn't a record: {e}\n{text}"))
}

fn cases(path: &str) -> i32 {
    let (version, history_length, plays_shared, queue_behind, queue_ahead, long_ago) = sync::TOOL_LIMITS;
    let mut out = serde_json::Map::new();
    out.insert("about".into(), json!("Written by tools/instep from the Windows app's own source (src/sync.rs, src/added.rs, src/config.rs). Not edited by hand: run tools/instep/run cases to write it again."));
    out.insert("limits".into(), json!({
        "version": version, "history_length": history_length, "plays_shared": plays_shared,
        "queue_behind": queue_behind, "queue_ahead": queue_ahead, "long_ago": long_ago,
        "keep_removed": KEEP_REMOVED, "playlist_name": sync::PLAYLIST_NAME, "now": NOW,
    }));

    // What serde makes of a text: the record, or nothing (which Windows takes for "nothing
    // shared yet", and writes over).
    let reads: Vec<Value> = read_texts()
        .into_iter()
        .map(|(name, text)| {
            let read = serde_json::from_str::<Shared>(&text);
            json!({ "name": name, "text": text, "record": read.as_ref().ok().map(|shared| serde_json::to_value(shared).unwrap()), "error": read.err().map(|e| e.to_string()) })
        })
        .collect();
    out.insert("reads".into(), reads.into());

    // What serde writes for a record, byte for byte. Only records with at most one entry in
    // each map: Rust writes a map's entries in no particular order.
    let writes: Vec<Value> = write_records()
        .into_iter()
        .map(|(name, text)| {
            let shared = record(text);
            json!({ "name": name, "record": serde_json::to_value(&shared).unwrap(), "text": serde_json::to_string(&shared).unwrap() })
        })
        .collect();
    out.insert("writes".into(), writes.into());

    let merges: Vec<Value> = merge_pairs()
        .into_iter()
        .map(|(name, a, b, now)| {
            let (a, b) = (record(&a), record(&b));
            let merged = sync::merge(&a, &b, now);
            let other = sync::merge(&b, &a, now);
            json!({
                "name": name, "now": now,
                "a": serde_json::to_value(&a).unwrap(), "b": serde_json::to_value(&b).unwrap(),
                "merged": serde_json::to_value(&merged).unwrap(),
                "merged_other_way": serde_json::to_value(&other).unwrap(),
                // What `exchange` asks before writing: does merging this device's record
                // (a) into the server's (b) add anything?
                "writes_back": merged != b,
                "same_both_ways": merged == other,
            })
        })
        .collect();
    out.insert("merges".into(), merges.into());

    let histories: Vec<Value> = play_lists()
        .into_iter()
        .map(|(name, a, b)| {
            json!({
                "name": name, "a": a, "b": b,
                "history": sync::merge_history(&a, &b), "history_other_way": sync::merge_history(&b, &a),
                "plays": sync::merge_plays(&a, &b), "plays_other_way": sync::merge_plays(&b, &a),
            })
        })
        .collect();
    out.insert("plays".into(), histories.into());

    let notes: Vec<Value> = note_pairs()
        .into_iter()
        .map(|(name, a, b, now)| {
            let a: added::Notes = serde_json::from_str(a).unwrap();
            let b: added::Notes = serde_json::from_str(b).unwrap();
            json!({ "name": name, "now": now, "a": a, "b": b, "merged": added::merge_notes(&a, &b, now), "merged_other_way": added::merge_notes(&b, &a, now) })
        })
        .collect();
    out.insert("notes".into(), notes.into());

    // One section to a line: small enough to keep in the repository, and a change to one
    // section shows as one line changed.
    let lines: Vec<String> = out.iter().map(|(name, value)| format!("{}:{}", serde_json::to_string(name).unwrap(), serde_json::to_string(value).unwrap())).collect();
    std::fs::write(path, format!("{{\n{}\n}}\n", lines.join(",\n"))).unwrap();
    println!("wrote {path}");
    0
}

/// Texts a comment might hold, and what is awkward about each.
fn read_texts() -> Vec<(&'static str, String)> {
    let deep = |levels: usize| format!("{{\"x\":{}1{}}}", "[".repeat(levels), "]".repeat(levels));
    let mut texts: Vec<(&'static str, String)> = [
        ("empty text", ""),
        ("only spaces", "  \n\t "),
        ("a word", "hello"),
        ("a sentence someone typed", "My sync playlist, do not delete"),
        ("null", "null"),
        ("true", "true"),
        ("a number", "1"),
        ("a string", "\"nexus\""),
        ("an empty record", "{}"),
        ("an empty record with spaces", " \r\n{ }\t"),
        ("the layout alone", r#"{"nexus_music_sync":1}"#),
        ("a later layout", r#"{"nexus_music_sync":2,"pins":["p"],"pins_at":5}"#),
        ("a much later layout", r#"{"nexus_music_sync":4294967295}"#),
        ("a layout past what the number holds", r#"{"nexus_music_sync":4294967296}"#),
        ("a layout below nothing", r#"{"nexus_music_sync":-1}"#),
        ("a layout as text", r#"{"nexus_music_sync":"1"}"#),
        ("a layout with a fraction", r#"{"nexus_music_sync":1.0}"#),
        ("a missing field", r#"{"nexus_music_sync":1,"pins":["a"],"theme":"Ember","theme_at":7}"#),
        ("an extra field", r#"{"nexus_music_sync":1,"pins":["a"],"pins_at":3,"devices":{"pc":[1,2,{"x":null}]},"note":"later"}"#),
        ("an extra field inside a list's choices", r#"{"lists":{"playlist:x":{"shuffle":true,"at":9,"columns":[1,2]}}}"#),
        ("an extra field inside a note", r#"{"added":{"p":{"s":{"times":[4],"at":4,"by":"pc"}}}}"#),
        ("a field twice", r#"{"pins_at":1,"pins_at":2}"#),
        ("an unknown field twice", r#"{"later":1,"later":2,"pins_at":2}"#),
        ("a list's key twice", r#"{"lists":{"liked":{"at":1},"liked":{"at":2}}}"#),
        ("a field as null", r#"{"pins":null}"#),
        ("a time as null", r#"{"pins_at":null}"#),
        ("an unknown field as null", r#"{"later":null,"pins_at":4}"#),
        ("a time as text", r#"{"pins_at":"12"}"#),
        ("a time with a fraction", r#"{"pins_at":12.0}"#),
        ("a time with an exponent", r#"{"pins_at":1e3}"#),
        ("a time of minus nothing", r#"{"pins_at":-0}"#),
        ("a time with a leading nought", r#"{"pins_at":012}"#),
        ("a time before 1970", r#"{"pins_at":-5,"theme_at":-9223372036854775808}"#),
        ("the latest time there is", r#"{"pins_at":9223372036854775807}"#),
        ("a time past the latest there is", r#"{"pins_at":9223372036854775808}"#),
        ("a time far too long", r#"{"pins_at":123456789012345678901234567890}"#),
        ("a sort past any the app has", r#"{"lists":{"songs":{"sort":18446744073709551615,"at":2}}}"#),
        ("a sort past what the number holds", r#"{"lists":{"songs":{"sort":18446744073709551616,"at":2}}}"#),
        ("a sort below nothing", r#"{"lists":{"songs":{"sort":-1,"at":2}}}"#),
        ("shuffle as a number", r#"{"lists":{"songs":{"shuffle":1,"at":2}}}"#),
        ("pins that aren't text", r#"{"pins":[1,2]}"#),
        ("pins as one text", r#"{"pins":"abc"}"#),
        ("a theme that isn't text", r#"{"theme":5}"#),
        ("lists as a list", r#"{"lists":[]}"#),
        ("a play with three parts in history", r#"{"history":[["a",1,"playlist:p"]]}"#),
        ("a play with one part", r#"{"plays":[["a"]]}"#),
        ("a play as an object", r#"{"plays":[{"song":"a","at":1}]}"#),
        ("a play the wrong way round", r#"{"plays":[[1,"a"]]}"#),
        ("a source with two parts", r#"{"play_sources":[["a",1]]}"#),
        ("a source with four parts", r#"{"play_sources":[["a",1,"playlist:p",2]]}"#),
        ("a record as a list", "[]"),
        ("a record as a list, part filled", r#"[1,["p","q"],40,"Ocean"]"#),
        ("a record as a list, all of it", r#"[1,["p"],40,"Ocean",41,{"liked":[true,5,true,true,9]},{"p":{"s":[[3,4],5]}},[["a",9]],[["a",9]],[["a",9,"playlist:p"]]]"#),
        ("a record as a list, one too many", r#"[1,["p"],40,"Ocean",41,{},{},[],[],[],0]"#),
        ("a list's choices as a list, too many", r#"{"lists":{"liked":[true,5,true,true,9,1]}}"#),
        ("a note as a list, part filled", r#"{"added":{"p":{"s":[[3]]}}}"#),
        ("something after the record", r#"{"pins_at":1} x"#),
        ("two records", r#"{"pins_at":1}{"pins_at":2}"#),
        ("a comma too many", r#"{"pins":["a",],"pins_at":1}"#),
        ("a comma too many at the end", r#"{"pins_at":1,}"#),
        ("cut short", r#"{"nexus_music_sync":1,"pins":["a","b"#),
        ("single quotes", "{'pins_at':1}"),
        ("a name without quotes", "{pins_at:1}"),
        ("a comment in it", "{\"pins_at\":1 /* mine */}"),
        ("text with escapes", r#"{"pins":["a\"b\\c\/d\b\f\n\r\té中"],"theme":"Amber"}"#),
        ("an escape there isn't", r#"{"theme":"\x41"}"#),
        ("a character outside the basic plane, written out", "{\"pins\":[\"\u{1F3B5}\",\"\u{10000}\",\"\u{FFFF}\"],\"theme\":\"\u{1F3B5}\"}"),
        ("the same as a pair of escapes", r#"{"pins":["🎵"]}"#),
        ("half a pair of escapes", r#"{"pins":["\ud83c"]}"#),
        ("the second half alone", r#"{"pins":["\udfb5"]}"#),
        ("half a pair, then a letter", r#"{"pins":["\ud83cx"]}"#),
        ("a line break inside text", "{\"theme\":\"a\nb\"}"),
        ("a tab inside text", "{\"theme\":\"a\tb\"}"),
        ("a nought byte written out", r#"{"theme":"a\u0000b"}"#),
        ("the byte order mark first", "\u{FEFF}{\"pins_at\":1}"),
        ("a space that isn't one of the four", "\u{00A0}{\"pins_at\":1}"),
        ("an unknown number of any size", r#"{"later":1e999,"more":-0.0,"pins_at":3}"#),
        ("an unknown number that isn't one", r#"{"later":01,"pins_at":3}"#),
        ("an empty name", r#"{"":5,"pins_at":3}"#),
        ("empty keys and ids", r#"{"pins":[""],"lists":{"":{"at":1}},"added":{"":{"":{"times":[],"at":0}}},"history":[["",0]]}"#),
        ("capitals in a name", r#"{"Pins_at":5,"PINS":["a"]}"#),
        ("true written large", r#"{"lists":{"liked":{"shuffle":True}}}"#),
        // Digits that are digits to Java and not to serde: full-width, and another script's.
        ("an escape in full-width digits", "{\"theme\":\"\\u\u{FF10}\u{FF10}\u{FF16}\u{FF11}\"}"),
        ("an escape in full-width digits, in a field nobody knows", "{\"later\":\"\\u\u{FF10}\u{FF10}\u{FF16}\u{FF11}\",\"pins_at\":3}"),
        ("an escape in full-width digits, in a name", "{\"\\u\u{FF10}\u{FF10}\u{FF16}\u{FF11}\":1,\"pins_at\":3}"),
        ("an escape in another script's digits", "{\"theme\":\"\\u\u{0660}\u{0660}\u{0666}\u{0661}\"}"),
        ("an escape with a full-width letter", "{\"theme\":\"\\u00\u{FF46}1\"}"),
        ("an escape in capitals", r#"{"theme":"\u00E9\u00e9"}"#),
        ("a field nobody knows inside a note inside a list as a list", r#"{"added":{"p":{"s":[[3],4]}},"lists":{"k":{"at":2,"more":{"deep":[1,{"x":"\u0041"}]}}}}"#),
    ]
    .into_iter()
    .map(|(name, text)| (name, text.to_string()))
    .collect();
    // Windows gives up on text nested more than 127 deep, in a field it reads...
    texts.push(("a known field nested as deep as serde goes", format!("{{\"pins\":{}", "[".repeat(200))));
    // ...but passes over an unknown field however deep it goes.
    texts.push(("an unknown field nested 127 deep", deep(126)));
    texts.push(("an unknown field nested 200 deep", deep(200)));
    texts.push(("an unknown field nested 3000 deep", deep(3000)));
    texts
}

/// Records whose text, as serde writes it, has only one possible order.
fn write_records() -> Vec<(&'static str, &'static str)> {
    vec![
        ("nothing at all", "{}"),
        ("the layout alone", r#"{"nexus_music_sync":1}"#),
        (
            "one of everything",
            r#"{"nexus_music_sync":1,"pins":["p1","p2"],"pins_at":1700000000,"theme":"Ember","theme_at":1700000001,
                "lists":{"playlist:abc":{"shuffle":true,"sort":5,"descending":true,"sort_chosen":true,"at":1700000002}},
                "added":{"abc":{"s1":{"times":[0,1700000003],"at":1700000003}}},
                "history":[["s2",1700000005],["s1",1700000004]],
                "plays":[["s2",1700000005],["s1",1700000004],["s1",1700000004]],
                "play_sources":[["s2",1700000005,"playlist:abc"]]}"#,
        ),
        (
            "text that needs escaping",
            "{\"pins\":[\"a\\\"b\\\\c/d\",\"line\\nbreak\\ttab\\r\\b\\f\",\"\\u0000\\u0001\\u001f\\u007f\",\"caf\u{e9} \u{4e2d}\u{6587} \u{1F3B5} \u{10000} \u{FFFF}\",\"\"],\"theme\":\"<>&'\u{2028}\u{2029}\"}",
        ),
        ("the furthest numbers", r#"{"nexus_music_sync":4294967295,"pins_at":-9223372036854775808,"theme_at":9223372036854775807,"lists":{"k":{"sort":18446744073709551615,"at":-1}},"added":{"p":{"s":{"times":[-9223372036854775808,0,9223372036854775807],"at":1}}},"history":[["a",-1]]}"#),
        ("empty keys and ids", r#"{"pins":[""],"lists":{"":{"at":1}},"added":{"":{"":{"times":[],"at":0}}},"history":[["",0]],"plays":[["",0]],"play_sources":[["",0,""]]}"#),
    ]
}

fn plays_text(plays: &[(String, i64)]) -> String {
    serde_json::to_string(plays).unwrap()
}

/// Pairs of records to merge: (what it is about, this device's, the server's, now).
fn merge_pairs() -> Vec<(&'static str, String, String, i64)> {
    let mut pairs: Vec<(&'static str, String, String, i64)> = [
        ("two empty records", "{}", "{}"),
        ("an empty record and a full one", "{}", r#"{"nexus_music_sync":1,"pins":["p"],"pins_at":10,"theme":"Ocean","theme_at":11,"lists":{"liked":{"shuffle":true,"at":12}},"added":{"p":{"s":{"times":[13],"at":13}}},"history":[["a",14]],"plays":[["a",14]],"play_sources":[["a",14,"playlist:p"]]}"#),
        ("a phone with nothing to say and a PC's record: nothing to write", r#"{"nexus_music_sync":1}"#, r#"{"nexus_music_sync":1,"pins":["p"],"pins_at":10,"theme":"Ocean","theme_at":11,"lists":{"liked":{"shuffle":true,"at":12}},"added":{"p":{"s":{"times":[13],"at":13}}},"history":[["a",14]],"plays":[["a",14]],"play_sources":[["a",14,"playlist:p"]]}"#),
        ("the server's layout number is nought", r#"{"nexus_music_sync":1}"#, r#"{"pins":["p"],"pins_at":10}"#),
        ("Windows' own test", r#"{"pins":["p1"],"pins_at":100,"theme":"Ember","theme_at":50,"lists":{"playlist:x":{"shuffle":true,"at":70},"album:y":{"sort":1,"at":10}},"added":{"x":{"s":{"times":[5],"at":5}}}}"#, r#"{"pins":["p2","p3"],"pins_at":90,"theme":"Ocean","theme_at":60,"lists":{"playlist:x":{"shuffle":false,"at":80}},"added":{"x":{"s":{"times":[],"at":9}}}}"#),
        ("pins as old as each other", r#"{"pins":["a"],"pins_at":1}"#, r#"{"pins":["b"],"pins_at":1}"#),
        ("pins as old as each other, one a longer list", r#"{"pins":["a","b"],"pins_at":7}"#, r#"{"pins":["a"],"pins_at":7}"#),
        ("pins as old as each other, one empty", r#"{"pins":[],"pins_at":7}"#, r#"{"pins":["a"],"pins_at":7}"#),
        ("no pins, stamped later, beats pins", r#"{"pins":[],"pins_at":8}"#, r#"{"pins":["a","b"],"pins_at":7}"#),
        ("no pins and no time loses to pins long ago", r#"{"pins":[],"pins_at":0}"#, r#"{"pins":["a"],"pins_at":1}"#),
        ("no pins and no time, against pins with no time", r#"{"pins":[],"pins_at":0}"#, r#"{"pins":["a"],"pins_at":0}"#),
        ("pins in another order are other pins", r#"{"pins":["a","b"],"pins_at":5}"#, r#"{"pins":["b","a"],"pins_at":5}"#),
        ("the default theme with no time loses to one chosen long ago", r#"{"theme":"Violet","theme_at":0}"#, r#"{"theme":"Ember","theme_at":1}"#),
        ("the default theme with no time, both sides", r#"{"theme":"Violet","theme_at":0}"#, r#"{"theme":"Violet","theme_at":0}"#),
        ("no theme against the default with no time", r#"{"theme":"","theme_at":0}"#, r#"{"theme":"Violet","theme_at":0}"#),
        ("the default with no time against no theme", r#"{"theme":"Violet","theme_at":0}"#, r#"{"theme":"","theme_at":0}"#),
        ("themes as old as each other", r#"{"theme":"Ember","theme_at":20}"#, r#"{"theme":"Ocean","theme_at":20}"#),
        ("a theme the app doesn't have still wins by time", r#"{"theme":"Ember","theme_at":20}"#, r#"{"theme":"Sunset","theme_at":21}"#),
        ("the same list, the server's newer", r#"{"lists":{"playlist:x":{"shuffle":true,"sort":2,"descending":true,"sort_chosen":true,"at":5}}}"#, r#"{"lists":{"playlist:x":{"at":6}}}"#),
        ("the same list, equal times: shuffle decides", r#"{"lists":{"k":{"shuffle":false,"sort":9,"descending":true,"sort_chosen":true,"at":5}}}"#, r#"{"lists":{"k":{"shuffle":true,"at":5}}}"#),
        ("the same list, equal times: then the sort", r#"{"lists":{"k":{"sort":2,"descending":true,"sort_chosen":true,"at":5}}}"#, r#"{"lists":{"k":{"sort":3,"at":5}}}"#),
        ("the same list, equal times: then which way round", r#"{"lists":{"k":{"sort":3,"descending":false,"sort_chosen":true,"at":5}}}"#, r#"{"lists":{"k":{"sort":3,"descending":true,"at":5}}}"#),
        ("the same list, equal times: then whether it was chosen", r#"{"lists":{"k":{"sort":3,"sort_chosen":false,"at":5}}}"#, r#"{"lists":{"k":{"sort":3,"sort_chosen":true,"at":5}}}"#),
        ("a sort past any the app has, and one past what a small number holds", r#"{"lists":{"k":{"sort":4294967296,"at":5},"j":{"sort":2,"at":6}}}"#, r#"{"lists":{"k":{"sort":18446744073709551615,"at":5},"j":{"sort":7,"at":5}}}"#),
        ("lists only one side has", r#"{"lists":{"liked":{"shuffle":true,"at":3},"songs":{"sort":1,"at":0}}}"#, r#"{"lists":{"album:1":{"at":0},"mix:top":{"shuffle":true,"at":1}}}"#),
        ("keys outside the basic plane", "{\"pins\":[\"\u{FFFF}\"],\"pins_at\":4,\"theme\":\"\u{FFFF}\",\"theme_at\":4}", "{\"pins\":[\"\u{10000}\"],\"pins_at\":4,\"theme\":\"\u{10000}\",\"theme_at\":4}"),
        ("text that sorts one way by bytes and another by units", "{\"pins\":[\"a\u{E000}\"],\"pins_at\":4,\"theme\":\"\u{FF5E}\",\"theme_at\":4}", "{\"pins\":[\"a\u{1F3B5}\"],\"pins_at\":4,\"theme\":\"\u{1D11E}\",\"theme_at\":4}"),
        ("a pin that begins another", r#"{"pins":["ab"],"pins_at":4}"#, r#"{"pins":["a","z"],"pins_at":4}"#),
        ("capitals sort before small letters", r#"{"theme":"ember","theme_at":4}"#, r#"{"theme":"Ocean","theme_at":4}"#),
        ("the same play from two devices", r#"{"history":[["a",30],["b",20]],"plays":[["a",30],["b",20]],"play_sources":[["a",30,"playlist:p"]]}"#, r#"{"history":[["a",30],["c",25]],"plays":[["a",30],["c",25]],"play_sources":[["a",30,"playlist:p"],["c",25,"playlist:q"]]}"#),
        ("the same play said to be from two playlists", r#"{"plays":[["a",30]],"play_sources":[["a",30,"playlist:q"]]}"#, r#"{"plays":[["a",30]],"play_sources":[["a",30,"playlist:p"]]}"#),
        ("sources outside the basic plane", "{\"plays\":[[\"a\",30]],\"play_sources\":[[\"a\",30,\"playlist:\u{FFFF}\"]]}", "{\"plays\":[[\"a\",30]],\"play_sources\":[[\"a\",30,\"playlist:\u{10000}\"]]}"),
        ("a source for a play neither has", r#"{"plays":[["a",30]],"play_sources":[["zz",99,"playlist:p"],["a",31,"playlist:p"]]}"#, r#"{"plays":[["b",20]],"play_sources":[["b",20,"playlist:q"],["a",30,""]]}"#),
        ("plays at equal times", r#"{"history":[["b",50],["a",50],["c",50]],"plays":[["b",50],["a",50],["c",50]]}"#, r#"{"history":[["c",50],["d",50],["a",50]],"plays":[["c",50],["d",50],["a",50],["a",50]]}"#),
        ("the same song twice running, and again later", r#"{"history":[["a",60],["a",50],["b",40],["a",30]],"plays":[["a",60],["a",50],["b",40],["a",30]]}"#, r#"{"history":[["a",55],["a",35]],"plays":[["a",55],["a",35]]}"#),
        ("plays out of order on one side", r#"{"history":[["a",10],["b",40],["c",20]],"plays":[["a",10],["b",40],["c",20]]}"#, r#"{"history":[["d",30]],"plays":[["d",30]]}"#),
        ("plays before 1970 and at the furthest times", r#"{"history":[["a",-5],["b",9223372036854775807]],"plays":[["a",-5],["b",9223372036854775807]]}"#, r#"{"history":[["c",-9223372036854775808],["d",0]],"plays":[["c",-9223372036854775808],["d",0]]}"#),
        ("song ids outside the basic plane, at one time", "{\"history\":[[\"\u{FFFF}\",9],[\"\u{1F3B5}\",9],[\"z\",9]],\"plays\":[[\"\u{FFFF}\",9],[\"\u{1F3B5}\",9],[\"z\",9]]}", "{\"history\":[[\"\u{10000}\",9],[\"\u{E000}\",9]],\"plays\":[[\"\u{10000}\",9],[\"\u{E000}\",9]]}"),
        ("notes: the newer wins, per song", r#"{"added":{"p":{"s":{"times":[5],"at":5},"t":{"times":[7],"at":7}},"q":{"u":{"times":[1],"at":1}}}}"#, r#"{"added":{"p":{"s":{"times":[],"at":9},"t":{"times":[6],"at":6},"v":{"times":[2,3],"at":3}}}}"#),
        ("notes as old as each other that say different things", r#"{"added":{"p":{"s":{"times":[5],"at":5}}}}"#, r#"{"added":{"p":{"s":{"times":[4,5],"at":5}}}}"#),
        ("a note with no time on it", r#"{"added":{"p":{"s":{"times":[0],"at":0}}}}"#, r#"{"added":{"p":{"s":{"times":[3],"at":0}},"q":{}}}"#),
    ]
    .into_iter()
    .map(|(name, a, b)| (name, a.to_string(), b.to_string(), NOW))
    .collect();

    // Word that a song was removed, around the day it stops being passed on.
    let removed = |at: i64| format!(r#"{{"added":{{"p":{{"gone":{{"times":[],"at":{at}}},"kept":{{"times":[1],"at":1}}}},"q":{{"only":{{"times":[],"at":{at}}}}}}}}}"#);
    pairs.push(("a removal a day short of forgotten", removed(NOW - KEEP_REMOVED + 86_400), "{}".into(), NOW));
    pairs.push(("a removal a second short of forgotten", removed(NOW - KEEP_REMOVED + 1), "{}".into(), NOW));
    pairs.push(("a removal forgotten to the second", removed(NOW - KEEP_REMOVED), "{}".into(), NOW));
    pairs.push(("a removal long forgotten, on the server's side", "{}".into(), removed(5), NOW));
    pairs.push(("a removal dated after now", removed(NOW + 500), "{}".into(), NOW));

    // At the limits: a hundred in history, five hundred plays.
    let run = |from: i64, count: i64, step: i64, tag: &str| -> Vec<(String, i64)> { (0..count).map(|i| (format!("{tag}{}", i % 37), from - i * step)).collect() };
    let at_limits = |history: &[(String, i64)], plays: &[(String, i64)], sources: &[(String, i64, String)]| {
        format!(r#"{{"history":{},"plays":{},"play_sources":{}}}"#, plays_text(history), plays_text(plays), serde_json::to_string(sources).unwrap())
    };
    let (ha, hb) = (run(NOW - 10, 100, 60, "s"), run(NOW - 40, 100, 60, "t"));
    let (pa, pb) = (run(NOW - 10, 500, 60, "s"), run(NOW - 40, 500, 60, "t"));
    let from = |plays: &[(String, i64)], every: usize, list: &str| -> Vec<(String, i64, String)> { plays.iter().step_by(every).map(|(song, at)| (song.clone(), *at, format!("playlist:{list}"))).collect() };
    pairs.push(("at the limits, two devices that played in turn", at_limits(&ha, &pa, &from(&pa, 3, "one")), at_limits(&hb, &pb, &from(&pb, 4, "two")), NOW));
    pairs.push(("at the limits, the same on both", at_limits(&ha, &pa, &from(&pa, 3, "one")), at_limits(&ha, &pa, &from(&pa, 3, "one")), NOW));
    let (hc, pc) = (run(NOW - 10, 130, 60, "s"), run(NOW - 10, 620, 60, "s"));
    pairs.push(("past the limits on one side", at_limits(&hc, &pc, &from(&pc, 2, "one")), "{}".into(), NOW));
    pairs.push(("past the limits on the server's side", "{}".into(), at_limits(&hc, &pc, &from(&pc, 2, "one")), NOW));
    // The five hundredth and five hundred and first plays are at one time: which is kept?
    let mut tied: Vec<(String, i64)> = (0..499).map(|i| (format!("s{i}"), NOW - i)).collect();
    tied.extend([("b".to_string(), 5), ("a".to_string(), 5), ("c".to_string(), 5)]);
    pairs.push(("the last place among the plays, at one time", at_limits(&[], &tied, &[("a".into(), 5, "playlist:p".into()), ("b".into(), 5, "playlist:p".into())]), "{}".into(), NOW));
    pairs
}

/// Lists of plays to merge as history and as plays: (what it is about, one device's, the other's).
fn play_lists() -> Vec<(&'static str, Vec<(String, i64)>, Vec<(String, i64)>)> {
    let plays = |entries: &[(&str, i64)]| -> Vec<(String, i64)> { entries.iter().map(|(id, at)| (id.to_string(), *at)).collect() };
    vec![
        ("nothing", vec![], vec![]),
        ("one side only", plays(&[("a", 3), ("b", 2), ("a", 1)]), vec![]),
        ("Windows' history test", plays(&[("c", 30), ("a", 10)]), plays(&[("d", 40), ("b", 20), ("a", 10)])),
        ("Windows' history test: back to back", plays(&[("a", 12)]), plays(&[("a", 10)])),
        ("Windows' plays test", plays(&[("a", 30), ("a", 20), ("b", 10)]), plays(&[("c", 40), ("a", 20)])),
        ("equal times, by id", plays(&[("b", 5), ("B", 5), ("a", 5), ("", 5)]), plays(&[("ab", 5), ("a", 5), ("Z", 5)])),
        ("the same play three times over", plays(&[("a", 5), ("a", 5), ("a", 5)]), plays(&[("a", 5)])),
        ("one song with others between at one time", plays(&[("a", 9), ("b", 9), ("a", 8), ("a", 7), ("b", 7), ("a", 6)]), plays(&[("a", 9), ("c", 8)])),
        ("ids outside the basic plane at one time", plays(&[("\u{FFFF}", 1), ("\u{1F3B5}", 1), ("z", 1), ("\u{E000}", 1)]), plays(&[("\u{10000}", 1), ("\u{FF5E}", 1), ("\u{D7FF}", 1)])),
        ("ids that begin one another", plays(&[("ab", 2), ("a", 2), ("abc", 2)]), plays(&[("b", 2), ("", 2)])),
        ("times before 1970", plays(&[("a", -1), ("b", -2)]), plays(&[("c", 0), ("d", -9_223_372_036_854_775_808)])),
        ("more than a hundred, more than five hundred", (0..700).map(|i| (format!("s{}", i % 3), 10_000 - i)).collect(), (0..700).map(|i| (format!("t{}", i % 50), 10_000 - i)).collect()),
    ]
}

/// Notes to merge: (what it is about, one device's, the other's, now).
fn note_pairs() -> Vec<(&'static str, &'static str, &'static str, i64)> {
    vec![
        ("nothing", "{}", "{}", NOW),
        ("one side only", r#"{"p":{"s":{"times":[5],"at":5}}}"#, "{}", NOW),
        ("the newer note wins", r#"{"p":{"s":{"times":[5],"at":5}}}"#, r#"{"p":{"s":{"times":[6],"at":6}}}"#, NOW),
        ("as old as each other", r#"{"p":{"s":{"times":[5],"at":5}}}"#, r#"{"p":{"s":{"times":[4],"at":5}}}"#, NOW),
        ("an empty playlist's entry is dropped", r#"{"p":{},"q":{"s":{"times":[1],"at":1}}}"#, r#"{"r":{}}"#, NOW),
        ("a removal still passed on", r#"{"p":{"s":{"times":[],"at":1794999999}}}"#, r#"{"p":{"s":{"times":[5],"at":5}}}"#, NOW),
        ("a removal forgotten takes the playlist with it", r#"{"p":{"s":{"times":[],"at":5}}}"#, "{}", NOW),
        ("a removal forgotten leaves the rest", r#"{"p":{"s":{"times":[],"at":5},"t":{"times":[0,9],"at":9}}}"#, r#"{"p":{"s":{"times":[3],"at":4}}}"#, NOW),
        ("merged at the very start of time", r#"{"p":{"s":{"times":[],"at":5}}}"#, "{}", 0),
    ]
}

// ---- reading what the phone wrote -----------------------------------------------------------

/// `[{"name":…, "text":…, "record":{…}}, …]`: each text must read, with Windows' code, as the
/// record beside it, and survive being written by Windows and read again.
fn reads(path: &str) -> i32 {
    let list: Vec<Value> = serde_json::from_str(&std::fs::read_to_string(path).expect("the file")).expect("a list of cases");
    let mut failed = 0;
    for case in &list {
        let name = case["name"].as_str().unwrap_or("?");
        let text = case["text"].as_str().expect("text");
        let wanted: Shared = serde_json::from_value(case["record"].clone()).expect("record");
        let ok = match serde_json::from_str::<Shared>(text) {
            Ok(read) => read == wanted && serde_json::from_str::<Shared>(&serde_json::to_string(&read).unwrap()).unwrap() == wanted,
            Err(_) => false,
        };
        if !ok {
            failed += 1;
            println!("FAIL  {name}");
        }
    }
    println!("{} texts written by the phone, {} read back by Windows' code to the same record, {failed} not", list.len(), list.len() - failed);
    if failed == 0 { 0 } else { 1 }
}

// ---- playing the PC -------------------------------------------------------------------------

struct Options(Vec<(String, String)>);

impl Options {
    fn of(args: &[String]) -> Options {
        let mut found = Vec::new();
        let mut args = args.iter();
        while let Some(name) = args.next() {
            let name = name.strip_prefix("--").unwrap_or_else(|| panic!("expected an option, not {name}"));
            found.push((name.to_string(), args.next().unwrap_or_else(|| panic!("--{name} needs a value")).clone()));
        }
        Options(found)
    }

    fn get(&self, name: &str) -> Option<&str> {
        self.0.iter().find(|(key, _)| key == name).map(|(_, value)| value.as_str())
    }

    fn need(&self, name: &str) -> &str {
        self.get(name).unwrap_or_else(|| panic!("--{name} is needed"))
    }

    fn client(&self) -> subsonic::Client {
        subsonic::Client::new(self.need("server"), self.need("user"), self.need("password"))
    }
}

/// The sync playlists this account can see, as Windows looks for them (by name and owner),
/// lowest id first, each with its comment exactly as the server has it.
fn sync_playlists(client: &subsonic::Client) -> Vec<(String, String)> {
    let mine = client.username().to_lowercase();
    let mut ids: Vec<String> = client
        .playlists()
        .expect("the playlists")
        .into_iter()
        .filter(|p| p.name == sync::PLAYLIST_NAME && (p.owner.is_empty() || p.owner.to_lowercase() == mine))
        .map(|p| p.id)
        .collect();
    ids.sort();
    ids.into_iter().map(|id| { let comment = client.playlist(&id).expect("the playlist").comment; (id, comment) }).collect()
}

fn peek(options: &Options) -> i32 {
    let found = sync_playlists(&options.client());
    let first = found.first();
    println!("{}", json!({ "playlist": first.map(|(id, _)| id), "comment": first.map(|(_, comment)| comment), "sync_playlists": found.len() }));
    0
}

/// One exchange, exactly as the Windows app makes it: `exchange` with this PC's record
/// (`--state`, a file: read, and written back as what the PC then holds). Prints what the
/// server's comment was before and after.
fn pc(options: &Options) -> i32 {
    let client = options.client();
    let state = options.need("state");
    let local: Shared = match std::fs::read_to_string(state) {
        Ok(text) => serde_json::from_str(&text).expect("the PC's record"),
        Err(_) => Shared::default(),
    };
    let before = sync_playlists(&client).into_iter().next();
    let known = options.get("known").map(str::to_string).or_else(|| before.as_ref().map(|(id, _)| id.clone()));
    match sync::tool_exchange(&client, known, local.clone()) {
        Ok((playlist, shared)) => {
            let after = client.playlist(&playlist).expect("the playlist").comment;
            std::fs::write(state, serde_json::to_string_pretty(&shared).unwrap()).unwrap();
            let before = before.map(|(_, comment)| comment);
            println!("{}", json!({
                "playlist": playlist,
                "wrote": before.as_deref() != Some(after.as_str()),
                "comment_before": before,
                "comment_after": after,
                "holds": serde_json::to_value(&shared).unwrap(),
            }));
            0
        }
        Err(e) => {
            eprintln!("the exchange failed: {e}");
            1
        }
    }
}

/// Puts `--text` in the sync playlist's comment as it is (`--new yes` makes another sync
/// playlist first and puts it there), with Windows' own calls.
fn plant(options: &Options) -> i32 {
    let client = options.client();
    let id = if options.get("new").is_some() {
        client.create_playlist(sync::PLAYLIST_NAME, &[]).expect("a new playlist").id
    } else {
        match sync_playlists(&client).into_iter().next() {
            Some((id, _)) => id,
            None => client.create_playlist(sync::PLAYLIST_NAME, &[]).expect("a new playlist").id,
        }
    };
    if let Some(text) = options.get("text") {
        client.set_playlist_comment(&id, text).expect("the comment");
    }
    println!("{}", json!({ "playlist": id, "comment": client.playlist(&id).expect("the playlist").comment }));
    0
}

fn queue_save(options: &Options) -> i32 {
    let ids: Vec<String> = options.need("ids").split(',').map(str::to_string).collect();
    let position: u64 = options.get("position").unwrap_or("0").parse().expect("milliseconds");
    match options.client().save_play_queue(options.need("device"), &ids, options.need("current"), position) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("saving the queue failed: {e}");
            1
        }
    }
}

fn queue_get(options: &Options) -> i32 {
    match options.client().play_queue() {
        Ok(queue) => {
            let ids: Vec<&str> = queue.songs.iter().map(|song| song.id.as_str()).collect();
            println!("{}", json!({ "ids": ids, "current": queue.current, "position": queue.position, "changed": queue.changed, "changed_by": queue.changed_by }));
            0
        }
        Err(e) => {
            eprintln!("reading the queue failed: {e}");
            1
        }
    }
}
