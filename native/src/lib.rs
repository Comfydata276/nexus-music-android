//! The shared core, as the phone app sees it.
//!
//! One function for each operation of the core the phone uses. Each takes one argument and
//! gives one answer, both JSON as UTF-8 **bytes** (never Java strings: Java's own encoding
//! of text damages what holds both a zero character and a character outside the basic
//! plane). The Kotlin side is `org.medianexus.music.core.Core`, and is the only thing in
//! the app that knows this library exists.
//!
//! Nothing here opens a connection, keeps a file, starts a thread or reads a clock: the
//! core does none of those, and neither does this layer. What the core wants of the network
//! (requests: `requests.rs`) it asks the app to fetch, through `wire.rs`; what it must have
//! written down before it changes Lidarr (the way back: `pages.rs`) it hands the app to
//! write, the same way. (One clock is read: the time the library's batch job notes an album
//! at, which the phone gives it no albums to ask about.)
//!
//! **What going wrong looks like.** An argument that isn't what the function takes, and a
//! panic anywhere below, both reach Kotlin as one exception (`CoreException`). An argument
//! refused carries the reason in words. **A panic carries none of its own**: "The shared
//! library stopped.", and no more. A panic's words can quote whatever the code was holding
//! (an answer, an address, a secret), and an exception nobody catches is written wherever
//! the phone writes those. Its place (file and line) goes to the phone's log, and nothing
//! of what it said goes anywhere.

mod client;
mod listening;
mod nexus_requests;
mod standin;
mod pages;
mod requests;
mod recording;
mod wire;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use std::collections::{HashMap, HashSet};

use nexus_core::handover::{self, Clock, First, Here, Place, Save, Saved, Then};
use nexus_core::model::{PlayQueue, Song};
use nexus_core::notes::{self, Kept, Note, Notes};
use nexus_core::playlists::{Edit, spot};
use nexus_core::step::{self, Agreed, Answers, Change, Choices, Device, Found, Outcome, Stated, Take, Write};
use nexus_core::sync::{self, ListPrefs};

// ---- what a panic looks like ----------------------------------------------------------------

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
}

/// Android throws away what a program writes to its error output, which is where Rust says
/// where it panicked. This sends it to the phone's log instead (and, on a computer, leaves
/// it where it was).
fn log_panics() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            // **Where it panicked, and nothing of what it said**: a panic's own words can
            // quote whatever the code was holding (an answer, an address, a secret), and
            // the phone's log is read by more than this app.
            let place = match info.location() {
                Some(place) => format!("panicked at {}:{}:{}", place.file(), place.line(), place.column()),
                None => "panicked".to_string(),
            };
            let text = std::ffi::CString::new(place).unwrap_or_default();
            #[cfg(target_os = "android")]
            // (6 is ANDROID_LOG_ERROR. Both pointers are to text that ends in a zero and
            // lives until the call returns.)
            unsafe {
                __android_log_write(6, c"nexus-core".as_ptr(), text.as_ptr());
            }
            #[cfg(not(target_os = "android"))]
            eprintln!("nexus-core: {}", text.to_string_lossy());
        }));
    });
}

const EXCEPTION: &str = "org/medianexus/music/core/CoreException";

/// One call from Kotlin: the argument read from its bytes, `act` run on it, the answer
/// written back as bytes. Whatever goes wrong is thrown to Kotlin, and nothing is returned.
fn call<In: DeserializeOwned, Out: Serialize>(env: JNIEnv, input: JByteArray, act: impl FnOnce(In) -> Result<Out, String>) -> jbyteArray {
    call_as(env, input, false, act)
}

/// The same, for a call whose argument holds something secret (a key, a token, a sign-in's
/// code): an argument that can't be read is refused without the reader's own words, which
/// can quote what it was reading.
fn call_private<In: DeserializeOwned, Out: Serialize>(env: JNIEnv, input: JByteArray, act: impl FnOnce(In) -> Result<Out, String>) -> jbyteArray {
    call_as(env, input, true, act)
}

