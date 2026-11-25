//! Contributor roles (translator, illustrator, editor, narrator) from library and Open
//! Library records, and author page data (`people` sidecar) from the Open Library authors.

use crate::dumps::year_of;
use crate::libraries::normalized_name;
use crate::sidecar::Person;
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;
use unicode_normalization::UnicodeNormalization;

/// A role and a name: `("illustrator", "Mary GrandPré")`.
pub type Credit = (&'static str, String);

pub const MAX_CONTRIBUTORS: usize = 8;
const MAX_NAMES: usize = 6;
const MAX_BIO_CHARS: usize = 1200;
const ROLES: [(&str, &[&str]); 4] = [
    (
        "translator",
        &[
            "trl", "tradu", "transl", "übers", "tõlk", "tłum", "přel", "kään", "tulk", "730",
        ],
    ),
    (
        "illustrator",
        &[
            "ill", "zeichn", "kuvit", "rysun", "dessin", "440", "ilustr", "teckn",
        ],
    ),
    (
        "editor",
        &[
            "edt", "edit", "ed.", "eds.", "hrsg", "herausg", "éd", "redak", "toim", "340", "red.",
            "edic",
        ],
    ),
    (
        "narrator",
        &[
            "nrt", "narrat", "sprecher", "lect", "lukija", "uppläs", "lektor", "čte", "lector",
        ],
    ),
];
const AUTHORITIES: [(&str, &str); 4] = [
    ("wikidata", "wikidata"),
    ("viaf", "viaf"),
    ("isni", "isni"),
    ("lc_naf", "lc"),
];

/// The role a relator term or code names: `ill`, `Illustrator`, `Übers.`, `traducción`.
pub fn role_of(text: &str) -> Option<&'static str> {
    let composed: String = text.nfc().collect();
    let text = composed
        .trim()
        .trim_matches(['(', ')', '[', ']', ',', ' '])
        .to_lowercase();
    let starts = |prefixes: &[&str]| prefixes.iter().any(|known| text.starts_with(known));
    ROLES
        .iter()
        .find(|(_, prefixes)| starts(prefixes))
        .map(|(role, _)| *role)
}

pub fn same_name(left: &str, right: &str) -> bool {
    let key = |name: &str| name.to_lowercase().replace('-', " ");
    key(left) == key(right)
}

pub fn push_unique(found: &mut Vec<Credit>, role: &'static str, name: &str) {
    let name = normalized_name(name);
    let known = found.iter().any(|(_, known)| same_name(known, &name));
    if !name.is_empty() && !known && found.len() < MAX_CONTRIBUTORS {
        found.push((role, name));
    }
}

pub fn translators(credits: &[Credit]) -> Vec<String> {
    let translating = credits.iter().filter(|(role, _)| *role == "translator");
    translating.map(|(_, name)| name.clone()).collect()
}

const LANGUAGE_WORD: &str = r"[\p{L}.]+";
const STATEMENT_CUES: [(&str, &[&str]); 4] = [
    (
        "translator",
        &[
            r"aus dem {L}(?: übersetzt| übertragen)? von",
            r"übersetzt(?: aus dem {L})? von",
            r"(?:übersetzung|übers\.)(?: aus dem {L})?(?: von|:)",
            r"deutsch von",
            r"translat(?:ed|ion)(?: from(?: the)? {L})? by",
            r"(?:svensk )?översättning(?: från {L})?:?",
            r"översatt(?: från {L})? av",
            r"suomentanut|suomentaneet|suomennos:?",
        ],
    ),
    (
        "illustrator",
        &[
            r"ill\.(?: von|:)?",
            r"illustr(?:iert|ationen|ated|ations|erad|ationer)(?: von| by| av|:)?",
            r"mit (?:farbigen |vielen |zahlreichen )?(?:bildern|zeichnungen) von",
            r"zeichnungen von|bilder von",
            r"bilder(?: av|:)|(?:pictures|drawings) by|kuvittanut|kuvitus:?",
        ],
    ),
    (
        "editor",
        &[
            r"herausgegeben von|hrsg\. von|edited by",
            r"redigerad av|toimittanut|toimittaneet",
        ],
    ),
    (
        "narrator",
        &[r"gelesen von|(?:read|narrated) by|uppläsare:?|lukija:?"],
    ),
];
const SUFFIX_CUES: [(&str, &str); 6] = [
    ("translator", "옮김"),
    ("translator", "번역"),
    ("translator", "역"),
    ("illustrator", "그림"),
    ("editor", "엮음"),
    ("editor", "편"),
];
const NAME_JOINS: [&str; 6] = [" und ", " u. ", " and ", " och ", " ja ", " & "];
const MAX_NAME_WORDS: usize = 4;

