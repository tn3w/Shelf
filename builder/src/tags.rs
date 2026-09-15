use Category::{Accolade, Audience, Form, Genre, Topic};
use serde::Deserialize;
use serde_json::Value;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap};
use std::sync::LazyLock;

#[derive(Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Category {
    Genre,
    Topic,
    Audience,
    Form,
    Accolade,
}

impl Category {
    pub fn name(self) -> &'static str {
        match self {
            Genre => "genre",
            Topic => "topic",
            Audience => "audience",
            Form => "form",
            Accolade => "accolade",
        }
    }
}

#[derive(Deserialize)]
pub struct Rule {
    pub slug: String,
    pub category: Category,
    pub labels: BTreeMap<String, String>,
    include: Vec<String>,
    exclude: Vec<String>,
    #[serde(default)]
    motifs: Vec<String>,
}

#[derive(Deserialize)]
struct Taxonomy {
    rules: Vec<Rule>,
    bisac: BTreeMap<String, Vec<String>>,
}

static TAXONOMY: LazyLock<Taxonomy> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../tags.json")).expect("valid tags.json")
});

const MAX_TAGS: usize = 8;
const WEAK_TAG_FILL: usize = 3;
const BISAC_WEIGHT: u16 = 2;
const MOTIF_WEIGHT: u16 = 2;
const CLASS_WEIGHT: u16 = 2;
const BAND_WEIGHT: u16 = 3;
const TEEN_SUBJECT_WEIGHT: u16 = 2;
const ADULT_MIN_CLASSIFIED: u16 = 5;
const ADULT_JUVENILE_SHARE: f32 = 0.1;
const YOUNG_JUVENILE_SHARE: f32 = 0.2;
const TEEN_MIN_EDITIONS: u16 = 40;
const CLASS_SHARE: f32 = 0.25;
const RELATIVE_FLOOR: f32 = 0.2;
const RICH_SUBJECTS: usize = 25;
const RICH_TOPIC_SUPPORT: u16 = 3;
const MEMO_LIMIT: usize = 200_000;

const IGNORED_PREFIXES: &[&str] = &[
    "nyt:",
    "award:",
    "series:",
    "collectionid:",
    "reading level",
    "accelerated reader",
    "open library",
    "open_syllabus",
    "long now manual",
];
const IGNORED_PHRASES: &[&str] = &[
    "adaptations",
    "study guides",
    "study and teaching",
    "examinations",
];

const SPECIFIC: &[&str] = &[
    "contemporary-romance",
    "historical-romance",
    "regency-romance",
    "epic-fantasy",
    "urban-fantasy",
    "space-opera",
    "dystopian",
    "cozy-mystery",
    "literary-fiction",
    "true-crime",
];

const TEEN_BANDS: &[&str] = &[
    "children: young adult",
    "(young adult)",
    "children's 12-up",
    "children's books - young adult",
    "children's books/young adult",
    "young adult fiction",
    "young adult literature",
    "children's audio - young adult",
    "spanish: young adult",
];
const KID_BANDS: &[&str] = &[
    "children's books/ages",
    "children's books/baby",
    "children: preschool",
    "children's 4-8",
    "children's 9-12",
    "children's baby",
    "preschool picture",
    "children's audio - 4-8",
    "children's audio - 9-12",
    "children: grades",
];
const PICTURE_BANDS: &[&str] = &[
    "picturebooks",
    "baby-preschool",
    "children: preschool",
    "preschool picture",
    "boardbooks",
    "children's baby",
];

thread_local! {
    static MEMO: RefCell<HashMap<String, Vec<u8>>> = RefCell::new(HashMap::new());
}

pub fn rules() -> &'static [Rule] {
    &TAXONOMY.rules
}

pub fn tag(slug: &str) -> u8 {
    rules()
        .iter()
        .position(|rule| rule.slug == slug)
        .expect("known slug") as u8
}

pub fn slug(tag: u8) -> &'static str {
    &rules()[tag as usize].slug
}

pub fn category(tag: u8) -> Category {
    rules()[tag as usize].category
}

pub fn is_genre_like(tag: u8) -> bool {
    matches!(category(tag), Genre | Audience | Form)
        && !matches!(slug(tag), "fiction" | "nonfiction")
}

