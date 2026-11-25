//! Requests: asking Lidarr for music that isn't in the library, as the phone sees it.
//!
//! One function for each thing the phone asks of the library's `lidarr`, `gate` and
//! `musicbrainz`, in the same shape as every other call (one argument and one answer, JSON
//! as bytes). These are the calls that **wait on the network**: each is made on a thread of
//! the app's kept for the purpose, never the main one, and makes its requests through
//! `wire::Host`, which is where to read how one is stopped.
//!
//! Every one that can be stopped takes the number of the operation it is (`operation`), which
//! the Kotlin side made up before calling and goes by to stop it; and answers with an
//! [`Outcome`], in which **stopped is an outcome of its own**, never a failure with words
//! and never "nothing found".
//!
//! **Secrets.** Lidarr's key, a sign-in's code, its check and its token arrive here inside
//! the argument and leave inside the answer, as bytes, and are handed to the library. They
//! are never written to a log and never put into an error's words: an argument of these
//! calls that can't be read is refused without saying what it held (`call_private`).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::jbyteArray;
use nexus_core::gate::{self, Found, Session};
use nexus_core::http::STOPPED;
use nexus_core::lidarr::{Lidarr, SignIn};
use nexus_core::listenbrainz::ListenBrainz;
use nexus_core::musicbrainz::MusicBrainz;
use nexus_core::offer::Offer;
use serde::{Deserialize, Serialize};

use crate::wire::{self, During, Host, Line, Whose};
use crate::{EXCEPTION, call, call_private};

/// How a call that waits on the network ended.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Outcome<T> {
    Got {
        value: T,
    },
    /// It failed, and this is the library's own sentence for why.
    Failed {
        why: String,
    },
    /// The app stopped it. Nothing was found out, and nothing is to be said.
    Stopped,
}

fn outcome<T>(result: Result<T, String>) -> Outcome<T> {
    match result {
        Ok(value) => Outcome::Got { value },
        // (The library reports a stop in words, as callers that only pass words on do: these.)
        Err(why) if why == STOPPED => Outcome::Stopped,
        Err(why) => Outcome::Failed { why },
    }
}

/// Tells the library which Kotlin object makes its requests. Once, before the first of them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_wire<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, wire: JObject<'l>) {
    if let Err(why) = wire::open(&mut env, &wire) {
        if !env.exception_check().unwrap_or(false) {
            let _ = env.throw_new(EXCEPTION, why);
        }
    }
}

// ---- the sign-in that can sit in front of Lidarr ------------------------------------------------

/// Where the sign-in service is and what it calls the sign-in in front of Lidarr
/// (`nexus_core::gate::Found`).
#[derive(Serialize, Deserialize, Clone)]
struct Door {
    service: String,
    client: String,
}

impl Door {
    fn found(&self) -> Found {
        Found { service: self.service.clone(), client: self.client.clone() }
    }
}

/// The sign-ins that are open: each keeps its token fresh and hands out passes for Lidarr,
/// one renewal at a time, for as long as the Kotlin side keeps it open.
static SESSIONS: Mutex<Option<HashMap<u64, Arc<Session>>>> = Mutex::new(None);
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

fn sessions<T>(act: impl FnOnce(&mut HashMap<u64, Arc<Session>>) -> T) -> T {
    act(SESSIONS.lock().unwrap_or_else(|stuck| stuck.into_inner()).get_or_insert_with(HashMap::new))
}

/// What a call to Lidarr is made with: where it is, its key, and the sign-in in front of it
/// if there is one (the number `gateOpen` gave).
#[derive(Deserialize)]
struct Connection {
    address: String,
    key: String,
    session: Option<u64>,
}

impl Connection {
    /// Lidarr, asked through this operation's own way out.
    fn lidarr(&self, operation: u64) -> Result<Lidarr, String> {
        let gate = match self.session {
            None => None,
            // (A sign-in that has been closed: as the library says when there is none.)
            Some(id) => Some(sessions(|open| open.get(&id).cloned()).ok_or(nexus_core::lidarr::NEEDED)? as Arc<dyn SignIn>),
        };
        Ok(Lidarr::new(&self.address, &self.key, gate, Host::new(Whose::Operation(operation), Line::Lidarr)))
    }
}

#[derive(Deserialize)]
struct FindIn {
    operation: u64,
    address: String,
}

/// `gate::find`: what answers at Lidarr's address. No value if it is Lidarr itself;
/// otherwise the sign-in in front of it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateFind<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: FindIn| {
        let _during = During::new(given.operation);
        let http = Host::new(Whose::Operation(given.operation), Line::Lidarr);
        let found = gate::find(http.as_ref(), &nexus_core::lidarr::tidy(&given.address));
        Ok(outcome(found.map(|found| found.map(|found| Door { service: found.service, client: found.client }))))
    })
}

#[derive(Deserialize)]
struct LinkIn {
    door: Door,
    back: String,
    state: String,
    secret: String,
}

/// `gate::sign_in_link`: the page the browser is sent to.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateLink<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: LinkIn| Ok(gate::sign_in_link(&given.door.found(), &given.back, &given.state, &given.secret)))
}

