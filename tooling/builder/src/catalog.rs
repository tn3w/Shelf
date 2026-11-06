use crate::dumps::{Authors, Book, FLAG_COVER, FLAG_ISBN, FLAG_PUBLISHER, FLAG_READABLE};
use crate::dumps::{Facts, Signal, TitleRecord, Titles};
use crate::libraries::{self, SYNTHETIC};
use crate::release::State;
use crate::search::{HANGUL, is_cyrillic};
pub use crate::search::{is_cjk, is_kana, tokenize};
use crate::{LANGUAGE_COUNT, LANGUAGES};
use std::collections::HashMap;
use std::sync::LazyLock;

pub const DESCRIPTION_MIN_SCORE: f32 = 400.0;
pub const MAX_SERIES_MEMBERS: usize = 40;
pub const SUBTITLE_SEPARATOR: char = '\u{1f}';
pub const OTHER_LANGUAGE: u32 = 1 << 31;

pub const MIN_SCORE: f32 = 150.0;
const MAX_TITLE_BYTES: usize = 160;
const FOREIGN_TITLE_WORDS: usize = 3;
const DESCRIPTION_TARGET: usize = 480;
const DESCRIPTION_LIMIT: usize = 1000;
const MAX_TAG_BYTES: usize = 400;
const MIN_DESCRIPTION_BYTES: usize = 20;
const MIN_DESCRIPTION_WORDS: usize = 4;
const TITLE_MENTION_POINTS: i64 = 200;
const CONTENTS_SEPARATORS: usize = 3;
const CONTENTS_PENALTY: i64 = 600;
const UNFINISHED_BYTES: i64 = 100;
const UNFINISHED_PENALTY: i64 = 700;
const REPEATED_PHRASE_WORDS: usize = 6;
const MAX_PHRASE_REPEATS: usize = 3;
const MIN_SENTENCE_KEPT_BYTES: usize = 80;
const JUDGED_HEAD_BYTES: usize = 40;
const MIN_PARAGRAPH_BYTES: usize = 60;
const SHORT_LEAD_BYTES: usize = 200;
const MAX_SHOUTED_SHARE: f32 = 0.7;
const COVER_REACH_POINTS: [(u32, i32); 4] = [(20_000, 25), (5_000, 20), (1_000, 15), (100, 8)];
const MODERN_EDITION: u16 = 1975;
const VOLUME_LABELS: [&str; 6] = ["Band", "Bd.", "Buch", "Teil", "Folge", "Book"];
const EDITION_TAGS: [&str; 2] = ["(german edition)", "(deutsche ausgabe)"];
const ABBREVIATIONS: [&str; 5] = ["etc", "ltd", "inc", "usw", "bzw"];
const COVER_EDITION_POINTS: [(u16, i32); 5] = [
    (2010, 15),
    (2000, 10),
    (1990, 5),
    (MODERN_EDITION, 0),
    (1, -30),
];
const COVER_READER_POINTS: [(u16, i32); 4] = [(100, 25), (30, 18), (10, 12), (3, 6)];
const COVER_CURATED_POINTS: i32 = 40;
const COVER_TITLE_POINTS: i32 = 20;
const COVER_SPECIAL_PENALTY: i32 = 25;
const COVER_SCAN_PENALTY: i32 = 20;
const COVER_LISTED_POINTS: i32 = 10;

const PLACEHOLDER_DESCRIPTIONS: &[&str] = &[
    "this is a duplicate",
    "this work:",
    "redirected to",
    "duplicate of",
    "book digitized by google",
    "legends of dune",
    "amazon.com review",
];

const LOW_CONTENT_PREFIXES: &[&str] = &[
    "carnet de notes",
    "carnet de mots de passe",
    "diabetes tagebuch",
    "diabetes-tagebuch",
    "schulheft",
    "kalender 20",
    "adult coloring",
    "goal planner",
    "password book",
    "password log",
    "blank lined",
];
const LOW_CONTENT_TITLES: &[&str] = &["notebook", "planner", "carnet", "cuaderno", "notizbuch"];
const JUNK_TITLES: &[&str] = &[
    "hardcover",
    "paperback",
    "special edition",
    "edition",
    "untitled",
];
const NOISE_WORDS: &[&str] = &[
    "imported",
    "paperback",
    "hardcover",
    "książka",
    "ksiazka",
    "microform",
    "polish text",
    "korean translation",
    "sic",
    "audiobook",
    "large print",
];
const VOLUME_WORDS: &[&str] = &["book", "bk", "bk.", "vol", "vol.", "volume", "no.", "part"];

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
const SHORT_COMMENT_BYTES: usize = 300;
const TERSE_COMMENT_BYTES: usize = 100;
const QUOTE_MARKS: [char; 6] = ['"', '\u{201c}', '\u{201d}', '\u{201e}', '«', '»'];

const READER_OPENINGS: &[&str] = &[
    " this book is about ",
    " this book was about ",
    " the book is about ",
    " this story is about ",
    " this is about ",
    " it is about ",
    " its about ",
    " it s about ",
];

const READER_VERDICTS: &[&str] = &[
    " must read ",
    " must have ",
    " recommend it ",
    " recommend this ",
    " i recommend ",
    " i highly recommend ",
    " i would recommend ",
    " favorite book ",
    " favourite book ",
    " best book ",
    " great read ",
    " good read ",
    " great book ",
    " good book ",
    " nice book ",
    " awesome book ",
    " amazing book ",
    " five stars ",
    " 5 stars ",
    " i love ",
    " i loved ",
    " i like ",
    " i liked ",
    " i enjoyed ",
    " i hated ",
    " i really ",
    " i want to ",
    " i need ",
    " i wish ",
    " i hope ",
    " i read ",
    " i just ",
    " i think ",
    " my copy ",
    " loved it ",
    " worth reading ",
    " in my opinion ",
    " page turner ",
    " well written ",
    " this book is amazing ",
    " this book is great ",
    " this book is good ",
    " this book is awesome ",
    " this book is the best ",
    " this is a good ",
    " this is a great ",
    " this is an amazing ",
    " it is a good ",
    " it is a great ",
    " very good ",
    " so good ",
    " very boring ",
    " very funny ",
    " so funny ",
    " is cool ",
    " is awesome ",
    " verry ",
];

const JUDGED_WORDS: usize = 25;
const MIN_STOP_WORD_SHARE: f32 = 0.05;
const MAX_STOP_WORD_BYTES: usize = 16;

pub const SOURCE_LANGUAGES: [&str; 18] = [
    "en", "de", "fr", "es", "et", "sv", "pl", "cs", "ko", "fi", "ja", "lv", "pt", "ru", "it", "lt",
    "nl", "da",
];

