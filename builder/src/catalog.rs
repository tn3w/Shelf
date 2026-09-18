use crate::dumps::{Authors, Book, FLAG_COVER, FLAG_ISBN, FLAG_PUBLISHER, FLAG_READABLE};
use crate::dumps::{Facts, Signal, TitleRecord, Titles};
use crate::release::State;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

pub const DESCRIPTION_MIN_SCORE: f32 = 400.0;
pub const MAX_SERIES_MEMBERS: usize = 40;
pub const SUBTITLE_SEPARATOR: char = '\u{1f}';
pub const OTHER_LANGUAGE: u32 = 1 << 31;

const MIN_SCORE: f32 = 150.0;
const MAX_TITLE_BYTES: usize = 160;
const FOREIGN_TITLE_WORDS: usize = 3;
const DESCRIPTION_TARGET: usize = 480;
const DESCRIPTION_LIMIT: usize = 1000;
const MAX_TAG_BYTES: usize = 80;
const JUDGED_HEAD_BYTES: usize = 40;
const MIN_PARAGRAPH_BYTES: usize = 60;
const SHORT_LEAD_BYTES: usize = 200;
const MAX_SHOUTED_SHARE: f32 = 0.7;
const COVER_TITLE_POINTS: i32 = 10;
const COVER_REACH_POINTS: [(u32, i32); 4] = [(20_000, 25), (5_000, 20), (1_000, 15), (100, 8)];
const COVER_EDITION_POINTS: [(u16, i32); 2] = [(1990, 10), (1960, 5)];

const TAIL_MARKERS: &[&str] = &[
    "-- back cover",
    "- back cover",
    "--back cover",
    "-- from the publisher",
    "-- publisher description",
    "-- provided by publisher",
    "-- amazon.com",
    "-- goodreads",
];

const JUNK_MARKERS: &[&str] = &[
    "about the author",
    "praise for",
    "translation of",
    "includes index",
    "includes bibliographical",
    "originally published",
    "table of contents",
    "contents:",
    "publisher's note",
    "back cover",
    "source:",
    "wikipedia entry",
];
const JUDGED_WORDS: usize = 25;
const MIN_STOP_WORD_SHARE: f32 = 0.05;
const MAX_TOKEN_BYTES: usize = 24;
const MAX_STOP_WORD_BYTES: usize = 16;

const STOP_WORDS: [&str; 5] = [
    "the and of to in is that it was for with as his her he she on but not you this \
     are at by from have had they all one their who been will would there what when \
     which him them",
    "der die das und ist nicht sich auf dem den ein eine einer eines im auch werden \
     wird als aus mit zum zur von bei nach über wie sie er ich wir sein seine hat \
     haben wurde oder zu",
    "le les une est dans pour qui sur avec pas plus ce ses cette ils nous au aux du \
     elle mais ou sont être été par lui leur je il avait",
    "el los las que por con para del su sus como pero este esta es al lo más fue \
     sobre entre también cuando muy hay ya",
    "het een van dat zijn niet wordt naar voor ook maar deze wij zij hun gli che di \
     da sono alla della nel os uma não seu sua ao pelo pela",
];

const BAD_TITLE_PREFIXES: &[&str] = &[
    "annual report",
    "proceedings of",
    "hearings before",
    "bulletin of",
    "report of the",
    "reports of the",
    "journal of the",
    "transactions of",
    "catalogue of",
    "catalog of",
    "index to",
    "bibliography of",
    "abstracts of",
    "statistical",
];

const COLLECTION_MARKERS: &[&str] = &[
    "box set",
    "boxed set",
    "box-set",
    "slipcase",
    "omnibus",
    "bind-up",
    "complete series",
    "complete collection",
    "complete novels",
    "complete saga",
    "complete works",
    "collected works",
    "collected novels",
    "books collection",
    "book collection",
    "book set",
    "books set",
    "volume set",
    "volumes set",
    "trilogy",
    "tetralogy",
    "quartet set",
    "schuber",
    "sammelband",
    "gesamtausgabe",
    "gesamtwerk",
    "gesamtausgaben",
    "trilogie",
    "coffret",
    "intégrale",
    "integrale",
    "estuche",
    "colección completa",
    "obras completas",
    "œuvres complètes",
    "oeuvres completes",
];

