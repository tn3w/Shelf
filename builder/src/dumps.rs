use crate::catalog::{self, Traits};
use crate::tags::{self, Classes};
use flate2::read::MultiGzDecoder;
use serde_json::Value;
use std::fs::File;
use std::io::{self, BufReader, Read};
use std::path::Path;
use std::sync::{Mutex, mpsc};
use std::thread;
use std::time::Duration;

const BLOCK_BYTES: usize = 8 << 20;
const MAX_RETRIES: u32 = 12;
const MAX_TITLE_BYTES: usize = 160;
const SUBTITLE_SEPARATOR: char = '\u{1f}';
const MAX_AUTHORS: usize = 4;
const NON_PRINT_FORMATS: [&str; 9] = [
    "audio",
    "cassette",
    "cd",
    "mp3",
    "braille",
    "player",
    "sound",
    "ebook",
    "electronic",
];
const MIN_SCAN_WIDTH: f32 = 700.0;
const TARGET_CODES: [&str; 4] = ["eng", "ger", "fre", "spa"];
const LANGUAGE_CODES: [&str; 24] = [
    "eng", "ger", "fre", "spa", "ita", "rus", "por", "dut", "jpn", "chi", "pol", "swe",
    "ara", "heb", "cze", "dan", "nor", "fin", "tur", "kor", "gre", "hun", "lat", "ind",
];
const ISBN_GROUPS: [(&str, usize); 14] = [
    ("9780", 0), ("9781", 0), ("9798", 0), ("9783", 1), ("9782", 2), ("97910", 2),
    ("97884", 3), ("978607", 3), ("978950", 3), ("978956", 3), ("978958", 3),
    ("978968", 3), ("978970", 3), ("978987", 3),
];

type CoverShape = (u16, u16);

pub const FLAG_ISBN: u8 = 1;
pub const FLAG_COVER: u8 = 2;
pub const FLAG_PUBLISHER: u8 = 4;
pub const FLAG_READABLE: u8 = 8;

struct Download {
    url: String,
    offset: u64,
    failures: u32,
    body: Option<ureq::BodyReader<'static>>,
}

impl Download {
    fn connect(&self) -> io::Result<ureq::BodyReader<'static>> {
        let response = ureq::get(&self.url)
            .header("Range", format!("bytes={}-", self.offset))
            .call()
            .map_err(io::Error::other)?;
        if self.offset > 0 && response.status() != 206 {
            return Err(io::Error::other("server ignored range request"));
        }
        Ok(response.into_body().into_reader())
    }

    fn try_read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.body.is_none() {
            self.body = Some(self.connect()?);
        }
        self.body.as_mut().expect("connected").read(buffer)
    }
}

impl Read for Download {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        loop {
            match self.try_read(buffer) {
                Ok(count) => {
                    self.offset += count as u64;
                    self.failures = 0;
                    return Ok(count);
                }
                Err(error) if self.failures < MAX_RETRIES => {
                    eprintln!("{} at byte {}: {error}; retrying", self.url, self.offset);
                    self.failures += 1;
                    self.body = None;
                    thread::sleep(Duration::from_secs(2u64.pow(self.failures.min(6))));
                }
                Err(error) => return Err(error),
            }
        }
    }
}

fn open(source: &str, name: &str) -> Box<dyn Read + Send> {
    let latest = format!("ol_dump_{name}_latest.txt.gz");
    if source.starts_with("http://") || source.starts_with("https://") {
        let url = format!("{}/{latest}", source.trim_end_matches('/'));
        let download = Download {
            url,
            offset: 0,
            failures: 0,
            body: None,
        };
        return Box::new(MultiGzDecoder::new(BufReader::with_capacity(
            1 << 20,
            download,
        )));
    }
    let path = [latest, format!("ol_dump_{name}.txt.gz")]
        .into_iter()
        .map(|file_name| Path::new(source).join(file_name))
        .find(|path| path.exists())
        .unwrap_or_else(|| panic!("no {name} dump in {source}"));
    let file = File::open(path).expect("open dump");
    Box::new(MultiGzDecoder::new(BufReader::with_capacity(1 << 20, file)))
}

fn fill(reader: &mut impl Read, buffer: &mut [u8]) -> usize {
    let mut filled = 0;
    while filled < buffer.len() {
        match reader.read(&mut buffer[filled..]).expect("read dump") {
            0 => break,
            count => filled += count,
        }
    }
    filled
}

