use crate::catalog::tokenize;
use crate::dumps::Authors;
use crate::release::Series;
use crate::tags;
use flate2::{Compress, Compression, FlushCompress, Status};
use std::collections::{BTreeMap, HashMap};
use std::ops::RangeInclusive;

const MAGIC: &[u8; 4] = b"SHLF";
pub const VERSION: u32 = 1;
const NAME_BYTES: usize = 16;
const RECORDS_PER_BLOCK: usize = 32;
const TERMS_PER_BLOCK: usize = 16;
const RANKS_PER_BLOCK: usize = 4096;
const DICTIONARY_BYTES: usize = 1 << 15;
const DICTIONARY_SAMPLES: usize = 6000;
const DESCRIPTION_BLOCK_BYTES: usize = 9000;
const COMPLETION_LENGTHS: RangeInclusive<usize> = 2..=4;
const COMPLETION_THRESHOLD: usize = 48;
const COMPLETION_LIMIT: usize = 12;
const YEAR_EPOCH: u32 = 1400;
const SEPARATOR: &str = "\u{1f}";

#[derive(Clone, Copy)]
pub struct Work<'a> {
    pub id: u32,
    pub title: &'a str,
    pub subtitle: &'a str,
    pub alternate: &'a str,
    pub authors: &'a [u32],
    pub year: u16,
    pub cover: u32,
    pub tags: &'a [u8],
    pub series: Option<(&'a Series, u16)>,
    pub description: &'a str,
}

pub struct Meta<'a> {
    pub language: usize,
    pub pack: &'a str,
    pub month: &'a str,
    pub base: &'a str,
}

pub struct Popularity {
    pub id: u32,
    pub score: f32,
    pub readers: u32,
    pub ratings: u16,
    pub mean_rating: f32,
    pub editions: u16,
}

fn put_u32(buffer: &mut Vec<u8>, value: u32) {
    buffer.extend_from_slice(&value.to_le_bytes());
}

fn put_varint(buffer: &mut Vec<u8>, mut value: u32) {
    while value >= 0x80 {
        buffer.push(value as u8 | 0x80);
        value >>= 7;
    }
    buffer.push(value as u8);
}

fn put_text(buffer: &mut Vec<u8>, text: &str) {
    put_varint(buffer, text.len() as u32);
    buffer.extend_from_slice(text.as_bytes());
}

fn postings(ascending: &[u32]) -> Vec<u8> {
    let mut buffer = Vec::with_capacity(ascending.len() + 4);
    let mut previous = 0;
    for &value in ascending {
        put_varint(&mut buffer, value - previous);
        previous = value;
    }
    buffer
}

fn raw_u32s(values: impl Iterator<Item = u32>) -> Vec<u8> {
    values.flat_map(u32::to_le_bytes).collect()
}

fn offset_table(chunks: &[Vec<u8>]) -> Vec<u8> {
    let mut table = Vec::new();
    put_u32(&mut table, chunks.len() as u32);
    put_u32(&mut table, 0);
    let mut offset = 0;
    for chunk in chunks {
        offset += chunk.len() as u32;
        put_u32(&mut table, offset);
    }
    chunks
        .iter()
        .for_each(|chunk| table.extend_from_slice(chunk));
    table
}

fn deflate(raw: &[u8], dictionary: &[u8]) -> Vec<u8> {
    let mut compressor = Compress::new(Compression::best(), false);
    if !dictionary.is_empty() {
        compressor
            .set_dictionary(dictionary)
            .expect("set dictionary");
    }
    let mut compressed = Vec::with_capacity(raw.len() + raw.len() / 8 + 64);
    let status = compressor.compress_vec(raw, &mut compressed, FlushCompress::Finish);
    assert_eq!(
        status.expect("deflate"),
        Status::StreamEnd,
        "deflate output truncated"
    );
    compressed
}

fn dictionary(samples: &[Vec<u8>]) -> Vec<u8> {
    let stride = (samples.len() / DICTIONARY_SAMPLES).max(1);
    let mut dictionary = Vec::with_capacity(DICTIONARY_BYTES);
    for sample in samples.iter().step_by(stride) {
        if dictionary.len() + sample.len() > DICTIONARY_BYTES {
            break;
        }
        dictionary.extend_from_slice(sample);
    }
    dictionary
}

