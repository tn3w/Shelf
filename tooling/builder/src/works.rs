//! `works.bin` (`SHWK`): the book layer across language catalogues.
//!
//! Little endian: magic, u32 version, u8 language count + (u8 length, code) each,
//! u32 work count + per work (ascending unified id) u32 unified id, u8 member count,
//! members (u8 language index, u32 local id), then u32 alias count + (u32 old, u32 new)
//! pairs ascending by old id, then (version 2) u32 author alias count + (u32 alias, u32
//! author) pairs ascending by alias. Works found in one catalogue only are left out: their
//! unified id is their local id.

use crate::reader::u32_at;
use std::collections::HashMap;

pub const MAGIC: &[u8; 4] = b"SHWK";
pub const VERSION: u32 = 2;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Works {
    pub languages: Vec<String>,
    pub works: Vec<(u32, Vec<(u8, u32)>)>,
    pub aliases: Vec<(u32, u32)>,
    pub authors: Vec<(u32, u32)>,
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn take(&mut self, length: usize) -> Option<&[u8]> {
        let slice = self.bytes.get(self.at..self.at + length)?;
        self.at += length;
        Some(slice)
    }

    fn byte(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn u32(&mut self) -> Option<u32> {
        Some(u32_at(self.take(4)?, 0))
    }
}

impl Works {
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = MAGIC.to_vec();
        bytes.extend(VERSION.to_le_bytes());
        bytes.push(self.languages.len() as u8);
        for code in &self.languages {
            bytes.push(code.len() as u8);
            bytes.extend(code.as_bytes());
        }
        bytes.extend((self.works.len() as u32).to_le_bytes());
        for (unified, members) in &self.works {
            bytes.extend(unified.to_le_bytes());
            bytes.push(members.len() as u8);
            for &(language, local) in members {
                bytes.push(language);
                bytes.extend(local.to_le_bytes());
            }
        }
        for pairs in [&self.aliases, &self.authors] {
            bytes.extend((pairs.len() as u32).to_le_bytes());
            for &(from, to) in pairs {
                bytes.extend(from.to_le_bytes());
                bytes.extend(to.to_le_bytes());
            }
        }
        bytes
    }

    pub fn parse(bytes: &[u8]) -> Option<Works> {
        let mut cursor = Cursor { bytes, at: 0 };
        if cursor.take(4)? != MAGIC {
            return None;
        }
        let version = cursor.u32()?;
        if !(1..=VERSION).contains(&version) {
            return None;
        }
        let languages = (0..cursor.byte()?)
            .map(|_| {
                let length = cursor.byte()? as usize;
                String::from_utf8(cursor.take(length)?.to_vec()).ok()
            })
            .collect::<Option<_>>()?;
        let works = (0..cursor.u32()?)
            .map(|_| {
                let unified = cursor.u32()?;
                let members = (0..cursor.byte()?)
                    .map(|_| Some((cursor.byte()?, cursor.u32()?)))
                    .collect::<Option<_>>()?;
                Some((unified, members))
            })
            .collect::<Option<_>>()?;
        let mut pairs = || -> Option<Vec<(u32, u32)>> {
            (0..cursor.u32()?)
                .map(|_| Some((cursor.u32()?, cursor.u32()?)))
                .collect()
        };
        let aliases = pairs()?;
        let authors = if version > 1 { pairs()? } else { Vec::new() };
        Some(Works {
            languages,
            works,
            aliases,
            authors,
        })
    }

    pub fn load(path: &std::path::Path) -> Option<Works> {
        Works::parse(&std::fs::read(path).ok()?)
    }

    /// Local id → unified id over all members; ids are global, so no language is needed.
    pub fn unified_ids(&self) -> HashMap<u32, u32> {
        let members = self.works.iter().flat_map(|(unified, members)| {
            members.iter().map(move |&(_, local)| (local, *unified))
        });
        members.chain(self.aliases.iter().copied()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let works = Works {
            languages: vec!["en".to_string(), "de".to_string()],
            works: vec![(5, vec![(0, 5), (1, 1_000_000_007)])],
            aliases: vec![(9, 5)],
            authors: vec![(7_098_281, 7_617_742)],
        };
        let bytes = works.encode();
        assert_eq!(Works::parse(&bytes), Some(works.clone()));
        assert_eq!(works.unified_ids()[&1_000_000_007], 5);
        assert!(Works::parse(&bytes[..bytes.len() - 1]).is_none());
    }

    #[test]
    fn version_one_has_no_author_aliases() {
        let works = Works {
            languages: vec!["en".to_string()],
            aliases: vec![(9, 5)],
            ..Works::default()
        };
        let mut bytes = works.encode();
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes.truncate(bytes.len() - 4);
        assert_eq!(Works::parse(&bytes), Some(works));
    }
}