fn call_as<In: DeserializeOwned, Out: Serialize>(mut env: JNIEnv, input: JByteArray, private: bool, act: impl FnOnce(In) -> Result<Out, String>) -> jbyteArray {
    log_panics();
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<jbyteArray, String> {
        let bytes = env.convert_byte_array(&input).map_err(|e| format!("The shared library couldn't be handed its argument: {e}"))?;
        let given: In = serde_json::from_slice(&bytes).map_err(|e| match private {
            true => "The shared library was handed something it doesn't take.".to_string(),
            false => format!("The shared library was handed something it doesn't take: {e}"),
        })?;
        let answer = serde_json::to_vec(&act(given)?).map_err(|e| format!("The shared library's answer couldn't be written out: {e}"))?;
        Ok(env.byte_array_from_slice(&answer).map_err(|e| format!("The shared library's answer couldn't be handed back: {e}"))?.into_raw())
    }));
    let why = match outcome {
        Ok(Ok(answer)) => return answer,
        Ok(Err(why)) => why,
        // (Not what it said: see the top of this file. Where it stopped is in the log.)
        Err(_) => "The shared library stopped.".to_string(),
    };
    // (Unless Java already has an exception of its own under way, which then stands.)
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new(EXCEPTION, why);
    }
    std::ptr::null_mut()
}

// ---- keeping devices in step: which playlist holds the record --------------------------------

#[derive(Deserialize)]
struct FindIn {
    /// The account's playlists as the server stated them.
    listed: Vec<Stated>,
    /// The account's name, as the phone signs in with it.
    username: String,
}

/// `kind` is "ours" (with `id`), "none" or "not_stated".
#[derive(Serialize)]
struct FindOut {
    kind: &'static str,
    id: String,
}

/// `nexus_core::step::find`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_find<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: FindIn| {
        Ok(match step::find(&given.listed, &given.username) {
            Found::Ours(id) => FindOut { kind: "ours", id },
            Found::None => FindOut { kind: "none", id: String::new() },
            Found::NotStated => FindOut { kind: "not_stated", id: String::new() },
        })
    })
}

// ---- keeping devices in step: an exchange ---------------------------------------------------
//
// The steps an app follows around these are numbered at the top of the core's `step.rs`;
// the phone's side of them is `sync/InStep.kt`. What the phone only hands back unread (the
// choices it keeps, a plan's `take` and `write`) crosses as the core's own JSON for it.

type Plays = Vec<(String, i64)>;
type Sources = Vec<(String, i64, String)>;

#[derive(Deserialize)]
struct SettleIn {
    username: String,
    /// The sign-in, exactly: the server's address and the username.
    account: String,
    choices: Choices,
    /// The phone's dated notes (`notes_shared`).
    notes: Notes,
    /// Recently played, newest first.
    history: Plays,
    /// Plays from the phone's log: **every play at least as new as its 500th newest**, in
    /// any order, and where those made from a playlist were made from. The newest are
    /// chosen here, by the core's rule; the phone only spares carrying its whole log across.
    plays: Plays,
    play_sources: Sources,
    listed: Vec<Stated>,
    playlist: Option<Stated>,
    /// The server's clock as its last answer gave it, and the phone's as that arrived.
    date: Option<i64>,
    received: i64,
}

/// Why, as a name the phone can tell kinds apart by and as words to show.
#[derive(Serialize)]
struct Said {
    kind: String,
    words: &'static str,
}

#[derive(Serialize)]
struct ClockOut {
    /// The server gave a time to compare with.
    measured: bool,
    /// The phone's clock is more than five minutes from the server's.
    wrong: bool,
}

fn clock_out(clock: Clock) -> ClockOut {
    ClockOut { measured: matches!(clock, Clock::Offset(_)), wrong: clock.wrong() }
}

#[derive(Serialize)]
struct PlanOut {
    /// Step 5: to be in the phone's log, on disk, before anything else is done.
    keep_plays: Plays,
    keep_sources: Sources,
    /// Step 6: for `take`, unread; and what of it the phone's own stores take in.
    take: Take,
    notes: Notes,
    history: Plays,
    held: Option<Said>,
    /// Step 7: for `sending` and `written`, unread; and what of it the phone sends.
    write: Option<Write>,
    write_into: Option<String>,
    write_text: String,
    write_found: String,
}

