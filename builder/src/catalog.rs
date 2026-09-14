use crate::dumps::{Authors, Book, FLAG_COVER, FLAG_ISBN, FLAG_PUBLISHER, FLAG_READABLE};
use crate::dumps::{Facts, Signal, TitleRecord, Titles};
use crate::release::State;
use crate::tags::{self, Category};
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

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
pub const CORE: u8 = 0;
const KIDS_TAGS: [&str; 3] = ["childrens", "picture-book", "middle-grade"];
pub const DESCRIPTION_MIN_SCORE: f32 = 400.0;

const MIN_SCORE: f32 = 150.0;
const FOREIGN_TITLE_WORDS: usize = 3;
const DESCRIPTION_LIMIT: usize = 480;
const JUDGED_WORDS: usize = 25;
const MIN_STOP_WORD_SHARE: f32 = 0.05;
const AUTHOR_FILL_SHARE: f32 = 0.7;
const AUTHOR_FILL_MINIMUM: usize = 2;
const MIN_SERIES_NAME: usize = 4;
const MAX_SERIES_NAME: usize = 80;
const MIN_SERIES_MEMBERS: usize = 2;
const MAX_SERIES_MEMBERS: usize = 40;
const MAX_TOKEN_BYTES: usize = 24;

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

const BAD_SUBJECTS: &[&str] = &[
    "periodicals",
    "government publications",
    "dissertations",
    "congresses",
    "bibliography",
    "abstracts",
    "statistics",
    "catalogs",
    "indexes",
    "yearbooks",
    "directories",
    "handbooks, manuals",
    "law reports",
    "legislation",
    "patents",
    "standards",
    "specifications",
    "examinations",
    "outlines, syllabi",
    "notation",
    "registers",
    "tables",
];

const VOLUME_MARKERS: &[&str] = &[
    "no", "nos", "v", "vol", "volume", "bk", "book", "part", "pt", "band", "tome",
    "issue",
];

const GENERIC_SERIES: &[&str] = &[
    "a novel",
    "novel",
    "fiction",
    "classics",
    "the classics",
    "classic",
    "collection",
    "the collection",
    "collected works",
    "library",
    "the library",
    "series",
    "the series",
    "new edition",
    "omnibus",
    "boxed set",
    "box set",
    "anthology",
    "reader",
    "readers",
    "picture books",
    "chapter books",
    "graphic novels",
    "large print",
    "audiobook",
];

static STOP_WORD_LANGUAGE: LazyLock<HashMap<&'static str, usize>> = LazyLock::new(|| {
    let lists = STOP_WORDS.iter().enumerate();
    lists
        .flat_map(|(language, list)| {
            list.split_whitespace().map(move |word| (word, language))
        })
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

pub fn is_eligible(
    traits: &Traits,
    facts: &Facts,
    signal: &Signal,
    language: usize,
) -> bool {
    let readers = signal.readers();
    let named = traits.has_author && traits.good_title;
    let published = facts.language_editions[language] > 0
        && facts.has(FLAG_READABLE)
        && (facts.has(FLAG_ISBN) || readers > 0);
    let described = traits.has_subjects || readers > 0;
    let wanted =
        readers > 0 || facts.has(FLAG_COVER) || traits.has_cover || facts.editions >= 2;
    let junk = traits.bad_subject && readers < 3;
    let popular = score(traits, facts, signal, language) >= MIN_SCORE;
    named && published && described && wanted && !junk && popular
}

