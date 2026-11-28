//! The pages and actions of requests, as the phone sees them: one function for each thing
//! the screens ask, over the library's own (`nexus_core::requests`, `asked`, `discover`),
//! which decide what is asked of Lidarr, MusicBrainz and ListenBrainz, in what order, and
//! what is made of the answers, **the same for every app**. Nothing is decided here.
//!
//! **What the library needs of the app comes from a [`Place`]**: one for each connection to
//! Lidarr, kept here under a number for as long as the Kotlin side keeps it open. It holds
//! what has been offered while the connection lasts (so that something tapped can be acted
//! on), and the three things the library reads *at the moment it needs them*, which the
//! Kotlin side sets whenever they change, during an operation too: the phone's copy of the
//! library, what has been asked for, and the "singles and EPs" setting.
//!
//! **And the way back from a change to Lidarr that has to be put back** (an artist's "new
//! releases" setting, turned off while their singles are read in): the library's own batch
//! job (`nexus_core::bulk::Job`), one to a place, which is where the library writes a way
//! back down before it changes Lidarr and crosses it off after (`lidarr::WayBack`), and
//! which puts back what an earlier run left ([`Keeper`] is the phone's part in it: the file
//! is the Kotlin side's to write). The phone asks for no batches; the job is here for
//! that, by the same rules and in the same file as the Windows app.
//!
//! Each operation asks through its own way out ([`Host`] with its number), MusicBrainz
//! included (`MusicBrainz::over`: the one MusicBrainz's pace and store, this operation's
//! requests), so that stopping one stops exactly its requests.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use nexus_core::asked::{self, Arrival, Asked};
use nexus_core::bulk;
use nexus_core::discover::{self, Kept};
use nexus_core::lidarr::{Offers, RequestLidarr};
use nexus_core::listenbrainz::ListenBrainz;
use nexus_core::musicbrainz::MusicBrainz;
use nexus_core::requests::{self, Library, Services, Snapshot, Stopped};
use serde::{Deserialize, Serialize};

use crate::requests::{Connection, Outcome, musicbrainz, outcome};
use crate::wire::{self, During, Host, Line, Whose};
use crate::{call, call_private};

/// What one connection to Lidarr has by it (see this file's own comment).
struct Place {
    /// What has been offered, **and the number of the connection it was offered under**
    /// (the app's own count: it moves whenever the address, the key or the sign-in
    /// changes). Whatever is done is done under a connection's number, and takes its
    /// offers by that number ([`Place::offers`]): so an operation's Lidarr and its offers
    /// are one connection's, whatever order things run in.
    offers: Mutex<(u64, Arc<Offers>)>,
    library: RwLock<Arc<Snapshot>>,
    asked: RwLock<Vec<Asked>>,
    singles: Arc<AtomicBool>,
    /// Where the way back from a change to Lidarr is written down, and what puts back what
    /// an earlier run left (see this file's own comment).
    job: bulk::Job,
}

/// The phone's part in the library's batch job (`bulk::Desk`): where it is written down.
/// The file is the Kotlin side's (`asking.json`, in the account's own folder); this hands
/// it what to write and is told whether it was written.
struct Keeper {
    place: u64,
    singles: Arc<AtomicBool>,
}

impl bulk::Desk for Keeper {
    fn keep(&self, batch: Option<Vec<u8>>, flushed: bool) {
        wire::way_back(self.place, batch.as_deref(), flushed);
    }

    /// What must be on the disk before Lidarr is changed: written, synced, and said
    /// truthfully whether it is.
    fn keep_checked(&self, batch: Vec<u8>) -> bool {
        wire::way_back(self.place, Some(&batch), true)
    }

    /// (When a batch notes something as asked for. The phone hands the job no albums, so
    /// nothing asks; were it to, the answer is the time it is.)
    fn now(&self) -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |since| since.as_secs() as i64)
    }

    fn singles(&self) -> bool {
        self.singles.load(Ordering::SeqCst)
    }
}

static PLACES: Mutex<Option<HashMap<u64, Arc<Place>>>> = Mutex::new(None);
static NEXT_PLACE: AtomicU64 = AtomicU64::new(1);