fn read_blocks(mut reader: impl Read, sender: mpsc::SyncSender<Vec<u8>>) {
    let mut carry = Vec::new();
    loop {
        let mut block = std::mem::take(&mut carry);
        let start = block.len();
        block.resize(start + BLOCK_BYTES, 0);
        let filled = start + fill(&mut reader, &mut block[start..]);
        block.truncate(filled);
        if filled == start {
            if !block.is_empty() {
                let _ = sender.send(block);
            }
            return;
        }
        let Some(last_newline) = block.iter().rposition(|&byte| byte == b'\n') else {
            carry = block;
            continue;
        };
        carry = block.split_off(last_newline + 1);
        if sender.send(block).is_err() {
            return;
        }
    }
}

fn worker_count() -> usize {
    thread::available_parallelism().map_or(4, |count| count.get())
}

fn scan<T: Send>(
    source: &str,
    name: &str,
    parse: impl Fn(&[u8]) -> Option<T> + Sync,
    mut absorb: impl FnMut(T),
) {
    let workers = worker_count();
    let (block_sender, block_receiver) = mpsc::sync_channel::<Vec<u8>>(workers);
    let (item_sender, item_receiver) = mpsc::sync_channel::<Vec<T>>(workers);
    let blocks = Mutex::new(block_receiver);
    thread::scope(|scope| {
        scope.spawn(|| read_blocks(open(source, name), block_sender));
        for _ in 0..workers {
            let item_sender = item_sender.clone();
            let (blocks, parse) = (&blocks, &parse);
            scope.spawn(move || {
                loop {
                    let next = blocks.lock().expect("block queue").recv();
                    let Ok(block) = next else { return };
                    let lines = block.split(|&byte| byte == b'\n');
                    let items = lines.filter(|line| !line.is_empty()).filter_map(parse);
                    if item_sender.send(items.collect()).is_err() {
                        return;
                    }
                }
            });
        }
        drop(item_sender);
        for items in item_receiver {
            items.into_iter().for_each(&mut absorb);
        }
    });
    eprintln!("scanned {name}");
}

fn field(line: &[u8], index: usize) -> &[u8] {
    line.split(|&byte| byte == b'\t')
        .nth(index)
        .unwrap_or_default()
}

fn json(line: &[u8]) -> Option<Value> {
    serde_json::from_slice(line.rsplit(|&byte| byte == b'\t').next()?).ok()
}

pub fn ol_id(key: &[u8]) -> Option<u32> {
    let start = key.windows(2).position(|pair| pair == b"OL")? + 2;
    let digits = key[start..]
        .iter()
        .take_while(|byte| byte.is_ascii_digit())
        .count();
    std::str::from_utf8(&key[start..start + digits])
        .ok()?
        .parse()
        .ok()
}

fn slot<T: Clone + Default>(values: &mut Vec<T>, id: u32) -> &mut T {
    let index = id as usize;
    if index >= values.len() {
        values.reserve_exact(index + 1 - values.len());
        values.resize(index + 1, T::default());
    }
    &mut values[index]
}

fn text<'a>(value: &'a Value, name: &str) -> &'a str {
    value
        .get(name)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
}

fn strings<'a>(value: &'a Value, name: &str) -> impl Iterator<Item = &'a str> {
    value
        .get(name)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
}

fn keys<'a>(value: &'a Value, name: &str) -> impl Iterator<Item = &'a str> {
    let entries = value
        .get(name)
        .and_then(Value::as_array)
        .into_iter()
        .flatten();
    entries.filter_map(|entry| entry.get("key").and_then(Value::as_str))
}

fn has_items(value: &Value, name: &str) -> bool {
    value
        .get(name)
        .and_then(Value::as_array)
        .is_some_and(|items| !items.is_empty())
}

fn first_cover(value: &Value) -> u32 {
    let covers = value
        .get("covers")
        .and_then(Value::as_array)
        .into_iter()
        .flatten();
    let cover = covers.filter_map(Value::as_i64).find(|&id| id > 0);
    cover.and_then(|id| u32::try_from(id).ok()).unwrap_or(0)
}

