//! Linking a person's devices, as the phone calls it: the layer between
//! `nexus_core::link::bind` and the Kotlin side. `docs/linking-design.md` in the Windows
//! repository, section 8.1, is the contract in full; this says the same thing in code, and
//! a Kotlin adapter is written from the two.
//!
//! **The phone's player is asked on its own thread and answers at once.** Linking lives
//! where the player does: in the playback service, on the application thread, with the
//! player itself (never through a `MediaController`, which answers later).
//!
//! What the Kotlin side **supplies** is one object, handed over once (`linkNew`). These
//! are called on the player's thread, from inside `linkHappened` or `linkTurn`, and are
//! answered before they return. Times are seconds, as a decimal number; text is UTF-8
//! bytes holding JSON.
//!
//! ```text
//! brief(): ByteArray          {"playing":…,"title":…,"artist":…}
//! full(): ByteArray           {"position":…,"now":entry|null,"coming":[entry…],"in_all":…,
//!                              "context":…,"shuffle":…,"repeat":0|1|2,"volume":0..1}
//!                             entry: {"id","title","artist","album","duration","queued"}
//!                             `coming`: the first 200 to come at the most; `in_all`: how many are to come
//! handed(): ByteArray         {"songs":[id…],"queued":…,"position":…,"context":…}
//!                             from the song playing on; {"songs":[]} for nothing another device could carry on with
//! queue(): Long               the number of the queue in the player
//! sounding(): ByteArray?      {"queue":…,"at":…} while audio is advancing for the queue in the player; null otherwise
//! hasSong(): Boolean
//! ahead(): ByteArray          the ids of everything to come, in the order it would play, a JSON list
//!                             (asked only when a command names an entry that is not where it says)
//! entryAt(Int): ByteArray?    the id of the song that many places after the current one, as it is; null if
//!                             the queue does not reach so far
//! toCome(): Int               how many are to come
//! carryOut(ByteArray): Int    {"command":{"do":…,"with":{…}},"ready":…}
//!                             1: it changed the player   2: it was so already   0: it refused, or could not
//! pause(): Boolean            stop the phone's own sound; whether it is then not set to play
//! take(ByteArray)             {"songs":[[id,queued]…],"begin":…,"position":…,"context":…}
//! claim()                     the queue is the phone's own from now
//! handedOn(): Boolean         the queue has been handed to another device; whether that is written in its file
//! handedBack()                it has not after all: the phone did not stop
//! ```
//!
//! **None of those can fail.** If one throws, or cannot be made, or answers with
//! something that is not what it answers with, this layer puts nothing in its place: no
//! zero, no "not playing", no "not done". It clears the exception and stops the call by
//! panicking, which the library catches where it catches a fault of its own: everything
//! is replaced, the app is told (`{"act":"fault"}`), and linking is off on the phone until
//! the app is next started.
//!
//! And three that may be called from any thread, which answer for themselves:
//!
//! ```text
//! random(Int): ByteArray?     that many bytes from SecureRandom, or null
//! elapsed(): Long             milliseconds on the clock that counts through sleep
//! wake()                      post a call of `linkTurn` to the player's looper (never run it here)
//! ```
//!
//! What it **calls**:
//!
//! ```text
//! linkNew        once for each playback service, on the player's thread, with the object
//!                above. Answers with **the number of this linking**, which every other
//!                call is given; with the number of its first sign-in; and with the
//!                sentence for the person if linking stops for a fault
//! linkClose      on the player's thread, as that service goes or after a fault
//! linkHappened   on the player's thread: the tick, a way into the player taken ("changed"),
//!                a look-up beginning and ending, what the person presses, the sign-in ending
//! linkTurn       on the player's thread, when woken, and whenever an answer says there is more
//! linkLibrary    on whichever thread reads the phone's copy of the library, with the number
//!                of the sign-in it was read for
//! linkLook, linkStay, linkSend, linkFind, linkFound
//!                on threads of its own, one for each ask that `linkHappened` and
//!                `linkTurn` give back (look, begin, send, find)
//! ```
//!
//! `linkHappened` and `linkTurn` answer `{"acts":[…],"asks":[…],"more":…,"sign_in":…,"refused":…}`:
//! what to show (the list, the device being steered, what to say, whether a press was
//! another device's, that linking has stopped), which requests to start or end, whether
//! to call `linkTurn` again now, and the number of the sign-in.
//!
//! **What waits for the player's thread waits in the library** (`bind::Inbox`), bounded
//! there, and the library's `Phone::turn` is the one drain of it: `linkTurn` is one call
//! of that and has no loop. A request whose answer finds no room keeps it and offers it
//! again; nothing is dropped.
//!
//! **Everything belongs to one linking, and ends with it.** The way up into the player,
//! the place where what the requests bring waits, and the linking itself are made by
//! `linkNew` and let go of by `linkClose`: nothing of them is the process's. A call that
//! names a linking that has been closed, or one that another has taken the place of, does
//! nothing and says so; a request still under way for it finds nowhere to put what it
//! brought, and lets it go. So a service made again in the same process starts with
//! nothing of the one before, and nothing of the one before can reach it, whatever
//! numbers the two give their sign-ins, looks and sessions.
//!
//! **Calling from a player's listener is safe.** A listener that hears a change while the
//! library is inside `carryOut`, `pause` or `take` may call `linkHappened` with "changed"
//! like any other: the library knows it is its own act heard back and drops it. One that
//! hears anything else while the library is inside a call has it kept and decided when
//! that call returns. Only a press is refused there (it never comes from a listener).
//!
//! The secret never crosses: a look hands it straight to the library.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JValue};
use jni::sys::jbyteArray;
use jni::{JNIEnv, JavaVM};
use nexus_core::http::Http;
use nexus_core::link::bind::{Act, Answer, Came, Event, Happened, Inbox, Phone, PhonePlayer, Songs};
use nexus_core::link::command::Command;
use nexus_core::link::device::{self, Brief, Carried, Do, Ready, Sounding};
use nexus_core::link::find;
use nexus_core::link::look::{self, Account};
use nexus_core::link::message::{Entry, Full, Handed};
use nexus_core::link::relay::{self, Relay};
use nexus_core::link::seen::Seen;
use nexus_core::link::text;
use serde::{Deserialize, Serialize};

