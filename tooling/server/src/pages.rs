use crate::http::{CACHE_DAILY, NO_STORE, with_cache};
use crate::images::cover_url;
use crate::library::{Choice, Library, Member, isbn_key, language_name};
use crate::{App, blocking, number};
use askama::Template;
use axum::extract::{Path, Query, State};
use axum::http::{StatusCode, header};
use axum::response::{Html, IntoResponse, Redirect, Response};
use builder::libraries::SYNTHETIC;
use builder::reader::Book;
use builder::search::{Hit, tokenize};
use builder::sidecar::Extras;
use serde::Deserialize;
use std::hash::{BuildHasher, RandomState};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

const RESULTS_PER_PAGE: usize = 20;
const LIST_PER_PAGE: usize = 50;
const MAX_PAGE: usize = 50;
const MAX_SEARCH_PAGE: usize = 11;
const SHELF_BOOKS: usize = 12;
const SIMILAR_BOOKS: usize = 8;
const DAILY_PICKS: usize = 12;
const SEARCH_AUTHORS: usize = 3;
const AUTHOR_HITS: usize = 20;
const RANDOM_DEPTH: usize = 1000;
const THUMBNAIL: u32 = 160;
const COVER: u32 = 320;
const PHOTO: u32 = 192;
const SMALL_PHOTO: u32 = 96;
const FORMATS: [(u8, &str); 5] =
    [(1, "Hardcover"), (2, "Paperback"), (4, "Ebook"), (8, "Audiobook"), (16, "Comic")];

type Shared = State<Arc<App>>;

pub struct Row {
    pub link: String,
    pub title: String,
    pub subtitle: String,
    pub detail: String,
    pub cover: String,
}

pub struct Shelf {
    link: String,
    name: String,
    total: usize,
    rows: Vec<Row>,
}

struct Link {
    label: &'static str,
    url: String,
}

struct Other {
    language: &'static str,
    link: String,
    title: String,
    text: String,
}

struct EditionRow {
    year: u16,
    publisher: String,
    format: String,
    isbn: String,
}

struct AuthorHit {
    id: u32,
    name: String,
    detail: String,
    photo: String,
}

struct LanguageLink {
    code: String,
    name: &'static str,
}

#[derive(Template)]
#[template(path = "home.html")]
struct Home {
    works: String,
    authors: String,
    languages: Vec<LanguageLink>,
    rows: Vec<Row>,
}

#[derive(Template)]
#[template(path = "message.html")]
struct Message {
    text: &'static str,
}

#[derive(Template)]
#[template(path = "results.html")]
struct Results {
    query: String,
    authors: Vec<AuthorHit>,
    rows: Vec<Row>,
    previous: Option<String>,
    next: Option<String>,
}