pub fn year_of(text: &str) -> u16 {
    text.split(|character: char| !character.is_ascii_digit())
        .filter(|digits| digits.len() == 4)
        .filter_map(|digits| digits.parse().ok())
        .find(|year| (1450..=2100).contains(year))
        .unwrap_or(0)
}

#[derive(Clone, Copy, Default)]
pub struct Signal {
    pub want: u16,
    pub reading: u16,
    pub finished: u16,
    pub ratings: u16,
    pub rating_total: u32,
}

impl Signal {
    pub fn readers(&self) -> u32 {
        self.want as u32 + self.reading as u32 + self.finished as u32
    }

    pub fn mean_rating(&self) -> f32 {
        self.rating_total as f32 / self.ratings.max(1) as f32
    }
}

pub fn signals(source: &str) -> Vec<Signal> {
    let mut signals: Vec<Signal> = Vec::new();
    let shelf = |line: &[u8]| Some((ol_id(field(line, 0))?, *field(line, 2).first()?));
    scan(source, "reading-log", shelf, |(work, shelf)| {
        let signal = slot(&mut signals, work);
        let counter = match shelf {
            b'W' => &mut signal.want,
            b'C' => &mut signal.reading,
            b'A' => &mut signal.finished,
            _ => return,
        };
        *counter = counter.saturating_add(1);
    });
    let rating = |line: &[u8]| {
        let score: u32 = std::str::from_utf8(field(line, 2))
            .ok()?
            .trim()
            .parse()
            .ok()?;
        (1..=5)
            .contains(&score)
            .then_some((ol_id(field(line, 0))?, score))
    };
    scan(source, "ratings", rating, |(work, score)| {
        let signal = slot(&mut signals, work);
        if signal.ratings < u16::MAX {
            signal.ratings += 1;
            signal.rating_total += score;
        }
    });
    signals
}

#[derive(Clone, Copy, Default)]
struct AuthorEntry {
    start: u32,
    length: u8,
    birth: u16,
}

#[derive(Default)]
pub struct Authors {
    names: String,
    entries: Vec<AuthorEntry>,
}

pub struct Author<'a> {
    pub name: &'a str,
    pub birth: u16,
}

impl Authors {
    pub fn get(&self, id: u32) -> Option<Author<'_>> {
        let entry = self
            .entries
            .get(id as usize)
            .filter(|entry| entry.length > 0)?;
        let start = entry.start as usize;
        let name = &self.names[start..start + entry.length as usize];
        Some(Author {
            name,
            birth: entry.birth,
        })
    }
}

fn latin_name(author: &Value) -> Option<&str> {
    let singles = ["name", "personal_name", "fuller_name"].map(|name| text(author, name));
    let mut names = singles
        .into_iter()
        .chain(strings(author, "alternate_names").map(str::trim));
    let mut names = names
        .by_ref()
        .filter(|name| !name.is_empty() && name.len() <= 120);
    let first = names.next()?;
    if catalog::is_mostly_latin(first) {
        return Some(first);
    }
    Some(
        names
            .find(|name| catalog::is_mostly_latin(name))
            .unwrap_or(first),
    )
}

pub fn authors(source: &str) -> Authors {
    let mut authors = Authors::default();
    let parse = |line: &[u8]| {
        let author = json(line)?;
        if text(&author, "entity_type") == "org" {
            return None;
        }
        let name = latin_name(&author)?.to_string();
        let birth = year_of(text(&author, "birth_date"));
        Some((ol_id(field(line, 1))?, name, birth))
    };
    scan(source, "authors", parse, |(id, name, birth)| {
        let start = u32::try_from(authors.names.len()).expect("author names under 4 GB");
        authors.names.push_str(&name);
        let length = name.len() as u8;
        *slot(&mut authors.entries, id) = AuthorEntry {
            start,
            length,
            birth,
        };
    });
    authors
}

#[derive(Clone, Copy, Default)]
pub struct Facts {
    pub editions: u16,
    pub language_editions: [u16; 4],
    pub languages: u32,
    pub first_year: u16,
    pub flags: u8,
    pub classes: Classes,
}

impl Facts {
    fn absorb(&mut self, other: &Facts) {
        self.editions = self.editions.saturating_add(other.editions);
        for (mine, theirs) in self
            .language_editions
            .iter_mut()
            .zip(other.language_editions)
        {
            *mine = mine.saturating_add(theirs);
        }
        self.languages |= other.languages;
        if other.first_year > 0
            && (self.first_year == 0 || other.first_year < self.first_year)
        {
            self.first_year = other.first_year;
        }
        self.flags |= other.flags;
        self.classes.absorb(other.classes);
    }