static CUES: LazyLock<Vec<(&str, Regex)>> = LazyLock::new(|| {
    let compiled = STATEMENT_CUES.iter().map(|(role, cues)| {
        let cues = cues.join("|").replace("{L}", LANGUAGE_WORD);
        let pattern = format!(r"(?i)(?:^|[^\p{{L}}])(?:{cues})\s*");
        (*role, Regex::new(&pattern).expect("statement cue pattern"))
    });
    compiled.collect()
});

fn first_sentence(text: &str) -> &str {
    let mut ends = text.match_indices(". ").map(|(index, _)| index);
    let sentence_end = ends.find(|&index| {
        let word = text[..index].rsplit(' ').next().unwrap_or_default();
        word.chars().count() > 1
    });
    &text[..sentence_end.unwrap_or(text.len())]
}

const NAME_PARTICLES: [&str; 12] = [
    "von", "van", "der", "den", "de", "du", "da", "di", "la", "le", "zu", "ten",
];

fn name_words(text: &str) -> String {
    let mut words = text.split_whitespace();
    let first = words.next().into_iter();
    let lowercase = |word: &&str| word.chars().next().is_some_and(char::is_lowercase);
    let rest = words.take_while(|word| !lowercase(word) || NAME_PARTICLES.contains(word));
    first.chain(rest).collect::<Vec<_>>().join(" ")
}

fn push_names(found: &mut Vec<Credit>, role: &'static str, text: &str) {
    let mut names = vec![text];
    for join in NAME_JOINS {
        names = names.iter().flat_map(|name| name.split(join)).collect();
    }
    for name in names.into_iter().map(name_words) {
        let name = name.as_str();
        let capitalized = name
            .chars()
            .next()
            .is_some_and(|first| !first.is_lowercase());
        if capitalized && name.split_whitespace().count() <= MAX_NAME_WORDS {
            push_unique(found, role, name);
        }
    }
}

/// Translators, illustrators, editors and narrators a statement of responsibility names:
/// `Jeff Kinney ; aus dem Englischen von Collin McMahon`, `한강 지음 ; 데보라 스미스 옮김`.
pub fn statement_credits(statement: &str) -> Vec<Credit> {
    let statement: &str = &statement.nfc().collect::<String>();
    let mut found = Vec::new();
    for (role, cue) in CUES.iter() {
        for matched in cue.find_iter(statement) {
            let rest = &statement[matched.end()..];
            let end = rest
                .find([';', '[', ']', '/', ',', '(', '='])
                .unwrap_or(rest.len());
            push_names(&mut found, role, first_sentence(&rest[..end]));
        }
    }
    for clause in statement.split([';', ',']).map(str::trim) {
        let suffixed = SUFFIX_CUES.iter().find_map(|(role, cue)| {
            let name = clause.strip_suffix(cue)?.strip_suffix(' ')?;
            Some((*role, name))
        });
        if let Some((role, name)) = suffixed {
            push_names(&mut found, role, name);
        }
    }
    found
}

static TRAILING_ROLE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(.+?)\s*\(([^()]+)\)\s*$").expect("role pattern"));

/// `contributors` (`{role, name}`), `contributions` (`Name (Role)`), `translated_by` and
/// the credits of `by_statement` of an edition.
pub fn edition_contributors(edition: &Value) -> Vec<(&'static str, String)> {
    let mut found = Vec::new();
    let listed = edition.get("contributors").and_then(Value::as_array);
    for entry in listed.into_iter().flatten() {
        let field = |name: &str| entry.get(name).and_then(Value::as_str).unwrap_or_default();
        if let Some(role) = role_of(field("role")) {
            push_unique(&mut found, role, field("name"));
        }
    }
    let credited = edition.get("contributions").and_then(Value::as_array);
    for text in credited.into_iter().flatten().filter_map(Value::as_str) {
        let Some(parts) = TRAILING_ROLE.captures(text) else {
            continue;
        };
        if let Some(role) = role_of(&parts[2]) {
            push_unique(&mut found, role, &parts[1]);
        }
    }
    let translating = edition.get("translated_by").and_then(Value::as_array);
    for name in translating.into_iter().flatten().filter_map(Value::as_str) {
        push_unique(&mut found, "translator", name);
    }
    let statement = edition.get("by_statement").and_then(Value::as_str);
    for (role, name) in statement.map(statement_credits).unwrap_or_default() {
        push_unique(&mut found, role, &name);
    }
    found
}

