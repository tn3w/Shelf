//! Tokenizer and ranking shared with the app (`Search.kt`): same tokens, same scores.

use crate::reader::{Book, Catalogue};
use std::collections::{HashMap, HashSet};
use std::ops::RangeInclusive;

const AUTHOR_LIMIT: usize = 150;
const RERANK_DEPTH: usize = 250;
const PREFIX_EXPANSIONS: usize = 12;
const FUZZY_CANDIDATES: usize = 60;
const AUTHOR_FIELD: f64 = 0.85;
const EXACT: f64 = 1.0;
const PREFIX: f64 = 0.5;
const PHRASE: f64 = 0.4;
const LENGTH: f64 = 0.04;
const COVERAGE: f64 = 0.4;
const COMBO: f64 = 0.5;
const AUTHOR: f64 = 1.0;
const POPULARITY: f64 = 0.6;
const ALTERNATE: f64 = 0.6;
const COMPANION_PENALTY: f64 = 0.7;
const SERIES_START: f64 = 0.9;
const RELEVANCE_FLOOR: f64 = 0.45;
const ANCHOR: f64 = 0.8;
const LONG_COMPLETION: f64 = 0.9;
const SHORT_COMPLETION: f64 = 0.75;
const SPARSE_EXACT: u32 = 50;
const SPARSE_COMPLETIONS: u32 = 20;
const ONE_TYPO: f64 = 0.72;
const TWO_TYPOS: f64 = 0.5;
const RANK_POPULARITY: f64 = 0.35;
const MAX_TOKEN_CHARACTERS: usize = 24;
pub const ARTICLES: [&str; 12] = [
    "the", "a", "an", "der", "die", "das", "le", "la", "les", "el", "los", "las",
];

const FOLDED: [(&str, char); 24] = [
    ("áàâäãåąÁÀÂÄÃÅĄ", 'a'),
    ("éèêëęěÉÈÊËĘĚ", 'e'),
    ("íìîïÍÌÎÏ", 'i'),
    ("óòôöõøÓÒÔÖÕØ", 'o'),
    ("úùûüůÚÙÛÜŮ", 'u'),
    ("ñńňÑŃŇ", 'n'),
    ("çćčÇĆČ", 'c'),
    ("ýÿÝ", 'y'),
    ("ßšśŠŚ", 's'),
    ("žźżŽŹŻ", 'z'),
    ("łľĺŁĽĹ", 'l'),
    ("řŕŘŔ", 'r'),
    ("ďĎ", 'd'),
    ("ťŤ", 't'),
    ("āĀ", 'a'),
    ("ēėĒĖ", 'e'),
    ("īįĪĮ", 'i'),
    ("ūųŪŲ", 'u'),
    ("ģĢ", 'g'),
    ("ķĶ", 'k'),
    ("ļĻ", 'l'),
    ("ņŅ", 'n'),
    ("ŗŖ", 'r'),
    ("ōŌ", 'o'),
];
pub const HANGUL: RangeInclusive<char> = '\u{ac00}'..='\u{d7a3}';
const CYRILLIC: RangeInclusive<char> = '\u{400}'..='\u{4ff}';

pub fn is_cyrillic(character: char) -> bool {
    CYRILLIC.contains(&character)
}
const KANA: [RangeInclusive<char>; 3] = [
    '\u{3040}'..='\u{30ff}',
    '\u{31f0}'..='\u{31ff}',
    '\u{ff66}'..='\u{ff9f}',
];
const KANJI: [RangeInclusive<char>; 3] = [
    '\u{3400}'..='\u{4dbf}',
    '\u{4e00}'..='\u{9fff}',
    '\u{f900}'..='\u{faff}',
];
const FULLWIDTH: RangeInclusive<char> = '\u{ff01}'..='\u{ff5e}';
const FULLWIDTH_OFFSET: u32 = 0xfee0;

pub fn is_kana(character: char) -> bool {
    KANA.iter().any(|range| range.contains(&character))
}

pub fn is_cjk(character: char) -> bool {
    let kanji = KANJI.iter().any(|range| range.contains(&character));
    kanji || is_kana(character) && character != '・'
}