    pub fn has(&self, flag: u8) -> bool {
        self.flags & flag != 0
    }

    pub fn language_count(&self) -> u32 {
        self.languages.count_ones()
    }
}

#[derive(Clone, Copy)]
pub struct TitleRecord {
    pub work: u32,
    pub language: u8,
    pub position: u16,
    pub year: u16,
    pub cover: u32,
    start: u32,
    title_length: u8,
    series_length: u8,
}

#[derive(Default)]
pub struct Titles {
    records: Vec<TitleRecord>,
    text: String,
}

impl Titles {
    pub fn of(&self, work: u32) -> &[TitleRecord] {
        let start = self.records.partition_point(|record| record.work < work);
        let end = self.records.partition_point(|record| record.work <= work);
        &self.records[start..end]
    }

    fn full_title(&self, record: &TitleRecord) -> (&str, &str) {
        let start = record.start as usize;
        let text = &self.text[start..start + record.title_length as usize];
        text.split_once(SUBTITLE_SEPARATOR).unwrap_or((text, ""))
    }

    pub fn title(&self, record: &TitleRecord) -> &str {
        self.full_title(record).0
    }

    pub fn subtitle(&self, record: &TitleRecord) -> &str {
        self.full_title(record).1
    }

    pub fn series(&self, record: &TitleRecord) -> &str {
        let start = record.start as usize + record.title_length as usize;
        &self.text[start..start + record.series_length as usize]
    }

    fn push(&mut self, edition: &Edition, language: u8) {
        let start = u32::try_from(self.text.len()).expect("edition titles under 4 GB");
        let (series, position) = edition
            .series
            .as_ref()
            .map_or(("", 0), |(name, at)| (name.as_str(), *at));
        self.text.push_str(&edition.title);
        self.text.push_str(series);
        self.records.push(TitleRecord {
            work: edition.work,
            language,
            position,
            year: edition.facts.first_year,
            cover: edition.cover,
            start,
            title_length: edition.title.len() as u8,
            series_length: series.len() as u8,
        });
    }
}

struct Edition {
    work: u32,
    facts: Facts,
    cover: u32,
    title: String,
    series: Option<(String, u16)>,
}

fn language_bit(key: &str) -> u32 {
    let code = key.rsplit('/').next().unwrap_or(key);
    LANGUAGE_CODES
        .iter()
        .position(|&known| known == code)
        .map_or(1 << 31, |bit| 1 << bit)
}

fn is_scan(edition: &Value) -> bool {
    !text(edition, "ocaid").is_empty()
        && strings(edition, "source_records")
            .any(|record| record.starts_with("ia:") || record.starts_with("promise:"))
}

fn is_front_cover(shape: CoverShape, scanned: bool) -> bool {
    let (width, height) = (f32::from(shape.0), f32::from(shape.1));
    let upright = height > 0.0 && (0.55..=0.8).contains(&(width / height));
    upright && width >= 180.0 && (!scanned || width >= MIN_SCAN_WIDTH)
}

fn print_cover(edition: &Value, shapes: &[CoverShape]) -> u32 {
    let format = text(edition, "physical_format").to_ascii_lowercase();
    if NON_PRINT_FORMATS.iter().any(|word| format.contains(word)) {
        return 0;
    }
    let cover = first_cover(edition);
    let shape = shapes.get(cover as usize).copied().unwrap_or_default();
    if is_front_cover(shape, is_scan(edition)) {
        cover
    } else {
        0
    }
}

fn cover_shape(line: &[u8]) -> Option<(usize, CoverShape)> {
    let text = |index| std::str::from_utf8(field(line, index)).ok();
    Some((
        text(0)?.parse().ok()?,
        (text(1)?.parse().ok()?, text(2)?.parse().ok()?),
    ))
}

fn cover_shapes(source: &str) -> Vec<CoverShape> {
    let mut shapes = Vec::new();
    scan(source, "covers_metadata", cover_shape, |(id, shape)| {
        if id >= shapes.len() {
            shapes.resize(id + 1, (0, 0));
        }
        shapes[id] = shape;
    });
    shapes
}