fn record_blocks(records: &[Vec<u8>], dictionary: &[u8]) -> Vec<u8> {
    let blocks: Vec<Vec<u8>> = records
        .chunks(RECORDS_PER_BLOCK)
        .map(|block| {
            let mut raw = Vec::new();
            for record in block {
                put_varint(&mut raw, record.len() as u32);
                raw.extend_from_slice(record);
            }
            deflate(&raw, dictionary)
        })
        .collect();
    offset_table(&blocks)
}

fn assemble(sections: Vec<(&str, Vec<u8>)>) -> Vec<u8> {
    let mut file = Vec::new();
    file.extend_from_slice(MAGIC);
    put_u32(&mut file, VERSION);
    put_u32(&mut file, sections.len() as u32);
    let mut offset = (12 + sections.len() * (NAME_BYTES + 8)) as u32;
    for (name, bytes) in &sections {
        let mut padded = [0u8; NAME_BYTES];
        padded[..name.len()].copy_from_slice(name.as_bytes());
        file.extend_from_slice(&padded);
        put_u32(&mut file, offset);
        put_u32(&mut file, bytes.len() as u32);
        offset += bytes.len() as u32;
    }
    sections
        .into_iter()
        .for_each(|(_, bytes)| file.extend(bytes));
    file
}

fn heads(work: &Work) -> Vec<u8> {
    let fields = [work.title, work.subtitle, work.alternate];
    let used = fields
        .iter()
        .rposition(|field| !field.is_empty())
        .map_or(1, |last| last + 1);
    fields[..used].join(SEPARATOR).into_bytes()
}

fn facts(work: &Work, author_slots: &[u32], series_slot: Option<u32>) -> Vec<u8> {
    let mut record = Vec::with_capacity(24 + work.tags.len());
    put_varint(&mut record, author_slots.len() as u32);
    author_slots
        .iter()
        .for_each(|&slot| put_varint(&mut record, slot));
    put_varint(&mut record, (work.year as u32).saturating_sub(YEAR_EPOCH));
    put_varint(&mut record, work.cover);
    put_varint(&mut record, work.tags.len() as u32);
    record.extend_from_slice(work.tags);
    put_varint(&mut record, series_slot.map_or(0, |slot| slot + 1));
    put_varint(
        &mut record,
        work.series.map_or(0, |(_, order)| order as u32),
    );
    record
}

pub fn content_hash(work: &Work, authors: &Authors) -> u64 {
    let mut bytes = heads(work);
    let names = work.authors.iter().filter_map(|&id| authors.get(id));
    names.for_each(|author| put_text(&mut bytes, author.name));
    bytes.extend(facts(work, work.authors, None));
    if let Some((series, _)) = work.series {
        put_text(&mut bytes, &series.name);
        series
            .members
            .iter()
            .for_each(|&member| put_u32(&mut bytes, member));
    }
    put_text(&mut bytes, work.description);
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |hash, &byte| {
        (hash ^ byte as u64).wrapping_mul(0x0100_0000_01b3)
    })
}

#[derive(Default)]
struct Postings {
    titles: Vec<u32>,
    authors: Vec<u32>,
}

fn push_unique(list: &mut Vec<u32>, value: u32) {
    if list.last() != Some(&value) {
        list.push(value);
    }
}

fn collect_terms(
    works: &[Work],
    author_order: &[u32],
    authors: &Authors,
) -> BTreeMap<String, Postings> {
    let mut terms: BTreeMap<String, Postings> = BTreeMap::new();
    for (index, work) in works.iter().enumerate() {
        for token in tokenize(work.title)
            .into_iter()
            .chain(tokenize(work.alternate))
        {
            push_unique(&mut terms.entry(token).or_default().titles, index as u32);
        }
    }
    for (slot, &id) in author_order.iter().enumerate() {
        let Some(author) = authors.get(id) else {
            continue;
        };
        for token in tokenize(author.name) {
            push_unique(&mut terms.entry(token).or_default().authors, slot as u32);
        }
    }
    terms
}

