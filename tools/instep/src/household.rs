//! A household on mixed versions, against a real server: two devices on the Windows code
//! under test (the exchange decided in its library, `nexus_core::step`), and a PC on an
//! older version (0.40.4), run from a program of its own built from that version's source
//! (`--old`: this tool, compiled from that checkout; its `pc` mode is that version's own
//! `exchange`).
//!
//! It says what it does and what it finds, a line at a time, and ends with how many of its
//! expectations did not hold (its exit status). Two kinds of line matter:
//!
//! * `ok` / `FAILED`: something the devices on the new code must do.
//! * `old`: what the older PC did, which is what the write-up of what Windows needed says an
//!   older version goes on doing until it is updated. These are expectations too: if the
//!   old PC stops doing one of them, the write-up is wrong.
//!
//! The devices on the new code are driven here in the library's own numbered steps
//! (`nexus_core::step`, the top), with the Windows app's own asking of the server:
//! `sync::read`, then `settle` from what the device holds at that moment, keep, take in,
//! `sending`, `sync::send`, and only then `written`.

use std::process::Command;

use nexus_core::handover::{self, Clock, Here, Save, Then};
use nexus_core::notes::{Kept, Note};
use nexus_core::record::{self, Read};
use nexus_core::step::{self, Change, Choices, Device, Held, Outcome, Plan, Stop, settle};
use nexus_core::sync::{PLAYS_SHARED, Shared, newest_plays};
use serde_json::Value;

use crate::subsonic::Client;
use crate::sync::{self, PLAYLIST_NAME};
use crate::{Options, sync_playlists};

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

/// What an exchange came to.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Came {
    Stopped(Stop),
    /// Taken in; `wrote` says whether anything was written, `held` why not if it would have been.
    Done { wrote: bool, held: Option<Held> },
}

/// A device on the code under test: what the Windows app holds, kept here in memory.
#[derive(Clone)]
struct New {
    name: &'static str,
    username: String,
    /// The device's own name for the sign-in.
    account: String,
    choices: Choices,
    kept: Kept,
    history: Vec<(String, i64)>,
    log: Vec<(String, i64)>,
    sources: Vec<(String, i64, String)>,
    /// How far this device's clock is from the true time (the server's).
    skew: i64,
    /// What the last read measured: the server's clock minus this device's.
    offset: std::cell::Cell<Option<i64>>,
}

impl New {
    fn new(name: &'static str, username: &str) -> New {
        New { name, username: username.into(), account: format!("{username}@the-test-server"), choices: Choices::default(), kept: Kept::default(), history: Vec::new(), log: Vec::new(), sources: Vec::new(), skew: 0, offset: Default::default() }
    }

    /// Its process is killed and started again: what it holds is what was saved.
    fn restarted(&self) -> New {
        New { choices: serde_json::from_str(&serde_json::to_string(&self.choices).unwrap()).unwrap(), ..self.clone() }
    }

    fn does(&mut self, change: Change) {
        self.choices.change(&self.account, change);
    }

    fn pins(&self) -> Vec<String> {
        self.choices.shown(&self.account).pins
    }

    /// Step 3: what the device holds, now.
    fn device(&self) -> Device {
        let plays = newest_plays(self.log.iter());
        Device {
            username: self.username.clone(),
            account: self.account.clone(),
            choices: self.choices.clone(),
            notes: self.kept.shared(None),
            history: self.history.clone(),
            play_sources: self.sources.iter().filter(|(song, at, _)| plays.contains(&(song.clone(), *at))).cloned().collect(),
            plays,
        }
    }

    /// Steps 1 to 3: the server is asked (the app's own `read`), and the decision made from
    /// what the device holds then. A device whose clock is wrong read its clock that far
    /// off as the answer arrived.
    fn read(&self, client: &Client) -> Outcome {
        let mut answers = sync::read(client).expect("the server");
        answers.received += self.skew;
        self.offset.set(step::clock(&answers).offset());
        settle(&self.device(), &answers)
    }

