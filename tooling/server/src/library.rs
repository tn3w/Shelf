//! One loaded catalogue release: every language's current chains, ranks, the unified
//! works of `works.bin`, the `extras.bin` sidecar and the `covers.bin` index.

use builder::LANGUAGES;
use builder::covers::{self, Cover};
use builder::reader::{Book, Catalogue, Description, Ranks, Segment, current_segments};
use builder::search::{Hit, search_all};
use builder::sidecar::{EDITION_CAP, EXTRAS, Extras, Sidecar};
use builder::works::Works;
use std::collections::{HashMap, HashSet};
use std::path::Path;

const POPULAR: usize = 2500;
const FORM_WEIGHT: f64 = 0.25;
const SIMILARITY_SHARE: f64 = 0.7;
const MIN_SIMILARITY: f64 = 0.2;
const DAILY_CANDIDATES: usize = 20;
const ISBN_BASE: u64 = 978_000_000_000;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Member {
    pub language: usize,
    pub work: u32,
}

pub enum Found {
    Members(Vec<Member>),
    Moved(u32),
}

pub enum Choice {
    Member(Member),
    Moved(u32),
    Missing,
}

struct AuthorEntry {
    number: u32,
    language: u16,
    segment: u16,
    slot: u32,
}

pub struct Author {
    pub name: String,
    pub born: u32,
    pub works: Vec<Member>,
}

pub struct Series {
    pub name: String,
    pub first: u32,
    pub members: Vec<Member>,
}

pub struct Library {
    pub release: String,
    pub catalogues: Vec<Catalogue>,
    members: HashMap<u32, Vec<Member>>,
    unified: HashMap<u32, u32>,
    extras: Option<Sidecar>,
    covers: HashMap<String, Vec<Cover>>,
    authors: Vec<AuthorEntry>,
    popular: Vec<Vec<(u32, Vec<u8>)>>,
    weights: Vec<Vec<f64>>,
    pub work_count: usize,
    pub author_count: usize,
}

fn fill(mut merged: Extras, code: &str, extras: Extras) -> Extras {
    let add_pairs = |target: &mut Vec<(String, String)>, found: Vec<(String, String)>| {
        for (language, text) in found {
            if !target.iter().any(|(known, _)| *known == language) {
                target.push((language, text));
            }
        }
    };
    add_pairs(&mut merged.descriptions, extras.descriptions);
    add_pairs(&mut merged.titles, extras.titles);
    for identifier in extras.identifiers {
        if !merged.identifiers.contains(&identifier) {
            merged.identifiers.push(identifier);
        }
    }
    let language = language_name(code);
    for name in extras.translators {
        if !merged.translators.iter().any(|known| known.starts_with(&name)) {
            merged.translators.push(format!("{name} ({language})"));
        }
    }
    if merged.original_title.is_empty() {
        merged.original_title = extras.original_title;
    }
    let room = EDITION_CAP.saturating_sub(merged.editions.len());
    merged.editions.extend(extras.editions.into_iter().take(room));
    merged
}

pub fn language_name(code: &str) -> &'static str {
    match code {
        "en" => "English",
        "de" => "German",
        "fr" => "French",
        "es" => "Spanish",
        "et" => "Estonian",
        "sv" => "Swedish",
        "pl" => "Polish",
        "cs" => "Czech",
        "ko" => "Korean",
        "fi" => "Finnish",
        "ja" => "Japanese",
        "lv" => "Latvian",
        "lt" => "Lithuanian",
        "it" => "Italian",
        "nl" => "Dutch",
        "pt" => "Portuguese",
        "ru" => "Russian",
        "zh" => "Chinese",
        _ => "Other",
    }
}

/// The ISBN key segments use: the first 12 digits of the ISBN-13 minus 978000000000.
pub fn isbn_key(text: &str) -> Option<u32> {
    let digits: Vec<u32> = text
        .chars()
        .filter(|character| !matches!(character, '-' | ' '))
        .map(|character| match character {
            'X' | 'x' => Some(10),
            _ => character.to_digit(10),
        })
        .collect::<Option<_>>()?;
    let twelve = match digits.len() {
        10 => {
            let sum: u32 = digits.iter().zip((1..=10).rev()).map(|(d, w)| d * w).sum();
            if !sum.is_multiple_of(11) || digits[..9].contains(&10) {
                return None;
            }
            [&[9, 7, 8][..], &digits[..9]].concat()
        }
        13 => {
            let sum: u32 =
                digits.iter().zip([1, 3].iter().cycle()).map(|(d, w)| d * w).sum();
            if !sum.is_multiple_of(10) || digits.contains(&10) {
                return None;
            }
            digits[..12].to_vec()
        }
        _ => return None,
    };
    let number =
        twelve.iter().fold(0u64, |number, &digit| number * 10 + u64::from(digit));
    u32::try_from(number.checked_sub(ISBN_BASE)?).ok()
}

