//! **For the tests' stand-in for the Nexus server only**: the bodies a backend answers with, and
//! the status each goes with, written by the core's own types and encoder
//! (`nexus::account::encode`, `Reply::status`, `Hello::for_accounts`), so that what the phone's
//! tests are answered with is exactly what the real backend would send, never a copy of the
//! shapes written by hand in Kotlin.
//!
//! (In every build of the library, as `echo` and `stop` are, and called by nothing in the app:
//! the library is built once for every kind of build, so that the library that ships is the
//! library that was tested.)

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::{Deserialize, Serialize};

use nexus_core::nexus::account::{
    self, AccountId, DeviceId, DeviceKind, DevicePage, Email, ForcedOut, MusicAddress, MusicLogin, MusicLoginResult, MusicSalt, MusicToken, Name,
    NotSignedIn, Notice, PasswordChanged, Person, Receipt, Refusal, Reply, Session, SessionId, SignInAnswer, SignOutNote, SignOutSecret, SignedIn,
    SignedOut, Stamp, StampTag,
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
    /// "password_changed", "session_ended", "wrong_login".
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
}

#[derive(Serialize)]
struct StandInOut {
    status: u16,
    body: Vec<u8>,
}

const BAD: &str = "The stand-in was asked for an answer the core can't write.";
const ACCOUNT: u64 = 7;
const SESSION_ID: u64 = 11;

fn receipt(given: &StandInIn, count: u64) -> Option<Receipt> {
    Some(Receipt {
        stamp: Stamp { backend: BackendId::from_hex(&given.backend)?, restore: 1, account: AccountId::new(ACCOUNT)?, life: 1, change: 1, tag: StampTag::from_bytes([7; 32]) },
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

/// A reply, written by the core's own encoder (which checks it as a reader would), with the
/// status the core gives its kind.
macro_rules! reply {
    ($reply:expr) => {{
        let reply = $reply.ok_or(BAD)?;
        let status = reply.status();
        Ok(StandInOut { status, body: account::encode(&reply).map_err(|_| BAD.to_string())?.bytes().to_vec() })
    }};
}

/// An answer of the backend's, for the tests' stand-in.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusStandIn<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call_private(env, input, |given: StandInIn| {
        let count = given.music.as_ref().map(|music| music.count).unwrap_or(given.count);
        match given.kind.as_str() {
            "hello" => {
                let hello = Hello::for_accounts(Version::parse("0.2.0").ok_or(BAD)?, BackendId::from_hex(&given.backend).ok_or(BAD)?, SPEAKS);
                Ok(StandInOut { status: 200, body: hello.to_json().into_bytes() })
            }
            "signed_in" => reply!((|| {
                let signed = SignedIn {
                    session: Session::from_hex(&given.session)?,
                    session_id: SessionId::new(SESSION_ID)?,
                    sign_out: SignOutNote { session: SessionId::new(SESSION_ID)?, secret: SignOutSecret::from_bytes([9; 32]) },
                    person: Person { id: AccountId::new(ACCOUNT)?, revision: 1, email: Email::new(&given.email)?, name: Name::new(&given.name)?, admin: false },
                    device: DeviceId::new(given.device.max(1))?,
                    restore: 1,
                    music: MusicLoginResult::Login(music(given.music.as_ref()?)?),
                    notice: Notice::new("This server records what you play.")?,
                };
                Some(Reply::<SignInAnswer>::Done { result: signed, receipt: receipt(&given, count)? })
            })()),
            "music_login" => reply!((|| Some(Reply::Done { result: MusicLoginResult::Login(music(given.music.as_ref()?)?), receipt: receipt(&given, count)? }))()),
            "devices" => reply!((|| {
                let me = account::Device {
                    id: DeviceId::new(given.device.max(1))?,
                    name: Name::new("Test phone")?,
                    kind: DeviceKind::Phone,
                    app_version: Version::parse("0.6.3")?,
                    last_seen_ms: 0,
                    session: SessionId::new(SESSION_ID),
                    this_device: true,
                };
                Some(Reply::Done { result: DevicePage::new(vec![me], false)?, receipt: receipt(&given, count)? })
            })()),
            "signed_out" => reply!((|| Some(Reply::Done { result: SignedOut {}, receipt: receipt(&given, count)? }))()),
            "forced_out" => reply!((|| Some(Reply::Done { result: ForcedOut { music_cut_off: false }, receipt: receipt(&given, count)? }))()),
            "password_changed" => reply!((|| Some(Reply::Done { result: PasswordChanged {}, receipt: receipt(&given, count)? }))()),
            "session_ended" => reply!(Some(Reply::<SignedOut>::Refused(Refusal::NotSignedIn(NotSignedIn::SessionEnded)))),
            "wrong_login" => reply!(Some(Reply::<SignInAnswer>::Refused(Refusal::NotAllowed(account::NotAllowed::WrongLogin)))),
            _ => Err(BAD.to_string()),
        }
    })
}
