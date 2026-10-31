use crate::catalog;
use crate::dumps;
use crate::libraries::{
    EBOOK, HARDCOVER, MAX_VOLUME, PAPERBACK, Record, Source, author_name, capitalized, clean,
    first_number, normalized_name,
};
use crate::release;
use crate::tags::Classes;
use serde_json::Value;
use std::collections::HashMap;
use std::io::{BufRead, BufReader};

pub const NLK_SEPARATOR: &[u8] = b"\n  }, {";
const LIBRIS_SKIPPED: [&str; 6] = [
    "ljudböcker",
    "talböcker",
    "läromedel",
    "videoupptagningar",
    "avhandlingar",
    "offentligt tryck",
];
const LIBRIS_SUBJECTS: [(&str, &str); 20] = [
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
const BNB_RESOURCE: &str = "http://bnb.data.bl.uk/id/resource/";
const BNB_SERIES: &str = "http://bnb.data.bl.uk/id/series/";
const BNB_SERIES_KINDS: [&str; 6] = [
    " novel",
    " mystery",
    " thriller",
    " story",
    " adventure",
    " book",
];
const BNB_SUBJECTS: [(&str, &str); 11] = [
    ("fantasyfiction", "fiction / fantasy"),
    (
        "detectiveandmysterystories",
        "fiction / mystery & detective",
    ),
    ("sciencefiction", "fiction / science fiction"),
    ("lovestories", "fiction / romance"),
    ("horrorfiction", "fiction / horror"),
    ("horrortales", "fiction / horror"),
    ("historicalfiction", "fiction / historical"),
    ("suspensefiction", "fiction / thrillers"),
    ("humorousstories", "fiction / humorous"),
    ("adventurestories", "fiction / action & adventure"),
    ("graphicnovels", "comics & graphic novels"),
];
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
    let comics = has("comics & graphic novels");
    let picture = has("picture books") && !comics;
    let kid = (traits.kid || picture) && !traits.teen;
    let classes = Classes {
        classified: 1,
        picture: u16::from(picture),
        juvenile: u16::from(kid || traits.teen),
        comics: u16::from(comics),
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
    normalized_name(&name)
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
    let mut subjects = mapped(&terms, &LIBRIS_SUBJECTS);
    if terms.iter().any(|term| term == "fiction") {
        subjects.push("fiction");
    }
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
    let literature = kdc.starts_with('8') && !kdc[1..].starts_with('0');
    let form = kdc.chars().nth(2).filter(|_| literature);
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
    let name = normalized_name(
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

#[derive(Default)]
struct BnbDraft {
    id: String,
    title: String,
    statement: String,
    isbns: Vec<u32>,
    series: String,
    year: u16,
    english: bool,
    subjects: Vec<String>,
}

impl BnbDraft {
    fn absorb(&mut self, predicate: &str, object: &str) {
        match tail(predicate) {
            "title" => self.title = literal(object),
            "label" => {
                let label = literal(object);
                self.statement = label
                    .split_once(" / ")
                    .map_or(String::new(), |(_, by)| by.into());
            }
            "isbn13" | "isbn10" => self.isbns.extend(catalog::isbn_key(&literal(object))),
            "isPartOf" => self.series = uri(object).trim_start_matches(BNB_SERIES).to_string(),
            "language" => self.english |= object.ends_with("/eng>"),
            "subject" => self.subjects.push(tail(uri(object)).to_lowercase()),
            _ => {}
        }
    }

    fn form(&self) -> Option<char> {
        let literary = |code: &&String| code.starts_with('8') && !code[1..].starts_with('0');
        let code = self.subjects.iter().find(literary)?;
        code.chars().nth(2).filter(|form| "123".contains(*form))
    }

    fn has(&self, stem: &str) -> bool {
        self.subjects.iter().any(|subject| subject.contains(stem))
    }

    fn is_fiction(&self) -> bool {
        self.form() == Some('3') || self.has("fiction") || self.has("stories")
    }

    fn is_usable(&self) -> bool {
        let literary = self.is_fiction() || self.form().is_some();
        literary && self.english && !self.isbns.is_empty() && !self.title.is_empty()
    }

    fn record(self, series_labels: &HashMap<String, String>) -> Record {
        let form = self.form();
        let has = |stem: &str| self.has(stem);
        let fiction = self.is_fiction();
        let mut subjects = mapped(&self.subjects, &BNB_SUBJECTS);
        subjects.extend(match form {
            Some('1') => Some("poetry"),
            Some('2') => Some("drama"),
            _ => None,
        });
        if has("picturebooks") {
            subjects.push("picture books");
        }
        let traits = Traits {
            kid: has("juvenile"),
            teen: has("youngadult"),
            fiction,
        };
        let (subjects, classes) = finished(subjects, &traits);
        let label = series_labels
            .get(&self.series)
            .map(|label| bnb_series(label));
        let (series, _) = label
            .and_then(|label| release::parse_series(&[label]))
            .unwrap_or_default();
        let (main, subtitle) = self.title.split_once(" : ").unwrap_or((&self.title, ""));
        let named =
            !subtitle.is_empty() && catalog::series_key(main) == catalog::series_key(&series);
        let title = clean(&if named {
            capitalized(subtitle)
        } else {
            main.to_string()
        });
        let name = author_name(&self.statement);
        let source = Source::Bnb;
        Record {
            source,
            id: source.local_id(&self.id),
            language: source.language(),
            isbns: self.isbns,
            links: Vec::new(),
            title,
            original: String::new(),
            series,
            volume: 0,
            author: source.author_id(&name),
            name,
            year: self.year,
            format: PAPERBACK,
            classes,
            subjects,
            digar: 0,
        }
    }
}

fn bnb_series(label: &str) -> &str {
    let label = label.strip_suffix(" series").unwrap_or(label);
    let Some(named) = label
        .strip_prefix("A ")
        .or_else(|| label.strip_prefix("An "))
    else {
        return label;
    };
    let kind = BNB_SERIES_KINDS
        .iter()
        .find_map(|kind| named.strip_suffix(kind));
    kind.unwrap_or(label)
}

fn triple(line: &str) -> Option<(&str, &str, &str)> {
    let (subject, rest) = line.strip_prefix('<')?.split_once("> <")?;
    let (predicate, object) = rest.split_once("> ")?;
    Some((subject, predicate, object.trim_end().strip_suffix(" .")?))
}

fn uri(object: &str) -> &str {
    object.trim_start_matches('<').trim_end_matches('>')
}

fn literal(object: &str) -> String {
    let body = object.strip_prefix('"').unwrap_or_default();
    let end = body.rfind('"').unwrap_or_default();
    let mut decoded = String::with_capacity(end);
    let mut characters = body[..end].chars();
    while let Some(character) = characters.next() {
        let width = match (character, characters.clone().next()) {
            ('\\', Some('u')) => 4,
            ('\\', Some('U')) => 8,
            ('\\', Some(escaped)) => {
                characters.next();
                decoded.push(if escaped == 'n' || escaped == 't' {
                    ' '
                } else {
                    escaped
                });
                continue;
            }
            _ => {
                decoded.push(character);
                continue;
            }
        };
        characters.next();
        let code: String = characters.by_ref().take(width).collect();
        decoded.extend(u32::from_str_radix(&code, 16).ok().and_then(char::from_u32));
    }
    decoded
}

#[derive(Default)]
struct BnbScan {
    drafts: Vec<BnbDraft>,
    series_labels: HashMap<String, String>,
    draft: BnbDraft,
}

impl BnbScan {
    fn line(&mut self, subject: &str, predicate: &str, object: &str) {
        if let Some(series) = subject.strip_prefix(BNB_SERIES) {
            if tail(predicate) == "label" {
                self.series_labels
                    .insert(series.to_string(), literal(object));
            }
            return;
        }
        let Some(resource) = subject.strip_prefix(BNB_RESOURCE) else {
            return;
        };
        let (id, event) = resource.split_once('/').unwrap_or((resource, ""));
        if id != self.draft.id {
            self.flush(id);
        }
        match event.is_empty() {
            true => self.draft.absorb(predicate, object),
            false if tail(predicate) == "time" => self.draft.year = dumps::year_of(uri(object)),
            false => {}
        }
    }

    fn flush(&mut self, next: &str) {
        let fresh = BnbDraft {
            id: next.to_string(),
            ..BnbDraft::default()
        };
        let done = std::mem::replace(&mut self.draft, fresh);
        if done.is_usable() {
            self.drafts.push(done);
        }
    }
}

pub fn bnb_records(location: &str) -> Vec<Record> {
    let mut reader = BufReader::new(dumps::open_location(location, b"\n"));
    let mut scan = BnbScan::default();
    let mut line = Vec::new();
    while reader
        .read_until(b'\n', &mut line)
        .is_ok_and(|read| read > 0)
    {
        let parsed = std::str::from_utf8(&line).ok().and_then(triple);
        if let Some((subject, predicate, object)) = parsed {
            scan.line(subject, predicate, object);
        }
        line.clear();
    }
    scan.flush("");
    let labels = &scan.series_labels;
    scan.drafts
        .into_iter()
        .map(|draft| draft.record(labels))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn triples(resource: &str, lines: &[(&str, &str)]) -> String {
        let subject = format!("<{BNB_RESOURCE}{resource}>");
        let line =
            |(predicate, object): &(&str, &str)| format!("{subject} <{predicate}> {object} .\n");
        lines.iter().map(line).collect()
    }

    #[test]
    fn bnb_fiction_kept_with_series_and_year() {
        let dc = "http://purl.org/dc/terms";
        let ddc = "<http://bnb.data.bl.uk/id/concept/ddc/e23";
        let event = format!(
            "<{BNB_RESOURCE}011022431/publicationevent/LondonScholastic1997> \
             <http://purl.org/NET/c4dm/event.owl#time> \
             <http://reference.data.gov.uk/id/year/1997> .\n"
        );
        let fiction = triples(
            "011022431",
            &[
                (&format!("{dc}/title"), "\"The subtle knife\""),
                (
                    "http://www.w3.org/2000/01/rdf-schema#label",
                    "\"The subtle knife / Philip Pullman\"",
                ),
                ("http://purl.org/ontology/bibo/isbn13", "\"9780590542432\""),
                (
                    &format!("{dc}/isPartOf"),
                    &format!("<{BNB_SERIES}Hisdarkmaterials>"),
                ),
                (
                    &format!("{dc}/language"),
                    "<http://lexvo.org/id/iso639-3/eng>",
                ),
                (&format!("{dc}/subject"), &format!("{ddc}/823.92>")),
            ],
        );
        let manual = triples(
            "011022432",
            &[
                (&format!("{dc}/title"), "\"Caf\\u00E9 accounts\""),
                ("http://purl.org/ontology/bibo/isbn13", "\"9780141363356\""),
                (
                    &format!("{dc}/language"),
                    "<http://lexvo.org/id/iso639-3/eng>",
                ),
                (&format!("{dc}/subject"), &format!("{ddc}/657.2>")),
            ],
        );
        let label = format!(
            "<{BNB_SERIES}Hisdarkmaterials> <http://www.w3.org/2000/01/rdf-schema#label> \
             \"His dark materials\" .\n"
        );
        let path = std::env::temp_dir().join(format!("bnb-{}.nt", std::process::id()));
        std::fs::write(&path, format!("{event}{fiction}{manual}{label}")).expect("fixture");
        let records = bnb_records(path.to_str().expect("utf-8 path"));
        std::fs::remove_file(&path).ok();
        assert_eq!(records.len(), 1);
        let record = &records[0];
        assert_eq!(record.title, "The subtle knife");
        assert_eq!(record.name, "Philip Pullman");
        assert_eq!(record.series, "His dark materials");
        assert_eq!(record.year, 1997);
        assert_eq!(record.language, crate::language_index("en"));
    }

    #[test]
    fn bnb_series_phrasing_reduced_to_name() {
        assert_eq!(bnb_series("A Discworld novel"), "Discworld");
        assert_eq!(
            bnb_series("An Inspector Montalbano mystery"),
            "Inspector Montalbano"
        );
        assert_eq!(
            bnb_series("The No. 1 Ladies' Detective Agency series"),
            "The No. 1 Ladies' Detective Agency"
        );
        assert_eq!(
            bnb_series("A Series of Unfortunate Events"),
            "A Series of Unfortunate Events"
        );
    }

    #[test]
    fn bnb_title_after_series_name_used() {
        let draft = BnbDraft {
            title: "Red dwarf : infinity welcomes careful drivers".to_string(),
            series: "Reddwarf".to_string(),
            isbns: vec![1],
            english: true,
            ..BnbDraft::default()
        };
        let labels = HashMap::from([("Reddwarf".to_string(), "Red dwarf".to_string())]);
        assert_eq!(
            draft.record(&labels).title,
            "Infinity welcomes careful drivers"
        );
    }

    #[test]
    fn bnb_literals_unescaped() {
        assert_eq!(literal("\"Caf\\u00E9 \\\"noir\\\"\"@en"), "Café \"noir\"");
    }
}
