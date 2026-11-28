//! **For the tests' stand-in for the Nexus server only**: the bodies a backend answers with,
//! written by the core's own types and encoder (`nexus::account::encode`, `Hello::to_json`), so
//! that what the phone's tests are answered with is exactly what the real backend would send,
//! and never a copy of the shapes written by hand in Kotlin.
//!
//! (In every build of the library, as `echo` and `stop` are, and called by nothing in the app:
//! the library is built once for every kind of build, so that the library that ships is the
//! library that was tested.)

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::{Deserialize, Serialize};

use nexus_core::nexus::account::{
    self, DeviceId, DeviceKind, DevicePage, Email, ForcedOut, MusicAddress, MusicLogin, MusicSalt, MusicToken, Name, Notice, PasswordChanged,
    Person, Receipt, Refusal, Reply, Session, SignInAnswer, SignedIn, SignedOut, Stamp, StampTag,
};
use nexus_core::nexus::proto::{BackendId, Hello, SPEAKS, Version};

use crate::call_private;

#[derive(Deserialize)]
struct MusicIn {
    address: String,
    name: String,
    salt: String,
    token: String,
    count: u64,
}

#[derive(Deserialize)]
struct StandInIn {
    /// "hello", "signed_in", "music_login", "devices", "signed_out", "forced_out",
    /// "password_changed", or "refused" (with `reason`, the core's own tag).
    kind: String,
    backend: String,
    #[serde(default)]
    email: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    session: String,
    #[serde(default)]
    device: u64,
    #[serde(default)]
    music: Option<MusicIn>,
    #[serde(default)]
    count: u64,
    #[serde(default)]
    reason: String,
}

#[derive(Serialize)]
struct StandInOut {
    body: Vec<u8>,
}

const BAD: &str = "The stand-in was asked for an answer the core can't write.";

fn receipt(given: &StandInIn, count: u64) -> Option<Receipt> {
    Some(Receipt {
        stamp: Stamp { backend: BackendId::from_hex(&given.backend)?, restore: 1, account: Email::new(&given.email)?, life: 1, change: 1, tag: StampTag::from_bytes([7; 32]) },
        music_login_count: count,
    })
}

fn music(given: &MusicIn) -> Option<MusicLogin> {
    Some(MusicLogin {
        address: MusicAddress::new(&given.address)?,
        name: Name::new(&given.name)?,
        salt: MusicSalt::new(&given.salt)?,
        token: MusicToken::from_hex(&given.token)?,
        count: given.count,
    })
}

/// A reply, written by the core's own encoder (which checks it as a reader would).
macro_rules! body {
    ($reply:expr) => {{
        let reply = $reply.ok_or(BAD)?;
        Ok(StandInOut { body: account::encode(&reply).map_err(|_| BAD.to_string())?.bytes().to_vec() })
    }};
}

fn refusal(reason: &str) -> Option<Refusal> {
    Some(match reason {
        "wrong_login" => Refusal::WrongLogin,
        "account_off" => Refusal::AccountOff,
        "busy" => Refusal::Busy,
        "not_signed_in" => Refusal::NotSignedIn,
        "start_again" => Refusal::StartAgain,
        "no_music_login" => Refusal::NoMusicLogin,
        _ => return None,
    })
}

/// An answer of the backend's, for the tests' stand-in.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusStandIn<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: StandInIn| {
        let count = given.music.as_ref().map(|music| music.count).unwrap_or(given.count);
        match given.kind.as_str() {
            "hello" => {
                let hello = Hello::new(Version::parse("0.2.0").ok_or(BAD)?, BackendId::from_hex(&given.backend).ok_or(BAD)?, SPEAKS, &["accounts"]).ok_or(BAD)?;
                Ok(StandInOut { body: hello.to_json().into_bytes() })
            }
            "signed_in" => body!((|| {
                let signed = SignedIn {
                    session: Session::from_hex(&given.session)?,
                    person: Person { email: Email::new(&given.email)?, name: Name::new(&given.name)?, admin: false },
                    device: DeviceId::new(given.device)?,
                    music: music(given.music.as_ref()?)?,
                    notice: Notice::new("This server records what you play.")?,
                };
                Some(Reply::Done { result: SignInAnswer::SignedIn(signed), receipt: receipt(&given, count)? })
            })()),
            "music_login" => body!((|| Some(Reply::Done { result: music(given.music.as_ref()?)?, receipt: receipt(&given, count)? }))()),
            "devices" => body!((|| {
                let me = account::Device {
                    id: DeviceId::new(given.device.max(1))?,
                    name: Name::new("Test phone")?,
                    kind: DeviceKind::Phone,
                    app_version: Version::parse("0.6.3")?,
                    last_seen_ms: 0,
                    signed_in: true,
                };
                Some(Reply::Done { result: DevicePage::new(vec![me], false)?, receipt: receipt(&given, count)? })
            })()),
            "signed_out" => body!((|| Some(Reply::Done { result: SignedOut {}, receipt: receipt(&given, count)? }))()),
            "forced_out" => body!((|| Some(Reply::Done { result: ForcedOut { music_cut_off: false }, receipt: receipt(&given, count)? }))()),
            "password_changed" => body!((|| Some(Reply::Done { result: PasswordChanged {}, receipt: receipt(&given, count)? }))()),
            "refused" => body!(refusal(&given.reason).map(Reply::<SignedOut>::Refused)),
            _ => Err(BAD.to_string()),
        }
    })
}
