use crate::{call_private, JByteArray, JClass, JNIEnv, jbyteArray};
use nexus_core::nexus::proto::Version;
use serde_json::Value;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::Path,
};

mod diagnostics;
mod journal;

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusJournal(
    env: JNIEnv, _: JClass, input: JByteArray,
) -> jbyteArray {
    call_private(env, input, |request| journal::call(request).map_err(|_| "Listening journal unavailable".into()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_medianexus_music_core_Core_nexusDiagnostics(
    env: JNIEnv, _: JClass, input: JByteArray,
) -> jbyteArray {
    call_private(env, input, |request| diagnostics::call(request).map_err(|_| "Diagnostics unavailable".into()))
}

fn invalid(_: impl std::any::Any) -> io::Error {
    io::ErrorKind::InvalidData.into()
}

fn version(value: &str) -> Version {
    Version::parse(value.split('-').next().unwrap_or_default())
        .unwrap_or_else(|| Version::parse("0.0.0").unwrap())
}

fn read(path: &Path, limit: usize) -> io::Result<Vec<u8>> {
    let mut data = Vec::new();
    File::open(path)?.take((limit + 1) as u64).read_to_end(&mut data)?;
    if data.len() > limit {
        return Err(invalid(()));
    }
    Ok(data)
}

fn private_directory(path: &Path) -> io::Result<()> {
    if path.is_dir() { return Ok(()); }
    let parent = path.parent().ok_or_else(|| invalid(()))?;
    private_directory(parent)?;
    match fs::create_dir(path) {
        Ok(()) => File::open(parent)?.sync_all(),
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists && path.is_dir() => Ok(()),
        Err(error) => Err(error),
    }
}

fn replace(path: &Path, data: &[u8]) -> io::Result<()> {
    let tmp = path.with_extension("next");
    let mut file = OpenOptions::new().write(true).create(true).truncate(true).open(&tmp)?;
    file.write_all(data)?;
    file.sync_all()?;
    fs::rename(tmp, path)?;
    File::open(path.parent().ok_or_else(|| invalid(()))?)?.sync_all()
}

fn to_value(value: impl serde::Serialize) -> io::Result<Value> {
    serde_json::to_value(value).map_err(invalid)
}

#[cfg(test)]
struct TestRoot(std::path::PathBuf);

#[cfg(test)]
impl TestRoot {
    fn new() -> Self {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static SEQUENCE: AtomicUsize = AtomicUsize::new(0);
        let name = format!("Nexus-recording-{}-{}", std::process::id(), SEQUENCE.fetch_add(1, Ordering::Relaxed));
        let path = std::env::temp_dir().join(name);
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

#[cfg(test)]
impl Drop for TestRoot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
