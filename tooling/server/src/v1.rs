use crate::http::ApiError;
use crate::library::{Choice, Library, Member, isbn_key, language_name};
use crate::pages::{cover_path, hit_member, moved, photo_path};
use crate::{App, blocking, number};
use axum::Json;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use builder::reader::{Book, isbn_of};
use builder::search::tokenize;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

const DEFAULT_LIMIT: usize = 20;
const MAX_LIMIT: usize = 50;
const MAX_PAGE: usize = 50;
const SUGGEST_WORKS: usize = 6;
const SUGGEST_AUTHORS: usize = 2;
const SIMILAR_LIMIT: usize = 12;
const COVER: u32 = 320;
const PHOTO: u32 = 192;

type Shared = State<Arc<App>>;

fn is_zero(value: &u32) -> bool {
    *value == 0
}

#[derive(Serialize)]
struct AuthorRef {
    id: u32,
    name: String,
}

#[derive(Serialize)]
struct WorkSummary {
    id: u32,
    language: String,
    title: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    subtitle: String,
    authors: Vec<AuthorRef>,
    #[serde(skip_serializing_if = "is_zero")]
    year: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    cover: Option<String>,
}

#[derive(Serialize)]
struct Text {
    language: String,
    text: String,
}

#[derive(Serialize)]
struct MemberRef {
    language: String,
    title: String,
}

#[derive(Serialize)]
struct TagRef {
    slug: String,
    label: String,
}

#[derive(Serialize)]
struct SeriesRef {
    id: u32,
    name: String,
    position: usize,
}

#[derive(Serialize)]
struct EditionDetail {
    #[serde(skip_serializing_if = "is_zero")]
    year: u32,
    publisher: String,
    isbn: String,
    format: u8,
}

#[derive(Serialize)]
struct WorkDetail {
    #[serde(flatten)]
    summary: WorkSummary,
    languages: Vec<MemberRef>,
    description: Option<Text>,
    translated: bool,
    tags: Vec<TagRef>,
    series: Option<SeriesRef>,
    descriptions: Vec<Text>,
    titles: Vec<Text>,
    original_title: String,
    translators: Vec<String>,
    identifiers: Vec<String>,
    editions: Vec<EditionDetail>,
}

#[derive(Serialize)]
struct Suggestion {
    kind: &'static str,
    id: u32,
    #[serde(skip_serializing_if = "String::is_empty")]
    language: String,
    label: String,
    detail: String,
}

#[derive(Serialize)]
struct LanguageCount {
    code: String,
    name: &'static str,
    works: u32,
}

#[derive(Serialize)]
struct SeriesEntry {
    work: u32,
    position: usize,
}

#[derive(Deserialize)]
pub struct SearchParams {
    #[serde(default)]
    q: String,
    page: Option<String>,
    limit: Option<String>,
}

#[derive(Deserialize)]
pub struct WorkParams {
    language: Option<String>,
}

fn parsed<T: std::str::FromStr>(name: &str, raw: &str) -> Result<T, ApiError> {
    raw.parse().map_err(|_| ApiError::invalid(format!("{name} must be a number")))
}

fn id_of(name: &str, raw: &str) -> Result<u32, ApiError> {
    number(raw).ok_or_else(|| ApiError::invalid(format!("{name} must be a number")))
}

fn paging(page: Option<&str>, limit: Option<&str>) -> Result<(usize, usize), ApiError> {
    let page: usize = page.map_or(Ok(1), |raw| parsed("page", raw))?;
    let limit: usize = limit.map_or(Ok(DEFAULT_LIMIT), |raw| parsed("limit", raw))?;
    if !(1..=MAX_PAGE).contains(&page) || !(1..=MAX_LIMIT).contains(&limit) {
        return Err(ApiError::invalid(format!(
            "page must be 1-{MAX_PAGE}, limit 1-{MAX_LIMIT}"
        )));
    }
    Ok((page, limit))
}

fn language_of(raw: Option<String>) -> Result<Option<String>, ApiError> {
    match raw.filter(|code| !code.is_empty()) {
        Some(code) if !builder::LANGUAGES.contains(&code.as_str()) => {
            Err(ApiError::invalid("language must be a catalogue code like en or de"))
        }
        code => Ok(code),
    }
}

fn reply<T: Serialize>(result: Option<Result<T, ApiError>>) -> Response {
    match result {
        Some(Ok(body)) => Json(body).into_response(),
        Some(Err(error)) => error.into_response(),
        None => ApiError::unavailable().into_response(),
    }
}

fn summary(library: &Library, member: Member, book: &Book) -> WorkSummary {
    WorkSummary {
        id: library.unified_of(member.work),
        language: library.code(member).to_string(),
        title: book.title.clone(),
        subtitle: book.subtitle.clone(),
        authors: book
            .authors
            .iter()
            .map(|author| AuthorRef { id: author.number, name: author.name.clone() })
            .collect(),
        year: book.year,
        cover: cover_path(library, member, COVER),
    }
}

fn summaries(library: &Library, members: &[Member]) -> Vec<WorkSummary> {
    let books =
        members.iter().filter_map(|&member| Some((member, library.book(member)?)));
    books.map(|(member, book)| summary(library, member, &book)).collect()
}