fn shared_prefix(left: &str, right: &str) -> usize {
    let common = left
        .bytes()
        .zip(right.bytes())
        .take_while(|(a, b)| a == b)
        .count();
    (0..=common)
        .rev()
        .find(|&length| right.is_char_boundary(length))
        .unwrap_or(0)
}

fn term_blocks(terms: &BTreeMap<String, Postings>, with_postings: bool) -> Vec<u8> {
    let entries: Vec<(&String, &Postings)> = terms.iter().collect();
    let blocks: Vec<Vec<u8>> = entries
        .chunks(TERMS_PER_BLOCK)
        .map(|block| {
            let mut raw = Vec::new();
            let mut previous = "";
            for (text, lists) in block {
                let shared = shared_prefix(previous, text);
                put_varint(&mut raw, shared as u32);
                put_text(&mut raw, &text[shared..]);
                put_varint(&mut raw, lists.titles.len() as u32);
                put_varint(&mut raw, lists.authors.len() as u32);
                if with_postings {
                    let (titles, authors) =
                        (postings(&lists.titles), postings(&lists.authors));
                    put_varint(&mut raw, titles.len() as u32);
                    put_varint(&mut raw, authors.len() as u32);
                    raw.extend(titles);
                    raw.extend(authors);
                }
                previous = text;
            }
            raw
        })
        .collect();
    offset_table(&blocks)
}

fn trigrams(term: &str) -> Vec<String> {
    let characters: Vec<char> = format!("${term}$").chars().collect();
    characters
        .windows(3)
        .map(|window| window.iter().collect())
        .collect()
}

fn gram_section(terms: &BTreeMap<String, Postings>) -> Vec<u8> {
    let mut grams: BTreeMap<String, Vec<u32>> = BTreeMap::new();
    for (id, (text, lists)) in terms.iter().enumerate() {
        if lists.titles.len() + lists.authors.len() < 2 || text.len() < 3 {
            continue;
        }
        for gram in trigrams(text) {
            push_unique(grams.entry(gram).or_default(), id as u32);
        }
    }
    let records: Vec<Vec<u8>> = grams
        .iter()
        .map(|(gram, ids)| {
            let mut record = Vec::new();
            put_text(&mut record, gram);
            record.extend(postings(ids));
            record
        })
        .collect();
    offset_table(&records)
}

fn completion_section(terms: &BTreeMap<String, Postings>) -> Vec<u8> {
    let mut by_prefix: BTreeMap<&str, Vec<(usize, u32)>> = BTreeMap::new();
    for (id, (text, lists)) in terms.iter().enumerate() {
        let frequency = lists.titles.len() + lists.authors.len();
        for length in COMPLETION_LENGTHS.filter(|&length| length < text.len()) {
            if text.is_char_boundary(length) {
                by_prefix
                    .entry(&text[..length])
                    .or_default()
                    .push((frequency, id as u32));
            }
        }
    }
    let records: Vec<Vec<u8>> = by_prefix
        .into_iter()
        .filter(|(_, candidates)| candidates.len() > COMPLETION_THRESHOLD)
        .map(|(prefix, mut candidates)| {
            candidates.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
            let mut record = Vec::new();
            put_text(&mut record, prefix);
            put_varint(&mut record, COMPLETION_LIMIT as u32);
            let chosen = candidates.iter().take(COMPLETION_LIMIT);
            chosen.for_each(|&(_, id)| put_varint(&mut record, id));
            record
        })
        .collect();
    offset_table(&records)
}

