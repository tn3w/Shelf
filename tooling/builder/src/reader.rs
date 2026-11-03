//! Reads SHLF v2 segments and ranks like the app (`Segment.kt`, `Catalogue.kt`, `Packs.kt`).

use crate::search::{is_companion, is_omnibus};
use flate2::{Decompress, FlushDecompress, Status};
use regex::Regex;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::ops::Range;
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex};

const MAGIC: &[u8; 4] = b"SHLF";
pub const VERSION: u32 = 2;
const NAME_BYTES: usize = 16;
const SEPARATOR: char = '\u{1f}';
const YEAR_EPOCH: u32 = 1400;
const COMPLETION_LENGTHS: Range<usize> = 2..5;
const ISBN_BASE: u64 = 978_000_000_000;
const CACHED_BLOCKS: usize = 1024;

type Block = Arc<Vec<u8>>;
pub const DIGAR: u8 = 2;
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

pub struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Reader<'a> {
        Reader { bytes, at: 0 }
    }

    pub fn has_more(&self) -> bool {
        self.at < self.bytes.len()
    }

    pub fn byte(&mut self) -> u8 {
        self.at += 1;
        self.bytes[self.at - 1]
    }

    pub fn varint(&mut self) -> u32 {
        let mut value = 0;
        for shift in (0..35).step_by(7) {
            let byte = self.byte();
            value |= u32::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                break;
            }
        }
        value
    }

    pub fn take(&mut self, length: usize) -> &'a [u8] {
        self.at += length;
        &self.bytes[self.at - length..self.at]
    }

    pub fn text(&mut self) -> String {
        let length = self.varint() as usize;
        String::from_utf8_lossy(self.take(length)).into_owned()
    }

    pub fn rest(&mut self) -> &'a [u8] {
        self.take(self.bytes.len() - self.at)
    }
}

pub fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("4 bytes"))
}

pub fn postings(bytes: &[u8]) -> Vec<u32> {
    let mut reader = Reader::new(bytes);
    let mut current = 0;
    let mut values = Vec::new();
    while reader.has_more() {
        current += reader.varint();
        values.push(current);
    }
    values
}

pub fn inflate(compressed: &[u8], dictionary: &[u8]) -> Vec<u8> {
    let mut decompress = Decompress::new(false);
    if !dictionary.is_empty() {
        decompress
            .set_dictionary(dictionary)
            .expect("set dictionary");
    }
    let mut output = Vec::with_capacity(compressed.len() * 4 + 64);
    loop {
        let read = decompress.total_in() as usize;
        let status = decompress
            .decompress_vec(&compressed[read..], &mut output, FlushDecompress::Finish)
            .expect("inflate");
        if status == Status::StreamEnd {
            return output;
        }
        assert_eq!(output.len(), output.capacity(), "truncated deflate block");
        output.reserve(output.len());
    }
}

fn table_count(table: &[u8]) -> usize {
    u32_at(table, 0) as usize
}

fn table_entry(table: &[u8], index: usize) -> &[u8] {
    let data = 4 + 4 * (table_count(table) + 1);
    let start = u32_at(table, 4 + 4 * index) as usize;
    let end = u32_at(table, 8 + 4 * index) as usize;
    &table[data + start..data + end]
}

fn leading_text(table: &[u8], index: usize, skip: usize) -> String {
    Reader::new(&table_entry(table, index)[skip..]).text()
}

fn last_at_most<T: Ord>(sorted: &[T], key: &T) -> Option<usize> {
    sorted.partition_point(|item| item <= key).checked_sub(1)
}

fn length_prefixed(raw: &[u8]) -> Vec<&[u8]> {
    let mut reader = Reader::new(raw);
    let mut records = Vec::new();
    while reader.has_more() {
        let length = reader.varint() as usize;
        records.push(reader.take(length));
    }
    records
}