const PRINT_ON_DEMAND: &[&str] = &[
    "book on demand",
    "books on demand",
    "print on demand",
    "createspace",
    "independently published",
    "valdebooks",
    "bibliolife",
    "bibliobazaar",
    "kessinger",
    "nabu press",
    "general books",
    "dodo press",
    "hansebooks",
    "hardpress",
    "forgotten books",
    "franklin classics",
    "wentworth press",
    "trieste publishing",
    "sagwan press",
    "andesite press",
    "palala press",
    "arkose press",
    "scholar's choice",
    "books llc",
    "alpha edition",
    "lector house",
    "outlook verlag",
    "lulu",
    "echo library",
    "tredition",
    "salzwasser",
    "hofenberg",
    "good press",
    "e-artnow",
    "musaicum",
    "digireads",
    "1st world library",
    "read books",
    "hachette livre",
    "adegi graphics",
    "aegitas",
    "sharp ink",
];

const TARGET_CODES: [&str; 4] = ["eng", "ger", "fre", "spa"];

const LANGUAGE_CODES: [&str; 24] = [
    "eng", "ger", "fre", "spa", "ita", "rus", "por", "dut", "jpn", "chi", "pol", "swe", "ara",
    "heb", "cze", "dan", "nor", "fin", "tur", "kor", "gre", "hun", "lat", "ind",
];

const ISBN_GROUPS: [(&str, usize); 19] = [
    ("9780", 0),
    ("9781", 0),
    ("9798", 0),
    ("9783", 1),
    ("9782", 2),
    ("97910", 2),
    ("97884", 3),
    ("978607", 3),
    ("978612", 3),
    ("978628", 3),
    ("978631", 3),
    ("978950", 3),
    ("978956", 3),
    ("978958", 3),
    ("978968", 3),
    ("978970", 3),
    ("978987", 3),
    ("9789972", 3),
    ("9789974", 3),
];

const FOREIGN_ISBN_GROUPS: [&str; 17] = [
    "9784", "9785", "9786", "9787", "97880", "97881", "97882", "97883", "97885", "97886", "97887",
    "97888", "97889", "9789", "97911", "97912", "97913",
];

const FOLDED: [(&str, char); 9] = [
    ("áàâäãåÁÀÂÄÃÅ", 'a'),
    ("éèêëÉÈÊË", 'e'),
    ("íìîïÍÌÎÏ", 'i'),
    ("óòôöõøÓÒÔÖÕØ", 'o'),
    ("úùûüÚÙÛÜ", 'u'),
    ("ñÑ", 'n'),
    ("çÇ", 'c'),
    ("ýÿÝ", 'y'),
    ("ß", 's'),
];

static STOP_WORD_LANGUAGE: LazyLock<HashMap<&'static str, usize>> = LazyLock::new(|| {
    let lists = STOP_WORDS.iter().enumerate();
    lists
        .flat_map(|(language, list)| list.split_whitespace().map(move |word| (word, language)))
        .collect()
});

pub struct Traits {
    pub has_author: bool,
    pub good_title: bool,
    pub has_subjects: bool,
    pub bad_subject: bool,
    pub has_description: bool,
    pub has_cover: bool,
}

pub fn is_eligible(traits: &Traits, facts: &Facts, signal: &Signal, language: usize) -> bool {
    let readers = signal.readers();
    let named = traits.has_author && traits.good_title;
    let published = facts.language_editions[language] > 0
        && facts.has(FLAG_READABLE)
        && (facts.has(FLAG_ISBN) || readers > 0);
    let described = traits.has_subjects || readers > 0;
    let wanted = readers > 0 || facts.has(FLAG_COVER) || traits.has_cover || facts.editions >= 2;
    let junk = traits.bad_subject && readers < 3;
    let popular = score(traits, facts, signal, language) >= MIN_SCORE;
    named && published && described && wanted && !junk && popular
}