fn read_manifest(directory: &Path) -> Result<serde_json::Value, String> {
    let path = directory.join("manifest.json");
    let bytes =
        std::fs::read(&path).map_err(|error| format!("{}: {error}", path.display()))?;
    serde_json::from_slice(&bytes).map_err(|error| format!("manifest: {error}"))
}

/// `(language, pack, id)` of every manifest entry.
pub fn manifest_entries(manifest: &serde_json::Value) -> Vec<(String, String, String)> {
    let entries = manifest["segments"].as_array().into_iter().flatten();
    entries
        .filter_map(|entry| {
            let text = |name: &str| entry[name].as_str().map(str::to_string);
            Some((text("language")?, text("pack")?, text("id")?))
        })
        .collect()
}

fn open_catalogue(
    directory: &Path,
    code: &str,
    entries: &[(String, String, String)],
) -> Result<Catalogue, String> {
    let mut segments = Vec::new();
    let mut ranks = None;
    for (_, pack, id) in entries.iter().filter(|(language, ..)| language == code) {
        let path = directory.join(format!("{id}.bin"));
        let broken = || format!("{} is not a segment", path.display());
        match pack.as_str() {
            "ranks" => ranks = Some(Ranks::open(&path).ok_or_else(broken)?),
            _ => segments.push(Segment::open(&path).ok_or_else(broken)?),
        }
    }
    Ok(Catalogue::new(code, current_segments(segments), ranks))
}

fn author_entries(language: usize, catalogue: &Catalogue) -> Vec<AuthorEntry> {
    let segments = catalogue.segments.iter().enumerate();
    segments
        .flat_map(|(segment, source)| {
            (0..source.author_count as u32).map(move |slot| AuthorEntry {
                number: source.author(slot).number,
                language: language as u16,
                segment: segment as u16,
                slot,
            })
        })
        .collect()
}

/// The most read works with their tags, the pool similar books come from.
fn popular(catalogue: &Catalogue) -> Vec<(u32, Vec<u8>)> {
    let mut works: Vec<(f64, u32)> =
        catalogue.works().iter().map(|&work| (catalogue.score(work), work)).collect();
    works.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
    let tagged = works.into_iter().take(POPULAR).map(|(_, work)| {
        let tags = catalogue.facts(work).map(|facts| facts.tags).unwrap_or_default();
        (work, tags)
    });
    tagged.collect()
}

/// Rarity of each tag, broad form and audience tags at a quarter.
fn tag_weights(catalogue: &Catalogue) -> Vec<f64> {
    let Some(tags) =
        catalogue.segments.iter().map(|segment| &segment.tags).max_by_key(|t| t.len())
    else {
        return Vec::new();
    };
    let works = f64::from(catalogue.work_count());
    let weight = |tag: &builder::reader::Tag| {
        let count: u32 = catalogue.segments.iter().map(|s| s.tag_count(tag.id)).sum();
        let rarity = (1.0 + works / f64::from(count.max(1))).ln();
        match tag.category.as_str() {
            "form" | "audience" => rarity * FORM_WEIGHT,
            _ => rarity,
        }
    };
    tags.iter().map(weight).collect()
}

fn covers_of(directory: &Path) -> HashMap<String, Vec<Cover>> {
    let Ok(bytes) = std::fs::read(directory.join("covers.bin")) else {
        return HashMap::new();
    };
    let sections = covers::sections_of(covers::MAGIC, covers::VERSION, &bytes);
    let languages = sections.into_iter().flatten().filter(|(name, _)| name != "meta");
    languages
        .filter_map(|(name, section)| Some((name, covers::decode(&section)?)))
        .collect()
}

struct Loaded {
    catalogue: Catalogue,
    authors: Vec<AuthorEntry>,
    popular: Vec<(u32, Vec<u8>)>,
    weights: Vec<f64>,
}