fn description_section(works: &[Work], dictionary: &[u8]) -> Vec<u8> {
    let mut firsts = Vec::new();
    let mut blocks = Vec::new();
    let mut raw = Vec::new();
    let described = works
        .iter()
        .enumerate()
        .filter(|(_, work)| !work.description.is_empty());
    for (index, work) in described {
        if raw.is_empty() {
            firsts.push(index as u32);
        }
        put_varint(
            &mut raw,
            index as u32 - firsts.last().expect("block started"),
        );
        put_text(&mut raw, work.description);
        if raw.len() >= DESCRIPTION_BLOCK_BYTES {
            blocks.push(deflate(&std::mem::take(&mut raw), dictionary));
        }
    }
    if !raw.is_empty() {
        blocks.push(deflate(&raw, dictionary));
    }
    let mut section = Vec::new();
    put_u32(&mut section, blocks.len() as u32);
    firsts
        .iter()
        .for_each(|&first| put_u32(&mut section, first));
    section.extend(offset_table(&blocks));
    section
}

fn tag_section(works: &[Work], language: usize) -> Vec<u8> {
    let mut tagged: Vec<Vec<u32>> = vec![Vec::new(); tags::rules().len()];
    for (index, work) in works.iter().enumerate() {
        work.tags
            .iter()
            .for_each(|&tag| push_unique(&mut tagged[tag as usize], index as u32));
    }
    let records: Vec<Vec<u8>> = tags::rules()
        .iter()
        .zip(&tagged)
        .map(|(rule, members)| {
            let mut record = Vec::new();
            put_text(&mut record, &rule.slug);
            put_text(&mut record, &rule.labels[crate::LANGUAGES[language]]);
            put_text(&mut record, rule.category.name());
            put_varint(&mut record, members.len() as u32);
            record.extend(postings(members));
            record
        })
        .collect();
    offset_table(&records)
}

fn author_section(
    works: &[Work],
    order: &[u32],
    slots: &HashMap<u32, u32>,
    authors: &Authors,
) -> Vec<u8> {
    let mut written: Vec<Vec<u32>> = vec![Vec::new(); order.len()];
    for (index, work) in works.iter().enumerate() {
        for id in work.authors {
            push_unique(&mut written[slots[id] as usize], index as u32);
        }
    }
    let records: Vec<Vec<u8>> = order
        .iter()
        .zip(&written)
        .map(|(&id, members)| {
            let author = authors.get(id);
            let mut record = Vec::new();
            put_varint(&mut record, id);
            put_varint(
                &mut record,
                author.as_ref().map_or(0, |author| author.birth as u32),
            );
            put_text(
                &mut record,
                author.as_ref().map_or("", |author| author.name),
            );
            record.extend(postings(members));
            record
        })
        .collect();
    record_blocks(&records, &[])
}

fn series_section(series: &[&Series]) -> Vec<u8> {
    let records: Vec<Vec<u8>> = series
        .iter()
        .map(|entry| {
            let mut record = Vec::new();
            put_text(&mut record, &entry.name);
            put_varint(&mut record, entry.members.len() as u32);
            entry
                .members
                .iter()
                .for_each(|&member| put_varint(&mut record, member));
            record
        })
        .collect();
    offset_table(&records)
}

fn meta_section(lines: &[(&str, String)]) -> Vec<u8> {
    lines
        .iter()
        .map(|(key, value)| format!("{key}={value}\n"))
        .collect::<String>()
        .into_bytes()
}

fn author_slots(works: &[Work]) -> (Vec<u32>, HashMap<u32, u32>) {
    let mut order = Vec::new();
    let mut slots = HashMap::new();
    for &id in works.iter().flat_map(|work| work.authors) {
        slots.entry(id).or_insert_with(|| {
            order.push(id);
            order.len() as u32 - 1
        });
    }
    (order, slots)
}

fn series_slots<'a>(works: &[Work<'a>]) -> (Vec<&'a Series>, Vec<Option<u32>>) {
    let mut listed: Vec<&Series> = Vec::new();
    let mut known: HashMap<*const Series, u32> = HashMap::new();
    let slots = works
        .iter()
        .map(|work| {
            let (series, _) = work.series?;
            Some(*known.entry(series).or_insert_with(|| {
                listed.push(series);
                listed.len() as u32 - 1
            }))
        })
        .collect();
    (listed, slots)
}

