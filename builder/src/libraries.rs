use crate::catalog::{self, MIN_SCORE, TARGET_CODES, tokenize};
use crate::dumps::{self, Authors, Book, FLAG_ISBN, FLAG_PUBLISHER, FLAG_READABLE, LibraryCover};
use crate::dumps::{Facts, Isbns, Signal, Titles};
use crate::linked;
use crate::marc::{self, Marc};
use crate::release;
use crate::tags::{self, Classes};
use crate::{LANGUAGE_COUNT, LANGUAGES};
use std::cmp::Reverse;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::Instant;
use unicode_normalization::UnicodeNormalization;

pub const SYNTHETIC: u32 = 1_000_000_000;
pub const MAX_VOLUME: u16 = 40;
const MAX_NAME_BYTES: usize = 120;
const RECENT_YEARS: f32 = 10.0;
const MAX_ALIAS_HOPS: usize = 8;
const KID_AUDIENCE: &str = "4030550-8";
const TEEN_AUDIENCE: &str = "4028859-6";
const DROPPED_TYPES: [&str; 3] = ["/bibo/Collection", "/bibo/Periodical", "/bibo/Series"];
const ARTICLES: [&str; 21] = [
    "the", "a", "an", "der", "die", "das", "den", "dem", "des", "ein", "eine", "le", "la", "les",
    "l", "un", "une", "el", "los", "las", "lo",
];
const APOSTROPHES: [char; 5] = ['\'', '’', '‘', '`', 'ʼ'];
const NAME_CUTS: [&str; 5] = [" und ", " & ", ", ", "/", " ("];
const ROLES: [&str; 8] = [
    "autor",
    "autorin",
    "autoren",
    "autorinnen",
    "herausgeber",
    "herausgeberin",
    "redaktion",
    "illustrationen",
];
const PERFORMERS: [&str; 9] = [
    "hörspiel",
    "erzähler",
    "sprecher",
    "gelesen",
    "lesung",
    "regie",
    "besetzung",
    "bearbeitung",
    "gesprochen",
];
const GENERIC_TITLES: usize = 4;
const GENRE_TITLES: [&str; 4] = ["krimi", "western", "erzahlung", "thriller"];
const VOLUME_WORDS: [&str; 6] = ["Band", "Bd.", "Folge", "Teil", "Heft", "Nr."];
const TITLE_SEPARATORS: [&str; 6] = [". ", " - ", " – ", ": ", " oder ", "\u{1f}"];
const HONORIFICS: [&str; 4] = ["sir", "dame", "dr", "prof"];
const NAME_PREFIXES: [&str; 5] = ["von ", "by ", "par ", "Autorin ", "Autor "];
pub const HARDCOVER: u8 = 1;
pub const PAPERBACK: u8 = 2;
pub const EBOOK: u8 = 4;
const AUDIO: u8 = 8;
pub const COMIC: u8 = 16;
const ERB_AUTHOR_IDS: u64 = 140_000_000;
const LOCAL_IDS: u32 = 100_000_000;
const MAX_DNB_ID: u32 = 200_000_000;
const PUBLISHER_SERIES_WRITERS: usize = 3;

const THEMA: [(&str, &str, &str); 24] = [
    ("FBC", "fiction / classics", "fiction / classics"),
    ("FB", "fiction / literary", "fiction / literary"),
    (
        "FDB",
        "fiction / dystopian",
        "young adult fiction / dystopian",
    ),
    (
        "FF",
        "fiction / mystery & detective",
        "fiction / mystery & detective",
    ),
    ("FH", "fiction / thrillers", "fiction / thrillers"),
    (
        "FJ",
        "fiction / action & adventure",
        "fiction / action & adventure",
    ),
    ("FK", "fiction / horror", "fiction / horror"),
    (
        "FL",
        "fiction / science fiction",
        "young adult fiction / science fiction",
    ),
    (
        "FMB",
        "fiction / fantasy / epic",
        "young adult fiction / fantasy",
    ),
    ("FM", "fiction / fantasy", "young adult fiction / fantasy"),
    (
        "FRH",
        "fiction / romance / historical",
        "young adult fiction / romance",
    ),
    ("FR", "fiction / romance", "young adult fiction / romance"),
    ("FU", "fiction / humorous", "fiction / humorous"),
    ("FV", "fiction / historical", "fiction / historical"),
    ("FYB", "fiction / short stories", "fiction / short stories"),
    (
        "YFH",
        "juvenile fiction / fantasy & magic",
        "young adult fiction / fantasy",
    ),
    (
        "YFG",
        "juvenile fiction / science fiction",
        "young adult fiction / science fiction",
    ),
    ("YFM", "juvenile fiction", "young adult fiction / romance"),
    ("YN", "juvenile nonfiction", "young adult nonfiction"),
    ("YF", "juvenile fiction", "young adult fiction"),
    ("YB", "juvenile fiction", "juvenile fiction"),
    ("X", "comics & graphic novels", "comics & graphic novels"),
    ("DC", "poetry", "poetry"),
    ("DD", "drama", "drama"),
];

#[derive(Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord)]
pub enum Source {
    #[default]
    Dnb,
    Erb,
    Libris,
    Bnp,
    Nkp,
    Nlk,
    Fennica,
    Bnf,
}

pub const DNB_COVER: u8 = 1;
const DIGAR_COVER: u8 = 2;
const ISBN_COVER: u8 = 3;
const BOOKBITES_COVER: u8 = 4;

impl Source {
    pub fn named(name: &str) -> Option<Source> {
        let all = [
            ("dnb", Source::Dnb),
            ("erb", Source::Erb),
            ("libris", Source::Libris),
            ("bnp", Source::Bnp),
            ("nkp", Source::Nkp),
            ("nlk", Source::Nlk),
            ("fennica", Source::Fennica),
            ("bnf", Source::Bnf),
        ];
        let found = all.iter().find(|(known, _)| *known == name);
        found.map(|&(_, source)| source)
    }

    pub fn cover(self) -> u8 {
        match self {
            Source::Dnb => DNB_COVER,
            Source::Erb => DIGAR_COVER,
            Source::Libris => BOOKBITES_COVER,
            _ => ISBN_COVER,
        }
    }

    pub fn language(self) -> usize {
        let code = match self {
            Source::Dnb => "de",
            Source::Erb => "et",
            Source::Libris => "sv",
            Source::Bnp => "pl",
            Source::Nkp => "cs",
            Source::Nlk => "ko",
            Source::Fennica => "fi",
            Source::Bnf => "fr",
        };
        crate::language_index(code)
    }

    fn separator(self) -> &'static [u8] {
        match self {
            Source::Dnb => b"\n</rdf:Description>\n",
            Source::Erb | Source::Fennica => b"</marc:record>",
            Source::Nkp => b"</record>",
            Source::Bnp | Source::Bnf => b"\x1d",
            Source::Nlk => linked::NLK_SEPARATOR,
            Source::Libris => b"\n",
        }
    }

    fn parse(self, bytes: &[u8]) -> Option<Record> {
        let text = || std::str::from_utf8(bytes).ok();
        match self {
            Source::Dnb => dnb_record(text()?),
            Source::Erb => marc::marc21_record(&Marc::xml(text()?), self, &marc::ERB),
            Source::Nkp => marc::marc21_record(&Marc::xml(text()?), self, &marc::NKP),
            Source::Fennica => marc::marc21_record(&Marc::xml(text()?), self, &marc::FENNICA),
            Source::Bnp => marc::marc21_record(&Marc::binary(bytes)?, self, &marc::BNP),
            Source::Bnf => marc::bnf_record(&Marc::binary(bytes)?),
            Source::Nlk => linked::nlk_record(text()?),
            Source::Libris => None,
        }
    }

    fn id_base(self) -> u32 {
        let slot = self as u32 - Source::Libris as u32;
        MAX_DNB_ID + slot * LOCAL_IDS
    }

    pub fn local_id(self, raw: &str) -> u32 {
        let number = raw
            .parse()
            .ok()
            .filter(|&number| number > 0 && number < LOCAL_IDS);
        let hashed = || (hash(&[raw.as_bytes()]) % u64::from(LOCAL_IDS)) as u32;
        self.id_base() + number.unwrap_or_else(hashed)
    }

    pub fn author_id(self, name: &str) -> u32 {
        if name.is_empty() {
            return 0;
        }
        let key = name_key(name);
        if self == Source::Erb {
            let hashed = hash(&[b"erb", key.as_bytes()]) % ERB_AUTHOR_IDS;
            return SYNTHETIC + hashed as u32;
        }
        self.id_base() + (hash(&[key.as_bytes()]) % u64::from(LOCAL_IDS)) as u32
    }
}

#[derive(Default)]
pub struct Record {
    pub source: Source,
    pub id: u32,
    pub language: usize,
    pub isbns: Vec<u32>,
    pub links: Vec<u32>,
    pub title: String,
    pub original: String,
    pub series: String,
    pub volume: u16,
    pub author: u32,
    pub name: String,
    pub year: u16,
    pub format: u8,
    pub classes: Classes,
    pub subjects: Vec<&'static str>,
    pub digar: u32,
}

impl Record {
    fn work(&self) -> u32 {
        SYNTHETIC + self.id
    }

    fn cover_key(&self) -> Option<u32> {
        match self.source {
            Source::Erb => Some(self.digar).filter(|&digar| digar > 0),
            _ => self.isbns.first().copied(),
        }
    }

    fn titles(&self) -> impl Iterator<Item = &str> {
        variants(&self.title).chain(variants(&self.original))
    }

    fn volume_key(&self, writer: &str) -> u64 {
        let series = catalog::series_key(&self.series);
        let volume = self.volume.to_le_bytes();
        hash(&[b"volume", writer.as_bytes(), series.as_bytes(), &volume])
    }

    fn keys(&self, generic: &Generic) -> Vec<u64> {
        let isbns = self
            .isbns
            .iter()
            .map(|isbn| hash(&[b"isbn", &isbn.to_le_bytes()]));
        let mut keys: Vec<u64> = isbns.collect();
        let writer = writer_key(&self.name);
        let specific = |key: &u64| !generic.contains(&(*key, self.language));
        if !writer.is_empty() {
            let titles = self.titles().map(|title| title_hash(&writer, title));
            keys.extend(titles.filter(specific));
            let volume = self.volume_key(&writer);
            keys.extend((self.volume > 0).then_some(volume).filter(specific));
        }
        let language = self.language.to_le_bytes();
        let local = |key: u64| hash(&[&key.to_le_bytes(), &language]);
        keys.into_iter().map(local).collect()
    }