fn sections(bytes: &[u8]) -> Option<HashMap<String, Range<usize>>> {
    if bytes.get(..4)? != MAGIC || u32_at(bytes.get(..8)?, 4) != VERSION {
        return None;
    }
    let count = u32_at(bytes.get(..12)?, 8) as usize;
    (0..count)
        .map(|index| {
            let entry = 12 + index * (NAME_BYTES + 8);
            let header = bytes.get(entry..entry + NAME_BYTES + 8)?;
            let name = std::str::from_utf8(&header[..NAME_BYTES]).ok()?;
            let offset = u32_at(header, NAME_BYTES) as usize;
            let range = offset..offset + u32_at(header, NAME_BYTES + 4) as usize;
            bytes.get(range.clone())?;
            Some((name.trim_end_matches('\0').to_string(), range))
        })
        .collect()
}

fn meta(section: &[u8]) -> HashMap<String, String> {
    String::from_utf8_lossy(section)
        .lines()
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect()
}

pub fn isbn_of(key: u32) -> String {
    let twelve = (ISBN_BASE + u64::from(key)).to_string();
    let sum: u32 = twelve
        .bytes()
        .enumerate()
        .map(|(index, digit)| u32::from(digit - b'0') * if index % 2 == 0 { 1 } else { 3 })
        .sum();
    format!("{twelve}{}", (10 - sum % 10) % 10)
}

pub struct Facts {
    pub authors: Vec<u32>,
    pub year: u32,
    pub cover: u32,
    pub tags: Vec<u8>,
    pub series: Option<usize>,
    pub position: u32,
}

pub struct Heads {
    pub title: String,
    pub subtitle: String,
    pub alternate: String,
}

impl Heads {
    fn parse(raw: &[u8]) -> Heads {
        let text = String::from_utf8_lossy(raw);
        let mut fields = text.split(SEPARATOR).map(str::to_string);
        Heads {
            title: fields.next().unwrap_or_default(),
            subtitle: fields.next().unwrap_or_default(),
            alternate: fields.next().unwrap_or_default(),
        }
    }
}

#[derive(Debug, PartialEq)]
pub struct Description {
    pub text: String,
    pub translated: bool,
}

pub struct AuthorRecord {
    pub number: u32,
    pub born: u32,
    pub name: String,
    pub works: Vec<u32>,
}

pub struct Series {
    pub name: String,
    pub members: Vec<u32>,
}

pub struct Tag {
    pub id: usize,
    pub slug: String,
    pub label: String,
    pub category: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct LibraryCover {
    pub source: u8,
    pub key: String,
}

pub struct Term {
    pub text: String,
    pub title_count: u32,
    pub author_count: u32,
    titles: Vec<u8>,
    authors: Vec<u8>,
}

impl Term {
    pub fn title_works(&self) -> Vec<u32> {
        postings(&self.titles)
    }

    pub fn author_slots(&self) -> Vec<u32> {
        postings(&self.authors)
    }

    pub fn frequency(&self) -> u32 {
        self.title_count + self.author_count
    }
}

struct TermBlocks {
    range: Range<usize>,
    firsts: Vec<String>,
    with_postings: bool,
}

impl TermBlocks {
    fn new(bytes: &[u8], range: Range<usize>, with_postings: bool) -> TermBlocks {
        let table = &bytes[range.clone()];
        let firsts = (0..table_count(table))
            .map(|index| leading_text(table, index, 1))
            .collect();
        TermBlocks {
            range,
            firsts,
            with_postings,
        }
    }

    fn block(&self, bytes: &[u8], index: usize) -> Vec<Term> {
        let mut reader = Reader::new(table_entry(&bytes[self.range.clone()], index));
        let mut previous = String::new();
        let mut terms = Vec::new();
        while reader.has_more() {
            let shared = reader.varint() as usize;
            let text = format!("{}{}", &previous[..shared], reader.text());
            let title_count = reader.varint();
            let author_count = reader.varint();
            let (mut titles, mut authors) = (Vec::new(), Vec::new());
            if self.with_postings {
                let (title_length, author_length) = (reader.varint(), reader.varint());
                titles = reader.take(title_length as usize).to_vec();
                authors = reader.take(author_length as usize).to_vec();
            }
            previous.clone_from(&text);
            terms.push(Term {
                text,
                title_count,
                author_count,
                titles,
                authors,
            });
        }
        terms
    }