#[derive(Clone, Copy, Default)]
pub struct Classes {
    pub classified: u16,
    pub picture: u16,
    pub juvenile: u16,
    pub comics: u16,
    pub poetry: u16,
    pub drama: u16,
    pub kid: u16,
    pub teen: u16,
    pub young_picture: u16,
}

impl Classes {
    pub fn absorb(&mut self, other: Classes) {
        self.classified = self.classified.saturating_add(other.classified);
        self.picture = self.picture.saturating_add(other.picture);
        self.juvenile = self.juvenile.saturating_add(other.juvenile);
        self.comics = self.comics.saturating_add(other.comics);
        self.poetry = self.poetry.saturating_add(other.poetry);
        self.drama = self.drama.saturating_add(other.drama);
        self.kid = self.kid.saturating_add(other.kid);
        self.teen = self.teen.saturating_add(other.teen);
        self.young_picture = self.young_picture.saturating_add(other.young_picture);
    }

    fn share(&self, count: u16) -> f32 {
        count as f32 / self.classified.max(1) as f32
    }
}

fn lowercase_strings<'a>(
    edition: &'a Value,
    field: &str,
) -> impl Iterator<Item = String> + 'a {
    edition
        .get(field)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(|text| text.trim().to_ascii_lowercase())
}

fn class_number(code: &str, prefix: &str) -> Option<u32> {
    let rest = code.strip_prefix(prefix)?.trim_start();
    let digits = rest
        .find(|character: char| !character.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..digits].parse().ok()
}

pub fn classes_of(edition: &Value) -> Classes {
    let mut classes = Classes::default();
    for code in lowercase_strings(edition, "dewey_decimal_class") {
        classes.classified += 1;
        classes.picture += u16::from(code == "[e]" || code == "e");
        classes.juvenile += u16::from(code == "[fic]" || code.starts_with('j'));
        classes.comics += u16::from(code.starts_with("741.5"));
        classes.poetry += u16::from(code.starts_with("811") || code.starts_with("821"));
        classes.drama += u16::from(code.starts_with("812") || code.starts_with("822"));
    }
    for code in lowercase_strings(edition, "lc_classifications") {
        classes.classified += 1;
        classes.juvenile +=
            u16::from(class_number(&code, "pz").is_some_and(|number| number >= 5));
        let comics = class_number(&code, "pn")
            .is_some_and(|number| (6700..6800).contains(&number));
        classes.comics += u16::from(comics);
    }
    let bands: Vec<String> = lowercase_strings(edition, "subjects").collect();
    let has_band = |markers: &[&str]| {
        bands
            .iter()
            .any(|band| markers.iter().any(|marker| band.contains(marker)))
    };
    classes.teen = u16::from(has_band(TEEN_BANDS));
    classes.kid = u16::from(classes.teen == 0 && has_band(KID_BANDS));
    classes.young_picture = u16::from(has_band(PICTURE_BANDS));
    classes
}

fn contains_word(haystack: &str, needle: &str) -> bool {
    let bytes = haystack.as_bytes();
    let mut from = 0;
    while let Some(offset) = haystack[from..].find(needle) {
        let start = from + offset;
        let mut end = start + needle.len();
        if bytes.get(end) == Some(&b's') {
            end += 1;
        }
        let opens = start == 0 || !bytes[start - 1].is_ascii_alphanumeric();
        let closes = end >= bytes.len() || !bytes[end].is_ascii_alphanumeric();
        if opens && closes {
            return true;
        }
        from = start + 1;
    }
    false
}

impl Rule {
    fn is_excluded(&self, subject: &str) -> bool {
        self.exclude.iter().any(|bad| contains_word(subject, bad))
    }

    fn matches(&self, subject: &str) -> bool {
        !self.is_excluded(subject)
            && self.include.iter().any(|good| contains_word(subject, good))
    }

    fn matches_motif(&self, subject: &str) -> bool {
        !self.is_excluded(subject)
            && self.motifs.iter().any(|motif| contains_word(subject, motif))
    }
}

fn motif_tags(subject: &str) -> impl Iterator<Item = u8> {
    rules()
        .iter()
        .enumerate()
        .filter(move |(_, rule)| rule.matches_motif(subject))
        .map(|(id, _)| id as u8)
}

fn rule_tags(subject: &str) -> Vec<u8> {
    MEMO.with_borrow_mut(|memo| {
        if let Some(tags) = memo.get(subject) {
            return tags.clone();
        }
        if memo.len() > MEMO_LIMIT {
            memo.clear();
        }
        let tags: Vec<u8> = rules()
            .iter()
            .enumerate()
            .filter(|(_, rule)| rule.matches(subject))
            .map(|(id, _)| id as u8)
            .collect();
        memo.insert(subject.to_string(), tags.clone());
        tags
    })
}

