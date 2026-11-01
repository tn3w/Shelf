//! The cover index the builder publishes as `covers.bin` with every catalogue release.

use crate::images::USER_AGENT;
use flate2::read::DeflateDecoder;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::io::Read;
use std::time::Duration;

const RELEASES: &str = "https://api.github.com/repos/tn3w/Shelf/releases?per_page=100";

const MAGIC: &[u8; 4] = b"SHCV";
const VERSION: u32 = 1;
const NAME_BYTES: usize = 16;
const MAX_RELEASE: usize = 16;

pub const OPEN_LIBRARY: u8 = 0;
pub const DNB: u8 = 1;
pub const DIGAR: u8 = 2;
pub const ISBN_COVER: u8 = 3;
pub const BOOKBITES: u8 = 4;
const ISBN_BASE: u64 = 978_000_000_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cover {
    pub source: u8,
    pub key: u32,
}

#[derive(Clone, Copy)]
struct Entry {
    work: u32,
    key: u32,
    source: u8,
}

pub struct Index {
    pub release: String,
    languages: HashMap<String, Vec<Entry>>,
}

fn check_digit(twelve: &str) -> u32 {
    let sum: u32 = twelve
        .bytes()
        .enumerate()
        .map(|(index, digit)| u32::from(digit - b'0') * if index % 2 == 0 { 1 } else { 3 })
        .sum();
    (10 - sum % 10) % 10
}

/// The ISBN-13 an ISBN key stands for, as in the app's `isbnOf`.
pub fn isbn_of(key: u32) -> String {
    let twelve = (ISBN_BASE + u64::from(key)).to_string();
    let check = check_digit(&twelve);
    format!("{twelve}{check}")
}

impl Cover {
    /// The address the app itself would load, see `Book.coverUrl("L")`.
    pub fn url(self) -> Option<String> {
        let isbn = || isbn_of(self.key);
        Some(match self.source {
            OPEN_LIBRARY => format!("https://covers.openlibrary.org/b/id/{}-L.jpg", self.key),
            DNB => format!("https://portal.dnb.de/opac/mvb/cover?isbn={}", isbn()),
            DIGAR => format!(
                "https://www.digar.ee/arhiiv/nlib-digar:{}?thumb=1",
                self.key
            ),
            ISBN_COVER => {
                format!(
                    "https://covers.openlibrary.org/b/isbn/{}-L.jpg?default=false",
                    isbn()
                )
            }
            BOOKBITES => format!("https://bookbites-prod-se.covers.bookbites.com/{}", isbn()),
            _ => return None,
        })
    }
}

/// Author photo address as in the app's `Author.photoUrl("M")`.
pub fn author_url(number: u32) -> String {
    format!("https://covers.openlibrary.org/a/olid/OL{number}A-M.jpg?default=false")
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset + 4)?.try_into().ok()?,
    ))
}

fn varint(bytes: &[u8], cursor: &mut usize) -> Option<u32> {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = *bytes.get(*cursor)?;
        *cursor += 1;
        value |= u32::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Some(value);
        }
    }
    None
}

fn entries(section: &[u8]) -> Option<Vec<Entry>> {
    let mut raw = Vec::new();
    DeflateDecoder::new(section).read_to_end(&mut raw).ok()?;
    let (mut cursor, mut work, mut entries) = (0, 0u32, Vec::with_capacity(raw.len() / 6));
    while cursor < raw.len() {
        work = work.checked_add(varint(&raw, &mut cursor)?)?;
        let source = *raw.get(cursor)?;
        cursor += 1;
        let key = varint(&raw, &mut cursor)?;
        entries.push(Entry { work, key, source });
    }
    entries
        .windows(2)
        .all(|pair| pair[0].work < pair[1].work)
        .then_some(entries)
}

fn release_of(meta: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(meta).ok()?;
    let release = text.lines().find_map(|line| line.strip_prefix("month="))?;
    is_release(release).then(|| release.to_string())
}

/// Release labels look like `2026-10-31` or `2026-10-31-1`.
pub fn is_release(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= MAX_RELEASE
        && label
            .split('-')
            .all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
}