    fn find(&self, bytes: &[u8], text: &str) -> Option<Term> {
        let index = last_at_most(&self.firsts, &text.to_string())?;
        self.block(bytes, index)
            .into_iter()
            .find(|term| term.text == text)
    }

    fn scan(&self, bytes: &[u8], prefix: &str) -> Vec<Term> {
        let start = self.firsts.partition_point(|first| first.as_str() < prefix);
        let blocks = (start.saturating_sub(1)..self.firsts.len())
            .take_while(|&index| {
                let first = &self.firsts[index];
                first.as_str() <= prefix || first.starts_with(prefix)
            })
            .flat_map(|index| self.block(bytes, index));
        blocks
            .filter(|term| term.text.starts_with(prefix) && term.text != prefix)
            .collect()
    }
}

struct Blocks {
    firsts: Vec<u32>,
    table: Range<usize>,
}

impl Blocks {
    fn new(bytes: &[u8], range: Range<usize>) -> Blocks {
        let section = &bytes[range.clone()];
        let count = u32_at(section, 0) as usize;
        let firsts = (0..count)
            .map(|index| u32_at(section, 4 + 4 * index))
            .collect();
        Blocks {
            firsts,
            table: range.start + 4 + 4 * count..range.end,
        }
    }

    fn inflated(&self, bytes: &[u8], block: usize, dictionary: &[u8]) -> Vec<u8> {
        inflate(table_entry(&bytes[self.table.clone()], block), dictionary)
    }
}

pub struct Segment {
    bytes: Vec<u8>,
    sections: HashMap<String, Range<usize>>,
    pub language: String,
    pub pack: String,
    pub month: String,
    pub base: String,
    pub author_count: usize,
    records_per_block: usize,
    terms_per_block: usize,
    pub tombstones: Vec<u32>,
    pub tags: Vec<Tag>,
    terms: TermBlocks,
    completion_keys: Vec<String>,
    gram_keys: Vec<String>,
    descriptions: Blocks,
    isbns: Option<Blocks>,
    library_covers: Option<Blocks>,
    cache: Mutex<HashMap<(usize, usize), Block>>,
}

impl Segment {
    pub fn parse(bytes: Vec<u8>) -> Option<Segment> {
        let sections = sections(&bytes)?;
        let meta = meta(&bytes[sections.get("meta")?.clone()]);
        let month = meta.get("month")?.clone();
        let words = &bytes[sections.get("tombstones")?.clone()];
        let tombstones = words
            .as_chunks::<4>()
            .0
            .iter()
            .map(|word| u32::from_le_bytes(*word))
            .collect();
        let tag_table = &bytes[sections.get("tags")?.clone()];
        let tags = (0..table_count(tag_table))
            .map(|id| {
                let mut reader = Reader::new(table_entry(tag_table, id));
                let (slug, label, category) = (reader.text(), reader.text(), reader.text());
                Tag {
                    id,
                    slug,
                    label,
                    category,
                }
            })
            .collect();
        let keys = |name: &str| {
            let table = &bytes[sections[name].clone()];
            (0..table_count(table))
                .map(|index| leading_text(table, index, 0))
                .collect()
        };
        sections.get("completions")?;
        sections.get("grams")?;
        let blocks = |name: &str| {
            sections
                .get(name)
                .map(|range| Blocks::new(&bytes, range.clone()))
        };
        Some(Segment {
            language: meta.get("language")?.clone(),
            pack: meta.get("pack")?.clone(),
            base: meta.get("base").unwrap_or(&month).clone(),
            author_count: meta
                .get("authors")
                .and_then(|count| count.parse().ok())
                .unwrap_or(0),
            records_per_block: meta.get("records_per_block")?.parse().ok()?,
            terms_per_block: meta.get("terms_per_block")?.parse().ok()?,
            tombstones,
            tags,
            terms: TermBlocks::new(&bytes, sections.get("terms")?.clone(), true),
            completion_keys: keys("completions"),
            gram_keys: keys("grams"),
            descriptions: blocks("descriptions")?,
            isbns: blocks("isbns"),
            library_covers: blocks("library_covers"),
            cache: Mutex::default(),
            month,
            sections,
            bytes,
        })
    }