#[derive(Template)]
#[template(path = "book.html")]
struct BookPage {
    code: String,
    title: String,
    subtitle: String,
    cover: String,
    icon: String,
    authors: Vec<(u32, String)>,
    overview: String,
    links: Vec<Link>,
    description: String,
    translated: bool,
    tags: Vec<String>,
    facts: Vec<(&'static str, String)>,
    others: Vec<Other>,
    series: Option<Shelf>,
    author: Option<(u32, String)>,
    author_rows: Vec<Row>,
    rows: Vec<Row>,
    editions: Vec<EditionRow>,
}

#[derive(Template)]
#[template(path = "author.html")]
struct AuthorPage {
    name: String,
    dates: String,
    names: String,
    bio: String,
    photo: String,
    total: usize,
    links: Vec<Link>,
    rows: Vec<Row>,
    previous: Option<String>,
    next: Option<String>,
}

#[derive(Template)]
#[template(path = "list.html")]
struct ListPage {
    name: String,
    note: String,
    rows: Vec<Row>,
    previous: Option<String>,
    next: Option<String>,
}

#[derive(Deserialize)]
pub struct SearchParams {
    #[serde(default)]
    q: String,
    #[serde(default)]
    p: usize,
}

#[derive(Deserialize)]
pub struct ListParams {
    #[serde(default)]
    p: usize,
}

fn render(template: impl Template) -> Response {
    Html(template.render().unwrap_or_default()).into_response()
}

pub fn not_found() -> Response {
    (StatusCode::NOT_FOUND, render(Message { text: "Not found." })).into_response()
}

fn respond(page: Option<Response>) -> Response {
    page.unwrap_or_else(|| {
        let text = "The catalogue is loading, try again in a minute.";
        let page = (StatusCode::SERVICE_UNAVAILABLE, render(Message { text }));
        with_cache(page.into_response(), NO_STORE)
    })
}

pub fn moved(target: String) -> Response {
    (StatusCode::MOVED_PERMANENTLY, [(header::LOCATION, target)]).into_response()
}

/// `/b/{work}/{language}/{width}.avif` when the book has a cover.
pub fn cover_path(book: &Book, width: u32) -> Option<String> {
    cover_url(book)?;
    Some(format!("/b/{}/{}/{width}.avif", book.work, book.language))
}

pub fn photo_path(number: u32, width: u32) -> Option<String> {
    (number > 0 && number < SYNTHETIC).then(|| format!("/a/{number}/{width}.avif"))
}

pub fn book_link(library: &Library, member: Member) -> String {
    format!("/b/{}/{}", library.unified_of(member.work), library.code(member))
}

pub fn book_row(library: &Library, member: Member, book: &Book) -> Row {
    let names: Vec<&str> = book.authors.iter().take(2).map(|a| a.name.as_str()).collect();
    let names = names.join(", ");
    let year = (book.year > 0).then(|| book.year.to_string());
    let language = Some(language_name(&book.language).to_string());
    let parts = [Some(names), year, language, library.series_label(member)];
    let detail: Vec<String> =
        parts.into_iter().flatten().filter(|p| !p.is_empty()).collect();
    Row {
        link: book_link(library, member),
        title: book.title.clone(),
        subtitle: book.subtitle.clone(),
        detail: detail.join(" · "),
        cover: cover_path(book, THUMBNAIL).unwrap_or_default(),
    }
}

pub fn rows(library: &Library, members: &[Member]) -> Vec<Row> {
    let books =
        members.iter().filter_map(|&member| Some((member, library.book(member)?)));
    books.map(|(member, book)| book_row(library, member, &book)).collect()
}

fn encode_query(text: &str) -> String {
    text.bytes()
        .map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => {
                char::from(byte).to_string()
            }
            b' ' => "+".to_string(),
            _ => format!("%{byte:02X}"),
        })
        .collect()
}

fn page_links(base: &str, page: usize, more: bool) -> (Option<String>, Option<String>) {
    let link = |page: usize| match page {
        0 => base.to_string(),
        _ => format!("{base}{}p={page}", if base.contains('?') { "&" } else { "?" }),
    };
    ((page > 0).then(|| link(page - 1)), more.then(|| link(page + 1)))
}

fn external_links(identifiers: &[String]) -> Vec<Link> {
    let mut links: Vec<Link> = Vec::new();
    for (scheme, value) in identifiers.iter().filter_map(|id| id.split_once(':')) {
        let (label, url) = match scheme {
            "ol" if value.ends_with('A') => {
                ("Open Library", format!("https://openlibrary.org/authors/{value}"))
            }
            "ol" => ("Open Library", format!("https://openlibrary.org/works/{value}")),
            "wikidata" => ("Wikidata", format!("https://www.wikidata.org/wiki/{value}")),
            "pg" => {
                ("Project Gutenberg", format!("https://www.gutenberg.org/ebooks/{value}"))
            }
            "viaf" => ("VIAF", format!("https://viaf.org/viaf/{value}")),
            "gnd" => ("GND", format!("https://d-nb.info/gnd/{value}")),
            "isni" => ("ISNI", format!("https://isni.org/isni/{value}")),
            "lc" => (
                "Library of Congress",
                format!("https://id.loc.gov/authorities/names/{value}"),
            ),
            "bne" => ("BNE", format!("https://datos.bne.es/persona/{value}")),
            _ => continue,
        };
        let safe = !value.is_empty()
            && value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-');
        if safe && !links.iter().any(|known| known.label == label) {
            links.push(Link { label, url });
        }
    }
    links
}

