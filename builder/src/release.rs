use crate::LANGUAGES;
use crate::catalog::{Entry, MAX_SERIES_MEMBERS, is_mostly_latin, series_key, tokenize};
use crate::dumps::Book;
use crate::tags;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;

pub const PACKS: [&str; 9] = [
    "core",
    "fantasy",
    "scifi",
    "mystery",
    "romance",
    "kids",
    "young-adult",
    "nonfiction",
    "general",
];
pub const CORE: u8 = 0;
pub type Merged = [bool; PACKS.len()];

const MERGE_BELOW_BYTES: usize = 1_000_000;
const MIN_SERIES_MEMBERS: usize = 2;
const MIN_SERIES_NAME: usize = 4;
const MAX_SERIES_NAME: usize = 80;

const VOLUME_MARKERS: &[&str] = &[
    "no", "nos", "v", "vol", "volume", "bk", "book", "part", "pt", "band", "tome",
    "issue",
];

const GENERIC_SERIES: &[&str] = &[
    "a novel",
    "novel",
    "fiction",
    "classics",
    "the classics",
    "classic",
    "collection",
    "the collection",
    "collected works",
    "library",
    "the library",
    "series",
    "the series",
    "new edition",
    "omnibus",
    "boxed set",
    "box set",
    "anthology",
    "reader",
    "readers",
    "picture books",
    "chapter books",
    "graphic novels",
    "large print",
    "audiobook",
];

fn roman_value(token: &str) -> Option<u32> {
    let digits: Vec<i64> = token
        .chars()
        .map(|character| match character {
            'i' => Some(1),
            'v' => Some(5),
            'x' => Some(10),
            'l' => Some(50),
            'c' => Some(100),
            _ => None,
        })
        .collect::<Option<_>>()?;
    let total: i64 = digits
        .iter()
        .enumerate()
        .map(|(slot, &value)| {
            let subtracted = digits[slot + 1..].iter().any(|&later| later > value);
            if subtracted { -value } else { value }
        })
        .sum();
    u32::try_from(total).ok().filter(|&total| total > 0)
}

fn position_of(part: &str) -> Option<u16> {
    let tokens = tokenize(part);
    let (number, markers) = tokens.split_last()?;
    let all_markers = markers.iter().all(|token| {
        VOLUME_MARKERS.contains(&token.as_str()) || roman_value(token).is_some()
    });
    if !all_markers {
        return None;
    }
    let value = number.parse().ok().or_else(|| roman_value(number))?;
    u16::try_from(value)
        .ok()
        .filter(|&value| value > 0 && value < 500)
}

fn tidy(text: &str) -> &str {
    text.trim().trim_end_matches([';', ',', '.', ':']).trim()
}

fn strip_volume_suffix(name: &str) -> &str {
    match name.rfind([',', '#']) {
        Some(cut) if cut > 0 && position_of(&name[cut..]).is_some() => {
            name[..cut].trim_end()
        }
        _ => name,
    }
}

fn clean_series_name(raw: &str) -> Option<String> {
    let name = tidy(strip_volume_suffix(tidy(raw)));
    let lowered = name.to_ascii_lowercase();
    let plain = (MIN_SERIES_NAME..=MAX_SERIES_NAME).contains(&name.len())
        && is_mostly_latin(name)
        && name.chars().any(char::is_alphabetic)
        && position_of(name).is_none()
        && !GENERIC_SERIES.contains(&lowered.as_str());
    plain.then(|| name.to_string())
}

pub fn parse_series(parts: &[&str]) -> Option<(String, u16)> {
    let first = tidy(parts.first()?);
    let name = clean_series_name(first)?;
    let tail = first[name.len()..].trim_start_matches([',', '#', ' ']);
    let later = parts[1..].iter().find_map(|part| position_of(part));
    Some((name, later.or_else(|| position_of(tail)).unwrap_or(0)))
}
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

pub fn previous_merged(previous: &State) -> Merged {
    let mut merged = [true; PACKS.len()];
    merged[CORE as usize] = false;
    for tracked in &previous.works {
        merged[tracked.pack as usize] = false;
    }
    merged
}