fn places<T>(act: impl FnOnce(&mut HashMap<u64, Arc<Place>>) -> T) -> T {
    act(PLACES.lock().unwrap_or_else(|stuck| stuck.into_inner()).get_or_insert_with(HashMap::new))
}

/// The place under this number. One that has been closed is an error: nothing is asked for
/// a connection that is over.
fn place(id: u64) -> Result<Arc<Place>, String> {
    places(|open| open.get(&id).cloned()).ok_or_else(|| "That connection to Lidarr is no longer open.".to_string())
}

impl Place {
    fn library(&self) -> Arc<dyn Library> {
        self.library.read().unwrap_or_else(|stuck| stuck.into_inner()).clone()
    }

    fn asked(&self) -> Vec<Asked> {
        self.asked.read().unwrap_or_else(|stuck| stuck.into_inner()).clone()
    }

    fn singles(&self) -> bool {
        self.singles.load(Ordering::SeqCst)
    }

    /// What connection `connection` has been offered, held by whoever asks for as long as
    /// they are at it.
    ///
    /// - The connection the place is at: its offers.
    /// - A newer one: the place moves to it, and its offers start from nothing. What the
    ///   connection before was offered is let go of.
    /// - An older one (something that was begun, or was still waiting its turn, before the
    ///   connection changed): a set of its own, which nobody else will ever read. What the
    ///   old Lidarr offers it is nothing to the connection there is now.
    fn offers(&self, connection: u64) -> Arc<Offers> {
        let mut now = self.offers.lock().unwrap_or_else(|stuck| stuck.into_inner());
        if connection > now.0 {
            *now = (connection, Arc::new(Offers::new()));
        }
        if connection == now.0 { now.1.clone() } else { Arc::new(Offers::new()) }
    }

    /// Changes what has been asked for, and says what it now is, for the Kotlin side to keep.
    fn change<T>(&self, act: impl FnOnce(&mut Vec<Asked>) -> T) -> (T, Vec<Asked>) {
        let mut items = self.asked.write().unwrap_or_else(|stuck| stuck.into_inner());
        let done = act(&mut items);
        (done, items.clone())
    }
}

/// A connection begins: its place, under the number handed back.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeOpen<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |_: bool| {
        let id = NEXT_PLACE.fetch_add(1, Ordering::SeqCst);
        let singles = Arc::new(AtomicBool::new(false));
        let made = Place {
            offers: Mutex::new((0, Arc::new(Offers::new()))),
            library: RwLock::new(Arc::new(Snapshot::default())),
            asked: RwLock::new(Vec::new()),
            singles: singles.clone(),
            job: bulk::Job::new(Keeper { place: id, singles }),
        };
        places(|open| open.insert(id, Arc::new(made)));
        Ok(id)
    })
}

#[derive(Deserialize)]
struct WaysIn {
    place: u64,
    /// What the phone has written down (`asking.json`), as it reads.
    written: String,
}

/// `bulk::Job::load`: takes up what an earlier run wrote down of changes to Lidarr still
/// to be put back. Says whether it could be made sense of; what can't be is the Kotlin
/// side's to set aside.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeWays<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: WaysIn| Ok(place(given.place)?.job.load(given.written.as_bytes())))
}

/// `bulk::Job::unfinished`: whether anything is written down that is still to be put back
/// **on this Lidarr** (by its address, as the library writes it: `Lidarr::way_back_address`).
/// What is written down for another Lidarr is not this one's, and is left as it is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_waysLeft<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let place = place(given.place)?;
        Ok(given.lidarr.lidarr(given.operation).is_ok_and(|lidarr| place.job.unfinished(&lidarr.way_back_address())))
    })
}