use crate::wire::{During, Host, Line, Whose};
use crate::{call, call_private};

// ---- the phone's player, called up into ----------------------------------------------------

/// The Kotlin object that is the phone's player to the library.
struct Door {
    vm: JavaVM,
    player: GlobalRef,
}

/// **One linking**: what one start of it (one playback service) has, and nothing else
/// has. Made by `linkNew`, closed by `linkClose` or by the next `linkNew`.
struct Link {
    /// Its number: never used twice while the app runs.
    number: u64,
    door: Door,
    /// What the requests bring back, waiting for the player's thread. Bounded, and
    /// drained, by the library.
    inbox: Inbox,
    closed: AtomicBool,
}

impl Link {
    /// Over: what was waiting is nobody's, and nothing more is taken.
    fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.inbox.clear();
    }

    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Puts something a request brought where it waits for the player's thread, unless
    /// this linking is over. Given back if there was no room.
    fn put(&self, what: Happened) -> Result<(), Happened> {
        if self.is_closed() {
            return Ok(());
        }
        self.inbox.put(what)?;
        // (Closed while it was being put: it is taken out again with whatever else is there.)
        if self.is_closed() {
            self.inbox.clear();
        }
        Ok(())
    }
}

/// The linking there is now, if there is one.
static LINK: RwLock<Option<Arc<Link>>> = RwLock::new(None);
static LINKS: AtomicU64 = AtomicU64::new(0);

const NOT_THIS_ONE: &str = "That linking has ended.";

/// The linking with this number, if it is the one there is now and has not been closed.
fn link(number: u64) -> Result<Arc<Link>, String> {
    LINK.read().unwrap_or_else(|stuck| stuck.into_inner()).clone().filter(|link| link.number == number && !link.is_closed()).ok_or_else(|| NOT_THIS_ONE.to_string())
}

impl Door {
    /// One call up. `None` if it couldn't be made or the Kotlin side threw; whatever it
    /// threw is cleared before this returns.
    fn up<T>(&self, act: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Option<T> {
        let mut env = self.vm.attach_current_thread().ok()?;
        let outcome = env.with_local_frame(8, |env| act(env, self.player.as_obj()));
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
        }
        outcome.ok()
    }

