use super::{invalid, private_directory, read, replace, version};
use nexus_core::nexus::{
    account::{DeviceKind, Notice},
    diag::{self, About, Fault, FaultPlace, Line, Record, Report, Source, SourceFile},
    events::{self, FaultShape, LossCounts},
    proto::Version,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{io, path::{Path, PathBuf}};

#[derive(Deserialize)]
pub(super) struct Request {
    root: PathBuf,
    #[serde(flatten)]
    action: Action,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Action {
    Notice {},
    Checkpoint { about: Phone, observations: Vec<Observation>, clear: bool },
    Read,
    Enabled { choice: Option<bool>, server_default: Option<bool> },
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Phone {
    version: String,
    android_major: u32,
    signed_in: bool,
    https: bool,
    offline: bool,
    local_permission: Option<bool>,
    android_auto: Option<bool>,
    loss: LossCounts,
}

impl From<Phone> for About {
    fn from(p: Phone) -> Self {
        Self {
            version: version(&p.version),
            device: DeviceKind::Phone,
            os: Version { major: p.android_major, minor: 0, patch: 0 },
            signed_in: p.signed_in,
            https: p.https,
            offline: p.offline,
            local_permission: p.local_permission,
            android_auto: p.android_auto,
            loss: p.loss,
            pc: None,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Observation {
    at: u64,
    line: ObservationKind,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case", remote = "diag::Reach")]
enum Reach {
    None,
    #[serde(rename = "name")]
    NameLookedUp,
    #[serde(rename = "connection")]
    ConnectionOpened,
    #[serde(rename = "secure")]
    MadeSecure,
    Answered,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case", remote = "diag::Network")]
enum Network { Wifi, Mobile, Ethernet, Vpn, Other, Unknown }

#[derive(Deserialize)]
#[serde(rename_all = "snake_case", remote = "diag::SessionEnded")]
enum SessionEnded {
    SignedOut, ForcedOut, Expired, PasswordChanged, PasswordReset, DataPutBack, AccountSwitchedOff, AccountDeleted, Other,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum ObservationKind {
    /// Only the shared report's canonical vocabulary can pass this boundary.
    Note { label: String },
    Fault { place: events::FaultPlace, shape: FaultShape, source_line: Option<u32> },
    Foreground { after_ms: u32 },
    DownloadJobStopped { reason: u8 },
    SessionEnd(#[serde(with = "SessionEnded")] diag::SessionEnded),
    Attempt {
        #[serde(with = "Reach")]
        reach: diag::Reach,
        local: Option<bool>,
        permission: Option<bool>,
        #[serde(with = "Network")]
        network: diag::Network,
        failed: bool,
    },
}

impl ObservationKind {
    fn line(self) -> Option<Line> {
        Some(match self {
            Self::Note { label } => match diag::clean_line(&label) {
                Some(line @ Line::Note(_)) => line,
                _ => return None,
            },
            Self::Fault { place, shape, source_line } => Line::Fault(Fault {
                place: match place {
                    events::FaultPlace::Playback => FaultPlace::Playback,
                    events::FaultPlace::AudioOutput => FaultPlace::AudioOutput,
                    events::FaultPlace::Library => FaultPlace::Library,
                    events::FaultPlace::Downloads => FaultPlace::Downloads,
                    events::FaultPlace::Foreground => FaultPlace::Foreground,
                    events::FaultPlace::MusicServer => FaultPlace::MusicServer,
                    events::FaultPlace::NexusServer => FaultPlace::NexusServer,
                    events::FaultPlace::Journal => FaultPlace::Journal,
                    events::FaultPlace::App => FaultPlace::App,
                },
                shape,
                source: source_line.filter(|n| *n > 0).map(|line| Source { file: SourceFile::Other, line }),
            }),
            Self::Foreground { after_ms } => Line::ForegroundAfterMs(after_ms.min(diag::MAX_FOREGROUND_MS)),
            Self::DownloadJobStopped { reason } => Line::DownloadJobStopped(reason),
            Self::SessionEnd(reason) => Line::SessionEnded(reason),
            Self::Attempt { .. } => return None,
        })
    }
}

fn kept(path: &Path) -> io::Result<Report> {
    let bytes = read(path, diag::CALL_BYTES)?;
    let text = std::str::from_utf8(&bytes).map_err(invalid)?;
    Report::read(text).map_err(invalid)
}

pub(super) fn call(request: Request) -> io::Result<Value> {
    let path = request.root.join("report.txt");
    match request.action {
        Action::Notice {} => Ok(json!(Notice::recording().as_str())),
        Action::Enabled { choice, server_default } => Ok(json!(diag::enabled(choice, server_default))),
        Action::Read => Ok(json!(kept(&path)?.text().map_err(invalid)?)),
        Action::Checkpoint { about, observations, clear } => {
            let mut record = if clear { Record::default() } else { kept(&path).map(|r| r.record).unwrap_or_default() };
            for observation in observations {
                match observation.line {
                    ObservationKind::Attempt { reach, local, permission, network, failed } => record.note_attempt(observation.at, diag::Trace {
                        reach, local_address: local, local_permission: permission, network,
                    }, failed),
                    kind => if let Some(line) = kind.line() { record.note(observation.at, line); },
                }
            }
            let text = Report { about: about.into(), record }.text().map_err(invalid)?;
            private_directory(&request.root)?;
            replace(&path, text.as_bytes())?;
            Ok(json!(text))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::TestRoot;

    #[test]
    fn the_record_accepts_closed_attempts_and_rejects_free_text() {
        let root = TestRoot::new();
        let secret = "a song 𐐀 private@example.invalid /hidden";
        let action = json!({ "root": root.0, "checkpoint": {
            "about": {
                "version": "0.6.3", "android_major": 17, "signed_in": true, "offline": false,
                "https": true, "local_permission": false, "android_auto": true,
                "loss": { "line_full": 2, "files_full": 3, "refused": 4 },
            },
            "clear": false,
            "observations": [
                { "at": 1_800_000_000_001u64, "line": { "note": { "label": secret } } },
                { "at": 1_800_000_000_001u64, "line": { "attempt": {
                    "reach": "secure", "local": true, "permission": false, "network": "wifi", "failed": true,
                } } },
                { "at": 1_800_000_000_001u64, "line": { "session_end": "expired" } },
            ],
        } });
        let written = call(serde_json::from_value(action).unwrap()).unwrap();
        let reopened = call(serde_json::from_value(json!({ "root": root.0, "read": null })).unwrap()).unwrap();
        assert_eq!(written, reopened);
        let text = reopened.as_str().unwrap();
        assert!(text.contains("Nexus last failure: made secure, local address yes, local permission no, network Wi-Fi"));
        assert!(text.contains("Nexus session ended: expired"));
        assert!(!text.contains(secret));
        assert!(!text.contains("example.invalid"));
    }
}
