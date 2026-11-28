//! The phone's one way to the Nexus server's rules: the core's `nexus::client`, one device's
//! dealings with the backend as a machine driven by four calls (the backend design's 5.5):
//! **start** with what was kept, **happened** for whatever happens, **do** each thing it gives
//! back, **stop** when the account changes. Everything that decides (signing in, what is kept,
//! a refused music login, what is shown and in what words) is the machine's; the phone sends
//! what it is told to send, keeps what it is told to keep, and shows what it is told to show.
//!
//! **This file only carries values across.** It reads the phone's JSON into the machine's own
//! types and writes what the machine gives back with the machine's own serialisation (`Do`).
//! (Until the core's own JSON entry point, `Client::call_json`, lands: then this becomes a call
//! to it. See the report of P7.)
//!
//! A phone has one machine; the unit tests make several apps in one process, so each machine
//! is kept here by a number the phone is given when it makes one.
//!
//! Everything here can hold a secret (a password typed, a session, a music login): every call
//! is `call_private`, and nothing it was given is ever quoted.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::{Deserialize, Serialize};

use nexus_core::nexus::account::{DeviceKind, DeviceSecret, MusicLogin, Name, Session};
use nexus_core::nexus::client::{AccountFile, AnswerBody, App, Client, Do, Failure, Given, Happened, Kept, Press, Random, ToTell, Typed};
use nexus_core::nexus::proto::Version;

use crate::call_private;

fn machines() -> &'static Mutex<HashMap<u64, Client>> {
    static MACHINES: OnceLock<Mutex<HashMap<u64, Client>>> = OnceLock::new();
    MACHINES.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

// ---- what the phone hands in -----------------------------------------------------------------

#[derive(Deserialize)]
struct GivenIn {
    now_ms: u64,
    local_offset_minutes: i32,
    /// 32 bytes from the phone's source of randomness; none if it gave none.
    #[serde(default)]
    random: Option<Vec<u8>>,
}

impl GivenIn {
    fn given(&self) -> Given {
        let random = self.random.as_deref().and_then(|bytes| <[u8; 32]>::try_from(bytes).ok()).map(Random::new);
        Given { now_ms: self.now_ms, local_offset_minutes: self.local_offset_minutes, random }
    }
}

#[derive(Deserialize)]
struct AppIn {
    version: String,
    device_name: String,
}

/// A music login as the machine writes it when it says to keep one (`Keep::Music`).
#[derive(Deserialize)]
struct MusicIn {
    address: String,
    name: String,
    salt: String,
    token: String,
    count: u64,
}

impl MusicIn {
    fn login(&self) -> Option<MusicLogin> {
        use nexus_core::nexus::account::{MusicAddress, MusicSalt, MusicToken};
        Some(MusicLogin {
            address: MusicAddress::new(&self.address)?,
            name: Name::new(&self.name)?,
            salt: MusicSalt::new(&self.salt)?,
            token: MusicToken::from_hex(&self.token)?,
            count: self.count,
        })
    }
}

/// A sign-out still to be told, as the machine writes it (`Keep::ToTell`).
#[derive(Deserialize)]
struct ToTellIn {
    session: String,
    backend: String,
    restore: u64,
}

/// What was kept, each part as the machine wrote it when it said to keep it. A part that can't
/// be read is handed over as missing: the machine then deals with it as with nothing kept.
#[derive(Deserialize)]
struct KeptIn {
    #[serde(default)]
    device_secret: Option<String>,
    #[serde(default)]
    session: Option<String>,
    #[serde(default)]
    music: Option<MusicIn>,
    #[serde(default)]
    account: Option<AccountFile>,
    #[serde(default)]
    to_tell: Option<ToTellIn>,
    #[serde(default)]
    offline: bool,
}