    /// One call up **into the player**, which cannot fail. If it could not be made, or the
    /// Kotlin side threw, nothing is put in place of the answer: the call the library is
    /// in stops here, and the library takes that for the fault it is. (The words name the
    /// call and nothing it was given or answered.)
    fn must<T>(&self, name: &str, act: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> T {
        self.up(act).unwrap_or_else(|| panic!("the phone's player could not be asked: {name}"))
    }

    /// A call into the player that takes nothing and answers with JSON.
    fn read<T: serde::de::DeserializeOwned>(&self, name: &str) -> T {
        let bytes = self.must(name, |env, player| {
            let answer = env.call_method(player, name, "()[B", &[])?.l()?;
            env.convert_byte_array(JByteArray::from(answer))
        });
        serde_json::from_slice(&bytes).unwrap_or_else(|_| panic!("the phone's player gave an answer that could not be read: {name}"))
    }

    /// A call into the player that takes JSON, if it takes anything, and answers nothing.
    fn tell(&self, name: &str, with: Option<&[u8]>) {
        self.must(name, |env, player| match with {
            Some(bytes) => {
                let bytes = env.byte_array_from_slice(bytes)?;
                env.call_method(player, name, "([B)V", &[JValue::Object(&bytes)]).map(drop)
            }
            None => env.call_method(player, name, "()V", &[]).map(drop),
        });
    }

    /// Milliseconds on the app's clock that counts through sleep. (If it cannot be asked,
    /// the end of time: whatever was being timed has run out.)
    fn elapsed(&self) -> u64 {
        self.up(|env, player| env.call_method(player, "elapsed", "()J", &[])?.j()).map_or(u64::MAX, |now| now.max(0) as u64)
    }

    /// Bytes from the system's source of randomness, or the word that there are none.
    fn random(&self, bytes: &mut [u8]) -> bool {
        let got = self.up(|env, player| {
            let answer = env.call_method(player, "random", "(I)[B", &[JValue::Int(bytes.len() as i32)])?.l()?;
            env.convert_byte_array(JByteArray::from(answer))
        });
        match got {
            Some(got) if got.len() == bytes.len() => {
                bytes.copy_from_slice(&got);
                true
            }
            _ => false,
        }
    }

    /// "Come for what is waiting": the player's thread is asked to take a turn. (If this
    /// cannot be said, what waits is found at the next call from that thread, whose
    /// answer says there is more.)
    fn wake(&self) {
        self.up(|env, player| env.call_method(player, "wake", "()V", &[]).map(drop));
    }
}

/// Everything the player shows, as the Kotlin side gives it: as many of what is to come
/// as a tell carries, and how many are to come in all.
#[derive(Deserialize)]
struct Shows {
    position: f64,
    now: Option<Entry>,
    coming: Vec<Entry>,
    in_all: usize,
    context: String,
    shuffle: bool,
    repeat: u8,
    volume: f64,
}

/// What could be carried on with, as the Kotlin side gives it.
#[derive(Deserialize)]
struct Carries {
    songs: Vec<String>,
    #[serde(default)]
    queued: usize,
    #[serde(default)]
    position: f64,
    #[serde(default)]
    context: String,
}

impl PhonePlayer for &Door {
    fn brief(&self) -> Brief {
        self.read("brief")
    }
    fn full(&self) -> Full {
        let shows: Shows = self.read("full");
        Full::fitted(shows.position, shows.now, shows.coming.into_iter(), shows.in_all, &shows.context, shows.shuffle, shows.repeat, shows.volume)
    }
    fn handed(&self) -> Handed {
        let carries: Carries = self.read("handed");
        if carries.songs.is_empty() { Handed::default() } else { Handed::fitted(&carries.songs, carries.queued, carries.position, &carries.context) }
    }
    fn sounding(&self) -> Option<Sounding> {
        // (Null is an answer: nothing is sounding.)
        let bytes = self.must("sounding", |env, player| {
            let answer = env.call_method(player, "sounding", "()[B", &[])?.l()?;
            if answer.is_null() { Ok(None) } else { env.convert_byte_array(JByteArray::from(answer)).map(Some) }
        })?;
        let sound: Sounding = serde_json::from_slice(&bytes).unwrap_or_else(|_| panic!("the phone's player gave an answer that could not be read: sounding"));
        (sound.at.is_finite() && sound.at >= 0.0).then_some(sound)
    }
    fn queue(&self) -> u64 {
        let number = self.must("queue", |env, player| env.call_method(player, "queue", "()J", &[])?.j());
        u64::try_from(number).unwrap_or_else(|_| panic!("the phone's player gave an answer that could not be read: queue"))
    }
    fn has_song(&self) -> bool {
        self.must("hasSong", |env, player| env.call_method(player, "hasSong", "()Z", &[])?.z())
    }
    fn ahead(&self) -> Vec<String> {
        self.read("ahead")
    }
    fn entry_at(&self, at: usize) -> Option<String> {
        // (A place no queue reaches: there is nothing there to ask after.)
        let at = i32::try_from(at).ok()?;
        let id = self.must("entryAt", |env, player| {
            let answer = env.call_method(player, "entryAt", "(I)[B", &[JValue::Int(at)])?.l()?;
            if answer.is_null() { Ok(None) } else { env.convert_byte_array(JByteArray::from(answer)).map(Some) }
        })?;
        Some(String::from_utf8(id).unwrap_or_else(|_| panic!("the phone's player gave an answer that could not be read: entryAt")))
    }
    fn to_come(&self) -> usize {
        let number = self.must("toCome", |env, player| env.call_method(player, "toCome", "()I", &[])?.i());
        usize::try_from(number).unwrap_or_else(|_| panic!("the phone's player gave an answer that could not be read: toCome"))
    }
    fn carry_out(&mut self, command: &Command, ready: &Ready) -> Carried {
        #[derive(Serialize)]
        struct Asked<'a> {
            command: &'a Command,
            ready: &'a Ready,
        }
        let asked = serde_json::to_vec(&Asked { command, ready }).unwrap_or_else(|_| panic!("a command could not be written out for the phone's player"));
        let came = self.must("carryOut", |env, player| {
            let asked = env.byte_array_from_slice(&asked)?;
            env.call_method(player, "carryOut", "([B)I", &[JValue::Object(&asked)])?.i()
        });
        match came {
            1 => Carried::Changed,
            2 => Carried::AlreadySo,
            0 => Carried::Refused,
            _ => panic!("the phone's player gave an answer that could not be read: carryOut"),
        }
    }
    fn handed_on(&mut self) -> bool {
        self.must("handedOn", |env, player| env.call_method(player, "handedOn", "()Z", &[])?.z())
    }
    fn handed_back(&mut self) {
        self.tell("handedBack", None);
    }
    fn pause(&mut self) -> bool {
        self.must("pause", |env, player| env.call_method(player, "pause", "()Z", &[])?.z())
    }
    fn take(&mut self, songs: &[(String, bool)], begin: usize, position: f64, context: &str) {
        let queue = serde_json::to_vec(&serde_json::json!({"songs": songs, "begin": begin, "position": position, "context": context})).unwrap_or_else(|_| panic!("a queue could not be written out for the phone's player"));
        self.tell("take", Some(&queue));
    }
    fn claim(&mut self) {
        self.tell("claim", None);
    }
}