fn format_names(format: u8) -> String {
    let names =
        FORMATS.iter().filter(|(bit, _)| format & bit != 0).map(|(_, name)| *name);
    names.collect::<Vec<_>>().join(", ")
}

fn millions(count: usize) -> String {
    match count {
        1_000_000.. => format!("{:.1} million", count as f64 / 1e6),
        _ => count.to_string(),
    }
}

fn today() -> u64 {
    let elapsed = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    elapsed.as_secs() / 86_400
}

pub async fn home(State(app): Shared) -> Response {
    let day = today();
    let page = blocking(&app, move |library| {
        let languages = library.catalogues.iter().map(|catalogue| LanguageLink {
            code: catalogue.language.clone(),
            name: language_name(&catalogue.language),
        });
        let home = Home {
            works: millions(library.work_count),
            authors: millions(library.author_count),
            languages: languages.collect(),
            rows: rows(library, &library.daily(day, DAILY_PICKS)),
        };
        with_cache(render(home), CACHE_DAILY)
    });
    respond(page.await)
}

/// Every query word starts a word of `name`: "j rowl" names J. K. Rowling.
pub fn is_named(words: &[String], name: &str) -> bool {
    let name = tokenize(name);
    words.iter().all(|word| name.iter().any(|part| part.starts_with(word)))
}

fn matching_authors(library: &Library, query: &str, hits: &[Hit]) -> Vec<AuthorHit> {
    let words = tokenize(query);
    let mut found: Vec<AuthorHit> = Vec::new();
    let books = hits.iter().take(AUTHOR_HITS).flat_map(|hit| &hit.books);
    for author in books.flat_map(|book| &book.authors) {
        let number = library.author_of(author.number);
        if !is_named(&words, &author.name) || found.iter().any(|known| known.id == number)
        {
            continue;
        }
        let Some(record) = library.author(number, None) else { continue };
        let born = (record.born > 0).then(|| format!("born {}", record.born));
        let books = Some(format!("{} books", record.works.len()));
        found.push(AuthorHit {
            id: number,
            name: author.name.clone(),
            detail: [born, books].into_iter().flatten().collect::<Vec<_>>().join(" · "),
            photo: photo_path(number, SMALL_PHOTO).unwrap_or_default(),
        });
        if found.len() >= SEARCH_AUTHORS {
            break;
        }
    }
    found
}

/// The matching book of the first catalogue language, so one result list reads in one
/// language wherever the books allow it.
pub fn hit_member<'a>(library: &Library, hit: &'a Hit) -> Option<(Member, &'a Book)> {
    let members = hit.books.iter().filter_map(|book| {
        Some((
            Member { language: library.language(&book.language)?, work: book.work },
            book,
        ))
    });
    members.min_by_key(|(member, _)| member.language)
}

pub async fn search(State(app): Shared, Query(params): Query<SearchParams>) -> Response {
    let query = params.q.trim().to_string();
    if query.is_empty() {
        return Redirect::to("/").into_response();
    }
    let page = params.p.min(MAX_SEARCH_PAGE);
    let found = blocking(&app, move |library| {
        if let Some(member) = isbn_key(&query).and_then(|key| library.isbn(key)) {
            return Redirect::to(&book_link(library, member)).into_response();
        }
        let shown = (page + 1) * RESULTS_PER_PAGE;
        let hits = library.search(&query, shown + 1);
        let rows = hits.iter().skip(page * RESULTS_PER_PAGE).take(RESULTS_PER_PAGE);
        let rows = rows.filter_map(|hit| hit_member(library, hit));
        let base = format!("/s?q={}", encode_query(&query));
        let (previous, next) = page_links(&base, page, hits.len() > shown);
        let authors = match page {
            0 => matching_authors(library, &query, &hits),
            _ => Vec::new(),
        };
        render(Results {
            rows: rows.map(|(member, book)| book_row(library, member, book)).collect(),
            authors,
            query,
            previous,
            next,
        })
    });
    respond(found.await)
}