pub fn score(traits: &Traits, facts: &Facts, signal: &Signal, language: usize) -> f32 {
    let attention = 3.0 * signal.finished as f32 + 2.0 * signal.reading as f32 + signal.want as f32;
    let language_editions = facts.language_editions[language] as f32;
    let mut score = 100.0 * attention.ln_1p()
        + 60.0 * (signal.ratings as f32).ln_1p()
        + 20.0 * (facts.editions as f32).ln_1p()
        + 30.0 * language_editions.ln_1p()
        + 45.0 * (facts.language_count().max(1) as f32).ln();
    if signal.ratings >= 5 {
        score += 18.0 * (signal.mean_rating() - 3.6);
    }
    let bonuses = [
        (facts.has(FLAG_COVER) || traits.has_cover, 30.0),
        (traits.has_description, 22.0),
        (traits.has_subjects, 15.0),
        (facts.has(FLAG_ISBN), 10.0),
        (facts.has(FLAG_PUBLISHER), 8.0),
        (facts.first_year >= 1950, 6.0),
    ];
    score
        + bonuses
            .iter()
            .filter(|(earned, _)| *earned)
            .map(|(_, bonus)| bonus)
            .sum::<f32>()
}

fn language_code(key: &str) -> &str {
    key.rsplit('/').next().unwrap_or(key)
}

pub fn language_bit(key: &str) -> u32 {
    LANGUAGE_CODES
        .iter()
        .position(|&known| known == language_code(key))
        .map_or(OTHER_LANGUAGE, |bit| 1 << bit)
}

pub fn target_language(key: &str) -> Option<usize> {
    TARGET_CODES
        .iter()
        .position(|&known| known == language_code(key))
}