/// `bulk::Job::work_through`, with no albums to ask for: **puts back what an earlier run
/// left changed on this Lidarr** and crosses it off, by the library's own rules (it waits
/// until Lidarr has finished with the artist, looking every few seconds, so it can take
/// minutes). The value is whether nothing is left for this Lidarr. Stopped, what it had
/// not put back is still written down.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_putBack<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let lidarr = match given.lidarr.lidarr(given.operation) {
            Ok(lidarr) => lidarr,
            Err(why) => return Ok(outcome(Err(why))),
        };
        // (One at a time. The Kotlin side does not begin this until the one before has
        // ended: were one still at work all the same, this is not begun beside it.)
        if !place.job.begin() {
            return Ok(Outcome::Got { value: false });
        }
        // (The job says how a batch is going as it goes, and which albums it noted or
        // forgot: there are none here, and nobody to tell.)
        place.job.work_through(&lidarr, musicbrainz(), &|_: bulk::Event| {});
        Ok(Outcome::Got { value: !place.job.unfinished(&lidarr.way_back_address()) })
    })
}

/// The connection is over: what was offered while it lasted is forgotten.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeClose<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |id: u64| Ok(places(|open| open.remove(&id)).is_some()))
}

#[derive(Deserialize)]
struct LibraryIn {
    place: u64,
    library: Snapshot,
}

/// The phone's copy of the library, handed over whenever it changes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeLibrary<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: LibraryIn| {
        *place(given.place)?.library.write().unwrap_or_else(|stuck| stuck.into_inner()) = Arc::new(given.library);
        Ok(true)
    })
}

#[derive(Deserialize)]
struct AskedIn {
    place: u64,
    asked: Vec<Asked>,
}

/// What has been asked for, as the phone kept it: handed over as the connection begins.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeAsked<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: AskedIn| {
        *place(given.place)?.asked.write().unwrap_or_else(|stuck| stuck.into_inner()) = given.asked;
        Ok(true)
    })
}

#[derive(Deserialize)]
struct SinglesIn {
    place: u64,
    on: bool,
}

/// The "singles and EPs" setting, handed over whenever it changes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_placeSingles<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: SinglesIn| {
        place(given.place)?.singles.store(given.on, Ordering::SeqCst);
        Ok(true)
    })
}

// ---- the services, for one operation --------------------------------------------------------------

/// Lidarr, MusicBrainz and ListenBrainz as one operation asks them: each through that
/// operation's own way out.
struct Asking<'a> {
    lidarr: Box<dyn RequestLidarr + 'a>,
    musicbrainz: MusicBrainz,
    listenbrainz: ListenBrainz,
}

impl<'a> Asking<'a> {
    fn of(connection: &'a Connection, operation: u64) -> Result<Self, String> {
        // (The public services too are asked as this account's doing: nothing they answer
        // can send a request on to its music server.)
        let home = connection.home.as_deref();
        let listenbrainz = || ListenBrainz::new(Host::new(Whose::Operation(operation), Line::ListenBrainz).at_home(home));
        Ok(Asking {
            lidarr: connection.request_lidarr(operation)?,
            musicbrainz: musicbrainz().over(Host::new(Whose::Operation(operation), Line::MusicBrainz).at_home(home), listenbrainz()),
            listenbrainz: listenbrainz(),
        })
    }

    fn services(&self) -> Services<'_> {
        Services { lidarr: self.lidarr.as_ref(), musicbrainz: &self.musicbrainz, listenbrainz: &self.listenbrainz }
    }
}

fn asked_for(connection: &Connection, operation: u64, place: &Place) -> Vec<Asked> {
    if let Some(scope) = &connection.nexus {
        return crate::nexus_requests::asked_history(
            &crate::nexus_requests::backend(scope, operation), place.library().as_ref(),
        ).unwrap_or_default();
    }
    place.asked()
}

/// What a page or an action came to: found, or stopped. (Why something couldn't be had is
/// inside what was found, as the library puts it there for showing.)
fn found<T>(result: Result<T, Stopped>) -> Outcome<T> {
    match result {
        Ok(value) => Outcome::Got { value },
        Err(Stopped) => Outcome::Stopped,
    }
}

#[derive(Deserialize)]
struct PageIn {
    operation: u64,
    lidarr: Connection,
    place: u64,
    /// What is searched for; or MusicBrainz's name for the artist or the album.
    what: String,
    /// What the artist's page calls them (for their most played songs only).
    #[serde(default)]
    name: String,
}