const STOP_WORDS: [&str; SOURCE_LANGUAGES.len()] = [
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
    "ja ning ei see kui oli mis aga oma nii siis seda kes või veel mida kõik tema nad \
     meie pole olen olid oleks nagu ainult juba väga",
    "och är inte till av ett från efter vid när hennes hade hon säger blev skulle \
     mot jag",
    "się jest przez są był była było oraz tylko już ich jego jej który która które gdy \
     dla bardzo może",
    "jsem pro jako není jsou byl byla bylo jeho její které který také když při jsme \
     však nebo",
    "",
    "että hän mutta kun niin jos tai myös kuin ovat olla mitä joka jotka sen hänen \
     ole",
    "",
    "arī viņš viņa viņi līdz pēc bija būs tika tiek savu",
    "não uma os com dos são foi ele ela seu sua seus suas ao pelo pela pelos pelas \
     também muito já está têm quando sem nas num numa isso esse essa mesmo ainda depois \
     onde",
    "что это как его она они был была было были для или если уже только когда \
     ещё даже меня тебя себя который которая которые очень потом теперь",
    "gli che di sono alla della nel nella delle dei degli per non più come questo \
     questa anche suo suoi sue hanno molto tra fra dopo essere stato stata",
    "yra kaip jis jie buvo bus apie prie nuo iki savo labai kuris kuri",
    "het een van dat zijn niet wordt naar voor ook maar deze wij zij hun werd waren \
     heeft hebben geen door met bij uit over zich ik",
    "og af ikke til på ved når hendes havde bliver mod ikkje frå eit jeg hvad hvor",
];

const NOT_RUSSIAN: &str = "іїєґўђјљњћџІЇЄҐЎЂЈЉЊЋЏ";

const KINDRED_LANGUAGES: [(&str, &str); 1] = [("et", "fi")];

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
    "bundle",
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
    "hard press",
    "indypublish",
    "book jungle",
    "filiquarian",
    "creative media partners",
    "simon & brown",
    "icon classics",
    "icon group",
    "librivox",
    "blurb",
    "ad classic",
    "xist publishing",
    "readhowyouwant",
    "engage books",
    "start publishing",
    "ebookslib",
    "floating press",
    "wilder publications",
    "prakash book depot",
    "editorium",
    "standard publications",
    "benediction classics",
    "bibliotech press",
    "mizzou publishing",
    "fredonia",
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

pub const TARGET_CODES: [&str; LANGUAGE_COUNT] = [
    "eng", "ger", "fre", "spa", "est", "swe", "pol", "cze", "kor", "fin", "jpn", "lav", "por",
    "rus", "ita",
];

const LANGUAGE_CODES: [&str; 27] = [
    "eng", "ger", "fre", "spa", "ita", "rus", "por", "dut", "jpn", "chi", "pol", "swe", "ara",
    "heb", "cze", "dan", "nor", "fin", "tur", "kor", "gre", "hun", "lat", "ind", "est", "lav",
    "lit",
];

const UDC_LANGUAGES: [(&str, &str); 20] = [
    ("821.111", "eng"),
    ("821.112.2", "ger"),
    ("821.113.6", "swe"),
    ("821.131.1", "ita"),
    ("821.133.1", "fre"),
    ("821.134.2", "spa"),
    ("821.134.3", "por"),
    ("821.161.1", "rus"),
    ("821.162.1", "pol"),
    ("821.162.3", "cze"),
    ("821.172", "lit"),
    ("821.174", "lav"),
    ("821.511.111", "fin"),
    ("821.511.113", "est"),
    ("821.521", "jpn"),
    ("821.531", "kor"),
    ("821.581", "chi"),
    ("821.112.5", "dut"),
    ("821.113.4", "dan"),
    ("821.113.5", "nor"),
];

const ISBN_GROUPS: [(&str, usize); 40] = [
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
    ("9789916", 4),
    ("9789949", 4),
    ("9789985", 4),
    ("97891", 5),
    ("97883", 6),
    ("97880", 7),
    ("97889", 8),
    ("97911", 8),
    ("978951", 9),
    ("978952", 9),
    ("9789935", 9),
    ("9784", 10),
    ("9789934", 11),
    ("9789984", 11),
    ("978972", 12),
    ("978989", 12),
    ("97885", 12),
    ("97865", 12),
    ("9785", 13),
    ("97888", 14),
    ("97912", 14),
];

const ISBN_BASE: u64 = 978_000_000_000;
const ISBN_KEYS: u32 = 2_000_000_000;