pub async fn isbn(State(app): Shared, Path(raw): Path<String>) -> Response {
    let found = blocking(&app, move |library| {
        match isbn_key(&raw).and_then(|key| library.isbn(key)) {
            Some(member) => Redirect::to(&book_link(library, member)).into_response(),
            None => not_found(),
        }
    });
    respond(found.await)
}

pub async fn random(State(app): Shared) -> Response {
    let seed = RandomState::new().hash_one(SystemTime::now()) as usize;
    let found = blocking(&app, move |library| {
        if library.catalogues.is_empty() {
            return not_found();
        }
        let language = seed % library.catalogues.len();
        let popular = library.popular(language);
        let Some(&work) =
            popular.get((seed >> 16) % popular.len().clamp(1, RANDOM_DEPTH))
        else {
            return not_found();
        };
        let target = book_link(library, Member { language, work });
        with_cache(Redirect::to(&target).into_response(), NO_STORE)
    });
    respond(found.await)
}

pub async fn book(State(app): Shared, Path(work): Path<String>) -> Response {
    book_page(app, work, None).await
}

pub async fn book_in(
    State(app): Shared,
    Path((work, code)): Path<(String, String)>,
) -> Response {
    book_page(app, work, Some(code)).await
}

async fn book_page(app: Arc<App>, raw: String, code: Option<String>) -> Response {
    let Some(id) = number(&raw) else { return not_found() };
    let page = blocking(&app, move |library| {
        let member = match library.choose(id, code.as_deref()) {
            Choice::Member(member) => member,
            Choice::Moved(to) => {
                let suffix = code.map(|code| format!("/{code}")).unwrap_or_default();
                return moved(format!("/b/{to}{suffix}"));
            }
            Choice::Missing => return not_found(),
        };
        book_view(library, id, member).map_or_else(not_found, render)
    });
    respond(page.await)
}

fn others(library: &Library, id: u32, member: Member, extras: &Extras) -> Vec<Other> {
    let members = library.members(id);
    let other_members = members.iter().filter(|&&other| other != member);
    let mut others: Vec<Other> = other_members
        .filter_map(|&other| {
            let book = library.book(other)?;
            let code = library.code(other);
            Some(Other {
                language: language_name(code),
                link: format!("/b/{id}/{code}"),
                title: book.title,
                text: library
                    .description(other)
                    .map(|found| found.text)
                    .unwrap_or_default(),
            })
        })
        .collect();
    let covered: Vec<&str> = members.iter().map(|&member| library.code(member)).collect();
    let descriptions =
        extras.descriptions.iter().filter(|(code, _)| !covered.contains(&&**code));
    others.extend(descriptions.map(|(code, text)| Other {
        language: language_name(code),
        link: String::new(),
        title: String::new(),
        text: text.clone(),
    }));
    others
}

fn facts(book: &Book, extras: &Extras) -> Vec<(&'static str, String)> {
    let titles = extras.titles.iter().filter(|(_, title)| *title != book.title);
    let titles: Vec<String> = titles
        .map(|(code, title)| format!("{title} ({})", language_name(code)))
        .collect();
    let original = Some(&extras.original_title).filter(|title| **title != book.title);
    let mut translators = extras.translators.clone();
    let credited = |wanted: &str| {
        let named = extras.contributors.iter().filter(|(role, _)| role == wanted);
        named.map(|(_, name)| name.clone()).collect::<Vec<_>>()
    };
    for name in credited("translator") {
        if !translators.iter().any(|known| known.starts_with(&name)) {
            translators.push(name);
        }
    }
    let facts = [
        ("Original title", original.cloned().unwrap_or_default()),
        ("Translated by", translators.join(", ")),
        ("Illustrated by", credited("illustrator").join(", ")),
        ("Edited by", credited("editor").join(", ")),
        ("Narrated by", credited("narrator").join(", ")),
        ("Also titled", titles.join(" · ")),
    ];
    facts.into_iter().filter(|(_, value)| !value.is_empty()).collect()
}