// ---- the linking, on the player's thread ----------------------------------------------------

/// A linking on the player's thread: the library's half, with what it is one linking of.
struct Here {
    link: Arc<Link>,
    phone: Phone,
}

thread_local! {
    /// The linking belongs to the thread it was started on, which is the player's: a call
    /// from any other finds none.
    static HERE: RefCell<Option<Rc<Here>>> = const { RefCell::new(None) };
}

/// The linking with this number, on the player's thread, if it is still the one there is.
fn here(number: u64) -> Result<Rc<Here>, String> {
    let here = HERE.with(|here| here.borrow().clone()).ok_or_else(|| "Linking is called on the player's thread, once it has been started there.".to_string())?;
    if here.link.number != number || here.link.is_closed() {
        return Err(NOT_THIS_ONE.to_string());
    }
    Ok(here)
}

/// Puts what a request brought where it waits for the player's thread, and wakes that
/// thread. If there is no room, it is kept here and offered again a second later, **until
/// it is taken, the request it belongs to is stopped (`http`'s wait says so), or the
/// linking is over**: then there is nobody it is for, and it is let go.
fn bring(link: &Link, mut what: Happened, http: &dyn Http) {
    loop {
        if link.is_closed() {
            return;
        }
        match link.put(what) {
            Ok(()) => break,
            Err(back) => what = back,
        }
        if http.pause(Duration::from_secs(1)).is_err() {
            return;
        }
    }
    link.door.wake();
}

/// An ask of the library's, as it crosses: as the library writes it, but that the place a
/// frame is to be sent to goes as its text (which is how `linkSend` takes it back), not as
/// sixteen numbers.
fn crossing(ask: Do) -> serde_json::Value {
    match ask {
        Do::Send { session, token, to, frame } => serde_json::json!({"send": {"session": session, "token": token, "to": text::place(&to), "frame": frame}}),
        other => serde_json::to_value(&other).unwrap_or(serde_json::Value::Null),
    }
}

#[derive(Serialize, Default)]
struct Out {
    acts: Vec<Act>,
    asks: Vec<serde_json::Value>,
    /// More is waiting than this call worked through: call `linkTurn` again now.
    more: bool,
    /// The number of the sign-in the linking is for now.
    sign_in: u64,
    /// The call was made from inside one of the library's own calls with something that
    /// needs an answer, or with something too large: nothing was done. (An answer, not a
    /// failure: a failure of one of these calls is a fault on the app's side.)
    refused: bool,
}

/// What a call was answered with, as it crosses. (Heard from a listener while the library
/// was inside a call, it was kept for when that call returns, or was the library's own act
/// heard back: nothing comes of it here.)
fn out(here: &Here, answer: Answer) -> Result<Out, String> {
    let came = match answer {
        Answer::Came(came) => came,
        Answer::Kept | Answer::PartOfIt => Came::default(),
        Answer::Refused => return Ok(Out { sign_in: here.phone.sign_in(), refused: true, ..Out::default() }),
    };
    Ok(Out { acts: came.acts, asks: came.native.into_iter().map(crossing).collect(), more: came.more || !here.link.inbox.is_empty(), sign_in: here.phone.sign_in(), refused: false })
}

#[derive(Deserialize)]
struct New {
    device: String,
    name: String,
    remembered: Vec<Seen>,
}

#[derive(Serialize)]
struct Started {
    /// The number of this linking: what every other call is given.
    link: u64,
    sign_in: u64,
}

/// What the person is told if linking stops on the phone for a fault: the library's
/// words. Asked for before anything is started, and needing nothing to have been.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkStoppedWords<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |_: u64| Ok(nexus_core::link::words::fault(nexus_core::link::words::Here::Phone)))
}

