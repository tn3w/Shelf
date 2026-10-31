use crate::catalog;
use crate::dumps;
use crate::libraries::{
    EBOOK, HARDCOVER, MAX_VOLUME, PAPERBACK, Record, Source, author_name, clean, first_number,
};
use crate::release;
use crate::tags::Classes;
use serde_json::Value;
use std::collections::HashMap;

pub const NLK_SEPARATOR: &[u8] = b"\n  }, {";
const LIBRIS_SKIPPED: [&str; 6] = [
    "ljudböcker",
    "talböcker",
    "läromedel",
    "videoupptagningar",
    "avhandlingar",
    "offentligt tryck",
];
const LIBRIS_SUBJECTS: [(&str, &str); 21] = [
    ("skönlitteratur", "fiction"),
    ("romaner", "fiction"),
    ("noveller", "fiction / short stories"),
    ("deckare", "fiction / mystery & detective"),
    ("kriminal", "fiction / mystery & detective"),
    ("thriller", "fiction / thrillers"),
    ("spänning", "fiction / thrillers"),
    ("fantasy", "fiction / fantasy"),
    ("science fiction", "fiction / science fiction"),
    ("kärleks", "fiction / romance"),
    ("historiska romaner", "fiction / historical"),
    ("skräck", "fiction / horror"),
    ("äventyr", "fiction / action & adventure"),
    ("humor", "humor"),
    ("tecknade serier", "comics & graphic novels"),
    ("poesi", "poetry"),
    ("lyrik", "poetry"),
    ("dramatik", "drama"),
    ("sagor", "fairy tales"),
    ("bilderböcker", "picture books"),
    ("fiction", "fiction"),
];
const LIBRIS_KIDS: [&str; 5] = [
    "barn- och ungdomslitteratur",
    "bilderböcker",
    "kapitelböcker",
    "mellanåldersböcker",
    "juvenile",
];
const NLK_SUBJECTS: [(&str, &str); 11] = [
    ("추리", "fiction / mystery & detective"),
    ("판타지", "fiction / fantasy"),
    ("공상과학", "fiction / science fiction"),
    ("로맨스", "fiction / romance"),
    ("연애", "fiction / romance"),
    ("역사소설", "fiction / historical"),
    ("공포", "fiction / horror"),
    ("만화", "comics & graphic novels"),
    ("그림책", "picture books"),
    ("동화", "fairy tales"),
    ("단편", "fiction / short stories"),
];
const NLK_KIDS: [&str; 4] = ["동화", "그림책", "어린이", "아동"];
fn items(value: &Value) -> Vec<&Value> {
    match value {
        Value::Array(values) => values.iter().collect(),
        Value::Null => Vec::new(),
        value => vec![value],
    }
}

fn text(value: &Value) -> &str {
    match value {
        Value::String(text) => text,
        Value::Array(values) => values.first().map_or("", text),
        _ => "",
    }
}

fn texts(value: &Value) -> Vec<&str> {
    items(value)
        .into_iter()
        .map(text)
        .filter(|text| !text.is_empty())
        .collect()
}