/// The member's tags, then those only other catalogues of the unified work give, all
/// labelled in the member's language.
fn tags(library: &Library, id: u32, member: Member, book: &Book) -> Vec<String> {
    let mut found = library.tags(member, book);
    for other in library.members(id).into_iter().filter(|&other| other != member) {
        let Some(theirs) = library.book(other) else { continue };
        for (slug, _) in library.tags(other, &theirs) {
            let known = found.iter().any(|(seen, _)| *seen == slug);
            let label = library.tag_label(member, &slug).filter(|_| !known);
            found.extend(label.map(|label| (slug, label)));
        }
    }
    found.into_iter().map(|(_, label)| label).collect()
}

fn book_view(library: &Library, id: u32, member: Member) -> Option<BookPage> {
    let book = library.book(member)?;
    let code = library.code(member);
    let extras = library.work_extras(id, member);
    let own = extras.descriptions.iter().find(|(language, _)| language == code);
    let (description, translated) = match library.description(member) {
        Some(found) => (found.text, found.translated),
        None => (own.map(|(_, text)| text.clone()).unwrap_or_default(), false),
    };
    let series = library.series(member);
    let mut shown: Vec<u32> = vec![id];
    let series_name = series.as_ref().map(|series| match series.position {
        0 => series.name.clone(),
        position => format!("{} #{position}", series.name),
    });
    let series = series.map(|series| {
        shown.extend(series.members.iter().map(|member| library.unified_of(member.work)));
        Shelf {
            link: format!("/series/{}/{code}", series.first),
            total: series.members.len(),
            rows: rows(library, &series.members[..series.members.len().min(SHELF_BOOKS)]),
            name: series.name,
        }
    });
    let first = book.authors.first().and_then(|author| {
        let found = library.author(author.number, Some(member.language))?;
        let works = found
            .works
            .into_iter()
            .filter(|m| !shown.contains(&library.unified_of(m.work)))
            .map(|m| library.display(library.unified_of(m.work), Some(member.language)));
        let works: Vec<Member> = works.flatten().take(SHELF_BOOKS).collect();
        Some(((found.number, author.name.clone()), works))
    });
    let (author, author_works) = first.map_or((None, Vec::new()), |(a, w)| (Some(a), w));
    shown.extend(author_works.iter().map(|member| library.unified_of(member.work)));
    let similar = library.similar(member, &shown, SIMILAR_BOOKS);
    let overview = [
        (book.year > 0).then(|| book.year.to_string()),
        Some(language_name(code).to_string()),
        series_name,
    ];
    let editions = extras.editions.iter().map(|edition| EditionRow {
        year: edition.year,
        publisher: edition.publisher.clone(),
        format: format_names(edition.format),
        isbn: edition.isbn.clone(),
    });
    Some(BookPage {
        code: code.to_string(),
        cover: cover_path(&book, COVER).unwrap_or_default(),
        icon: cover_path(&book, THUMBNAIL).unwrap_or_default(),
        authors: book
            .authors
            .iter()
            .map(|a| (library.author_of(a.number), a.name.clone()))
            .collect(),
        overview: overview.into_iter().flatten().collect::<Vec<_>>().join(" · "),
        links: external_links(&extras.identifiers),
        tags: tags(library, id, member, &book),
        facts: facts(&book, &extras),
        others: others(library, id, member, &extras),
        author_rows: rows(library, &author_works),
        rows: rows(library, &similar),
        editions: editions.collect(),
        title: book.title,
        subtitle: book.subtitle,
        description,
        translated,
        series,
        author,
    })
}

pub async fn author(
    State(app): Shared,
    Path(raw): Path<String>,
    Query(params): Query<ListParams>,
) -> Response {
    author_page(app, raw, None, params).await
}

/// `/a/{author}/{language}`: books, author and series names in that catalogue's language.
pub async fn author_in(
    app: Arc<App>,
    raw: String,
    code: String,
    params: ListParams,
) -> Response {
    author_page(app, raw, Some(code), params).await
}