const FOREIGN_ISBN_GROUPS: [&str; 17] = [
    "9784", "9785", "9786", "9787", "97880", "97881", "97882", "97883", "97885", "97886", "97887",
    "97888", "97889", "9789", "97911", "97912", "97913",
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
    let (title, subtitle) = (
        &libraries::sanitized(title),
        &libraries::sanitized(subtitle),
    );
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

fn has_valid_check_digit(isbn13: &str) -> bool {
    let weights = [1, 3].into_iter().cycle();
    let digits = isbn13
        .bytes()
        .map(|byte| u32::from(byte.wrapping_sub(b'0')));
    let sum: u32 = digits
        .zip(weights)
        .map(|(digit, weight)| digit * weight)
        .sum();
    isbn13.bytes().all(|byte| byte.is_ascii_digit()) && sum.is_multiple_of(10)
}

pub fn isbn13(key: u32) -> String {
    let twelve = (ISBN_BASE + u64::from(key)).to_string();
    let weights = [1, 3].into_iter().cycle();
    let digits = twelve.bytes().map(|byte| u32::from(byte - b'0'));
    let sum: u32 = digits
        .zip(weights)
        .map(|(digit, weight)| digit * weight)
        .sum();
    format!("{twelve}{}", (10 - sum % 10) % 10)
}

pub fn isbn_key(isbn: &str) -> Option<u32> {
    let cleaned: String = isbn.chars().filter(char::is_ascii_alphanumeric).collect();
    let twelve = match cleaned.len() {
        10 => format!("978{}", &cleaned[..9]),
        13 if has_valid_check_digit(&cleaned) => cleaned[..12].to_string(),
        _ => return None,
    };
    let key = twelve.parse::<u64>().ok()?.checked_sub(ISBN_BASE)?;
    u32::try_from(key).ok().filter(|&key| key < ISBN_KEYS)
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

fn is_address_or_number(title: &str) -> bool {
    let digits = title.chars().filter(char::is_ascii_digit).count();
    let numeric = title
        .chars()
        .all(|c| c.is_ascii_digit() || "- xX".contains(c));
    title.starts_with("www.") || title.starts_with("http") || numeric && digits >= 9
}

pub fn is_bad_title(title: &str) -> bool {
    let lowered = title.trim().to_ascii_lowercase();
    let low_content = LOW_CONTENT_TITLES.contains(&lowered.as_str())
        || LOW_CONTENT_PREFIXES
            .iter()
            .any(|prefix| lowered.starts_with(prefix));
    if JUNK_TITLES.contains(&lowered.as_str()) || low_content || is_address_or_number(&lowered) {
        return true;
    }
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

pub const ENTITIES: [(&str, &str); 7] = [
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
            let words: Vec<&str> = line.split_whitespace().collect();
            words
                .join(" ")
                .trim_matches(['*', '_', '#', '>'])
                .trim()
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

fn without_lead_marker(paragraph: &str) -> &str {
    let bare = paragraph.trim_start_matches(['•', '·', '-', '*', ' ']);
    let digits = bare.trim_start_matches(|c: char| c.is_ascii_digit());
    let numbered = digits.len() < bare.len() && digits.starts_with(". ");
    if numbered {
        digits[2..].trim_start()
    } else {
        bare
    }
}

fn has_repeated_phrase(text: &str) -> bool {
    let words: Vec<&str> = text.split_whitespace().collect();
    let mut seen: HashMap<&[&str], usize> = HashMap::new();
    words.windows(REPEATED_PHRASE_WORDS).any(|phrase| {
        let count = seen.entry(phrase).or_default();
        *count += 1;
        *count >= MAX_PHRASE_REPEATS
    })
}

fn ends_cleanly(text: &str) -> String {
    let closers = ['.', '!', '?', '…', '"', ')', '»', '”', '“', '’', '。'];
    let unfinished = text.ends_with("...") || !text.ends_with(closers);
    let stop = [". ", "! ", "? "]
        .iter()
        .filter_map(|mark| text.rfind(mark))
        .max();
    match stop.filter(|&at| unfinished && at + 1 >= MIN_SENTENCE_KEPT_BYTES) {
        Some(at) => text[..=at].to_string(),
        None => text.to_string(),
    }
}

fn is_placeholder(text: &str) -> bool {
    let lowered = text.to_lowercase();
    text.len() < MIN_DESCRIPTION_BYTES
        || PLACEHOLDER_DESCRIPTIONS
            .iter()
            .any(|marker| lowered.starts_with(marker))
}

pub fn description_score(text: &str, title: &str) -> Option<i64> {
    if text.split_whitespace().count() < MIN_DESCRIPTION_WORDS {
        return None;
    }
    let length = text.chars().count().min(DESCRIPTION_LIMIT) as i64;
    let separators = text.matches(" -- ").count() + text.matches(" ; ").count();
    let title_key = libraries::match_key(title);
    let mut score = length / 3;
    if !title_key.is_empty() && libraries::match_key(text).contains(&title_key) {
        score += TITLE_MENTION_POINTS;
    }
    if separators >= CONTENTS_SEPARATORS {
        score -= CONTENTS_PENALTY;
    }
    if length >= UNFINISHED_BYTES && !text.ends_with(['.', '!', '?', '…', '"', '»', '。']) {
        score -= UNFINISHED_PENALTY;
    }
    Some(score)
}

pub fn clean_description(text: &str) -> String {
    let text = libraries::unmojibaked(text);
    let garbage = |c: char| c == '\u{fffd}' || ('\u{80}'..='\u{9f}').contains(&c);
    let text: String = text.chars().filter(|&c| !garbage(c)).collect();
    let cut = text.find("----------").unwrap_or(text.len());
    let mut text = text[..cut].trim().replace('\r', "");
    for (entity, character) in ENTITIES {
        text = text.replace(entity, character);
    }
    for emphasis in ["**", "__"] {
        text = text.replace(emphasis, "");
    }
    let stripped = without_citations(&without_links(&without_tags(&text)));
    let text = tidy(&without_urls(&stripped));
    let scripts = [
        is_mostly_latin,
        is_mostly_hangul,
        is_mostly_japanese,
        is_mostly_cyrillic,
    ];
    let readable = scripts.iter().any(|script| script(&text));
    if is_subject_list(&text) || !readable {
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
    let kept = without_wrapping_quotes(&kept);
    let kept = ends_cleanly(without_lead_marker(kept));
    if is_reader_comment(&kept) || is_placeholder(&kept) || has_repeated_phrase(&kept) {
        return String::new();
    }
    kept
}

fn without_quotations(text: &str) -> String {
    let mut quoted = false;
    let mut kept = String::with_capacity(text.len());
    for character in text.chars() {
        if QUOTE_MARKS.contains(&character) {
            quoted = !quoted;
            continue;
        }
        if !quoted {
            kept.push(character);
        }
    }
    kept
}

fn spoken_words(text: &str) -> String {
    let lowered = without_quotations(text).to_lowercase();
    let words = lowered
        .split(|character: char| !character.is_alphanumeric())
        .filter(|word| !word.is_empty());
    format!(" {} ", words.collect::<Vec<&str>>().join(" "))
}

fn is_reader_comment(text: &str) -> bool {
    let Some(first) = text.chars().next() else {
        return false;
    };
    let words = spoken_words(text);
    let has = |phrases: &[&str]| phrases.iter().any(|phrase| words.contains(phrase));
    let opens = |phrases: &[&str]| phrases.iter().any(|phrase| words.starts_with(phrase));
    let lowercase_i = without_quotations(text).contains(" i ");
    let noise = ["!!", "??", "...."].iter().any(|mark| text.contains(mark));
    let verdict_points = if text.len() < SHORT_COMMENT_BYTES {
        2
    } else {
        1
    };
    let points = [
        (first.is_lowercase(), 2),
        (has(READER_VERDICTS), verdict_points),
        (lowercase_i, 2),
        (opens(READER_OPENINGS), 1),
        (
            noise || has(&[" u ", " ur ", " lol ", " lmao ", " idk "]),
            1,
        ),
        (text.len() < TERSE_COMMENT_BYTES, 1),
    ];
    let total: u8 = points
        .iter()
        .filter(|(earned, _)| *earned)
        .map(|(_, points)| points)
        .sum();
    total >= 2
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

fn stop_word_hits(text: &str) -> ([usize; SOURCE_LANGUAGES.len()], usize) {
    let words = text
        .split(|character: char| !character.is_alphabetic())
        .filter(|word| !word.is_empty());
    let mut hits = [0usize; SOURCE_LANGUAGES.len()];
    let mut count = 0;
    for word in words {
        count += 1;
        if let Some(language) = stop_word_language(word) {
            hits[language] += 1;
        }
    }
    (hits, count)
}

fn is_kindred(left: usize, right: usize) -> bool {
    let pair = (SOURCE_LANGUAGES[left], SOURCE_LANGUAGES[right]);
    KINDRED_LANGUAGES.contains(&pair) || KINDRED_LANGUAGES.contains(&(pair.1, pair.0))
}

pub fn is_foreign_title(title: &str, language: usize) -> bool {
    let (hits, words) = stop_word_hits(title);
    let foreign = hits
        .iter()
        .enumerate()
        .any(|(other, &count)| other != language && count > 0 && !is_kindred(other, language));
    words >= FOREIGN_TITLE_WORDS && hits[language] == 0 && foreign
}

pub fn detect_language(text: &str) -> Option<usize> {
    if is_mostly_hangul(text) {
        return Some(crate::language_index("ko"));
    }
    if text.chars().any(is_kana) {
        return Some(crate::language_index("ja"));
    }
    if is_russian(text) {
        return Some(crate::language_index("ru"));
    }
    let (hits, words) = stop_word_hits(text);
    let best = (0..hits.len()).max_by_key(|&language| hits[language])?;
    let others = hits
        .iter()
        .enumerate()
        .filter(|&(language, _)| language != best);
    let runner_up = others.map(|(_, &count)| count).max().unwrap_or(0);
    let thin = words >= JUDGED_WORDS && (hits[best] as f32) < MIN_STOP_WORD_SHARE * words as f32;
    (hits[best] > runner_up && !thin).then_some(best)
}

fn is_mostly(text: &str, script: impl Fn(char) -> bool) -> bool {
    let letters = text.chars().filter(|character| character.is_alphabetic());
    let (matching, total) = letters.fold((0, 0), |(matching, total), character| {
        (matching + usize::from(script(character)), total + 1)
    });
    total == 0 || matching * 2 > total
}

pub fn is_mostly_latin(text: &str) -> bool {
    is_mostly(text, |character| (character as u32) < 0x250)
}

pub fn is_mostly_hangul(text: &str) -> bool {
    text.chars().any(char::is_alphabetic)
        && is_mostly(text, |character| HANGUL.contains(&character))
}

pub fn is_mostly_cyrillic(text: &str) -> bool {
    text.chars().any(char::is_alphabetic) && is_mostly(text, is_cyrillic)
}

pub fn is_russian(text: &str) -> bool {
    is_mostly_cyrillic(text)
        && !text
            .chars()
            .any(|character| NOT_RUSSIAN.contains(character))
}

pub fn is_mostly_japanese(text: &str) -> bool {
    text.chars().any(char::is_alphabetic) && is_mostly(text, is_cjk)
}

pub fn udc_language(udc: &str) -> Option<&'static str> {
    let longest = UDC_LANGUAGES
        .iter()
        .filter(|(prefix, _)| udc.starts_with(prefix))
        .max_by_key(|(prefix, _)| prefix.len());
    longest.map(|&(_, language)| language)
}

pub fn is_romanised_korean(text: &str) -> bool {
    text.contains(['ŭ', 'ŏ', 'Ŭ', 'Ŏ', 'ʻ'])
}

pub fn is_native_script(text: &str, language: usize) -> bool {
    match LANGUAGES[language] {
        "ko" => is_mostly_hangul(text) || is_mostly_latin(text),
        "ja" => is_mostly_japanese(text) || is_mostly_latin(text),
        "ru" => is_mostly_cyrillic(text) || is_mostly_latin(text),
        _ => is_mostly_latin(text),
    }
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

fn without_volume_prefix(title: &str, series: &str) -> Option<(String, u16)> {
    let labels = std::iter::once(series).chain(VOLUME_LABELS);
    labels.filter(|label| !label.is_empty()).find_map(|label| {
        let number = title
            .get(label.len()..)?
            .trim_start_matches([' ', '.', ',']);
        let after = number.trim_start_matches(|digit: char| digit.is_ascii_digit());
        let volume = number[..number.len() - after.len()].parse().ok()?;
        let separated = after.trim_start().starts_with(['.', ':', '-', '–']);
        let stripped = libraries::without_series(title, label);
        let worded = stripped.starts_with(char::is_alphanumeric);
        (separated && worded && stripped != title).then_some((stripped, volume))
    })
}

fn named_volume(title: &str, names: &[String]) -> Option<(String, String, u16)> {
    names.iter().find_map(|name| {
        let (title, volume) = without_volume_prefix(title, name)?;
        Some((title, name.clone(), volume))
    })
}

fn adopt_series(entries: &mut [Entry], writers: &[String]) {
    let mut known: HashMap<&str, Vec<String>> = HashMap::new();
    for (entry, writer) in entries.iter().zip(writers) {
        let names = known.entry(writer).or_default();
        if !entry.series.is_empty() && !names.contains(&entry.series) {
            names.push(entry.series.clone());
        }
    }
    for (entry, writer) in entries.iter_mut().zip(writers) {
        let names = known
            .get(writer.as_str())
            .filter(|_| entry.series.is_empty());
        let found = names.and_then(|names| named_volume(&entry.title, names));
        let Some((title, series, volume)) = found.filter(|_| !writer.is_empty()) else {
            continue;
        };
        if entry.alternate.is_empty() {
            entry.alternate = std::mem::take(&mut entry.title);
        }
        (entry.title, entry.series, entry.series_position) = (title, series, volume);
    }
}

fn is_volume_number(label: &str) -> bool {
    let Some((marker, number)) = label.rsplit_once(' ') else {
        return false;
    };
    let marker = marker.to_lowercase();
    let numeric = !number.is_empty() && number.bytes().all(|byte| byte.is_ascii_digit());
    numeric && VOLUME_WORDS.contains(&marker.rsplit(' ').next().unwrap_or_default())
}

fn is_noise(inner: &str) -> bool {
    let lowered = inner.trim().to_lowercase();
    let numbered = lowered
        .rsplit_once('#')
        .is_some_and(|(name, number)| !name.trim().is_empty() && number.parse::<u16>().is_ok());
    let counted = lowered.contains(' ') && is_volume_number(&lowered);
    lowered.ends_with("edition") || NOISE_WORDS.contains(&lowered.as_str()) || numbered || counted
}

fn without_noise(title: &str) -> String {
    let mut kept = String::with_capacity(title.len());
    let mut rest = title;
    while let Some(start) = rest.find(['(', '[']) {
        let closer = if rest[start..].starts_with('(') {
            ')'
        } else {
            ']'
        };
        let end = rest[start..].find(closer).map(|end| start + end);
        kept.push_str(&rest[..start]);
        let Some(end) = end else {
            rest = &rest[start + 1..];
            continue;
        };
        if !is_noise(&rest[start + 1..end]) {
            kept.push_str(&rest[start..=end]);
        }
        rest = &rest[end + 1..];
    }
    kept + rest
}

fn without_volume_tail(title: &str) -> &str {
    let numbered = title.rsplit_once(" #");
    let numbered = numbered.filter(|(_, number)| number.parse::<u16>().is_ok());
    if let Some((head, _)) = numbered {
        return head;
    }
    match title.rsplit_once(", ") {
        Some((head, tail)) if is_volume_number(&tail.to_lowercase()) => head,
        _ => title,
    }
}

fn without_responsibility(title: &str) -> &str {
    let outside = |(at, _): &(usize, &str)| {
        let before = &title[..*at];
        before.matches(['(', '[']).count() == before.matches([')', ']']).count()
    };
    let responsibility = |(at, _): &(usize, &str)| {
        let tail = &title[at + 3..];
        tail.contains(';') || tail.starts_with(char::is_lowercase)
    };
    let mut slashes = title.match_indices(" / ").filter(outside);
    let cut = slashes.find(responsibility);
    let head = cut.map_or(title, |(at, _)| &title[..at]);
    if head.is_empty() { title } else { head }
}

fn without_series_prefix<'a>(title: &'a str, series: &str) -> &'a str {
    let head = title.get(..series.len()).unwrap_or_default();
    if series.is_empty() || head.to_lowercase() != series.to_lowercase() {
        return title;
    }
    let rest = &title[head.len()..];
    let remainder = [" - ", " – ", ": ", ". "]
        .iter()
        .find_map(|separator| rest.strip_prefix(separator))
        .unwrap_or_default();
    let first = remainder
        .split(' ')
        .next()
        .unwrap_or_default()
        .to_lowercase();
    let labelled =
        VOLUME_WORDS.contains(&first.as_str()) || remainder.starts_with(char::is_numeric);
    if remainder.is_empty() || labelled {
        title
    } else {
        remainder
    }
}

fn without_series_tail<'a>(title: &'a str, series: &str) -> &'a str {
    let bracketed = title
        .strip_suffix(')')
        .and_then(|head| head.rsplit_once(" ("));
    match bracketed {
        Some((head, inner)) if !series.is_empty() && series_key(inner) == series_key(series) => {
            head
        }
        _ => title,
    }
}

fn tail_series(title: &str) -> Option<(String, u16)> {
    let bracketed = title
        .strip_suffix(')')
        .and_then(|head| head.rsplit_once(" ("));
    let inner = bracketed.map_or(title, |(_, inner)| inner);
    let (name, number) = match inner.rsplit_once(" #") {
        Some(numbered) => numbered,
        None if is_volume_number(&inner.to_lowercase()) => {
            let (labelled, number) = inner.rsplit_once(' ')?;
            (labelled.rsplit_once(' ')?.0, number)
        }
        None => return None,
    };
    let number: u16 = number
        .parse()
        .ok()
        .filter(|&number| (1..=MAX_SERIES_MEMBERS as u16).contains(&number))?;
    let name = name.trim_end_matches(',').trim();
    (name.len() >= 4).then(|| (name.to_string(), number))
}

fn listed_series(alternate: &str, title: &str) -> Option<(String, u16)> {
    let listed = alternate.rsplit(';').next()?.trim();
    let (name, rest) = listed.split_once(". ")?;
    let (name, number) = name.rsplit_once(' ')?;
    let number: u16 = number
        .parse()
        .ok()
        .filter(|&number| (1..=MAX_SERIES_MEMBERS as u16).contains(&number))?;
    let named = name.trim().len() >= 4 && title_key(rest, &[]) == title_key(title, &[]);
    named.then(|| (name.trim().to_string(), number))
}

fn tidy_title(title: &str) -> String {
    let mut title = libraries::sanitized(title);
    for tag in EDITION_TAGS {
        if let Some(start) = title.to_ascii_lowercase().find(tag) {
            title.replace_range(start..start + tag.len(), "");
        }
    }
    let title = without_noise(without_responsibility(&title));
    let title = match title.rsplit_once(" - ") {
        Some((head, tail)) if tail.to_lowercase().ends_with("edition") => head.to_string(),
        _ => title,
    };
    let title = without_volume_tail(title.trim());
    let title = title.trim_end_matches([' ', ';', '/', ':', '=', ',', '-', '–']);
    let title = title.trim();
    let title = match title.rsplit_once(". ") {
        Some((head, tail)) if libraries::is_genre(tail.trim_end_matches('.')) => head,
        _ => title,
    };
    let last = title.rsplit(' ').next().unwrap_or_default();
    let word = last.strip_suffix('.').unwrap_or_default();
    let abbreviated = ABBREVIATIONS.contains(&word.to_lowercase().as_str());
    let worded = word.chars().count() > 2 && word.chars().any(char::is_lowercase);
    let dotted = worded && !abbreviated && word.chars().all(char::is_alphabetic);
    title
        .strip_suffix('.')
        .filter(|_| dotted)
        .unwrap_or(title)
        .to_string()
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

type RankedCover = (i32, u16, u32);

fn curated_points(book: &Book, cover: u32, edition_year: u16) -> i32 {
    let year = if edition_year > 0 {
        edition_year
    } else {
        book.year
    };
    if year < MODERN_EDITION {
        return 0;
    }
    match book.covers.iter().position(|&curated| curated == cover) {
        Some(0) => COVER_CURATED_POINTS,
        Some(_) => COVER_LISTED_POINTS,
        None => 0,
    }
}

#[derive(Default)]
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
        let mut ballots: Vec<(String, &str, u16)> = records
            .iter()
            .map(|record| self.spelling(record, series))
            .filter(|title| is_native_script(title, self.language))
            .filter(|title| LANGUAGES[self.language] != "ko" || !is_romanised_korean(title))
            .map(|title| (title_key(title, &[]), title, 0))
            .filter(|ballot| !ballot.0.is_empty())
            .collect();
        let native = |ballot: &(String, &str, u16)| match LANGUAGES[self.language] {
            "ko" => is_mostly_hangul(ballot.1),
            "ja" => is_mostly_japanese(ballot.1),
            "ru" => is_mostly_cyrillic(ballot.1),
            _ => false,
        };
        if ballots.iter().any(native) {
            ballots.retain(native);
        }
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
        let foreign = |title: &str| is_foreign_title(title, self.language);
        if foreign(best.spelling) && !work_title.is_empty() && !foreign(work_title) {
            return keep;
        }
        if own == 0 || own_key.is_empty() || !is_native_script(work_title, self.language) {
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

    fn cover_score(&self, record: &TitleRecord, title: &str, book: &Book) -> i32 {
        let matching = title_key(self.title(record), &[]) == title;
        banded(record.readers, &COVER_READER_POINTS)
            + award(matching, COVER_TITLE_POINTS)
            + banded(record.year, &COVER_EDITION_POINTS)
            + banded(self.titles.publisher_editions(record), &COVER_REACH_POINTS)
            + curated_points(book, record.cover, record.year)
            - award(record.special, COVER_SPECIAL_PENALTY)
            - award(record.scanned, COVER_SCAN_PENALTY)
    }

    fn best_cover(
        &self,
        records: &[&TitleRecord],
        title: &str,
        language: usize,
        book: &Book,
    ) -> Option<RankedCover> {
        let usable = records
            .iter()
            .filter(|record| record.cover > 0)
            .filter(|record| record.language as usize == language)
            .filter(|record| is_native_script(self.title(record), language))
            .filter(|record| !is_foreign_title(self.title(record), language));
        usable
            .map(|record| {
                (
                    self.cover_score(record, title, book),
                    record.year,
                    record.cover,
                )
            })
            .max()
    }

    fn unclaimed_cover(&self, records: &[&TitleRecord], book: &Book) -> Option<RankedCover> {
        let usable = book.covers.iter().filter(|&&cover| {
            let held = records.iter().any(|record| record.cover == cover);
            cover > 0 && !held && !self.titles.is_claimed_cover(cover)
        });
        usable
            .map(|&cover| (curated_points(book, cover, 0), 0, cover))
            .max()
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
        let (franchise, franchise_position) = self.choose_series(&records);
        let voters = if local.is_empty() { &records } else { &local };
        let (mut title, mut alternate) = self.choose_title(&book.title, "", voters);
        if is_series_label(&title, &franchise) {
            (title, alternate) = self.choose_title(&book.title, &franchise, voters);
        }
        let stripped = without_volume_prefix(&title, &series);
        let title = stripped.map_or(title, |(title, _)| title);
        let title = without_series_prefix(&title, &series).to_string();
        let raw = libraries::sanitized(&title);
        let tail = tail_series(&raw);
        let tidied = tidy_title(&title);
        let bare = |(name, _): &(String, u16)| title_key(name, &[]) == title_key(&tidied, &[]);
        let title = if tail.as_ref().is_some_and(bare) && !raw.ends_with(')') {
            raw
        } else {
            tidied
        };
        alternate = tidy_title(&alternate);
        let mut series = series;
        let mut series_position = series_position;
        if let Some(found) = tail.filter(|_| series.is_empty()) {
            (series, series_position) = found;
        }
        if series.is_empty() {
            let listed = listed_series(&alternate, &title);
            if let Some((name, position)) = listed {
                (series, series_position) = (name, position);
                alternate.clear();
            }
        }
        let words = |text: &str| format!(" {text} ");
        let named = words(&title_key(&title, &[])).contains(&words(&series_key(&franchise)));
        if series.is_empty() && !franchise.is_empty() && named {
            (series, series_position) = (franchise, franchise_position);
        }
        let mut title = without_series_tail(&title, &series).to_string();
        let bare = |candidate: &str| series_key(candidate) == series_key(&series);
        if !series.is_empty() && bare(&title) {
            let serial = |record: &&&TitleRecord| bare(self.titles.series(record));
            let titles = voters
                .iter()
                .filter(serial)
                .map(|record| tidy_title(self.title(record)));
            let named = titles.filter(|candidate| !candidate.is_empty() && !bare(candidate));
            if let Some(named) = libraries::most_common(named) {
                alternate = std::mem::replace(&mut title, named);
            }
        }
        if !series.is_empty() && series_key(&alternate) == series_key(&series) {
            alternate.clear();
        }
        let key = title_key(&title, &[]);
        if title_key(&alternate, &[]) == key || alternate.to_lowercase() == title.to_lowercase() {
            alternate.clear();
        }
        let unnamed = !key.contains(&series_key(&series));
        let touched = book
            .library_cover
            .is_some_and(|cover| cover.language == self.language);
        if touched && alternate.is_empty() && series_position > 0 && unnamed {
            alternate = format!("{series} {series_position}. {title}");
        }
        let unclaimed = self.unclaimed_cover(&records, book);
        let english = self.best_cover(&records, &key, 0, book).max(unclaimed);
        let native = match self.language {
            0 => english,
            language => self.best_cover(&records, &key, language, book),
        };
        let weak = native.is_some_and(|(_, year, _)| (1..MODERN_EDITION).contains(&year));
        let preferred = if weak { native.max(english) } else { native };
        let fallback = book.covers.iter().copied().find(|&cover| cover > 0);
        let cover = preferred
            .or(english)
            .map(|(_, _, cover)| cover)
            .or(fallback);
        Entry {
            book: index,
            cover: cover.unwrap_or(0),
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
    let writer = |entry: &Entry| {
        let author = books[entry.book]
            .authors
            .first()
            .and_then(|&id| authors.get(id));
        author.map_or(String::new(), |author| libraries::writer_key(author.name))
    };
    let writers: Vec<String> = entries.iter().map(writer).collect();
    adopt_series(&mut entries, &writers);
    entries.sort_by(|a, b| {
        let stickiness = b.sticky.is_some().cmp(&a.sticky.is_some());
        stickiness
            .then(b.score.total_cmp(&a.score))
            .then(a.book.cmp(&b.book))
    });
    let mut seen: HashMap<_, Vec<u16>> = HashMap::new();
    let touched = |entry: &Entry| {
        let cover = books[entry.book].library_cover;
        cover.is_some_and(|cover| cover.language == language)
    };
    let guarded = entries.iter().any(touched);
    let mut matched: HashMap<(String, String, u16), bool> = HashMap::new();
    entries.retain(|entry| {
        let book = &books[entry.book];
        let names: Vec<&str> = book
            .authors
            .iter()
            .filter_map(|&id| authors.get(id))
            .map(|author| author.name)
            .collect();
        let writer = names.first().map(|name| libraries::writer_key(name));
        let author = writer
            .clone()
            .or_else(|| book.authors.first().map(u32::to_string));
        let position = entry.series_position;
        let positions = seen
            .entry((title_key(&entry.title, &names), author))
            .or_default();
        let volume = position > 0 && !positions.contains(&position) && !positions.contains(&0);
        let fresh = positions.is_empty() || volume;
        positions.push(position);
        let writer = writer.filter(|_| guarded);
        let title = || libraries::match_key(&entry.title);
        let library_key = writer.map(|writer| (title(), writer, position));
        let library_key =
            library_key.filter(|(title, writer, _)| !title.is_empty() && !writer.is_empty());
        let repeated = library_key.is_some_and(|library_key| {
            let earlier = matched.get(&library_key).copied();
            matched.insert(library_key, earlier.unwrap_or(false) || touched(entry));
            earlier.is_some_and(|earlier| earlier || touched(entry))
        });
        fresh && !repeated || entry.sticky.is_some()
    });
    let is_library = |entry: &Entry| books[entry.book].work >= SYNTHETIC;
    let read = entries.iter().filter(|entry| {
        let book = &books[entry.book];
        !is_library(entry) && book.signal.readers() > 0
    });
    let reader_floor = read.map(|entry| entry.score).fold(f32::INFINITY, f32::min);
    let rank = |entry: &Entry| match is_library(entry) {
        true => entry.score.min(reader_floor),
        false => entry.score,
    };
    entries.sort_by(|a, b| {
        let by_rank = rank(b).total_cmp(&rank(a));
        by_rank
            .then(is_library(a).cmp(&is_library(b)))
            .then(a.book.cmp(&b.book))
    });
    entries
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_scripts_tokenized_like_the_app() {
        assert_eq!(tokenize("Żółw příliš"), ["zolw", "prilis"]);
        assert_eq!(tokenize("채식주의자 - 한강"), ["채식주의자", "한강"]);
        assert_eq!(tokenize(&"a".repeat(30)), ["a".repeat(24), "a".repeat(6)]);
        assert_eq!(
            detect_language("채식주의자"),
            Some(crate::language_index("ko"))
        );
        assert_eq!(
            isbn_language("9788327154491"),
            Some(crate::language_index("pl"))
        );
        assert_eq!(
            isbn_language("9789510396358"),
            Some(crate::language_index("fi"))
        );
    }

    #[test]
    fn japanese_and_baltic_tokenized_like_the_app() {
        assert_eq!(
            tokenize("ノルウェイの森"),
            ["ノル", "ルウ", "ウェ", "ェイ", "イの", "の森"]
        );
        assert_eq!(
            tokenize("ハリー・ポッターと賢者の石"),
            [
                "ハリ", "リー", "ポッ", "ッタ", "ター", "ーと", "と賢", "賢者", "者の", "の石"
            ]
        );
        assert_eq!(tokenize("1Q84 村上春樹"), ["1q84", "村上", "上春", "春樹"]);
        assert_eq!(tokenize("Ｈａｒｒｙ 猫"), ["harry", "猫"]);
        assert_eq!(tokenize("Dvēseļu putenis"), ["dveselu", "putenis"]);
        assert_eq!(tokenize("Žalgirio mūšis"), ["zalgirio", "musis"]);
        assert_eq!(tokenize("채식주의자"), ["채식주의자"]);
        assert_eq!(
            detect_language("海辺のカフカ"),
            Some(crate::language_index("ja"))
        );
        assert_eq!(
            isbn_language("9784101001616"),
            Some(crate::language_index("ja"))
        );
        assert_eq!(
            isbn_language("9789934151005"),
            Some(crate::language_index("lv"))
        );
        assert_eq!(isbn_language("9786094661234"), None);
    }

    #[test]
    fn baltic_stop_words_belong_to_one_language() {
        let latvian = crate::language_index("lv");
        let lithuanian = SOURCE_LANGUAGES.iter().position(|&code| code == "lt");
        let lithuanian = lithuanian.expect("lt");
        for (language, list) in STOP_WORDS.iter().enumerate() {
            for word in list.split_whitespace() {
                let owner = stop_word_language(word);
                let baltic = [language, owner.unwrap_or(language)];
                if baltic.contains(&latvian) || baltic.contains(&lithuanian) {
                    assert_eq!(owner, Some(language), "{word}");
                }
            }
        }
        assert!(!is_foreign_title(
            "Un nouvel ami",
            crate::language_index("fr")
        ));
    }

    #[test]
    fn source_languages_start_with_catalogue_languages() {
        assert_eq!(
            &SOURCE_LANGUAGES[..crate::LANGUAGE_COUNT],
            &crate::LANGUAGES[..]
        );
        let portuguese = detect_language("não uma os com dos são foi ele ela").expect("pt");
        assert_eq!(SOURCE_LANGUAGES[portuguese], "pt");
    }

    #[test]
    fn isbn_keys_round_trip_and_udc_names_languages() {
        let key = isbn_key("978-3-8339-1131-6").expect("key");
        assert_eq!(isbn13(key), "9783833911316");
        assert_eq!(udc_language("821.111-31"), Some("eng"));
        assert_eq!(udc_language("821.112.2-3"), Some("ger"));
        assert_eq!(udc_language("821.172-93"), Some("lit"));
        assert_eq!(udc_language("94(474.5)"), None);
    }

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
    fn volume_prefixes_stripped() {
        for (title, series, expected, volume) in [
            (
                "Gregs Tagebuch 01 . Von Idioten umzingelt!",
                "Gregs Tagebuch",
                "Von Idioten umzingelt!",
                1,
            ),
            (
                "Gregs Tagebuch 02. Gibt's Probleme?",
                "Gregs Tagebuch",
                "Gibt's Probleme?",
                2,
            ),
            ("Beast Quest 52 - Silver", "Beast Quest", "Silver", 52),
            (
                "Star Trek Voyager. 16. Gestrandet",
                "Star Trek Voyager",
                "Gestrandet",
                16,
            ),
            (
                "Band 2: Das Geheimnis im Finsterbau",
                "",
                "Das Geheimnis im Finsterbau",
                2,
            ),
            ("Buch 1: Verpestet", "The Extinction Cycle", "Verpestet", 1),
            (
                "Warriors 3: Forest of Secrets",
                "warriors",
                "Forest of Secrets",
                3,
            ),
        ] {
            let stripped = without_volume_prefix(title, series);
            assert_eq!(stripped, Some((expected.to_string(), volume)), "{title}");
        }
        for (title, series) in [
            ("Gregs Tagebuch Spezial 1. Mach's selbst", "Gregs Tagebuch"),
            ("Eragon: Das Vermächtnis der Drachenreiter", "Eragon"),
            ("Gregs Tagebuch 3", "Gregs Tagebuch"),
            ("Das 8. Geständnis", ""),
            ("Gregs Tagebuch 1 Von Idioten umzingelt!", "Gregs Tagebuch"),
            ("Bandit 2: Rache", ""),
            ("Die Gabel, die Hexe und der Wurm. Band 1 : Eragon", ""),
            ("Band 14: (berührende Pferde-Reihe ab 10 Jahren)", ""),
            ("Band 06 (deutsche Ausgabe)", ""),
        ] {
            assert_eq!(without_volume_prefix(title, series), None, "{title}");
        }
    }

    #[test]
    fn titles_tidied() {
        for (title, expected) in [
            ("Die Darwin-Kinder (German Edition)", "Die Darwin-Kinder"),
            ("Jenseits des Horizonts. Roman.", "Jenseits des Horizonts"),
            (
                "Die Geschichte der Kinder Hurins. Erzählung.",
                "Die Geschichte der Kinder Hurins",
            ),
            (
                "Mord auf Rezept. Ein Fall für Mrs. Murphy.",
                "Mord auf Rezept. Ein Fall für Mrs. Murphy",
            ),
            ("Die  Frau im Dunkel", "Die Frau im Dunkel"),
            ("Flug nach Arras.", "Flug nach Arras"),
            ("Hallo Mr.", "Hallo Mr."),
            ("Ludwig XIV.", "Ludwig XIV."),
            ("Lost Souls Ltd.", "Lost Souls Ltd."),
            ("Mädchen, Frau etc.", "Mädchen, Frau etc."),
            (
                "Lila lässt die Funken fliegen. (Ab 8 J.).",
                "Lila lässt die Funken fliegen. (Ab 8 J.).",
            ),
            ("Wait...", "Wait..."),
            ("Roman eines Schicksallosen", "Roman eines Schicksallosen"),
        ] {
            assert_eq!(tidy_title(title), expected);
        }
    }

    #[test]
    fn isbns_keyed() {
        let key = Some(43_902_348);
        assert_eq!(isbn_key("9780439023481"), key);
        assert_eq!(isbn_key("978-0-439-02348-1"), key);
        assert_eq!(isbn_key("0439023483"), key);
        assert_eq!(isbn_key("043902348X"), key);
        assert_eq!(isbn_key("9791032305690"), Some(1_103_230_569));
        for invalid in ["9780439023482", "1234567890123", "12345", "97804390234AB"] {
            assert_eq!(isbn_key(invalid), None, "{invalid}");
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

    #[test]
    fn reader_comments_dropped() {
        for comment in [
            "It is verry funny to read and it is cool",
            "this is the only truly psychedelic book I've ever read.",
            "This book is the best! I loved it and I wanted to read more!",
            "This is a very good book I read in the past. I hope to read it again.",
            "Great book about all the facets of sound. The book is a must read.",
            "xix, 442 p., p. of plates : 26 cm",
        ] {
            assert!(clean_description(comment).is_empty(), "{comment}");
        }
        for blurb in [
            "Four teenaged friends go out for a practice drive one night when the driver \
             hits and possibly kills a pedestrian.",
            "I'd always dreamed of my wedding day. But not like this. Not looking \
             into the eyes of the man who betrayed me.",
            "\"I think you have something to say to me, Papa,\" Salena said tautly, and \
             the train pulled out of the Monte Carlo station into the night.",
            "Franklin's favorite blanket is missing. He searches everywhere because he \
             can't sleep without it.",
        ] {
            assert!(!clean_description(blurb).is_empty(), "{blurb}");
        }
    }

    #[test]
    fn source_languages_detected() {
        for (language, text) in [
            (
                "en",
                "The story of a girl who was lost and found by her father.",
            ),
            (
                "es",
                "La historia de una niña que fue encontrada por su padre.",
            ),
            (
                "pt",
                "A história de uma menina que não foi encontrada pelo seu pai.",
            ),
            (
                "it",
                "La storia di una ragazza che non è stata trovata dal suo papà.",
            ),
            (
                "nl",
                "Het verhaal van een meisje dat niet werd gevonden door haar vader.",
            ),
            (
                "sv",
                "Två väninnor är ute och vandrar när en av dem inte kommer hem till byn.",
            ),
            (
                "da",
                "Hans Thomas og faren rejser til Grækenland, \
                 og han har ikke fortalt hvor.",
            ),
        ] {
            let detected = detect_language(text).map(|index| SOURCE_LANGUAGES[index]);
            assert_eq!(detected, Some(language), "{text}");
        }
    }

    #[test]
    fn titles_stripped_of_catalogue_noise() {
        for (title, expected) in [
            ("\u{98}Le \u{9c}petit sapin", "Le petit sapin"),
            (
                "Rozkosz / Gabriele d'Annunzio ; z włoskiego przełożył",
                "Rozkosz",
            ),
            ("Byłem--", "Byłem"),
            ("Framgångens pris ;", "Framgångens pris"),
            ("Just kids [Imported] [Paperback]", "Just kids"),
            ("Kim (Standard Edition)", "Kim"),
            ("Kim - Illustrated Edition", "Kim"),
            ("Waylander (Drenai Tales, Book 4)", "Waylander"),
            ("Sapphique (Incarceron #2)", "Sapphique"),
            ("1Q84, Book 1", "1Q84"),
            ("Mia paket #3", "Mia paket"),
            ("[Dina Rubina", "Dina Rubina"),
            ("Humboldts gÃ¥va", "Humboldts gåva"),
            ("Mia\u{200b}  paket &#63;", "Mia paket ?"),
            ("Das Buch (Band 2)", "Das Buch (Band 2)"),
        ] {
            assert_eq!(tidy_title(title), expected, "{title}");
        }
    }

    #[test]
    fn junk_titles_rejected() {
        for title in [
            "Hardcover",
            "www.laska.cz",
            "9788375780123",
            "Box Books Bundle",
        ] {
            assert!(is_bad_title(title), "{title}");
        }
        assert!(!is_bad_title("Der Hobbit"));
    }

    #[test]
    fn description_placeholders_dropped() {
        let sentence = "Eine lange Geschichte über zwei Schwestern, die ihr Dorf verlassen. ";
        let truncated = format!("{sentence}{sentence}Und dann kam der Winter mit...");
        let placeholders = [
            "This is a duplicate. Please update your lists. See",
            "Redirected to another work in the catalogue.",
            "Short.",
            "Amazon.com Review A fine tale of two sisters leaving home.",
        ];
        for placeholder in placeholders {
            assert!(clean_description(placeholder).is_empty(), "{placeholder}");
        }
        assert!(clean_description(&truncated).ends_with("verlassen."));
        let cut = format!("{sentence}\n\n---------- German-language description: Hallo");
        assert!(!clean_description(&cut).contains("German-language"));
        let looping = "Hän oli vain yksi niistä. ".repeat(6);
        assert!(clean_description(&looping).is_empty());
        let marked = format!("• {sentence}");
        assert!(clean_description(&marked).starts_with("Eine"));
    }

    #[test]
    fn series_listed_in_alternate_title_adopted() {
        let found = listed_series("Pendragon 1. Taliesin", "Taliesin");
        assert_eq!(found, Some(("Pendragon".to_string(), 1)));
        let publisher = listed_series("Heyne Reihe 8449; Sprawl 1. Neuromancer", "Neuromancer");
        assert_eq!(publisher, Some(("Sprawl".to_string(), 1)));
        assert_eq!(listed_series("Pendragon 1. Taliesin", "Merlin"), None);
    }

    #[test]
    fn series_prefix_needs_separator_and_real_title() {
        assert_eq!(
            without_series_prefix("Warrior Cats - In die Wildnis", "Warrior Cats"),
            "In die Wildnis"
        );
        for title in [
            "Scott Pilgrim's Precious Little Life",
            "Vinland Saga: Book Twelve",
            "Raider/Border Law",
            "Discworld: 2",
        ] {
            let series = title.split(['\'', '/', ':']).next().unwrap_or_default();
            assert_eq!(without_series_prefix(title, series), title);
        }
    }

    #[test]
    fn eponymous_opener_loses_bracketed_volume() {
        let title = "The Mysterious Benedict Society (The Mysterious Benedict Society #1)";
        let tail = tail_series(title);
        assert_eq!(
            tail,
            Some(("The Mysterious Benedict Society".to_string(), 1))
        );
        assert_eq!(tidy_title(title), "The Mysterious Benedict Society");
    }

    #[test]
    fn series_name_tail_dropped_once_known() {
        let title = "The Lightning Thief (Percy Jackson)";
        assert_eq!(
            without_series_tail(title, "Percy Jackson"),
            "The Lightning Thief"
        );
        assert_eq!(without_series_tail(title, "Redwall"), title);
    }

    #[test]
    fn series_kept_from_stripped_title_tails() {
        let drenai = tail_series("Waylander (Drenai Tales, Book 4)");
        assert_eq!(drenai, Some(("Drenai Tales".to_string(), 4)));
        let incarceron = tail_series("Sapphique (Incarceron #2)");
        assert_eq!(incarceron, Some(("Incarceron".to_string(), 2)));
        assert_eq!(tail_series("1Q84, Book 1"), Some(("1Q84".to_string(), 1)));
        let manga = tail_series("Soul Eater, Vol. 3");
        assert_eq!(manga, Some(("Soul Eater".to_string(), 3)));
        let unit = tail_series("Wait for Dark (Bishop/Special Crimes Unit Book 5)");
        assert_eq!(unit, Some(("Bishop/Special Crimes Unit".to_string(), 5)));
        assert_eq!(tail_series("Das Buch (Band 2)"), None);
    }

    #[test]
    fn low_content_titles_spare_real_books() {
        for title in ["Diary", "Tagebuch", "Journal", "Cuaderno gris"] {
            assert!(!is_bad_title(title), "{title}");
        }
        for title in [
            "Notebook",
            "Cuaderno",
            "Carnet de Notes",
            "Diabetes Tagebuch",
        ] {
            assert!(is_bad_title(title), "{title}");
        }
    }

    #[test]
    fn descriptions_without_sentence_break_kept_whole() {
        let text = "Ein kurzer Klappentext ohne Satzende und mit Auslassung...";
        assert_eq!(clean_description(text), text);
        let quoted = "Sie sagte: „Komm mit, wir gehen jetzt gemeinsam nach Hause.“";
        assert_eq!(clean_description(quoted), quoted);
    }

    #[test]
    fn finnish_titles_with_shared_estonian_words_stay_local() {
        let finnish = crate::language_index("fi");
        assert!(!is_foreign_title("Harry Potter ja viisasten kivi", finnish));
        assert!(is_foreign_title(
            "Harry Potter and the Philosopher's Stone",
            finnish
        ));
    }

    #[test]
    fn romanised_korean_detected() {
        assert!(is_romanised_korean("Ŭiryo munje ŭi yullijŏk sŏngchʻal"));
        assert!(!is_romanised_korean("의료 문제의 윤리적 성찰"));
    }

    #[test]
    fn omnibus_titles_kept_whole() {
        for title in ["Valloittava vauva / Neiti Järkevä", "Tiloja / Avaruuksia"] {
            assert_eq!(tidy_title(title), title);
        }
        assert_eq!(
            tidy_title("De engelska språkljuden / av Karl S. Hofsø"),
            "De engelska språkljuden"
        );
    }

    #[test]
    fn slashes_inside_brackets_kept() {
        let title = "A Little Fate (Winter Rose / Witching Hour)";
        assert_eq!(tidy_title(title), title);
    }
}