/// Orders release labels like the app's `releaseOrder`.
pub fn compare_releases(left: &str, right: &str) -> Ordering {
    let parts = |label: &str| -> Vec<u64> {
        label
            .split(['-', '.'])
            .map(|part| part.parse().unwrap_or(0))
            .collect()
    };
    parts(left).cmp(&parts(right))
}

impl Index {
    pub fn parse(file: &[u8]) -> Option<Index> {
        if file.get(..4)? != MAGIC || u32_at(file, 4)? != VERSION {
            return None;
        }
        let mut release = None;
        let mut languages = HashMap::new();
        for index in 0..u32_at(file, 8)? as usize {
            let entry = 12 + index * (NAME_BYTES + 8);
            let name = std::str::from_utf8(file.get(entry..entry + NAME_BYTES)?).ok()?;
            let name = name.trim_end_matches('\0');
            let offset = u32_at(file, entry + NAME_BYTES)? as usize;
            let length = u32_at(file, entry + NAME_BYTES + 4)? as usize;
            let section = file.get(offset..offset.checked_add(length)?)?;
            match name {
                "meta" => release = release_of(section),
                _ => {
                    languages.insert(name.to_string(), entries(section)?);
                }
            }
        }
        Some(Index {
            release: release?,
            languages,
        })
    }

    pub fn cover(&self, language: &str, work: u32) -> Option<Cover> {
        let entries = self.languages.get(language)?;
        let found = entries
            .binary_search_by_key(&work, |entry| entry.work)
            .ok()?;
        let Entry { source, key, .. } = entries[found];
        Some(Cover { source, key })
    }
}

/// The index of the newest catalogue release, or `None` when `current` is already it.
pub async fn newer(current: Option<&Index>) -> Result<Option<Index>, String> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(300))
        .user_agent(USER_AGENT)
        .build()
        .map_err(|error| error.to_string())?;
    let releases: serde_json::Value = client
        .get(RELEASES)
        .send()
        .await
        .and_then(|response| response.error_for_status())
        .map_err(|error| error.to_string())?
        .json()
        .await
        .map_err(|error| error.to_string())?;
    let published = pick(&releases).ok_or("no catalogue release with covers.bin")?;
    let up_to_date = current.is_some_and(|index| {
        compare_releases(&published.release, &index.release) != Ordering::Greater
    });
    if up_to_date {
        return Ok(None);
    }
    let bytes = client
        .get(&published.url)
        .send()
        .await
        .and_then(|response| response.error_for_status())
        .map_err(|error| error.to_string())?
        .bytes()
        .await
        .map_err(|error| error.to_string())?;
    let index = Index::parse(&bytes).ok_or("broken cover index")?;
    if index.release != published.release {
        return Err(format!(
            "index names {} for {}",
            index.release, published.release
        ));
    }
    Ok(Some(index))
}

struct Published {
    release: String,
    url: String,
}

fn pick(releases: &serde_json::Value) -> Option<Published> {
    releases.as_array()?.iter().find_map(|release| {
        let tag = release["tag_name"].as_str()?;
        let label = tag
            .strip_prefix("catalogue-")
            .filter(|label| is_release(label))?;
        if release["draft"].as_bool() == Some(true) {
            return None;
        }
        let asset = release["assets"]
            .as_array()?
            .iter()
            .find(|asset| asset["name"].as_str() == Some("covers.bin"))?;
        let url = asset["browser_download_url"].as_str()?.to_string();
        Some(Published {
            release: label.to_string(),
            url,
        })
    })
}

#[cfg(test)]
pub mod fixture {
    use super::*;
    use flate2::Compression;
    use flate2::write::DeflateEncoder;
    use std::io::Write;

    fn put_varint(buffer: &mut Vec<u8>, mut value: u32) {
        while value >= 0x80 {
            buffer.push(value as u8 | 0x80);
            value >>= 7;
        }
        buffer.push(value as u8);
    }

    /// Work, source and key of one cover.
    pub type Row = (u32, u8, u32);