async fn author_page(
    app: Arc<App>,
    raw: String,
    code: Option<String>,
    params: ListParams,
) -> Response {
    let Some(number) = number(&raw) else { return not_found() };
    let page = params.p.min(MAX_PAGE);
    let found = blocking(&app, move |library| {
        let wanted = match code.as_deref().map(|code| library.language(code)) {
            Some(None) => return not_found(),
            found => found.flatten(),
        };
        let suffix = code.map(|code| format!("/{code}")).unwrap_or_default();
        let Some(author) = library.author(number, wanted) else { return not_found() };
        if author.number != number {
            return moved(format!("/a/{}{suffix}", author.number));
        }
        let total = author.works.len();
        let shown = author.works.iter().skip(page * LIST_PER_PAGE).take(LIST_PER_PAGE);
        let shown: Vec<Member> = shown.copied().collect();
        let more = (page + 1) * LIST_PER_PAGE < total;
        let (previous, next) = page_links(&format!("/a/{number}{suffix}"), page, more);
        let mut identifiers = author.person.identifiers;
        if photo_path(number, PHOTO).is_some() {
            identifiers.insert(0, format!("ol:OL{number}A"));
        }
        let dates = match (author.born, author.person.died) {
            (0, 0) => String::new(),
            (born, 0) => format!("Born {born}"),
            (0, died) => format!("Died {died}"),
            (born, died) => format!("{born}–{died}"),
        };
        render(AuthorPage {
            name: author.name,
            dates,
            names: author.person.names.join(" · "),
            bio: author.person.bio,
            photo: photo_path(number, PHOTO).unwrap_or_default(),
            links: external_links(&identifiers),
            rows: rows(library, &shown),
            total,
            previous,
            next,
        })
    });
    respond(found.await)
}

pub async fn series(
    State(app): Shared,
    Path(raw): Path<String>,
    Query(params): Query<ListParams>,
) -> Response {
    series_page(app, raw, None, params).await
}

pub async fn series_in(
    State(app): Shared,
    Path((raw, code)): Path<(String, String)>,
    Query(params): Query<ListParams>,
) -> Response {
    series_page(app, raw, Some(code), params).await
}

async fn series_page(
    app: Arc<App>,
    raw: String,
    code: Option<String>,
    params: ListParams,
) -> Response {
    let Some(id) = number(&raw) else { return not_found() };
    let page = params.p.min(MAX_PAGE);
    let found = blocking(&app, move |library| {
        let suffix = code.as_ref().map(|code| format!("/{code}")).unwrap_or_default();
        let member = match library.choose(id, code.as_deref()) {
            Choice::Member(member) => member,
            Choice::Moved(to) => return moved(format!("/series/{to}{suffix}")),
            Choice::Missing => return not_found(),
        };
        let Some(series) = library.series(member) else { return not_found() };
        let total = series.members.len();
        let shown = series.members.iter().skip(page * LIST_PER_PAGE).take(LIST_PER_PAGE);
        let shown: Vec<Member> = shown.copied().collect();
        let more = (page + 1) * LIST_PER_PAGE < total;
        let (previous, next) = page_links(&format!("/series/{id}{suffix}"), page, more);
        render(ListPage {
            note: format!("{total} books in reading order"),
            name: series.name,
            rows: rows(library, &shown),
            previous,
            next,
        })
    });
    respond(found.await)
}

pub async fn catalogue(
    State(app): Shared,
    Path(code): Path<String>,
    Query(params): Query<ListParams>,
) -> Response {
    let page = params.p.min(MAX_PAGE);
    let found = blocking(&app, move |library| {
        let Some(language) = library.language(&code) else { return not_found() };
        let popular = library.popular(language);
        let shown = popular.iter().skip(page * LIST_PER_PAGE).take(LIST_PER_PAGE);
        let shown: Vec<Member> = shown.map(|&work| Member { language, work }).collect();
        let more = (page + 1) * LIST_PER_PAGE < popular.len();
        let (previous, next) = page_links(&format!("/l/{code}"), page, more);
        let total = library.catalogues[language].work_count();
        render(ListPage {
            name: format!("{} books", language_name(&code)),
            note: format!("{total} books, most read first"),
            rows: rows(library, &shown),
            previous,
            next,
        })
    });
    respond(found.await)
}