    pub fn open(path: &Path) -> Option<Segment> {
        Segment::parse(std::fs::read(path).ok()?)
    }

    fn section(&self, name: &str) -> &[u8] {
        &self.bytes[self.sections[name].clone()]
    }

    pub fn is_base(&self) -> bool {
        self.base == self.month
    }

    pub fn work_count(&self) -> usize {
        self.section("works").len() / 4
    }

    pub fn work(&self, local: usize) -> u32 {
        u32_at(self.section("works"), 4 * local)
    }

    pub fn local_of(&self, work: u32) -> Option<usize> {
        let works = self.section("works");
        let (mut low, mut high) = (0, self.work_count());
        while low < high {
            let middle = (low + high) / 2;
            match u32_at(works, 4 * middle).cmp(&work) {
                Ordering::Less => low = middle + 1,
                Ordering::Greater => high = middle,
                Ordering::Equal => return Some(middle),
            }
        }
        None
    }

    fn dictionary(&self, table: &str) -> &[u8] {
        match table {
            "heads" => self.section("head_dictionary"),
            _ => &[],
        }
    }

    /// Inflated blocks by (table offset, block), forgotten in bulk like a small LRU.
    fn cached(&self, key: (usize, usize), inflate: impl FnOnce() -> Vec<u8>) -> Block {
        if let Some(found) = self.cache.lock().expect("block cache").get(&key) {
            return found.clone();
        }
        let raw = Arc::new(inflate());
        let mut cache = self.cache.lock().expect("block cache");
        if cache.len() >= CACHED_BLOCKS {
            cache.clear();
        }
        cache.insert(key, raw.clone());
        raw
    }

    fn indexed(&self, index: &Blocks, block: usize, dictionary: &[u8]) -> Block {
        let key = (index.table.start, block);
        self.cached(key, || index.inflated(&self.bytes, block, dictionary))
    }

    fn record(&self, table: &str, index: usize) -> Vec<u8> {
        let block = index / self.records_per_block;
        let raw = self.cached((self.sections[table].start, block), || {
            inflate(
                table_entry(self.section(table), block),
                self.dictionary(table),
            )
        });
        length_prefixed(&raw)[index % self.records_per_block].to_vec()
    }

    fn records(&self, table: &str) -> Vec<Vec<u8>> {
        let section = self.section(table);
        (0..table_count(section))
            .flat_map(|block| {
                let raw = inflate(table_entry(section, block), self.dictionary(table));
                length_prefixed(&raw)
                    .into_iter()
                    .map(<[u8]>::to_vec)
                    .collect::<Vec<_>>()
            })
            .collect()
    }

    pub fn facts(&self, local: usize) -> Facts {
        let raw = self.record("facts", local);
        let mut reader = Reader::new(&raw);
        let authors = (0..reader.varint()).map(|_| reader.varint()).collect();
        let year = match reader.varint() {
            0 => 0,
            offset => offset + YEAR_EPOCH,
        };
        let cover = reader.varint();
        let tags = (0..reader.varint()).map(|_| reader.byte()).collect();
        let series = (reader.varint() as usize).checked_sub(1);
        let position = if reader.has_more() {
            reader.varint()
        } else {
            0
        };
        Facts {
            authors,
            year,
            cover,
            tags,
            series,
            position,
        }
    }

    pub fn heads(&self, local: usize) -> Heads {
        Heads::parse(&self.record("heads", local))
    }

    pub fn all_heads(&self) -> Vec<Heads> {
        self.records("heads")
            .iter()
            .map(|raw| Heads::parse(raw))
            .collect()
    }

    pub fn author(&self, slot: u32) -> AuthorRecord {
        let raw = self.record("authors", slot as usize);
        let mut reader = Reader::new(&raw);
        AuthorRecord {
            number: reader.varint(),
            born: reader.varint(),
            name: reader.text(),
            works: postings(reader.rest()),
        }
    }

    pub fn series(&self, slot: usize) -> Series {
        let mut reader = Reader::new(table_entry(self.section("series"), slot));
        let name = reader.text();
        let members = (0..reader.varint()).map(|_| reader.varint()).collect();
        Series { name, members }
    }