    fn facts(&self) -> Facts {
        let mut facts = Facts {
            editions: 1,
            languages: catalog::language_bit(TARGET_CODES[self.language]),
            first_year: self.year,
            flags: FLAG_ISBN | FLAG_PUBLISHER | FLAG_READABLE,
            classes: self.classes,
            ..Facts::default()
        };
        facts.language_editions[self.language] = 1;
        facts
    }
}

struct Line<'a> {
    depth: usize,
    tag: &'a str,
    resource: &'a str,
    text: &'a str,
}

impl<'a> Line<'a> {
    fn parse(raw: &'a str) -> Option<Line<'a>> {
        let content = raw.trim_start_matches('\t');
        let body = content
            .strip_prefix('<')
            .filter(|body| !body.starts_with('/'))?;
        let tag = &body[..body.find([' ', '>', '/']).unwrap_or(body.len())];
        let resource = attribute(body, "rdf:resource=\"")
            .or_else(|| attribute(body, "rdf:about=\""))
            .unwrap_or_default();
        let text = body
            .split_once('>')
            .and_then(|(_, rest)| rest.split_once("</"))
            .map_or("", |(text, _)| text);
        Some(Line {
            depth: raw.len() - content.len(),
            tag,
            resource,
            text,
        })
    }
}

fn attribute<'a>(body: &'a str, name: &str) -> Option<&'a str> {
    let start = body.find(name)? + name.len();
    body[start..].split('"').next()
}

fn last_segment(uri: &str) -> &str {
    uri.trim_end_matches('/').rsplit('/').next().unwrap_or(uri)
}

fn record_id(identifier: &str) -> Option<u32> {
    let body = identifier.get(..identifier.len().checked_sub(1)?)?;
    let id: u32 = body.parse().ok()?;
    (id > 0 && id < MAX_DNB_ID).then_some(id)
}

pub fn clean(text: &str) -> String {
    let unescaped = catalog::ENTITIES.iter().fold(
        text.replace('@', "").replace("&amp;amp;", "&amp;"),
        |text, (entity, character)| text.replace(entity, character),
    );
    unescaped.nfc().collect::<String>().trim().to_string()
}

fn capitalized(text: &str) -> String {
    let mut characters = text.chars();
    characters.next().map_or_else(String::new, |first| {
        first.to_uppercase().chain(characters).collect()
    })
}

pub fn without_series(title: &str, series: &str) -> String {
    let head = title.get(..series.len()).unwrap_or_default();
    let rest = &title[head.len()..];
    let named = !series.is_empty() && head.to_lowercase() == series.to_lowercase();
    if !named || rest.trim_start().starts_with(char::is_alphabetic) {
        return title.to_string();
    }
    let separators = |character: char| {
        character.is_ascii_digit() || character.is_whitespace() || ".-–:,".contains(character)
    };
    match rest.trim_start_matches(separators) {
        "" => title.to_string(),
        remainder => capitalized(remainder),
    }
}

fn variants(title: &str) -> impl Iterator<Item = &str> {
    let tails = TITLE_SEPARATORS
        .iter()
        .filter_map(|separator| title.split_once(separator).map(|(_, tail)| tail));
    let heads = [" (", " oder "]
        .iter()
        .filter_map(|separator| title.split_once(separator).map(|(head, _)| head));
    let all = std::iter::once(title).chain(tails).chain(heads);
    all.filter(|variant| !variant.trim().is_empty())
}

fn heads(title: &str) -> impl Iterator<Item = &str> {
    let heads = [". ", "\u{1f}"]
        .iter()
        .filter_map(|separator| title.split_once(separator).map(|(head, _)| head));
    variants(title).chain(heads)
}

fn volume_label(title: &str) -> Option<(&str, u16)> {
    let (rest, number) = title.rsplit_once(' ')?;
    let number = number.parse().ok()?;
    let head = VOLUME_WORDS
        .iter()
        .find_map(|word| rest.strip_suffix(word))?;
    Some((head.trim_end_matches([' ', ',', '-', '–', ':']), number))
}

pub fn is_genre(title: &str) -> bool {
    let key = match_key(title);
    GENRE_TITLES.contains(&key.as_str()) || !key.contains(' ') && key.ends_with("roman")
}

pub fn is_numbered_genre(title: &str) -> bool {
    TITLE_SEPARATORS.iter().any(|separator| {
        let split = title.rsplit_once(separator);
        split.is_some_and(|(head, tail)| {
            head.ends_with(|c: char| c.is_ascii_digit()) && is_genre(tail)
        })
    })
}

pub fn first_number(text: &str) -> Option<u16> {
    let digits: String = text
        .chars()
        .skip_while(|character| !character.is_ascii_digit())
        .take_while(char::is_ascii_digit)
        .collect();
    digits.parse().ok()
}

fn sentence_end(text: &str) -> usize {
    let ends_word = |at: usize| {
        let letters = text[..at].chars().rev().take(2);
        letters
            .filter(|character| character.is_alphabetic())
            .count()
            == 2
    };
    let mut ends = text.match_indices(". ").map(|(at, _)| at);
    ends.find(|&at| ends_word(at)).unwrap_or(text.len())
}

fn with_shared_surname(names: &str) -> String {
    let pair = names
        .split_once(" und ")
        .or_else(|| names.split_once(" & "));
    let Some((given, partner)) = pair.filter(|(given, _)| !given.contains(' ')) else {
        return names.to_string();
    };
    let partner: Vec<&str> = partner.split_whitespace().collect();
    let named = partner
        .iter()
        .all(|word| word.starts_with(char::is_uppercase));
    match partner.last() {
        Some(surname) if partner.len() > 1 && named => format!("{given} {surname}"),
        _ => names.to_string(),
    }
}

pub fn author_name(statement: &str) -> String {
    let statement = clean(statement);
    let first = statement.split(" ; ").next().unwrap_or_default();
    let lowered = first.to_lowercase();
    if PERFORMERS.iter().any(|role| lowered.contains(role)) {
        return String::new();
    }
    let first = first.split('[').next().unwrap_or_default();
    let first = first.rsplit(": ").next().unwrap_or_default();
    let first = with_shared_surname(&first[..sentence_end(first)]);
    let first = NAME_CUTS.iter().fold(first.as_str(), |name, cut| {
        name.split(cut).next().unwrap_or(name)
    });
    let first = NAME_PREFIXES.iter().fold(first, |name, prefix| {
        name.strip_prefix(prefix).unwrap_or(name)
    });
    let name = clean(first.trim_matches(['[', ']', ' ', '.']));
    let role = ROLES.contains(&name.to_lowercase().as_str());
    let usable = name.starts_with(char::is_uppercase) && name.len() <= MAX_NAME_BYTES && !role;
    if usable { name } else { String::new() }
}

pub fn hash(parts: &[&[u8]]) -> u64 {
    let bytes = parts.iter().flat_map(|part| part.iter().chain(&[0xff]));
    bytes.fold(0xcbf2_9ce4_8422_2325, |hash, &byte| {
        (hash ^ byte as u64).wrapping_mul(0x0100_0000_01b3)
    })
}

fn folded(text: &str) -> String {
    let marks = |character: &char| !unicode_normalization::char::is_combining_mark(*character);
    let quotes = |character: &char| !APOSTROPHES.contains(character);
    text.nfkd().filter(marks).filter(quotes).nfc().collect()
}

pub fn match_key(title: &str) -> String {
    let words = tokenize(&folded(title));
    match words.split_first() {
        Some((first, rest)) if ARTICLES.contains(&first.as_str()) => rest.join(" "),
        _ => words.join(" "),
    }
}

pub fn name_key(name: &str) -> String {
    let mut words = tokenize(&folded(name));
    words.sort_unstable();
    words.join(" ")
}

pub fn writer_key(name: &str) -> String {
    let ordered = match name.split_once(", ") {
        Some((last, first)) => format!("{first} {last}"),
        None => name.to_string(),
    };
    let mut words = tokenize(&folded(&ordered));
    words.retain(|word| !HONORIFICS.contains(&word.as_str()));
    let Some((last, given)) = words.split_last() else {
        return String::new();
    };
    let spelled = given.iter().find(|word| word.chars().nth(1).is_some());
    let first = spelled.or(given.first()).unwrap_or(last);
    format!("{last} {}", first.chars().next().unwrap_or_default())
}

fn title_hash(writer: &str, title: &str) -> u64 {
    hash(&[b"title", writer.as_bytes(), match_key(title).as_bytes()])
}

fn thema_subject(code: &str, teen: bool) -> Option<&'static str> {
    let (_, general, teenage) = THEMA.iter().find(|(prefix, ..)| code.starts_with(prefix))?;
    Some(if teen { teenage } else { general })
}

#[derive(Default)]
struct Fields<'a> {
    about: &'a str,
    dropped: bool,
    languages: Vec<&'a str>,
    isbns: Vec<&'a str>,
    links: Vec<&'a str>,
    title: &'a str,
    original: &'a str,
    citations: Vec<&'a str>,
    authors: Vec<&'a str>,
    statement: &'a str,
    issued: &'a str,
    carrier: &'a str,
    contents: Vec<&'a str>,
    binding: &'a str,
    genres: Vec<u16>,
    categories: Vec<&'a str>,
    thema: Vec<&'a str>,
    audiences: Vec<&'a str>,
}

struct Reader {
    juvenile: bool,
    kid: bool,
    teen: bool,
    middle: bool,
    picture: bool,
}

impl<'a> Fields<'a> {
    fn read(&mut self, parent: &str, line: Line<'a>) {
        let Line { resource, text, .. } = line;
        match (parent, line.tag) {
            ("rdf:Description", "rdf:Description") => self.about = resource,
            ("rdf:type", "rdf:type") => {
                self.dropped |= DROPPED_TYPES.iter().any(|kind| resource.ends_with(kind));
            }
            ("bibo:isbn13" | "bibo:isbn10", _) => self.isbns.push(text),
            ("dcterms:language", _) => self.languages.push(resource),
            ("dc:title", _) if self.title.is_empty() => self.title = text,
            ("schema:exampleOfWork", "gndo:preferredName") => self.original = text,
            ("dcterms:bibliographicCitation", _) => self.citations.push(text),
            ("marcRole:aut" | "dcterms:creator", _) if resource.contains("/gnd/") => {
                self.authors.push(resource);
            }
            ("rdau:P60327", _) => self.statement = text,
            ("dcterms:issued", _) => self.issued = text,
            ("dcterms:medium", _) => self.carrier = last_segment(resource),
            ("rdau:P60049", _) if resource.contains("RDAContentType") => {
                self.contents.push(last_segment(resource));
            }
            ("rdau:P60521", _) => self.binding = text,
            ("dcterms:isFormatOf", _) => self.links.push(last_segment(resource)),
            ("schema:genre", "skos:notation") => self.genres.extend(text.parse::<u16>().ok()),
            ("dc:subject", _) => self.categories.push(text),
            ("dcterms:subject", _) if resource.contains("/thema/") => {
                self.thema.push(last_segment(resource));
            }
            ("dcterms:audience", _) => self.audiences.push(last_segment(resource)),
            _ => {}
        }
    }