/// Once for each playback service, on the player's thread. `player` is the object
/// described at the top. **Nothing is kept unless all of it could be**: what was given
/// is read first, and the linking, the way up into the player and the place where
/// requests' answers wait are put in place together, last. A linking there was before
/// (a service that went without closing its own) is closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkNew<'l>(env: JNIEnv<'l>, _: JClass<'l>, player: JObject<'l>, input: JByteArray<'l>) -> jbyteArray {
    let door = env.get_java_vm().ok().zip(env.new_global_ref(&player).ok()).map(|(vm, player)| Door { vm, player });
    call(env, input, |given: New| {
        let device = text::read_id(&given.device).ok_or("That isn't a device's id.")?;
        let door = door.ok_or("The shared library couldn't keep hold of the phone's player.")?;
        let phone = Phone::new(device, &given.name, given.remembered);
        let link = Arc::new(Link { number: LINKS.fetch_add(1, Ordering::SeqCst) + 1, door, inbox: Inbox::default(), closed: AtomicBool::new(false) });
        let started = Started { link: link.number, sign_in: phone.sign_in() };
        if let Some(before) = LINK.write().unwrap_or_else(|stuck| stuck.into_inner()).replace(link.clone()) {
            before.close();
        }
        HERE.with(|kept| *kept.borrow_mut() = Some(Rc::new(Here { link, phone })));
        Ok(started)
    })
}

/// The service that started this linking is going, or the linking has stopped for a
/// fault: everything of it is let go of. On the player's thread. **It cannot fail and may
/// be called twice**: a number that is not the linking there is closes nothing. **Zero
/// closes whatever there is**: for a start whose answer could not be read, which has no
/// number to name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkClose<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |number: u64| {
        let closed = {
            let mut now = LINK.write().unwrap_or_else(|stuck| stuck.into_inner());
            if now.as_ref().is_some_and(|link| number == 0 || link.number == number) { now.take() } else { None }
        };
        if let Some(link) = closed {
            link.close();
        }
        // (If this is the thread it lived on, as it should be, the library's half goes
        // too; if not, it goes when that thread next starts one, having been closed.)
        HERE.with(|kept| {
            let mut kept = kept.borrow_mut();
            if kept.as_ref().is_some_and(|here| number == 0 || here.link.number == number) {
                *kept = None;
            }
        });
        Ok(())
    })
}

/// When it is, as the Kotlin side says it: milliseconds on the clock that counts through
/// sleep, and the day (days since 1970, by the phone's calendar clock).
#[derive(Deserialize)]
struct At {
    now: u64,
    today: u64,
}

#[derive(Deserialize)]
struct Told {
    link: u64,
    event: Event,
    at: At,
}

#[derive(Deserialize)]
struct Turn {
    link: u64,
    at: At,
}

/// Something happened on the phone. On the player's thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkHappened<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: Told| {
        let here = here(given.link)?;
        let door = &here.link.door;
        let answer = here.phone.happened(Happened::Event(given.event), given.at.now, given.at.today, &mut |bytes| door.random(bytes), &mut &*door);
        out(&here, answer)
    })
}

/// A turn: what the library kept from the calls before, and then what the requests have
/// brought, a bounded number of things. On the player's thread: when woken, and again
/// whenever an answer says there is more. It has no loop of its own.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkTurn<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |Turn { link, at }: Turn| {
        // (A call from another thread finds no linking here, and has taken nothing.)
        let here = here(link)?;
        let door = &here.link.door;
        let answer = here.phone.turn(&here.link.inbox, at.now, at.today, &mut |bytes| door.random(bytes), &mut &*door);
        out(&here, answer)
    })
}

#[derive(Deserialize)]
struct Library {
    link: u64,
    /// The number of the sign-in the copy was read for: the one that was current when the
    /// reading began.
    sign_in: u64,
    ids: Vec<String>,
}

/// Every copy of the library handed over, each held here as well as by the linking, so
/// that the one the linking lets go of is not freed on the player's thread: it is freed
/// here, on whichever thread next brings one.
static COPIES: Mutex<Vec<Arc<Songs>>> = Mutex::new(Vec::new());

/// The phone's copy of the library changed, or was read for a new sign-in. On whichever
/// thread read it: the set is made here, and only put in place on the player's thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkLibrary<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: Library| {
        let link = link(given.link)?;
        let songs = Songs::of(given.ids);
        let let_go: Vec<Arc<Songs>> = {
            let mut copies = COPIES.lock().unwrap_or_else(|stuck| stuck.into_inner());
            let (kept, let_go) = std::mem::take(&mut *copies).into_iter().partition(|copy| Arc::strong_count(copy) > 1);
            *copies = kept;
            copies.push(songs.clone());
            let_go
        };
        drop(let_go);
        if !link.is_closed() {
            link.inbox.library(given.sign_in, songs);
            if link.is_closed() {
                link.inbox.clear();
            }
            link.door.wake();
        }
        Ok(())
    })
}

// ---- the requests, on threads of the app's own ----------------------------------------------
//
// Each is given an operation's number, as every request the library makes for the app is.
// **Ending that operation on the Kotlin side is how one of these is stopped**: the look
// when the library says `stop_look`, a session's stay (and its sends that have not gone)
// when it says `end`, all of them when the sign-in ends or linking stops for a fault.
// And each is given the number of the linking it is for: what it brings goes to that one
// or nowhere.

#[derive(Deserialize)]
struct Server {
    rest: String,
    form: String,
    username: String,
}