pub fn merge_small(merged: &mut Merged, sizes: &[usize]) {
    for (pack, &size) in sizes.iter().enumerate().skip(1) {
        merged[pack] |= size < MERGE_BELOW_BYTES;
    }
}

fn primary_pack(tags: &[u8]) -> u8 {
    let pack = tags::pack_slug(tags);
    PACKS
        .iter()
        .position(|&known| known == pack)
        .expect("known pack") as u8
}

pub struct Series {
    pub name: String,
    pub members: Vec<u32>,
}

pub struct Chosen<'a> {
    pub entry: &'a Entry,
    pub book: &'a Book,
    pub pack: u8,
    pub series: Option<(usize, u16)>,
}

pub struct Selection<'a> {
    pub chosen: Vec<Chosen<'a>>,
    pub series: Vec<Series>,
}

pub fn select<'a>(
    entries: &'a [Entry],
    books: &'a [Book],
    limit: usize,
    core_limit: usize,
    merged: &Merged,
) -> Selection<'a> {
    let mut fresh = 0;
    let mut chosen: Vec<Chosen> = Vec::new();
    for entry in entries {
        let book = &books[entry.book];
        let pack = match entry.sticky {
            Some(pack) => pack,
            None if fresh >= limit => continue,
            None if fresh < core_limit => CORE,
            None => match primary_pack(&book.tags) {
                pack if merged[pack as usize] => CORE,
                pack => pack,
            },
        };
        fresh += usize::from(entry.sticky.is_none());
        chosen.push(Chosen {
            entry,
            book,
            pack,
            series: None,
        });
    }
    let series = group_series(&mut chosen);
    Selection { chosen, series }
}

fn dominant_author(chosen: &[Chosen], members: &[usize]) -> Option<u32> {
    let mut authors: Vec<u32> = members
        .iter()
        .filter_map(|&index| chosen[index].book.authors.first().copied())
        .collect();
    authors.sort_unstable();
    let largest = authors
        .chunk_by(|a, b| a == b)
        .max_by_key(|same| same.len())?;
    (largest.len() * 2 > members.len()).then_some(largest[0])
}

fn reading_order(chosen: &[Chosen], mut members: Vec<usize>) -> Vec<usize> {
    let mut positions: Vec<u16> = members
        .iter()
        .map(|&index| chosen[index].entry.series_position)
        .collect();
    positions.sort_unstable();
    positions.dedup();
    let numbered = positions.len() == members.len() && !positions.contains(&0);
    members.sort_by_key(|&index| {
        let (entry, book) = (chosen[index].entry, chosen[index].book);
        let lead = if numbered {
            entry.series_position
        } else {
            book.year
        };
        (lead, index)
    });
    members
}

fn group_series(chosen: &mut [Chosen]) -> Vec<Series> {
    let mut named: Vec<(String, usize)> = chosen
        .iter()
        .enumerate()
        .filter(|(_, item)| !item.entry.series.is_empty())
        .map(|(index, item)| (series_key(&item.entry.series), index))
        .collect();
    named.sort_unstable();
    let mut series = Vec::new();
    for group in named.chunk_by(|a, b| a.0 == b.0) {
        let members: Vec<usize> = group.iter().map(|&(_, index)| index).collect();
        let Some(author) = dominant_author(chosen, &members) else {
            continue;
        };
        let mut kept: Vec<usize> = members
            .into_iter()
            .filter(|&index| chosen[index].book.authors.first() == Some(&author))
            .collect();
        kept.truncate(MAX_SERIES_MEMBERS);
        if kept.len() < MIN_SERIES_MEMBERS {
            continue;
        }
        let ordered = reading_order(chosen, kept);
        let mut spellings: Vec<&str> = ordered
            .iter()
            .map(|&index| chosen[index].entry.series.as_str())
            .collect();
        spellings.sort_unstable();
        let name = spellings
            .chunk_by(|a, b| a == b)
            .max_by_key(|same| same.len())
            .expect("members")[0];
        let members = ordered
            .iter()
            .map(|&index| chosen[index].book.work)
            .collect();
        series.push(Series {
            name: name.to_string(),
            members,
        });
        for (order, &index) in ordered.iter().enumerate() {
            chosen[index].series = Some((series.len() - 1, order as u16 + 1));
        }
    }
    series
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