fn is_ignored(subject: &str) -> bool {
    IGNORED_PREFIXES
        .iter()
        .any(|prefix| subject.starts_with(prefix))
        || IGNORED_PHRASES
            .iter()
            .any(|phrase| subject.contains(phrase))
}

fn is_teen_reading_level(subject: &str) -> bool {
    subject
        .strip_prefix("reading level-grade ")
        .and_then(|grade| grade.trim().parse::<u8>().ok())
        .is_some_and(|grade| (9..=12).contains(&grade))
}

fn bisac_path(subject: &str) -> Option<String> {
    if subject.contains(" / ") {
        return Some(
            subject
                .split('/')
                .map(str::trim)
                .collect::<Vec<_>>()
                .join(", "),
        );
    }
    let prefixed = ["fiction, ", "juvenile fiction, ", "young adult fiction, "]
        .iter()
        .any(|prefix| subject.starts_with(prefix));
    prefixed.then(|| {
        subject
            .split(',')
            .map(str::trim)
            .collect::<Vec<_>>()
            .join(", ")
    })
}

fn bisac_tags(path: &str) -> Vec<u8> {
    let mut tags: Vec<u8> = TAXONOMY
        .bisac
        .iter()
        .filter(|(prefix, _)| {
            path == *prefix
                || path.starts_with(*prefix) && path[prefix.len()..].starts_with(", ")
        })
        .flat_map(|(_, slugs)| slugs.iter().map(|slug| tag(slug)))
        .collect();
    tags.sort_unstable();
    tags.dedup();
    tags
}

#[derive(Default, Clone)]
struct Support(Vec<(u8, u16)>);

impl Support {
    fn add(&mut self, tag: u8, weight: u16) {
        match self.0.iter_mut().find(|(known, _)| *known == tag) {
            Some(entry) => entry.1 += weight,
            None => self.0.push((tag, weight)),
        }
    }

    fn of(&self, slug: &str) -> u16 {
        let wanted = tag(slug);
        self.0
            .iter()
            .find(|(known, _)| *known == wanted)
            .map_or(0, |entry| entry.1)
    }

    fn add_subject(&mut self, subject: &str) {
        let bisac = bisac_path(subject)
            .map(|path| bisac_tags(&path))
            .unwrap_or_default();
        if !bisac.is_empty() {
            bisac
                .into_iter()
                .for_each(|found| self.add(found, BISAC_WEIGHT));
            return;
        }
        let teen = tag("young-adult");
        for found in rule_tags(subject) {
            let weight = if found == teen {
                TEEN_SUBJECT_WEIGHT
            } else {
                1
            };
            self.add(found, weight);
        }
    }
}

#[derive(PartialEq)]
enum Reader {
    Unknown,
    Adult,
    Kids,
    Teens,
}

fn reader_of(classes: &Classes, editions: u16) -> Reader {
    let juvenile_share = classes.share(classes.juvenile);
    if classes.classified >= ADULT_MIN_CLASSIFIED && juvenile_share < ADULT_JUVENILE_SHARE
    {
        return Reader::Adult;
    }
    if classes.classified > 0 && juvenile_share < YOUNG_JUVENILE_SHARE {
        return Reader::Unknown;
    }
    if classes.kid > classes.teen {
        return Reader::Kids;
    }
    let unbanded_teen = classes.kid == 0 && editions >= TEEN_MIN_EDITIONS;
    if classes.teen > classes.kid || unbanded_teen && classes.classified > 0 {
        return Reader::Teens;
    }
    Reader::Unknown
}

fn class_tags(classes: &Classes) -> Vec<u8> {
    [
        (classes.picture, "picture-book"),
        (classes.juvenile, "childrens"),
        (classes.comics, "graphic-novel"),
        (classes.poetry, "poetry"),
        (classes.drama, "drama"),
    ]
    .into_iter()
    .filter(|(count, _)| classes.share(*count) >= CLASS_SHARE)
    .map(|(_, slug)| tag(slug))
    .collect()
}