impl Library {
    pub fn open(directory: &Path) -> Result<Library, String> {
        let manifest = read_manifest(directory)?;
        let release =
            manifest["month"].as_str().ok_or("manifest without month")?.to_string();
        let entries = manifest_entries(&manifest);
        let codes: Vec<&str> = LANGUAGES
            .into_iter()
            .filter(|code| entries.iter().any(|(language, ..)| language == code))
            .collect();
        let loaded: Result<Vec<Loaded>, String> = std::thread::scope(|scope| {
            let threads: Vec<_> = codes
                .iter()
                .enumerate()
                .map(|(language, code)| {
                    let entries = &entries;
                    scope.spawn(move || {
                        let catalogue = open_catalogue(directory, code, entries)?;
                        Ok(Loaded {
                            authors: author_entries(language, &catalogue),
                            popular: popular(&catalogue),
                            weights: tag_weights(&catalogue),
                            catalogue,
                        })
                    })
                })
                .collect();
            threads
                .into_iter()
                .map(|thread| thread.join().expect("open catalogue"))
                .collect()
        });
        let works = Works::load(&directory.join("works.bin")).unwrap_or_default();
        let extras = std::fs::read(directory.join("extras.bin"))
            .ok()
            .and_then(|bytes| Sidecar::parse(EXTRAS, &bytes));
        let mut library = Library {
            release,
            catalogues: Vec::new(),
            members: HashMap::new(),
            unified: works.unified_ids(),
            extras,
            covers: covers_of(directory),
            authors: Vec::new(),
            popular: Vec::new(),
            weights: Vec::new(),
            work_count: 0,
            author_count: 0,
        };
        for each in loaded? {
            library.catalogues.push(each.catalogue);
            library.authors.extend(each.authors);
            library.popular.push(each.popular);
            library.weights.push(each.weights);
        }
        library.authors.sort_unstable_by_key(|entry| (entry.number, entry.language));
        let mut numbers: Vec<u32> =
            library.authors.iter().map(|entry| entry.number).collect();
        numbers.dedup();
        library.author_count = numbers.len();
        for (unified, members) in works.works {
            let members = members.into_iter().filter_map(|(language, work)| {
                let code = works.languages.get(language as usize)?;
                Some(Member { language: library.language(code)?, work })
            });
            library.members.insert(unified, members.collect());
        }
        let total: usize =
            library.catalogues.iter().map(|c| c.work_count() as usize).sum();
        let merged: usize =
            library.members.values().map(|m| m.len().saturating_sub(1)).sum();
        library.work_count = total.saturating_sub(merged);
        Ok(library)
    }

    pub fn language(&self, code: &str) -> Option<usize> {
        self.catalogues.iter().position(|catalogue| catalogue.language == code)
    }

    pub fn code(&self, member: Member) -> &str {
        &self.catalogues[member.language].language
    }

    pub fn unified_of(&self, work: u32) -> u32 {
        self.unified.get(&work).copied().unwrap_or(work)
    }

    fn holds(&self, member: Member) -> bool {
        let catalogue = &self.catalogues[member.language];
        let located = catalogue.locate(member.work);
        located.is_some_and(|(segment, local)| {
            catalogue.segments[segment].work(local) == member.work
        })
    }

    fn score(&self, member: Member) -> f64 {
        self.catalogues[member.language].score(member.work)
    }

    /// Raw rank scores: a book leads in the catalogue where most people read it.
    fn by_popularity(&self, members: &mut [Member]) {
        members.sort_by(|a, b| {
            self.score(*b).total_cmp(&self.score(*a)).then(a.language.cmp(&b.language))
        });
    }

    /// The members of a unified work, most popular first, or where an old id moved to.
    pub fn find(&self, id: u32) -> Option<Found> {
        let unified = self.unified_of(id);
        if unified != id {
            return Some(Found::Moved(unified));
        }
        let mut members: Vec<Member> = match self.members.get(&id) {
            Some(members) => members.clone(),
            None => (0..self.catalogues.len())
                .map(|language| Member { language, work: id })
                .collect(),
        };
        members.retain(|&member| self.holds(member));
        if !members.is_empty() {
            self.by_popularity(&mut members);
            return Some(Found::Members(members));
        }
        let moved = self.catalogues.iter().find_map(|catalogue| catalogue.alias(id));
        let moved = moved.filter(|&work| work != id)?;
        Some(Found::Moved(self.unified_of(moved)))
    }

