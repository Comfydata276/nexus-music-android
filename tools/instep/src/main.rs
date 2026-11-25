//! The Windows app's code for keeping devices in step, run here.
//!
//!   instep cases <file>      writes what the Windows app's library decides for a list of
//!                            inputs (an exchange: what a device holds and what the server
//!                            answered; a run of the person's changes): the cases the phone's
//!                            door to that same library is held to
//!                            (app/src/test/resources/instep-cases.json; cases.rs)
//!   instep pc ...            plays the PC: Windows' own `exchange`, against a server
//!   instep peek ...          the sync playlist and its comment, byte for byte, changing nothing
//!   instep plant ...         puts a text of your choosing in the comment (an unreadable one,
//!                            a newer layout), or makes a second sync playlist
//!   instep queue-save / queue-get    the server's saved queue, as Windows saves and reads it
//!   instep pc-looks ...      the PC opens a playlist: that version's own noting of what is in it
//!   instep household ...     two devices on the Windows code under test and a PC on an older
//!                            version, as one household against a real server (household.rs)
//!
//! Which of these a build has depends on the Windows checkout it was compiled from (build.rs):
//! `pc` and `pc-looks` are that checkout's own exchange, and exist for 0.40.4 and before;
//! `household` drives the exchange that is decided in the Windows app's library, and exists
//! for the versions after.
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
/// (What the Windows app's notes of when songs were added name from its own `playlists`
/// module, which is the library's.)
#[cfg(stepped)]
mod playlists {
    pub use nexus_core::playlists::Edit;
}
#[cfg(stepped)]
mod household;
#[cfg(stepped)]
mod cases;

use serde_json::{Value, json};
use sync::Shared;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = args.first().map(String::as_str).unwrap_or("");
    let rest = &args[args.len().min(1)..];
    let status = match mode {
        #[cfg(stepped)]
        "cases" => cases::cases(rest.first().expect("the file to write")),
        #[cfg(not(stepped))]
        "pc" => pc(&Options::of(rest)),
        #[cfg(not(stepped))]
        "pc-looks" => pc_looks(&Options::of(rest)),
        #[cfg(stepped)]
        "household" => household::household(&Options::of(rest)),
        #[cfg(stepped)]
        "pc" | "pc-looks" => {
            eprintln!("this instep was built from a Windows checkout whose exchange is its library's: the PC it would play is the code under test (see `household`). Build it from 0.40.4 or before for `pc`.");
            2
        }
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

/// The PC opens a playlist: that version's own `observe`, with the playlist as `--ids` and
/// the time as `--now`. What it notes is kept in a file beside `--state`, and whatever of it
/// that version shares goes into what the PC holds, for its next exchange.
#[cfg(not(stepped))]
fn pc_looks(options: &Options) -> i32 {
    let state = options.need("state");
    let mut holds: Shared = std::fs::read_to_string(state).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or_default();
    let mut noted = added::Added::load(std::path::PathBuf::from(state).with_extension("added.json"));
    let songs: Vec<subsonic::Song> = options.need("ids").split(',').map(|id| subsonic::Song { id: id.to_string(), ..Default::default() }).collect();
    let now: i64 = options.need("now").parse().expect("a time in seconds");
    noted.observe(options.need("playlist"), &songs, now);
    holds.added = added::merge_notes(&holds.added, &noted.shared(None), now);
    std::fs::write(state, serde_json::to_string_pretty(&holds).unwrap()).unwrap();
    0
}

/// One exchange, exactly as the Windows app makes it: `exchange` with this PC's record
/// (`--state`, a file: read, and written back as what the PC then holds). Prints what the
/// server's comment was before and after.
#[cfg(not(stepped))]
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
    #[cfg(stepped)]
    let read = options.client().play_queue_timed().map(|answer| answer.value);
    #[cfg(not(stepped))]
    let read = options.client().play_queue();
    match read {
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