    fn after_tag_labels(&self, tag: usize) -> Option<Reader<'_>> {
        let table = self.section("tags");
        if tag >= table_count(table) {
            return None;
        }
        let mut reader = Reader::new(table_entry(table, tag));
        for _ in 0..3 {
            let length = reader.varint() as usize;
            reader.take(length);
        }
        Some(reader)
    }

    pub fn tag_works(&self, tag: usize) -> Vec<u32> {
        let Some(mut reader) = self.after_tag_labels(tag) else {
            return Vec::new();
        };
        reader.varint();
        postings(reader.rest())
    }

    pub fn tag_count(&self, tag: usize) -> u32 {
        self.after_tag_labels(tag)
            .map_or(0, |mut reader| reader.varint())
    }

    pub fn description(&self, local: usize) -> Option<Description> {
        let index = &self.descriptions;
        let block = last_at_most(&index.firsts, &(local as u32))?;
        let raw = self.indexed(index, block, self.section("text_dictionary"));
        let mut reader = Reader::new(&raw);
        while reader.has_more() {
            let marked = reader.varint();
            let text = reader.text();
            if index.firsts[block] + (marked >> 1) == local as u32 {
                let translated = marked & 1 == 1;
                return Some(Description { text, translated });
            }
        }
        None
    }

    fn isbn_rows(&self, block: usize) -> Vec<(u32, usize)> {
        let index = self.isbns.as_ref().expect("isbn section");
        let raw = self.indexed(index, block, &[]);
        let mut reader = Reader::new(&raw);
        let mut isbn = index.firsts[block];
        let mut rows = Vec::new();
        while reader.has_more() {
            isbn += reader.varint();
            rows.push((isbn, reader.varint() as usize));
        }
        rows
    }

    pub fn local_of_isbn(&self, key: u32) -> Option<usize> {
        let block = last_at_most(&self.isbns.as_ref()?.firsts, &key)?;
        let rows = self.isbn_rows(block);
        rows.into_iter()
            .find(|&(isbn, _)| isbn == key)
            .map(|(_, local)| local)
    }

    pub fn isbns_of(&self, local: usize) -> Vec<u32> {
        let Some(index) = &self.isbns else {
            return Vec::new();
        };
        (0..index.firsts.len())
            .flat_map(|block| self.isbn_rows(block))
            .filter(|&(_, owner)| owner == local)
            .map(|(isbn, _)| isbn)
            .collect()
    }

    pub fn library_cover(&self, local: usize) -> Option<LibraryCover> {
        let index = self.library_covers.as_ref()?;
        let block = last_at_most(&index.firsts, &(local as u32))?;
        let raw = self.indexed(index, block, &[]);
        let mut reader = Reader::new(&raw);
        let mut covered = index.firsts[block];
        while reader.has_more() {
            covered += reader.varint();
            let source = reader.byte();
            let key = reader.varint();
            if covered == local as u32 {
                let key = if source == DIGAR {
                    key.to_string()
                } else {
                    isbn_of(key)
                };
                return Some(LibraryCover { source, key });
            }
        }
        None
    }

    pub fn term(&self, text: &str) -> Option<Term> {
        self.terms.find(&self.bytes, text)
    }

    pub fn term_by_id(&self, id: u32) -> Term {
        let id = id as usize;
        let mut block = self.terms.block(&self.bytes, id / self.terms_per_block);
        block.swap_remove(id % self.terms_per_block)
    }

    pub fn completions(&self, prefix: &str, limit: usize) -> Vec<Term> {
        let stored = COMPLETION_LENGTHS.contains(&prefix.encode_utf16().count());
        let position = stored
            .then(|| self.completion_keys.binary_search(&prefix.to_string()).ok())
            .flatten();
        let Some(position) = position else {
            return self.scan(prefix, limit);
        };
        let mut reader = Reader::new(table_entry(self.section("completions"), position));
        reader.text();
        let count = (reader.varint() as usize).min(limit);
        (0..count)
            .map(|_| self.term_by_id(reader.varint()))
            .collect()
    }

