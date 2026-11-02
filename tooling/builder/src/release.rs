use crate::catalog::{
    Entry, MAX_SERIES_MEMBERS, is_mostly_japanese, is_mostly_latin, series_key, title_key, tokenize,
};
use crate::dumps::Book;
use crate::tags;
use crate::{Built, LANGUAGES};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::Path;

pub use crate::reader::PACKS;
pub const CORE: u8 = 0;
pub type Merged = [bool; PACKS.len()];

const MERGE_BELOW_BYTES: usize = 1_000_000;
const MIN_SERIES_MEMBERS: usize = 2;
const MIN_SERIES_NAME: usize = 4;
const MAX_SERIES_NAME: usize = 80;

const VOLUME_MARKERS: &[&str] = &[
    "no", "nos", "v", "vol", "volume", "bk", "book", "part", "pt", "band", "tome", "issue",
];

const SERIES_MARKERS: &[&str] = &[
    "seria", "seeria", "nivel", "level", "serie", "série", "bk", "bk.", "vol", "vol.",
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
    let all_markers = markers
        .iter()
        .all(|token| VOLUME_MARKERS.contains(&token.as_str()) || roman_value(token).is_some());
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
        Some(cut) if cut > 0 && position_of(&name[cut..]).is_some() => name[..cut].trim_end(),
        _ => name,
    }
}

fn strip_marked_number(name: &str) -> &str {
    let tail = name
        .rsplit_once(' ')
        .filter(|(_, number)| number.parse::<u16>().is_ok());
    let marked = tail.and_then(|(rest, _)| rest.rsplit_once(' '));
    match marked {
        Some((head, marker)) if SERIES_MARKERS.contains(&marker.to_lowercase().as_str()) => head,
        _ => name,
    }
}

fn without_dashes(name: &str) -> &str {
    match name.split_once("--") {
        Some((head, tail)) if tail.trim().is_empty() || position_of(tail).is_some() => head,
        _ => name,
    }
}

fn without_open_bracket(mut name: &str) -> &str {
    while name.matches('(').count() > name.matches(')').count() {
        name = name[..name.rfind('(').unwrap_or(0)].trim_end();
    }
    name
}

fn clean_series_name(raw: &str) -> Option<String> {
    let name = tidy(without_dashes(tidy(raw)));
    let name = tidy(strip_marked_number(strip_volume_suffix(
        without_open_bracket(name),
    )));
    let name = name.strip_suffix("-sarja").unwrap_or(name);
    let name = name.trim_end_matches('-').trim_end();
    let lowered = name.to_ascii_lowercase();
    let plain = (MIN_SERIES_NAME..=MAX_SERIES_NAME).contains(&name.len())
        && (is_mostly_latin(name) || is_mostly_japanese(name))
        && name.chars().any(char::is_alphabetic)
        && position_of(name).is_none()
        && !name.contains(" / ")
        && !lowered.contains("bestseller")
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
const ALIAS_MAGIC: &[u8; 4] = b"ALIA";
pub const RELEASE_URL: &str = "https://github.com/tn3w/Shelf/releases/download";

#[derive(Clone, Copy)]
pub struct Tracked {
    pub work: u32,
    pub pack: u8,
    pub hash: u64,
}

pub struct State {
    pub base: String,
    pub works: Vec<Tracked>,
    pub aliases: Vec<(u32, u32)>,
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset + 4)?.try_into().ok()?,
    ))
}