/// `requests::search`: songs, artists and albums beyond the library. What has been found so
/// far is handed up each time more of it is known (`CoreHttp.published`); all of it is the
/// answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_pageSearch<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let asking = match Asking::of(&given.lidarr, given.operation) {
            Ok(asking) => asking,
            Err(why) => return Ok(outcome(Err(why))),
        };
        let operation = given.operation;
        let mut publish = |so_far: requests::Outside| {
            if let Ok(bytes) = serde_json::to_vec(&so_far) {
                wire::publish(operation, &bytes);
            }
        };
        let asked = std::sync::OnceLock::new();
        let asked = || asked.get_or_init(|| asked_for(&given.lidarr, operation, &place)).clone();
        Ok(found(requests::search(asking.services(), &|| place.library(), &place.offers(given.lidarr.connection), &|| place.singles(), &asked, &given.what, &mut publish)))
    })
}

/// `requests::artist`: an artist as the wider world knows them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_pageArtist<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let asking = match Asking::of(&given.lidarr, given.operation) {
            Ok(asking) => asking,
            Err(why) => return Ok(outcome(Err(why))),
        };
        Ok(found(requests::artist(asking.services(), &|| place.library(), &place.offers(given.lidarr.connection), &|| place.singles(), &given.what)))
    })
}

/// `requests::artist_popular`: the songs an artist is most played for that the library
/// doesn't have; no value if there are none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_pageArtistPopular<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let asking = match Asking::of(&given.lidarr, given.operation) {
            Ok(asking) => asking,
            Err(why) => return Ok(outcome(Err(why))),
        };
        // (None: said as one with no songs, which is what the screens are told.)
        let popular = requests::artist_popular(asking.services(), &|| place.library(), &place.offers(given.lidarr.connection), &|| place.singles(), &|| asked_for(&given.lidarr, given.operation, &place), &given.what, &given.name);
        Ok(found(popular.map(Option::unwrap_or_default)))
    })
}

/// `requests::album`: an album that isn't in the library.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_pageAlbum<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let asking = match Asking::of(&given.lidarr, given.operation) {
            Ok(asking) => asking,
            Err(why) => return Ok(outcome(Err(why))),
        };
        Ok(found(requests::album(asking.services(), &place.offers(given.lidarr.connection), &given.what)))
    })
}

/// `requests::on_their_way`: what has arrived lately, and what Lidarr has still to get.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_pageOnTheirWay<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        if let Some(scope) = &given.lidarr.nexus {
            let client = crate::nexus_requests::backend(scope, given.operation);
            return Ok(outcome(crate::nexus_requests::waiting(&client, place.library().as_ref())));
        }
        let lidarr = match given.lidarr.lidarr(given.operation) {
            Ok(lidarr) => lidarr,
            Err(why) => return Ok(outcome(Err(why))),
        };
        Ok(match requests::on_their_way(&lidarr, &place.asked()) {
            Ok(waiting) => outcome(serde_json::to_value(waiting).map_err(|_| "Couldn't show Requests.".to_string())),
            Err(Stopped) => Outcome::Stopped,
        })
    })
}

// ---- asking for an album ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct AskingIn {
    place: u64,
    /// The connection the album was offered under (the row that was pressed says).
    #[serde(default)]
    connection: u64,
    album: String,
    title: String,
    #[serde(default)]
    by: String,
    #[serde(default)]
    song: String,
    #[serde(default)]
    kind: String,
}

/// `requests::asking`: whether to ask, and how the question is put. Asks nobody.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_asking<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: AskingIn| Ok(requests::asking(&place(given.place)?.offers(given.connection), &given.album, &given.title, &given.by, &given.song, &given.kind)))
}

/// `requests::request`: asks Lidarr for an album that has been offered. The value is who it
/// is by. Seen through once it has begun to change Lidarr (see `wire::Host::keep_going`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_request<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let lidarr = match given.lidarr.request_lidarr(given.operation) {
            Ok(lidarr) => lidarr,
            Err(why) => return Ok(outcome(Err(why))),
        };
        Ok(match requests::request(lidarr.as_ref(), &place.offers(given.lidarr.connection), &given.what, &place.job) {
            Ok(result) => outcome(result),
            Err(Stopped) => Outcome::Stopped,
        })
    })
}