impl Server {
    fn account(self) -> Account {
        Account { rest: self.rest, form: self.form, username: self.username }
    }
}

#[derive(Deserialize)]
struct Look {
    link: u64,
    operation: u64,
    server: Server,
    number: u64,
    used: Option<String>,
    reset: bool,
    /// When it began, on the clock that counts through sleep.
    since: u64,
}

/// `look`: the whole look, on the thread it is called on. What it found is put where it
/// waits for the player's thread. Nothing of it is this call's answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkLook<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Look| {
        let _during = During::new(given.operation);
        let link = link(given.link)?;
        let host = Host::new(Whose::Operation(given.operation), Line::Music);
        let door = &link.door;
        let looked = look::look(
            &*host,
            &given.server.account(),
            given.used,
            given.reset,
            &mut || {
                let mut secret = [0u8; 32];
                door.random(&mut secret).then_some(secret)
            },
            &mut |_| {},
        );
        bring(&link, Happened::Looked { number: given.number, since_ms: given.since, looked, reset: given.reset }, &*host);
        Ok(())
    })
}

#[derive(Deserialize)]
struct Through {
    base: String,
    key: String,
}

#[derive(Deserialize)]
struct Stay {
    link: u64,
    operation: u64,
    relay: Through,
    session: u64,
    room: String,
}

/// `begin`: the stay at the relay, for as long as the session lasts, on the thread it is
/// called on. What it hears is put where it waits for the player's thread as it arrives;
/// frames there was no room for are not counted as handed over, and are asked for again.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkStay<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Stay| {
        let _during = During::new(given.operation);
        let host = Host::new(Whose::Operation(given.operation), Line::Relay);
        let relay = Relay { base: given.relay.base, key: given.relay.key };
        let link = link(given.link)?;
        let door = &link.door;
        relay::keep_place(
            &*host,
            &relay,
            &given.room,
            &mut || {
                let mut place = [0u8; 16];
                door.random(&mut place).then_some(place)
            },
            &mut |from| {
                // (A linking that is over takes it and lets it go: the stay is being ended.)
                let taken = link.put(Happened::FromRelay { session: given.session, from }).is_ok();
                if taken {
                    door.wake();
                }
                taken
            },
            &mut |_| {},
        );
        Ok(())
    })
}

#[derive(Deserialize)]
struct Send {
    link: u64,
    operation: u64,
    relay: Through,
    room: String,
    token: String,
    to: String,
    frame: String,
    /// When the session's secret was last read (`begin` and `renew` say), and now, on
    /// the clock that counts through sleep.
    since: u64,
    now: u64,
}

/// `send`. Answers with the relay's status, or null: if it gave none, or the session's
/// secret had run out of its time by the time this send's turn came, and nothing was sent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkSend<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Send| {
        let _during = During::new(given.operation);
        link(given.link)?;
        if !device::may_go(false, given.since, given.now) {
            return Ok(None);
        }
        let host = Host::new(Whose::Operation(given.operation), Line::Relay);
        let to = text::read_place(&given.to).ok_or("That isn't a place.")?;
        Ok(relay::send(&*host, &Relay { base: given.relay.base, key: given.relay.key }, &given.room, &given.token, &to, &given.frame))
    })
}

#[derive(Deserialize)]
struct Find {
    link: u64,
    operation: u64,
    server: Server,
    ids: Vec<String>,
    /// The moment the command these songs are for is given up, on the clock that counts
    /// through sleep (`find`'s `until_ms`).
    until: u64,
}

/// `find`: the songs of a command that the phone's copy of the library lacks, asked of the
/// music server on the thread this is called on, each request given what is then left of
/// the command's time and no more. **The songs are this call's answer and nothing else
/// comes of it**: the app keeps them where its player will find them, and then says which
/// it has (`linkFound`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkFind<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Find| {
        let _during = During::new(given.operation);
        let host = Host::new(Whose::Operation(given.operation), Line::Music);
        // (This layer reads no clock of its own: the app's, which counts through sleep.)
        let link = link(given.link)?;
        let door = &link.door;
        Ok(find::find(&*host, &given.server.account(), &given.ids, &mut || Duration::from_millis(given.until.saturating_sub(door.elapsed()))))
    })
}

#[derive(Deserialize)]
struct Found {
    link: u64,
    operation: u64,
    ticket: u64,
    ids: Vec<String>,
}

/// The songs a `find` brought are in the app's hands: these, by id. On the thread that
/// made the `find`, after it has kept them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkFound<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: Found| {
        let link = link(given.link)?;
        let host = Host::new(Whose::Operation(given.operation), Line::Music);
        bring(&link, Happened::Found { ticket: given.ticket, songs: given.ids }, &*host);
        Ok(())
    })
}

// ---- a request that could not be made at all -------------------------------------------------
//
// **Every asker gets an end.** A look, a stay or a look-up of songs that the app could not
// even begin (no thread for it, or its own failure on the way) is said to the library as
// that request having failed, at once, so that nothing is left waiting for it.