#[derive(Serialize)]
struct SettleOut {
    clock: ClockOut,
    stop: Option<Said>,
    plan: Option<PlanOut>,
}

/// `nexus_core::step::settle`, with `clock` beside it (steps 2 to 4).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_settle<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: SettleIn| {
        let plays = sync::newest_plays(given.plays.iter());
        let newest: HashSet<(&str, i64)> = plays.iter().map(|(song, at)| (song.as_str(), *at)).collect();
        let play_sources = given.play_sources.into_iter().filter(|(song, at, _)| newest.contains(&(song.as_str(), *at))).collect();
        let device = Device { username: given.username, account: given.account, choices: given.choices, notes: given.notes, history: given.history, plays, play_sources };
        let answers = Answers { listed: given.listed, playlist: given.playlist, date: given.date, received: given.received };
        let clock = clock_out(step::clock(&answers));
        Ok(match step::settle(&device, &answers) {
            Outcome::Stop(stop) => SettleOut { clock, stop: Some(Said { kind: format!("{stop:?}"), words: stop.words() }), plan: None },
            Outcome::Go(plan) => {
                let plan = *plan;
                let (write_into, write_text, write_found) = plan.write.as_ref().map(|write| (write.into.clone(), write.text.clone(), write.found.clone())).unwrap_or_default();
                let out = PlanOut {
                    keep_plays: plan.keep.plays,
                    keep_sources: plan.keep.sources,
                    notes: plan.take.notes.clone(),
                    history: plan.take.history.clone(),
                    take: plan.take,
                    held: plan.held.map(|held| Said { kind: format!("{held:?}"), words: held.words() }),
                    write: plan.write,
                    write_into,
                    write_text,
                    write_found,
                };
                SettleOut { clock, stop: None, plan: Some(out) }
            }
        })
    })
}

#[derive(Deserialize)]
struct TakeIn {
    choices: Choices,
    take: Take,
}

/// `Choices::take` (step 6).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_take<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: TakeIn| {
        given.choices.take(&given.take);
        Ok(given.choices)
    })
}

#[derive(Deserialize)]
struct WriteIn {
    choices: Choices,
    write: Write,
}

#[derive(Serialize)]
struct SendingOut {
    choices: Choices,
    /// The write still fits the choices it was made from, and may be sent.
    fits: bool,
}

/// `Choices::sending` (step 7.2).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_sending<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: WriteIn| {
        let fits = given.choices.sending(&given.write);
        Ok(SendingOut { choices: given.choices, fits })
    })
}

/// `Choices::written` (step 7.4).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_written<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: WriteIn| {
        given.choices.written(&given.write);
        Ok(given.choices)
    })
}

/// Something the person changed, as the phone names it.
#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum ChangeIn {
    Pin { id: String },
    Unpin { id: String },
    Shuffle { key: String, on: bool },
    Sort { key: String, sort: usize, descending: bool },
    Usual { key: String },
    Theme { name: String },
}

impl From<ChangeIn> for Change {
    fn from(change: ChangeIn) -> Change {
        match change {
            ChangeIn::Pin { id } => Change::Pin(id),
            ChangeIn::Unpin { id } => Change::Unpin(id),
            ChangeIn::Shuffle { key, on } => Change::Shuffle { key, on },
            ChangeIn::Sort { key, sort, descending } => Change::Sort { key, sort, descending },
            ChangeIn::Usual { key } => Change::Usual { key },
            ChangeIn::Theme { name } => Change::Theme(name),
        }
    }
}

#[derive(Deserialize)]
struct ChangeArgs {
    choices: Choices,
    account: String,
    change: ChangeIn,
}

#[derive(Serialize)]
struct ChangeOut {
    choices: Choices,
    /// Something now shown differs.
    changed: bool,
}