pub fn score(traits: &Traits, facts: &Facts, signal: &Signal, language: usize) -> f32 {
    let attention =
        3.0 * signal.finished as f32 + 2.0 * signal.reading as f32 + signal.want as f32;
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

pub fn is_bad_title(title: &str) -> bool {
    let lowered = title.to_ascii_lowercase();
    BAD_TITLE_PREFIXES
        .iter()
        .any(|prefix| lowered.starts_with(prefix))
}

pub fn is_bad_subject(subject: &str) -> bool {
    let lowered = subject.trim().to_ascii_lowercase();
    BAD_SUBJECTS.iter().any(|bad| lowered.ends_with(bad))
}

pub fn clean_description(text: &str) -> String {
    let text = text[..text.find("\n\n----").unwrap_or(text.len())]
        .trim()
        .replace('\r', "");
    if text.len() <= DESCRIPTION_LIMIT {
        return text;
    }
    let end = text.floor_char_boundary(DESCRIPTION_LIMIT);
    let window = &text[..end];
    let stop = window.rfind(". ").map_or(end, |position| position + 1);
    format!("{}…", window[..stop].trim())
}

fn stop_word_hits(text: &str) -> ([usize; 5], usize) {
    let words = text
        .split(|character: char| !character.is_alphabetic())
        .filter(|word| !word.is_empty());
    let mut hits = [0usize; 5];
    let mut count = 0;
    for word in words {
        count += 1;
        if let Some(&language) = STOP_WORD_LANGUAGE.get(word.to_lowercase().as_str()) {
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
    let thin =
        words >= JUDGED_WORDS && (hits[best] as f32) < MIN_STOP_WORD_SHARE * words as f32;
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
    let folded = match character {
        'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' | 'Á' | 'À' | 'Â' | 'Ä' | 'Ã' | 'Å' => {
            'a'
        }
        'é' | 'è' | 'ê' | 'ë' | 'É' | 'È' | 'Ê' | 'Ë' => 'e',
        'í' | 'ì' | 'î' | 'ï' | 'Í' | 'Ì' | 'Î' | 'Ï' => 'i',
        'ó' | 'ò' | 'ô' | 'ö' | 'õ' | 'ø' | 'Ó' | 'Ò' | 'Ô' | 'Ö' | 'Õ' | 'Ø' => {
            'o'
        }
        'ú' | 'ù' | 'û' | 'ü' | 'Ú' | 'Ù' | 'Û' | 'Ü' => 'u',
        'ñ' | 'Ñ' => 'n',
        'ç' | 'Ç' => 'c',
        'ý' | 'ÿ' | 'Ý' => 'y',
        'ß' => 's',
        _ => return None,
    };
    Some(folded)
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

fn roman_value(token: &str) -> Option<u32> {
    let digits: Vec<i64> = token
        .chars()
        .map(|character| match character {
            'i' => Some(1),
            'v' => Some(5),
            'x' => Some(10),
            'l' => Some(50),
            'c' => Some(100),
            _ => None,
        })
        .collect::<Option<_>>()?;
    let total: i64 = digits
        .iter()
        .enumerate()
        .map(|(slot, &value)| {
            let subtracted = digits[slot + 1..].iter().any(|&later| later > value);
            if subtracted { -value } else { value }
        })
        .sum();
    u32::try_from(total).ok().filter(|&total| total > 0)
}

fn position_of(part: &str) -> Option<u16> {
    let tokens = tokenize(part);
    let (number, markers) = tokens.split_last()?;
    let all_markers = markers.iter().all(|token| {
        VOLUME_MARKERS.contains(&token.as_str()) || roman_value(token).is_some()
    });
    if !all_markers {
        return None;
    }
    let value = number.parse().ok().or_else(|| roman_value(number))?;
    u16::try_from(value)
        .ok()
        .filter(|&value| value > 0 && value < 500)
}

fn tidy(text: &str) -> &str {
    text.trim().trim_end_matches([';', ',', '.', ':']).trim()
}

fn strip_volume_suffix(name: &str) -> &str {
    match name.rfind([',', '#']) {
        Some(cut) if cut > 0 && position_of(&name[cut..]).is_some() => {
            name[..cut].trim_end()
        }
        _ => name,
    }
}

fn clean_series_name(raw: &str) -> Option<String> {
    let name = tidy(strip_volume_suffix(tidy(raw)));
    let lowered = name.to_ascii_lowercase();
    let plain = (MIN_SERIES_NAME..=MAX_SERIES_NAME).contains(&name.len())
        && is_mostly_latin(name)
        && name.chars().any(char::is_alphabetic)
        && position_of(name).is_none()
        && !GENERIC_SERIES.contains(&lowered.as_str());
    plain.then(|| name.to_string())
}

pub fn parse_series(parts: &[&str]) -> Option<(String, u16)> {
    let first = tidy(parts.first()?);
    let name = clean_series_name(first)?;
    let tail = first[name.len()..].trim_start_matches([',', '#', ' ']);
    let later = parts[1..].iter().find_map(|part| position_of(part));
    Some((name, later.or_else(|| position_of(tail)).unwrap_or(0)))
}

pub fn fill_author_tags(books: &mut [Book]) {
    let fiction = tags::tag("fiction");
    let nonfiction = tags::tag("nonfiction");
    let is_shelved = |book: &Book| book.tags.iter().any(|&tag| tags::is_genre_like(tag));
    let mut order: Vec<usize> = (0..books.len())
        .filter(|&index| !books[index].authors.is_empty())
        .collect();
    order.sort_by_key(|&index| books[index].authors[0]);
    let mut fills: Vec<(usize, Vec<u8>)> = Vec::new();
    for group in order.chunk_by(|&a, &b| books[a].authors[0] == books[b].authors[0]) {
        let shelved: Vec<usize> = group
            .iter()
            .copied()
            .filter(|&i| is_shelved(&books[i]))
            .collect();
        if shelved.len() < AUTHOR_FILL_MINIMUM || shelved.len() == group.len() {
            continue;
        }
        let mut counts: HashMap<u8, usize> = HashMap::new();
        for &index in &shelved {
            let borrowable = books[index]
                .tags
                .iter()
                .filter(|&&tag| tags::is_genre_like(tag) || tag == fiction);
            borrowable.for_each(|&tag| *counts.entry(tag).or_default() += 1);
        }
        let needed = AUTHOR_FILL_SHARE * shelved.len() as f32;
        let mut shared: Vec<(usize, u8)> = counts
            .into_iter()
            .filter(|&(_, count)| count as f32 >= needed)
            .map(|(tag, count)| (count, tag))
            .collect();
        shared.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        for &index in group {
            let book = &books[index];
            let decided = book
                .tags
                .iter()
                .any(|&tag| tag == fiction || tag == nonfiction);
            if decided || is_shelved(book) {
                continue;
            }
            let borrowed: Vec<u8> = shared
                .iter()
                .map(|&(_, tag)| tag)
                .filter(|&tag| !book.tags.contains(&tag))
                .filter(|&tag| {
                    book.tags.is_empty() || tags::category(tag) != Category::Audience
                })
                .collect();
            if borrowed.iter().any(|&tag| tags::is_genre_like(tag)) {
                fills.push((index, borrowed));
            }
        }
    }
    for (index, borrowed) in fills {
        books[index].tags.extend(borrowed);
    }
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

fn spelling<'a>(record: &TitleRecord, titles: &'a Titles, series: &str) -> &'a str {
    let title = titles.title(record);
    let subtitle = titles.subtitle(record);
    let named = subtitle.chars().next().is_some_and(char::is_uppercase);
    if !named || !is_series_label(title, series) {
        return title;
    }
    subtitle
}

fn title_ballots<'a>(
    records: &[&TitleRecord],
    titles: &'a Titles,
    series: &str,
) -> Vec<(String, &'a str, u16)> {
    let spellings = records
        .iter()
        .map(|record| spelling(record, titles, series));
    let latin = spellings.filter(|title| is_mostly_latin(title));
    latin
        .map(|title| (title_key(title, &[]), title, 0))
        .filter(|ballot| !ballot.0.is_empty())
        .collect()
}

fn choose_title(
    work_title: &str,
    series: &str,
    records: &[&TitleRecord],
    titles: &Titles,
) -> (String, String) {
    let ballots = title_ballots(records, titles, series);
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

fn choose_series(records: &[&TitleRecord], titles: &Titles) -> (String, u16) {
    let ballots = records
        .iter()
        .map(|record| (titles.series(record), record.position))
        .filter(|(name, _)| !name.is_empty())
        .map(|(name, position)| (series_key(name), name, position))
        .collect();
    tally(ballots).map_or((String::new(), 0), |vote| {
        (vote.spelling.to_string(), vote.position)
    })
}

pub struct Entry {
    pub book: usize,
    pub title: String,
    pub alternate: String,
    pub series: String,
    pub series_position: u16,
    pub score: f32,
    pub sticky: Option<u8>,
    local_title: bool,
}

fn resolve(index: usize, book: &Book, titles: &Titles, language: usize) -> Entry {
    let records = titles.of(book.work);
    let in_language: Vec<&TitleRecord> = records
        .iter()
        .filter(|record| record.language as usize == language)
        .filter(|record| !is_foreign_title(titles.title(record), language))
        .collect();
    let everywhere: Vec<&TitleRecord> = records.iter().collect();
    let (series, series_position) = choose_series(&in_language, titles);
    let (franchise, _) = choose_series(&everywhere, titles);
    let voters = if in_language.is_empty() {
        &everywhere
    } else {
        &in_language
    };
    let (mut title, mut alternate) = choose_title(&book.title, "", voters, titles);
    if is_series_label(&title, &franchise) {
        (title, alternate) = choose_title(&book.title, &franchise, voters, titles);
    }
    if !series.is_empty() && series_key(&alternate) == series_key(&series) {
        alternate.clear();
    }
    let same_key = title_key(&alternate, &[]) == title_key(&title, &[]);
    if same_key || alternate.to_lowercase() == title.to_lowercase() {
        alternate.clear();
    }
    Entry {
        book: index,
        title,
        alternate,
        series,
        series_position,
        score: book.scores[language],
        sticky: None,
        local_title: !in_language.is_empty(),
    }
}

pub fn candidates(
    books: &[Book],
    titles: &Titles,
    authors: &Authors,
    language: usize,
    previous: Option<&State>,
) -> Vec<Entry> {
    let mut entries: Vec<Entry> = books
        .iter()
        .enumerate()
        .filter_map(|(index, book)| {
            let sticky = previous.and_then(|state| state.pack_of(book.work));
            if !book.eligible[language] && sticky.is_none() {
                return None;
            }
            let entry = resolve(index, book, titles, language);
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

fn primary_pack(tags: &[u8]) -> u8 {
    let slugs: Vec<&str> = tags.iter().map(|&tag| tags::slug(tag)).collect();
    let pack = if slugs.iter().any(|slug| KIDS_TAGS.contains(slug)) {
        "kids"
    } else if slugs.contains(&"young-adult") {
        "young-adult"
    } else {
        slugs
            .iter()
            .find_map(|slug| genre_pack(slug))
            .unwrap_or("general")
    };
    PACKS
        .iter()
        .position(|&known| known == pack)
        .expect("known pack") as u8
}

fn genre_pack(slug: &str) -> Option<&'static str> {
    match slug {
        "fantasy" | "epic-fantasy" | "urban-fantasy" | "paranormal" => Some("fantasy"),
        "science-fiction" | "space-opera" | "dystopian" => Some("scifi"),
        "mystery" | "cozy-mystery" | "thriller" | "crime" => Some("mystery"),
        "romance" | "regency-romance" | "contemporary-romance" | "historical-romance" => {
            Some("romance")
        }
        "nonfiction" => Some("nonfiction"),
        _ => None,
    }
}

pub struct Series {
    pub name: String,
    pub members: Vec<u32>,
}

pub struct Chosen<'a> {
    pub entry: &'a Entry,
    pub book: &'a Book,
    pub pack: u8,
    pub series: Option<(usize, u16)>,
}

pub struct Selection<'a> {
    pub chosen: Vec<Chosen<'a>>,
    pub series: Vec<Series>,
}

pub fn select<'a>(
    entries: &'a [Entry],
    books: &'a [Book],
    limit: usize,
    core_limit: usize,
) -> Selection<'a> {
    let mut fresh = 0;
    let mut chosen: Vec<Chosen> = Vec::new();
    for entry in entries {
        let book = &books[entry.book];
        let pack = match entry.sticky {
            Some(pack) => pack,
            None if fresh >= limit => continue,
            None if fresh < core_limit => CORE,
            None => primary_pack(&book.tags),
        };
        fresh += usize::from(entry.sticky.is_none());
        chosen.push(Chosen {
            entry,
            book,
            pack,
            series: None,
        });
    }
    let series = group_series(&mut chosen);
    Selection { chosen, series }
}

fn dominant_author(chosen: &[Chosen], members: &[usize]) -> Option<u32> {
    let mut authors: Vec<u32> = members
        .iter()
        .filter_map(|&index| chosen[index].book.authors.first().copied())
        .collect();
    authors.sort_unstable();
    let largest = authors
        .chunk_by(|a, b| a == b)
        .max_by_key(|same| same.len())?;
    (largest.len() * 2 > members.len()).then_some(largest[0])
}

fn reading_order(chosen: &[Chosen], mut members: Vec<usize>) -> Vec<usize> {
    let mut positions: Vec<u16> = members
        .iter()
        .map(|&index| chosen[index].entry.series_position)
        .collect();
    positions.sort_unstable();
    positions.dedup();
    let numbered = positions.len() == members.len() && !positions.contains(&0);
    members.sort_by_key(|&index| {
        let (entry, book) = (chosen[index].entry, chosen[index].book);
        let lead = if numbered {
            entry.series_position
        } else {
            book.year
        };
        (lead, index)
    });
    members
}

fn group_series(chosen: &mut [Chosen]) -> Vec<Series> {
    let mut named: Vec<(String, usize)> = chosen
        .iter()
        .enumerate()
        .filter(|(_, item)| !item.entry.series.is_empty())
        .map(|(index, item)| (series_key(&item.entry.series), index))
        .collect();
    named.sort_unstable();
    let mut series = Vec::new();
    for group in named.chunk_by(|a, b| a.0 == b.0) {
        let members: Vec<usize> = group.iter().map(|&(_, index)| index).collect();
        let Some(author) = dominant_author(chosen, &members) else {
            continue;
        };
        let mut kept: Vec<usize> = members
            .into_iter()
            .filter(|&index| chosen[index].book.authors.first() == Some(&author))
            .collect();
        kept.truncate(MAX_SERIES_MEMBERS);
        if kept.len() < MIN_SERIES_MEMBERS {
            continue;
        }
        let ordered = reading_order(chosen, kept);
        let mut spellings: Vec<&str> = ordered
            .iter()
            .map(|&index| chosen[index].entry.series.as_str())
            .collect();
        spellings.sort_unstable();
        let name = spellings
            .chunk_by(|a, b| a == b)
            .max_by_key(|same| same.len())
            .expect("members")[0];
        let members = ordered
            .iter()
            .map(|&index| chosen[index].book.work)
            .collect();
        series.push(Series {
            name: name.to_string(),
            members,
        });
        for (order, &index) in ordered.iter().enumerate() {
            chosen[index].series = Some((series.len() - 1, order as u16 + 1));
        }
    }
    series
}