impl KeptIn {
    fn kept(self) -> Kept {
        Kept {
            device_secret: self.device_secret.as_deref().and_then(DeviceSecret::from_hex),
            session: self.session.as_deref().and_then(Session::from_hex),
            music: self.music.as_ref().and_then(MusicIn::login),
            account: self.account,
            to_tell: self.to_tell.and_then(|tell| Some(ToTell { session: Session::from_hex(&tell.session)?, backend: tell.backend, restore: tell.restore })),
            offline: self.offline,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum PressIn {
    SignIn { address: String, email: String, password: String },
    SignInWithCode { address: String, code: String },
    ChoosePassword { password: String, again: String },
    SignOut,
    OpenDevices,
    ForceOut { device: u64 },
    ChangePassword { current: String, new: String, again: String },
    NoticeSeen,
}

impl PressIn {
    fn press(self) -> Press {
        match self {
            PressIn::SignIn { address, email, password } => Press::SignIn { address, email, password: Typed::new(&password) },
            PressIn::SignInWithCode { address, code } => Press::SignInWithCode { address, code: Typed::new(&code) },
            PressIn::ChoosePassword { password, again } => Press::ChoosePassword { password: Typed::new(&password), again: Typed::new(&again) },
            PressIn::SignOut => Press::SignOut,
            PressIn::OpenDevices => Press::OpenDevices,
            PressIn::ForceOut { device } => Press::ForceOut { device },
            PressIn::ChangePassword { current, new, again } => Press::ChangePassword { current: Typed::new(&current), new: Typed::new(&new), again: Typed::new(&again) },
            PressIn::NoticeSeen => Press::NoticeSeen,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum EventIn {
    Pressed(PressIn),
    Answered { ask: u64, status: u16, body: Vec<u8> },
    Failed { ask: u64, failure: Failure },
    Network { up: bool },
    Offline(bool),
    Tick,
    MusicRefused { count: u64 },
}

impl EventIn {
    fn happened(self) -> Happened {
        match self {
            EventIn::Pressed(press) => Happened::Pressed(press.press()),
            EventIn::Answered { ask, status, body } => Happened::Answered { ask, status, body: AnswerBody::new(body) },
            EventIn::Failed { ask, failure } => Happened::Failed { ask, failure },
            EventIn::Network { up } => Happened::Network { up },
            EventIn::Offline(on) => Happened::Offline(on),
            EventIn::Tick => Happened::Tick,
            EventIn::MusicRefused { count } => Happened::MusicRefused { count },
        }
    }
}

#[derive(Deserialize)]
struct CallIn {
    machine: u64,
    /// "start", "happened", "stop", "waits_for".
    call: String,
    #[serde(default)]
    given: Option<GivenIn>,
    #[serde(default)]
    app: Option<AppIn>,
    #[serde(default)]
    kept: Option<KeptIn>,
    #[serde(default)]
    event: Option<EventIn>,
    #[serde(default)]
    ask: u64,
}

#[derive(Serialize)]
struct CallOut {
    /// What to do, in order: the machine's own `Do`, as it writes them.
    does: Vec<Do>,
    /// The generation now (every ask carries the one it was made under).
    generation: u64,
    /// For "waits_for": whether the machine still waits for that ask.
    waits: bool,
}

const REFUSED: &str = "The shared library was handed something it doesn't take.";

/// A new machine: its number.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusMachine<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |_: serde_json::Value| {
        let number = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        machines().lock().map_err(|_| REFUSED.to_string())?.insert(number, Client::default());
        Ok(number)
    })
}

/// One of the four calls, to the machine of that number.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusCall<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: CallIn| {
        let mut all = machines().lock().map_err(|_| REFUSED.to_string())?;
        let machine = all.get_mut(&given.machine).ok_or_else(|| "There is no such machine.".to_string())?;
        let now = given.given.as_ref().map(GivenIn::given);
        let does = match given.call.as_str() {
            "start" => {
                let app = given.app.ok_or(REFUSED)?;
                let app = App {
                    version: Version::parse(&app.version).ok_or(REFUSED)?,
                    kind: DeviceKind::Phone,
                    device_name: Name::new(&app.device_name).ok_or(REFUSED)?,
                };
                machine.start(app, given.kept.ok_or(REFUSED)?.kept(), &now.ok_or(REFUSED)?)
            }
            "happened" => machine.happened(given.event.ok_or(REFUSED)?.happened(), &now.ok_or(REFUSED)?),
            "stop" => {
                machine.stop();
                Vec::new()
            }
            "waits_for" => Vec::new(),
            "close" => {
                all.remove(&given.machine);
                return Ok(CallOut { does: Vec::new(), generation: 0, waits: false });
            }
            _ => return Err(REFUSED.to_string()),
        };
        Ok(CallOut { waits: machine.waits_for(given.ask), generation: machine.generation(), does })
    })
}
