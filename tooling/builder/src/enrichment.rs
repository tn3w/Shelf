use crate::dumps::{self, Authors, Book};
use crate::libraries::{SYNTHETIC, clean, match_key, normalized_name, writer_key};
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::path::Path;

const ENTITY: &str = "http://www.wikidata.org/entity/Q";
const GUTENBERG_SEPARATOR: &[u8] = b"</rdf:RDF>";
const SUMMARY_NOTE: &str = "(This is an automatically generated summary.)";
const AUTHORITIES: [&str; 3] = ["gnd", "viaf", "isni"];

#[derive(Default)]
pub struct WikidataWork {
    pub item: u32,
    pub language: String,
    pub gutenberg: Vec<u32>,
    pub labels: Vec<(String, String)>,
}

#[derive(Default)]
pub struct Wikidata {
    pub works: HashMap<u32, WikidataWork>,
    pub authors: HashMap<u32, Vec<String>>,
}

#[derive(Default)]
pub struct GutenbergBook {
    pub id: u32,
    pub title: String,
    pub author: String,
    pub summary: String,
}

#[derive(Default)]
pub struct Enrichment {
    pub wikidata: Wikidata,
    pub gutenberg: HashMap<u32, Vec<GutenbergBook>>,
}

fn item_number(field: &str) -> Option<u32> {
    let inner = field.trim_start_matches('<').trim_end_matches('>');
    inner.strip_prefix(ENTITY)?.parse().ok()
}

fn literal(field: &str) -> (&str, &str) {
    let field = field.trim();
    let Some(body) = field.strip_prefix('"') else {
        return (field.trim_matches(['<', '>']), "");
    };
    match body.rsplit_once('"') {
        Some((text, tail)) => (text, tail.trim_start_matches('@')),
        None => (body, ""),
    }
}

fn rows(path: &Path, mut each: impl FnMut(u32, &str, &str)) {
    let Ok(file) = std::fs::File::open(path) else {
        eprintln!("{}: missing, skipped", path.display());
        return;
    };
    for line in BufReader::new(file).lines().map_while(Result::ok) {
        let Some((item, value)) = line.split_once('\t') else {
            continue;
        };
        let Some(item) = item_number(item) else {
            continue;
        };
        let (text, language) = literal(value);
        each(item, &text.replace("\\\"", "\""), language);
    }
}

fn open_library_id(text: &str, kind: char) -> Option<u32> {
    text.strip_prefix("OL")?.strip_suffix(kind)?.parse().ok()
}

impl Wikidata {
    pub fn load(directory: &Path) -> Wikidata {
        let mut wikidata = Wikidata::default();
        let mut works: HashMap<u32, Vec<u32>> = HashMap::new();
        let mut authors: HashMap<u32, Vec<u32>> = HashMap::new();
        rows(&directory.join("openlibrary.tsv"), |item, text, _| {
            if let Some(work) = open_library_id(text, 'W') {
                works.entry(item).or_default().push(work);
            }
            if let Some(author) = open_library_id(text, 'A') {
                authors.entry(item).or_default().push(author);
            }
        });
        let mut items: HashMap<u32, WikidataWork> = works
            .keys()
            .map(|&item| {
                (
                    item,
                    WikidataWork {
                        item,
                        ..WikidataWork::default()
                    },
                )
            })
            .collect();
        rows(&directory.join("language.tsv"), |item, text, _| {
            if let Some(linked) = items.get_mut(&item) {
                linked.language = text.to_string();
            }
        });
        rows(&directory.join("gutenberg.tsv"), |item, text, _| {
            let linked = items.get_mut(&item);
            if let Some((linked, number)) = linked.zip(text.parse().ok()) {
                linked.gutenberg.push(number);
            }
        });
        rows(&directory.join("label.tsv"), |item, text, language| {
            if let Some(linked) = items.get_mut(&item) {
                linked.labels.push((language.to_string(), clean(text)));
            }
        });
        for (item, ids) in works {
            let Some(linked) = items.remove(&item) else {
                continue;
            };
            for &work in &ids[1..] {
                let copy = WikidataWork {
                    item,
                    language: linked.language.clone(),
                    gutenberg: linked.gutenberg.clone(),
                    labels: linked.labels.clone(),
                };
                wikidata.works.insert(work, copy);
            }
            wikidata.works.insert(ids[0], linked);
        }
        let mut ids: HashMap<u32, Vec<String>> = HashMap::new();
        for scheme in AUTHORITIES {
            rows(&directory.join(format!("{scheme}.tsv")), |item, text, _| {
                if authors.contains_key(&item) {
                    ids.entry(item)
                        .or_default()
                        .push(format!("{scheme}:{text}"));
                }
            });
        }
        for (item, people) in authors {
            let mut known = vec![format!("wikidata:Q{item}")];
            known.extend(ids.remove(&item).unwrap_or_default());
            for author in people {
                wikidata.authors.insert(author, known.clone());
            }
        }
        eprintln!(
            "wikidata: {} works, {} authors",
            wikidata.works.len(),
            wikidata.authors.len()
        );
        wikidata
    }
}

fn element<'a>(text: &'a str, open: &str) -> Option<&'a str> {
    let start = text.find(open)?;
    let after = &text[start..];
    let body = &after[after.find('>')? + 1..];
    Some(&body[..body.find('<')?])
}

fn decoded(text: &str) -> String {
    let entities = [
        ("&amp;", "&"),
        ("&lt;", "<"),
        ("&gt;", ">"),
        ("&quot;", "\""),
    ];
    let replaced = entities
        .iter()
        .fold(text.to_string(), |text, (entity, plain)| {
            text.replace(entity, plain)
        });
    clean(&replaced)
}