fn percent_decoded(value: &str) -> String {
    let raw = value.as_bytes();
    let mut bytes = Vec::with_capacity(raw.len());
    let mut index = 0;
    while index < raw.len() {
        let pair = raw
            .get(index + 1..index + 3)
            .and_then(|pair| std::str::from_utf8(pair).ok());
        match (
            raw[index],
            pair.and_then(|pair| u8::from_str_radix(pair, 16).ok()),
        ) {
            (b'%', Some(decoded)) => {
                bytes.push(decoded);
                index += 3;
            }
            (byte, _) => {
                bytes.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn tail(uri: &str) -> &str {
    uri.trim_end_matches(['/', '.'])
        .rsplit(['/', '#'])
        .next()
        .unwrap_or(uri)
}

fn mapped(terms: &[String], table: &[(&str, &'static str)]) -> Vec<&'static str> {
    let hits = table
        .iter()
        .filter(|(stem, _)| terms.iter().any(|term| term.contains(stem)));
    hits.map(|&(_, subject)| subject).collect()
}

fn mentions(terms: &[String], stems: &[&str]) -> bool {
    terms
        .iter()
        .any(|term| stems.iter().any(|stem| term.contains(stem)))
}

struct Traits {
    kid: bool,
    teen: bool,
    fiction: bool,
}

fn finished(mut subjects: Vec<&'static str>, traits: &Traits) -> (Vec<&'static str>, Classes) {
    let has = |subject| subjects.contains(&subject);
    let picture = has("picture books");
    let kid = (traits.kid || picture) && !traits.teen;
    let classes = Classes {
        classified: 1,
        picture: u16::from(picture),
        juvenile: u16::from(kid || traits.teen),
        comics: u16::from(has("comics & graphic novels")),
        poetry: u16::from(has("poetry")),
        drama: u16::from(has("drama")),
        kid: u16::from(kid),
        teen: u16::from(traits.teen),
        young_picture: u16::from(picture),
    };
    let base = match () {
        _ if traits.teen => Some("young adult fiction"),
        _ if kid => Some("juvenile fiction"),
        _ if traits.fiction => Some("fiction"),
        _ => None,
    };
    subjects.extend(base);
    subjects.sort_unstable();
    subjects.dedup();
    (subjects, classes)
}

fn isbn_keys<'a>(values: impl Iterator<Item = &'a str>) -> Vec<u32> {
    let mut keys = Vec::new();
    for key in values.filter_map(catalog::isbn_key) {
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    keys
}

fn person_name(agent: &Value) -> String {
    let given = text(&agent["givenName"]);
    let family = text(&agent["familyName"]);
    let name = match (given.is_empty(), family.is_empty()) {
        (false, false) => format!("{given} {family}"),
        (true, false) => family.to_string(),
        _ => text(&agent["name"]).to_string(),
    };
    clean(&name)
}

pub fn libris_names(line: &[u8]) -> Option<(String, String)> {
    let value: Value = serde_json::from_slice(line).ok()?;
    let person = *items(&value["@graph"]).get(1)?;
    let name = person_name(person);
    let id = text(&person["@id"]);
    (!name.is_empty() && !id.is_empty()).then(|| (id.to_string(), name))
}

fn libris_terms(work: &Value, instance: &Value) -> Vec<String> {
    let fields = [
        &work["genreForm"],
        &work["category"],
        &work["intendedAudience"],
        &instance["category"],
    ];
    let ids = fields
        .into_iter()
        .flat_map(items)
        .map(|term| text(&term["@id"]));
    ids.map(|id| percent_decoded(tail(id)).to_lowercase())
        .collect()
}

fn libris_author(work: &Value, instance: &Value, names: &HashMap<String, String>) -> String {
    let contributions = items(&work["contribution"]);
    let primary = contributions
        .iter()
        .find(|contribution| contribution["@type"] == "PrimaryContribution");
    let agent = primary.and_then(|primary| items(&primary["agent"]).first().copied());
    let inline = agent.map(person_name).unwrap_or_default();
    let linked = agent.and_then(|agent| names.get(text(&agent["@id"])));
    match (inline.is_empty(), linked) {
        (false, _) => inline,
        (true, Some(name)) => name.clone(),
        _ => author_name(text(&instance["responsibilityStatement"])),
    }
}

fn libris_series(instance: &Value) -> (String, u16) {
    let memberships = items(&instance["seriesMembership"]);
    let parsed = memberships.iter().find_map(|membership| {
        let statement = clean(text(&membership["seriesStatement"]));
        let (name, _) = release::parse_series(&[statement.as_str()])?;
        let volume = first_number(text(&membership["seriesEnumeration"]))?;
        (1..=MAX_VOLUME).contains(&volume).then_some((name, volume))
    });
    parsed.unwrap_or_default()
}

pub fn libris_record(line: &[u8], names: &HashMap<String, String>) -> Option<Record> {
    memchr_find(line, b"/language/swe")?;
    let value: Value = serde_json::from_slice(line).ok()?;
    let graph = items(&value["@graph"]);
    let (meta, instance) = (*graph.first()?, *graph.get(1)?);
    let work = &instance["instanceOf"];
    let swedish = items(&work["language"])
        .iter()
        .any(|language| text(&language["@id"]).ends_with("/swe"));
    let terms = libris_terms(work, instance);
    if !swedish || mentions(&terms, &LIBRIS_SKIPPED) {
        return None;
    }
    let codes = items(&work["classification"])
        .into_iter()
        .map(|class| text(&class["code"]));
    let codes: Vec<&str> = codes.collect();
    let fiction_class = codes.iter().any(|code| code.starts_with('H'));
    let juvenile_class = codes.iter().any(|code| code.starts_with("uH"));
    let subjects = mapped(&terms, &LIBRIS_SUBJECTS);
    let kid = juvenile_class || mentions(&terms, &LIBRIS_KIDS);
    let teen = mentions(&terms, &["ungdomsböcker"]);
    if subjects.is_empty() && !fiction_class && !kid && !teen {
        return None;
    }
    let identifiers = items(&instance["identifiedBy"]);
    let isbn_entries = identifiers.iter().filter(|entry| entry["@type"] == "ISBN");
    let isbn_entries: Vec<&&Value> = isbn_entries.collect();
    let isbns = isbn_keys(isbn_entries.iter().map(|entry| text(&entry["value"])));
    if isbns.is_empty() {
        return None;
    }
    let titles = items(&instance["hasTitle"]);
    let main = titles.iter().find(|title| title["@type"] == "Title")?;
    let title = clean(text(&main["mainTitle"]));
    let (series, volume) = libris_series(instance);
    let qualifiers: String = isbn_entries
        .iter()
        .map(|entry| text(&entry["qualifier"]))
        .collect();
    let digital = matches!(text(&instance["@type"]), "Electronic" | "DigitalResource");
    let format = match () {
        _ if digital => EBOOK,
        _ if qualifiers.contains("inb") => HARDCOVER,
        _ => PAPERBACK,
    };
    let originals = items(&work["translationOf"])
        .into_iter()
        .flat_map(|original| {
            items(&original["hasTitle"])
                .into_iter()
                .map(|title| text(&title["mainTitle"]))
        });
    let original = originals.into_iter().next().map(clean).unwrap_or_default();
    let published = items(&instance["publication"]).into_iter();
    let year = published
        .map(|event| dumps::year_of(text(&event["year"])))
        .max();
    let traits = Traits {
        kid,
        teen,
        fiction: fiction_class || subjects.contains(&"fiction"),
    };
    let (subjects, classes) = finished(subjects, &traits);
    let name = libris_author(work, instance, names);
    let control = text(&meta["controlNumber"]);
    let source = Source::Libris;
    Some(Record {
        source,
        id: source.local_id(control),
        language: source.language(),
        isbns,
        links: Vec::new(),
        title,
        original,
        series,
        volume,
        author: source.author_id(&name),
        name,
        year: year.unwrap_or(0),
        format,
        classes,
        subjects,
        digar: 0,
    })
    .filter(|record| !record.title.is_empty())
}

fn memchr_find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

pub fn libris_records(location: &str) -> Vec<Record> {
    let (instances, persons) = location.split_once(',').unwrap_or((location, ""));
    let mut names = HashMap::new();
    if !persons.is_empty() {
        let reader = dumps::open_location(persons, b"\n");
        dumps::scan_records(reader, b"\n", libris_names, |(id, name)| {
            names.insert(id, name);
        });
    }
    let mut records = Vec::new();
    let reader = dumps::open_location(instances, b"\n");
    let parse = |line: &[u8]| libris_record(line, &names);
    dumps::scan_records(reader, b"\n", parse, |record| records.push(record));
    records
}

pub fn nlk_record(chunk: &str) -> Option<Record> {
    if !chunk.contains("language/kor") {
        return None;
    }
    let start = chunk.find("\"@id\"")?;
    let body = chunk[start..]
        .trim_end()
        .trim_end_matches(['}', ']', '\n', ' ']);
    let value: Value = serde_json::from_str(&format!("{{{body}}}")).ok()?;
    let language = &value["http://id.loc.gov/ontologies/bibframe/language"];
    if !texts(language).iter().any(|code| code.ends_with("/kor")) {
        return None;
    }
    let kdc = text(&value["kdc"]);
    let form = kdc.chars().nth(2).filter(|_| kdc.starts_with('8'));
    let keywords = texts(&value["keyword"]);
    let mut terms: Vec<String> = keywords.iter().map(|keyword| keyword.to_string()).collect();
    terms.push(text(&value["title"]).to_string());
    let mut subjects = mapped(&terms, &NLK_SUBJECTS);
    subjects.extend(match form {
        Some('1') => Some("poetry"),
        Some('2') => Some("drama"),
        Some('3') => Some("fiction"),
        _ => None,
    });
    let kid = mentions(&terms, &NLK_KIDS);
    let teen = mentions(&terms, &["청소년"]);
    if subjects.is_empty() && !kid && !teen {
        return None;
    }
    let isbns = isbn_keys(texts(&value["isbn"]).into_iter());
    if isbns.is_empty() {
        return None;
    }
    let creators = texts(&value["dc:creator"]);
    let elements = texts(&value["http://purl.org/dc/elements/1.1/creator"]);
    let name = clean(
        creators
            .into_iter()
            .chain(elements)
            .next()
            .unwrap_or_default(),
    );
    let series = clean(text(&value["titleOfSeries"]));
    let volume = first_number(text(&value["volumeOfSeries"]))
        .filter(|volume| (1..=MAX_VOLUME).contains(volume))
        .unwrap_or(0);
    let title = clean(text(&value["title"]));
    let title = match title
        .strip_prefix('(')
        .and_then(|rest| rest.split_once(')'))
    {
        Some((_, rest)) if !rest.trim().is_empty() => rest.trim().to_string(),
        _ => title,
    };
    let traits = Traits {
        kid,
        teen,
        fiction: form == Some('3'),
    };
    let (subjects, classes) = finished(subjects, &traits);
    let source = Source::Nlk;
    Some(Record {
        source,
        id: source.local_id(tail(text(&value["@id"]))),
        language: source.language(),
        isbns,
        links: Vec::new(),
        title,
        original: String::new(),
        series: if volume > 0 { series } else { String::new() },
        volume,
        author: source.author_id(&name),
        name,
        year: dumps::year_of(text(&value["issuedYear"])),
        format: PAPERBACK,
        classes,
        subjects,
        digar: 0,
    })
    .filter(|record| !record.title.is_empty())
}
