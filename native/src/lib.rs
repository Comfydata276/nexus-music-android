//! The shared core, as the phone app sees it.
//!
//! One function for each operation of the core the phone uses. Each takes one argument and
//! gives one answer, both JSON as UTF-8 **bytes** (never Java strings: Java's own encoding
//! of text damages what holds both a zero character and a character outside the basic
//! plane). The Kotlin side is `org.medianexus.music.core.Core`, and is the only thing in
//! the app that knows this library exists.
//!
//! Nothing here opens a connection, keeps a file, starts a thread or reads a clock: the
//! core does none of those, and neither does this layer.
//!
//! **What going wrong looks like.** An argument that isn't what the function takes, and a
//! panic anywhere below, both reach Kotlin as one exception (`CoreException`) carrying the
//! reason in words. A panic's place (file and line) goes to the phone's log.

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use nexus_core::step::{self, Found, Stated};

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
            let text = std::ffi::CString::new(format!("{info}")).unwrap_or_default();
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

/// What a panic said, as far as it said it in words.
fn said(panic: &(dyn std::any::Any + Send)) -> String {
    panic.downcast_ref::<&str>().map(|text| text.to_string()).or_else(|| panic.downcast_ref::<String>().cloned()).unwrap_or_default()
}

const EXCEPTION: &str = "org/medianexus/music/core/CoreException";

/// One call from Kotlin: the argument read from its bytes, `act` run on it, the answer
/// written back as bytes. Whatever goes wrong is thrown to Kotlin, and nothing is returned.
fn call<In: DeserializeOwned, Out: Serialize>(mut env: JNIEnv, input: JByteArray, act: impl FnOnce(In) -> Result<Out, String>) -> jbyteArray {
    log_panics();
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<jbyteArray, String> {
        let bytes = env.convert_byte_array(&input).map_err(|e| format!("The shared library couldn't be handed its argument: {e}"))?;
        let given: In = serde_json::from_slice(&bytes).map_err(|e| format!("The shared library was handed something it doesn't take: {e}"))?;
        let answer = serde_json::to_vec(&act(given)?).map_err(|e| format!("The shared library's answer couldn't be written out: {e}"))?;
        Ok(env.byte_array_from_slice(&answer).map_err(|e| format!("The shared library's answer couldn't be handed back: {e}"))?.into_raw())
    }));
    let why = match outcome {
        Ok(Ok(answer)) => return answer,
        Ok(Err(why)) => why,
        Err(panic) => format!("The shared library stopped: {}", said(panic.as_ref())),
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

// ---- for the tests of the boundary itself ------------------------------------------------------

/// Hands back the text it was given, having read it as text and written it again: what
/// crosses, crosses exactly.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_echo<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: String| Ok(given))
}

/// Panics, with the words it was given: for the check that a panic reaches Kotlin as an
/// exception and the app carries on. (Reached only from the app's debug builds and its
/// tests: see `Core.stopForTheTest`.)
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_stop<'l>(env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    call(env, input, |given: String| -> Result<(), String> { panic!("{given}") })
}