pub fn edition_title(title: &str, subtitle: &str) -> String {
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

pub fn is_reprinter(publisher: &str) -> bool {
    let lowered = publisher.to_ascii_lowercase();
    PRINT_ON_DEMAND
        .iter()
        .any(|reprinter| lowered.contains(reprinter))
}

fn normalized_isbn(isbn: &str) -> Option<String> {
    let digits: String = isbn.chars().filter(char::is_ascii_digit).collect();
    let normalized = if digits.len() == 10 {
        format!("978{digits}")
    } else {
        digits
    };
    (normalized.len() == 13).then_some(normalized)
}

pub fn isbn_language(isbn: &str) -> Option<usize> {
    let normalized = normalized_isbn(isbn)?;
    ISBN_GROUPS
        .iter()
        .find(|(prefix, _)| normalized.starts_with(prefix))
        .map(|&(_, language)| language)
}

pub fn is_foreign_isbn(isbn: &str) -> bool {
    normalized_isbn(isbn).is_some_and(|normalized| {
        FOREIGN_ISBN_GROUPS
            .iter()
            .any(|prefix| normalized.starts_with(prefix))
    })
}

pub fn is_bad_title(title: &str) -> bool {
    let lowered = title.to_ascii_lowercase();
    let listed = BAD_TITLE_PREFIXES
        .iter()
        .any(|prefix| lowered.starts_with(prefix));
    listed || is_collection_title(&lowered)
}

fn is_collection_title(lowered: &str) -> bool {
    COLLECTION_MARKERS
        .iter()
        .any(|marker| lowered.contains(marker))
        || has_volume_range(lowered)
}

fn is_volume_range(token: &str) -> bool {
    let Some((first, last)) = token.split_once(['-', '–', '—']) else {
        return false;
    };
    let bounds = first.parse::<u16>().ok().zip(last.parse::<u16>().ok());
    bounds.is_some_and(|(first, last)| {
        first >= 1 && last > first && last <= MAX_SERIES_MEMBERS as u16
    })
}

fn has_volume_range(title: &str) -> bool {
    let compact: String = title.chars().filter(|c| !c.is_whitespace()).collect();
    compact
        .split(|c: char| !c.is_ascii_digit() && !matches!(c, '-' | '–' | '—'))
        .any(is_volume_range)
}

const ENTITIES: [(&str, &str); 7] = [
    ("&amp;", "&"),
    ("&lt;", "<"),
    ("&gt;", ">"),
    ("&quot;", "\""),
    ("&apos;", "'"),
    ("&#39;", "'"),
    ("&nbsp;", " "),
];

fn is_link_definition(line: &str) -> bool {
    let line = line.trim_start();
    line.starts_with('[') && line.contains("]:")
}

fn is_subject_list(text: &str) -> bool {
    let dashes = text.matches(" -- ").count();
    dashes >= 2 || (dashes == 1 && text.matches(';').count() >= 2)
}

fn is_tag_start(character: char) -> bool {
    character.is_ascii_alphabetic() || character == '/'
}

fn without_tags(text: &str) -> String {
    let mut cleaned = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find('<') {
        let tag = &rest[start..];
        let end = tag.find('>').filter(|&end| end <= MAX_TAG_BYTES);
        let opens_tag = tag[1..].starts_with(is_tag_start);
        let Some(end) = end.filter(|_| opens_tag) else {
            cleaned.push_str(&rest[..start + 1]);
            rest = &rest[start + 1..];
            continue;
        };
        cleaned.push_str(&rest[..start]);
        rest = &tag[end + 1..];
    }
    cleaned + rest
}

fn without_citations(text: &str) -> String {
    let mut cleaned = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find('[') {
        let end = rest[start..].find(']').map(|end| start + end);
        let marker = end.filter(|&end| {
            let inside = &rest[start + 1..end];
            !inside.is_empty() && inside.bytes().all(|byte| byte.is_ascii_digit())
        });
        let Some(end) = marker else {
            cleaned.push_str(&rest[..start + 1]);
            rest = &rest[start + 1..];
            continue;
        };
        cleaned.push_str(&rest[..start]);
        rest = &rest[end + 1..];
    }
    cleaned + rest
}

fn without_links(text: &str) -> String {
    let mut cleaned = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find('[') {
        let label_end = rest[start..].find(']').map(|end| start + end);
        let Some(label_end) = label_end else { break };
        let target = rest[label_end + 1..].starts_with(['(', '[']);
        let closing = if rest[label_end + 1..].starts_with('(') {
            ')'
        } else {
            ']'
        };
        let end = target
            .then(|| rest[label_end..].find(closing))
            .flatten()
            .map(|end| label_end + end);
        let Some(end) = end else {
            cleaned.push_str(&rest[..label_end + 1]);
            rest = &rest[label_end + 1..];
            continue;
        };
        let image = rest[..start].ends_with('!');
        cleaned.push_str(&rest[..start - usize::from(image)]);
        if !image {
            cleaned.push_str(&rest[start + 1..label_end]);
        }
        rest = &rest[end + 1..];
    }
    cleaned + rest
}

fn without_urls(text: &str) -> String {
    let words = text.split(' ').filter(|word| {
        let bare = word.trim_start_matches(['(', '<']);
        !bare.starts_with("http://") && !bare.starts_with("https://") && !bare.starts_with("www.")
    });
    words.collect::<Vec<&str>>().join(" ")
}

fn tidy(text: &str) -> String {
    let lines = text
        .lines()
        .filter(|line| !is_link_definition(line))
        .map(|line| {
            line.trim_matches([' ', '\t', '*', '_', '#', '>'])
                .to_string()
        });
    let mut cleaned = String::new();
    for line in lines {
        let blank = line.is_empty();
        if blank && (cleaned.is_empty() || cleaned.ends_with("\n\n")) {
            continue;
        }
        cleaned.push_str(&line);
        cleaned.push('\n');
        if blank {
            cleaned.push('\n');
        }
    }
    cleaned.replace("\n\n\n", "\n\n").trim().to_string()
}

fn is_junk_paragraph(paragraph: &str) -> bool {
    let lowered = paragraph.to_lowercase();
    let head = &lowered[..lowered.floor_char_boundary(JUDGED_HEAD_BYTES)];
    if JUNK_MARKERS.iter().any(|marker| head.contains(marker)) {
        return true;
    }
    if paragraph.len() < MIN_PARAGRAPH_BYTES && !paragraph.ends_with(['.', '!', '?', '…']) {
        return true;
    }
    let letters = paragraph
        .chars()
        .filter(|character| character.is_alphabetic());
    let (shouted, total) = letters.fold((0, 0), |(shouted, total), character| {
        (shouted + usize::from(character.is_uppercase()), total + 1)
    });
    total > 0 && shouted as f32 > MAX_SHOUTED_SHARE * total as f32
}

fn readable_paragraphs(text: &str) -> Vec<&str> {
    let split: Vec<&str> = text
        .split("\n\n")
        .map(str::trim)
        .filter(|paragraph| !paragraph.is_empty())
        .collect();
    let readable: Vec<&str> = split
        .iter()
        .copied()
        .filter(|paragraph| !is_junk_paragraph(paragraph))
        .collect();
    let mut paragraphs = if readable.is_empty() { split } else { readable };
    let Some(&lead) = paragraphs.first() else {
        return paragraphs;
    };
    let overshadowed = paragraphs[1..]
        .iter()
        .any(|paragraph| paragraph.len() >= 2 * lead.len());
    if lead.len() < SHORT_LEAD_BYTES && overshadowed {
        paragraphs.remove(0);
    }
    paragraphs
}

fn without_wrapping_quotes(text: &str) -> &str {
    let opens = ['"', '\u{201c}', '\u{201e}'];
    let closes = ['"', '\u{201d}', '\u{201c}'];
    let Some(first) = text.chars().next().filter(|c| opens.contains(c)) else {
        return text;
    };
    let quotes = text
        .chars()
        .filter(|c| opens.contains(c) || closes.contains(c))
        .count();
    let wrapped = quotes == 2 && text.ends_with(closes);
    if quotes.is_multiple_of(2) && !wrapped {
        return text;
    }
    let body = text[first.len_utf8()..].trim_start();
    match wrapped {
        true => body.trim_end_matches(closes).trim_end(),
        false => body,
    }
}

fn truncate(text: String) -> String {
    if text.len() <= DESCRIPTION_LIMIT {
        return text;
    }
    let end = text.floor_char_boundary(DESCRIPTION_LIMIT);
    let window = &text[..end];
    let stop = window.rfind(". ").map_or(end, |position| position + 1);
    format!("{}…", window[..stop].trim())
}

fn without_tail(text: String) -> String {
    let lowered = text.to_lowercase();
    let cut = TAIL_MARKERS
        .iter()
        .filter_map(|marker| lowered.find(marker))
        .min();
    match cut {
        Some(cut) => text[..cut]
            .trim_end()
            .trim_end_matches(['-', ' '])
            .to_string(),
        None => text,
    }
}

pub fn clean_description(text: &str) -> String {
    let cut = text.find("\n\n----").unwrap_or(text.len());
    let mut text = text[..cut].trim().replace('\r', "");
    for (entity, character) in ENTITIES {
        text = text.replace(entity, character);
    }
    for emphasis in ["**", "__"] {
        text = text.replace(emphasis, "");
    }
    let stripped = without_citations(&without_links(&without_tags(&text)));
    let text = tidy(&without_urls(&stripped));
    if is_subject_list(&text) || !is_mostly_latin(&text) {
        return String::new();
    }
    let paragraphs = readable_paragraphs(&text);

    let mut kept = String::new();
    for paragraph in paragraphs {
        let extra = paragraph.len() + "\n\n".len();
        if !kept.is_empty() && kept.len() + extra > DESCRIPTION_TARGET {
            break;
        }
        if !kept.is_empty() {
            kept.push_str("\n\n");
        }
        kept.push_str(paragraph);
    }
    let kept = without_tail(truncate(kept));
    without_wrapping_quotes(&kept).to_string()
}

fn stop_word_language(word: &str) -> Option<usize> {
    if word.len() > MAX_STOP_WORD_BYTES {
        return None;
    }
    if !word.is_ascii() {
        return STOP_WORD_LANGUAGE
            .get(word.to_lowercase().as_str())
            .copied();
    }
    let mut lowered = [0u8; MAX_STOP_WORD_BYTES];
    let bytes = &mut lowered[..word.len()];
    bytes.copy_from_slice(word.as_bytes());
    bytes.make_ascii_lowercase();
    let lowered = str::from_utf8(bytes).expect("ascii stays utf-8");
    STOP_WORD_LANGUAGE.get(lowered).copied()
}

fn stop_word_hits(text: &str) -> ([usize; 5], usize) {
    let words = text
        .split(|character: char| !character.is_alphabetic())
        .filter(|word| !word.is_empty());
    let mut hits = [0usize; 5];
    let mut count = 0;
    for word in words {
        count += 1;
        if let Some(language) = stop_word_language(word) {
            hits[language] += 1;
        }
    }
    (hits, count)
}

fn is_foreign_title(title: &str, language: usize) -> bool {
    let (hits, words) = stop_word_hits(title);
    let foreign = hits
        .iter()
        .enumerate()
        .any(|(other, &count)| other != language && count > 0);
    words >= FOREIGN_TITLE_WORDS && hits[language] == 0 && foreign
}

pub fn detect_language(text: &str) -> Option<usize> {
    let (hits, words) = stop_word_hits(text);
    let best = (0..hits.len()).max_by_key(|&language| hits[language])?;
    let others = hits
        .iter()
        .enumerate()
        .filter(|&(language, _)| language != best);
    let runner_up = others.map(|(_, &count)| count).max().unwrap_or(0);
    let thin = words >= JUDGED_WORDS && (hits[best] as f32) < MIN_STOP_WORD_SHARE * words as f32;
    (best < 4 && hits[best] > runner_up && !thin).then_some(best)
}

pub fn is_mostly_latin(text: &str) -> bool {
    let letters = text.chars().filter(|character| character.is_alphabetic());
    let (latin, total) = letters.fold((0, 0), |(latin, total), character| {
        (latin + usize::from((character as u32) < 0x250), total + 1)
    });
    total == 0 || latin * 2 > total
}

fn fold(character: char) -> Option<char> {
    let lowered = character.to_ascii_lowercase();
    if lowered.is_ascii_alphanumeric() {
        return Some(lowered);
    }
    let found = FOLDED.iter().find(|(set, _)| set.contains(character));
    found.map(|&(_, folded)| folded)
}

pub fn tokenize(text: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    for character in text
        .chars()
        .filter(|character| !matches!(character, '\'' | '’' | '\u{300}'..='\u{36f}'))
    {
        match fold(character) {
            Some(folded) => current.push(folded),
            None if !current.is_empty() => tokens.push(std::mem::take(&mut current)),
            None => {}
        }
        if current.len() >= MAX_TOKEN_BYTES {
            tokens.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

fn without_article(tokens: &[String]) -> String {
    let skip = matches!(tokens.first().map(String::as_str), Some("the" | "a" | "an"));
    tokens[usize::from(skip)..].join(" ")
}

fn possessive_length(tokens: &[String], author_name: &str) -> Option<usize> {
    let name = tokenize(author_name);
    let possessive = format!("{}s", name.last()?);
    let position = tokens
        .iter()
        .take(4)
        .position(|token| *token == possessive)?;
    tokens[..position]
        .iter()
        .all(|token| name.contains(token))
        .then_some(position + 1)
}

pub fn title_key(title: &str, author_names: &[&str]) -> String {
    let main = title.split([':', ';', '(', '/']).next().unwrap_or(title);
    let mut tokens = tokenize(main);
    let possessive = author_names
        .iter()
        .find_map(|name| possessive_length(&tokens, name));
    if let Some(length) = possessive.filter(|&length| length < tokens.len()) {
        tokens.drain(..length);
    }
    without_article(&tokens)
}

pub fn series_key(name: &str) -> String {
    without_article(&tokenize(name))
}

struct Vote<'a> {
    key: String,
    count: usize,
    spelling: &'a str,
    position: u16,
}

fn tally<'a>(mut ballots: Vec<(String, &'a str, u16)>) -> Option<Vote<'a>> {
    ballots.sort_unstable();
    let groups = ballots.chunk_by(|a, b| a.0 == b.0);
    let best = groups.max_by(|a, b| a.len().cmp(&b.len()).then(b[0].0.cmp(&a[0].0)))?;
    let spellings = best.chunk_by(|a, b| a.1 == b.1);
    let spelling = spellings
        .max_by_key(|same| same.len())
        .map_or("", |same| same[0].1);
    let mut positions: Vec<u16> = best
        .iter()
        .map(|ballot| ballot.2)
        .filter(|&position| position > 0)
        .collect();
    positions.sort_unstable();
    let position = positions
        .chunk_by(|a, b| a == b)
        .max_by_key(|same| same.len());
    Some(Vote {
        key: best[0].0.clone(),
        count: best.len(),
        spelling,
        position: position.map_or(0, |same| same[0]),
    })
}