fn texts(pairs: &[(String, String)]) -> Vec<Text> {
    let texts = pairs
        .iter()
        .map(|(language, text)| Text { language: language.clone(), text: text.clone() });
    texts.collect()
}

fn work_detail(library: &Library, id: u32, member: Member) -> Option<WorkDetail> {
    let book = library.book(member)?;
    let code = library.code(member).to_string();
    let extras = library.work_extras(id, member);
    let description = library.description(member);
    let series = library.series(member).map(|series| SeriesRef {
        id: series.first,
        position: series.members.iter().position(|&known| known == member).unwrap_or(0)
            + 1,
        name: series.name,
    });
    let languages = library.members(id).into_iter().filter_map(|other| {
        let title = library.book(other)?.title;
        Some(MemberRef { language: library.code(other).to_string(), title })
    });
    let tags = library
        .tags(member, &book)
        .into_iter()
        .map(|(slug, label)| TagRef { slug, label });
    let editions = extras.editions.iter().map(|edition| EditionDetail {
        year: u32::from(edition.year),
        publisher: edition.publisher.clone(),
        isbn: edition.isbn.clone(),
        format: edition.format,
    });
    Some(WorkDetail {
        summary: summary(library, member, &book),
        languages: languages.collect(),
        translated: description.as_ref().is_some_and(|found| found.translated),
        description: description.map(|found| Text { language: code, text: found.text }),
        tags: tags.collect(),
        series,
        descriptions: texts(&extras.descriptions),
        titles: texts(&extras.titles),
        editions: editions.collect(),
        original_title: extras.original_title,
        translators: extras.translators,
        identifiers: extras.identifiers,
    })
}

fn elsewhere(choice: Choice, path: &str, code: Option<&str>) -> Response {
    let Choice::Moved(to) = choice else {
        return ApiError::not_found("Work").into_response();
    };
    let query = code.map(|code| format!("?language={code}")).unwrap_or_default();
    moved(format!("/api/v1/{path}/{to}{query}"))
}

fn loaded(found: Option<Response>) -> Response {
    found.unwrap_or_else(|| ApiError::unavailable().into_response())
}

pub async fn search(State(app): Shared, Query(params): Query<SearchParams>) -> Response {
    let paged = paging(params.page.as_deref(), params.limit.as_deref());
    let Ok((page, limit)) = paged else { return reply::<()>(Some(paged.map(drop))) };
    let query = params.q.trim().to_string();
    let found = blocking(&app, move |library| {
        let hits = library.search(&query, page * limit + 1);
        let shown = hits.iter().skip((page - 1) * limit).take(limit);
        let results = shown.filter_map(|hit| hit_member(library, hit));
        let results = results.map(|(member, book)| summary(library, member, book));
        Ok(serde_json::json!({
            "query": query,
            "page": page,
            "limit": limit,
            "more": hits.len() > page * limit,
            "results": results.collect::<Vec<_>>(),
        }))
    });
    reply(found.await)
}

pub async fn work(
    State(app): Shared,
    Path(raw): Path<String>,
    Query(params): Query<WorkParams>,
) -> Response {
    let checked =
        id_of("work id", &raw).and_then(|id| Ok((id, language_of(params.language)?)));
    let (id, code) = match checked {
        Ok(checked) => checked,
        Err(error) => return error.into_response(),
    };
    let found = blocking(&app, move |library| {
        let member = match library.choose(id, code.as_deref()) {
            Choice::Member(member) => member,
            other => return elsewhere(other, "works", code.as_deref()),
        };
        match work_detail(library, id, member) {
            Some(detail) => Json(detail).into_response(),
            None => ApiError::not_found("Work").into_response(),
        }
    });
    loaded(found.await)
}

pub async fn similar(
    State(app): Shared,
    Path(raw): Path<String>,
    Query(params): Query<SearchParams>,
) -> Response {
    let limit =
        params.limit.as_deref().map_or(Ok(SIMILAR_LIMIT), |raw| parsed("limit", raw));
    let checked = id_of("work id", &raw).and_then(|id| Ok((id, limit?)));
    let (id, limit) = match checked {
        Ok((_, limit)) if !(1..=MAX_LIMIT).contains(&limit) => {
            return ApiError::invalid(format!("limit must be 1-{MAX_LIMIT}"))
                .into_response();
        }
        Ok(checked) => checked,
        Err(error) => return error.into_response(),
    };
    let found = blocking(&app, move |library| {
        let member = match library.choose(id, None) {
            Choice::Member(member) => member,
            other => return elsewhere(other, "similar", None),
        };
        let results = summaries(library, &library.similar(member, &[id], limit));
        Json(serde_json::json!({ "work": id, "results": results })).into_response()
    });
    loaded(found.await)
}