/// `Choices::change`: the person changed something shared.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_change<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: ChangeArgs| {
        let changed = given.choices.change(&given.account, given.change.into());
        Ok(ChangeOut { choices: given.choices, changed })
    })
}

#[derive(Deserialize)]
struct ShownIn {
    choices: Choices,
    account: String,
}

#[derive(Serialize)]
struct ShownOut {
    pins: Vec<String>,
    /// The record has said something about the pins (they have a time, or there are some
    /// agreed). Until it has, the phone's own pins are nobody's but the phone's.
    pins_said: bool,
    /// Empty when no theme is agreed and none is waiting.
    theme: String,
    lists: HashMap<String, PrefsOut>,
}

/// One list's choices, as the phone holds them: the order is a number that fits in 31 bits
/// there (the orders there are number a handful; a record can hold any number).
#[derive(Serialize)]
struct PrefsOut {
    shuffle: bool,
    sort: i32,
    descending: bool,
    sort_chosen: bool,
    at: i64,
}

impl From<ListPrefs> for PrefsOut {
    fn from(prefs: ListPrefs) -> PrefsOut {
        PrefsOut { shuffle: prefs.shuffle, sort: i32::try_from(prefs.sort).unwrap_or(i32::MAX), descending: prefs.descending, sort_chosen: prefs.sort_chosen, at: prefs.at }
    }
}

/// `Choices::shown`: what was agreed, with this account's waiting changes on top.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_shown<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: ShownIn| {
        let shown = given.choices.shown(&given.account);
        let agreed = &given.choices.agreed;
        Ok(ShownOut { pins_said: agreed.pins_at != 0 || !agreed.pins.is_empty(), pins: shown.pins, theme: shown.theme, lists: shown.lists.into_iter().map(|(key, prefs)| (key, prefs.into())).collect() })
    })
}

#[derive(Deserialize)]
struct SortBackIn {
    choices: Choices,
    account: String,
    key: String,
}

/// The person has chosen, for the list `key`, an order only this device offers. A sort for
/// that list still waiting to be shared, under this account, is taken back: it is no longer
/// what the device shows or wants. This is the first thing [`Choices::change`] does for any
/// later word on the same thing (a change not yet tried gives way to it); here the later
/// word is the device's own and is not a change at all. One already tried stays: its write
/// may have arrived.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_sortTakenBack<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: SortBackIn| {
        given.choices.waiting.retain(|waits| waits.account != given.account || waits.tried || !matches!(&waits.change, Change::Sort { key, .. } if *key == given.key));
        Ok(given.choices)
    })
}

/// A change as builds of the phone app before the shared core kept it.
#[derive(Deserialize)]
#[serde(default)]
#[derive(Default)]
struct Formerly {
    what: String,
    key: String,
    on: bool,
    sort: i64,
    descending: bool,
}

#[derive(Deserialize)]
struct AdoptIn {
    /// What was agreed with the server, each with the record's own time for it.
    agreed: Agreed,
    /// The changes still waiting, oldest first.
    waiting: Vec<Formerly>,
    account: String,
    /// The times on `agreed` were put there by rules since thrown out (a build that stamped
    /// whole values with "now"): each choice is kept, as chosen "long ago", so that it is
    /// offered and loses to anything chosen since on any device. `usual_theme` is the theme
    /// nobody chose.
    demote: bool,
    usual_theme: String,
}