#[derive(Deserialize)]
struct ParamIn {
    query: String,
    name: String,
}

/// `gate::param`: one named value from the part of an address after the "?".
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateParam<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: ParamIn| Ok(gate::param(&given.query, &given.name)))
}

#[derive(Deserialize)]
struct FinishIn {
    operation: u64,
    door: Door,
    code: String,
    back: String,
    secret: String,
}

#[derive(Serialize)]
struct SignedIn {
    token: String,
    name: String,
}

/// `gate::finish`: collects the token once the browser has come back. **The caller has
/// checked the browser's `state` itself** (see the note where the library defines it).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateFinish<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: FinishIn| {
        let _during = During::new(given.operation);
        let http = Host::new(Whose::Operation(given.operation), Line::Lidarr);
        let done = gate::finish(http.as_ref(), &given.door.found(), &given.code, &given.back, &given.secret);
        Ok(outcome(done.map(|done| SignedIn { token: done.token, name: done.name })))
    })
}

#[derive(Deserialize)]
struct OpenIn {
    door: Door,
    token: String,
}

/// `gate::Session::new`: a sign-in that's been done, kept open under the number handed
/// back. Whenever its token changes the Kotlin side is handed the new one to put away
/// (`CoreHttp.kept`), with "" when the service has stopped honouring it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateOpen<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: OpenIn| {
        let id = NEXT_SESSION.fetch_add(1, Ordering::SeqCst);
        // Asked through a way out that is nobody's: a renewal replaces the token, and is
        // never cut off part-way (see `wire::Whose::Nobodys`).
        let session = Session::new(given.door.found(), given.token, move |token| wire::keep(id, token), Host::new(Whose::Nobodys, Line::Lidarr));
        sessions(|open| open.insert(id, Arc::new(session)));
        Ok(id)
    })
}

/// `gate::Session::close`: ends the sign-in here and hands back its token, for handing back
/// to the service. Waits for a renewal that is under way.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateClose<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |id: u64| Ok(sessions(|open| open.remove(&id)).map(|session| session.close()).unwrap_or_default()))
}

#[derive(Deserialize)]
struct GiveUpIn {
    operation: u64,
    service: String,
    token: String,
}

/// `gate::give_up_token`: tells the service a token is no longer wanted. Best effort.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_gateGiveUp<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: GiveUpIn| {
        let _during = During::new(given.operation);
        gate::give_up_token(Host::new(Whose::Operation(given.operation), Line::Lidarr).as_ref(), &given.service, &given.token);
        Ok(true)
    })
}

// ---- Lidarr -------------------------------------------------------------------------------------

#[derive(Deserialize)]
struct Of {
    operation: u64,
    lidarr: Connection,
}

/// `Lidarr::version`: checks the address and the key (and the sign-in, if there is one) by
/// asking Lidarr which version it is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrVersion<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Of| {
        let _during = During::new(given.operation);
        Ok(outcome(given.lidarr.lidarr(given.operation).and_then(|lidarr| lidarr.version())))
    })
}

#[derive(Deserialize)]
struct RequestIn {
    operation: u64,
    lidarr: Connection,
    wanted: Offer,
}

/// `Lidarr::request`: puts an album (or an EP, a single, a soundtrack) on Lidarr's list
/// and has Lidarr start looking for it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrRequest<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: RequestIn| {
        let _during = During::new(given.operation);
        Ok(outcome(given.lidarr.lidarr(given.operation).and_then(|lidarr| lidarr.request(&given.wanted)).map(|()| true)))
    })
}

#[derive(Deserialize)]
struct AddArtistIn {
    operation: u64,
    lidarr: Connection,
    /// Lidarr's name for the artist (a MusicBrainz id), and what they're called.
    artist: String,
    name: String,
}

/// `Lidarr::add_artist`: has Lidarr get everything by an artist. Answers with how many of
/// their albums were newly asked for (none, for an artist new to Lidarr: it learns their
/// albums in its own time).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrAddArtist<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: AddArtistIn| {
        let _during = During::new(given.operation);
        Ok(outcome(given.lidarr.lidarr(given.operation).and_then(|lidarr| lidarr.add_artist(&given.artist, &given.name))))
    })
}

/// An album on Lidarr's list that hasn't fully arrived (`nexus_core::lidarr::Pending`).
#[derive(Serialize)]
struct Pending {
    /// Lidarr's number for it: what `lidarrCancel` takes.
    id: i64,
    title: String,
    artist: String,
    /// How much of it has been fetched, in hundredths; none if it isn't being fetched yet.
    progress: Option<u8>,
}

/// `Lidarr::pending`: what is on its way, those being fetched first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrPending<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: Of| {
        let _during = During::new(given.operation);
        let pending = given.lidarr.lidarr(given.operation).and_then(|lidarr| lidarr.pending());
        Ok(outcome(pending.map(|all| all.into_iter().map(|one| Pending { id: one.id, title: one.title, artist: one.artist, progress: one.progress }).collect::<Vec<_>>())))
    })
}