    fn has_genre(&self, range: std::ops::Range<u16>) -> bool {
        self.genres.iter().any(|genre| range.contains(genre))
    }

    fn has_thema(&self, prefixes: &[&str]) -> bool {
        let matches = |code: &&str| prefixes.iter().any(|prefix| code.starts_with(prefix));
        self.thema.iter().any(matches)
    }

    fn is_literature(&self) -> bool {
        self.has_genre(100..300)
            || self
                .categories
                .iter()
                .any(|category| matches!(*category, "B" | "K"))
            || self.has_thema(&["F", "Y", "X", "DC", "DD"])
    }

    fn is_fiction(&self) -> bool {
        self.has_genre(100..200) || self.categories.contains(&"B") || self.has_thema(&["F"])
    }

    fn reader(&self) -> Reader {
        let ages: Vec<char> = self
            .thema
            .iter()
            .filter_map(|code| code.strip_prefix("5A")?.chars().next())
            .collect();
        let aged =
            |range: std::ops::RangeInclusive<char>| ages.iter().any(|age| range.contains(age));
        let kid =
            aged('B'..='M') || self.has_genre(250..260) || self.audiences.contains(&KID_AUDIENCE);
        let teen = aged('N'..='T')
            || self.has_genre(220..230)
            || self.has_genre(260..270)
            || self.audiences.contains(&TEEN_AUDIENCE);
        Reader {
            juvenile: kid
                || teen
                || self.has_genre(200..300)
                || self.categories.contains(&"K")
                || self.has_thema(&["Y"]),
            kid: kid && !teen,
            teen,
            middle: aged('J'..='M') && !teen,
            picture: self.has_thema(&["YB"]),
        }
    }

    fn format(&self) -> u8 {
        let bound = ["Festeinband", "Pappband", "Gebunden"];
        match () {
            _ if self.contents.contains(&"1013") => AUDIO,
            _ if self.contents.contains(&"1014") => COMIC,
            _ if self.carrier == "1018" => EBOOK,
            _ if bound.iter().any(|kind| self.binding.starts_with(kind)) => HARDCOVER,
            _ => PAPERBACK,
        }
    }

    fn subjects(&self, reader: &Reader) -> Vec<&'static str> {
        let mapped = self
            .thema
            .iter()
            .filter_map(|code| thema_subject(code, reader.teen));
        let mut subjects: Vec<&str> = mapped.collect();
        let base = match () {
            _ if self.has_genre(280..290) => Some("juvenile nonfiction"),
            _ if reader.teen => Some("young adult fiction"),
            _ if reader.juvenile => Some("juvenile fiction"),
            _ if self.is_fiction() => Some("fiction"),
            _ => None,
        };
        subjects.extend(base);
        if reader.middle {
            subjects.push("middle grade");
        }
        subjects.sort_unstable();
        subjects.dedup();
        subjects
    }

    fn series(&self) -> (String, u16) {
        let parsed = self.citations.iter().find_map(|citation| {
            let cleaned = clean(citation);
            let (name, rest) = cleaned.split_once(" ; ")?;
            let volume = first_number(rest).filter(|volume| (1..=MAX_VOLUME).contains(volume))?;
            let (name, _) = release::parse_series(&[name])?;
            Some((name, volume))
        });
        parsed.unwrap_or_default()
    }

    fn is_merchandise(&self) -> bool {
        self.has_genre(290..300) || self.has_thema(&["YZ", "WZ", "YBG", "YBL", "YP"])
    }

    fn titled(&self) -> Option<(String, String, u16)> {
        let (series, volume) = self.series();
        let title = without_series(&clean(self.title), &series);
        if is_genre(&title) || is_numbered_genre(&title) {
            return None;
        }
        let Some((head, number)) = volume_label(&title) else {
            return Some((title, series, volume)).filter(|(title, ..)| !title.is_empty());
        };
        let (series, volume) = match series.is_empty() {
            true => (head.to_string(), number),
            false => (series, volume),
        };
        let numbered = !series.is_empty() && (1..=MAX_VOLUME).contains(&volume);
        numbered.then(|| (format!("{series} {volume}"), series, volume))
    }

    fn record(&self) -> Option<Record> {
        if self.dropped || !self.is_literature() || self.is_merchandise() {
            return None;
        }
        let language = Source::Dnb.language();
        let mut written = self.languages.iter();
        if !written.any(|code| catalog::target_language(code) == Some(language)) {
            return None;
        }
        let mut isbns: Vec<u32> = Vec::new();
        let native = self
            .isbns
            .iter()
            .filter(|isbn| !catalog::is_foreign_isbn(isbn));
        for key in native.filter_map(|isbn| catalog::isbn_key(isbn)) {
            if !isbns.contains(&key) {
                isbns.push(key);
            }
        }
        if isbns.is_empty() {
            return None;
        }
        let (title, series, volume) = self.titled()?;
        let reader = self.reader();
        let format = self.format();
        let classes = Classes {
            classified: 1,
            picture: u16::from(reader.picture),
            juvenile: u16::from(reader.juvenile),
            comics: u16::from(self.has_thema(&["X"])),
            poetry: u16::from(self.has_thema(&["DC"])),
            drama: u16::from(self.has_thema(&["DD"])),
            kid: u16::from(reader.kid),
            teen: u16::from(reader.teen),
            young_picture: u16::from(reader.picture),
        };
        Some(Record {
            source: Source::Dnb,
            id: record_id(last_segment(self.about))?,
            language,
            isbns,
            links: self
                .links
                .iter()
                .filter_map(|link| record_id(link))
                .collect(),
            title,
            original: clean(self.original),
            series,
            volume,
            author: self
                .authors
                .iter()
                .find_map(|author| record_id(last_segment(author)))
                .unwrap_or(0),
            name: author_name(self.statement),
            year: dumps::year_of(self.issued),
            format,
            classes,
            subjects: self.subjects(&reader),
            digar: 0,
        })
    }
}

fn dnb_record(text: &str) -> Option<Record> {
    let mut fields = Fields::default();
    let mut parent = "";
    for line in text.lines().filter_map(Line::parse) {
        if line.depth <= 1 {
            parent = line.tag;
        }
        fields.read(parent, line);
    }
    fields.record()
}

fn root(parents: &mut [u32], mut node: u32) -> u32 {
    while parents[node as usize] != node {
        let grandparent = parents[parents[node as usize] as usize];
        parents[node as usize] = grandparent;
        node = grandparent;
    }
    node
}

fn union(parents: &mut [u32], left: u32, right: u32) {
    let (left, right) = (root(parents, left), root(parents, right));
    parents[left.max(right) as usize] = left.min(right);
}

type Generic = HashSet<(u64, usize)>;

fn generic_titles(records: &[Record]) -> Generic {
    let mut sources: HashMap<(u64, usize), HashSet<u64>> = HashMap::new();
    let mut add = |key: (u64, usize), source: u64| {
        let known = sources.entry(key).or_default();
        if known.len() < GENERIC_TITLES {
            known.insert(source);
        }
    };
    for record in records.iter().filter(|record| !record.name.is_empty()) {
        let writer = writer_key(&record.name);
        let language = record.language;
        let full = title_hash(&writer, &record.title);
        for variant in variants(&record.title).skip(1) {
            add((title_hash(&writer, variant), language), full);
        }
        for variant in variants(&record.original).filter(|_| !record.original.is_empty()) {
            add((title_hash(&writer, variant), language), full);
        }
        if record.volume > 0 {
            add(
                (full, language),
                hash(&[b"volume", &record.volume.to_le_bytes()]),
            );
            add((record.volume_key(&writer), language), full);
        }
    }
    let generic = sources
        .into_iter()
        .filter(|(_, known)| known.len() >= GENERIC_TITLES);
    generic.map(|(key, _)| key).collect()
}

fn grouped(records: &[Record], generic: &Generic) -> Vec<Vec<u32>> {
    let mut parents: Vec<u32> = (0..records.len() as u32).collect();
    let mut owners: HashMap<u64, u32> = HashMap::new();
    let positions: HashMap<u32, u32> = (0..records.len() as u32)
        .map(|index| (records[index as usize].id, index))
        .collect();
    for (index, record) in (0..records.len() as u32).zip(records) {
        let linked = record
            .links
            .iter()
            .filter_map(|link| positions.get(link).copied());
        let mut others: Vec<u32> = linked.collect();
        for key in record.keys(generic) {
            others.push(*owners.entry(key).or_insert(index));
        }
        others
            .into_iter()
            .for_each(|other| union(&mut parents, index, other));
    }
    let mut slots = vec![u32::MAX; records.len()];
    let mut groups: Vec<Vec<u32>> = Vec::new();
    for index in 0..records.len() as u32 {
        let top = root(&mut parents, index) as usize;
        if slots[top] == u32::MAX {
            slots[top] = groups.len() as u32;
            groups.push(Vec::new());
        }
        groups[slots[top] as usize].push(index);
    }
    groups
}

fn shared_isbns(records: &[Record]) -> HashSet<u32> {
    let mut owners: HashMap<u32, HashSet<String>> = HashMap::new();
    let mut shared = HashSet::new();
    for record in records {
        let keys: HashSet<String> = record.titles().map(match_key).collect();
        for &isbn in &record.isbns {
            let owner = owners.entry(isbn).or_insert_with(|| keys.clone());
            if owner.is_disjoint(&keys) {
                shared.insert(isbn);
            }
        }
    }
    shared
}

fn drop_publisher_series(records: &mut [Record]) {
    let series_of = |record: &Record| (catalog::series_key(&record.series), record.language);
    let mut writers: HashMap<(String, usize), HashSet<String>> = HashMap::new();
    for record in records.iter().filter(|record| record.volume > 0) {
        if match_key(&record.title).contains(&match_key(&record.series)) {
            continue;
        }
        let names = writers.entry(series_of(record)).or_default();
        names.insert(writer_key(&record.name));
    }
    let many = |names: &HashSet<String>| names.len() >= PUBLISHER_SERIES_WRITERS;
    for record in records.iter_mut() {
        if writers.get(&series_of(record)).is_some_and(many) {
            record.series.clear();
            record.volume = 0;
        }
    }
}

fn most_common<T: Ord + Clone>(values: impl Iterator<Item = T>) -> Option<T> {
    let mut sorted: Vec<T> = values.collect();
    sorted.sort();
    let runs = sorted.chunk_by(|left, right| left == right);
    let best = runs.rev().max_by_key(|run| run.len())?;
    Some(best[0].clone())
}

