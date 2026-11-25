//! The phone's answer to the library's requests for the network.
//!
//! The library opens no connections: whatever it wants from Lidarr, the sign-in service,
//! MusicBrainz or ListenBrainz it asks for through `nexus_core::http::Http`. On Windows that
//! is one of the app's own agents (`src/corehttp.rs` there). Here it is [`Host`], which
//! calls up into **one Kotlin object** (`org.medianexus.music.core.CoreHttp`), and that
//! makes the request with the app's own client for these services. docs/DESIGN.md, "The
//! library's requests", says what was decided and why; this is the Rust half.
//!
//! **What crosses.** Bytes, both ways, as with every other call: a request as JSON, an
//! answer's status and headers as JSON, a body as it was received. Nothing here writes a
//! request, an answer or any part of one to a log, and nothing here puts one into words:
//! a key, a pass, a sign-in's code and its token pass through and are not looked at.
//!
//! **A body is read only if the library asks for it**, as the Windows app reads one: whole,
//! as text, or a piece at a time as bytes (which is how the library reads JSON, so that an
//! answer that doesn't begin as JSON is given up at once). One that is never asked for is
//! let go unread when the answer is dropped.
//!
//! **Threads.** A call up is made on whichever thread the library is on. The app's own
//! threads are the Java machine's already; one the library started itself is joined to it
//! for the length of the call and let go again. Whatever the Kotlin side throws is caught
//! here and cleared, and becomes the library's own "couldn't be reached" (or, for a wait,
//! "stopped"): no exception is ever left waiting in the Java machine for the next call to
//! trip over.

use std::cell::Cell;
use std::io::Read;
use std::sync::{Arc, RwLock};
use std::time::Duration;

use jni::objects::{GlobalRef, JByteArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use nexus_core::http::{Failure, Http, Method, Request, Response, Stream};
use serde::{Deserialize, Serialize};

/// The Kotlin object every request goes up to.
struct Door {
    vm: JavaVM,
    wire: GlobalRef,
}

static DOOR: RwLock<Option<Arc<Door>>> = RwLock::new(None);

/// Told once, before the first request, which Kotlin object makes them.
pub fn open(env: &mut JNIEnv, wire: &JObject) -> Result<(), String> {
    let door = Door {
        vm: env.get_java_vm().map_err(|e| format!("The shared library couldn't keep hold of the app: {e}"))?,
        wire: env.new_global_ref(wire).map_err(|e| format!("The shared library couldn't keep hold of the app's way of making requests: {e}"))?,
    };
    *DOOR.write().unwrap_or_else(|stuck| stuck.into_inner()) = Some(Arc::new(door));
    Ok(())
}

fn door() -> Option<Arc<Door>> {
    DOOR.read().unwrap_or_else(|stuck| stuck.into_inner()).clone()
}

impl Door {
    /// One call up. `None` if it couldn't be made or the Kotlin side threw; whatever it
    /// threw is cleared before this returns.
    fn up<T>(&self, act: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Option<T> {
        // (A thread of the app's is the Java machine's already, and this only finds it. One
        // the library started is joined here and let go when `env` is dropped.)
        let mut env = self.vm.attach_current_thread().ok()?;
        // A frame of its own for what the call makes, so that an operation of a thousand
        // requests doesn't hold a thousand arrays until it returns.
        let outcome = env.with_local_frame(8, |env| act(env, self.wire.as_obj()));
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
        }
        outcome.ok()
    }

    /// A call up that answers with bytes.
    fn bytes(&self, name: &str, signature: &str, arguments: &[JValue]) -> Option<Vec<u8>> {
        self.up(|env, wire| {
            let answer = env.call_method(wire, name, signature, arguments)?.l()?;
            env.convert_byte_array(JByteArray::from(answer))
        })
    }
}

/// Which service a request is for. Each has connections of its own on the Kotlin side, kept
/// as long as the Windows app keeps them and following redirects or not as it does.
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Line {
    /// Lidarr, and the sign-in in front of it: redirects are never followed (one is how a
    /// sign-in shows itself).
    Lidarr,
    MusicBrainz,
    ListenBrainz,
}

thread_local! {
    /// The operation this thread is carrying out for the app (see [`During`]); 0 for none.
    static DOING: Cell<u64> = const { Cell::new(0) };
}

/// Marks the thread as carrying out an operation of the app's until it is dropped: what a
/// service that outlives operations (MusicBrainz, which keeps the pace for everybody) goes
/// by to tell whose request this is.
pub struct During(u64);

impl During {
    pub fn new(operation: u64) -> During {
        During(DOING.replace(operation))
    }
}

impl Drop for During {
    fn drop(&mut self) {
        DOING.set(self.0);
    }
}

/// Whose request it is: what the Kotlin side goes by to stop it.
#[derive(Clone, Copy)]
pub enum Whose {
    /// One operation's.
    Operation(u64),
    /// Whichever operation the calling thread is carrying out (see [`During`]).
    TheThreads,
    /// Nobody's: it is never stopped and never cut off, and has a limit of its own instead.
    /// The sign-in's requests, which replace the token that is kept: one cut off after the
    /// service had replaced it would leave the app with a token that no longer works.
    Nobodys,
}

/// The library's `Http`, for one service and one owner. Costs nothing to make.
pub struct Host {
    pub whose: Whose,
    pub line: Line,
}

impl Host {
    pub fn new(whose: Whose, line: Line) -> Arc<Host> {
        Arc::new(Host { whose, line })
    }

    fn operation(&self) -> u64 {
        match self.whose {
            Whose::Operation(operation) => operation,
            Whose::TheThreads => DOING.get(),
            Whose::Nobodys => 0,
        }
    }
}

/// A request, as it goes up.
#[derive(Serialize)]
struct Asked<'a> {
    /// 0 for nobody's.
    operation: u64,
    line: Line,
    method: &'static str,
    url: &'a str,
    headers: &'a [(&'a str, &'a str)],
    body: Option<&'a str>,
    /// Milliseconds the whole call may take, if the library set a limit of its own.
    patience: Option<u64>,
}