    fn scan(&self, prefix: &str, limit: usize) -> Vec<Term> {
        let mut found = self.terms.scan(&self.bytes, prefix);
        found.sort_by_key(|term| std::cmp::Reverse(term.frequency()));
        found.truncate(limit);
        found
    }

    pub fn gram_terms(&self, gram: &str) -> Vec<u32> {
        let Ok(position) = self.gram_keys.binary_search(&gram.to_string()) else {
            return Vec::new();
        };
        let mut reader = Reader::new(table_entry(self.section("grams"), position));
        reader.text();
        postings(reader.rest())
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Popularity {
    pub score: f64,
    pub readers: u32,
    pub ratings: u32,
    pub rating: f64,
    pub editions: u32,
}

pub struct Ranks {
    bytes: Vec<u8>,
    pub month: String,
    pub works: u32,
    terms: TermBlocks,
    popularity: Blocks,
    aliases: Vec<(u32, u32)>,
}

impl Ranks {
    pub fn parse(bytes: Vec<u8>) -> Option<Ranks> {
        let sections = sections(&bytes)?;
        let meta = meta(&bytes[sections.get("meta")?.clone()]);
        let pairs = sections
            .get("aliases")
            .map_or(&[][..], |range| &bytes[range.clone()]);
        let aliases = pairs
            .as_chunks::<8>()
            .0
            .iter()
            .map(|pair| (u32_at(pair, 0), u32_at(pair, 4)))
            .collect();
        Some(Ranks {
            month: meta.get("month").cloned().unwrap_or_default(),
            works: meta.get("works")?.parse().ok()?,
            terms: TermBlocks::new(&bytes, sections.get("terms")?.clone(), false),
            popularity: Blocks::new(&bytes, sections.get("popularity")?.clone()),
            aliases,
            bytes,
        })
    }

    pub fn open(path: &Path) -> Option<Ranks> {
        Ranks::parse(std::fs::read(path).ok()?)
    }

    pub fn frequency(&self, text: &str) -> u32 {
        self.terms
            .find(&self.bytes, text)
            .map_or(0, |term| term.frequency())
    }

    pub fn alias(&self, work: u32) -> Option<u32> {
        let index = self
            .aliases
            .binary_search_by_key(&work, |&(from, _)| from)
            .ok()?;
        Some(self.aliases[index].1)
    }

    pub fn aliases(&self) -> &[(u32, u32)] {
        &self.aliases
    }

    fn block(&self, index: usize) -> Vec<(u32, Popularity)> {
        let raw = self.popularity.inflated(&self.bytes, index, &[]);
        let mut reader = Reader::new(&raw);
        let mut work = self.popularity.firsts[index];
        let mut rows = Vec::new();
        while reader.has_more() {
            work += reader.varint();
            let score = f64::from(reader.varint()) / 10.0;
            let readers = reader.varint();
            let ratings = reader.varint();
            let rating = f64::from(reader.byte()) / 20.0;
            let editions = u32::from(reader.byte());
            rows.push((
                work,
                Popularity {
                    score,
                    readers,
                    ratings,
                    rating,
                    editions,
                },
            ));
        }
        rows
    }

    pub fn popularity(&self, work: u32) -> Option<Popularity> {
        let index = last_at_most(&self.popularity.firsts, &work)?;
        let rows = self.block(index);
        let position = rows.binary_search_by_key(&work, |&(known, _)| known).ok()?;
        Some(rows[position].1.clone())
    }

    pub fn rows(&self) -> Vec<(u32, Popularity)> {
        (0..self.popularity.firsts.len())
            .flat_map(|index| self.block(index))
            .collect()
    }
}

fn label_parts(label: &str) -> Vec<u32> {
    label
        .split(['-', '.'])
        .map(|part| part.parse().unwrap_or(0))
        .collect()
}

pub fn release_order(left: &str, right: &str) -> Ordering {
    label_parts(left).cmp(&label_parts(right))
}

fn current_chain(chain: Vec<Segment>) -> Vec<Segment> {
    let bases = chain.iter().filter(|segment| segment.is_base());
    let Some(base) = bases
        .map(|segment| segment.month.clone())
        .max_by(|a, b| release_order(a, b))
    else {
        return Vec::new();
    };
    let current = |segment: &Segment| {
        segment.base == base && release_order(&segment.month, &base) != Ordering::Less
    };
    chain.into_iter().filter(current).collect()
}

pub fn current_segments(segments: Vec<Segment>) -> Vec<Segment> {
    let mut chains: Vec<(String, Vec<Segment>)> = Vec::new();
    for segment in segments {
        match chains.iter_mut().find(|(pack, _)| *pack == segment.pack) {
            Some((_, chain)) => chain.push(segment),
            None => chains.push((segment.pack.clone(), vec![segment])),
        }
    }
    let mut current: Vec<Segment> = chains
        .into_iter()
        .flat_map(|(_, chain)| current_chain(chain))
        .collect();
    let pack_order = |segment: &Segment| PACKS.iter().position(|&pack| pack == segment.pack);
    current.sort_by(|a, b| {
        release_order(&a.month, &b.month)
            .then(a.is_base().cmp(&b.is_base()).reverse())
            .then(pack_order(a).cmp(&pack_order(b)))
    });
    current
}

static SEGMENT_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^([a-z]{2})-([a-z-]+)-(\d{4}-\d{2}(?:-\d{2}(?:-\d+)?)?)\.bin$")
        .expect("segment name pattern")
});