pub fn aliases(previous: &[(u32, u32)], fresh: &[(u32, u32)], books: &[Book]) -> Vec<(u32, u32)> {
    let known: BTreeMap<u32, u32> = previous.iter().chain(fresh).copied().collect();
    let is_live = |work: u32| books.binary_search_by_key(&work, |book| book.work).is_ok();
    let resolve = |mut target: u32| {
        for _ in 0..MAX_ALIAS_HOPS {
            match known.get(&target) {
                Some(&next) if next != target => target = next,
                _ => break,
            }
        }
        target
    };
    known
        .iter()
        .map(|(&from, &to)| (from, resolve(to)))
        .filter(|&(from, to)| from != to && !is_live(from))
        .collect()
}

fn load_source(source: Source, location: &str) -> Vec<Record> {
    let started = Instant::now();
    let records = match source {
        Source::Libris => linked::libris_records(location),
        _ => {
            let mut records = Vec::new();
            let reader = dumps::open_location(location, source.separator());
            let parse = |bytes: &[u8]| source.parse(bytes);
            dumps::scan_records(reader, source.separator(), parse, |record| {
                records.push(record)
            });
            records
        }
    };
    let elapsed = started.elapsed();
    eprintln!("{location}: {} records in {elapsed:.0?}", records.len());
    records
}

#[derive(Default)]
pub struct Library {
    records: Vec<Record>,
    groups: Vec<Vec<u32>>,
    languages: Vec<usize>,
    by_work: HashMap<u32, Vec<u32>>,
    by_title: HashMap<u64, Vec<u32>>,
    originals: HashMap<u64, u32>,
    generic: Generic,
    newest: u16,
}

pub struct Outcome {
    pub aliases: Vec<Vec<(u32, u32)>>,
    pub enriched: [usize; LANGUAGE_COUNT],
    pub added: [usize; LANGUAGE_COUNT],
}

struct Placed {
    work: u32,
    group: usize,
}

fn each<'a>(records: &'a [Record], group: &'a [u32]) -> impl Iterator<Item = &'a Record> {
    group.iter().map(|&index| &records[index as usize])
}

fn original_key(original: &str) -> u64 {
    hash(&[match_key(original).as_bytes()])
}

fn named_authors(books: &[Book], authors: &Authors) -> HashMap<String, u32> {
    let mut counts: HashMap<u32, u32> = HashMap::new();
    let open_library = books.iter().filter(|book| book.work < SYNTHETIC);
    for &author in open_library.flat_map(|book| &book.authors) {
        *counts.entry(author).or_default() += 1;
    }
    let mut best: HashMap<String, (u32, Reverse<u32>)> = HashMap::new();
    for (author, count) in counts {
        let Some(known) = authors.get(author) else {
            continue;
        };
        let entry = best
            .entry(name_key(known.name))
            .or_insert((count, Reverse(author)));
        *entry = (*entry).max((count, Reverse(author)));
    }
    best.into_iter()
        .map(|(name, (_, Reverse(author)))| (name, author))
        .collect()
}

impl Library {
    pub fn load(sources: &[(Source, String)]) -> Library {
        let records = std::thread::scope(|scope| {
            let loads: Vec<_> = sources
                .iter()
                .map(|(source, location)| scope.spawn(|| load_source(*source, location)))
                .collect();
            let loaded = loads
                .into_iter()
                .map(|load| load.join().expect("library load"));
            loaded.flatten().collect()
        });
        Library::from_records(records)
    }

    fn from_records(mut records: Vec<Record>) -> Library {
        records.sort_unstable_by_key(|record| (record.source, record.id));
        records.dedup_by_key(|record| (record.source, record.id));
        let shared = shared_isbns(&records);
        for record in &mut records {
            record.isbns.retain(|isbn| !shared.contains(isbn));
        }
        records.retain(|record| !record.isbns.is_empty());
        drop_publisher_series(&mut records);
        let generic = generic_titles(&records);
        let groups = grouped(&records, &generic);
        let languages = groups
            .iter()
            .map(|group| most_common(each(&records, group).map(|record| record.language)))
            .map(|language| language.unwrap_or(0))
            .collect();
        let mut originals: HashMap<u64, u32> = HashMap::new();
        for group in &groups {
            let named = each(&records, group).filter(|record| !record.original.is_empty());
            let keys: HashSet<u64> = named.map(|record| original_key(&record.original)).collect();
            keys.into_iter()
                .for_each(|key| *originals.entry(key).or_default() += 1);
        }
        let newest = records.iter().map(|record| record.year).max().unwrap_or(0);
        eprintln!(
            "libraries: {} records, {} works",
            records.len(),
            groups.len()
        );
        Library {
            records,
            groups,
            languages,
            originals,
            generic,
            newest,
            ..Library::default()
        }
    }

    fn members(&self, group: usize) -> impl Iterator<Item = &Record> {
        each(&self.records, &self.groups[group])
    }

    pub fn match_editions(&mut self, isbns: &Isbns) {
        if self.groups.is_empty() {
            return;
        }
        let mut owners: HashMap<u32, u32> = HashMap::new();
        for group in 0..self.groups.len() {
            for &isbn in self.members(group).flat_map(|record| &record.isbns) {
                owners.entry(isbn).or_insert(group as u32);
            }
        }
        let mut votes: HashMap<(u32, u32), u32> = HashMap::new();
        for (work, isbn) in isbns.entries() {
            if let Some(&group) = owners.get(&isbn) {
                *votes.entry((group, work)).or_default() += 1;
            }
        }
        let mut ranked: Vec<((u32, u32), u32)> = votes.into_iter().collect();
        ranked.sort_unstable_by_key(|&((group, work), count)| (group, Reverse(count), work));
        for best in ranked.chunk_by(|left, right| left.0.0 == right.0.0) {
            let ((group, work), _) = best[0];
            self.by_work.entry(work).or_default().push(group);
        }
        let mut keys = Vec::new();
        for group in 0..self.groups.len() {
            for record in self.members(group) {
                let writer = writer_key(&record.name);
                let titles = record.titles().filter(|_| !writer.is_empty());
                let hashed = titles.map(|title| title_hash(&writer, title));
                let language = record.language;
                let specific = hashed.filter(|&key| !self.generic.contains(&(key, language)));
                keys.extend(specific.map(|key| (key, group as u32)));
            }
        }
        for (key, group) in keys {
            let groups = self.by_title.entry(key).or_default();
            if groups.last() != Some(&group) {
                groups.push(group);
            }
        }
        eprintln!(
            "libraries: {} works matched by ISBN, {} title keys",
            self.by_work.len(),
            self.by_title.len()
        );
    }

    pub fn is_empty(&self) -> bool {
        self.groups.is_empty()
    }

    pub fn claim(&self, work: u32, titles: &[&str], author: Option<&str>) -> Vec<u32> {
        let mut groups = self.by_work.get(&work).cloned().unwrap_or_default();
        if let Some(author) = author.filter(|_| !self.by_title.is_empty()) {
            let writer = writer_key(author);
            for title in titles.iter().flat_map(|title| heads(title)) {
                let found = self.by_title.get(&title_hash(&writer, title));
                groups.extend(found.into_iter().flatten());
            }
        }
        groups.sort_unstable();
        groups.dedup();
        groups
    }

    pub fn facts(&self, groups: &[u32]) -> Facts {
        let mut facts = Facts::default();
        let records = groups
            .iter()
            .flat_map(|&group| self.members(group as usize));
        records.for_each(|record| facts.absorb(&record.facts()));
        facts
    }