/// Puts the failure where what requests bring waits, and wakes the player's thread. (If
/// there is no room there, the library is busy with more than this and will find the
/// request gone at its own time limit.)
fn failed(link: u64, what: Happened) -> Result<(), String> {
    let link = self::link(link)?;
    if link.put(what).is_ok() {
        link.door.wake();
    }
    Ok(())
}

#[derive(Deserialize)]
struct LookFailed {
    link: u64,
    number: u64,
    since: u64,
    reset: bool,
}

/// The look with this number could not be made: as if the server could not be asked.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkLookFailed<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: LookFailed| failed(given.link, Happened::Looked { number: given.number, since_ms: given.since, looked: look::Looked::Failed, reset: given.reset }))
}

#[derive(Deserialize)]
struct Of {
    link: u64,
    /// The session, or the look-up's ticket.
    number: u64,
}

/// The session's stay at the relay could not be begun: said under the list.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkStayFailed<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: Of| failed(given.link, Happened::FromRelay { session: given.number, from: relay::FromRelay::Says(nexus_core::link::words::NO_STAY) }))
}

/// The look-up of songs with this ticket could not be made: it found none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_linkFindFailed<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: Of| failed(given.link, Happened::Found { ticket: given.number, songs: Vec::new() }))
}

// ---- for the tests of the phone's side, which have no second device -------------------------

/// **Only in the library built for the machine the unit tests run on** (`test-seams`:
/// `native/build` turns it on for that library and no other, so none of this is in a
/// library a phone carries). What another device of the same account, and the relay
/// between, would bring: handed straight to the linking on the player's thread, with no
/// relay and no request. The secret, the phone's place and the other device's are fixed.
#[cfg(feature = "test-seams")]
mod for_tests {
    use nexus_core::link::frame::{self, ASK, Keys, TELL};
    use nexus_core::link::look::Looked;
    use nexus_core::link::message::{self, Ask, Want, read_tell};
    use nexus_core::link::relay::FromRelay;
    use nexus_core::link::secret::Secret;

    use super::*;

    const MINE: [u8; 16] = [5; 16];
    const OTHER: [u8; 16] = [1; 16];

    fn keys() -> Keys {
        Keys::of(&Secret::new([7; 32]))
    }

    /// (The linking this thread has, whichever its number: the tests have one at a time.)
    fn decide(what: Happened, at: &At) -> Result<Out, String> {
        let here = HERE.with(|here| here.borrow().clone()).filter(|here| !here.link.is_closed()).ok_or(NOT_THIS_ONE)?;
        let door = &here.link.door;
        let answer = here.phone.happened(what, at.now, at.today, &mut |bytes| door.random(bytes), &mut &*door);
        out(&here, answer)
    }

    #[derive(Deserialize)]
    struct Looked_ {
        number: u64,
        at: At,
    }