fn is_unreadable(format: &str) -> bool {
    let format = format.to_ascii_lowercase();
    let unreadable = [
        "microform",
        "microfilm",
        "microfiche",
        "thesis",
        "manuscript",
        "cd-rom",
    ];
    unreadable.iter().any(|bad| format.contains(bad))
}

fn edition_title(title: &str, subtitle: &str) -> String {
    if title.len() > MAX_TITLE_BYTES {
        return String::new();
    }
    if subtitle.is_empty() || subtitle.len() > MAX_TITLE_BYTES {
        return title.to_string();
    }
    let labelled = format!("{title}{SUBTITLE_SEPARATOR}{subtitle}");
    let fits = labelled.len() <= usize::from(u8::MAX);
    if fits { labelled } else { title.to_string() }
}

fn isbn_language(isbn: &str) -> Option<usize> {
    let digits: String = isbn.chars().filter(char::is_ascii_digit).collect();
    let normalized = if digits.len() == 10 { format!("978{digits}") } else { digits };
    ISBN_GROUPS
        .iter()
        .find(|(prefix, _)| normalized.len() == 13 && normalized.starts_with(prefix))
        .map(|&(_, language)| language)
}

fn implied_language(edition: &Value) -> Option<usize> {
    let mut isbns = strings(edition, "isbn_13").chain(strings(edition, "isbn_10"));
    isbns.find_map(isbn_language).or_else(|| {
        let title = format!("{} {}", text(edition, "title"), text(edition, "subtitle"));
        catalog::detect_language(&title)
    })
}

fn edition_from(edition: &Value, shapes: &[CoverShape]) -> Option<Edition> {
    let work = ol_id(keys(edition, "works").next()?.as_bytes())?;
    let mut facts = Facts {
        editions: 1,
        ..Facts::default()
    };
    for key in keys(edition, "languages") {
        facts.languages |= language_bit(key);
        let code = key.rsplit('/').next().unwrap_or(key);
        if let Some(target) = TARGET_CODES.iter().position(|&known| known == code) {
            facts.language_editions[target] = 1;
        }
    }
    if facts.languages == 0 {
        let target = implied_language(edition).unwrap_or(0);
        facts.language_editions[target] = 1;
        facts.languages = 1 << target;
    }
    facts.first_year = year_of(text(edition, "publish_date"));
    facts.classes = tags::classes_of(edition);
    let flags = [
        (
            has_items(edition, "isbn_13") || has_items(edition, "isbn_10"),
            FLAG_ISBN,
        ),
        (first_cover(edition) > 0, FLAG_COVER),
        (has_items(edition, "publishers"), FLAG_PUBLISHER),
        (
            !is_unreadable(text(edition, "physical_format")),
            FLAG_READABLE,
        ),
    ];
    facts.flags = flags
        .iter()
        .filter(|(set, _)| *set)
        .fold(0, |all, (_, flag)| all | flag);
    let title = edition_title(text(edition, "title"), text(edition, "subtitle"));
    let series = catalog::parse_series(&strings(edition, "series").collect::<Vec<_>>());
    Some(Edition {
        work,
        facts,
        cover: print_cover(edition, shapes),
        title,
        series,
    })
}

pub fn editions(source: &str) -> (Vec<Facts>, Titles) {
    let mut facts: Vec<Facts> = Vec::new();
    let mut titles = Titles::default();
    let shapes = cover_shapes(source);
    scan(
        source,
        "editions",
        |line| edition_from(&json(line)?, &shapes),
        |edition| {
            slot(&mut facts, edition.work).absorb(&edition.facts);
            if edition.title.is_empty() {
                return;
            }
            for (language, &count) in edition.facts.language_editions.iter().enumerate() {
                if count > 0 {
                    titles.push(&edition, language as u8);
                }
            }
        },
    );
    titles
        .records
        .sort_unstable_by_key(|record| (record.work, record.language));
    titles.records.shrink_to_fit();
    titles.text.shrink_to_fit();
    (facts, titles)
}

pub struct Book {
    pub work: u32,
    pub title: String,
    pub subtitle: String,
    pub authors: Vec<u32>,
    pub tags: Vec<u8>,
    pub description: String,
    pub description_language: Option<usize>,
    pub cover: u32,
    pub year: u16,
    pub editions: u16,
    pub signal: Signal,
    pub scores: [f32; 4],
    pub eligible: [bool; 4],
}