/// An answer's status and headers, as they come back; its body waits on the Kotlin side
/// under `body` until it is read or let go.
#[derive(Deserialize)]
struct Answered {
    status: u16,
    /// A value that isn't text is `null`: there, and not to be read.
    headers: Vec<(String, Option<String>)>,
    body: i64,
    length: Option<u64>,
}

// What comes back from `send` begins with one of these; from `text` and `read`, with one of
// the first two.
const FINE: u8 = 0;
const FAILED: u8 = 1;
const STOPPED: u8 = 2;

/// What the app is told when the Kotlin side gave no answer at all (it threw, or the library
/// was never told which object to ask): the end of the library's own sentence.
const NO_ANSWER: &str = "The app couldn't make the request.";

impl Http for Host {
    fn send(&self, request: &Request) -> Result<Response, Failure> {
        let door = door().ok_or_else(|| Failure::Reach(NO_ANSWER.into()))?;
        let (method, body) = match request.method {
            Method::Get => ("GET", None),
            Method::Post(body) => ("POST", Some(body)),
            Method::Put(body) => ("PUT", Some(body)),
        };
        let asked = Asked {
            operation: self.operation(),
            line: self.line,
            method,
            url: request.url,
            headers: request.headers,
            body,
            patience: request.patience.map(|patience| patience.as_millis().min(u64::MAX as u128) as u64),
        };
        let frame = serde_json::to_vec(&asked).map_err(|_| Failure::Reach(NO_ANSWER.into()))?;
        let answer = door
            .up(|env, wire| {
                let frame = env.byte_array_from_slice(&frame)?;
                let answer = env.call_method(wire, "send", "([B)[B", &[JValue::Object(&frame)])?.l()?;
                env.convert_byte_array(JByteArray::from(answer))
            })
            .ok_or_else(|| Failure::Reach(NO_ANSWER.into()))?;
        match answer.split_first() {
            Some((&FINE, head)) => {
                let head: Answered = serde_json::from_slice(head).map_err(|_| Failure::Reach(NO_ANSWER.into()))?;
                Ok(Response::streamed(head.status, head.headers, Body { door, id: head.body, length: head.length }))
            }
            Some((&STOPPED, _)) => Err(Failure::Stopped),
            Some((&FAILED, why)) => Err(Failure::Reach(String::from_utf8_lossy(why).into_owned())),
            _ => Err(Failure::Reach(NO_ANSWER.into())),
        }
    }