    /// The member in language `code`, else the most popular one.
    pub fn choose(&self, id: u32, code: Option<&str>) -> Choice {
        let members = match self.find(id) {
            Some(Found::Members(members)) => members,
            Some(Found::Moved(to)) => return Choice::Moved(to),
            None => return Choice::Missing,
        };
        let wanted =
            |member: &&Member| code.is_none_or(|code| self.code(**member) == code);
        members
            .iter()
            .find(wanted)
            .map_or(Choice::Missing, |&member| Choice::Member(member))
    }

    pub fn members(&self, id: u32) -> Vec<Member> {
        match self.find(id) {
            Some(Found::Members(members)) => members,
            _ => Vec::new(),
        }
    }

    pub fn book(&self, member: Member) -> Option<Book> {
        self.catalogues[member.language].book(member.work)
    }

    pub fn description(&self, member: Member) -> Option<Description> {
        self.catalogues[member.language].description(member.work)
    }

    pub fn extras(&self, member: Member) -> Option<Extras> {
        self.extras.as_ref()?.extras_of(self.code(member), member.work)
    }

    /// Extras of `member`, gaps filled from the other members of unified work `id`.
    pub fn work_extras(&self, id: u32, member: Member) -> Extras {
        let own = self.extras(member).unwrap_or_default();
        let others = self.members(id).into_iter().filter(|&other| other != member);
        others
            .filter_map(|other| Some((self.code(other), self.extras(other)?)))
            .fold(own, |merged, (code, extras)| fill(merged, code, extras))
    }

    pub fn tags(&self, member: Member, book: &Book) -> Vec<(String, String)> {
        let catalogue = &self.catalogues[member.language];
        let Some((segment, _)) = catalogue.locate(member.work) else {
            return Vec::new();
        };
        let tags = &catalogue.segments[segment].tags;
        let found = book.tags.iter().filter_map(|&tag| tags.get(tag as usize));
        found.map(|tag| (tag.slug.clone(), tag.label.clone())).collect()
    }

    pub fn cover(&self, code: &str, work: u32) -> Option<Cover> {
        let covers = self.covers.get(code)?;
        let found = covers.binary_search_by_key(&work, |cover| cover.work).ok()?;
        Some(covers[found])
    }

    pub fn series(&self, member: Member) -> Option<Series> {
        let found = self.catalogues[member.language].series(member.work)?;
        let members =
            found.members.iter().map(|&work| Member { language: member.language, work });
        let members: Vec<Member> = members.filter(|&member| self.holds(member)).collect();
        let first = self.unified_of(members.first()?.work);
        Some(Series { name: found.name, first, members })
    }

    pub fn author(&self, number: u32) -> Option<Author> {
        let start = self.authors.partition_point(|entry| entry.number < number);
        let entries =
            self.authors[start..].iter().take_while(|entry| entry.number == number);
        let mut author = Author { name: String::new(), born: 0, works: Vec::new() };
        for entry in entries {
            let catalogue = &self.catalogues[entry.language as usize];
            let record = catalogue.segments[entry.segment as usize].author(entry.slot);
            if author.name.is_empty() {
                author.name = record.name;
            }
            author.born = author.born.max(record.born);
            let works = catalogue.visible_works(entry.segment as usize, &record.works);
            let language = entry.language as usize;
            author.works.extend(works.into_iter().map(|work| Member { language, work }));
        }
        if author.works.is_empty() {
            return None;
        }
        self.by_popularity(&mut author.works);
        let mut seen = HashSet::new();
        author.works.retain(|member| seen.insert(self.unified_of(member.work)));
        Some(author)
    }

    pub fn search(&self, query: &str, depth: usize) -> Vec<Hit> {
        search_all(&self.catalogues, |work| self.unified_of(work), query, depth)
    }

    pub fn isbn(&self, key: u32) -> Option<Member> {
        self.catalogues.iter().enumerate().find_map(|(language, catalogue)| {
            let book = catalogue.book_of_isbn(key)?;
            Some(Member { language, work: book.work })
        })
    }

    pub fn popular(&self, language: usize) -> Vec<u32> {
        self.popular[language].iter().map(|(work, _)| *work).collect()
    }