/// The choices a build from before the shared core kept (what it had agreed, and the
/// changes still waiting, as it wrote them), as the core keeps them. Done once, at the
/// upgrade.
///
/// The released build that kept changes waiting (0.4.0) had keeping in step switched off:
/// it never exchanged, so it had agreed nothing, and nothing chosen on it was a statement
/// about the person's other devices. So a waiting change to something **never agreed with
/// the server** is not a change any more:
///
/// * a theme, a list's shuffle and a list's sort become what the device holds, as chosen
///   "long ago" ([`sync::LONG_AGO`], as [`Agreed::from_stamped`] has what an earlier build
///   stamped): offered, and losing to anything chosen since on any device;
/// * a pin stays a change, of that one playlist: it is added to whatever pins the server
///   has, and replaces nothing. (As a whole list chosen long ago it would lose to the other
///   devices' list, and the playlist the person pinned would no longer be pinned.) An unpin
///   took back a pin of the device's own, and is dropped: the device never showed the
///   other devices' pins, so it was never a wish to unpin one of theirs.
///
/// A waiting change to something that **had** been agreed with the server, at the record's
/// own time (a build that did exchange: none released), is a change still, made again in
/// order under the account.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_adopt<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: AdoptIn| {
        let mut agreed = if given.demote {
            let lists = given.agreed.lists.iter().map(|(key, prefs)| (key.clone(), ListPrefs { at: 0, ..*prefs })).collect();
            Agreed::from_stamped(&given.agreed.pins, 0, &given.agreed.theme, 0, &lists, &given.usual_theme)
        } else {
            given.agreed
        };
        // Whether each was agreed with the server, as the build that wrote this had it.
        let pins_agreed = agreed.pins_at > sync::LONG_AGO;
        let theme_agreed = agreed.theme_at > sync::LONG_AGO;
        let lists_agreed: HashSet<String> = agreed.lists.iter().filter(|(_, prefs)| prefs.at > sync::LONG_AGO).map(|(key, _)| key.clone()).collect();
        let mut still = Vec::new();
        for was in given.waiting {
            match was.what.as_str() {
                "pin" => still.push(Change::Pin(was.key)),
                "unpin" if pins_agreed => still.push(Change::Unpin(was.key)),
                // (The last word on that playlist: a pin of it made before is taken back.)
                "unpin" => still.retain(|change| *change != Change::Pin(was.key.clone())),
                "shuffle" if lists_agreed.contains(&was.key) => still.push(Change::Shuffle { key: was.key, on: was.on }),
                "shuffle" => {
                    let prefs = agreed.lists.entry(was.key).or_default();
                    *prefs = ListPrefs { shuffle: was.on, at: sync::LONG_AGO, ..*prefs };
                }
                "sort" if was.sort < 0 => {}
                "sort" if lists_agreed.contains(&was.key) => still.push(Change::Sort { key: was.key, sort: was.sort as usize, descending: was.descending }),
                "sort" => {
                    let prefs = agreed.lists.entry(was.key).or_default();
                    *prefs = ListPrefs { sort: was.sort as usize, descending: was.descending, sort_chosen: true, at: sync::LONG_AGO, ..*prefs };
                }
                "theme" if theme_agreed => still.push(Change::Theme(was.key)),
                "theme" => {
                    agreed.theme = was.key;
                    agreed.theme_at = sync::LONG_AGO;
                }
                // (Nothing an earlier build wrote.)
                _ => {}
            }
        }
        let mut choices = Choices { agreed, ..Default::default() };
        for change in still {
            choices.change(&given.account, change);
        }
        Ok(choices)
    })
}

#[derive(Deserialize)]
struct HistoryIn {
    mine: Plays,
    theirs: Plays,
}

/// `sync::merge_history`: Recently played here and as another device has it, as one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_history<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: HistoryIn| Ok(sync::merge_history(&given.mine, &given.theirs)))
}

#[derive(Serialize)]
struct Names {
    playlist: &'static str,
    reserved: &'static str,
    history_length: usize,
    plays_shared: usize,
}

/// What the phone has to know by name: the record's playlist (it is never listed, played or
/// edited there), what to say when that name is asked for, and the two lengths.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_names<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |_: ()| Ok(Names { playlist: sync::PLAYLIST_NAME, reserved: step::RESERVED, history_length: sync::HISTORY_LENGTH, plays_shared: sync::PLAYS_SHARED }))
}

/// `step::reserved`: whether a playlist of the person's own may not have this name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_reserved<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |name: String| Ok(step::reserved(&name)))
}

// ---- when songs were added to playlists -------------------------------------------------------
//
// The phone keeps the notes (`store/Added.kt`); every rule about them is the core's
// (`notes::Kept`). A call about one playlist carries that playlist's notes and nothing else:
// the core's functions touch only the playlist they are given.