pub async fn author(State(app): Shared, Path(raw): Path<String>) -> Response {
    let id = match id_of("author id", &raw) {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    let found = blocking(&app, move |library| {
        let author = library.author(id).ok_or_else(|| ApiError::not_found("Author"))?;
        let works: Vec<u32> =
            author.works.iter().map(|member| library.unified_of(member.work)).collect();
        Ok(serde_json::json!({
            "id": id,
            "name": author.name,
            "born": (author.born > 0).then_some(author.born),
            "photo": photo_path(id, PHOTO),
            "works": works,
        }))
    });
    reply(found.await)
}

pub async fn series(State(app): Shared, Path(raw): Path<String>) -> Response {
    let id = match id_of("series id", &raw) {
        Ok(id) => id,
        Err(error) => return error.into_response(),
    };
    let found = blocking(&app, move |library| {
        let members = library.members(id);
        let series = members.iter().find_map(|&member| library.series(member));
        let series = series.ok_or_else(|| ApiError::not_found("Series"))?;
        let language =
            series.members.first().map(|&member| library.code(member).to_string());
        let works = series.members.iter().enumerate().map(|(index, member)| {
            SeriesEntry { work: library.unified_of(member.work), position: index + 1 }
        });
        Ok(serde_json::json!({
            "id": series.first,
            "name": series.name,
            "language": language,
            "works": works.collect::<Vec<_>>(),
        }))
    });
    reply(found.await)
}

pub async fn isbn(State(app): Shared, Path(raw): Path<String>) -> Response {
    let Some(key) = isbn_key(&raw) else {
        return ApiError::invalid("not a valid ISBN").into_response();
    };
    let found = blocking(&app, move |library| {
        let member = library.isbn(key).ok_or_else(|| ApiError::not_found("ISBN"))?;
        Ok(serde_json::json!({
            "isbn": isbn_of(key),
            "work": library.unified_of(member.work),
            "language": library.code(member),
        }))
    });
    reply(found.await)
}

fn suggestions(library: &Library, query: &str) -> Vec<Suggestion> {
    let hits = library.search(query, SUGGEST_WORKS);
    let words = tokenize(query);
    let mut authors: Vec<Suggestion> = Vec::new();
    for author in hits.iter().flat_map(|hit| &hit.books).flat_map(|book| &book.authors) {
        let name = tokenize(&author.name);
        let named =
            words.iter().all(|word| name.iter().any(|part| part.starts_with(word)));
        let known = authors.iter().any(|known| known.id == author.number);
        if named && !known && authors.len() < SUGGEST_AUTHORS {
            authors.push(Suggestion {
                kind: "author",
                id: author.number,
                language: String::new(),
                label: author.name.clone(),
                detail: "Author".to_string(),
            });
        }
    }
    let works = hits.iter().take(SUGGEST_WORKS).filter_map(|hit| {
        let (member, book) = hit_member(library, hit)?;
        let author = book.authors.first().map(|author| author.name.clone());
        let year = (book.year > 0).then(|| book.year.to_string());
        Some(Suggestion {
            kind: "work",
            id: hit.work,
            language: library.code(member).to_string(),
            label: book.title.clone(),
            detail: author.into_iter().chain(year).collect::<Vec<_>>().join(" · "),
        })
    });
    authors.into_iter().chain(works).collect()
}

pub async fn suggest(State(app): Shared, Query(params): Query<SearchParams>) -> Response {
    let query = params.q.trim_start().to_string();
    let found = blocking(&app, move |library| {
        let suggestions = suggestions(library, &query);
        Ok(serde_json::json!({ "query": query, "suggestions": suggestions }))
    });
    reply(found.await)
}

pub async fn languages(State(app): Shared) -> Response {
    let found = blocking(&app, |library| {
        let mut languages: Vec<LanguageCount> = library
            .catalogues
            .iter()
            .map(|catalogue| LanguageCount {
                code: catalogue.language.clone(),
                name: language_name(&catalogue.language),
                works: catalogue.work_count(),
            })
            .collect();
        languages.sort_by_key(|language| std::cmp::Reverse(language.works));
        Ok(serde_json::json!({ "languages": languages }))
    });
    reply(found.await)
}

pub async fn catalogue(
    State(app): Shared,
    Path(code): Path<String>,
    Query(params): Query<SearchParams>,
) -> Response {
    let checked = paging(params.page.as_deref(), params.limit.as_deref())
        .and_then(|paging| Ok((paging, language_of(Some(code))?)));
    let ((page, limit), code) = match checked {
        Ok((paging, Some(code))) => (paging, code),
        Ok(_) => return ApiError::invalid("language").into_response(),
        Err(error) => return error.into_response(),
    };
    let found = blocking(&app, move |library| {
        let language =
            library.language(&code).ok_or_else(|| ApiError::not_found("Language"))?;
        let popular = library.popular(language);
        let shown = popular.iter().skip((page - 1) * limit).take(limit);
        let shown: Vec<Member> = shown.map(|&work| Member { language, work }).collect();
        Ok(serde_json::json!({
            "language": code,
            "total": library.catalogues[language].work_count(),
            "page": page,
            "limit": limit,
            "more": page * limit < popular.len(),
            "results": summaries(library, &shown),
        }))
    });
    reply(found.await)
}

pub async fn fallback() -> ApiError {
    ApiError::not_found("Endpoint")
}