    /// Popular books with covers, a different dozen every day.
    pub fn daily(&self, day: u64, count: usize) -> Vec<Member> {
        let mut seen = HashSet::new();
        let mut candidates = Vec::new();
        for rank in 0..DAILY_CANDIDATES * 12 {
            for (language, works) in self.popular.iter().enumerate() {
                let Some(&(work, _)) = works.get(rank) else { continue };
                let member = Member { language, work };
                let covered = self.cover(self.code(member), work).is_some();
                if covered && seen.insert(self.unified_of(work)) {
                    candidates.push(member);
                }
            }
            if candidates.len() >= DAILY_CANDIDATES * count {
                break;
            }
        }
        if candidates.is_empty() {
            return candidates;
        }
        let start = (day as usize * count) % candidates.len();
        candidates
            .iter()
            .cycle()
            .skip(start)
            .take(count.min(candidates.len()))
            .copied()
            .collect()
    }

    fn vector(&self, language: usize, tags: &[u8]) -> HashMap<u8, f64> {
        let weight = |tag: u8| self.weights[language].get(tag as usize).copied();
        tags.iter().filter_map(|&tag| Some((tag, weight(tag)?))).collect()
    }

    /// Most read books of the same language ranked by weighted tag cosine and
    /// popularity; one per author, not the source's author, no companions or repeats.
    pub fn similar(&self, member: Member, excluded: &[u32], limit: usize) -> Vec<Member> {
        let Some(book) = self.book(member) else { return Vec::new() };
        let language = member.language;
        let catalogue = &self.catalogues[language];
        let own = self.vector(language, &book.tags);
        let norm = |vector: &HashMap<u8, f64>| {
            vector.values().map(|w| w * w).sum::<f64>().sqrt()
        };
        let own_norm = norm(&own);
        let mut scored: Vec<(f64, u32)> = self.popular[language]
            .iter()
            .filter(|(work, _)| *work != member.work)
            .filter_map(|(work, tags)| {
                let theirs = self.vector(language, tags);
                let common: f64 =
                    theirs.keys().filter_map(|tag| own.get(tag)).map(|w| w * w).sum();
                let similarity = common / (own_norm * norm(&theirs)).max(f64::EPSILON);
                let popularity = catalogue.popularity(*work);
                let score =
                    SIMILARITY_SHARE * similarity + (1.0 - SIMILARITY_SHARE) * popularity;
                (similarity >= MIN_SIMILARITY).then_some((score, *work))
            })
            .collect();
        scored.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
        let mut authors: HashSet<u32> = book.authors.iter().map(|a| a.number).collect();
        let mut titles = HashSet::from([book.title.to_lowercase()]);
        let fresh = scored
            .into_iter()
            .filter_map(|(_, work)| catalogue.book(work))
            .filter(|other| {
                let author = other.authors.first().map_or(0, |author| author.number);
                !other.is_companion()
                    && !excluded.contains(&self.unified_of(other.work))
                    && authors.insert(author)
                    && titles.insert(other.title.to_lowercase())
            });
        fresh.take(limit).map(|other| Member { language, work: other.work }).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn extras(language: &str, translator: &str, identifier: &str) -> Extras {
        Extras {
            descriptions: vec![(language.to_string(), format!("{language} text"))],
            identifiers: vec![identifier.to_string()],
            translators: vec![translator.to_string()],
            original_title: "Harry Potter and the Philosopher's Stone".to_string(),
            editions: vec![Default::default(); 2],
            ..Extras::default()
        }
    }

    #[test]
    fn member_without_extras_takes_the_other_members() {
        let merged =
            fill(Extras::default(), "fr", extras("fr", "Jean-François Ménard", "pg:1"));
        let merged = fill(merged, "es", extras("es", "Alicia Dellepiane", "pg:1"));
        assert_eq!(merged.original_title, "Harry Potter and the Philosopher's Stone");
        assert_eq!(merged.descriptions.len(), 2);
        assert_eq!(merged.identifiers, ["pg:1"]);
        assert_eq!(
            merged.translators,
            ["Jean-François Ménard (French)", "Alicia Dellepiane (Spanish)"]
        );
        assert_eq!(merged.editions.len(), 4);
    }

    #[test]
    fn own_extras_come_first() {
        let own = extras("de", "Klaus Fritz", "ol:OL1W");
        let merged = fill(own, "fr", extras("de", "Klaus Fritz", "ol:OL1W"));
        assert_eq!(merged.descriptions, [("de".to_string(), "de text".to_string())]);
        assert_eq!(merged.translators, ["Klaus Fritz"]);
    }
}
