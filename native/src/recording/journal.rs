use super::{invalid, read, replace, to_value, version};
use nexus_core::nexus::{
    account::DeviceKind,
    events::{self, EventId, Kind, Loss, LossCounts, Outcome, Placed, Record},
    proto::BackendId,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    cell::RefCell,
    collections::{BTreeMap, HashMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
struct Account {
    backend_id: String,
    account: u64,
}

impl Account {
    fn key(&self) -> io::Result<String> {
        if BackendId::from_hex(&self.backend_id).is_none() || self.account == 0 {
            return Err(invalid(()));
        }
        Ok(format!("{}/{}", self.backend_id, self.account))
    }
}

#[derive(Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
struct Scope {
    account: Account,
    life: u64,
}

#[derive(Deserialize)]
pub(super) struct Request {
    root: PathBuf,
    #[serde(flatten)]
    action: Action,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Action {
    Activate { scope: Scope, now: u64 },
    Append { records: Vec<Capture>, lost: u64, active: Option<Scope>, now: u64 },
    Change { effect: Change, active: Option<Scope>, now: u64 },
    Read { account: Account, life: u64, from_sequence: u64, max_events: usize, max_bytes: usize, through: u64 },
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Change {
    Taken { account: Account, life: u64, ids: Vec<String>, at_ms: u64 },
    Refused { account: Account, life: u64, ids: Vec<String> },
    LifeMoved { account: Account, life: u64 },
    ResendAll { backend_id: String },
}

#[derive(Deserialize)]
struct Capture {
    scope: Scope,
    id: String,
    at: u64,
    data: Observation,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Observation {
    Open { version: String },
    Start {
        song_id: String, title: String, artist: String, album: String,
        from: events::PlayedFrom, began: events::Began, sound: events::Sound, position_ms: u32,
    },
    Stop {
        start: String, position_ms: u32, duration_ms: Option<u32>, heard_ms: u32, why: events::Stopped,
    },
    Pause { start: String, position_ms: u32 },
    Resume { start: String, position_ms: u32 },
    Seek { start: String, from_ms: u32, to_ms: u32 },
    Search { words: String, found: u32, requests: bool },
    Fault { fault: events::Fault },
    Position { start: String, position_ms: u32 },
}

impl Observation {
    fn kind(self) -> io::Result<Kind> {
        Ok(match self {
            Self::Open { version: app } => Kind::Open { version: version(&app), device: DeviceKind::Phone },
            Self::Start { song_id, title, artist, album, from, began, sound, position_ms } =>
                Kind::Start { song_id, title, artist, album, from, began, sound, position_ms },
            Self::Stop { start, position_ms, duration_ms, heard_ms, why } =>
                Kind::Stop { start: id(&start)?, position_ms: Some(position_ms), duration_ms, heard_ms: Some(heard_ms), why },
            Self::Pause { start, position_ms } => Kind::Pause { start: id(&start)?, position_ms },
            Self::Resume { start, position_ms } => Kind::Resume { start: id(&start)?, position_ms },
            Self::Seek { start, from_ms, to_ms } => Kind::Seek { start: id(&start)?, from_ms, to_ms },
            Self::Search { words, found, requests } => Kind::Search {
                words, found, where_: if requests { events::SearchedIn::Requests } else { events::SearchedIn::Library },
            },
            Self::Fault { fault } => Kind::Fault { fault, count: 1 },
            Self::Position { .. } => return Err(invalid(())),
        })
    }
}

fn id(text: &str) -> io::Result<EventId> {
    EventId::from_hex(text).ok_or_else(|| invalid(()))
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default)]
struct State {
    reserved: u64,
    loss: LossCounts,
    accounts: BTreeMap<String, AccountState>,
    repairs: Vec<Repair>,
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default)]
struct AccountState {
    life: u64,
    settled: BTreeMap<u64, Settled>,
    position: Option<(String, u32)>,
}

#[derive(Clone, Serialize, Deserialize)]
struct Settled {
    outcome: Outcome,
    at: u64,
}

#[derive(Clone, Serialize, Deserialize)]
struct Repair {
    scope: Scope,
    first: u64,
    remove: bool,
}

#[derive(Default)]
struct Catalog {
    segments: BTreeMap<u64, Segment>,
}

struct Segment {
    bytes: usize,
    events: Vec<Placed>,
}

impl Scope {
    fn folder(&self, root: &Path) -> io::Result<PathBuf> {
        Ok(root.join(self.account.key()?).join(self.life.to_string()))
    }
    fn segment(&self, root: &Path, first: u64) -> io::Result<PathBuf> {
        Ok(self.folder(root)?.join(format!("{first}.events")))
    }
}

const STATE_BYTES: usize = 64 * 1024 * 1024;
fn load(root: &Path) -> io::Result<State> {
    match read(&root.join("state.json"), STATE_BYTES) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(invalid),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(State::default()),
        Err(e) => Err(e),
    }
}

fn segment_numbers(folder: &Path) -> io::Result<Vec<u64>> {
    let entries = match fs::read_dir(folder) {
        Ok(entries) => entries,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    let mut numbers = Vec::new();
    for entry in entries {
        let path = entry?.path();
        if path.extension().is_some_and(|ext| ext == "events")
            && let Some(number) = path.file_stem().and_then(|n| n.to_str()).and_then(|n| n.parse().ok())
        {
            numbers.push(number);
        }
    }
    numbers.sort_unstable();
    Ok(numbers)
}

fn event_file(path: &Path) -> io::Result<(events::JournalFile, usize)> {
    let bytes = read(path, events::FILE_BYTES)?;
    Ok((events::read_file(&bytes).map_err(invalid)?, bytes.len()))
}

struct Writer {
    root: PathBuf,
    state: State,
    through: u64,
    catalogs: HashMap<Scope, Catalog>,
    uncertain: HashSet<PathBuf>,
    recovered: HashSet<Scope>,
    unwritten: u64,
}

impl Writer {
    fn open(root: PathBuf) -> io::Result<Self> {
        fs::create_dir_all(&root)?;
        let state = load(&root)?;
        let through = state.reserved;
        let mut writer = Self { root, state, through, catalogs: HashMap::new(), uncertain: HashSet::new(), recovered: HashSet::new(), unwritten: 0 };
        writer.repair()?;
        Ok(writer)
    }

    fn commit(&mut self, state: State) -> io::Result<()> {
        let bytes = serde_json::to_vec(&state).map_err(invalid)?;
        if bytes.len() > STATE_BYTES {
            return Err(invalid(()));
        }
        replace(&self.root.join("state.json"), &bytes)?;
        self.state = state;
        Ok(())
    }

    fn repair(&mut self) -> io::Result<()> {
        if self.state.repairs.is_empty() {
            return Ok(());
        }
        for repair in &self.state.repairs {
            let path = repair.scope.segment(&self.root, repair.first)?;
            if repair.remove {
                match fs::remove_file(&path) {
                    Ok(()) => File::open(path.parent().ok_or_else(|| invalid(()))?)?.sync_all()?,
                    Err(e) if e.kind() == io::ErrorKind::NotFound => (),
                    Err(e) => return Err(e),
                }
            } else {
                let (file, _) = event_file(&path)?;
                if file.skipped_lines > 0 {
                    let mut bytes = events::file_header(file.life).map_err(invalid)?;
                    for event in file.events {
                        bytes.extend(event.line().map_err(invalid)?);
                    }
                    replace(&path, &bytes)?;
                }
            }
            self.catalogs.remove(&repair.scope);
        }
        let mut state = self.state.clone();
        state.repairs.clear();
        self.commit(state)
    }

    fn set_life(&mut self, scope: &Scope) -> io::Result<bool> {
        let key = scope.account.key()?;
        let prior = self.state.accounts.get(&key).map(|a| a.life);
        if prior.is_some_and(|life| life > scope.life) {
            return Ok(false);
        }
        if prior != Some(scope.life) {
            let mut state = self.state.clone();
            state.accounts.insert(key.clone(), AccountState { life: scope.life, ..AccountState::default() });
            let folder = self.root.join(key);
            if folder.exists() {
                for dir in fs::read_dir(folder)? {
                    let dir = dir?;
                    let Some(life) = dir.file_name().to_str().and_then(|name| name.parse().ok()) else { continue };
                    if life == scope.life { continue; }
                    let older = Scope { account: scope.account.clone(), life };
                    for first in segment_numbers(&dir.path())? {
                        state.repairs.push(Repair { scope: older.clone(), first, remove: true });
                    }
                }
            }
            self.commit(state)?;
            self.repair()?;
        }
        Ok(true)
    }

    fn catalog(&mut self, scope: &Scope) -> io::Result<()> {
        self.repair()?;
        if self.catalogs.contains_key(scope) {
            return Ok(());
        }
        let mut catalog = Catalog::default();
        let mut state = self.state.clone();
        for first in segment_numbers(&scope.folder(&self.root)?)? {
            let path = scope.segment(&self.root, first)?;
            if self.uncertain.contains(&path) {
                File::open(&path)?.sync_all()?;
                self.uncertain.remove(&path);
            }
            match event_file(&path) {
                Ok((file, bytes)) if file.life == scope.life => {
                    if file.skipped_lines != 0 {
                        state.loss.add(Loss::LineFull, file.skipped_lines as u64);
                        state.repairs.push(Repair { scope: scope.clone(), first, remove: false });
                    }
                    catalog.segments.insert(first, Segment { bytes, events: file.events });
                }
                Ok(_) => return Err(invalid(())),
                Err(error) if error.kind() == io::ErrorKind::InvalidData => {
                    // A crash before the first header's newline left no committed line.
                    state.loss.add(Loss::LineFull, 1);
                    state.repairs.push(Repair { scope: scope.clone(), first, remove: true });
                }
                Err(error) => return Err(error),
            }
        }
        if !state.repairs.is_empty() {
            self.commit(state)?;
            self.repair()?;
            return self.catalog(scope);
        }
        self.catalogs.insert(scope.clone(), catalog);
        Ok(())
    }

    fn append(&mut self, scope: &Scope, record: Record) -> io::Result<()> {
        self.catalog(scope)?;
        let sequence = events::next_sequence(self.through).ok_or_else(|| invalid(()))?;
        if sequence > self.state.reserved {
            let mut state = self.state.clone();
            state.reserved = sequence.saturating_add(63);
            self.commit(state)?;
        }
        self.through = sequence;
        let event = record.place(sequence).map_err(invalid)?;
        let catalog = self.catalogs.get_mut(scope).unwrap();
        let last = catalog.segments.last_key_value();
        let tail = last.map(|(_, tail)| events::FileTail { life: scope.life, bytes: tail.bytes, last_byte: b'\n' });
        let append = events::append(scope.life, tail, &event).map_err(invalid)?;
        let first = if append.new_file { sequence } else { *last.unwrap().0 };
        let path = scope.segment(&self.root, first)?;
        let old_len = if append.new_file { 0 } else { last.unwrap().1.bytes };
        let result = (|| {
            if append.new_file {
                fs::create_dir_all(path.parent().ok_or_else(|| invalid(()))?)?;
                // Publish a first segment atomically so a failed header never looks like an event.
                replace(&path, &append.bytes)
            } else {
                let mut file = OpenOptions::new().append(true).open(&path)?;
                file.write_all(&append.bytes)?;
                file.sync_all()
            }
        })();
        if let Err(error) = result {
            // If the kernel accepted the full line, a later scan decides whether it survived.
            // A partial line is also counted by that scan, once its repair intent is kept.
            if fs::metadata(&path).is_ok_and(|m| m.len() > old_len as u64) {
                self.catalogs.remove(scope);
                self.uncertain.insert(path);
                return Ok(());
            }
            return Err(error);
        }
        let segment = catalog.segments.entry(first).or_insert_with(|| Segment { bytes: 0, events: Vec::new() });
        segment.bytes += append.bytes.len();
        segment.events.push(event);
        Ok(())
    }

    fn activate(&mut self, scope: &Scope, now: u64) -> io::Result<bool> {
        if !self.set_life(scope)? { return Ok(false); }
        self.catalog(scope)?;
        if self.recovered.contains(scope) { return Ok(true); }
        let open = events::open_starts(self.catalogs[scope].segments.values().flat_map(|s| &s.events));
        let key = scope.account.key()?;
        let position = self.state.accounts[&key].position.as_ref().and_then(|(start, ms)| Some((id(start).ok()?, *ms)));
        // Recovery runs once per process/scope. New IDs are made on the writer thread.
        for start in open {
            let new_id = random_id().ok_or_else(|| invalid(()))?;
            let stop = events::end_open_start(start, position, new_id, now).map_err(invalid)?;
            self.append(scope, stop)?;
        }
        self.catalog(scope)?;
        self.recovered.insert(scope.clone());
        self.prune(scope, now)?;
        Ok(true)
    }

    fn append_batch(&mut self, records: Vec<Capture>, lost: u64, now: u64) -> u64 {
        let mut dirty = HashSet::new();
        let mut positions = BTreeMap::new();
        for capture in records {
            let scope = capture.scope;
            let position_only = matches!(&capture.data, Observation::Position { .. });
            let result = (|| {
                let key = scope.account.key()?;
                if self.state.accounts.get(&key).is_some_and(|a| a.life > scope.life) {
                    return Ok(()); // Deletion retires these deliberately.
                }
                if !self.activate(&scope, now)? { return Ok(()); }
                if let Observation::Position { start, position_ms } = capture.data {
                    positions.insert(key, (start, position_ms));
                    return Ok(());
                }
                let record = Record::new(id(&capture.id)?, capture.at, capture.data.kind()?).map_err(invalid)?;
                self.append(&scope, record)?;
                dirty.insert(scope.clone());
                Ok::<_, io::Error>(())
            })();
            if result.is_err() && !position_only {
                self.unwritten = self.unwritten.saturating_add(1);
            }
        }
        for scope in dirty {
            let _ = self.prune(&scope, now);
        }
        let mut state = self.state.clone();
        let counted = self.unwritten.saturating_add(lost);
        state.loss.add(Loss::LineFull, counted);
        let has_positions = !positions.is_empty();
        for (key, position) in positions {
            if let Some(account) = state.accounts.get_mut(&key) {
                account.position = Some(position);
            }
        }
        if (has_positions || counted != 0) && self.commit(state).is_ok() {
            self.unwritten = 0;
            return lost;
        }
        0
    }

    fn prune(&mut self, scope: &Scope, now: u64) -> io::Result<()> {
        self.catalog(scope)?;
        let key = scope.account.key()?;
        let account = &self.state.accounts[&key];
        let catalog = &self.catalogs[scope];
        let files: Vec<_> = catalog.segments.values().map(|segment| {
            let pending = segment.events.iter().filter(|e| waiting(account, e)).count() as u64;
            let final_at_ms = segment.events.iter().filter_map(|e| account.settled.get(&e.sequence()).map(|s| s.at)).max();
            events::KeptFile { life: scope.life, bytes: segment.bytes as u64, events: segment.events.len() as u64, pending, final_at_ms }
        }).collect();
        let plan = events::remove_files(&files, scope.life, now);
        if plan.remove.is_empty() { return Ok(()); }
        let mut state = self.state.clone();
        let account = state.accounts.get_mut(&key).unwrap();
        for index in plan.remove {
            let (first, segment) = catalog.segments.iter().nth(index).ok_or_else(|| invalid(()))?;
            for event in &segment.events {
                account.settled.remove(&event.sequence());
            }
            state.repairs.push(Repair { scope: scope.clone(), first: *first, remove: true });
        }
        state.loss.add(Loss::FilesFull, plan.dropped);
        self.commit(state)?;
        self.repair()
    }

    fn settle(&mut self, scope: &Scope, ids: Vec<String>, outcome: Outcome, at: u64, now: u64) -> io::Result<()> {
        let key = scope.account.key()?;
        if self.state.accounts.get(&key).map(|a| a.life) != Some(scope.life) { return Ok(()); }
        self.catalog(scope)?;
        let ids: HashSet<_> = ids.iter().filter_map(|one| EventId::from_hex(one)).collect();
        let mut state = self.state.clone();
        let account = state.accounts.get_mut(&key).unwrap();
        for event in self.catalogs[scope].segments.values().flat_map(|segment| &segment.events) {
            if ids.contains(&event.record().id()) && waiting(account, event) {
                account.settled.insert(event.sequence(), Settled { outcome, at });
                if outcome == Outcome::Refused { state.loss.add(Loss::Refused, 1); }
            }
        }
        self.commit(state)?;
        self.prune(scope, now)
    }

    fn change(&mut self, effect: Change, now: u64) -> io::Result<()> {
        self.repair()?;
        match effect {
            Change::Taken { account, life, ids, at_ms } => self.settle(&Scope { account, life }, ids, Outcome::Taken, at_ms, now),
            Change::Refused { account, life, ids } => self.settle(&Scope { account, life }, ids, Outcome::Refused, now, now),
            Change::LifeMoved { account, life } => {
                self.set_life(&Scope { account, life })?;
                Ok(())
            }
            Change::ResendAll { backend_id } => {
                if BackendId::from_hex(&backend_id).is_none() { return Err(invalid(())); }
                let mut state = self.state.clone();
                let prefix = format!("{backend_id}/");
                let mut scopes = Vec::new();
                for (key, account) in state.accounts.range_mut(prefix.clone()..).take_while(|(key, _)| key.starts_with(&prefix)) {
                    let number = key.strip_prefix(&prefix).and_then(|value| value.parse().ok()).ok_or_else(|| invalid(()))?;
                    account.settled.retain(|_, settled| !settled.outcome.should_send(true));
                    scopes.push(Scope { account: Account { backend_id: backend_id.clone(), account: number }, life: account.life });
                }
                self.commit(state)?;
                for scope in scopes {
                    self.prune(&scope, now)?;
                    self.catalogs.remove(&scope);
                }
                Ok(())
            }
        }
    }

    fn summary(&mut self, scope: Option<Scope>, counted: u64) -> Value {
        let waiting = scope.and_then(|scope| {
            let key = scope.account.key().ok()?;
            let account = self.state.accounts.get(&key)?;
            if account.life != scope.life { return None; }
            self.catalog(&scope).ok()?;
            let account = &self.state.accounts[&key];
            let mut events = self.catalogs[&scope].segments.values().flat_map(|s| &s.events).filter(|e| waiting(account, e));
            let first = events.next().map(|e| e.sequence());
            Some(json!({
                "account": scope.account, "life": scope.life,
                "pending": first.map_or(0, |_| 1 + events.count()),
                "from_sequence": first.unwrap_or(0)
            }))
        });
        let mut loss = self.state.loss;
        loss.add(Loss::LineFull, self.unwritten);
        json!({ "waiting": waiting, "through": self.through, "loss": loss, "counted": counted, "retry": self.unwritten > 0 || !self.uncertain.is_empty() })
    }
}

fn waiting(account: &AccountState, event: &Placed) -> bool {
    !account.settled.contains_key(&event.sequence())
}

fn random_id() -> Option<EventId> {
    use std::io::Read;
    let mut bytes = [0; 16];
    File::open("/dev/urandom").ok()?.read_exact(&mut bytes).ok()?;
    Some(EventId::from_bytes(bytes))
}

fn read_events(root: &Path, scope: &Scope, from: u64, limit: usize, bytes: usize, through: u64) -> io::Result<Value> {
    let state = load(root)?;
    let Some(account) = state.accounts.get(&scope.account.key()?).filter(|a| a.life == scope.life) else {
        return Ok(json!([]));
    };
    let mut batch = Vec::new();
    let mut used = 0;
    let numbers = segment_numbers(&scope.folder(root)?)?;
    for (index, &first) in numbers.iter().enumerate() {
        if state.repairs.iter().any(|repair| repair.remove && repair.scope == *scope && repair.first == first) { continue; }
        if numbers.get(index + 1).is_some_and(|&next| next <= from) { continue; }
        let (file, _) = match event_file(&scope.segment(root, first)?) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        if file.life != scope.life { return Err(invalid(())); }
        for event in file.events {
            if event.sequence() < from || event.sequence() > through || !waiting(account, &event) { continue; }
            used += event.line().map_err(invalid)?.len();
            if used > bytes.min(events::BATCH_BYTES) || batch.len() == limit.min(events::BATCH_EVENTS) {
                return to_value(batch);
            }
            batch.push(event);
        }
    }
    to_value(batch)
}

thread_local! { static WRITERS: RefCell<HashMap<PathBuf, Writer>> = RefCell::new(HashMap::new()); }

pub(super) fn call(request: Request) -> io::Result<Value> {
    let Request { root, action } = request;
    if let Action::Read { account, life, from_sequence, max_events, max_bytes, through } = action {
        return read_events(&root, &Scope { account, life }, from_sequence, max_events, max_bytes, through);
    }
    WRITERS.with_borrow_mut(|writers| {
        let writer = match writers.entry(root.clone()) {
            std::collections::hash_map::Entry::Occupied(entry) => entry.into_mut(),
            std::collections::hash_map::Entry::Vacant(entry) => entry.insert(Writer::open(root)?),
        };
        match action {
            Action::Activate { scope, now } => {
                writer.activate(&scope, now)?;
                Ok(writer.summary(Some(scope), 0))
            }
            Action::Append { records, lost, active, now } => {
                let counted = writer.append_batch(records, lost, now);
                Ok(writer.summary(active, counted))
            }
            Action::Change { effect, active, now } => {
                writer.change(effect, now)?;
                Ok(writer.summary(active, 0))
            }
            Action::Read { .. } => unreachable!(),
        }
    })
}