pub fn segment(
    meta: &Meta,
    works: &[Work],
    tombstones: &[u32],
    authors: &Authors,
) -> Vec<u8> {
    let (order, slots) = author_slots(works);
    let (series, series_of) = series_slots(works);
    let fact_records: Vec<Vec<u8>> = works
        .iter()
        .zip(&series_of)
        .map(|(work, &series_slot)| {
            let local: Vec<u32> = work.authors.iter().map(|id| slots[id]).collect();
            facts(work, &local, series_slot)
        })
        .collect();
    let head_records: Vec<Vec<u8>> = works.iter().map(heads).collect();
    let head_dictionary = dictionary(&head_records);
    let descriptions: Vec<Vec<u8>> = works
        .iter()
        .map(|work| work.description.as_bytes().to_vec())
        .collect();
    let text_dictionary = dictionary(&descriptions);
    let terms = collect_terms(works, &order, authors);
    let meta_lines = [
        ("format", VERSION.to_string()),
        ("language", crate::LANGUAGES[meta.language].to_string()),
        ("pack", meta.pack.to_string()),
        ("month", meta.month.to_string()),
        ("base", meta.base.to_string()),
        ("works", works.len().to_string()),
        ("authors", order.len().to_string()),
        ("terms", terms.len().to_string()),
        ("records_per_block", RECORDS_PER_BLOCK.to_string()),
        ("terms_per_block", TERMS_PER_BLOCK.to_string()),
    ];
    assemble(vec![
        ("meta", meta_section(&meta_lines)),
        ("works", raw_u32s(works.iter().map(|work| work.id))),
        ("tombstones", raw_u32s(tombstones.iter().copied())),
        ("head_dictionary", head_dictionary.clone()),
        ("text_dictionary", text_dictionary.clone()),
        ("facts", record_blocks(&fact_records, &[])),
        ("heads", record_blocks(&head_records, &head_dictionary)),
        ("authors", author_section(works, &order, &slots, authors)),
        ("tags", tag_section(works, meta.language)),
        ("series", series_section(&series)),
        ("terms", term_blocks(&terms, true)),
        ("completions", completion_section(&terms)),
        ("grams", gram_section(&terms)),
        ("descriptions", description_section(works, &text_dictionary)),
    ])
}

fn popularity_block(block: &[Popularity]) -> Vec<u8> {
    let mut raw = Vec::with_capacity(block.len() * 8);
    let mut previous = block[0].id;
    for row in block {
        put_varint(&mut raw, row.id - previous);
        put_varint(&mut raw, (row.score * 10.0).round().max(0.0) as u32);
        put_varint(&mut raw, row.readers);
        put_varint(&mut raw, u32::from(row.ratings));
        raw.push((row.mean_rating * 20.0).round() as u8);
        raw.push(row.editions.min(255) as u8);
        previous = row.id;
    }
    deflate(&raw, &[])
}

fn popularity_section(rows: &[Popularity]) -> Vec<u8> {
    let blocks: Vec<&[Popularity]> = rows.chunks(RANKS_PER_BLOCK).collect();
    let mut section = Vec::new();
    put_u32(&mut section, blocks.len() as u32);
    blocks
        .iter()
        .for_each(|block| put_u32(&mut section, block[0].id));
    let compressed: Vec<Vec<u8>> =
        blocks.iter().map(|block| popularity_block(block)).collect();
    section.extend(offset_table(&compressed));
    section
}

pub fn ranks(
    language: usize,
    month: &str,
    works: &[Work],
    rows: &[Popularity],
    authors: &Authors,
) -> Vec<u8> {
    let (order, _) = author_slots(works);
    let terms = collect_terms(works, &order, authors);
    let meta_lines = [
        ("format", VERSION.to_string()),
        ("language", crate::LANGUAGES[language].to_string()),
        ("pack", "ranks".to_string()),
        ("month", month.to_string()),
        ("works", rows.len().to_string()),
        ("authors", order.len().to_string()),
        ("terms", terms.len().to_string()),
        ("terms_per_block", TERMS_PER_BLOCK.to_string()),
        ("ranks_per_block", RANKS_PER_BLOCK.to_string()),
    ];
    assemble(vec![
        ("meta", meta_section(&meta_lines)),
        ("popularity", popularity_section(rows)),
        ("terms", term_blocks(&terms, false)),
    ])
}