    /// The look the library asked for has read a secret.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestLooked<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |given: Looked_| {
            let looked = Looked::Secret { secret: Secret::new([7; 32]), id: "k".into(), wrote: None };
            decide(Happened::Looked { number: given.number, since_ms: given.at.now, looked, reset: false }, &given.at)
        })
    }

    #[derive(Deserialize)]
    struct Entered {
        session: u64,
        at: At,
    }

    /// The stay at the relay has a place for the phone.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestEntered<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |given: Entered| decide(Happened::FromRelay { session: given.session, from: FromRelay::Entered { place: MINE, token: "a-token".into() } }, &given.at))
    }

    #[derive(Deserialize)]
    struct Asked {
        /// What makes this ask's nonce: each a different one.
        n: u8,
        /// `brief`, `full` or `queue`, for a look; else a command.
        look: Option<String>,
        command: Option<Command>,
        #[serde(default)]
        generation: u64,
        /// How the device that asks speaks, if not as this build does: 1 is the number
        /// left out, as a build from before it was said leaves it.
        #[serde(default)]
        speaks: Option<u64>,
    }

    /// A message as a device that speaks otherwise would have written it.
    fn respoken(message: String, speaks: Option<u64>) -> Result<String, String> {
        let Some(speaks) = speaks else { return Ok(message) };
        let mut fields: serde_json::Map<String, serde_json::Value> = serde_json::from_str(&message).map_err(|_| "That is not a message.")?;
        if speaks == 1 {
            fields.remove("speaks");
        } else {
            fields.insert("speaks".into(), speaks.into());
        }
        Ok(serde_json::Value::Object(fields).to_string())
    }

    #[derive(Deserialize)]
    struct Heard {
        session: u64,
        asks: Vec<Asked>,
        at: At,
    }

    /// The relay hands the phone these, sealed as the other device would seal them, in one
    /// hand-over.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestHeard<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |given: Heard| {
            let mut frames = Vec::new();
            for asked in given.asks {
                let ask = match (asked.look.as_deref(), asked.command) {
                    (Some("full"), _) => Ask::Look(Want::Full),
                    (Some("queue"), _) => Ask::Look(Want::Queue),
                    (Some(_), _) => Ask::Look(Want::Brief),
                    (None, Some(command)) => Ask::Do { command, at: message::At { generation: asked.generation, minute: nexus_core::link::minute(given.at.now) } },
                    (None, None) => return Err("An ask is a look or a command.".to_string()),
                };
                let text = respoken(ask.text(), asked.speaks)?;
                let sealed = frame::seal(&keys(), ASK, &[asked.n; 24], &OTHER, &MINE, text.as_bytes()).ok_or("That ask is too large to seal.")?;
                frames.push((text::place(&OTHER), sealed));
            }
            let from = FromRelay::Heard { places: vec![text::place(&MINE), text::place(&OTHER)], frames };
            decide(Happened::FromRelay { session: given.session, from }, &given.at)
        })
    }

    #[derive(Serialize)]
    struct Told {
        /// What made the nonce of the ask this answers, if it answers one.
        re: Option<u8>,
        ok: Option<bool>,
        why: Option<String>,
        /// What made the nonce of the command that made the state, if one is named.
        by: Option<u8>,
        done: bool,
        busy: bool,
        playing: bool,
        title: String,
        generation: u64,
        /// What the phone says it is called.
        name: String,
        /// How the phone says it speaks.
        speaks: u64,
        /// How many songs the queue it carries has, if it carries one.
        queue: Option<usize>,
    }

    #[derive(Deserialize)]
    struct Answered {
        session: u64,
        /// What the phone asked to be sent, sealed: the last look among them is answered.
        frames: Vec<String>,
        /// How the device that answers speaks, if not as this build does.
        speaks: Option<u64>,
        name: String,
        playing: bool,
        title: String,
        /// What a device steering it shows, if the look asked for that.
        #[serde(default)]
        full: Option<message::Full>,
        /// What it is playing and has to come, if the look asked for that.
        #[serde(default)]
        queue: Option<message::Handed>,
        at: At,
    }

    /// The other device answers a look the phone sent it: with what it is called, what it
    /// is playing, and how it speaks.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestAnswered<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |given: Answered| {
            let looks = given.frames.iter().filter_map(|sealed| frame::open(&keys(), sealed, &MINE, &OTHER)).filter(|opened| opened.kind == ASK);
            let look = looks.last().ok_or("The phone has sent the other device no look.")?;
            let tell = message::Tell {
                re: Some(look.nonce),
                ok: None,
                why: None,
                device: [9; 16],
                name: given.name,
                speaks: message::SPEAKS,
                at: message::At { generation: 1, minute: nexus_core::link::minute(given.at.now) },
                busy: false,
                by: None,
                done: false,
                playing: given.playing,
                title: given.title,
                artist: String::new(),
                steering: String::new(),
                full: given.full,
                queue: given.queue,
            };
            // (A frame's own nonce: any that the look's is not.)
            let mut nonce = look.nonce;
            nonce[0] ^= 0xff;
            let sealed = frame::seal(&keys(), TELL, &nonce, &OTHER, &MINE, respoken(tell.text(), given.speaks)?.as_bytes()).ok_or("That tell is too large to seal.")?;
            let from = FromRelay::Heard { places: vec![text::place(&MINE), text::place(&OTHER)], frames: vec![(text::place(&OTHER), sealed)] };
            decide(Happened::FromRelay { session: given.session, from }, &given.at)
        })
    }

    /// Whether a frame the phone asked to be sent to the other device is a command.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestIsCommand<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |sealed: String| {
            let opened = frame::open(&keys(), &sealed, &MINE, &OTHER).ok_or("That frame does not open.")?;
            Ok(opened.kind == ASK && matches!(message::read_ask(&opened.message), Some((Ask::Do { .. }, _))))
        })
    }

    /// What a frame the phone asked to be sent to the other device asks, as that device
    /// reads it: `{"t":"do","do":…,…}` for a command.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestAsked<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |sealed: String| {
            let opened = frame::open(&keys(), &sealed, &MINE, &OTHER).ok_or("That frame does not open.")?;
            if opened.kind != ASK {
                return Err("That is not an ask.".to_string());
            }
            serde_json::from_slice::<serde_json::Value>(&opened.message).map_err(|_| "That ask is not JSON.".to_string())
        })
    }

    /// A frame the phone asked to be sent to the other device, opened as that device would.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_medianexus_music_core_Core_linkTestOpen<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
        call(env, input, |sealed: String| {
            let opened = frame::open(&keys(), &sealed, &MINE, &OTHER).ok_or("That frame does not open.")?;
            let tell = read_tell(&opened.message).ok_or("That is not a tell.")?;
            Ok(Told {
                re: tell.re.map(|nonce| nonce[0]),
                ok: tell.ok,
                why: tell.why.map(|why| format!("{why:?}").to_lowercase()),
                by: tell.by.map(|nonce| nonce[0]),
                done: tell.done,
                busy: tell.busy,
                playing: tell.playing,
                title: tell.title,
                generation: tell.at.generation,
                name: tell.name,
                speaks: tell.speaks,
                queue: tell.queue.map(|queue| queue.songs.len()),
            })
        })
    }
}
