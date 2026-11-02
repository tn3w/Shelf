//! Server-only sidecars beside the app packs, never listed in `manifest.segments`.
//!
//! `links-<lang>.bin` (`SHLK`) holds the ids that tie a work to other catalogues and
//! `extras-<lang>.bin` (`SHEX`) what a book page shows beyond the app pack. Both follow the
//! cover index: magic, version, section table, `meta` section, one section per language.
//! A language section is a block index (block count, first work per block, offset table)
//! over raw deflate blocks; a block holds records sorted by work: varint work delta,
//! varint length, record bytes. Texts are varint length + UTF-8, lists varint count + items.

use crate::covers::{merge_files, month_of, sections_of};
use crate::segment::{deflate, indexed_blocks, put_text, put_varint};
use std::io::Read;
use std::path::Path;

pub const LINKS: &[u8; 4] = b"SHLK";
pub const EXTRAS: &[u8; 4] = b"SHEX";
pub const VERSION: u32 = 1;
pub const EDITION_CAP: usize = 300;
const RECORDS_PER_BLOCK: usize = 64;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Links {
    pub work: u32,
    pub wikidata: u32,
    pub open_library: Vec<u32>,
    pub gutenberg: Vec<u32>,
    pub original_title: String,
    pub original_language: String,
    pub authorities: Vec<String>,
    pub author: String,
    pub series: String,
    pub position: u16,
    pub records: u32,
    pub language: String,
    pub originals: Vec<(String, u32)>,
    pub compilation: bool,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Edition {
    pub year: u16,
    pub publisher: String,
    pub isbn: String,
    pub format: u8,
    pub identifier: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Extras {
    pub work: u32,
    pub descriptions: Vec<(String, String)>,
    pub identifiers: Vec<String>,
    pub titles: Vec<(String, String)>,
    pub translators: Vec<String>,
    pub original_title: String,
    pub editions: Vec<Edition>,
}

fn put_texts(buffer: &mut Vec<u8>, texts: &[String]) {
    put_varint(buffer, texts.len() as u32);
    texts.iter().for_each(|text| put_text(buffer, text));
}

fn put_pairs(buffer: &mut Vec<u8>, pairs: &[(String, String)]) {
    put_varint(buffer, pairs.len() as u32);
    for (key, value) in pairs {
        put_text(buffer, key);
        put_text(buffer, value);
    }
}

fn put_votes(buffer: &mut Vec<u8>, votes: &[(String, u32)]) {
    put_varint(buffer, votes.len() as u32);
    for (key, count) in votes {
        put_text(buffer, key);
        put_varint(buffer, *count);
    }
}

fn put_numbers(buffer: &mut Vec<u8>, numbers: &[u32]) {
    put_varint(buffer, numbers.len() as u32);
    numbers
        .iter()
        .for_each(|&number| put_varint(buffer, number));
}

impl Links {
    fn encode(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        put_varint(&mut buffer, self.wikidata);
        put_numbers(&mut buffer, &self.open_library);
        put_numbers(&mut buffer, &self.gutenberg);
        put_text(&mut buffer, &self.original_title);
        put_text(&mut buffer, &self.original_language);
        put_texts(&mut buffer, &self.authorities);
        put_text(&mut buffer, &self.author);
        put_text(&mut buffer, &self.series);
        put_varint(&mut buffer, u32::from(self.position));
        put_varint(&mut buffer, self.records);
        put_text(&mut buffer, &self.language);
        put_votes(&mut buffer, &self.originals);
        buffer.push(u8::from(self.compilation));
        buffer
    }

    fn decode(work: u32, reader: &mut Cursor) -> Option<Links> {
        Some(Links {
            work,
            wikidata: reader.varint()?,
            open_library: reader.numbers()?,
            gutenberg: reader.numbers()?,
            original_title: reader.text()?,
            original_language: reader.text()?,
            authorities: reader.texts()?,
            author: reader.text()?,
            series: reader.text()?,
            position: u16::try_from(reader.varint()?).ok()?,
            records: reader.varint()?,
            language: reader.text()?,
            originals: reader.votes()?,
            compilation: reader.byte()? == 1,
        })
    }
}

impl Extras {
    fn encode(&self) -> Vec<u8> {
        let mut buffer = Vec::new();
        put_pairs(&mut buffer, &self.descriptions);
        put_texts(&mut buffer, &self.identifiers);
        put_pairs(&mut buffer, &self.titles);
        put_texts(&mut buffer, &self.translators);
        put_text(&mut buffer, &self.original_title);
        put_varint(&mut buffer, self.editions.len() as u32);
        for edition in &self.editions {
            put_varint(&mut buffer, u32::from(edition.year));
            put_text(&mut buffer, &edition.publisher);
            put_text(&mut buffer, &edition.isbn);
            buffer.push(edition.format);
            put_text(&mut buffer, &edition.identifier);
        }
        buffer
    }

    fn decode(work: u32, reader: &mut Cursor) -> Option<Extras> {
        let mut extras = Extras {
            work,
            descriptions: reader.pairs()?,
            identifiers: reader.texts()?,
            titles: reader.pairs()?,
            translators: reader.texts()?,
            original_title: reader.text()?,
            editions: Vec::new(),
        };
        for _ in 0..reader.varint()? {
            extras.editions.push(Edition {
                year: u16::try_from(reader.varint()?).ok()?,
                publisher: reader.text()?,
                isbn: reader.text()?,
                format: reader.byte()?,
                identifier: reader.text()?,
            });
        }
        Some(extras)
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Cursor<'_> {
    fn has_more(&self) -> bool {
        self.at < self.bytes.len()
    }

    fn byte(&mut self) -> Option<u8> {
        let byte = *self.bytes.get(self.at)?;
        self.at += 1;
        Some(byte)
    }

    fn varint(&mut self) -> Option<u32> {
        let mut value = 0u32;
        for shift in (0..35).step_by(7) {
            let byte = self.byte()?;
            value |= u32::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Some(value);
            }
        }
        None
    }

    fn take(&mut self, length: usize) -> Option<&[u8]> {
        let slice = self.bytes.get(self.at..self.at + length)?;
        self.at += length;
        Some(slice)
    }

    fn text(&mut self) -> Option<String> {
        let length = self.varint()? as usize;
        String::from_utf8(self.take(length)?.to_vec()).ok()
    }

    fn texts(&mut self) -> Option<Vec<String>> {
        (0..self.varint()?).map(|_| self.text()).collect()
    }

    fn pairs(&mut self) -> Option<Vec<(String, String)>> {
        (0..self.varint()?)
            .map(|_| Some((self.text()?, self.text()?)))
            .collect()
    }

    fn votes(&mut self) -> Option<Vec<(String, u32)>> {
        (0..self.varint()?)
            .map(|_| Some((self.text()?, self.varint()?)))
            .collect()
    }

    fn numbers(&mut self) -> Option<Vec<u32>> {
        (0..self.varint()?).map(|_| self.varint()).collect()
    }
}

fn section(mut records: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    records.sort_by_key(|(work, _)| *work);
    records.dedup_by_key(|(work, _)| *work);
    let chunks: Vec<&[(u32, Vec<u8>)]> = records.chunks(RECORDS_PER_BLOCK).collect();
    let firsts: Vec<u32> = chunks.iter().map(|chunk| chunk[0].0).collect();
    let blocks: Vec<Vec<u8>> = chunks
        .iter()
        .map(|chunk| {
            let mut raw = Vec::new();
            let mut previous = chunk[0].0;
            for (work, bytes) in chunk.iter() {
                put_varint(&mut raw, work - previous);
                put_varint(&mut raw, bytes.len() as u32);
                raw.extend_from_slice(bytes);
                previous = *work;
            }
            deflate(&raw, &[])
        })
        .collect();
    indexed_blocks(&firsts, &blocks)
}

fn file(magic: &[u8; 4], month: &str, language: &str, records: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    let languages = vec![(language.to_string(), section(records))];
    crate::covers::index_file(magic, VERSION, month, languages)
}

pub fn links_file(month: &str, language: &str, links: &[Links]) -> Vec<u8> {
    let records = links
        .iter()
        .map(|links| (links.work, links.encode()))
        .collect();
    file(LINKS, month, language, records)
}

pub fn extras_file(month: &str, language: &str, extras: &[Extras]) -> Vec<u8> {
    let records = extras
        .iter()
        .map(|extras| (extras.work, extras.encode()))
        .collect();
    file(EXTRAS, month, language, records)
}

pub fn merge(magic: &[u8; 4], inputs: &[impl AsRef<Path>]) -> Result<Vec<u8>, String> {
    merge_files(magic, VERSION, inputs)
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    let slice = bytes.get(offset..offset + 4)?;
    Some(u32::from_le_bytes(slice.try_into().ok()?))
}

struct BlockIndex<'a> {
    firsts: Vec<u32>,
    offsets: Vec<u32>,
    data: &'a [u8],
}

impl<'a> BlockIndex<'a> {
    fn parse(section: &'a [u8]) -> Option<BlockIndex<'a>> {
        let count = u32_at(section, 0)? as usize;
        let firsts = (0..count).map(|index| u32_at(section, 4 + 4 * index));
        let table = 4 + 4 * count;
        let offsets = (0..=count).map(|index| u32_at(section, table + 4 + 4 * index));
        let data = section.get(table + 4 + 4 * (count + 1)..)?;
        Some(BlockIndex {
            firsts: firsts.collect::<Option<_>>()?,
            offsets: offsets.collect::<Option<_>>()?,
            data,
        })
    }

    fn block(&self, index: usize) -> Option<Vec<(u32, Vec<u8>)>> {
        let range = self.offsets[index] as usize..self.offsets[index + 1] as usize;
        let mut raw = Vec::new();
        flate2::read::DeflateDecoder::new(self.data.get(range)?)
            .read_to_end(&mut raw)
            .ok()?;
        let mut cursor = Cursor { bytes: &raw, at: 0 };
        let mut work = self.firsts[index];
        let mut records = Vec::new();
        while cursor.has_more() {
            work = work.checked_add(cursor.varint()?)?;
            let length = cursor.varint()? as usize;
            records.push((work, cursor.take(length)?.to_vec()));
        }
        Some(records)
    }
}

pub struct Sidecar {
    pub month: String,
    pub languages: Vec<(String, Vec<u8>)>,
}

impl Sidecar {
    pub fn parse(magic: &[u8; 4], file: &[u8]) -> Option<Sidecar> {
        let mut found = sections_of(magic, VERSION, file)?.into_iter();
        let (_, meta) = found.next()?;
        Some(Sidecar {
            month: month_of(&meta)?,
            languages: found.collect(),
        })
    }

    fn records(&self, language: &str, work: Option<u32>) -> Option<Vec<(u32, Vec<u8>)>> {
        let (_, bytes) = self.languages.iter().find(|(name, _)| name == language)?;
        let index = BlockIndex::parse(bytes)?;
        let Some(work) = work else {
            let blocks = (0..index.firsts.len()).map(|block| index.block(block));
            return Some(blocks.collect::<Option<Vec<_>>>()?.concat());
        };
        let block = index
            .firsts
            .partition_point(|&first| first <= work)
            .checked_sub(1)?;
        let records = index.block(block)?.into_iter();
        Some(records.filter(|(found, _)| *found == work).collect())
    }

    fn decoded<T>(
        &self,
        language: &str,
        work: Option<u32>,
        decode: fn(u32, &mut Cursor) -> Option<T>,
    ) -> Option<Vec<T>> {
        let records = self.records(language, work)?.into_iter();
        let decode = |(work, bytes): (u32, Vec<u8>)| {
            decode(
                work,
                &mut Cursor {
                    bytes: &bytes,
                    at: 0,
                },
            )
        };
        records.map(decode).collect()
    }

    pub fn links(&self, language: &str) -> Option<Vec<Links>> {
        self.decoded(language, None, Links::decode)
    }

    pub fn extras(&self, language: &str) -> Option<Vec<Extras>> {
        self.decoded(language, None, Extras::decode)
    }

    pub fn links_of(&self, language: &str, work: u32) -> Option<Links> {
        self.decoded(language, Some(work), Links::decode)?.pop()
    }

    pub fn extras_of(&self, language: &str, work: u32) -> Option<Extras> {
        self.decoded(language, Some(work), Extras::decode)?.pop()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn links(work: u32) -> Links {
        Links {
            work,
            wikidata: 180_736,
            open_library: vec![work, 66_554],
            gutenberg: vec![1342],
            original_title: "pride and prejudice".to_string(),
            original_language: "en".to_string(),
            authorities: vec!["wikidata:Q36322".to_string(), "viaf:102333412".to_string()],
            author: "austen jane".to_string(),
            series: String::new(),
            position: 0,
            records: 412,
            language: "en".to_string(),
            originals: vec![("pride and prejudice".to_string(), 3)],
            compilation: false,
        }
    }

    fn extras(work: u32) -> Extras {
        Extras {
            work,
            descriptions: vec![("en".to_string(), "A novel of manners.".to_string())],
            identifiers: vec![format!("ol:OL{work}W"), "pg:1342".to_string()],
            titles: vec![("ja".to_string(), "高慢と偏見".to_string())],
            translators: vec!["Karin von Schweder-Schreiner".to_string()],
            original_title: "Pride and Prejudice".to_string(),
            editions: vec![Edition {
                year: 2014,
                publisher: "Anaconda".to_string(),
                isbn: "9783730601273".to_string(),
                format: 2,
                identifier: "dnb:1051183219".to_string(),
            }],
        }
    }

    #[test]
    fn links_round_trip_by_work() {
        let all: Vec<Links> = (0..200).map(|index| links(10 + index * 7)).collect();
        let bytes = links_file("2026-11-05", "de", &all);
        let sidecar = Sidecar::parse(LINKS, &bytes).unwrap();
        assert_eq!(sidecar.month, "2026-11-05");
        assert_eq!(sidecar.links("de").unwrap(), all);
        assert_eq!(sidecar.links_of("de", 10 + 150 * 7), Some(all[150].clone()));
        assert_eq!(sidecar.links_of("de", 11), None);
        assert!(Sidecar::parse(EXTRAS, &bytes).is_none());
    }

    #[test]
    fn extras_merge_and_round_trip() {
        let directory = std::env::temp_dir().join(format!("sidecar-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let german = vec![extras(4), extras(9)];
        let japanese = vec![extras(4)];
        let de = directory.join("extras-de.bin");
        let ja = directory.join("extras-ja.bin");
        std::fs::write(&de, extras_file("2026-11-05", "de", &german)).unwrap();
        std::fs::write(&ja, extras_file("2026-11-05", "ja", &japanese)).unwrap();
        let merged = merge(EXTRAS, &[ja, de]).unwrap();
        let sidecar = Sidecar::parse(EXTRAS, &merged).unwrap();
        assert_eq!(sidecar.extras("de").unwrap(), german);
        assert_eq!(sidecar.extras_of("ja", 4), Some(japanese[0].clone()));
        std::fs::remove_dir_all(directory).unwrap();
    }
}