    /// Keeps the plays and takes in what was shared (the library's steps 5 and 6).
    fn take(&mut self, plan: &Plan) {
        for play in &plan.keep.plays {
            if !self.log.contains(play) {
                self.log.push(play.clone());
            }
        }
        for source in &plan.keep.sources {
            if !self.sources.contains(source) {
                self.sources.push(source.clone());
            }
        }
        self.choices.take(&plan.take);
        self.kept.take(&plan.take.notes);
        self.history = step::history(&self.history, &plan.take);
    }

    /// A whole exchange. `hears` is whether the answer to its write, if it makes one,
    /// reaches it: without it the server has the write and the device doesn't know.
    fn exchange_hearing(&mut self, client: &Client, hears: bool) -> Came {
        let plan = match self.read(client) {
            Outcome::Stop(stop) => return Came::Stopped(stop),
            Outcome::Go(plan) => plan,
        };
        self.take(&plan);
        let Some(write) = &plan.write else { return Came::Done { wrote: false, held: plan.held } };
        assert!(self.choices.sending(write), "decided and sent in one go: the plan fits the choices");
        sync::send(client, write, &|| true).expect("the write").expect("it was sent");
        if hears {
            self.choices.written(write);
        }
        Came::Done { wrote: true, held: plan.held }
    }

    fn exchange(&mut self, client: &Client) -> Came {
        self.exchange_hearing(client, true)
    }
}

/// The PC on the older version: its own program, and the file that is what it holds.
struct Old<'a> {
    program: &'a str,
    options: &'a Options,
    state: std::path::PathBuf,
}

impl Old<'_> {
    fn run(&self, mode: &str, more: &[&str]) -> Value {
        let mut command = Command::new(self.program);
        command.args([mode, "--server", self.options.need("server"), "--user", self.options.need("user"), "--password", self.options.need("password")]).args(more);
        let out = command.output().unwrap_or_else(|e| panic!("the older program ({}) couldn't be run: {e}", self.program));
        assert!(out.status.success(), "the older program's {mode} failed: {}", String::from_utf8_lossy(&out.stderr));
        let text = String::from_utf8_lossy(&out.stdout);
        if text.trim().is_empty() { Value::Null } else { serde_json::from_str(&text).expect("what the older program printed") }
    }

    /// One exchange, as that version makes it. Whether it wrote.
    fn exchange(&self, known: Option<&str>) -> bool {
        let state = self.state.to_string_lossy().to_string();
        let mut more = vec!["--state", state.as_str()];
        if let Some(known) = known {
            more.extend(["--known", known]);
        }
        self.run("pc", &more)["wrote"].as_bool().unwrap_or(false)
    }

    fn holds(&self) -> Shared {
        serde_json::from_str(&std::fs::read_to_string(&self.state).unwrap_or_else(|_| "{}".into())).expect("what the older PC holds")
    }

    /// The person does something on it: that version stamps the whole value, now.
    fn change(&self, with: impl FnOnce(&mut Shared)) {
        let mut holds = self.holds();
        holds.nexus_music_sync = 1;
        with(&mut holds);
        std::fs::write(&self.state, serde_json::to_string(&holds).unwrap()).unwrap();
    }
}

/// The run's tally.
struct Tally {
    failed: usize,
    held: usize,
}

impl Tally {
    fn check(&mut self, kind: &str, holds: bool, what: &str) {
        if holds {
            self.held += 1;
            println!("  {kind}: {what}");
        } else {
            self.failed += 1;
            println!("  FAILED ({kind}): {what}");
        }
    }
    fn ok(&mut self, holds: bool, what: &str) {
        self.check("ok", holds, what);
    }
    fn old(&mut self, holds: bool, what: &str) {
        self.check("old", holds, what);
    }
}

fn comment(client: &Client) -> (String, String) {
    sync_playlists(client).into_iter().next().unwrap_or_default()
}

fn on_server(client: &Client) -> Shared {
    match record::read(&comment(client).1) {
        Read::Record(record) => record.shared,
        other => panic!("the comment on the server is {other:?}"),
    }
}