const ONE: &str = "p";

#[derive(Deserialize, Serialize, Default)]
#[serde(default)]
struct One {
    /// By song id.
    notes: HashMap<String, Note>,
    /// The phone has looked at this playlist before.
    seen: bool,
}

impl One {
    fn kept(self) -> Kept {
        let mut kept = Kept::default();
        if !self.notes.is_empty() {
            kept.lists.insert(ONE.to_string(), self.notes);
        }
        if self.seen {
            kept.seen.insert(ONE.to_string());
        }
        kept
    }

    fn of(mut kept: Kept) -> One {
        One { notes: kept.lists.remove(ONE).unwrap_or_default(), seen: kept.seen.contains(ONE) }
    }
}

#[derive(Serialize)]
struct OneOut {
    #[serde(flatten)]
    one: One,
    /// `notice`: something changed, to be saved. The others: something worth sharing did.
    told: bool,
}

#[derive(Deserialize)]
struct NoticeIn {
    #[serde(flatten)]
    one: One,
    ids: Vec<String>,
    now: i64,
}

/// `Kept::notice`: a copy of the playlist has been read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesNotice<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: NoticeIn| {
        let mut kept = given.one.kept();
        let ids: Vec<&str> = given.ids.iter().map(String::as_str).collect();
        let told = kept.notice(ONE, &ids, given.now);
        Ok(OneOut { one: One::of(kept), told })
    })
}

#[derive(Deserialize)]
struct ChangedIn {
    #[serde(flatten)]
    one: One,
    /// The playlist as the change was worked out from, and what the change made of it.
    before: Vec<String>,
    after: Vec<String>,
    /// For a change that took songs out: where in `before` they were. (Then `after` is not
    /// looked at: which copies went is what counts.)
    removed: Option<Vec<usize>>,
    now: i64,
}

/// `Kept::changed`, or `Kept::edited` for a removal: the person changed the playlist here,
/// and the server has taken the change.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesChanged<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: ChangedIn| {
        let mut kept = given.one.kept();
        let told = match given.removed {
            Some(places) => kept.edited(ONE, &given.before, &Edit::Remove(places.iter().filter_map(|&place| spot(&given.before, place)).collect()), given.now),
            None => kept.changed(ONE, &given.before, &given.after, given.now),
        };
        Ok(OneOut { one: One::of(kept), told })
    })
}

#[derive(Deserialize)]
struct AddedIn {
    #[serde(flatten)]
    one: One,
    ids: Vec<String>,
    /// The playlist has just been made by the app, with these songs in it.
    fresh: bool,
    now: i64,
}

/// `Kept::added`, or `Kept::create`: songs the app has just added to the playlist.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesAdded<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: AddedIn| {
        let mut kept = given.one.kept();
        if given.fresh { kept.create(ONE, &given.ids, given.now) } else { kept.added(ONE, &given.ids, given.now) }
        Ok(OneOut { one: One::of(kept), told: !given.ids.is_empty() })
    })
}

#[derive(Deserialize)]
struct DatesIn {
    notes: HashMap<String, Note>,
    ids: Vec<String>,
}

/// `Kept::dates`: when each of these songs was added, in order; 0 where it isn't known.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesDates<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: DatesIn| {
        let kept = One { notes: given.notes, seen: true }.kept();
        let ids: Vec<&str> = given.ids.iter().map(String::as_str).collect();
        Ok(kept.dates(ONE, &ids))
    })
}

#[derive(Deserialize)]
struct SharedIn {
    lists: Notes,
    /// The playlists that still exist, if the phone has a list of them.
    playlists: Option<Vec<String>>,
}

/// `Kept::shared`: the notes worth sharing with other devices.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesShared<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: SharedIn| {
        let kept = Kept { lists: given.lists, ..Default::default() };
        let known: Option<HashSet<&str>> = given.playlists.as_ref().map(|ids| ids.iter().map(String::as_str).collect());
        Ok(kept.shared(known.as_ref()))
    })
}

