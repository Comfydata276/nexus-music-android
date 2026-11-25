//! The cases the phone's door to the shared library is held to.
//!
//! The phone app carries the Windows app's library (its `core/`, at one pinned commit) and
//! calls it through one Kotlin object, each call JSON in and JSON out. This writes down, for
//! a list of inputs, what **the library in the Windows checkout this tool was built from**
//! decides; the phone's `CoreCasesTest` hands the same inputs through its door and must get
//! the same answers. So it shows two things at once: that nothing is lost or changed on the
//! way through the phone's door, and that the library the phone has pinned decides as the
//! Windows checkout beside it does. (When the Windows library has moved on and the phone's
//! pin has not, this is where it shows.)
//!
//! The inputs are the awkward ones this tool has always kept (`read_texts`, `merge_pairs`
//! in main.rs): never added to the JSON by hand.

use nexus_core::step::{Agreed, Answers, Change, Choices, Device, Stated, settle};
use nexus_core::sync::{PLAYLIST_NAME, Shared};
use serde_json::{Value, json};

use crate::{NOW, merge_pairs, read_texts, record};

const ACCOUNT: &str = "the-account";

/// A device that last agreed `shared` with the server, and holds its notes, history and plays.
fn device(shared: &Shared) -> Device {
    Device {
        username: "tester".into(),
        account: ACCOUNT.into(),
        choices: Choices { agreed: Agreed { pins: shared.pins.clone(), pins_at: shared.pins_at, theme: shared.theme.clone(), theme_at: shared.theme_at, lists: shared.lists.clone() }, ..Default::default() },
        notes: shared.added.clone(),
        history: shared.history.clone(),
        plays: shared.plays.clone(),
        play_sources: shared.play_sources.clone(),
    }
}

/// The server's two answers for a sync playlist holding `comment` (Navidrome leaves an
/// empty one out), with its clock at `date`, received by the device at `received`.
fn answers(comment: &str, date: Option<i64>, received: i64) -> Answers {
    let stated = Stated { id: "r".into(), name: PLAYLIST_NAME.into(), owner: Some("Tester".into()), comment: (!comment.is_empty()).then(|| comment.to_string()) };
    Answers { listed: vec![stated.clone()], playlist: Some(stated), date, received }
}

fn settled(name: &str, device: &Device, answers: &Answers) -> Value {
    json!({ "name": name, "device": device, "answers": answers, "outcome": settle(device, answers) })
}