fn clear(client: &Client) {
    for (id, _) in sync_playlists(client) {
        client.delete_playlist(&id).expect("deleting a sync playlist");
    }
}

fn ids(list: &[&str]) -> Vec<String> {
    list.iter().map(|id| id.to_string()).collect()
}

pub fn household(options: &Options) -> i32 {
    let client = options.client();
    let user = options.need("user").to_string();
    let program = options.need("old");
    let folder = std::path::PathBuf::from(options.need("folder"));
    std::fs::create_dir_all(&folder).expect("the folder for the older PC's files");
    let old_at = |name: &str| {
        let state = folder.join(format!("{name}.json"));
        let _ = std::fs::remove_file(&state);
        let _ = std::fs::remove_file(state.with_extension("added.json"));
        Old { program, options, state }
    };
    let mut t = Tally { failed: 0, held: 0 };

    // The account's own playlists with songs in them, the shortest first.
    let mut playlists: Vec<_> = client.playlists().expect("the playlists").into_iter().filter(|p| p.name != PLAYLIST_NAME && p.owner == user && p.song_count > 0).collect();
    playlists.sort_by_key(|p| (p.song_count, p.id.clone()));
    let playlists: Vec<String> = playlists.into_iter().map(|p| p.id).collect();
    let songs: Vec<String> = client.library_page(0, 0, 12, 0).expect("songs").songs.into_iter().map(|song| song.id).collect();
    assert!(playlists.len() >= 4 && songs.len() >= 8, "the test server has playlists and songs");
    let (p1, p2, p3, p4) = (playlists[0].clone(), playlists[1].clone(), playlists[2].clone(), playlists[3].clone());
    clear(&client);

    println!("1. Two devices on the new code, and nothing shared yet.");
    let mut a = New::new("A", &user);
    let mut b = New::new("B", &user);
    t.ok(a.exchange(&client) == Came::Done { wrote: false, held: None } && sync_playlists(&client).is_empty(), "a device with nothing to say makes no playlist");
    a.does(Change::Pin(p1.clone()));
    t.ok(a.exchange(&client) == Came::Done { wrote: true, held: None } && on_server(&client).pins == [p1.clone()], "with a pin of the person's, it makes the record");

    println!("2. B pins a playlist before it has read anything, and its process is killed and started again.");
    b.does(Change::Pin(p2.clone()));
    t.ok(b.pins() == [p2.clone()], "all B can show is its own pin");
    let mut b = b.restarted();
    b.exchange(&client);
    a.exchange(&client);
    let both = vec![p1.clone(), p2.clone()];
    t.ok(on_server(&client).pins == both && a.pins() == both && b.pins() == both, "B's pin is added to A's, on the server and on both: not put in their place");

    println!("3. A list's shuffle from one, its sort from the other; a theme; each made to what the server has.");
    let liked = "liked".to_string();
    a.does(Change::Sort { key: liked.clone(), sort: 2, descending: true });
    a.exchange(&client);
    b.does(Change::Shuffle { key: liked.clone(), on: true });
    b.does(Change::Theme("Ocean".into()));
    b.exchange(&client);
    a.exchange(&client);
    let list = on_server(&client).lists.get(&liked).copied().unwrap_or_default();
    t.ok((list.sort, list.descending, list.sort_chosen, list.shuffle) == (2, true, true, true), "the list has A's sort and B's shuffle (a whole value from B would have lost the sort)");
    t.ok(a.choices.shown(&a.account).theme == "Ocean" && a.choices.shown(&a.account).lists[&liked] == b.choices.shown(&b.account).lists[&liked], "A shows B's theme, and both show the same choices for the list");

    println!("4. Crossed: both read, then both write. The server has no write that waits on what was read.");
    a.does(Change::Pin(p3.clone()));
    b.does(Change::Sort { key: format!("playlist:{p1}"), sort: 1, descending: false });
    let (plan_a, plan_b) = (a.read(&client), b.read(&client));
    for (device, outcome) in [(&mut a, plan_a), (&mut b, plan_b)] {
        let Outcome::Go(plan) = outcome else { panic!("stopped") };
        device.take(&plan);
        let write = plan.write.expect("something to write");
        assert!(device.choices.sending(&write));
        sync::send(&client, &write, &|| true).expect("the write").expect("it was sent");
        device.choices.written(&write);
    }
    let crossed = on_server(&client);
    t.ok(!crossed.pins.contains(&p3) && crossed.lists.contains_key(&format!("playlist:{p1}")), "ACCEPTED: the second write went without the first's part (A's pin is not on the server)");
    a.exchange(&client);
    b.exchange(&client);
    a.exchange(&client);
    let settled = on_server(&client);
    t.ok(settled.pins == [p1.clone(), p2.clone(), p3.clone()] && settled.lists[&format!("playlist:{p1}")].sort == 1 && b.pins().len() == 3, "after one more look each, both parts are on the server and on both devices");
    let before = comment(&client).1;
    t.ok(a.exchange(&client) == Came::Done { wrote: false, held: None } && b.exchange(&client) == Came::Done { wrote: false, held: None } && comment(&client).1 == before, "and then nothing more is written: they have settled");

    println!("5. The answer to a write is lost, and the other device then sets the opposite (the second review's case).");
    a.does(Change::Unpin(p3.clone()));
    a.exchange(&client);
    b.exchange(&client);
    a.does(Change::Pin(p4.clone()));
    a.exchange_hearing(&client, false);
    let lost = a.restarted();
    t.ok(on_server(&client).pins.contains(&p4) && lost.choices.waiting.len() == 1 && lost.choices.waiting[0].tried, "the server took A's pin; A never heard, and still holds the change as tried");
    let mut quiet = lost.clone();
    let before = comment(&client).1;
    t.ok(quiet.exchange(&client) == Came::Done { wrote: false, held: None } && comment(&client).1 == before && quiet.choices.waiting.is_empty(), "if nothing else happens, A makes the change again over its own result: nothing written, nothing given a new time");
    b.exchange(&client);
    b.does(Change::Unpin(p4.clone()));
    std::thread::sleep(std::time::Duration::from_secs(1));
    b.exchange(&client);
    t.ok(!on_server(&client).pins.contains(&p4), "B, having seen the pin, deliberately unpins it");
    let mut a = lost;
    std::thread::sleep(std::time::Duration::from_secs(1));
    a.exchange(&client);
    b.exchange(&client);
    t.ok(on_server(&client).pins.contains(&p4) && b.pins().contains(&p4) && a.choices.waiting.is_empty(), "ACCEPTED, and written down: A, started again, makes its change again on top of B's unpin. The playlist is pinned on both");
    b.does(Change::Unpin(p4.clone()));
    b.exchange(&client);
    a.exchange(&client);
    t.ok(a.pins() == both && on_server(&client).pins == both, "unpinned again on B, it stays unpinned: the change was made once more, not for ever");

    println!("6. Plays: the record holds the latest five hundred. One that is about to fall out is kept first.");
    let old_play = (songs[0].clone(), now() - 5_000_000);
    b.log.push(old_play.clone());
    b.sources.push((old_play.0.clone(), old_play.1, format!("playlist:{p1}")));
    b.exchange(&client);
    let mut busy = New::new("C", &user);
    busy.log = (0..PLAYS_SHARED as i64).map(|n| (songs[(n % 8) as usize].clone(), now() - 4_000 + n)).collect();
    let Outcome::Go(plan) = busy.read(&client) else { panic!("stopped") };
    let Read::Record(to_write) = record::read(&plan.write.as_ref().expect("a write").text) else { panic!("not a record") };
    t.ok(plan.keep.plays.contains(&old_play) && !to_write.shared.plays.contains(&old_play) && to_write.shared.plays.len() == PLAYS_SHARED, "a device with five hundred newer plays is handed B's old play to keep, before it writes the record without it");
    busy.exchange(&client);
    t.ok(busy.log.contains(&old_play) && busy.sources.iter().any(|(song, at, _)| (song, at) == (&old_play.0, &old_play.1)), "and it has it, with where it was played from");

    println!("7. Dates: only a device's own confirmed change is dated.");
    let added_at = now();
    // (A song the playlist doesn't have yet.)
    let has: Vec<String> = client.playlist(&p1).expect("the playlist").songs.into_iter().map(|song| song.id).collect();
    let new_song = client.library_page(0, 0, 200, 0).expect("songs").songs.into_iter().map(|song| song.id).find(|id| !has.contains(id)).expect("a song the playlist lacks");
    client.add_to_playlist(&p1, &[new_song.clone()]).expect("adding a song");
    a.kept.added(&p1, &[new_song.clone()], added_at);
    a.exchange(&client);
    let before = comment(&client).1;
    let read: Vec<String> = client.playlist(&p1).expect("the playlist").songs.into_iter().map(|song| song.id).collect();
    b.kept.notice(&p1, &read.iter().map(String::as_str).collect::<Vec<_>>(), now() + 50);
    let came = b.exchange(&client);
    t.ok(came == Came::Done { wrote: false, held: None } && comment(&client).1 == before, "B opens the playlist and notices everything in it: nothing is dated, nothing written");
    t.ok(on_server(&client).added[&p1][&new_song] == Note { times: vec![added_at], at: added_at } && on_server(&client).added[&p1].len() == 1, "the one dated song is the one A added, at the time A added it");

    println!("8. A device whose clock is an hour ahead of the server's (by the Date on the server's own answers).");
    let mut fast = New::new("D", &user);
    fast.skew = 3_600;
    fast.does(Change::Pin(p3.clone()));
    let before = comment(&client).1;
    let came = fast.exchange(&client);
    t.ok(came == Came::Done { wrote: false, held: Some(Held::ClockWrong) } && comment(&client).1 == before, "it takes in and writes nothing, and says its clock is wrong");
    t.ok(fast.pins() == [p1.clone(), p2.clone(), p3.clone()] && fast.choices.waiting.len() == 1 && handover::save(&saved_queue(&client).0, &Here { device: "NexusMusic-d".into(), current: Some("x".into()), stamp: now() + 3_600, ..Default::default() }, Clock::Offset(fast.offset.get().unwrap_or(0))) == Save::NotNow, "it shows the others' pins with its own waiting on top, and saves no queue");
    fast.skew = 0;
    fast.exchange(&client);
    t.ok(on_server(&client).pins == [p1.clone(), p2.clone(), p3.clone()], "its clock put right, the pin goes on top of the others'");
    fast.does(Change::Unpin(p3.clone()));
    fast.exchange(&client);
    a.exchange(&client);
    b.exchange(&client);

    println!("9. The queue: a device saves its own only if it is still the newest thing that happened.");
    let (name_a, name_b) = ("NexusMusic-aaaaaaaa", "NexusMusic-bbbbbbbb");
    // (The time of a change is the device's own clock's; the clock beside the server's is
    // measured by the very read that precedes a save or makes a look.)
    let changed_on_a = now();
    std::thread::sleep(std::time::Duration::from_secs(2));
    client.save_play_queue(name_b, &ids(&[&songs[1], &songs[2], &songs[3]]), &songs[2], 5_000).expect("B's queue");
    let (theirs, clock) = saved_queue(&client);
    let here_a = Here { device: name_a.into(), current: Some(songs[5].clone()), playing: false, stamp: changed_on_a, taken: None };
    t.ok(handover::save(&theirs, &here_a, clock) == Save::NotNewest, "A changed its queue, paused; B saved its own two seconds later; A's save, three seconds after the change, reads the server first and is not made");
    let looked = handover::look(&theirs, &here_a, clock, now());
    let Then::TakeUp(place) = looked.then else { panic!("A did not take B's queue up: {looked:?}") };
    t.ok(place.cursor == 1 && place.position == 5.0, "at its next look A takes B's queue up, paused, at B's song and place");
    let taken = Here { stamp: place.stamp, taken: Some(place.taken.clone()), current: Some(songs[2].clone()), ..here_a.clone() };
    t.ok(handover::save(&theirs, &taken, clock) == Save::NotNewest && handover::look(&theirs, &taken, clock, now()).then == Then::Nothing, "and does not save it back as its own, nor take it up a second time");
    std::thread::sleep(std::time::Duration::from_secs(2));
    let played = Here { stamp: now(), taken: None, ..taken.clone() };
    t.ok(handover::save(&theirs, &played, clock) == Save::Save, "played on at A, it is A's to save");
    client.save_play_queue(name_a, &ids(&[&songs[1], &songs[2], &songs[3]]), &songs[3], 1_000).expect("A's queue");
    let (theirs, clock) = saved_queue(&client);
    let here_b = Here { device: name_b.into(), current: Some(songs[2].clone()), playing: true, stamp: changed_on_a, taken: None };
    let looked = handover::look(&theirs, &here_b, clock, now());
    t.ok(looked.offer == Some(2) && looked.then == Then::Nothing, "B, playing, keeps its own queue and is offered A's as \"Continue here\"");
    t.ok(handover::first(&here_b) == handover::First::Save && handover::first(&here_a) == handover::First::Read, "and while B plays, the saves of its place don't each read the server first; A's, made while nothing plays, do");

    println!("10. A PC on 0.40.4 joins the household.");
    let pc = old_at("pc");
    pc.exchange(None);
    t.old(pc.holds().pins == both, "it takes in what is there, as any device does");
    a.does(Change::Unpin(p1.clone()));
    a.does(Change::Pin(p3.clone()));
    a.exchange(&client);
    b.exchange(&client);
    let wanted = vec![p2.clone(), p3.clone()];
    t.ok(on_server(&client).pins == wanted && b.pins() == wanted, "meanwhile, with the PC switched off, A unpins one playlist and pins another");
    // (The PC's clock reads later than A's change, as it would on the day: this run makes
    // changes faster than one a second, and each is timed at least a second after the last.)
    let later = now().max(on_server(&client).pins_at + 1);
    pc.change(|holds| {
        holds.pins.push(p4.clone());
        holds.pins_at = later;
    });
    pc.exchange(None);
    a.exchange(&client);
    b.exchange(&client);
    let stale = vec![p1.clone(), p2.clone(), p4.clone()];
    t.old(on_server(&client).pins == stale && a.pins() == stale && b.pins() == stale, "the PC, back on, pins a playlist: its whole stale list, stamped now, replaces everyone's. A's unpin and A's pin are undone on every device");
    println!("     (Nothing of that can be recovered by the new devices: the PC's value is the newest, and theirs had been shared and agreed. The person does it again.)");
    a.does(Change::Unpin(p1.clone()));
    a.does(Change::Unpin(p4.clone()));
    a.does(Change::Pin(p3.clone()));
    a.exchange(&client);
    pc.exchange(None);
    t.ok(on_server(&client).pins == wanted && pc.holds().pins == wanted, "done again on A, it goes on top of the PC's list, and the PC takes it");

    println!("11. A new PC on 0.40.4, just installed: a pin made before its first exchange.");
    let fresh = old_at("pc-fresh");
    let later = now().max(on_server(&client).pins_at + 1);
    fresh.change(|holds| {
        holds.pins = vec![p1.clone()];
        holds.pins_at = later;
    });
    fresh.exchange(None);
    a.exchange(&client);
    t.old(on_server(&client).pins == [p1.clone()] && a.pins() == [p1.clone()], "its one pin becomes \"the pins\" for every device (step 2 is the same thing done by a device on the new code)");
    b.exchange(&client);
    b.does(Change::Pin(p2.clone()));
    b.exchange(&client);
    a.exchange(&client);
    pc.exchange(None);
    fresh.exchange(None);

    println!("12. The PC's clock is an hour ahead when it pins something.");
    pc.change(|holds| {
        holds.pins.push(p4.clone());
        holds.pins_at = now() + 3_600;
    });
    pc.exchange(None);
    a.exchange(&client);
    t.old(on_server(&client).pins_at > now() + 3_000 && a.pins().contains(&p4), "0.40.4 checks nothing against the server's clock: the pins now carry a time an hour from now");
    a.does(Change::Unpin(p4.clone()));
    a.exchange(&client);
    pc.exchange(None);
    t.ok(!on_server(&client).pins.contains(&p4) && !pc.holds().pins.contains(&p4), "RECOVERED: a change made on a new device now is timed after the value it changes, so it wins, on the PC too (stamped \"now\" it would lose for an hour)");

    println!("13. A field nobody knows yet, at the top of the record (as a later version might add under layout 1).");
    let (playlist, text) = comment(&client);
    client.set_playlist_comment(&playlist, &text.replacen('{', r#"{"devices":{"x":[1,2]},"#, 1)).expect("planting a field");
    a.does(Change::Pin(p3.clone()));
    a.exchange(&client);
    t.ok(comment(&client).1.contains(r#""devices":{"x":[1,2]}"#) && on_server(&client).pins.contains(&p3), "a device on the new code writes its pin and carries the field back out exactly");
    let later = now().max(on_server(&client).theme_at + 1);
    pc.change(|holds| {
        holds.theme = "Rose".into();
        holds.theme_at = later;
    });
    pc.exchange(None);
    t.old(!comment(&client).1.contains("devices"), "the PC on 0.40.4 writes, and the field is gone from the server: so no field may be added to layout 1 while such a PC is in use");

    println!("14. The comment is not a record (somebody's note).");
    client.set_playlist_comment(&playlist, "do not delete: my notes").expect("planting a note");
    a.does(Change::Unpin(p3.clone()));
    let came = a.exchange(&client);
    t.ok(came == Came::Stopped(Stop::NotARecord) && comment(&client).1 == "do not delete: my notes" && a.choices.waiting.len() == 1, "the new device leaves it exactly as it is, and its change waits");
    pc.exchange(None);
    t.old(record::read(&comment(&client).1) != Read::Unreadable, "the PC on 0.40.4 takes it for \"nothing shared yet\" and writes its own record over it");
    a.exchange(&client);
    t.ok(!on_server(&client).pins.contains(&p3), "RECOVERED: with a record there again, the new device's waiting change goes through");

    println!("15. A record in a later layout.");
    let (playlist, text) = comment(&client);
    let later = text.replacen(r#""nexus_music_sync":1"#, r#""nexus_music_sync":2"#, 1);
    client.set_playlist_comment(&playlist, &later).expect("planting layout 2");
    a.does(Change::Pin(p3.clone()));
    let came = a.exchange(&client);
    t.ok(came == Came::Done { wrote: false, held: Some(Held::LaterLayout) } && comment(&client).1 == later, "new device: read, not written, and it says why");
    pc.change(|holds| {
        holds.pins.push(p4.clone());
        holds.pins_at = now() + 5;
    });
    t.old(!pc.exchange(None) && comment(&client).1 == later, "0.40.4, where the later layout keeps every field's shape: read, and not written either");
    let reshaped = r#"{"nexus_music_sync":2,"pins":{"all":"changed"},"history":"kept singly"}"#;
    client.set_playlist_comment(&playlist, reshaped).expect("planting a reshaped layout 2");
    let came = a.exchange(&client);
    t.ok(came == Came::Done { wrote: false, held: Some(Held::LaterLayout) } && comment(&client).1 == reshaped, "new device, where a field has changed shape: still a later layout, still not written");
    pc.exchange(None);
    t.old(comment(&client).1 != reshaped && record::read(&comment(&client).1) != Read::Unreadable, "0.40.4 fails to read it, takes it for nothing, and writes layout 1 over it: so a later layout must keep the shape of every field 0.40.4 knows");
    a.exchange(&client);
    b.exchange(&client);

    println!("16. Two sync playlists (two devices each made one, having found none).");
    let first = comment(&client).0;
    let second = client.create_playlist(PLAYLIST_NAME, &[]).expect("a second sync playlist").id;
    let (low, high) = if second < first { (second.clone(), first.clone()) } else { (first.clone(), second.clone()) };
    // (The PC remembers the higher one; if the record is in the lower, it is moved for the test.)
    if high != first {
        client.set_playlist_comment(&high, &client.playlist(&first).expect("the playlist").comment).expect("moving the record");
        client.set_playlist_comment(&low, "").expect("emptying the lower");
    }
    a.does(Change::Sort { key: format!("playlist:{p2}"), sort: 4, descending: true });
    a.exchange(&client);
    b.exchange(&client);
    let in_low = match record::read(&client.playlist(&low).expect("the lower").comment) {
        Read::Record(record) => record.shared,
        other => panic!("the lower playlist holds {other:?}"),
    };
    t.ok(in_low.lists.contains_key(&format!("playlist:{p2}")) && in_low.pins == a.pins() && b.choices.shown(&b.account).lists.contains_key(&format!("playlist:{p2}")), "the new devices both go to the lowest id, taking what they hold with them");
    pc.change(|holds| {
        holds.theme = "Graphite".into();
        holds.theme_at = now() + 10;
    });
    pc.exchange(Some(&high));
    a.exchange(&client);
    t.old(a.choices.shown(&a.account).theme != "Graphite" && !pc.holds().lists.contains_key(&format!("playlist:{p2}")), "the PC on 0.40.4 goes on using the id it remembers: its theme reaches nobody, and A's sort does not reach it. Split, with nothing destroyed, until it is updated");
    client.delete_playlist(&high).expect("deleting the higher");

    println!("17. The PC on 0.40.4 opens a playlist some time after a song was added to it elsewhere.");
    let late = now() + 500;
    let state = pc.state.to_string_lossy().to_string();
    let listed = read.join(",");
    pc.run("pc-looks", &["--state", &state, "--playlist", &p1, "--ids", &read[..read.len() - 1].join(","), "--now", &(added_at - 100).to_string()]);
    pc.run("pc-looks", &["--state", &state, "--playlist", &p1, "--ids", &listed, "--now", &late.to_string()]);
    pc.exchange(None);
    a.exchange(&client);
    t.old(on_server(&client).added[&p1][&new_song].at == late && a.kept.dates(&p1, &[new_song.as_str()]) == [late], "0.40.4 dates what it notices with the moment it looked, and that newer note replaces the real date on every device (A's own included)");

    println!("18. The queue again, with the PC on 0.40.4.");
    client.save_play_queue(name_a, &ids(&[&songs[4], &songs[5]]), &songs[5], 2_000).expect("A's queue");
    let changed_on_a = now();
    std::thread::sleep(std::time::Duration::from_secs(2));
    // (The PC changed its queue before A did, and saves it three seconds later, as it does.)
    pc.run("queue-save", &["--device", "NexusMusic-0badc0de", "--ids", &format!("{},{}", songs[6], songs[7]), "--current", &songs[6], "--position", "0"]);
    let (theirs, clock) = saved_queue(&client);
    let here_a = Here { device: name_a.into(), current: Some(songs[5].clone()), playing: false, stamp: changed_on_a, taken: None };
    t.old(theirs.changed_by == "NexusMusic-0badc0de" && matches!(handover::look(&theirs, &here_a, clock, now()).then, Then::TakeUp(_)), "0.40.4 saves without a look at what is there: its older queue lands on top of A's newer one, as the newest, and A, paused, takes it up");

    clear(&client);
    println!("{} expectations held, {} did not.", t.held, t.failed);
    t.failed.min(1) as i32
}

/// The server's saved queue, and this device's clock beside the server's as that read gives it.
fn saved_queue(client: &Client) -> (crate::subsonic::PlayQueue, Clock) {
    let read = client.play_queue_timed().expect("the queue");
    let clock = Clock::measured(read.date, read.received);
    (read.value, clock)
}
