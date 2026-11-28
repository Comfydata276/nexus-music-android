//! Android projection of checked listening replies and the shared one-action deletion.
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use serde::Deserialize;
use serde_json::{Value, json};

use nexus_core::nexus::account::{self, NotAllowed, Password, Refusal, Reply, RequestId, Stamp};
use nexus_core::nexus::events::{self, HistoryDeleteError, HistoryDeletion, Kind, StatsPage, StatsRequest};
use crate::client::FeatureScope;

use crate::call_private;

#[derive(Deserialize)]
struct Answer {
    status: u16,
    body: String,
}

impl Answer {
    fn stats(&self, scope: &FeatureScope) -> Result<(Stamp, StatsPage), HistoryDeleteError> {
        match events::read_stats_reply(self.status, self.body.as_bytes())? {
            Reply::Done { result, receipt } if scope.owns(&receipt.stamp) => Ok((receipt.stamp, result)),
            Reply::Refused(reason) => Err(HistoryDeleteError::Refused(reason)),
            _ => Err(HistoryDeleteError::Invalid),
        }
    }
}

#[derive(Deserialize)]
struct Delete {
    stats: Answer,
    action: u64,
}

impl Delete {
    fn plan(&self, scope: &FeatureScope) -> Result<HistoryDeletion, HistoryDeleteError> {
        let (stamp, page) = self.stats.stats(scope)?;
        let id = RequestId::new(self.action).ok_or(HistoryDeleteError::Invalid)?;
        HistoryDeletion::new(scope.head(), &stamp, &page, id)
    }
}

#[derive(Deserialize)]
struct Input {
    scope: FeatureScope,
    #[serde(flatten)]
    step: Step,
}

#[derive(Deserialize)]
#[serde(tag = "step", rename_all = "snake_case")]
enum Step {
    Stats,
    Read { answer: Answer },
    Grant { delete: Delete, password: String },
    Spend { delete: Delete, answer: Answer },
    Confirm { delete: Delete, answer: Answer },
}

fn words(error: HistoryDeleteError) -> String {
    match error {
        HistoryDeleteError::Refused(Refusal::NotAllowed(NotAllowed::WrongLogin)) => {
            "That password didn't match. Try again.".into()
        }
        HistoryDeleteError::Refused(Refusal::Busy { retry_after_seconds: Some(wait) }) => {
            format!("Try again in {} seconds.", wait.seconds())
        }
        HistoryDeleteError::Refused(Refusal::NotSignedIn(_)) => {
            "Sign in again or turn off Offline mode to continue.".into()
        }
        HistoryDeleteError::MissingRevision => {
            "This Nexus server didn't provide the account revision needed to delete history.".into()
        }
        _ => "Your Nexus server couldn't complete this action. Try again.".into(),
    }
}

fn post(path: &str, bytes: &[u8]) -> Result<Value, HistoryDeleteError> {
    let body = std::str::from_utf8(bytes).map_err(|_| HistoryDeleteError::Invalid)?;
    Ok(json!({"path": path, "body": body}))
}

fn step(Input { scope, step }: Input) -> Result<Value, HistoryDeleteError> {
    match step {
        Step::Stats => {
            let body = account::encode(&StatsRequest { head: scope.head(), after: None })?;
            post(events::STATS_PATH, body.bytes())
        }
        Step::Read { answer } => {
            let (_, page) = answer.stats(&scope)?;
            let recent: Vec<_> = page.recent.iter().filter_map(|play| {
                let record = play.event.record();
                let Kind::Start { title, artist, album, .. } = record.kind() else { return None };
                Some(json!({
                    "title": events::clean_display_text(title),
                    "artist": events::clean_display_text(artist),
                    "album": events::clean_display_text(album),
                    "at_ms": record.at_ms(),
                }))
            }).collect();
            Ok(json!({"starts": page.starts, "recent": recent, "can_delete": page.account_revision.is_some()}))
        }
        Step::Grant { delete, password } => {
            let Some(password) = Password::presented(&password) else {
                return Ok(json!({"error": "Enter your current Nexus password."}));
            };
            let body = delete.plan(&scope)?.grant(password)?;
            post(account::GRANT, body.bytes())
        }
        Step::Spend { delete, answer } => {
            let body = delete.plan(&scope)?.spend(answer.status, answer.body.as_bytes())?;
            post(account::DELETE_LISTENING_DATA, body.bytes())
        }
        Step::Confirm { delete, answer } => {
            let life = delete.plan(&scope)?.confirmed_life(answer.status, answer.body.as_bytes())?;
            Ok(json!({"life": life}))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusListening<'l>(
    env: JNIEnv<'l>, _: JClass<'l>, input: JByteArray<'l>,
) -> jbyteArray {
    call_private(env, input, |input: Input| {
        Ok(step(input).unwrap_or_else(|error| json!({"error": words(error)})))
    })
}
