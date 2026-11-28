//! A transport for the core's Requests stand-in. The driver checks the reply
//! before the stand-in gets it, and a transport failure ends sends for this operation.

use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::Deserialize;
use serde_json::{Value, json};

use nexus_core::nexus::account::{Reply, RequestId};
use nexus_core::nexus::requests::{
    self, Answer, AppBatchResolution, AppBatchStep, AppRoute, BackendLidarr, Refusal,
    RequestError, TransportError, TransportReply,
};

use crate::call_private;
use crate::client::FeatureScope;
use crate::wire;

pub(crate) fn backend(scope: &FeatureScope, operation: u64) -> BackendLidarr<'_> {
    enum Ended {
        Transport(TransportError),
        ExpiredOffer { status: u16, body: Vec<u8> },
    }

    // Core can poll again after transport loss or obtain a fresh expired offer.
    // The phone ends this operation at the first failure and shows its sentence.
    let ended = std::sync::Arc::new(Mutex::new(None::<Ended>));
    let paused = ended.clone();
    BackendLidarr::new(
        scope.head(),
        move |_, body, _| {
            if let Some(ended) = &*ended.lock().unwrap_or_else(|err| err.into_inner()) {
                return match ended {
                    Ended::Transport(error) => Err(*error),
                    Ended::ExpiredOffer { status, body } => Ok(TransportReply { status: *status, body: body.clone() }),
                };
            }
            let answer = wire::nexus_post(operation, body);
            match &answer {
                Err(error) => *ended.lock().unwrap_or_else(|err| err.into_inner()) = Some(Ended::Transport(*error)),
                Ok(reply) if matches!(requests::read_reply(reply.status, &reply.body),
                    Ok(Reply::Done { result: Answer::Refused { reason: Refusal::SearchAgain }, .. })) =>
                    *ended.lock().unwrap_or_else(|err| err.into_inner()) = Some(Ended::ExpiredOffer {
                        status: reply.status,
                        body: reply.body.clone(),
                    }),
                Ok(_) => {}
            }
            answer
        },
        |receipt| scope.owns(&receipt.stamp).then_some(()).ok_or(RequestError::UnexpectedAnswer),
        move |wait, _| {
            if paused.lock().unwrap_or_else(|err| err.into_inner()).is_some() {
                return Err(TransportError::Stopped);
            }
            wire::nexus_pause(operation, wait)
        },
        move || wire::nexus_id(operation).and_then(|id| RequestId::new(id).ok_or(TransportError::Stopped)),
    )
}

#[derive(Deserialize)]
#[serde(tag = "step", rename_all = "snake_case")]
enum In {
    Status { scope: FeatureScope, operation: u64 },
    BatchStep { kept: Option<AppBatchResolution> },
}

fn route(status: Result<requests::Availability, RequestError>) -> Value {
    match requests::app_route(&status) {
        AppRoute::Direct => json!({"route": "direct"}),
        AppRoute::Server => json!({"route": "server"}),
        AppRoute::Unavailable => json!({"route": "unavailable", "words": status.unwrap_err().to_string()}),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusRequests<'l>(
    env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>,
) -> jbyteArray {
    call_private(env, input, |given: In| {
        Ok(match given {
            In::Status { scope, operation } => route(backend(&scope, operation).status()),
            In::BatchStep { kept } => match requests::app_batch_step(kept.as_ref()) {
                AppBatchStep::Lookup => json!({"step": "lookup"}),
                AppBatchStep::Send { release, sends } => json!({"step": "send", "release": release, "sends": sends}),
                AppBatchStep::Wait => json!({"step": "wait"}),
            },
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_explicit_unconfigured_status_allows_direct() {
        use requests::Availability::*;
        for status in [
            Ok(Ready), Ok(Unavailable),
            Err(TransportError::Stopped.into()), Err(TransportError::Unreachable.into()),
            Err(RequestError::UnexpectedAnswer), Err(RequestError::Refused(Refusal::SwitchedOff)),
            Err(RequestError::Refused(Refusal::NotAllowed)), Err(RequestError::Refused(Refusal::NotSignedIn)),
        ] {
            assert_ne!(route(status)["route"], "direct");
        }
        assert_eq!(route(Ok(Unconfigured))["route"], "direct");
        assert_eq!(route(Err(RequestError::Refused(Refusal::LidarrNotSetUp)))["route"], "direct");
    }
}

pub(crate) fn cancel(
    scope: &FeatureScope, operation: u64, id: i64, album: &str,
) -> Result<(), String> {
    let (Some(id), Some(album)) = (requests::LidarrNumber::new(id), requests::MusicBrainzId::new(album)) else {
        return Err(Refusal::NoLongerListed.sentence(None).into_owned());
    };
    backend(scope, operation).cancel_release(id, album).map_err(|error| error.to_string())
}

pub(crate) fn waiting(
    client: &BackendLidarr<'_>, library: &dyn nexus_core::requests::Library,
) -> Result<Value, String> {
    let history = client.asked_history().map_err(|error| error.to_string())?;
    let (pending, words) = match client.pending_rows() {
        Ok(rows) => (rows, String::new()),
        Err(reason) => (Vec::new(), reason.to_string()),
    };
    let ids: std::collections::HashSet<_> = pending.iter().map(|row| row.album.as_str()).collect();
    let mut waiting = nexus_core::requests::Waiting::default();
    for row in history.into_iter().filter(|row| !ids.contains(row.release.as_str())) {
        let at = i64::try_from(row.asked_ms / 1_000).unwrap_or(i64::MAX);
        if let Some((album, cover)) = library.same_album(&row.title, &row.artist) {
            let line = row.artist.clone();
            waiting.arrived.push((nexus_core::asked::Asked {
                album: row.release.into(), title: row.title, artist: row.artist, at, arrived: at.max(1),
                library_album: album, cover, ..Default::default()
            }, line));
        } else {
            let line = format!("{} • Asked", row.artist);
            waiting.pending.push((nexus_core::lidarr::Pending {
                album: row.release.into(), title: row.title, artist: row.artist, ..Default::default()
            }, line));
        }
    }
    let removable: Vec<_> = pending.iter().filter(|row| row.removable).map(|row| row.id.get()).collect();
    for row in pending {
        let progress = row.progress.map_or_else(|| "Being looked for".to_string(), |percent| format!("Downloading, {percent}%"));
        let line = format!("{} • {progress}", row.artist);
        waiting.pending.push((row.into(), line));
    }
    if !waiting.pending.is_empty() {
        waiting.heading = "Asked for".into();
    }
    waiting.failed = !words.is_empty();
    waiting.empty = if !words.is_empty() {
        words
    } else if waiting.pending.is_empty() && waiting.arrived.is_empty() {
        "You haven't asked for any music yet. Search for something that isn't in your library to make a request.".into()
    } else {
        String::new()
    };
    let mut answer = serde_json::to_value(waiting).map_err(|_| "Couldn't show Requests.".to_string())?;
    answer["removable"] = json!(removable);
    Ok(answer)
}