/// `Lidarr::add_artist`: has Lidarr get everything by an artist (`what` is Lidarr's name
/// for them, a MusicBrainz id; `name` what they're called). Answers with how many of their
/// albums were newly asked for (none, for an artist new to Lidarr: it learns their albums
/// in its own time). One step of the way back at a time with every other change to an
/// artist's record, which is why it takes the place.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_addArtist<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: PageIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        Ok(outcome(given.lidarr.request_lidarr(given.operation).and_then(|lidarr| lidarr.add_artist(&given.what, &given.name, &place.job))))
    })
}

#[derive(Deserialize)]
struct WantedIn {
    operation: u64,
    lidarr: Connection,
    place: u64,
    wanted: nexus_core::offer::Offer,
}

/// `Lidarr::request`, for an offer handed over whole: for the tests of the boundary (how
/// a request that changes Lidarr is stopped, and seen through), which nothing in the app
/// calls. The way back is the place's, as for every request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_lidarrRequest<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: WantedIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        Ok(outcome(given.lidarr.request_lidarr(given.operation).and_then(|lidarr| lidarr.request(&given.wanted, &place.job)).map(|()| true)))
    })
}

#[derive(Deserialize)]
struct NotedIn {
    place: u64,
    album: String,
    title: String,
    artist: String,
    #[serde(default)]
    song: String,
    now: i64,
}

/// Once Lidarr has an album on its list: the note of it (`requests::asked_for`), put with
/// the rest (`asked::put`). **The answer is the whole list, for the Kotlin side to keep, and
/// only once it is kept is the offer marked** ([`Java_org_medianexus_music_core_Core_marked`]):
/// the order the library asks of every app.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_noted<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: NotedIn| {
        let note = requests::asked_for(&given.album, &given.title, &given.artist, &given.song, given.now);
        Ok(place(given.place)?.change(|items| asked::put(items, note)).1)
    })
}

#[derive(Deserialize)]
struct MarkedIn {
    place: u64,
    /// The connection it was asked for under: marked among that connection's offers only.
    #[serde(default)]
    connection: u64,
    album: String,
    requested: bool,
}

/// `Offers::mark`: an album has been asked for (or no longer is), so it is shown that way.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_marked<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: MarkedIn| {
        place(given.place)?.offers(given.connection).mark(&given.album, given.requested);
        Ok(true)
    })
}

#[derive(Deserialize)]
struct AddingIn {
    name: String,
    open: usize,
}

/// `requests::adding`: the question before Lidarr is set to keep track of an artist, its
/// heading and its text.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_adding<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: AddingIn| Ok(requests::adding(&given.name, given.open)))
}

// ---- what was asked for, and what has arrived -----------------------------------------------------

/// What has been asked for after a change, and whether it changed (if not, there is
/// nothing to write down).
#[derive(Serialize)]
struct Changed {
    changed: bool,
    asked: Vec<Asked>,
}

#[derive(Deserialize)]
struct ForgetIn {
    place: u64,
    #[serde(default)]
    album: String,
    title: String,
    #[serde(default)]
    artist: String,
}

/// `asked::forget`: stops watching for an album that has been taken off Lidarr's list, by
/// what it is: Lidarr's own name for it where the note and the caller both have one, and
/// otherwise its title and who it is by.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_askedForget<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: ForgetIn| {
        let (changed, asked) = place(given.place)?.change(|items| asked::forget(items, &given.album, &given.title, &given.artist));
        Ok(Changed { changed, asked })
    })
}

#[derive(Deserialize)]
struct NowIn {
    place: u64,
    now: i64,
}

/// `asked::tidy`: drops what has been listed long enough, and what has been waited for too
/// long.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_askedTidy<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: NowIn| {
        let (changed, asked) = place(given.place)?.change(|items| asked::tidy(items, given.now));
        Ok(Changed { changed, asked })
    })
}

/// What looking through the library for what was asked for came to.
#[derive(Serialize)]
struct Arrived {
    /// What has been asked for, with the arrivals marked: to be kept if `changed`.
    asked: Vec<Asked>,
    changed: bool,
    /// The arrivals that are news (they had not arrived before), as they were noted.
    told: Vec<Asked>,
    /// What to say of them (`requests::arrived_news`); empty when none is news.
    news: String,
}