pub fn cases(path: &str) -> i32 {
    let mut out = serde_json::Map::new();
    out.insert("about".into(), json!("Written by tools/instep from the Windows app's own library (core/). Not edited by hand: run tools/instep/run cases to write it again."));
    out.insert("names".into(), json!({
        "playlist": PLAYLIST_NAME, "reserved": nexus_core::step::RESERVED,
        "history_length": nexus_core::sync::HISTORY_LENGTH, "plays_shared": nexus_core::sync::PLAYS_SHARED,
    }));

    let mut settles = Vec::new();
    // Every pair of records: a device that holds the one, a server that holds the other.
    // Then the same with something the person has changed on the device, still waiting.
    for (name, mine, theirs, now) in merge_pairs() {
        let (mine, theirs) = (record(&mine), record(&theirs));
        let text = serde_json::to_string(&theirs).unwrap();
        let holds = device(&mine);
        settles.push(settled(name, &holds, &answers(&text, Some(now), now)));
        let mut changed = holds.clone();
        changed.choices.change(ACCOUNT, Change::Pin("pinned-here".into()));
        changed.choices.change(ACCOUNT, Change::Shuffle { key: "liked".into(), on: true });
        changed.choices.change(ACCOUNT, Change::Theme("Rose".into()));
        settles.push(settled(&format!("{name}; and changes waiting here"), &changed, &answers(&text, Some(now), now)));
    }
    // Every text a comment might hold, read by a device with a pin waiting.
    let mut pinned = device(&Shared::default());
    pinned.choices.change(ACCOUNT, Change::Pin("pinned-here".into()));
    for (name, text) in read_texts() {
        settles.push(settled(&format!("the comment is: {name}"), &pinned, &answers(&text, Some(NOW), NOW)));
    }
    // The clocks.
    let full = r#"{"nexus_music_sync":1,"pins":["p1","p2"],"pins_at":1700000000,"theme":"Ember","theme_at":1700000100}"#;
    for (name, date, received) in [
        ("the server gives no date", None, NOW),
        ("the device's clock is an hour ahead", Some(NOW), NOW + 3_600),
        ("the device's clock is an hour behind", Some(NOW), NOW - 3_600),
        ("the clocks are five minutes apart, exactly", Some(NOW), NOW + 300),
        ("the clocks are a second more than five minutes apart", Some(NOW), NOW + 301),
        ("the device's clock is before 1970", Some(NOW), -5),
    ] {
        settles.push(settled(name, &pinned, &answers(full, date, received)));
    }
    // What the server did not say.
    let stated = |owner: Option<&str>, comment: Option<&str>| Stated { id: "r".into(), name: PLAYLIST_NAME.into(), owner: owner.map(Into::into), comment: comment.map(Into::into) };
    for (name, listed, playlist) in [
        ("no playlist at all, and something to say", vec![], None),
        ("the owner is left out of both answers", vec![stated(None, None)], Some(stated(None, None))),
        ("the list states a comment and the playlist's own answer leaves it out", vec![stated(Some("tester"), Some(full))], Some(stated(Some("tester"), None))),
        ("the two answers state different comments", vec![stated(Some("tester"), Some(full))], Some(stated(Some("tester"), Some("{}")))),
        ("the list says the comment is empty and the playlist's own answer states one", vec![stated(Some("tester"), Some(""))], Some(stated(Some("tester"), Some(full)))),
        ("the playlist's own answer wasn't handed over", vec![stated(Some("tester"), Some(full))], None),
        ("the playlist is somebody else's", vec![stated(Some("guest"), Some(full))], None),
    ] {
        settles.push(settled(name, &pinned, &Answers { listed, playlist, date: Some(NOW), received: NOW }));
    }
    let nothing = device(&Shared::default());
    settles.push(settled("no playlist at all, and nothing to say", &nothing, &Answers { listed: vec![], playlist: None, date: Some(NOW), received: NOW }));
    settles.push(settled("an empty playlist, and nothing to say", &nothing, &answers("", Some(NOW), NOW)));
    out.insert("settles".into(), settles.into());

    // Runs of the person's changes, from what a PC had agreed: after each, what is kept and
    // what is shown.
    let agreed: Shared = record(r#"{"nexus_music_sync":1,"pins":["p1","p2"],"pins_at":1700000000,"theme":"Ember","theme_at":1700000100,"lists":{"liked":{"shuffle":false,"sort":3,"descending":true,"sort_chosen":true,"at":1700000200}}}"#);
    let pin = |id: &str| Change::Pin(id.into());
    let unpin = |id: &str| Change::Unpin(id.into());
    let runs: Vec<(&str, Vec<Change>)> = vec![
        ("a pin", vec![pin("x")]),
        ("a pin taken back before it was sent", vec![pin("x"), unpin("x")]),
        ("an unpin of what was agreed, then pinned again", vec![unpin("p1"), pin("p1")]),
        ("the same pin twice", vec![pin("x"), pin("x")]),
        ("a pin of what is pinned already", vec![pin("p2")]),
        ("a list's shuffle, then its sort, then its shuffle again", vec![
            Change::Shuffle { key: "liked".into(), on: true },
            Change::Sort { key: "liked".into(), sort: 1, descending: false },
            Change::Shuffle { key: "liked".into(), on: false },
        ]),
        ("a list nobody has chosen anything for", vec![Change::Shuffle { key: "album:a".into(), on: true }, Change::Usual { key: "album:a".into() }]),
        ("themes, the last word", vec![Change::Theme("Ocean".into()), Change::Theme("Rose".into()), Change::Theme("Ember".into())]),
        ("odd text", vec![pin("Été & 夜 \u{1F3B6} \"quoted\""), Change::Theme(String::new())]),
    ];
    let runs: Vec<Value> = runs
        .into_iter()
        .map(|(name, changes)| {
            let mut choices = device(&agreed).choices;
            let steps: Vec<Value> = changes
                .into_iter()
                .map(|change| {
                    let changed = choices.change(ACCOUNT, change.clone());
                    let shown = choices.shown(ACCOUNT);
                    json!({ "change": change, "changed": changed, "choices": choices, "shown": shown, "shown_to_another_account": choices.shown("another") })
                })
                .collect();
            json!({ "name": name, "account": ACCOUNT, "from": device(&agreed).choices, "steps": steps })
        })
        .collect();
    out.insert("changes".into(), runs.into());

    // One section to a line: small enough to keep in the repository, and a change to one
    // section shows as one line changed.
    let lines: Vec<String> = out.iter().map(|(name, value)| format!("{}:{}", serde_json::to_string(name).unwrap(), serde_json::to_string(value).unwrap())).collect();
    std::fs::write(path, format!("{{\n{}\n}}\n", lines.join(",\n"))).unwrap();
    println!("wrote {path}: {} exchanges, {} runs of changes", out["settles"].as_array().unwrap().len(), out["changes"].as_array().unwrap().len());
    0
}
