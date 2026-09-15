use crate::LANGUAGES;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;

const STATE_MAGIC: &[u8; 4] = b"SHST";
const STATE_VERSION: u32 = 3;
const LEGACY_LABEL_BYTES: usize = 10;
const RECORD_BYTES: usize = 13;
const RELEASE_URL: &str = "https://github.com/tn3w/Shelf/releases/download";

#[derive(Clone, Copy)]
pub struct Tracked {
    pub work: u32,
    pub pack: u8,
    pub hash: u64,
}

pub struct State {
    pub base: String,
    pub works: Vec<Tracked>,
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset + 4)?.try_into().ok()?,
    ))
}

impl State {
    pub fn load(path: &Path) -> Option<State> {
        let bytes = std::fs::read(path).ok()?;
        if !bytes.starts_with(STATE_MAGIC) {
            panic!("{} is not a state file", path.display());
        }
        let (label_start, label_bytes) = match u32_at(&bytes, 4) {
            Some(2) => (8, LEGACY_LABEL_BYTES),
            Some(STATE_VERSION) => (9, *bytes.get(8)? as usize),
            _ => panic!("{} has an unknown state version", path.display()),
        };
        let label_end = label_start + label_bytes;
        let label = bytes.get(label_start..label_end)?;
        let base = String::from_utf8_lossy(label).into_owned();
        let count = u32_at(&bytes, label_end)? as usize;
        let header = label_end + 4;
        let records = bytes.get(header..header + count * RECORD_BYTES)?;
        let works = records
            .as_chunks::<RECORD_BYTES>()
            .0
            .iter()
            .map(|record| Tracked {
                work: u32::from_le_bytes(record[..4].try_into().expect("4 bytes")),
                pack: record[4],
                hash: u64::from_le_bytes(record[5..].try_into().expect("8 bytes")),
            })
            .collect();
        Some(State { base, works })
    }

    pub fn save(&self, path: &Path) {
        let mut bytes = Vec::with_capacity(32 + self.works.len() * RECORD_BYTES);
        bytes.extend_from_slice(STATE_MAGIC);
        bytes.extend_from_slice(&STATE_VERSION.to_le_bytes());
        bytes.push(self.base.len() as u8);
        bytes.extend_from_slice(self.base.as_bytes());
        bytes.extend_from_slice(&(self.works.len() as u32).to_le_bytes());
        for tracked in &self.works {
            bytes.extend_from_slice(&tracked.work.to_le_bytes());
            bytes.push(tracked.pack);
            bytes.extend_from_slice(&tracked.hash.to_le_bytes());
        }
        std::fs::write(path, bytes).expect("write state");
    }

    pub fn get(&self, work: u32) -> Option<&Tracked> {
        let index = self
            .works
            .binary_search_by_key(&work, |tracked| tracked.work)
            .ok()?;
        Some(&self.works[index])
    }

    pub fn pack_of(&self, work: u32) -> Option<u8> {
        self.get(work).map(|tracked| tracked.pack)
    }
}

pub struct Published {
    pub language: usize,
    pub pack: String,
    pub month: String,
    pub file: String,
}

fn manifest_entry(output: &Path, published: &Published) -> Value {
    let bytes = std::fs::read(output.join(&published.file)).expect("read published file");
    let sha256: String = Sha256::digest(&bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    json!({
        "id": published.file.trim_end_matches(".bin"),
        "language": LANGUAGES[published.language],
        "pack": published.pack,
        "month": published.month,
        "size": bytes.len(),
        "sha256": sha256,
        "url": format!("{RELEASE_URL}/catalogue-{}/{}", published.month, published.file),
    })
}

fn is_kept(entry: &Value, rebased: &[bool; 4]) -> bool {
    let language = entry["language"].as_str().unwrap_or_default();
    let Some(index) = LANGUAGES.iter().position(|&known| known == language) else {
        return false;
    };
    !rebased[index] && entry["pack"] != "ranks"
}

pub fn write_manifest(
    previous: Option<&Path>,
    output: &Path,
    month: &str,
    rebased: &[bool; 4],
    published: &[Published],
) {
    let previous: Option<Value> = previous
        .and_then(|directory| std::fs::read(directory.join("manifest.json")).ok())
        .map(|bytes| serde_json::from_slice(&bytes).expect("parse previous manifest"));
    let kept = previous
        .as_ref()
        .and_then(|manifest| manifest["segments"].as_array())
        .into_iter()
        .flatten()
        .filter(|entry| is_kept(entry, rebased))
        .cloned();
    let fresh = published.iter().map(|entry| manifest_entry(output, entry));
    let segments: Vec<Value> = kept.chain(fresh).collect();
    let manifest = json!({
        "format": crate::segment::VERSION,
        "month": month,
        "segments": segments,
    });
    let text = serde_json::to_string_pretty(&manifest).expect("serialize manifest");
    std::fs::write(output.join("manifest.json"), text + "\n").expect("write manifest");
}