pub struct Context<'a> {
    pub signals: &'a [Signal],
    pub facts: &'a [Facts],
    pub authors: &'a Authors,
    pub sticky: &'a [bool],
}

struct WorkLine {
    month: String,
    book: Option<Book>,
}

fn author_ids(work: &Value, authors: &Authors) -> Vec<u32> {
    let entries = work
        .get("authors")
        .and_then(Value::as_array)
        .into_iter()
        .flatten();
    let keys = entries.filter_map(|entry| {
        let key = entry.get("author").and_then(|author| author.get("key"));
        key.or_else(|| entry.get("key"))?.as_str()
    });
    let mut ids: Vec<u32> = keys
        .filter_map(|key| ol_id(key.as_bytes()))
        .filter(|&id| authors.get(id).is_some())
        .collect();
    ids.dedup();
    ids.truncate(MAX_AUTHORS);
    ids
}

fn description_of(work: &Value) -> &str {
    let Some(description) = work.get("description") else {
        return "";
    };
    let text = description
        .as_str()
        .or_else(|| description.get("value")?.as_str());
    text.unwrap_or_default().trim()
}

fn book_from(id: u32, work: &Value, context: &Context) -> Option<Book> {
    let facts = context.facts.get(id as usize).copied().unwrap_or_default();
    let signal = context
        .signals
        .get(id as usize)
        .copied()
        .unwrap_or_default();
    let sticky = context.sticky.get(id as usize).copied().unwrap_or(false);
    let title = text(work, "title");
    let authors = author_ids(work, context.authors);
    let subjects: Vec<&str> = strings(work, "subjects").collect();
    let description = catalog::clean_description(description_of(work));
    let traits = Traits {
        has_author: !authors.is_empty(),
        good_title: !title.is_empty() && !catalog::is_bad_title(title),
        has_subjects: !subjects.is_empty(),
        bad_subject: subjects
            .iter()
            .any(|subject| catalog::is_bad_subject(subject)),
        has_description: description.len() >= 40,
        has_cover: first_cover(work) > 0,
    };
    let mut scores = [0.0; 4];
    let mut eligible = [false; 4];
    for language in 0..4 {
        scores[language] = catalog::score(&traits, &facts, &signal, language);
        eligible[language] = catalog::is_eligible(&traits, &facts, &signal, language);
    }
    if !sticky && !eligible.contains(&true) {
        return None;
    }
    let described = scores
        .iter()
        .any(|&score| score >= catalog::DESCRIPTION_MIN_SCORE);
    let year = match year_of(text(work, "first_publish_date")) {
        0 => facts.first_year,
        year => year,
    };
    Some(Book {
        work: id,
        title: title.to_string(),
        subtitle: text(work, "subtitle").to_string(),
        tags: tags::confident_tags(subjects.into_iter(), &facts.classes, facts.editions),
        description_language: catalog::detect_language(&description),
        description: if described {
            description
        } else {
            String::new()
        },
        authors,
        cover: first_cover(work),
        year,
        editions: facts.editions,
        signal,
        scores,
        eligible,
    })
}

fn work_line(line: &[u8], context: &Context) -> Option<WorkLine> {
    if field(line, 0) != b"/type/work" {
        return None;
    }
    let id = ol_id(field(line, 1))?;
    let month = String::from_utf8_lossy(field(line, 3).get(..10)?).into_owned();
    let has_editions = context
        .facts
        .get(id as usize)
        .is_some_and(|facts| facts.editions > 0);
    let sticky = context.sticky.get(id as usize).copied().unwrap_or(false);
    let book = if has_editions || sticky {
        book_from(id, &json(line)?, context)
    } else {
        None
    };
    Some(WorkLine { month, book })
}

pub struct Works {
    pub books: Vec<Book>,
    pub month: String,
}

pub fn works(source: &str, context: &Context) -> Works {
    let mut works = Works {
        books: Vec::new(),
        month: String::new(),
    };
    let absorb = |line: WorkLine| {
        if line.month > works.month {
            works.month = line.month;
        }
        works.books.extend(line.book);
    };
    scan(source, "works", |line| work_line(line, context), absorb);
    works.books.sort_unstable_by_key(|book| book.work);
    works
}
