//! Cover index: book id to cover source for each language, read by the image proxy.
//!
//! Layout follows segments: `SHCV`, version, section table, then a `meta` section and
//! one raw deflate section per language. A language section holds records sorted by
//! work: varint work delta, source byte, varint key. Source 0 is an Open Library cover
//! id, other sources match `library_covers` in segments (key is an ISBN key or a DIGAR
//! id).

use crate::segment::{assemble_file, deflate, meta_section, put_varint};
use std::path::Path;

pub const MAGIC: &[u8; 4] = b"SHCV";
pub const VERSION: u32 = 1;
pub const OPEN_LIBRARY: u8 = 0;
const NAME_BYTES: usize = 16;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cover {
    pub work: u32,
    pub source: u8,
    pub key: u32,
}

/// The cover the app shows: an Open Library cover id wins over a library cover.
pub fn cover_of(work: u32, cover: u32, library: Option<(u8, u32)>) -> Option<Cover> {
    if cover != 0 {
        return Some(Cover {
            work,
            source: OPEN_LIBRARY,
            key: cover,
        });
    }
    library.map(|(source, key)| Cover { work, source, key })
}

/// Adds alias ids so books saved under a merged work keep their cover.
pub fn with_aliases(mut covers: Vec<Cover>, aliases: &[(u32, u32)]) -> Vec<Cover> {
    covers.sort_by_key(|cover| cover.work);
    covers.dedup_by_key(|cover| cover.work);
    let found = |work: u32| {
        covers
            .binary_search_by_key(&work, |cover| cover.work)
            .ok()
            .map(|index| covers[index])
    };
    let extra: Vec<Cover> = aliases
        .iter()
        .filter(|&&(from, _)| found(from).is_none())
        .filter_map(|&(from, to)| {
            found(to).map(|cover| Cover {
                work: from,
                ..cover
            })
        })
        .collect();
    covers.extend(extra);
    covers.sort_by_key(|cover| cover.work);
    covers.dedup_by_key(|cover| cover.work);
    covers
}

fn encode(covers: &[Cover]) -> Vec<u8> {
    let mut raw = Vec::with_capacity(covers.len() * 6);
    let mut previous = 0;
    for cover in covers {
        put_varint(&mut raw, cover.work - previous);
        raw.push(cover.source);
        put_varint(&mut raw, cover.key);
        previous = cover.work;
    }
    deflate(&raw, &[])
}

/// One file with a section per language; `languages` must hold sorted records.
pub fn index_file(
    magic: &[u8; 4],
    version: u32,
    month: &str,
    languages: Vec<(String, Vec<u8>)>,
) -> Vec<u8> {
    let meta = meta_section(&[
        ("format", version.to_string()),
        ("month", month.to_string()),
    ]);
    let mut sections = vec![("meta", meta)];
    sections.extend(
        languages
            .iter()
            .map(|(code, bytes)| (code.as_str(), bytes.clone())),
    );
    assemble_file(magic, version, sections)
}

pub fn language_index(month: &str, language: &str, covers: &[Cover]) -> Vec<u8> {
    index_file(
        MAGIC,
        VERSION,
        month,
        vec![(language.to_string(), encode(covers))],
    )
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    let slice = bytes.get(offset..offset + 4)?;
    Some(u32::from_le_bytes(slice.try_into().ok()?))
}

#[cfg(test)]
pub fn sections(file: &[u8]) -> Option<Vec<(String, Vec<u8>)>> {
    sections_of(MAGIC, VERSION, file)
}

/// Sections of an index file, `meta` first.
pub fn sections_of(magic: &[u8; 4], version: u32, file: &[u8]) -> Option<Vec<(String, Vec<u8>)>> {
    if file.get(..4)? != magic || u32_at(file, 4)? != version {
        return None;
    }
    (0..u32_at(file, 8)? as usize)
        .map(|index| {
            let entry = 12 + index * (NAME_BYTES + 8);
            let name = file.get(entry..entry + NAME_BYTES)?;
            let name = String::from_utf8(name.to_vec()).ok()?;
            let offset = u32_at(file, entry + NAME_BYTES)? as usize;
            let length = u32_at(file, entry + NAME_BYTES + 4)? as usize;
            let bytes = file.get(offset..offset + length)?.to_vec();
            Some((name.trim_end_matches('\0').to_string(), bytes))
        })
        .collect()
}