#[derive(Deserialize)]
struct NotesTakeIn {
    lists: Notes,
    theirs: Notes,
}

#[derive(Serialize)]
struct NotesTakeOut {
    lists: Notes,
    changed: bool,
}

/// `notes::take`: notes from other devices taken in.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_notesTake<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |mut given: NotesTakeIn| {
        let changed = notes::take(&mut given.lists, &given.theirs);
        Ok(NotesTakeOut { lists: given.lists, changed })
    })
}

// ---- the play queue between devices -----------------------------------------------------------
//
// The steps for a save and for a look are numbered at the top of the core's `handover.rs`;
// the phone's side of them is `sync/QueueStep.kt`.

/// A song of a queue, as far as these rules look at one.
#[derive(Deserialize, Default)]
#[serde(default)]
struct QueueSong {
    id: String,
    /// In seconds.
    duration: u32,
    /// A radio station: not something another device could pick up by id.
    radio: bool,
}

impl QueueSong {
    fn song(&self) -> Song {
        Song { id: self.id.clone(), duration: self.duration, radio_url: if self.radio { "radio".into() } else { String::new() }, ..Default::default() }
    }
}

/// The server's saved queue, as it answered.
#[derive(Deserialize, Default)]
#[serde(default)]
struct Theirs {
    songs: Vec<QueueSong>,
    current: String,
    /// Milliseconds into that song.
    position: u64,
    changed: String,
    changed_by: String,
    /// The server's clock as that answer gave it, and the phone's as it arrived.
    date: Option<i64>,
    received: i64,
}

impl Theirs {
    fn queue(&self) -> PlayQueue {
        PlayQueue { songs: self.songs.iter().map(QueueSong::song).collect(), current: self.current.clone(), position: self.position, changed: self.changed.clone(), changed_by: self.changed_by.clone() }
    }

    /// The phone's clock beside the server's, as measured by this very read.
    fn clock(&self) -> Clock {
        Clock::measured(self.date, self.received)
    }
}

#[derive(Deserialize)]
struct WindowIn {
    /// The queue in the order it plays.
    songs: Vec<QueueSong>,
    cursor: usize,
    /// Seconds into the current song.
    position: f64,
}

/// `handover::window`: what of a queue is saved on the server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_queueWindow<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: WindowIn| -> Result<Option<Saved>, String> {
        let songs: Vec<Song> = given.songs.iter().map(QueueSong::song).collect();
        Ok(handover::window(&songs.iter().collect::<Vec<_>>(), given.cursor, given.position))
    })
}

/// `handover::first`: whether a save begins by reading the server's saved queue.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_queueFirst<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |here: Here| Ok(match handover::first(&here) { First::Read => "read", First::Save => "save" }))
}

#[derive(Deserialize)]
struct SaveIn {
    theirs: Theirs,
    here: Here,
}

/// `handover::save`: whether this device's queue is still the newest thing that happened.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_queueSave<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: SaveIn| {
        Ok(match handover::save(&given.theirs.queue(), &given.here, given.theirs.clock()) {
            Save::Save => "save",
            Save::NotNewest => "not_newest",
            Save::NotNow => "not_now",
        })
    })
}

#[derive(Deserialize)]
struct LookIn {
    theirs: Theirs,
    here: Here,
    /// The phone's clock.
    now: i64,
}

#[derive(Serialize)]
struct LookOut {
    /// The song of the server's queue to offer as "Continue here" (its place there).
    offer: Option<usize>,
    /// "nothing", "take_up" (with `place`) or "save".
    then: &'static str,
    place: Option<Place>,
    clock: ClockOut,
}

/// `handover::look`: what a look at the server's saved queue leads to.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_queueLook<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: LookIn| {
        let clock = given.theirs.clock();
        let looked = handover::look(&given.theirs.queue(), &given.here, clock, given.now);
        let (then, place) = match looked.then {
            Then::Nothing => ("nothing", None),
            Then::TakeUp(place) => ("take_up", Some(place)),
            Then::Save => ("save", None),
        };
        Ok(LookOut { offer: looked.offer, then, place, clock: clock_out(clock) })
    })
}