pub fn gutenberg_book(bytes: &[u8]) -> Option<GutenbergBook> {
    let text = std::str::from_utf8(bytes).ok()?;
    let about = text.split("<pgterms:ebook rdf:about=\"ebooks/").nth(1)?;
    let id = about.split('"').next()?.parse().ok()?;
    let is_text = text.contains("<rdf:value>Text</rdf:value>");
    let title = decoded(element(text, "<dcterms:title")?);
    let summary = element(text, "<pgterms:marc520")
        .map(decoded)
        .unwrap_or_default();
    let author = element(text, "<pgterms:name")
        .map(decoded)
        .unwrap_or_default();
    let title = title.lines().next().unwrap_or_default().to_string();
    is_text.then(|| GutenbergBook {
        id,
        title,
        author: normalized_name(&author),
        summary: summary.trim_end_matches(SUMMARY_NOTE).trim().to_string(),
    })
}

fn gutenberg_books(location: &str) -> Vec<GutenbergBook> {
    let reader = dumps::open_location(location, GUTENBERG_SEPARATOR);
    let mut books = Vec::new();
    dumps::scan_records(reader, GUTENBERG_SEPARATOR, gutenberg_book, |book| {
        books.push(book)
    });
    eprintln!("gutenberg: {} books", books.len());
    books
}

fn book_key(writer: &str, title: &str) -> (String, String) {
    (writer_key(writer), match_key(title))
}

pub fn match_gutenberg(
    books: Vec<GutenbergBook>,
    works: &[Book],
    authors: &Authors,
    wikidata: &Wikidata,
) -> HashMap<u32, Vec<GutenbergBook>> {
    let mut by_number: HashMap<u32, u32> = HashMap::new();
    for (&work, linked) in &wikidata.works {
        linked.gutenberg.iter().for_each(|&number| {
            by_number.insert(number, work);
        });
    }
    let mut by_title: HashMap<(String, String), Option<u32>> = HashMap::new();
    for book in works.iter().filter(|book| book.work < SYNTHETIC) {
        let Some(author) = book.authors.first().and_then(|&id| authors.get(id)) else {
            continue;
        };
        let slot = by_title
            .entry(book_key(author.name, &book.title))
            .or_insert(Some(book.work));
        if *slot != Some(book.work) {
            *slot = None;
        }
    }
    let mut matched: HashMap<u32, Vec<GutenbergBook>> = HashMap::new();
    for book in books {
        let titled = by_title
            .get(&book_key(&book.author, &book.title))
            .copied()
            .flatten();
        if let Some(work) = by_number.get(&book.id).copied().or(titled) {
            matched.entry(work).or_default().push(book);
        }
    }
    eprintln!("gutenberg: {} works matched", matched.len());
    matched
}

impl Enrichment {
    pub fn load(
        wikidata: Option<&Path>,
        gutenberg: Option<&str>,
    ) -> (Wikidata, Vec<GutenbergBook>) {
        std::thread::scope(|scope| {
            let books = scope.spawn(|| gutenberg.map(gutenberg_books).unwrap_or_default());
            let linked = wikidata.map(Wikidata::load).unwrap_or_default();
            (linked, books.join().expect("gutenberg"))
        })
    }

    pub fn author_ids(&self, author: u32) -> &[String] {
        let found = self.wikidata.authors.get(&author);
        found.map_or(&[][..], Vec::as_slice)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RDF: &str = r#"<rdf:RDF>
  <pgterms:ebook rdf:about="ebooks/1342">
    <dcterms:creator><pgterms:agent><pgterms:name>Austen, Jane</pgterms:name></pgterms:agent></dcterms:creator>
    <dcterms:title>Pride and Prejudice</dcterms:title>
    <pgterms:marc520>A novel of manners &amp; marriage. (This is an automatically generated summary.)</pgterms:marc520>
    <dcterms:type><rdf:Description><rdf:value>Text</rdf:value></rdf:Description></dcterms:type>
  </pgterms:ebook>
"#;

    #[test]
    fn gutenberg_rdf_parsed() {
        let book = gutenberg_book(RDF.as_bytes()).expect("book");
        assert_eq!(
            (book.id, book.title.as_str()),
            (1342, "Pride and Prejudice")
        );
        assert_eq!(book.author, "Jane Austen");
        assert_eq!(book.summary, "A novel of manners & marriage.");
        assert!(gutenberg_book(RDF.replace(">Text<", ">Sound<").as_bytes()).is_none());
    }

    #[test]
    fn wikidata_rows_link_open_library_ids() {
        let directory = std::env::temp_dir().join(format!("wikidata-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let entity = |item: u32, value: &str| format!("<{ENTITY}{item}>\t{value}\n");
        let files = [
            (
                "openlibrary",
                entity(170583, "\"OL66554W\"") + &entity(36322, "\"OL21594A\""),
            ),
            (
                "language",
                "?item\t?value\n".to_string() + &entity(170583, "\"en\""),
            ),
            ("gutenberg", entity(170583, "\"1342\"")),
            (
                "label",
                entity(170583, "\"高慢と偏見\"@ja") + &entity(170583, "\"Stolz\"@de"),
            ),
            ("viaf", entity(36322, "\"102333412\"")),
        ];
        for (name, content) in files {
            std::fs::write(directory.join(format!("{name}.tsv")), content).unwrap();
        }
        let wikidata = Wikidata::load(&directory);
        let work = &wikidata.works[&66554];
        assert_eq!((work.item, work.language.as_str()), (170583, "en"));
        assert_eq!(work.gutenberg, [1342]);
        assert_eq!(work.labels[0], ("ja".to_string(), "高慢と偏見".to_string()));
        assert_eq!(
            wikidata.authors[&21594],
            ["wikidata:Q36322", "viaf:102333412"]
        );
        std::fs::remove_dir_all(directory).unwrap();
    }
}