/// Looks through the phone's copy of the library for the albums asked for that are still
/// awaited (`asked::arrivals`, with the library's own rule for "the same album"), marks
/// those that are there now (`asked::mark`) and says which are news. Asks nobody.
///
/// (The Windows app also puts songs an imported playlist was missing into that playlist
/// when their album arrives. The phone imports no playlists and makes no such notes; a note
/// with songs to place, were one ever here, is left as it is for the app that made it.)
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_askedArrivals<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: NowIn| {
        let place = place(given.place)?;
        let library = place.library();
        let ((told, changed), asked) = place.change(|items| {
            let arrivals: Vec<Arrival> = asked::arrivals(items, given.now, |title, artist| library.same_album(title, artist), |_| None);
            // (Which notes are news is the library's to say, by the note itself and not
            // by where it stood in the list: `asked::news`.)
            let told: Vec<Asked> = asked::news(items, &arrivals);
            if arrivals.is_empty() {
                return (told, false);
            }
            asked::mark(items, &arrivals, given.now);
            (told, true)
        });
        Ok(Arrived { news: requests::arrived_news(&told), told, changed, asked })
    })
}

/// `requests::arrived_news`: what to say of albums that were asked for and have arrived
/// (the notes as they were when it was found). Empty for none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_arrivedNews<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |told: Vec<Asked>| Ok(requests::arrived_news(&told)))
}

// ---- artists you might like ------------------------------------------------------------------------

/// A song of the library's, as far as choosing favourites needs it.
#[derive(Deserialize)]
struct Played {
    artist_id: String,
    artist: String,
    plays: u32,
}

/// `discover::favourites`: the artists played most, favourite first, as the music server's
/// id for each and their name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_discoverFavourites<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |songs: Vec<Played>| {
        let songs: Vec<nexus_core::model::Song> =
            songs.into_iter().map(|song| nexus_core::model::Song { artist_id: song.artist_id, artist: song.artist, play_count: song.plays, ..Default::default() }).collect();
        Ok(discover::favourites(&songs))
    })
}

#[derive(Deserialize)]
struct LookIn {
    operation: u64,
    lidarr: Connection,
    place: u64,
    /// For each favourite, in order: their name and who the music server says they are
    /// like, most alike first.
    similar: Vec<(String, Vec<String>)>,
    now: i64,
}

/// `discover::look`: suggestions looked for afresh. **Kept only if it is got**: a stopped
/// look is no answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_discoverLook<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: LookIn| {
        let _during = During::new(given.operation);
        let place = place(given.place)?;
        let lidarr = match given.lidarr.request_lidarr(given.operation) {
            Ok(lidarr) => lidarr,
            Err(why) => return Ok(outcome(Err(why))),
        };
        let library = place.library();
        Ok(found(discover::look(lidarr.as_ref(), &given.similar, library.as_ref(), given.now)))
    })
}

#[derive(Deserialize)]
struct KeptIn {
    place: u64,
    kept: Kept,
    #[serde(default)]
    now: i64,
    /// An artist to stop suggesting, if that is what is asked.
    #[serde(default)]
    forget: String,
}

/// What is to be shown of the suggestions that are kept, and whether they will still do.
#[derive(Serialize)]
struct Suggested {
    /// `Kept::fresh`: they will do; if not, it is time to look again.
    fresh: bool,
    /// `Kept::shown`: what is kept, less anyone the library has gained since.
    shown: Vec<discover::Suggestion>,
    /// What is kept, after `forget` if one was asked: to be kept again if `changed`.
    kept: Kept,
    changed: bool,
}

/// The suggestions that are kept, read as the library reads them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_discoverKept<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: KeptIn| {
        let mut kept = given.kept;
        let changed = !given.forget.is_empty() && kept.forget(&given.forget);
        let have = place(given.place)?.library().artist_names();
        Ok(Suggested { fresh: kept.fresh(given.now), shown: kept.clone().shown(&have), kept, changed })
    })
}