fn fold(character: char) -> Option<char> {
    let narrow = FULLWIDTH
        .contains(&character)
        .then(|| char::from_u32(character as u32 - FULLWIDTH_OFFSET))
        .flatten();
    let lowered = narrow.unwrap_or(character).to_ascii_lowercase();
    if lowered.is_ascii_alphanumeric() || HANGUL.contains(&character) {
        return Some(lowered);
    }
    if is_cyrillic(character) {
        let lowered = character.to_lowercase().next()?;
        return Some(if lowered == 'ё' { 'е' } else { lowered });
    }
    let found = FOLDED.iter().find(|(set, _)| set.contains(character));
    found.map(|&(_, folded)| folded)
}

fn push_word(word: &mut String, length: &mut usize, tokens: &mut Vec<String>) {
    if !word.is_empty() {
        tokens.push(std::mem::take(word));
    }
    *length = 0;
}

fn push_bigrams(run: &mut Vec<char>, tokens: &mut Vec<String>) {
    match run.as_slice() {
        [] => return,
        [single] => tokens.push(single.to_string()),
        pairs => tokens.extend(pairs.windows(2).map(|pair| pair.iter().collect())),
    }
    run.clear();
}

pub fn tokenize(text: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut word = String::new();
    let mut run = Vec::new();
    let mut length = 0;
    let kept = text
        .chars()
        .filter(|character| !matches!(character, '\'' | '’' | '\u{300}'..='\u{36f}'));
    for character in kept {
        if is_cjk(character) {
            push_word(&mut word, &mut length, &mut tokens);
            run.push(character);
            continue;
        }
        push_bigrams(&mut run, &mut tokens);
        let Some(folded) = fold(character) else {
            push_word(&mut word, &mut length, &mut tokens);
            continue;
        };
        word.push(folded);
        length += 1;
        if length >= MAX_TOKEN_CHARACTERS {
            push_word(&mut word, &mut length, &mut tokens);
        }
    }
    push_bigrams(&mut run, &mut tokens);
    push_word(&mut word, &mut length, &mut tokens);
    tokens
}

pub fn without_article(text: &str) -> &str {
    let stripped = ARTICLES
        .iter()
        .find_map(|article| text.strip_prefix(article)?.strip_prefix(' '));
    stripped.unwrap_or(text)
}

pub fn main_title(title: &str) -> &str {
    title.split([':', ';', '(', '/']).next().unwrap_or(title)
}

pub fn edit_distance(left: &str, right: &str, limit: usize) -> usize {
    let (left, right): (Vec<char>, Vec<char>) = (left.chars().collect(), right.chars().collect());
    if left.len().abs_diff(right.len()) > limit {
        return limit + 1;
    }
    let mut before_previous: Option<Vec<usize>> = None;
    let mut previous: Vec<usize> = (0..=right.len()).collect();
    for i in 1..=left.len() {
        let mut current = vec![0; right.len() + 1];
        current[0] = i;
        for j in 1..=right.len() {
            let cost = usize::from(left[i - 1] != right[j - 1]);
            current[j] = (previous[j] + 1)
                .min(current[j - 1] + 1)
                .min(previous[j - 1] + cost);
            let swapped =
                i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1];
            if let Some(earlier) = before_previous.as_ref().filter(|_| swapped) {
                current[j] = current[j].min(earlier[j - 2] + 1);
            }
        }
        if current.iter().min().is_some_and(|&least| least > limit) {
            return limit + 1;
        }
        before_previous = Some(previous);
        previous = current;
    }
    previous[right.len()]
}

pub fn allowed_typos(token: &str) -> usize {
    match token.encode_utf16().count() {
        0..=3 => 0,
        4..=6 => 1,
        _ => 2,
    }
}

fn transpositions(token: &str) -> Vec<String> {
    let characters: Vec<char> = token.chars().collect();
    (0..characters.len().saturating_sub(1))
        .map(|index| {
            let mut swapped = characters.clone();
            swapped.swap(index, index + 1);
            swapped.into_iter().collect()
        })
        .collect()
}

fn trigrams(term: &str) -> Vec<String> {
    let characters: Vec<char> = format!("${term}$").chars().collect();
    characters
        .windows(3)
        .map(|window| window.iter().collect())
        .collect()
}

fn sorted_by_value<K: Ord + Copy, V: PartialOrd + Copy>(map: &HashMap<K, V>) -> Vec<(K, V)> {
    let mut entries: Vec<(K, V)> = map.iter().map(|(&key, &value)| (key, value)).collect();
    entries.sort_by_key(|&(key, _)| key);
    entries.sort_by(|a, b| b.1.partial_cmp(&a.1).expect("comparable"));
    entries
}