    pub fn subjects(&self, groups: &[u32]) -> Vec<&'static str> {
        let records = groups
            .iter()
            .flat_map(|&group| self.members(group as usize));
        let mut subjects: Vec<&str> = records
            .flat_map(|record| record.subjects.iter().copied())
            .collect();
        subjects.sort_unstable();
        subjects.dedup();
        subjects
    }

    fn series(&self, group: usize) -> &str {
        let named = self.members(group).filter(|record| record.volume > 0);
        most_common(named.map(|record| record.series.as_str())).unwrap_or_default()
    }

    fn title(&self, group: usize) -> String {
        let series = self.series(group);
        let titles: Vec<String> = self
            .members(group)
            .map(|record| without_series(&record.title, series))
            .collect();
        let key = most_common(titles.iter().map(|title| match_key(title)));
        let mut matching = titles.into_iter();
        let found = matching.find(|title| key.as_ref() == Some(&match_key(title)));
        found.unwrap_or_default()
    }

    fn cover(&self, group: usize) -> Option<LibraryCover> {
        let printed = |record: &&Record| record.format & (HARDCOVER | PAPERBACK) != 0;
        let newest = |record: &&Record| (record.year, Reverse(record.id));
        let covered: Vec<&Record> = self
            .members(group)
            .filter(|record| record.cover_key().is_some())
            .collect();
        let best = covered.iter().copied().filter(printed).max_by_key(newest);
        let record = best.or_else(|| covered.into_iter().max_by_key(newest))?;
        Some(LibraryCover {
            source: record.source.cover(),
            key: record.cover_key()?,
            language: self.languages[group],
        })
    }

    fn writer(&self, group: usize) -> Option<&Record> {
        let named = self.members(group).filter(|record| record.author > 0);
        let mut named = named.filter(|record| !record.name.is_empty());
        named.next()
    }

    fn score(&self, group: usize) -> f32 {
        let formats = self
            .members(group)
            .fold(0, |all, record| all | record.format);
        let editions = self.groups[group].len() as f32;
        let translations = self
            .members(group)
            .filter(|record| !record.original.is_empty())
            .filter_map(|record| self.originals.get(&original_key(&record.original)))
            .max()
            .copied()
            .unwrap_or(0);
        let series = self.members(group).any(|record| record.volume > 0);
        let latest = self.members(group).map(|record| record.year).max();
        let age = f32::from(self.newest.saturating_sub(latest.unwrap_or(0)));
        let recency = (1.0 - age / RECENT_YEARS).clamp(0.0, 1.0);
        MIN_SCORE
            + 20.0 * (formats.count_ones() as f32).ln_1p()
            + 15.0 * editions.ln_1p()
            + 20.0 * (translations as f32).ln_1p()
            + if series { 15.0 } else { 0.0 }
            + 10.0 * recency
    }

    fn winners(&self, books: &[Book]) -> Vec<Option<usize>> {
        let mut winners: Vec<Option<usize>> = vec![None; self.groups.len()];
        let rank = |index: usize, group: u32, language: usize| {
            let book = &books[index];
            let by_isbn = self.by_work.get(&book.work);
            let by_isbn = by_isbn.is_some_and(|groups| groups.contains(&group));
            let claimed = book
                .claim
                .as_ref()
                .map_or(0.0, |claim| claim.scores[language]);
            (
                book.eligible[language],
                by_isbn,
                claimed,
                Reverse(book.work),
            )
        };
        for (index, book) in books.iter().enumerate() {
            let Some(claim) = &book.claim else { continue };
            for &group in &claim.groups {
                let language = self.languages[group as usize];
                let slot = &mut winners[group as usize];
                let better = slot.is_none_or(|known| {
                    let order =
                        rank(index, group, language).partial_cmp(&rank(known, group, language));
                    order.is_some_and(|order| order.is_gt())
                });
                if better && claim.eligible[language] {
                    *slot = Some(index);
                }
            }
        }
        winners
    }

    fn canonical_writers(
        &self,
        books: &[Book],
        winners: &[Option<usize>],
        authors: &Authors,
    ) -> HashMap<u32, u32> {
        let mut pairs: Vec<(u32, u32)> = Vec::new();
        for (group, winner) in winners.iter().enumerate() {
            let (Some(index), Some(record)) = (winner, self.writer(group)) else {
                continue;
            };
            let Some(&author) = books[*index].authors.first() else {
                continue;
            };
            let named = authors.get(author).map(|known| writer_key(known.name));
            if named == Some(writer_key(&record.name)) {
                pairs.push((record.author, author));
            }
        }
        let mut writers = HashMap::new();
        pairs.sort_unstable();
        for same_writer in pairs.chunk_by(|left, right| left.0 == right.0) {
            let ids = same_writer.iter().map(|&(_, author)| author);
            writers.insert(same_writer[0].0, most_common(ids).expect("pairs"));
        }
        writers
    }

    fn apply_claim(
        &self,
        book: &mut Book,
        groups: &[usize],
        names: &HashMap<String, u32>,
        authors: &Authors,
    ) {
        let Some(claim) = book.claim.take() else {
            return;
        };
        for language in groups.iter().map(|&group| self.languages[group]) {
            book.scores[language] = claim.scores[language];
            book.eligible[language] = claim.eligible[language];
        }
        book.tags = claim.tags;
        book.editions = claim.editions;
        book.library_cover = groups.iter().find_map(|&group| self.cover(group));
        let mut canonical: Vec<u32> = Vec::new();
        for &author in &book.authors {
            let named = authors
                .get(author)
                .and_then(|known| names.get(&name_key(known.name)));
            let id = named.copied().unwrap_or(author);
            if !canonical.contains(&id) {
                canonical.push(id);
            }
        }
        book.authors = canonical;
    }

    fn author(
        writer: &Record,
        writers: &HashMap<u32, u32>,
        names: &mut HashMap<String, u32>,
        authors: &mut Authors,
    ) -> u32 {
        if let Some(&known) = writers.get(&writer.author) {
            return known;
        }
        *names.entry(name_key(&writer.name)).or_insert_with(|| {
            let writer_name = writer_key(&writer.name);
            let mut id = SYNTHETIC + writer.author;
            while authors
                .get(id)
                .is_some_and(|known| writer_key(known.name) != writer_name)
            {
                id += 1;
            }
            authors.add(id, &writer.name);
            id
        })
    }

    fn orphan(&self, group: usize, work: u32, author: u32) -> Option<Book> {
        let title = self.title(group);
        if title.is_empty() || catalog::is_bad_title(&title) {
            return None;
        }
        let language = self.languages[group];
        let facts = self.facts(&[group as u32]);
        let subjects = self.subjects(&[group as u32]);
        let mut scores = [0.0; LANGUAGE_COUNT];
        let mut eligible = [false; LANGUAGE_COUNT];
        scores[language] = self.score(group);
        eligible[language] = true;
        let years = self.members(group).map(|record| record.year);
        Some(Book {
            work,
            title,
            subtitle: String::new(),
            authors: vec![author],
            tags: tags::confident_tags(subjects.into_iter(), &facts.classes, facts.editions),
            description: String::new(),
            description_language: None,
            covers: Vec::new(),
            year: years.filter(|&year| year > 0).min().unwrap_or(0),
            editions: facts.editions,
            signal: Signal::default(),
            scores,
            eligible,
            claim: None,
            library_cover: self.cover(group),
        })
    }

    fn identity(&self, group: usize, previous: &HashSet<u32>) -> u32 {
        let works = self.members(group).map(Record::work);
        let kept = works.filter(|work| previous.contains(work)).min();
        let oldest = || self.members(group).map(Record::work).min().expect("member");
        kept.unwrap_or_else(oldest)
    }

    pub fn extend(
        &self,
        books: &mut Vec<Book>,
        claimants: Vec<Book>,
        titles: &mut Titles,
        isbns: &mut Isbns,
        authors: &mut Authors,
        previous: &HashSet<u32>,
    ) -> Outcome {
        let mut outcome = Outcome {
            aliases: vec![Vec::new(); LANGUAGES.len()],
            enriched: [0; LANGUAGE_COUNT],
            added: [0; LANGUAGE_COUNT],
        };
        if self.groups.is_empty() {
            return outcome;
        }
        let claimed: HashSet<u32> = claimants.iter().map(|book| book.work).collect();
        books.extend(claimants);
        books.sort_unstable_by_key(|book| book.work);
        let winners = self.winners(books);
        let mut names = named_authors(books, authors);
        let mut won: BTreeMap<usize, Vec<usize>> = BTreeMap::new();
        for (group, winner) in winners.iter().enumerate() {
            if let Some(index) = winner {
                won.entry(*index).or_default().push(group);
            }
        }
        let mut placed: Vec<Placed> = Vec::new();
        for (&index, groups) in &won {
            self.apply_claim(&mut books[index], groups, &names, authors);
            outcome.enriched[self.languages[groups[0]]] += 1;
            let work = books[index].work;
            placed.extend(groups.iter().map(|&group| Placed { work, group }));
        }
        books.iter_mut().for_each(|book| book.claim = None);
        let writers = self.canonical_writers(books, &winners, authors);
        let mut orphans = Vec::new();
        for group in (0..self.groups.len()).filter(|&group| winners[group].is_none()) {
            let Some(writer) = self.writer(group) else {
                continue;
            };
            let author = Library::author(writer, &writers, &mut names, authors);
            let work = self.identity(group, previous);
            let Some(book) = self.orphan(group, work, author) else {
                continue;
            };
            outcome.added[self.languages[group]] += 1;
            orphans.push(book);
            placed.push(Placed { work, group });
        }
        books.extend(orphans);
        books.retain(|book| !claimed.contains(&book.work) || book.eligible.contains(&true));
        books.sort_unstable_by_key(|book| book.work);
        for &Placed { work, group } in &placed {
            let renamed = self.members(group).map(Record::work);
            let renamed = renamed.filter(|old| *old != work && previous.contains(old));
            outcome.aliases[self.languages[group]].extend(renamed.map(|old| (old, work)));
        }
        self.index(&placed, titles, isbns);
        outcome
    }

    fn index(&self, placed: &[Placed], titles: &mut Titles, isbns: &mut Isbns) {
        let named: HashSet<(u32, usize)> = placed
            .iter()
            .map(|placed| (placed.work, self.languages[placed.group]))
            .filter(|&(work, language)| titles.has_local_title(work, language))
            .collect();
        let mut rows = Vec::new();
        for &Placed { work, group } in placed {
            let series = self.series(group);
            for record in self.members(group) {
                let language = record.language as u8;
                rows.extend(record.isbns.iter().map(|&isbn| (work, language, isbn)));
                let volume = (record.series.as_str(), record.volume);
                let kept = named.contains(&(work, record.language));
                if kept && record.series.is_empty() {
                    continue;
                }
                let shown = without_series(&record.title, series);
                let title = match kept {
                    true => String::new(),
                    false => catalog::edition_title(&shown, ""),
                };
                titles.add((work, language), &title, volume, record.year);
            }
        }
        titles.sort();
        isbns.extend(rows);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEADER: &str = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rdf:RDF\n\
                          \txmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n";

    #[derive(Clone, Copy)]
    struct Edition<'a> {
        id: &'a str,
        isbn: &'a str,
        title: &'a str,
        citation: &'a str,
        original: &'a str,
        statement: &'a str,
        medium: &'a str,
        links: &'a str,
    }

    const STRAW: Edition = Edition {
        id: "1378381130",
        isbn: "9783833911316",
        title: "Jetzt reicht's!",
        citation: "Gregs Tagebuch ; 3",
        original: "Diary of a wimpy kid - The last straw",
        statement: "Jeff Kinney ; aus dem Englischen von Collin McMahon",
        medium: "1044",
        links: "",
    };

    const DNB: &str = "https://d-nb.info";
    const RDA: &str = "http://rdaregistry.info/termList";
    const ISO: &str = "http://id.loc.gov/vocabulary/iso639-2";
    const THEMA_URI: &str = "https://ns.editeur.org/thema";
    const CONCEPT: &str = "http://www.w3.org/2004/02/skos/core#Concept";

    fn rdf(edition: &Edition) -> String {
        let link = match edition.links {
            "" => String::new(),
            id => format!("\t<dcterms:isFormatOf rdf:resource=\"{DNB}/{id}\"/>\n"),
        };
        let original = match edition.original {
            "" => String::new(),
            title => format!(
                "\t<schema:exampleOfWork>\n\t\t<rdf:Description rdf:nodeID=\"n1\">\n\
                 \t\t\t<gndo:preferredName>{title}</gndo:preferredName>\n\
                 \t\t</rdf:Description>\n\t</schema:exampleOfWork>\n"
            ),
        };
        format!(
            "<rdf:Description rdf:about=\"{DNB}/{id}\">\n\
             \t<dcterms:medium rdf:resource=\"{RDA}/RDACarrierType/{medium}\"/>\n\
             \t<rdau:P60049 rdf:resource=\"{RDA}/RDAContentType/1020\"/>\n\
             \t<bibo:isbn13 rdf:datatype=\"x#string\">{isbn}</bibo:isbn13>\n\
             \t<dcterms:language rdf:resource=\"{ISO}/ger\"/>\n\
             \t<rdau:P60327 rdf:datatype=\"x#string\">{statement}</rdau:P60327>\n\
             \t<dc:title rdf:datatype=\"x#string\">{title}</dc:title>\n\
             {original}\
             \t<marcRole:aut rdf:resource=\"{DNB}/gnd/133953513\"/>\n\
             \t<dcterms:bibliographicCitation>{citation}\
             </dcterms:bibliographicCitation>\n\
             {link}\
             \t<schema:genre>\n\t\t<rdf:Description rdf:nodeID=\"n2\">\n\
             \t\t\t<skos:notation rdf:datatype=\"x#notation\">250</skos:notation>\n\
             \t\t\t<rdf:type rdf:resource=\"{CONCEPT}\"/>\n\
             \t\t</rdf:Description>\n\t</schema:genre>\n\
             \t<dcterms:subject rdf:resource=\"{THEMA_URI}/YFQ\"/>\n\
             \t<dcterms:subject rdf:resource=\"{THEMA_URI}/5AL\"/>\n\
             \t<dcterms:issued rdf:datatype=\"x#string\">2024</dcterms:issued>\n",
            id = edition.id,
            medium = edition.medium,
            isbn = edition.isbn,
            statement = edition.statement,
            title = edition.title,
            citation = edition.citation,
        )
    }

    fn library(editions: &[Edition]) -> Library {
        let separator = std::str::from_utf8(Source::Dnb.separator()).expect("utf-8");
        let dump = HEADER.to_string()
            + &editions.iter().map(rdf).collect::<Vec<_>>().join(separator)
            + separator
            + "</rdf:RDF>\n";
        let mut records = Vec::new();
        let reader = Box::new(std::io::Cursor::new(dump.into_bytes()));
        dumps::scan_records(
            reader,
            Source::Dnb.separator(),
            |bytes| Source::Dnb.parse(bytes),
            |record| {
                records.push(record);
            },
        );
        Library::from_records(records)
    }

    #[test]
    fn only_german_books_kept() {
        let english = rdf(&STRAW).replace("/ger\"", "/eng\"");
        assert!(Source::Dnb.parse(english.as_bytes()).is_none());
        let russian = rdf(&STRAW).replace(STRAW.isbn, "9785992504408");
        assert!(Source::Dnb.parse(russian.as_bytes()).is_none());
        let calendar = rdf(&STRAW).replace("thema/YFQ", "thema/YZSD");
        assert!(Source::Dnb.parse(calendar.as_bytes()).is_none());
        let teaching = rdf(&STRAW).replace("thema/YFQ", "thema/YPCA");
        assert!(Source::Dnb.parse(teaching.as_bytes()).is_none());
        let untitled = rdf(&STRAW).replace(STRAW.title, "Arztroman");
        assert!(Source::Dnb.parse(untitled.as_bytes()).is_none());
        let puzzles = rdf(&STRAW).replace(">250<", ">290<");
        assert!(Source::Dnb.parse(puzzles.as_bytes()).is_none());
        assert!(Source::Dnb.parse(rdf(&STRAW).as_bytes()).is_some());
    }

    #[test]
    fn record_parsed() {
        let library = library(&[STRAW]);
        let record = &library.records[0];
        assert_eq!(record.id, 137838113);
        assert_eq!(record.language, 1);
        assert_eq!(record.title, "Jetzt reicht's!");
        assert_eq!(record.series, "Gregs Tagebuch");
        assert_eq!(record.volume, 3);
        assert_eq!(record.name, "Jeff Kinney");
        assert_eq!(record.author, 13395351);
        assert_eq!(record.original, "Diary of a wimpy kid - The last straw");
        assert_eq!(record.year, 2024);
        assert_eq!(
            record.isbns,
            vec![catalog::isbn_key(STRAW.isbn).expect("isbn")]
        );
        assert_eq!(record.classes.kid, 1);
        assert!(record.subjects.contains(&"juvenile fiction"));
        assert!(record.subjects.contains(&"middle grade"));
    }

    #[test]
    fn formats_grouped_into_one_work() {
        let ebook = Edition {
            id: "1068521406",
            isbn: "9783732508709",
            title: "Gregs Tagebuch 3 - Jetzt reicht's!",
            citation: "",
            original: "",
            medium: "1018",
            links: "1378381130",
            ..STRAW
        };
        let audio = Edition {
            id: "1148232885",
            isbn: "9783838786551",
            original: "",
            statement: "Jeff Kinney ; gelesen von Marco Eßer",
            medium: "1003",
            ..STRAW
        };
        let next = Edition {
            id: "1148232893",
            isbn: "9783838786568",
            title: "Ich war's nicht!",
            citation: "Gregs Tagebuch ; 4",
            original: "Diary of a wimpy kid - Dog days",
            ..STRAW
        };
        let library = library(&[STRAW, ebook, audio, next]);
        assert_eq!(library.groups.len(), 2);
        let third = library.groups.iter().position(|group| group.len() == 3);
        let third = third.expect("group");
        assert_eq!(library.title(third), "Jetzt reicht's!");
        assert_eq!(library.series(third), "Gregs Tagebuch");
    }

    #[test]
    fn statements_named() {
        for (statement, name) in [
            (
                "Siegfried Lenz. [Ill. von Helmut Hellmessen",
                "Siegfried Lenz",
            ),
            ("J. K. Rowling ; aus dem Englischen", "J. K. Rowling"),
            (
                "Charles Dickens. Übers. von Trude Geissler",
                "Charles Dickens",
            ),
            ("Text und Illustrationen: Franz Hohler", "Franz Hohler"),
            ("von Erich Ka\u{308}stner", "Erich Kästner"),
            ("mit vielen Bildern von Walter Trier", ""),
            ("Autorinnen und Autoren: Kollektiv", "Kollektiv"),
            ("Autorinnen und Autoren", ""),
            (
                "Hörspielfassung: Theresia Singer ; Besetzung: Greg: Marco Eßer",
                "",
            ),
            ("Erzählerin Laura Maire", ""),
            (
                "Douglas Preston &amp; Lincoln Child. Aus dem Amerikan.",
                "Douglas Preston",
            ),
            ("Douglas Preston/Lincoln Child", "Douglas Preston"),
            ("Werner Schlummer (Hrsg.)", "Werner Schlummer"),
            ("Autorin Nina Wilkening", "Nina Wilkening"),
            (
                "Dorothy und Thomas Hoobler. Aus dem Amerikan. von Yvonne Hergane",
                "Dorothy Hoobler",
            ),
            ("Jacob &amp; Wilhelm Grimm", "Jacob Grimm"),
            ("Brinx und Kömmerling", "Brinx"),
            ("Anna und die wilden Tiere", "Anna"),
        ] {
            assert_eq!(author_name(statement), name, "{statement}");
        }
    }

    #[test]
    fn apostrophes_ignored_in_keys() {
        assert_eq!(
            match_key("Die Toten vom Gare d’Austerlitz"),
            match_key("Toten vom Gare d‘Austerlitz")
        );
        assert_eq!(match_key("A Pigeon`s Tale"), match_key("A Pigeon's Tale"));
    }

    #[test]
    fn punctuation_ignored_in_keys() {
        assert_eq!(
            match_key("Darth Vader: Schatten und Geheimnisse"),
            match_key("Darth Vader - Schatten und Geheimnisse")
        );
        assert_eq!(
            match_key("Novelle / Das Märchen"),
            match_key("Novelle, Das Märchen")
        );
        assert_ne!(
            match_key("Darth Vader: Der Auserwählte"),
            match_key("Darth Vader")
        );
        let parenthesized = match_key("Millionäre küsst man (nicht)");
        assert_eq!(parenthesized, match_key("Millionäre küsst man nicht"));
    }

    #[test]
    fn volume_labels_become_series_titles() {
        let parsed = |title: &str, citation: &str| {
            let edition = Edition {
                title,
                citation,
                ..STRAW
            };
            Source::Dnb
                .parse(rdf(&edition).as_bytes())
                .map(|record| (record.title, record.series, record.volume))
        };
        let numbered = |title: &str, series: &str, volume| {
            Some((title.to_string(), series.to_string(), volume))
        };
        let labelled = parsed("Band 3", "Gregs Tagebuch ; 3");
        assert_eq!(labelled, numbered("Gregs Tagebuch 3", "Gregs Tagebuch", 3));
        let named = parsed("Gregs Tagebuch, Band 3", "");
        assert_eq!(named, numbered("Gregs Tagebuch 3", "Gregs Tagebuch", 3));
        assert_eq!(parsed("Band 3", ""), None);
        assert_eq!(parsed("Gregs Tagebuch - Folge 1946", ""), None);
    }

    fn subfield_xml(code: char, text: &str) -> String {
        format!(r#"<marc:subfield code="{code}">{text}</marc:subfield>"#)
    }

    fn datafield(tag: &str, subfields: &[(char, &str)]) -> String {
        let inner = subfields
            .iter()
            .map(|(code, text)| subfield_xml(*code, text))
            .collect::<Vec<_>>()
            .join("\n");
        format!("<marc:datafield tag=\"{tag}\">\n{inner}\n</marc:datafield>\n")
    }

    fn marc(control: &str, fixed: &str, fields: &[(&str, &[(char, &str)])]) -> String {
        let data: String = fields
            .iter()
            .map(|(tag, subfields)| datafield(tag, subfields))
            .collect();
        format!(
            "<marc:record>\n  <leader>02673nam a22006972i 4500</leader>\n\
             <marc:controlfield tag=\"001\">{control}</marc:controlfield>\n\
             <marc:controlfield tag=\"008\">{fixed}</marc:controlfield>\n{data}"
        )
    }

    const NOVEL: &str = "201023s2020    er            000 1 est d";

    fn erb(fields: &[(&str, &[(char, &str)])]) -> Option<Record> {
        Source::Erb.parse(marc("b5536158x", NOVEL, fields).as_bytes())
    }

    #[test]
    fn erb_novel_parsed() {
        let record = erb(&[
            ("020", &[('a', "9789916142257"), ('q', "(köites)")]),
            ("041", &[('a', "est"), ('h', "eng")]),
            ("100", &[('a', "Schwab, V. E.,"), ('e', "autor.")]),
            (
                "245",
                &[('a', "Addie LaRue nähtamatu elu /"), ('c', "V. E. Schwab")],
            ),
            ("246", &[('a', "The invisible life of Addie LaRue.")]),
            ("490", &[('a', "Põnevikud ;"), ('v', "3.")]),
            ("655", &[('a', "romaanid (vormimärksõna)")]),
            (
                "856",
                &[('u', "https://www.digar.ee/arhiiv/nlib-digar:882325")],
            ),
        ])
        .expect("record");
        assert_eq!(record.id, SYNTHETIC + 5536158);
        assert_eq!(record.work(), 2 * SYNTHETIC + 5536158);
        assert_eq!(record.language, 4);
        assert_eq!(record.title, "Addie LaRue nähtamatu elu");
        assert_eq!(record.name, "V. E. Schwab");
        assert_eq!(record.original, "The invisible life of Addie LaRue");
        assert_eq!((record.series.as_str(), record.volume), ("Põnevikud", 3));
        assert_eq!((record.format, record.digar), (HARDCOVER, 882325));
        assert_eq!(record.cover_key(), Some(882325));
        assert_eq!(record.subjects, ["fiction"]);
        let authors = SYNTHETIC..SYNTHETIC + ERB_AUTHOR_IDS as u32;
        assert!(authors.contains(&record.author));
    }

    #[test]
    fn erb_volumes_and_contributors_read() {
        let record = erb(&[
            ("020", &[('a', "9789916099896"), ('q', "(pdf)")]),
            ("245", &[('a', "Tõde ja õigus :"), ('n', "1. [kd.] /")]),
            ("700", &[('a', "Helk, Vello,"), ('e', "tõlkija.")]),
            ("700", &[('a', "Tammsaare, A. H.,"), ('e', "autor.")]),
            ("655", &[('a', "lastekirjandus"), ('a', "pildiraamatud")]),
        ])
        .expect("record");
        assert_eq!(record.title, "Tõde ja õigus 1");
        assert_eq!(
            (record.series.as_str(), record.volume),
            ("Tõde ja õigus", 1)
        );
        assert_eq!(record.name, "A. H. Tammsaare");
        assert_eq!((record.format, record.cover_key()), (EBOOK, None));
        assert_eq!((record.classes.kid, record.classes.picture), (1, 1));
        assert_eq!(record.subjects, ["juvenile fiction", "picture books"]);
    }

    #[test]
    fn erb_year_parts_are_not_volumes() {
        let record = erb(&[
            ("020", &[('a', "9985303601")]),
            (
                "245",
                &[('a', "Harry Potter ja tarkade kivi."), ('n', "[1. aasta")],
            ),
        ])
        .expect("record");
        assert_eq!(
            (record.title.as_str(), record.volume),
            ("Harry Potter ja tarkade kivi", 0)
        );
    }

    #[test]
    fn erb_non_literature_skipped() {
        let isbn = ("020", &[('a', "9789949059324")][..]);
        let title = ("245", &[('a', "Matemaatika 5. klassile")][..]);
        let textbook = ("655", &[('a', "õpikud"), ('a', "ilukirjandus")][..]);
        assert!(erb(&[isbn, title, textbook]).is_none());
        assert!(erb(&[title, ("655", &[('a', "romaanid")])]).is_none());
        let english = NOVEL.replace("est d", "eng d");
        let record = marc("b5536158x", &english, &[isbn, title]);
        assert!(Source::Erb.parse(record.as_bytes()).is_none());
        let plain = NOVEL.replace(" 1 est", " 0 est");
        let record = marc("b5536158x", &plain, &[isbn, title]);
        assert!(Source::Erb.parse(record.as_bytes()).is_none());
        let parallel = ("245", &[('a', "Farsi kroonikad = The chronicles /")][..]);
        let titled = erb(&[isbn, parallel]).map(|record| record.title);
        assert_eq!(titled.as_deref(), Some("Farsi kroonikad"));
    }

    #[test]
    fn colliding_library_authors_split() {
        let mut authors = Authors::default();
        let writer = Record {
            author: SYNTHETIC + 7,
            name: "Mari Tamm".to_string(),
            ..Record::default()
        };
        authors.add(2 * SYNTHETIC + 7, "Jaan Kask");
        let mut names = HashMap::new();
        let id = Library::author(&writer, &HashMap::new(), &mut names, &mut authors);
        assert_eq!(id, 2 * SYNTHETIC + 8);
        let renamed = Record {
            name: "Tamm, Mari".to_string(),
            ..writer
        };
        let again = Library::author(&renamed, &HashMap::new(), &mut names, &mut authors);
        assert_eq!(again, id);
    }

    #[test]
    fn genre_issues_dropped() {
        let parsed = |title| {
            let edition = Edition { title, ..STRAW };
            Source::Dnb.parse(rdf(&edition).as_bytes())
        };
        for title in [
            "Familienroman",
            "Sophienlust 348 - Familienroman",
            "Toni der Hüttenwirt 233 – Heimatroman",
            "G.F. Barner 166 – Western",
            "Butler Parker Paket 2 – Kriminalroman",
        ] {
            assert!(parsed(title).is_none(), "{title}");
        }
        for title in [
            "Atlan 183: Der Mutantenjäger",
            "Roman eines Schicksallosen",
            "Romane",
        ] {
            assert!(parsed(title).is_some(), "{title}");
        }
        let escaped = parsed("bark &amp;amp; bite").map(|record| record.title);
        assert_eq!(escaped.as_deref(), Some("bark & bite"));
    }

    #[test]
    fn set_isbns_do_not_merge_volumes() {
        let first = Edition {
            id: "1070098897",
            isbn: "9783551556837",
            title: "Der verschwundene Halbgott",
            citation: "Helden des Olymp ; 1",
            original: "",
            ..STRAW
        };
        let third = Edition {
            id: "1370098899",
            isbn: "9783551556851",
            title: "Das Zeichen der Athene",
            citation: "Helden des Olymp ; 3",
            ..first
        };
        let with_set = |edition: &Edition| {
            let set = "<bibo:isbn13>9783551556899</bibo:isbn13>\n\t<dcterms:language";
            let text = rdf(edition).replacen("<dcterms:language", set, 1);
            Source::Dnb.parse(text.as_bytes()).expect("record")
        };
        let records = vec![with_set(&first), with_set(&third)];
        assert!(records.iter().all(|record| record.isbns.len() == 2));
        let library = Library::from_records(records);
        assert_eq!(library.groups.len(), 2);
        assert!(library.records.iter().all(|record| record.isbns.len() == 1));
    }

    #[test]
    fn generic_subtitles_do_not_merge_works() {
        let edition = |id, isbn, title| Edition {
            id,
            isbn,
            title,
            citation: "",
            original: "",
            ..STRAW
        };
        let library = library(&[
            edition("1000000011", "9783732500017", "Gregs Ranch - Western"),
            edition("1000000021", "9783732500024", "Gregs Rache - Western"),
            edition("1000000031", "9783732500031", "Gregs Ritt - Western"),
            edition("1000000061", "9783732500062", "Gregs Ruhm - Western"),
            edition(
                "1000000041",
                "9783732500048",
                "Der Hobbit oder hin und zurück",
            ),
            edition("1000000051", "9783732500055", "Der Hobbit"),
        ]);
        assert_eq!(library.groups.len(), 5);
    }

    #[test]
    fn series_originals_do_not_merge_volumes() {
        let volume = |id, isbn, title| Edition {
            id,
            isbn,
            title,
            citation: "",
            original: "Diary of a wimpy kid",
            ..STRAW
        };
        let library = library(&[
            volume("1000000101", "9783732500109", "Gibt's Probleme?"),
            volume("1000000111", "9783732500116", "Jetzt reicht's!"),
            volume("1000000121", "9783732500123", "Ich war's nicht!"),
            volume("1000000131", "9783732500130", "Geht's noch?"),
        ]);
        assert_eq!(library.groups.len(), 4);
    }

    #[test]
    fn series_named_volumes_stay_apart() {
        let volume = |id, isbn, citation| Edition {
            id,
            isbn,
            title: "Gregs Tagebuch",
            citation,
            original: "",
            ..STRAW
        };
        let library = library(&[
            volume("1000000101", "9783732500109", "Gregs Tagebuch ; 1"),
            volume("1000000111", "9783732500116", "Gregs Tagebuch ; 2"),
            volume("1000000121", "9783732500123", "Gregs Tagebuch ; 3"),
            volume("1000000131", "9783732500130", "Gregs Tagebuch ; 4"),
        ]);
        assert_eq!(library.groups.len(), 4);
    }

    #[test]
    fn title_variants_cover_alternatives() {
        assert!(variants("Jetzt reicht's! (Roman)").any(|title| title == "Jetzt reicht's!"));
        let alternative: Vec<&str> = variants("Fabian oder Der Gang vor die Hunde").collect();
        assert!(alternative.contains(&"Fabian"));
        assert!(alternative.contains(&"Der Gang vor die Hunde"));
        assert!(heads("Grüne Tomaten. Roman.").any(|title| title == "Grüne Tomaten"));
        let subtitled = format!("Die Spur der Katze{}Band 1", catalog::SUBTITLE_SEPARATOR);
        assert!(heads(&subtitled).any(|title| title == "Die Spur der Katze"));
        assert!(!heads("Das Sams - Sams in Gefahr").any(|title| title == "Das Sams"));
        assert!(!variants("Grüne Tomaten. Roman.").any(|title| title == "Grüne Tomaten"));
        let dashed = "Spinster Girls 3 – Was ist schon Liebe?";
        assert!(variants(dashed).any(|title| title == "Was ist schon Liebe?"));
    }

    #[test]
    fn initials_ignored_in_writer_keys() {
        assert_eq!(
            writer_key("A. Meredith Walters"),
            writer_key("Meredith Walters")
        );
        assert_eq!(writer_key("Kinney, Jeff"), writer_key("Jeff Kinney"));
        assert_eq!(writer_key("J. K. Rowling"), writer_key("Joanne K. Rowling"));
        assert_ne!(writer_key("Jeff Kinney"), writer_key("Kate Kinney"));
        assert_eq!(writer_key("Sir Walter Scott"), writer_key("Walter Scott"));
        assert_eq!(
            writer_key("Frances Hodgson Burnett"),
            writer_key("Frances H. Burnett")
        );
    }

    #[test]
    fn series_prefix_removed() {
        for (title, series, expected) in [
            (
                "Gregs Tagebuch 3 - Jetzt reicht's!",
                "Gregs Tagebuch",
                "Jetzt reicht's!",
            ),
            (
                "Gregs Tagebuch. Ich war's nicht!",
                "Gregs Tagebuch",
                "Ich war's nicht!",
            ),
            (
                "Gregs Tagebuch Spezial 1. Mach's selbst",
                "Gregs Tagebuch",
                "",
            ),
            ("Gregs Tagebuch 3", "Gregs Tagebuch", "Gregs Tagebuch 3"),
        ] {
            let expected = if expected.is_empty() { title } else { expected };
            assert_eq!(without_series(title, series), expected, "{title}");
        }
    }

    #[test]
    fn publisher_series_dropped() {
        let volume = |name: &str, title: &str, series: &str, volume| Record {
            name: name.to_string(),
            title: title.to_string(),
            series: series.to_string(),
            volume,
            ..Record::default()
        };
        let mut records = vec![
            volume("Henryk Sienkiewicz", "Quo vadis", "Kanon Lektur", 1),
            volume("Bolesław Prus", "Lalka", "Kanon Lektur", 2),
            volume("Rudyard Kipling", "Księga dżungli", "Kanon Lektur", 8),
            volume(
                "Andrzej Sapkowski",
                "Ostatnie życzenie",
                "Saga o wiedźminie",
                1,
            ),
            volume(
                "Andrzej Sapkowski",
                "Miecz przeznaczenia",
                "Saga o wiedźminie",
                2,
            ),
            volume("Ben Nevis", "Die drei ??? und der Fluch", "Die drei ???", 3),
            volume(
                "Marco Sonnleitner",
                "Die drei ??? und das Phantom",
                "Die drei ???",
                4,
            ),
            volume(
                "Kari Erlhoff",
                "Die drei ??? und die Rache",
                "Die drei ???",
                5,
            ),
        ];
        drop_publisher_series(&mut records);
        let kept: Vec<u16> = records.iter().map(|record| record.volume).collect();
        assert_eq!(kept, [0, 0, 0, 1, 2, 3, 4, 5]);
    }

    #[test]
    fn aliases_follow_later_moves() {
        let books: Vec<Book> = Vec::new();
        let merged = aliases(
            &[(SYNTHETIC + 5, SYNTHETIC + 9)],
            &[(SYNTHETIC + 9, 42)],
            &books,
        );
        assert_eq!(merged, vec![(SYNTHETIC + 5, 42), (SYNTHETIC + 9, 42)]);
    }

    fn per_language<T: Copy + Default>(values: &[(usize, T)]) -> [T; LANGUAGE_COUNT] {
        let mut all = [T::default(); LANGUAGE_COUNT];
        values
            .iter()
            .for_each(|&(language, value)| all[language] = value);
        all
    }

    fn open_library_book(work: u32, title: &str, author: u32) -> Book {
        Book {
            work,
            title: title.to_string(),
            subtitle: String::new(),
            authors: vec![author],
            tags: Vec::new(),
            description: String::new(),
            description_language: None,
            covers: Vec::new(),
            year: 0,
            editions: 1,
            signal: Signal::default(),
            scores: per_language(&[(0, 200.0)]),
            eligible: per_language(&[(0, true)]),
            claim: None,
            library_cover: None,
        }
    }

    fn claimed(library: &Library, mut book: Book, authors: &Authors) -> Book {
        let name = authors.get(book.authors[0]).map(|author| author.name);
        let groups = library.claim(book.work, &[&book.title], name);
        if !groups.is_empty() {
            book.claim = Some(Box::new(dumps::Claim {
                groups,
                scores: per_language(&[(0, 200.0), (1, 250.0)]),
                eligible: per_language(&[(0, true), (1, true)]),
                tags: Vec::new(),
                editions: 2,
            }));
        }
        book
    }

    fn straw_isbn() -> u32 {
        catalog::isbn_key(STRAW.isbn).expect("isbn")
    }

    struct Run {
        books: Vec<Book>,
        outcome: Outcome,
        isbns: Isbns,
        titles: Titles,
    }

    fn run(library: &Library, books: Vec<Book>, previous: &[u32]) -> Run {
        titled_run(library, books, previous, Titles::default())
    }

    fn titled_run(library: &Library, books: Vec<Book>, previous: &[u32], titles: Titles) -> Run {
        let (mut books, mut titles) = (books, titles);
        let mut isbns = Isbns::default();
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let previous: HashSet<u32> = previous.iter().copied().collect();
        let outcome = library.extend(
            &mut books,
            Vec::new(),
            &mut titles,
            &mut isbns,
            &mut authors,
            &previous,
        );
        Run {
            books,
            outcome,
            isbns,
            titles,
        }
    }

    fn matched(editions: &[Edition], work: u32) -> Library {
        let mut library = library(editions);
        library.match_editions(&Isbns::from_rows(vec![(work, 0, straw_isbn())]));
        library
    }

    #[test]
    fn claimed_titles_keep_open_library_names() {
        let library = matched(&[STRAW], 42);
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let mut titles = Titles::default();
        titles.add((42, 1), "Ich war's nicht!", ("", 0), 2009);
        let book = open_library_book(42, "The Last Straw", 7);
        let run = titled_run(
            &library,
            vec![claimed(&library, book, &authors)],
            &[],
            titles,
        );
        let records = run.titles.of(42);
        let names = records.iter().map(|record| run.titles.title(record));
        let names: Vec<&str> = names.filter(|name| !name.is_empty()).collect();
        assert_eq!(names, ["Ich war's nicht!"]);
        let series = records
            .iter()
            .map(|record| (run.titles.series(record), record.position));
        assert!(series.collect::<Vec<_>>().contains(&("Gregs Tagebuch", 3)));
        assert_eq!(run.isbns.of(42, 1), &[straw_isbn()]);
    }

    #[test]
    fn open_library_series_prefix_dropped_for_library_series() {
        let library = matched(&[STRAW], 42);
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let mut titles = Titles::default();
        titles.add(
            (42, 1),
            "Gregs Tagebuch 03 . Jetzt reicht's!",
            ("", 0),
            2009,
        );
        let book = claimed(
            &library,
            open_library_book(42, "The Last Straw", 7),
            &authors,
        );
        let run = titled_run(&library, vec![book], &[], titles);
        let entries = catalog::candidates(&run.books, &run.titles, &authors, 1, None);
        let entry = &entries[0];
        assert_eq!(entry.title, "Jetzt reicht's!");
        assert_eq!(
            (entry.series.as_str(), entry.series_position),
            ("Gregs Tagebuch", 3)
        );
        assert_eq!(entry.alternate, "The Last Straw");
    }

    #[test]
    fn open_library_volumes_join_library_series() {
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let mut titles = Titles::default();
        let prefixed = "Gregs Tagebuch 01 . Von Idioten umzingelt!";
        titles.add((42, 1), prefixed, ("", 0), 2008);
        let mut book = open_library_book(42, "Diary of a Wimpy Kid", 7);
        book.eligible[1] = true;
        book.scores[1] = 200.0;
        let run = titled_run(&library(&[STRAW]), vec![book], &[], titles);
        let entries = catalog::candidates(&run.books, &run.titles, &authors, 1, None);
        let mut shelved: Vec<(&str, &str, u16)> = entries
            .iter()
            .map(|entry| (&entry.title[..], &entry.series[..], entry.series_position))
            .collect();
        shelved.sort_unstable_by_key(|&(.., position)| position);
        assert_eq!(
            shelved,
            [
                ("Von Idioten umzingelt!", "Gregs Tagebuch", 1),
                ("Jetzt reicht's!", "Gregs Tagebuch", 3)
            ]
        );
        let opened = entries.iter().find(|entry| entry.series_position == 1);
        assert_eq!(
            opened.map(|entry| &entry.alternate[..]),
            Some("Diary of a Wimpy Kid")
        );
    }

    #[test]
    fn same_writer_duplicates_dropped() {
        let mut authors = Authors::default();
        authors.add(7, "Thomas Harris");
        authors.add(8, "Thomas Harris");
        authors.add(9, "Sir Walter Scott");
        authors.add(SYNTHETIC + 1, "Walter Scott");
        let cover = LibraryCover {
            source: Source::Dnb.cover(),
            key: straw_isbn(),
            language: 1,
        };
        let mut books = vec![
            open_library_book(1, "Hannibal", 7),
            open_library_book(2, "Hannibal", 8),
            open_library_book(3, "Ivanhoe", 9),
            Book {
                library_cover: Some(cover),
                ..open_library_book(SYNTHETIC + 4, "Ivanhoe", SYNTHETIC + 1)
            },
        ];
        let mut titles = Titles::default();
        for book in &mut books {
            book.eligible = per_language(&[(1, true)]);
            book.scores = per_language(&[(1, 200.0)]);
            titles.add((book.work, 1), &book.title, ("", 0), 2000);
        }
        titles.sort();
        let entries = catalog::candidates(&books, &titles, &authors, 1, None);
        let mut kept: Vec<&str> = entries.iter().map(|entry| &entry.title[..]).collect();
        kept.sort_unstable();
        assert_eq!(kept, ["Hannibal", "Ivanhoe"]);
    }

    #[test]
    fn isbn_match_enriches_open_library_work() {
        let library = matched(&[STRAW], 42);
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let book = open_library_book(42, "The Last Straw", 7);
        let run = run(&library, vec![claimed(&library, book, &authors)], &[]);
        assert_eq!(run.books.len(), 1);
        assert_eq!((run.outcome.enriched[1], run.outcome.added[1]), (1, 0));
        let book = &run.books[0];
        assert!(book.eligible[1] && book.eligible[0]);
        assert_eq!(book.scores, per_language(&[(0, 200.0), (1, 250.0)]));
        assert_eq!(
            book.library_cover.map(|cover| cover.key),
            Some(straw_isbn())
        );
        assert_eq!(run.isbns.of(42, 1), &[straw_isbn()]);
    }

    #[test]
    fn original_title_claims_without_isbn() {
        let library = matched(&[STRAW], 99);
        let mut authors = Authors::default();
        authors.add(7, "Kinney, Jeff");
        let book = open_library_book(42, "The Last Straw", 7);
        assert_eq!(
            claimed(&library, book, &authors)
                .claim
                .map(|claim| claim.groups),
            Some(vec![0])
        );
    }

    #[test]
    fn synthetic_ids_stay_stable_and_alias_to_open_library() {
        let first = run(&library(&[STRAW]), Vec::new(), &[]);
        let work = first.books[0].work;
        assert_eq!(work, SYNTHETIC + 137838113);
        assert_eq!(first.books[0].authors, vec![SYNTHETIC + 13395351]);
        let older = Edition {
            id: "1068521406",
            isbn: "9783732508709",
            title: "Gregs Tagebuch 3 - Jetzt reicht's!",
            citation: "",
            original: "",
            medium: "1018",
            ..STRAW
        };
        let fresh = run(&library(&[STRAW, older]), Vec::new(), &[]);
        assert_eq!(fresh.books[0].work, SYNTHETIC + 106852140);
        let kept = run(&library(&[STRAW, older]), Vec::new(), &[work]);
        assert_eq!(kept.books[0].work, work);
        assert!(kept.outcome.aliases.iter().all(Vec::is_empty));

        let library = matched(&[STRAW, older], 42);
        let mut authors = Authors::default();
        authors.add(7, "Jeff Kinney");
        let book = claimed(
            &library,
            open_library_book(42, "Diary of a Wimpy Kid", 7),
            &authors,
        );
        let gained = run(&library, vec![book], &[work]);
        assert_eq!(gained.books.len(), 1);
        assert_eq!(gained.outcome.aliases[1], vec![(work, 42)]);
        let merged = aliases(&[], &gained.outcome.aliases[1], &gained.books);
        assert_eq!(merged, vec![(work, 42)]);
    }

    #[test]
    fn empty_library_changes_nothing() {
        let library = Library::default();
        let book = open_library_book(42, "Emma", 7);
        assert!(library.claim(42, &["Emma"], Some("Jane Austen")).is_empty());
        let run = run(&library, vec![book], &[]);
        assert_eq!(run.books[0].scores, per_language(&[(0, 200.0)]));
        assert!(run.books[0].library_cover.is_none());
    }
}
