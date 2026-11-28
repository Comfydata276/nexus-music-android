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
    self, Answer, AppBatchResolution, AppBatchStep, AppRoute, BackendLidarr, BatchLine, BatchLineProgress, Change, JobId, MusicBrainzId, Progress, Refusal,
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
                Ok(reply) if reply.body.len() <= requests::SMALL_REPLY_MAX_BYTES && matches!(requests::read_reply(reply.status, &reply.body),
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
    BatchBegin { scope: FeatureScope, operation: u64, request: u64, singles: bool, lines: Vec<BatchLine> },
    BatchProgress { scope: FeatureScope, operation: u64, job: JobId },
    BatchResolve { scope: FeatureScope, operation: u64, job: JobId, index: u16, release: Option<MusicBrainzId> },
    BatchLookup { operation: u64, line: BatchLine, singles: bool },

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
        Ok(run(given).unwrap_or_else(|error| json!({
            "error": error.to_string(),
            "missing": error == RequestError::Refused(Refusal::JobNotFound),
        })))
    })
}

fn run(given: In) -> Result<Value, RequestError> {
    Ok(match given {
        In::Status { scope, operation } => route(backend(&scope, operation).status()),
        In::BatchStep { kept } => match requests::app_batch_step(kept.as_ref()) {
            AppBatchStep::Lookup => json!({"step": "lookup"}),
            AppBatchStep::Send { release, sends } => json!({"step": "send", "release": release, "sends": sends}),
            AppBatchStep::Wait => json!({"step": "wait"}),
        },
        In::BatchBegin { scope, operation, request, singles, lines } => {
            let request = RequestId::new(request).ok_or(RequestError::UnexpectedAnswer)?;
            let job = backend(&scope, operation).begin(request, Change::Batch { singles, lines })?;
            json!({"job": job})
        }
        In::BatchProgress { scope, operation, job } => {
            let client = backend(&scope, operation);
            let progress = client.after(job)?;
            let rows = client.batch_lines(job)?;
            let working = matches!(progress, Progress::Working);
            let notice = match progress {
                Progress::Working => "Your Nexus server is working through this batch.".into(),
                Progress::Done(result) => result.sentence().into(),
                Progress::Failed(reason) => reason.sentence(None).into_owned(),
            };
            let rows: Vec<_> = rows.into_iter().map(|row| {
                let detail = match &row.progress {
                    BatchLineProgress::Waiting => "Waiting".into(),
                    BatchLineProgress::NeedsResolution => "Finding the song's release".into(),
                    BatchLineProgress::Found { .. } => "Release found".into(),
                    BatchLineProgress::Asked { .. } => "Asked".into(),
                    BatchLineProgress::Done { .. } => "On the list".into(),
                    BatchLineProgress::NotFound => "Couldn't find a release".into(),
                    BatchLineProgress::Failed { reason, .. } => reason.sentence(None).into_owned(),
                };
                json!({"index": row.index, "detail": detail, "needs_resolution": row.progress == BatchLineProgress::NeedsResolution})
            }).collect();
            json!({"working": working, "notice": notice, "rows": rows})
        }
        In::BatchResolve { scope, operation, job, index, release } => {
            backend(&scope, operation).resolve_batch_line(job, index, release)?;
            json!({"accepted": true})
        }
        In::BatchLookup { operation, line, singles } => {
            use nexus_core::listenbrainz::ListenBrainz;
            use crate::wire::{During, Host, Line, Whose};
            let _during = During::new(operation);
            let musicbrainz = crate::requests::musicbrainz().over(
                Host::new(Whose::Operation(operation), Line::MusicBrainz),
                ListenBrainz::new(Host::new(Whose::Operation(operation), Line::ListenBrainz)),
            );
            json!({"release": line.resolve_on_device(&musicbrainz, singles).ok().flatten()})
        }
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

pub(crate) fn asked_history(
    client: &BackendLidarr<'_>, library: &dyn nexus_core::requests::Library,
) -> Result<Vec<nexus_core::asked::Asked>, RequestError> {
    client.asked_history().map(|rows| rows.into_iter().map(|row| {
        let at = i64::try_from(row.asked_ms / 1_000).unwrap_or(i64::MAX);
        let (arrived, library_album, cover) = library.same_album(&row.title, &row.artist)
            .map_or((0, String::new(), row.cover), |(album, cover)| (at.max(1), album, cover));
        nexus_core::asked::Asked { album: row.release.into(), title: row.title, artist: row.artist, at,
            arrived, library_album, cover, ..Default::default() }
    }).collect())
}

pub(crate) fn waiting(
    client: &BackendLidarr<'_>, library: &dyn nexus_core::requests::Library,
) -> Result<Value, String> {
    let history = asked_history(client, library).map_err(|error| error.to_string())?;
    let (pending, words) = match client.pending_rows() {
        Ok(rows) => (rows, String::new()),
        Err(reason) => (Vec::new(), reason.to_string()),
    };
    let ids: std::collections::HashSet<_> = pending.iter().map(|row| row.album.as_str()).collect();
    let mut waiting = nexus_core::requests::Waiting::default();
    for row in history.into_iter().filter(|row| !ids.contains(row.album.as_str())) {
        if row.arrived != 0 {
            let line = row.artist.clone();
            waiting.arrived.push((row, line));
        } else {
            let line = format!("{} • Asked", row.artist);
            waiting.pending.push((nexus_core::lidarr::Pending {
                album: row.album, title: row.title, artist: row.artist, ..Default::default()
            }, line));
        }
    }
    let removable: Vec<_> = pending.iter().filter(|row| row.removable).map(|row| format!("{}/{}", row.id.get(), row.album.as_str())).collect();
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