#[derive(Deserialize)]
struct CancelIn {
    operation: u64,
    lidarr: Connection,
    id: i64,
}

/// `Lidarr::cancel`: takes an album off Lidarr's list.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrCancel<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: CancelIn| {
        let _during = During::new(given.operation);
        Ok(outcome(given.lidarr.lidarr(given.operation).and_then(|lidarr| lidarr.cancel(given.id)).map(|()| true)))
    })
}

// ---- MusicBrainz --------------------------------------------------------------------------------

/// MusicBrainz, of which there is one for the whole app: it keeps the gap MusicBrainz asks
/// for between requests, and two would each keep their own. Its requests are whichever
/// operation's the calling thread is carrying out.
fn musicbrainz() -> &'static MusicBrainz {
    static ONE: OnceLock<MusicBrainz> = OnceLock::new();
    ONE.get_or_init(|| {
        MusicBrainz::new(Host::new(Whose::TheThreads, Line::MusicBrainz), ListenBrainz::new(Host::new(Whose::TheThreads, Line::ListenBrainz)))
    })
}

#[derive(Deserialize)]
struct TracksIn {
    operation: u64,
    /// MusicBrainz's name for the album (which is Lidarr's too).
    album: String,
}

/// A song on an album, by name only (`nexus_core::musicbrainz::TrackName`).
#[derive(Serialize)]
struct TrackName {
    title: String,
    seconds: u32,
    disc: u32,
    number: u32,
}

/// `MusicBrainz::album_tracks`: what is on an album.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_albumTracks<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: TracksIn| {
        let _during = During::new(given.operation);
        let tracks = musicbrainz().album_tracks(&given.album);
        Ok(outcome(tracks.map(|all| all.into_iter().map(|one| TrackName { title: one.title, seconds: one.seconds, disc: one.disc, number: one.number }).collect::<Vec<_>>())))
    })
}

// ---- for the tests of the boundary itself ------------------------------------------------------
//
// (In every build of the library and called by nothing in a release build of the app, as
// `echo` and `stop` are: see there.)

#[derive(Deserialize)]
struct ProbeIn {
    operation: u64,
    /// "lidarr", "musicbrainz" or "listenbrainz".
    line: String,
    /// Asked as nobody's request, not the operation's.
    nobodys: bool,
    method: String,
    url: String,
    headers: Vec<(String, String)>,
    body: Option<String>,
    patience: Option<u64>,
    /// A wait asked for before the request, in milliseconds.
    pause: Option<u64>,
    /// What is done with the answer: "text", "json", "drop" or "panic".
    then: String,
}

#[derive(Serialize, Default)]
struct ProbeOut {
    /// Whether the wait asked for was waited out; none if none was asked for.
    waited: Option<bool>,
    status: u16,
    headers: Vec<(String, Option<String>)>,
    /// The body as text, or as the library wrote the JSON it read out again.
    body: Option<String>,
    /// Why the body couldn't be read, in the app's words.
    unread: Option<String>,
}

/// One request made through the library's own `Http`, and its answer read as the library
/// reads one: for the tests of what the boundary does with an answer (and with a panic
/// while one is held).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_wireProbe<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    use nexus_core::http::{Failure, Http, Method, Request};
    call_private(env, input, |given: ProbeIn| {
        let _during = During::new(given.operation);
        let line = match given.line.as_str() {
            "musicbrainz" => Line::MusicBrainz,
            "listenbrainz" => Line::ListenBrainz,
            _ => Line::Lidarr,
        };
        let http = Host::new(if given.nobodys { Whose::Nobodys } else { Whose::Operation(given.operation) }, line);
        let mut out = ProbeOut::default();
        if let Some(pause) = given.pause {
            out.waited = Some(http.pause(std::time::Duration::from_millis(pause)).is_ok());
        }
        let body = given.body.as_deref().unwrap_or_default();
        let method = match given.method.as_str() {
            "POST" => Method::Post(body),
            "PUT" => Method::Put(body),
            _ => Method::Get,
        };
        let headers: Vec<(&str, &str)> = given.headers.iter().map(|(name, value)| (name.as_str(), value.as_str())).collect();
        let request = Request { method, url: &given.url, headers: &headers, patience: given.patience.map(std::time::Duration::from_millis) };
        let response = match http.send(&request) {
            Ok(response) => response,
            Err(Failure::Stopped) => return Ok(outcome(Err(STOPPED.to_string()))),
            Err(Failure::Reach(why)) => return Ok(outcome(Err(why))),
        };
        out.status = response.status;
        out.headers = response.headers.clone();
        match given.then.as_str() {
            "text" => match response.body() {
                Ok(text) => out.body = Some(text),
                Err(why) => out.unread = Some(why),
            },
            "json" => match response.json::<serde_json::Value>() {
                Ok(value) => out.body = Some(value.to_string()),
                Err(why) => out.unread = Some(why),
            },
            "panic" => panic!("the probe was asked to, holding an answer"),
            _ => drop(response),
        }
        Ok(outcome(Ok(out)))
    })
}