/// `xx-pack-YYYY-MM-DD[-N].bin` → (language, pack, release).
pub fn parse_name(name: &str) -> Option<(&str, &str, &str)> {
    let found = SEGMENT_NAME.captures(name)?;
    let part = |index| found.get(index).map(|part| part.as_str());
    Some((part(1)?, part(2)?, part(3)?))
}

#[derive(Clone, Debug, PartialEq)]
pub struct Author {
    pub number: u32,
    pub name: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Book {
    pub work: u32,
    pub title: String,
    pub subtitle: String,
    pub alternate: String,
    pub authors: Vec<Author>,
    pub year: u32,
    pub cover: u32,
    pub tags: Vec<u8>,
    pub library_cover: Option<LibraryCover>,
    pub language: String,
}

impl Book {
    pub fn is_companion(&self) -> bool {
        is_companion(&format!("{} {}", self.title, self.subtitle)) || is_omnibus(&self.title)
    }
}

pub struct Catalogue {
    pub language: String,
    pub segments: Vec<Segment>,
    pub ranks: Option<Ranks>,
    visible: Vec<Vec<bool>>,
    works: Vec<u32>,
    scores: Vec<f32>,
    max_score: f64,
}

fn visibility(segments: &[Segment]) -> Vec<Vec<bool>> {
    let mut entries: Vec<(u32, usize, usize)> = Vec::new();
    for (index, segment) in segments.iter().enumerate() {
        entries
            .extend((0..segment.work_count()).map(|local| (segment.work(local), index, local + 1)));
        entries.extend(segment.tombstones.iter().map(|&work| (work, index, 0)));
    }
    entries.sort_unstable();
    let mut visible: Vec<Vec<bool>> = segments
        .iter()
        .map(|segment| vec![false; segment.work_count()])
        .collect();
    for same in entries.chunk_by(|a, b| a.0 == b.0) {
        let &(_, segment, local) = same.last().expect("entry");
        if local > 0 {
            visible[segment][local - 1] = true;
        }
    }
    visible
}

impl Catalogue {
    pub fn new(language: &str, segments: Vec<Segment>, ranks: Option<Ranks>) -> Catalogue {
        let visible = visibility(&segments);
        let mut works: Vec<u32> = segments
            .iter()
            .zip(&visible)
            .flat_map(|(segment, bits)| {
                let shown = bits.iter().enumerate().filter(|(_, shown)| **shown);
                shown
                    .map(|(local, _)| segment.work(local))
                    .collect::<Vec<_>>()
            })
            .collect();
        works.sort_unstable();
        let mut scores = vec![0.0f32; works.len()];
        for (work, popularity) in ranks.iter().flat_map(Ranks::rows) {
            if let Ok(index) = works.binary_search(&work) {
                scores[index] = popularity.score as f32;
            }
        }
        let best = scores.iter().copied().fold(0.0f32, f32::max);
        let max_score = if best > 0.0 { f64::from(best) } else { 1.0 };
        Catalogue {
            language: language.to_string(),
            segments,
            ranks,
            visible,
            works,
            scores,
            max_score,
        }
    }