struct TermMatch {
    text: String,
    weight: f64,
}

pub struct Lookup {
    pub tokens: Vec<String>,
    matches: Vec<Vec<TermMatch>>,
}

struct TokenScores {
    token: String,
    scores: HashMap<u32, f64>,
    terms: HashMap<u32, String>,
    idf: f64,
}

pub struct Searcher<'a> {
    catalogue: &'a Catalogue,
    total: u32,
}

impl<'a> Searcher<'a> {
    pub fn new(catalogue: &'a Catalogue) -> Searcher<'a> {
        let total = catalogue
            .ranks
            .as_ref()
            .map_or(catalogue.work_count(), |ranks| ranks.works);
        Searcher { catalogue, total }
    }

    fn fuzzy_terms(&self, token: &str) -> Vec<(String, usize)> {
        let limit = allowed_typos(token);
        if limit == 0 {
            return Vec::new();
        }
        let needed = (trigrams(token).len() as i64 - 3 * limit as i64).max(1) as u32;
        let variants: Vec<String> = std::iter::once(token.to_string())
            .chain(transpositions(token))
            .collect();
        let mut texts: Vec<String> = Vec::new();
        for segment in &self.catalogue.segments {
            let mut hits: HashMap<u32, u32> = HashMap::new();
            for variant in &variants {
                let mut variant_hits: HashMap<u32, u32> = HashMap::new();
                let grams: HashSet<String> = trigrams(variant).into_iter().collect();
                for gram in grams {
                    for term in segment.gram_terms(&gram) {
                        *variant_hits.entry(term).or_default() += 1;
                    }
                }
                for (term, count) in variant_hits {
                    let best = hits.entry(term).or_default();
                    *best = (*best).max(count);
                }
            }
            hits.retain(|_, count| *count >= needed);
            let best = sorted_by_value(&hits).into_iter().take(FUZZY_CANDIDATES);
            texts.extend(best.map(|(term, _)| segment.term_by_id(term).text));
        }
        let mut seen = HashSet::new();
        texts.retain(|text| seen.insert(text.clone()));
        texts
            .into_iter()
            .filter_map(|text| {
                let distance = edit_distance(token, &text, limit);
                (text != token && distance <= limit).then_some((text, distance))
            })
            .collect()
    }

    pub fn frequency(&self, text: &str) -> u32 {
        match &self.catalogue.ranks {
            Some(ranks) => ranks.frequency(text),
            None => self.exact_frequency(text),
        }
    }

    fn exact_frequency(&self, text: &str) -> u32 {
        let terms = self
            .catalogue
            .segments
            .iter()
            .filter_map(|segment| segment.term(text));
        terms.map(|term| term.frequency()).sum()
    }

    fn completions(&self, prefix: &str) -> Vec<String> {
        let mut texts: Vec<String> = Vec::new();
        for segment in &self.catalogue.segments {
            let found = segment.completions(prefix, PREFIX_EXPANSIONS);
            texts.extend(found.into_iter().map(|term| term.text));
        }
        let mut seen = HashSet::new();
        texts.retain(|text| seen.insert(text.clone()));
        let mut ranked: Vec<(String, u32)> = texts
            .into_iter()
            .map(|text| {
                let frequency = self.frequency(&text);
                (text, frequency)
            })
            .collect();
        ranked.sort_by_key(|(_, frequency)| std::cmp::Reverse(*frequency));
        ranked
            .into_iter()
            .take(PREFIX_EXPANSIONS)
            .map(|(text, _)| text)
            .collect()
    }

    fn term_matches(&self, token: &str, is_last: bool) -> Vec<TermMatch> {
        let mut matches = Vec::new();
        let exact_frequency = self.exact_frequency(token);
        if exact_frequency > 0 {
            matches.push(TermMatch {
                text: token.to_string(),
                weight: 1.0,
            });
        }
        let mut completion_frequency = 0;
        let length = token.encode_utf16().count();
        if is_last && length >= 2 {
            let weight = if length >= 4 {
                LONG_COMPLETION
            } else {
                SHORT_COMPLETION
            };
            for text in self.completions(token) {
                completion_frequency += self.frequency(&text);
                matches.push(TermMatch { text, weight });
            }
        }
        let sparse = exact_frequency < SPARSE_EXACT
            && (completion_frequency < SPARSE_COMPLETIONS || exact_frequency == 0);
        if !sparse {
            return matches;
        }
        for (text, distance) in self.fuzzy_terms(token) {
            let weight = if distance == 1 { ONE_TYPO } else { TWO_TYPOS };
            matches.push(TermMatch { text, weight });
        }
        matches
    }

    fn token_scores(&self, token: &str, matches: &[TermMatch]) -> TokenScores {
        let mut scores: HashMap<u32, f64> = HashMap::new();
        let mut terms: HashMap<u32, String> = HashMap::new();
        let mut offer = |work: u32, value: f64, text: &str| {
            if value <= scores.get(&work).copied().unwrap_or(0.0) {
                return;
            }
            scores.insert(work, value);
            terms.insert(work, text.to_string());
        };
        let mut authors = 0;
        for found in matches {
            for (index, segment) in self.catalogue.segments.iter().enumerate() {
                let Some(term) = segment.term(&found.text) else {
                    continue;
                };
                for work in self.catalogue.visible_works(index, &term.title_works()) {
                    offer(work, found.weight, &found.text);
                }
                for slot in term.author_slots() {
                    let over = authors >= AUTHOR_LIMIT;
                    authors += 1;
                    if over {
                        break;
                    }
                    let written = segment.author(slot).works;
                    for work in self.catalogue.visible_works(index, &written) {
                        offer(work, found.weight * AUTHOR_FIELD, &found.text);
                    }
                }
            }
        }
        let frequencies: u32 = matches
            .iter()
            .map(|found| self.frequency(&found.text))
            .sum();
        let documents = (scores.len() as u32).max(frequencies);
        let total = f64::from(self.total);
        let idf = ((total + 1.0) / (f64::from(documents.min(self.total)) + 1.0)).ln() + 1.0;
        TokenScores {
            token: token.to_string(),
            scores,
            terms,
            idf,
        }
    }

    fn candidates(per_token: &[TokenScores]) -> HashMap<u32, f64> {
        let total_idf: f64 = per_token.iter().map(|entry| entry.idf).sum();
        let works: HashSet<u32> = per_token
            .iter()
            .flat_map(|entry| entry.scores.keys().copied())
            .collect();
        let mut scored = HashMap::new();
        for work in works {
            let (mut matched, mut anchors, mut text) = (0, 0, 0.0);
            for entry in per_token {
                let Some(&value) = entry.scores.get(&work) else {
                    continue;
                };
                matched += 1;
                anchors += usize::from(value >= ANCHOR);
                text += value * entry.idf;
            }
            if anchors == 0 && matched < per_token.len() {
                continue;
            }
            let coverage = matched as f64 / per_token.len() as f64;
            let score = text / total_idf * coverage * coverage;
            if score > 0.0 {
                scored.insert(work, score);
            }
        }
        scored
    }

    fn series_bonus(&self, book: &Book, query: &str) -> f64 {
        let Some(series) = self.catalogue.series(book.work) else {
            return 0.0;
        };
        if tokenize(&series.name).join(" ") != query {
            return 0.0;
        }
        if series.members.first() == Some(&book.work) {
            SERIES_START
        } else {
            0.0
        }
    }

    fn query_of(book: &Book, per_token: &[TokenScores]) -> Vec<String> {
        per_token
            .iter()
            .map(|entry| entry.terms.get(&book.work).unwrap_or(&entry.token).clone())
            .collect()
    }

    fn names(book: &Book, title: &str, per_token: &[TokenScores]) -> bool {
        let words: Vec<String> = tokenize(title)
            .into_iter()
            .filter(|word| !ARTICLES.contains(&word.as_str()))
            .collect();
        let query = Self::query_of(book, per_token);
        !words.is_empty() && words.iter().all(|word| query.contains(word))
    }

    fn without_companions(ranked: Vec<(Book, f64)>, per_token: &[TokenScores]) -> Vec<(Book, f64)> {
        let tokens: Vec<&str> = per_token.iter().map(|entry| entry.token.as_str()).collect();
        let wants_companion = is_companion(&tokens.join(" "));
        let known_item = !wants_companion
            && ranked.iter().any(|(book, _)| {
                !book.is_companion() && Self::names(book, main_title(&book.title), per_token)
            });
        if !known_item {
            return ranked;
        }
        ranked
            .into_iter()
            .filter(|(book, _)| {
                let full = format!("{} {}", book.title, book.subtitle);
                !book.is_companion() || Self::names(book, &full, per_token)
            })
            .collect()
    }

    fn bonus(&self, book: &Book, per_token: &[TokenScores]) -> f64 {
        let query = Self::query_of(book, per_token);
        let confidence: f64 = per_token
            .iter()
            .map(|entry| entry.scores.get(&book.work).copied().unwrap_or(0.0))
            .sum::<f64>()
            / per_token.len() as f64;
        let last_token = &per_token.last().expect("tokens").token;
        let last = query.last().expect("tokens");
        let completing = last != last_token
            && (last.starts_with(last_token.as_str()) || last_token.starts_with(last.as_str()));
        let joined_query = query.join(" ");
        let labelled = self
            .catalogue
            .series(book.work)
            .map_or(String::new(), |series| {
                format!("{} {}", series.name, book.title)
            });
        let titles = [
            (book.title.as_str(), 1.0),
            (book.alternate.as_str(), ALTERNATE),
            (labelled.as_str(), ALTERNATE),
        ];
        let best_title = titles
            .iter()
            .filter(|(text, _)| !text.is_empty())
            .map(|&(text, discount)| {
                title_value(text, &book.subtitle, &query, &joined_query, completing) * discount
            })
            .reduce(f64::max)
            .map_or(0.0, |best| best.max(0.0));
        let all = format!(
            "{labelled} {} {} {}",
            book.title, book.subtitle, book.alternate
        );
        let words: HashSet<String> = tokenize(&all).into_iter().collect();
        let author_tokens: HashSet<String> = book
            .authors
            .iter()
            .flat_map(|author| tokenize(&author.name))
            .collect();
        let title_hits = query.iter().filter(|word| words.contains(*word)).count();
        let author_hits = query
            .iter()
            .filter(|word| author_tokens.contains(*word))
            .count();
        let size = query.len();
        let mut bonus = best_title * confidence;
        bonus += COVERAGE * (1.0f64).min((title_hits + author_hits) as f64 / size as f64);
        if (1..size).contains(&title_hits) && size <= title_hits + author_hits {
            bonus += COMBO;
        }
        if author_hits == size && title_hits < size {
            bonus += AUTHOR * confidence;
        }
        bonus
    }

    pub fn lookup(&self, query: &str) -> Lookup {
        let tokens = tokenize(query);
        let last = tokens.len().saturating_sub(1);
        let matches = tokens
            .iter()
            .enumerate()
            .map(|(position, token)| self.term_matches(token, position == last))
            .collect();
        Lookup { tokens, matches }
    }

    pub fn search(&self, query: &str, limit: usize) -> Vec<Book> {
        let ranked = self.ranked(&self.lookup(query), limit);
        ranked.into_iter().map(|(book, _)| book).collect()
    }

    pub fn ranked(&self, lookup: &Lookup, limit: usize) -> Vec<(Book, f64)> {
        if lookup.tokens.is_empty() {
            return Vec::new();
        }
        let per_token: Vec<TokenScores> = lookup
            .tokens
            .iter()
            .zip(&lookup.matches)
            .map(|(token, matches)| self.token_scores(token, matches))
            .collect();
        let scored = Self::candidates(&per_token);
        let mut popularity: HashMap<u32, f64> = HashMap::new();
        let mut popularity_of = |work: u32| {
            *popularity
                .entry(work)
                .or_insert_with(|| self.catalogue.popularity(work))
        };
        let mut keys: Vec<(u32, f64)> = scored
            .iter()
            .map(|(&work, &score)| (work, score + RANK_POPULARITY * popularity_of(work)))
            .collect();
        keys.sort_by_key(|&(work, _)| work);
        keys.sort_by(|a, b| b.1.partial_cmp(&a.1).expect("comparable"));
        let joined = lookup.tokens.join(" ");
        let mut ranked: Vec<(Book, f64)> = keys
            .iter()
            .take(RERANK_DEPTH)
            .filter_map(|&(work, _)| self.catalogue.book(work))
            .map(|book| {
                let matched = per_token
                    .iter()
                    .filter(|entry| entry.scores.contains_key(&book.work))
                    .count() as f64
                    / per_token.len() as f64;
                let boost = (RANK_POPULARITY + POPULARITY) * popularity_of(book.work) * matched;
                let penalty = if book.is_companion() {
                    COMPANION_PENALTY
                } else {
                    0.0
                };
                let score = scored[&book.work]
                    + self.bonus(&book, &per_token)
                    + boost
                    + self.series_bonus(&book, &joined)
                    - penalty;
                (book, score)
            })
            .collect();
        ranked.sort_by(|a, b| b.1.partial_cmp(&a.1).expect("comparable"));
        let ranked = Self::without_companions(ranked, &per_token);
        let best = ranked.first().map_or(0.0, |(_, score)| *score);
        ranked
            .into_iter()
            .filter(|(_, score)| *score >= RELEVANCE_FLOOR * best)
            .take(limit)
            .collect()
    }
}

pub struct Hit {
    pub work: u32,
    pub score: f64,
    pub books: Vec<Book>,
}

/// Searches every catalogue in parallel and merges books by unified id: best score,
/// best scoring book first.
pub fn search_all(
    catalogues: &[Catalogue],
    unified: impl Fn(u32) -> u32,
    query: &str,
    limit: usize,
) -> Vec<Hit> {
    let ranked: Vec<Vec<(Book, f64)>> = std::thread::scope(|scope| {
        let searches: Vec<_> = catalogues
            .iter()
            .map(|catalogue| {
                scope.spawn(move || {
                    let searcher = Searcher::new(catalogue);
                    searcher.ranked(&searcher.lookup(query), limit)
                })
            })
            .collect();
        let finished = searches.into_iter();
        finished
            .map(|search| search.join().expect("search"))
            .collect()
    });
    let mut merged: HashMap<u32, Hit> = HashMap::new();
    for (book, score) in ranked.into_iter().flatten() {
        let work = unified(book.work);
        let hit = merged.entry(work).or_insert(Hit {
            work,
            score,
            books: Vec::new(),
        });
        if score > hit.score {
            hit.score = score;
            hit.books.insert(0, book);
        } else {
            hit.books.push(book);
        }
    }
    let mut ranked: Vec<Hit> = merged.into_values().collect();
    ranked.sort_by(|a, b| b.score.total_cmp(&a.score).then(a.work.cmp(&b.work)));
    ranked
}

fn title_value(
    title: &str,
    subtitle: &str,
    query: &[String],
    joined_query: &str,
    completing: bool,
) -> f64 {
    let joined = tokenize(title).join(" ");
    let full = tokenize(&format!("{title} {subtitle}")).join(" ");
    let main = tokenize(main_title(title));
    let main_joined = main.join(" ");
    let titles = [without_article(&joined), without_article(&main_joined)];
    let value = if titles.contains(&without_article(joined_query)) && !completing {
        EXACT
    } else if joined.starts_with(joined_query) {
        PREFIX
    } else if format!(" {full} ").contains(&format!(" {joined_query} ")) {
        PHRASE
    } else {
        0.0
    };
    value - (LENGTH * main.len().saturating_sub(query.len()) as f64).min(0.3)
}

static COMPANION: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
    let pattern = [
        r"box(ed)? set|collection set|books? collection|[0-9] ?books? set|gift set|",
        r"books? bundle|\buntitled\b|",
        r"\bseries ?(box|set|collection)?\s*$|",
        r"\(series\)|\b[0-9]{1,2}\s*[-–]\s*[0-9]{1,2}\b|omnibus|slipcase|",
        r"complete (series|collection|novels|saga|works)|collected (works|novels)|",
        r"trilogy|tetralogy|trilogie|gesamtausgabe|gesamtwerk|sammelband|",
        r"coffret|int[ée]grale|estuche|obras completas|colecci[óo]n completa|",
        r"colou?ring book|activity book|sticker|annual [0-9]{4}|calendar|planner|",
        r"study guide|sparknotes|cliffs ?notes|summary of|analysis of|quiz|trivia|",
        r"unofficial|companion|movie storybook|the making of|selections from|",
        r"big book|adventure game|lesson plan|workbook|journal\s*$|notes\s*$|",
        r"\blevel [0-9]|[0-9] ?(paperback|hardcover)",
    ];
    regex::Regex::new(&format!("(?i){}", pattern.concat())).expect("companion pattern")
});

static OMNIBUS: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
    let articles = ["The|A|An", "Der|Die|Das", "Le|La|Les", "El|La|Los|Las"];
    let pattern = articles
        .map(|set| format!(r"^({set}) [^,&/;+]+( and| /|;) ({set}) \p{{Lu}}"))
        .join("|");
    regex::Regex::new(&pattern).expect("omnibus pattern")
});

pub fn is_companion(text: &str) -> bool {
    COMPANION.is_match(text)
}

pub fn is_omnibus(title: &str) -> bool {
    OMNIBUS.is_match(title)
}