fn aliases(trailer: &[u8]) -> Vec<(u32, u32)> {
    let Some(body) = trailer.strip_prefix(ALIAS_MAGIC) else {
        return Vec::new();
    };
    let count = u32_at(body, 0).unwrap_or(0) as usize;
    let at = |index: usize, offset: usize| u32_at(body, offset + 8 * index);
    let pair = |index: usize| at(index, 4).zip(at(index, 8));
    (0..count).map_while(pair).collect()
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
        let end = header + count * RECORD_BYTES;
        let records = bytes.get(header..end)?;
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
        Some(State {
            base,
            works,
            aliases: aliases(&bytes[end..]),
        })
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
        if !self.aliases.is_empty() {
            bytes.extend_from_slice(ALIAS_MAGIC);
            bytes.extend_from_slice(&(self.aliases.len() as u32).to_le_bytes());
            for &(from, to) in &self.aliases {
                bytes.extend_from_slice(&from.to_le_bytes());
                bytes.extend_from_slice(&to.to_le_bytes());
            }
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

fn reading_order(chosen: &[Chosen], mut members: Vec<usize>) -> (Vec<usize>, bool) {
    let mut positions: Vec<u16> = members
        .iter()
        .map(|&index| chosen[index].entry.series_position)
        .collect();
    positions.sort_unstable();
    positions.dedup();
    let numbered = positions.len() == members.len() && !positions.contains(&0);
    let volume = |index: usize| chosen[index].entry.series_position;
    let year = |index: usize| chosen[index].book.year;
    let anchor = |index: usize| {
        let earlier = members
            .iter()
            .filter(|&&other| volume(other) > 0 && year(other) <= year(index));
        earlier.map(|&other| volume(other)).max().unwrap_or(0)
    };
    let mut keyed: Vec<((u16, bool, u16), usize)> = members
        .iter()
        .map(|&index| match volume(index) {
            0 => ((anchor(index), true, year(index)), index),
            known => ((known, false, year(index)), index),
        })
        .collect();
    keyed.sort_unstable();
    members = keyed.into_iter().map(|(_, index)| index).collect();
    (members, numbered)
}

fn is_single_title(chosen: &[Chosen], members: &[usize], name: &str) -> bool {
    let key = series_key(name);
    members
        .iter()
        .all(|&index| title_key(&chosen[index].entry.title, &[]) == key)
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
        let numbered = |index: &usize| chosen[*index].entry.series_position > 0;
        if kept.iter().filter(|index| numbered(index)).count() * 2 > kept.len() {
            kept.retain(numbered);
        }
        if kept.len() < MIN_SERIES_MEMBERS {
            continue;
        }
        let (mut ordered, numbered) = reading_order(chosen, kept);
        ordered.truncate(MAX_SERIES_MEMBERS);
        let mut spellings: Vec<&str> = ordered
            .iter()
            .map(|&index| chosen[index].entry.series.as_str())
            .collect();
        spellings.sort_unstable();
        let name = spellings
            .chunk_by(|a, b| a == b)
            .max_by_key(|same| same.len())
            .expect("members")[0];
        if !numbered && is_single_title(chosen, &ordered, name) {
            continue;
        }
        let members = ordered
            .iter()
            .map(|&index| chosen[index].book.work)
            .collect();
        series.push(Series {
            name: name.to_string(),
            members,
        });
        let volumes: Vec<u16> = ordered
            .iter()
            .map(|&index| chosen[index].entry.series_position)
            .filter(|&volume| volume > 0)
            .collect();
        let distinct = volumes
            .iter()
            .all(|volume| volumes.iter().filter(|other| *other == volume).count() == 1);
        for (order, &index) in ordered.iter().enumerate() {
            let volume = chosen[index].entry.series_position;
            let known = distinct && !volumes.is_empty();
            let order = if known { volume } else { order as u16 + 1 };
            chosen[index].series = Some((series.len() - 1, order));
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

fn manifest_entry(output: &Path, published: &Published, base_url: &str) -> Value {
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
        "url": format!("{base_url}/catalogue-{}/{}", published.month, published.file),
    })
}

fn is_kept(entry: &Value, rebased: &Built, built: &Built) -> bool {
    let language = entry["language"].as_str().unwrap_or_default();
    let Some(index) = LANGUAGES.iter().position(|&known| known == language) else {
        return false;
    };
    built[index] && !rebased[index] && entry["pack"] != "ranks"
}

pub fn write_manifest(
    previous: Option<&Path>,
    output: &Path,
    month: &str,
    rebased: &Built,
    published: &[Published],
    base_url: &str,
    built: &Built,
) {
    let previous: Option<Value> = previous
        .and_then(|directory| std::fs::read(directory.join("manifest.json")).ok())
        .map(|bytes| serde_json::from_slice(&bytes).expect("parse previous manifest"));
    let kept = previous
        .as_ref()
        .and_then(|manifest| manifest["segments"].as_array())
        .into_iter()
        .flatten()
        .filter(|entry| is_kept(entry, rebased, built))
        .cloned();
    let fresh = published
        .iter()
        .map(|entry| manifest_entry(output, entry, base_url));
    let segments: Vec<Value> = kept.chain(fresh).collect();
    let manifest = json!({
        "format": crate::segment::VERSION,
        "month": month,
        "segments": segments,
    });
    let text = serde_json::to_string_pretty(&manifest).expect("serialize manifest");
    std::fs::write(output.join("manifest.json"), text + "\n").expect("write manifest");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unconfirmed_members_left_out_of_numbered_series() {
        let entry = |series_position| {
            let mut entry = Entry::default();
            entry.series = "Discworld".to_string();
            entry.series_position = series_position;
            entry
        };
        let book = |work| Book {
            work,
            authors: vec![7],
            ..Book::default()
        };
        let entries = [entry(1), entry(2), entry(0)];
        let books = [book(1), book(2), book(3)];
        let mut chosen: Vec<Chosen> = entries
            .iter()
            .zip(&books)
            .map(|(entry, book)| Chosen {
                entry,
                book,
                pack: CORE,
                series: None,
            })
            .collect();
        let series = group_series(&mut chosen);
        assert_eq!(series[0].members, [1, 2]);
        assert!(chosen[2].series.is_none());
    }

    #[test]
    fn unnumbered_members_keep_numbered_order() {
        let entry = |series_position| {
            let mut entry = Entry::default();
            entry.series_position = series_position;
            entry
        };
        let book = |year| Book {
            year,
            ..Book::default()
        };
        let entries = [entry(1), entry(2), entry(0), entry(3)];
        let books = [book(1990), book(1991), book(1991), book(1992)];
        let chosen: Vec<Chosen> = entries
            .iter()
            .zip(&books)
            .map(|(entry, book)| Chosen {
                entry,
                book,
                pack: CORE,
                series: None,
            })
            .collect();
        let (ordered, numbered) = reading_order(&chosen, vec![3, 2, 1, 0]);
        assert_eq!(ordered, [0, 1, 2, 3]);
        assert!(!numbered);
    }

    fn saved(state: &State, name: &str) -> (State, Vec<u8>) {
        let file = format!("{name}-{}.bin", std::process::id());
        let path = std::env::temp_dir().join(file);
        state.save(&path);
        let bytes = std::fs::read(&path).expect("read state");
        let loaded = State::load(&path).expect("load state");
        std::fs::remove_file(&path).expect("remove state");
        (loaded, bytes)
    }

    #[test]
    fn aliases_survive_state_round_trip() {
        let works = vec![Tracked {
            work: 5,
            pack: 1,
            hash: 9,
        }];
        let plain = State {
            base: "base".to_string(),
            works: works.clone(),
            aliases: Vec::new(),
        };
        let aliased = State {
            base: "base".to_string(),
            works,
            aliases: vec![(1_000_000_001, 5)],
        };
        let (loaded, plain_bytes) = saved(&plain, "plain");
        assert!(loaded.aliases.is_empty());
        let (loaded, aliased_bytes) = saved(&aliased, "aliased");
        assert_eq!(loaded.aliases, vec![(1_000_000_001, 5)]);
        assert_eq!(loaded.works[0].work, 5);
        assert!(aliased_bytes.starts_with(&plain_bytes));
        assert_eq!(aliased_bytes.len(), plain_bytes.len() + 4 + 4 + 8);
    }

    #[test]
    fn series_names_cleaned() {
        for (raw, expected) in [
            ("La casa del árbol --", Some("La casa del árbol")),
            (
                "Elige tu propia aventura -- 2",
                Some("Elige tu propia aventura"),
            ),
            ("Oxford reading tree -- bk.5", Some("Oxford reading tree")),
            ("Pisma zebrane. Seria 2", Some("Pisma zebrane")),
            ("The Black Jewels (", Some("The Black Jewels")),
            ("Teosed / Rudolf Sirge", None),
            ("AnWel Trilogie", Some("AnWel Trilogie")),
            ("Harry Potter -sarja", Some("Harry Potter")),
            ("Harry Potter-sarja", Some("Harry Potter")),
            ("New York Times'i bestseller nr. 1", None),
        ] {
            let name = clean_series_name(raw);
            assert_eq!(name.as_deref(), expected, "{raw}");
        }
    }
}