/// `handover::place`: where to pick a saved queue up ("Continue here").
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_queuePlace<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |theirs: Theirs| Ok(handover::place(&theirs.queue(), theirs.clock())))
}

/// `handover::device_name`: the name this device saves the queue under.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_deviceName<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |id: String| Ok(handover::device_name(&id)))
}

// ---- the Nexus server: is it there? -----------------------------------------------------------

#[derive(Deserialize)]
struct HelloIn {
    /// The answer's status, as the transport got it (a redirect is never followed).
    status: u16,
    /// What the phone's transport read of the body, in order: to its end, or to one byte past
    /// the most a `hello` may be, whichever came first.
    body: Vec<u8>,
    /// False if reading the body failed before its end.
    whole: bool,
}

/// `backend`: whether it is the Nexus server. `why`: if not, in a word, for the tests.
#[derive(Serialize)]
struct HelloOut {
    backend: bool,
    why: &'static str,
    backend_id: Option<String>,
    diagnostics_default: Option<bool>,
}

/// The body as the phone's transport read it, handed to the core as the reader it asks for:
/// the bytes read, and then the end, or (if reading failed before the end) the failure. So the
/// core reads the body exactly as the transport did, never a copy cut to fit.
struct AsRead<'a> {
    bytes: &'a [u8],
    whole: bool,
}

impl std::io::Read for AsRead<'_> {
    fn read(&mut self, into: &mut [u8]) -> std::io::Result<usize> {
        if self.bytes.is_empty() {
            return if self.whole { Ok(0) } else { Err(std::io::Error::other("the body was cut off")) };
        }
        let n = into.len().min(self.bytes.len());
        into[..n].copy_from_slice(&self.bytes[..n]);
        self.bytes = &self.bytes[n..];
        Ok(n)
    }
}

/// `nexus::proto::classify`: whether an answer to `GET /nexus/api/hello` is the Nexus server's.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusHello<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    use nexus_core::nexus::proto::{Found, NoBackend, classify};
    call(env, input, |given: HelloIn| {
        let found = classify(given.status, AsRead { bytes: &given.body, whole: given.whole });
        let why = match &found {
            Found::Backend(_) => "",
            Found::NoBackend(NoBackend::Redirect) => "redirect",
            Found::NoBackend(NoBackend::Status(_)) => "status",
            Found::NoBackend(NoBackend::Empty) => "empty",
            Found::NoBackend(NoBackend::TooLarge) => "too_large",
            Found::NoBackend(NoBackend::Cut) => "cut",
            Found::NoBackend(NoBackend::NotHello(_)) => "not_hello",
        };
        let (backend_id, diagnostics_default) = match found {
            Found::Backend(hello) => (
                Some(hello.id.to_hex()),
                Some(nexus_core::nexus::diag::enabled(None, hello.diagnostics_default())),
            ),
            Found::NoBackend(_) => (None, None),
        };
        Ok(HelloOut { backend: why.is_empty(), why, backend_id, diagnostics_default })
    })
}

// ---- for the tests of the boundary itself ------------------------------------------------------
//
// (In every build of the library, and called by nothing in a release build of the app: see
// `stop`, below.)

/// Hands back the text it was given, having read it as text and written it again: what
/// crosses, crosses exactly.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_echo<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: String| Ok(given))
}

/// Panics, with the words it was given: for the check that a panic reaches Kotlin as an
/// exception without those words, and the app carries on.
///
/// **This and `echo` above are in the library every build carries, the release build's
/// too, and nothing in a release build calls either**: the unit tests do (`CoreTest`), and
/// a debug build when the test rig asks (`DebugStep`, which a release build has only an
/// empty version of). They stay in rather than being built out because the library is
/// built once for every kind of build, so that the library that ships is the library that
/// was tested. Called anyway, the one hands back its text and the other is caught at the
/// boundary like any panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_stop<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: String| -> Result<(), String> { panic!("{given}") })
}