static MARKDOWN_LINK: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\[([^\]]*)\]\([^)]*\)").expect("link pattern"));
static REFERENCE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?m)\(\s*\[[^\]]*\]\[[^\]]*\]\s*\)|\[([^\]]*)\]\[[^\]]*\]|^\s*\[\d+\]:.*$")
        .expect("reference pattern")
});

/// Bio text without Markdown links and source references, cut after a sentence.
pub fn clean_bio(text: &str) -> String {
    let linked = MARKDOWN_LINK.replace_all(text, "$1");
    let plain = REFERENCE.replace_all(&linked, "$1");
    let words: Vec<&str> = plain.split_whitespace().collect();
    let joined = words.join(" ");
    if joined.chars().count() <= MAX_BIO_CHARS {
        return joined;
    }
    let cut: String = joined.chars().take(MAX_BIO_CHARS).collect();
    match cut.rfind(". ") {
        Some(end) => cut[..=end].to_string(),
        None => format!("{}…", cut.trim_end()),
    }
}

fn bio_of(author: &Value) -> String {
    let bio = author.get("bio");
    let text = bio.and_then(|bio| bio.get("value").or(Some(bio)));
    clean_bio(text.and_then(Value::as_str).unwrap_or_default())
}

/// Death year, bio, other spellings and authority ids of an Open Library author record.
pub fn person_from(number: u32, author: &Value, name: &str) -> Option<Person> {
    let field = |key: &str| author.get(key).and_then(Value::as_str).unwrap_or_default();
    let alternates = author.get("alternate_names").and_then(Value::as_array);
    let mut names: Vec<String> = Vec::new();
    for alternate in alternates.into_iter().flatten().filter_map(Value::as_str) {
        let alternate = normalized_name(alternate);
        let fresh = alternate != name && !names.contains(&alternate);
        if !alternate.is_empty() && fresh && names.len() < MAX_NAMES {
            names.push(alternate);
        }
    }
    let remote = author.get("remote_ids");
    let identifiers = AUTHORITIES.iter().filter_map(|(key, scheme)| {
        let value = remote?.get(*key)?.as_str()?.trim();
        (!value.is_empty()).then(|| format!("{scheme}:{value}"))
    });
    let person = Person {
        number,
        died: year_of(field("death_date")),
        bio: bio_of(author),
        names,
        identifiers: identifiers.collect(),
    };
    let empty = person.died == 0
        && person.bio.is_empty()
        && person.names.is_empty()
        && person.identifiers.is_empty();
    (!empty).then_some(person)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statements_credit_translators_illustrators_and_editors() {
        let credit = |role: &'static str, name: &str| (role, name.to_string());
        for (statement, expected) in [
            (
                "Jeff Kinney ; aus dem Englischen von Collin McMahon",
                vec![credit("translator", "Collin McMahon")],
            ),
            (
                "T. Ferriss. Aus dem Engl. von Heike Schlatterer und Henning Dedekind",
                vec![
                    credit("translator", "Heike Schlatterer"),
                    credit("translator", "Henning Dedekind"),
                ],
            ),
            (
                "Hrsg. von Agnes Husslein. [U\u{308}bers. aus dem Engl.: Uta Hasekamp]",
                vec![
                    credit("translator", "Uta Hasekamp"),
                    credit("editor", "Agnes Husslein"),
                ],
            ),
            (
                "Astrid Lindgren; translated by Edna Hurup, illustrated by Tony Ross",
                vec![
                    credit("translator", "Edna Hurup"),
                    credit("illustrator", "Tony Ross"),
                ],
            ),
            (
                "John Grisham ; översättning: Kjell Waltman",
                vec![credit("translator", "Kjell Waltman")],
            ),
            (
                "Siegfried Lenz. [Ill. von J. K. Hellmessen]",
                vec![credit("illustrator", "J. K. Hellmessen")],
            ),
            (
                "한강 지음 ; 데보라 스미스 옮김",
                vec![credit("translator", "데보라 스미스")],
            ),
            ("Charles Dickens", vec![]),
            (
                "Saint-Exupéry. Übers. von Hans Reisiger neu bearb. von Annette-Lallemand",
                vec![credit("translator", "Hans Reisiger")],
            ),
            (
                "Ill. von Ursula von der Leyen",
                vec![credit("illustrator", "Ursula von der Leyen")],
            ),
            ("[Märchenspiel in 3 Bildern von Adelheid Wette]", vec![]),
            (
                "hrsg. von d. Internationalen Lenau-Gesellschaft in Zsarb. mit d. F.",
                vec![],
            ),
        ] {
            assert_eq!(statement_credits(statement), expected, "{statement}");
        }
        assert_eq!(
            role_of("ka\u{308}a\u{308}nta\u{308}ja\u{308}"),
            Some("translator")
        );
    }

    #[test]
    fn roles_from_codes_and_terms() {
        assert_eq!(role_of("ill"), Some("illustrator"));
        assert_eq!(role_of("Illustrator"), Some("illustrator"));
        assert_eq!(role_of("Übers."), Some("translator"));
        assert_eq!(role_of("traducción"), Some("translator"));
        assert_eq!(role_of("Hrsg."), Some("editor"));
        assert_eq!(role_of("Narrator/Reader"), Some("narrator"));
        assert_eq!(role_of("Library of Congress"), None);
        assert_eq!(role_of("Foreword"), None);
    }

    #[test]
    fn open_library_contributors() {
        let edition: Value = serde_json::from_str(
            r#"{"contributors": [{"role": "Illustrator", "name": "Mary GrandPré"},
                {"role": "Cover art", "name": "Someone"}],
                "contributions": ["GrandPré, Mary (Illustrator)", "Fry, Stephen (Narrator)",
                "Library of Congress (U.S.)", "Plain Name"],
                "translated_by": ["Ralph Manheim"],
                "by_statement": "Günter Grass ; translated by Ralph Manheim ; edited by Ann Lee"}"#,
        )
        .unwrap();
        let found = edition_contributors(&edition);
        assert_eq!(found[0], ("illustrator", "Mary GrandPré".to_string()));
        assert_eq!(found[1], ("narrator", "Stephen Fry".to_string()));
        assert_eq!(found[2], ("translator", "Ralph Manheim".to_string()));
        assert_eq!(found.last(), Some(&("editor", "Ann Lee".to_string())));
        assert_eq!(found.len(), 4);
        assert!(same_name("Mary Grandpré", "Mary GrandPré"));
    }

    #[test]
    fn bio_loses_markdown_and_sources() {
        let text = "Born in [Yate](https://en.wikipedia.org/wiki/Yate), she wrote.\r\n\r\n\
                    ([Source][1])\r\n\r\n[1]: https://en.wikipedia.org/wiki/JKR";
        assert_eq!(clean_bio(text), "Born in Yate, she wrote.");
        let long = "A sentence. ".repeat(200);
        assert!(clean_bio(&long).ends_with("sentence."));
        assert!(clean_bio(&long).chars().count() <= MAX_BIO_CHARS);
    }

    #[test]
    fn person_keeps_page_data_only() {
        let author: Value = serde_json::from_str(
            r#"{"name": "J. K. Rowling", "death_date": "", "alternate_names":
                ["Joanne Rowling", "J. K. Rowling", "Joanne Rowling"],
                "remote_ids": {"viaf": "116796842", "wikidata": "Q34660", "goodreads": "1"},
                "bio": {"type": "/type/text", "value": "British author."}}"#,
        )
        .unwrap();
        let person = person_from(23_919, &author, "J. K. Rowling").unwrap();
        assert_eq!(person.names, ["Joanne Rowling"]);
        assert_eq!(person.identifiers, ["wikidata:Q34660", "viaf:116796842"]);
        assert_eq!(person.bio, "British author.");
        let bare: Value = serde_json::from_str(r#"{"name": "X Y"}"#).unwrap();
        assert_eq!(person_from(1, &bare, "X Y"), None);
    }
}