fn is_series_label(title: &str, series: &str) -> bool {
    let label = tokenize(title);
    let named: Vec<&String> = label
        .iter()
        .take_while(|token| !token.bytes().all(|byte| byte.is_ascii_digit()))
        .collect();
    let series = tokenize(series);
    !named.is_empty()
        && series.len() > named.len()
        && named
            .iter()
            .zip(&series)
            .all(|(label, name)| *label == name)
}

fn award(earned: bool, points: i32) -> i32 {
    if earned { points } else { 0 }
}

fn banded<T: PartialOrd>(value: T, points: &[(T, i32)]) -> i32 {
    points
        .iter()
        .find(|(start, _)| value >= *start)
        .map_or(0, |(_, points)| *points)
}

pub struct Entry {
    pub book: usize,
    pub title: String,
    pub cover: u32,
    pub alternate: String,
    pub series: String,
    pub series_position: u16,
    pub score: f32,
    pub sticky: Option<u8>,
    local_title: bool,
}

struct Resolver<'a> {
    titles: &'a Titles,
    language: usize,
}

impl<'a> Resolver<'a> {
    fn title(&self, record: &TitleRecord) -> &'a str {
        self.titles.title(record)
    }

    fn is_local(&self, record: &TitleRecord) -> bool {
        record.language as usize == self.language
            && !is_foreign_title(self.title(record), self.language)
    }

    fn spelling(&self, record: &TitleRecord, series: &str) -> &'a str {
        let title = self.title(record);
        let subtitle = self.titles.subtitle(record);
        let named = subtitle.chars().next().is_some_and(char::is_uppercase);
        if !named || !is_series_label(title, series) {
            return title;
        }
        subtitle
    }

    fn choose_title(
        &self,
        work_title: &str,
        series: &str,
        records: &[&TitleRecord],
    ) -> (String, String) {
        let ballots: Vec<(String, &str, u16)> = records
            .iter()
            .map(|record| self.spelling(record, series))
            .filter(|title| is_mostly_latin(title))
            .map(|title| (title_key(title, &[]), title, 0))
            .filter(|ballot| !ballot.0.is_empty())
            .collect();
        let own_key = title_key(work_title, &[]);
        let own = ballots.iter().filter(|ballot| ballot.0 == own_key).count();
        let keep = (work_title.to_string(), String::new());
        let Some(best) = tally(ballots) else {
            return keep;
        };
        let retitle = (best.spelling.to_string(), work_title.to_string());
        if best.key == own_key {
            return keep;
        }
        if own == 0 || own_key.is_empty() || !is_mostly_latin(work_title) {
            return retitle;
        }
        let common = best.count >= 2 && best.count > own * 2;
        if own_key.contains(&best.key) {
            return if common { retitle } else { keep };
        }
        if best.key.contains(&own_key) {
            return keep;
        }
        match (common, best.count >= 2) {
            (true, _) => retitle,
            (false, true) => (work_title.to_string(), best.spelling.to_string()),
            _ => keep,
        }
    }

    fn choose_series(&self, records: &[&TitleRecord]) -> (String, u16) {
        let ballots = records
            .iter()
            .map(|record| (self.titles.series(record), record.position))
            .filter(|(name, _)| !name.is_empty())
            .map(|(name, position)| (series_key(name), name, position))
            .collect();
        tally(ballots).map_or((String::new(), 0), |vote| {
            (vote.spelling.to_string(), vote.position)
        })
    }

    fn cover_score(&self, record: &TitleRecord, wanted: &str) -> i32 {
        let matching = title_key(self.title(record), &[]) == wanted;
        i32::from(record.cover_quality)
            + banded(self.titles.publisher_editions(record), &COVER_REACH_POINTS)
            + banded(record.year, &COVER_EDITION_POINTS)
            + award(matching, COVER_TITLE_POINTS)
    }

    fn best_cover(&self, records: &[&TitleRecord], key: &str, language: usize) -> u32 {
        let usable = records
            .iter()
            .filter(|record| record.cover > 0 && !record.print_on_demand)
            .filter(|record| record.language as usize == language)
            .filter(|record| is_mostly_latin(self.title(record)))
            .filter(|record| !is_foreign_title(self.title(record), language));
        usable
            .max_by_key(|record| (self.cover_score(record, key), record.year, record.cover))
            .map_or(0, |record| record.cover)
    }

    fn resolve(&self, index: usize, book: &Book) -> Entry {
        let records: Vec<&TitleRecord> = self
            .titles
            .of(book.work)
            .iter()
            .filter(|record| !is_bad_title(self.title(record)))
            .collect();
        let local: Vec<&TitleRecord> = records
            .iter()
            .copied()
            .filter(|record| self.is_local(record))
            .collect();
        let (series, series_position) = self.choose_series(&local);
        let (franchise, _) = self.choose_series(&records);
        let voters = if local.is_empty() { &records } else { &local };
        let (mut title, mut alternate) = self.choose_title(&book.title, "", voters);
        if is_series_label(&title, &franchise) {
            (title, alternate) = self.choose_title(&book.title, &franchise, voters);
        }
        if !series.is_empty() && series_key(&alternate) == series_key(&series) {
            alternate.clear();
        }
        let key = title_key(&title, &[]);
        if title_key(&alternate, &[]) == key || alternate.to_lowercase() == title.to_lowercase() {
            alternate.clear();
        }
        let cover = [
            self.best_cover(&records, &key, self.language),
            self.best_cover(&records, &key, 0),
            book.cover,
        ];
        Entry {
            book: index,
            cover: cover.into_iter().find(|&cover| cover > 0).unwrap_or(0),
            title,
            alternate,
            series,
            series_position,
            score: book.scores[self.language],
            sticky: None,
            local_title: !local.is_empty(),
        }
    }
}