fn reader_tags(classes: &Classes, reader: &Reader) -> Vec<u8> {
    let picture = classes.young_picture > 0 && classes.young_picture * 4 >= classes.kid;
    match reader {
        Reader::Kids if picture => vec![tag("childrens"), tag("picture-book")],
        Reader::Kids => vec![tag("childrens")],
        Reader::Teens => vec![tag("young-adult")],
        _ => Vec::new(),
    }
}

fn resolve_reader(chosen: Vec<u8>, reader: &Reader, evidence: &Support) -> Vec<u8> {
    let teen_evidence = evidence.of("young-adult") > evidence.of("childrens");
    let dropped: &[&str] = match reader {
        Reader::Adult => &["childrens", "young-adult", "picture-book"],
        Reader::Kids => &["young-adult"],
        Reader::Teens => &["childrens", "picture-book"],
        Reader::Unknown if teen_evidence => &["childrens", "picture-book"],
        Reader::Unknown => &[],
    };
    let mut kept: Vec<u8> = chosen
        .into_iter()
        .filter(|&found| !dropped.contains(&slug(found)))
        .collect();
    let teen = tag("young-adult");
    if *reader == Reader::Unknown && teen_evidence && !kept.contains(&teen) {
        kept.push(teen);
    }
    kept
}

fn with_nonfiction(mut chosen: Vec<u8>) -> Vec<u8> {
    let storytelling = ["fiction", "nonfiction", "poetry", "drama", "graphic-novel"];
    let has_story = chosen
        .iter()
        .any(|&found| storytelling.contains(&slug(found)));
    let has_topic = chosen.iter().any(|&found| category(found) == Topic);
    if has_topic && !has_story {
        chosen.push(tag("nonfiction"));
    }
    chosen
}

fn is_specific(tag: u8) -> bool {
    SPECIFIC.contains(&slug(tag))
}

fn is_supported(tag: u8, count: u16, fiction: u16, rich: bool) -> bool {
    if category(tag) == Topic && rich && count < RICH_TOPIC_SUPPORT {
        return is_specific(tag) && count >= 2;
    }
    let adaptation = matches!(slug(tag), "graphic-novel" | "drama");
    !(adaptation && fiction >= 3 && count < 3)
}

fn select_supported(mut support: Vec<(u8, u16)>, fiction: u16, rich: bool) -> Vec<u8> {
    support.retain(|&(found, count)| is_supported(found, count, fiction, rich));
    let top = support.first().map_or(0, |entry| entry.1);
    let floor = (RELATIVE_FLOOR * top as f32).ceil() as u16;
    support.retain(|&(found, count)| {
        let exempt = matches!(slug(found), "fiction" | "classics") || is_specific(found);
        exempt || count >= floor
    });
    let strong: Vec<u8> = support
        .iter()
        .filter(|&&(found, count)| count >= 2 || is_specific(found))
        .map(|&(found, _)| found)
        .collect();
    if strong.len() >= 2 {
        return strong.into_iter().take(MAX_TAGS).collect();
    }
    let fill = strong.len().clamp(WEAK_TAG_FILL, MAX_TAGS);
    support.iter().take(fill).map(|&(found, _)| found).collect()
}

pub fn confident_tags<'a>(
    subjects: impl Iterator<Item = &'a str>,
    classes: &Classes,
    editions: u16,
) -> Vec<u8> {
    let mut support = Support::default();
    let mut seen: Vec<String> = Vec::new();
    for subject in subjects {
        let normalized = subject.trim().trim_matches('"').to_ascii_lowercase();
        if seen.contains(&normalized) {
            continue;
        }
        if is_teen_reading_level(&normalized) {
            support.add(tag("young-adult"), 1);
        }
        if is_ignored(&normalized) {
            continue;
        }
        support.add_subject(&normalized);
        seen.push(normalized);
    }
    if support.of("fiction") > 0 {
        for found in seen.iter().flat_map(|subject| motif_tags(subject)) {
            support.add(found, MOTIF_WEIGHT);
        }
    }
    class_tags(classes)
        .into_iter()
        .for_each(|found| support.add(found, CLASS_WEIGHT));
    let reader = reader_of(classes, editions);
    for found in reader_tags(classes, &reader) {
        support.add(found, BAND_WEIGHT);
    }
    support.0.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let fiction = support.of("fiction");
    let chosen =
        select_supported(support.0.clone(), fiction, seen.len() >= RICH_SUBJECTS);
    with_nonfiction(resolve_reader(chosen, &reader, &support))
}