    /// The current chain of every pack plus the newest ranks in `directory`, like the app.
    pub fn open(directory: &Path, language: &str) -> std::io::Result<Catalogue> {
        let mut segments = Vec::new();
        let mut ranks: Vec<(String, std::path::PathBuf)> = Vec::new();
        for entry in std::fs::read_dir(directory)? {
            let path = entry?.path();
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default();
            let Some((code, pack, release)) = parse_name(name) else {
                continue;
            };
            if code != language {
                continue;
            }
            if pack == "ranks" {
                ranks.push((release.to_string(), path.clone()));
                continue;
            }
            segments.extend(Segment::open(&path));
        }
        let newest = ranks.into_iter().max_by(|a, b| release_order(&a.0, &b.0));
        let ranks = newest.and_then(|(_, path)| Ranks::open(&path));
        Ok(Catalogue::new(language, current_segments(segments), ranks))
    }

    pub fn work_count(&self) -> u32 {
        self.works.len() as u32
    }

    pub fn works(&self) -> &[u32] {
        &self.works
    }

    pub fn is_visible(&self, segment: usize, local: usize) -> bool {
        self.visible[segment][local]
    }

    fn find(&self, work: u32) -> Option<(usize, usize)> {
        for (index, segment) in self.segments.iter().enumerate().rev() {
            if let Some(local) = segment.local_of(work) {
                return Some((index, local));
            }
            if segment.tombstones.binary_search(&work).is_ok() {
                return None;
            }
        }
        None
    }

    pub fn alias(&self, work: u32) -> Option<u32> {
        self.ranks.as_ref()?.alias(work)
    }

    pub fn locate(&self, work: u32) -> Option<(usize, usize)> {
        self.find(work).or_else(|| self.find(self.alias(work)?))
    }

    pub fn score(&self, work: u32) -> f64 {
        self.works
            .binary_search(&work)
            .map_or(0.0, |index| f64::from(self.scores[index]))
    }

    pub fn popularity(&self, work: u32) -> f64 {
        (1.0 + self.score(work)).ln() / (1.0 + self.max_score).ln()
    }

    pub fn book_at(&self, segment: usize, local: usize) -> Book {
        let source = &self.segments[segment];
        let heads = source.heads(local);
        let facts = source.facts(local);
        let authors = facts
            .authors
            .iter()
            .map(|&slot| source.author(slot))
            .map(|record| Author {
                number: record.number,
                name: record.name,
            })
            .collect();
        Book {
            work: source.work(local),
            title: heads.title,
            subtitle: heads.subtitle,
            alternate: heads.alternate,
            authors,
            year: facts.year,
            cover: facts.cover,
            tags: facts.tags,
            library_cover: source.library_cover(local),
            language: self.language.clone(),
        }
    }

    pub fn book(&self, work: u32) -> Option<Book> {
        let (segment, local) = self.locate(work)?;
        Some(self.book_at(segment, local))
    }

    pub fn book_of_isbn(&self, key: u32) -> Option<Book> {
        self.segments.iter().rev().find_map(|segment| {
            let local = segment.local_of_isbn(key)?;
            self.book(segment.work(local))
        })
    }

    pub fn facts(&self, work: u32) -> Option<Facts> {
        let (segment, local) = self.locate(work)?;
        Some(self.segments[segment].facts(local))
    }

    pub fn description(&self, work: u32) -> Option<Description> {
        let (segment, local) = self.locate(work)?;
        self.segments[segment].description(local)
    }

    pub fn series(&self, work: u32) -> Option<Series> {
        let (segment, local) = self.locate(work)?;
        let slot = self.segments[segment].facts(local).series?;
        Some(self.segments[segment].series(slot))
    }

    pub fn visible_works(&self, segment: usize, locals: &[u32]) -> Vec<u32> {
        let bits = &self.visible[segment];
        let shown = locals.iter().filter(|&&local| bits[local as usize]);
        shown
            .map(|&local| self.segments[segment].work(local as usize))
            .collect()
    }
}