pub fn candidates(
    books: &[Book],
    titles: &Titles,
    authors: &Authors,
    language: usize,
    previous: Option<&State>,
) -> Vec<Entry> {
    let resolver = Resolver { titles, language };
    let mut entries: Vec<Entry> = books
        .iter()
        .enumerate()
        .filter_map(|(index, book)| {
            let sticky = previous.and_then(|state| state.pack_of(book.work));
            if !book.eligible[language] && sticky.is_none() {
                return None;
            }
            let entry = resolver.resolve(index, book);
            if is_bad_title(&entry.title) {
                return None;
            }
            (entry.local_title || sticky.is_some()).then_some(Entry { sticky, ..entry })
        })
        .collect();
    entries.sort_by(|a, b| {
        let stickiness = b.sticky.is_some().cmp(&a.sticky.is_some());
        stickiness
            .then(b.score.total_cmp(&a.score))
            .then(a.book.cmp(&b.book))
    });
    let mut seen = HashSet::new();
    entries.retain(|entry| {
        let book = &books[entry.book];
        let names: Vec<&str> = book
            .authors
            .iter()
            .filter_map(|&id| authors.get(id))
            .map(|author| author.name)
            .collect();
        let key = (
            title_key(&entry.title, &names),
            book.authors.first().copied(),
        );
        seen.insert(key) || entry.sticky.is_some()
    });
    entries.sort_by(|a, b| b.score.total_cmp(&a.score).then(a.book.cmp(&b.book)));
    entries
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collections_rejected() {
        for title in [
            "Harry Potter 1-7",
            "Harry Potter Boxed Set",
            "Die Tribute von Panem. Gesamtausgabe",
            "The Lord of the Rings Trilogy",
            "Twilight Saga Complete Collection",
            "Sherlock Holmes 1-4",
        ] {
            assert!(is_bad_title(title), "{title}");
        }
        for title in [
            "Catch-22",
            "Nineteen Eighty-Four",
            "Europe 1914-1918",
            "Brave New World",
            "Harry Potter and the Goblet of Fire",
            "Volume One",
        ] {
            assert!(!is_bad_title(title), "{title}");
        }
    }

    #[test]
    fn descriptions_cleaned() {
        let lone = "\"Who is Matigari? He is young or old, dead or living. \
                    These are the questions asked by the people of a country.";
        assert!(!clean_description(lone).starts_with('"'));
        let cited = "The Commitments (1987) is a novel by Roddy Doyle.[2] It is \
                     about a band of unemployed young people in Dublin, Ireland.";
        assert!(!clean_description(cited).contains("[2]"));
        let emphasis = "**Leaves of Grass** is a poetry collection by the American \
                        poet Walt Whitman, first published in 1855 at his own expense.";
        assert!(!clean_description(emphasis).contains('*'));
        let subjects = "Drug traffic -- Iran - History; Drug control -- Iran - \
                        History; Drug abuse -- Iran -- Prevention.";
        assert!(clean_description(subjects).is_empty());
        let tail = "\"A young man returns to the village of his birth and finds it \
                    changed beyond recognition by the war.\" -- back cover.";
        let cleaned = clean_description(tail);
        assert_eq!(
            cleaned,
            "A young man returns to the village of his birth and finds it changed \
             beyond recognition by the war."
        );
    }
}
