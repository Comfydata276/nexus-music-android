//! The phone's one way to the Nexus server's rules: the core's `nexus::client`, one device's
//! dealings with the backend as a machine driven by four calls (the backend design's 5.5):
//! **start** with what was kept, **happened** for whatever happens, **do** each thing it gives
//! back, **stop** when the account changes. Everything that decides (signing in, what is kept,
//! a refused music login, what is shown and in what words) is the machine's.
//!
//! **This file only holds the machines.** Each call is the core's own JSON entry point,
//! `Client::call_json`, given the envelope exactly as Kotlin wrote it and giving back exactly
//! what the core wrote. The one thing added here is the number of the machine a call is for:
//! a phone has one, but the unit tests make several apps in one process.
//!
//! Everything here can hold a secret (a password typed, a session, a music login): every call
//! is `call_private`, and nothing it was given is ever quoted.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::Deserialize;
use serde_json::value::RawValue;

use nexus_core::nexus::client::Client;

use crate::call_private;

fn machines() -> &'static Mutex<HashMap<u64, Client>> {
    static MACHINES: OnceLock<Mutex<HashMap<u64, Client>>> = OnceLock::new();
    MACHINES.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT: AtomicU64 = AtomicU64::new(1);

const REFUSED: &str = "The shared library was handed something it doesn't take.";

/// A new machine: its number.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusMachine<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |_: serde_json::Value| {
        let number = NEXT.fetch_add(1, Ordering::Relaxed);
        machines().lock().map_err(|_| REFUSED.to_string())?.insert(number, Client::default());
        Ok(number)
    })
}

/// `{"machine": n, "call": <the core's envelope>}`; or `{"machine": n, "close": true}`.
#[derive(Deserialize)]
struct CallIn {
    machine: u64,
    #[serde(default)]
    call: Option<Box<RawValue>>,
    #[serde(default)]
    close: bool,
}

/// One call to the machine of that number, through `Client::call_json`: its answer as the core
/// wrote it (`{"generation":N,"do":[…]}`, or `{"error":"input"}`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusCall<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: CallIn| {
        let mut all = machines().lock().map_err(|_| REFUSED.to_string())?;
        if given.close {
            all.remove(&given.machine);
            return Ok(serde_json::json!({}));
        }
        let machine = all.get_mut(&given.machine).ok_or_else(|| "There is no such machine.".to_string())?;
        let call = given.call.ok_or(REFUSED)?;
        let answer = machine.call_json(call.get().as_bytes());
        // (Handed back as the value the core wrote, so that Kotlin reads exactly that.)
        serde_json::from_slice::<serde_json::Value>(&answer).map_err(|_| REFUSED.to_string())
    })
}

#[derive(Deserialize)]
struct CarriesIn {
    before_address: String,
    before_user: String,
    /// The music login handed over, as the machine wrote it (`Hand::Login`).
    music: MusicIn,
}

#[derive(Deserialize)]
struct MusicIn {
    address: String,
    name: String,
    salt: String,
    token: String,
    count: u64,
}

/// `client::carries_over`: whether the folder this phone had goes to the person this music login
/// was handed to (section 11). The one rule both apps apply.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusCarriesOver<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    use nexus_core::nexus::account::{MusicAddress, MusicLogin, MusicSalt, MusicToken, Name};
    call_private(env, input, |given: CarriesIn| {
        let music = &given.music;
        let login = (|| {
            Some(MusicLogin {
                address: MusicAddress::new(&music.address)?,
                name: Name::new(&music.name)?,
                salt: MusicSalt::new(&music.salt)?,
                token: MusicToken::from_hex(&music.token)?,
                count: music.count,
            })
        })();
        Ok(login.is_some_and(|login| nexus_core::nexus::client::carries_over(&given.before_address, &given.before_user, &login)))
    })
}