pub fn month_of(meta: &[u8]) -> Option<String> {
    String::from_utf8_lossy(meta)
        .lines()
        .find_map(|line| line.strip_prefix("month="))
        .map(str::to_string)
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

/// Covers of one language section, sorted by work.
pub fn decode(section: &[u8]) -> Option<Vec<Cover>> {
    use std::io::Read;
    let mut raw = Vec::new();
    flate2::read::DeflateDecoder::new(section)
        .read_to_end(&mut raw)
        .ok()?;
    let (mut cursor, mut work, mut covers) = (0, 0u32, Vec::new());
    while cursor < raw.len() {
        work = work.checked_add(varint(&raw, &mut cursor)?)?;
        let source = *raw.get(cursor)?;
        cursor += 1;
        covers.push(Cover {
            work,
            source,
            key: varint(&raw, &mut cursor)?,
        });
    }
    Some(covers)
}

/// Merges per-language index files into one; every file must name the same month.
pub fn merge(inputs: &[impl AsRef<Path>]) -> Result<Vec<u8>, String> {
    merge_files(MAGIC, VERSION, inputs)
}

pub fn merge_files(
    magic: &[u8; 4],
    version: u32,
    inputs: &[impl AsRef<Path>],
) -> Result<Vec<u8>, String> {
    let mut month: Option<String> = None;
    let mut languages: Vec<(String, Vec<u8>)> = Vec::new();
    for input in inputs {
        let path = input.as_ref();
        let file = std::fs::read(path).map_err(|error| format!("{}: {error}", path.display()))?;
        let found = sections_of(magic, version, &file)
            .ok_or_else(|| format!("{}: not an index file", path.display()))?;
        for (name, bytes) in found {
            if name == "meta" {
                let current = month_of(&bytes).ok_or("missing month")?;
                if month.get_or_insert_with(|| current.clone()) != &current {
                    return Err(format!("{}: month {current} differs", path.display()));
                }
            } else if !crate::LANGUAGES.contains(&name.as_str()) {
                return Err(format!("{}: unknown language {name}", path.display()));
            } else if languages.iter().any(|(known, _)| *known == name) {
                return Err(format!("{}: language {name} repeated", path.display()));
            } else {
                languages.push((name, bytes));
            }
        }
    }
    let month = month.ok_or("no index file given")?;
    languages.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(index_file(magic, version, &month, languages))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_library_cover_wins_over_library_cover() {
        assert_eq!(cover_of(5, 9, Some((1, 4))).unwrap().source, OPEN_LIBRARY);
        assert_eq!(
            cover_of(5, 0, Some((1, 4))).unwrap(),
            Cover {
                work: 5,
                source: 1,
                key: 4
            }
        );
        assert_eq!(cover_of(5, 0, None), None);
    }

    #[test]
    fn aliases_point_at_their_target_cover() {
        let covers = vec![Cover {
            work: 20,
            source: 0,
            key: 7,
        }];
        let all = with_aliases(covers, &[(3, 20), (4, 99), (20, 3)]);
        assert_eq!(
            all,
            vec![
                Cover {
                    work: 3,
                    source: 0,
                    key: 7
                },
                Cover {
                    work: 20,
                    source: 0,
                    key: 7
                }
            ]
        );
    }

    #[test]
    fn languages_merge_and_round_trip() {
        let directory = std::env::temp_dir().join(format!("covers-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let english = vec![
            Cover {
                work: 2,
                source: 0,
                key: 300,
            },
            Cover {
                work: 900,
                source: 3,
                key: 12,
            },
        ];
        let german = vec![Cover {
            work: 2,
            source: 1,
            key: 5,
        }];
        let en = directory.join("covers-en.bin");
        let de = directory.join("covers-de.bin");
        std::fs::write(&en, language_index("2026-11-05", "en", &english)).unwrap();
        std::fs::write(&de, language_index("2026-11-05", "de", &german)).unwrap();
        let merged = merge(&[en.clone(), de.clone()]).unwrap();
        let found = sections(&merged).unwrap();
        assert_eq!(month_of(&found[0].1).as_deref(), Some("2026-11-05"));
        assert_eq!(found[1].0, "de");
        assert_eq!(decode(&found[1].1).unwrap(), german);
        assert_eq!(decode(&found[2].1).unwrap(), english);
        std::fs::write(&de, language_index("2026-12-05", "de", &german)).unwrap();
        assert!(merge(&[en, de]).is_err());
        std::fs::remove_dir_all(directory).unwrap();
    }
}