    fn pause(&self, wait: Duration) -> Result<(), Failure> {
        let millis = wait.as_millis().min(i64::MAX as u128) as i64;
        let waited = door().and_then(|door| {
            door.up(|env, wire| env.call_method(wire, "pause", "(JJ)Z", &[JValue::Long(self.operation() as i64), JValue::Long(millis)])?.z())
        });
        // A wait that couldn't be asked for is "stopped", never "waited": the library waits
        // in loops, and one that never waited would spin.
        if waited == Some(true) { Ok(()) } else { Err(Failure::Stopped) }
    }
}

/// An answer's body, unread, waiting on the Kotlin side.
struct Body {
    door: Arc<Door>,
    id: i64,
    length: Option<u64>,
}

impl Body {
    /// A call up for some of it: the bytes, or why they couldn't be had.
    fn ask(&self, name: &str, signature: &str, arguments: &[JValue]) -> Result<Vec<u8>, String> {
        let mut answer = self.door.bytes(name, signature, arguments).ok_or(NO_ANSWER)?;
        match answer.first() {
            Some(&FINE) => {
                answer.remove(0);
                Ok(answer)
            }
            Some(&FAILED) => Err(String::from_utf8_lossy(&answer[1..]).into_owned()),
            _ => Err(NO_ANSWER.into()),
        }
    }
}

impl Stream for Body {
    fn length(&self) -> Option<u64> {
        self.length
    }

    /// The whole of it, as the Windows app reads one: up to the library's limit, and bytes
    /// that aren't text become the mark for that rather than a failure.
    fn text(self: Box<Self>) -> Result<String, String> {
        let bytes = self.ask("text", "(J)[B", &[JValue::Long(self.id)])?;
        Ok(match String::from_utf8(bytes) {
            Ok(text) => text,
            Err(not) => String::from_utf8_lossy(not.as_bytes()).into_owned(),
        })
    }

    fn bytes(self: Box<Self>) -> Box<dyn Read + Send> {
        Box::new(Reading { body: *self, held: Vec::new(), at: 0, ended: false })
    }
}

impl Drop for Body {
    /// Read or not, it is let go: the Kotlin side closes what is left of the answer.
    fn drop(&mut self) {
        let id = self.id;
        self.door.up(|env, wire| env.call_method(wire, "close", "(J)V", &[JValue::Long(id)]).map(drop));
    }
}

/// How much of a body is asked for at a time.
const PIECE: i32 = 64 * 1024;

/// A body being read a piece at a time.
struct Reading {
    body: Body,
    held: Vec<u8>,
    at: usize,
    ended: bool,
}

impl Read for Reading {
    fn read(&mut self, into: &mut [u8]) -> std::io::Result<usize> {
        if self.at == self.held.len() {
            if self.ended {
                return Ok(0);
            }
            self.held = self.body.ask("read", "(JI)[B", &[JValue::Long(self.body.id), JValue::Int(PIECE)]).map_err(std::io::Error::other)?;
            self.at = 0;
            if self.held.is_empty() {
                self.ended = true;
                return Ok(0);
            }
        }
        let count = into.len().min(self.held.len() - self.at);
        into[..count].copy_from_slice(&self.held[self.at..self.at + count]);
        self.at += count;
        Ok(count)
    }
}

/// Hands the Kotlin side a sign-in's token to put away (or "" when it is dead): see
/// `nexus_core::gate::Session::new`'s `keep`.
pub fn keep(session: u64, token: &str) {
    if let Some(door) = door() {
        door.up(|env, wire| {
            let token = env.byte_array_from_slice(token.as_bytes())?;
            env.call_method(wire, "kept", "(J[B)V", &[JValue::Long(session as i64), JValue::Object(&token)]).map(drop)
        });
    }
}