    /// Writes an index in the builder's layout.
    pub fn file(release: &str, languages: &[(&str, &[Row])]) -> Vec<u8> {
        let mut sections = vec![(
            "meta".to_string(),
            format!("format=1\nmonth={release}\n").into_bytes(),
        )];
        for (name, rows) in languages {
            let mut raw = Vec::new();
            let mut previous = 0;
            for &(work, source, key) in *rows {
                put_varint(&mut raw, work - previous);
                raw.push(source);
                put_varint(&mut raw, key);
                previous = work;
            }
            let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
            encoder.write_all(&raw).unwrap();
            sections.push((name.to_string(), encoder.finish().unwrap()));
        }
        let mut file = MAGIC.to_vec();
        file.extend(VERSION.to_le_bytes());
        file.extend((sections.len() as u32).to_le_bytes());
        let mut offset = (12 + sections.len() * (NAME_BYTES + 8)) as u32;
        for (name, bytes) in &sections {
            let mut padded = [0u8; NAME_BYTES];
            padded[..name.len()].copy_from_slice(name.as_bytes());
            file.extend(padded);
            file.extend(offset.to_le_bytes());
            file.extend((bytes.len() as u32).to_le_bytes());
            offset += bytes.len() as u32;
        }
        sections
            .into_iter()
            .for_each(|(_, bytes)| file.extend(bytes));
        file
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_finds_covers_per_language() {
        let file = fixture::file(
            "2026-11-05",
            &[
                ("en", &[(2, OPEN_LIBRARY, 300), (900, ISBN_COVER, 7)]),
                ("de", &[(2, DNB, 5)]),
            ],
        );
        let index = Index::parse(&file).unwrap();
        assert_eq!(index.release, "2026-11-05");
        assert_eq!(
            index.cover("en", 2),
            Some(Cover {
                source: OPEN_LIBRARY,
                key: 300
            })
        );
        assert_eq!(
            index.cover("de", 2),
            Some(Cover {
                source: DNB,
                key: 5
            })
        );
        assert_eq!(index.cover("de", 900), None);
        assert_eq!(index.cover("fr", 2), None);
    }

    #[test]
    fn newest_catalogue_release_with_covers_is_picked() {
        let asset = |name: &str| {
            serde_json::json!({
                "name": name,
                "browser_download_url": format!("https://x/{name}"),
            })
        };
        let releases = serde_json::json!([
            {"tag_name": "v1.5.0", "assets": [asset("covers.bin")]},
            {"tag_name": "catalogue-2026-12-05", "draft": true, "assets": [asset("covers.bin")]},
            {"tag_name": "catalogue-2026-11-05", "assets": [asset("manifest.json")]},
            {"tag_name": "catalogue-2026-10-31", "assets": [asset("covers.bin")]},
        ]);
        let picked = pick(&releases).unwrap();
        assert_eq!(picked.release, "2026-10-31");
        assert_eq!(picked.url, "https://x/covers.bin");
    }

    #[test]
    fn broken_files_are_rejected() {
        assert!(Index::parse(b"SHLF").is_none());
        let mut file = fixture::file("2026-11-05", &[("en", &[(2, 0, 3)])]);
        file.truncate(file.len() - 2);
        assert!(Index::parse(&file).is_none());
    }

    #[test]
    fn urls_match_the_app() {
        let cover = |source, key| Cover { source, key }.url().unwrap();
        assert_eq!(
            cover(OPEN_LIBRARY, 42),
            "https://covers.openlibrary.org/b/id/42-L.jpg"
        );
        assert_eq!(isbn_of(917_629_168), "9789176291689");
        assert!(cover(DNB, 357_050_132).ends_with("isbn=9783570501320"));
        assert_eq!(
            cover(BOOKBITES, 917_629_168),
            "https://bookbites-prod-se.covers.bookbites.com/9789176291689"
        );
        assert_eq!(
            cover(DIGAR, 123),
            "https://www.digar.ee/arhiiv/nlib-digar:123?thumb=1"
        );
        assert!(Cover { source: 9, key: 1 }.url().is_none());
        assert_eq!(
            author_url(7),
            "https://covers.openlibrary.org/a/olid/OL7A-M.jpg?default=false"
        );
    }

    #[test]
    fn releases_are_validated_and_ordered() {
        assert!(is_release("2026-10-31") && is_release("2026-10-31-1"));
        assert!(!is_release("") && !is_release("latest") && !is_release("2026--1"));
        assert_eq!(
            compare_releases("2026-10-31", "2026-10-31-1"),
            Ordering::Less
        );
        assert_eq!(
            compare_releases("2026-11-05", "2026-10-31-2"),
            Ordering::Greater
        );
    }
}
