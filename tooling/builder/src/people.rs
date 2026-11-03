//! Contributor roles (translator, illustrator, editor, narrator) from library and Open
//! Library records, and author page data (`people` sidecar) from the Open Library authors.

use crate::dumps::year_of;
use crate::libraries::normalized_name;
use crate::sidecar::Person;
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;

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
    let text = text
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
    left.to_lowercase() == right.to_lowercase()
}

fn push_unique(found: &mut Vec<(&'static str, String)>, role: &'static str, name: &str) {
    let name = normalized_name(name);
    let known = found.iter().any(|(_, known)| same_name(known, &name));
    if !name.is_empty() && !known && found.len() < MAX_CONTRIBUTORS {
        found.push((role, name));
    }
}

static TRAILING_ROLE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^(.+?)\s*\(([^()]+)\)\s*$").expect("role pattern"));

/// `contributors` (`{role, name}`) and `contributions` (`Name (Role)`) of an edition.
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
                "Library of Congress (U.S.)", "Plain Name"]}"#,
        )
        .unwrap();
        let found = edition_contributors(&edition);
        assert_eq!(found[0], ("illustrator", "Mary GrandPré".to_string()));
        assert_eq!(found.last(), Some(&("narrator", "Stephen Fry".to_string())));
        assert_eq!(found.len(), 2);
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
