use crate::{Check, Kind, Pow, Puzzle, SCRYPT_BLOCK_SIZE, SCRYPT_LOG_COST, SOLUTIONS};
use askama::Template;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Form, Query, Request, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, Uri, header};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Redirect, Response};
use axum::routing::get;
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::json;
use std::net::IpAddr;
use std::sync::Arc;

const NO_STORE: &str = "no-store";
const BODY_LIMIT_BYTES: usize = 1024;
const MAX_RETURN_BYTES: usize = 512;
const REQUIRED: &str =
    "Solve the proof of work at /api/pow and send the token as a Bearer token";

pub struct Pages {
    pub title: &'static str,
    pub style: &'static str,
    pub script: &'static str,
}

pub struct Gate {
    pub pow: Pow,
    pages: Pages,
}

#[derive(Template)]
#[template(path = "challenge.html")]
struct ChallengePage<'a> {
    pages: &'a Pages,
    back: String,
}

#[derive(Template)]
#[template(path = "puzzle.html")]
struct PuzzlePage<'a> {
    pages: &'a Pages,
    note: &'a str,
    back: &'a str,
    puzzle: Puzzle,
}

impl Gate {
    pub fn new(pow: Pow, pages: Pages) -> Arc<Gate> {
        Arc::new(Gate { pow, pages })
    }

    fn challenge_page(&self, uri: &Uri) -> Response {
        let back = encode(uri.path_and_query().map_or("/", |path| path.as_str()));
        page(StatusCode::UNAUTHORIZED, ChallengePage { pages: &self.pages, back })
    }

    fn puzzle_page(&self, status: StatusCode, back: &str, note: &str) -> Response {
        let puzzle = self.pow.puzzle();
        page(status, PuzzlePage { pages: &self.pages, note, back: local(back), puzzle })
    }
}

fn page(status: StatusCode, template: impl Template) -> Response {
    uncached(status, Html(template.render().unwrap_or_default()))
}

pub fn routes(gate: Arc<Gate>) -> Router {
    Router::new()
        .route("/api/pow", get(challenge).post(solve))
        .route("/pow", get(puzzle).post(answer))
        .layer(DefaultBodyLimit::max(BODY_LIMIT_BYTES))
        .with_state(gate)
}

pub async fn guard(
    State(gate): State<Arc<Gate>>,
    request: Request,
    next: Next,
) -> Response {
    let client = client(request.headers());
    match token(request.headers()).map(|token| gate.pow.check(&token, &client)) {
        Some(Check::Valid) => next.run(request).await,
        Some(Check::Limited) => limited(),
        _ if request.uri().path().starts_with("/api/") => required(&gate),
        _ => gate.challenge_page(request.uri()),
    }
}

fn token(headers: &HeaderMap) -> Option<String> {
    let bearer = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    let cookie = headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(|value| value.split(';'))
        .find_map(|part| part.trim().strip_prefix("t="));
    bearer.or(cookie).map(str::to_string)
}

fn network(headers: &HeaderMap) -> Vec<u8> {
    let address = headers.get("cf-connecting-ip").and_then(|value| value.to_str().ok());
    match address.and_then(|address| address.parse().ok()) {
        Some(IpAddr::V4(address)) => address.octets().to_vec(),
        Some(IpAddr::V6(address)) => address.octets()[..8].to_vec(),
        None => Vec::new(),
    }
}

fn client(headers: &HeaderMap) -> Vec<u8> {
    let agent = headers.get(header::USER_AGENT).map_or(&[][..], |value| value.as_bytes());
    [&network(headers)[..], b"\0", agent].concat()
}

fn uncached(status: StatusCode, body: impl IntoResponse) -> Response {
    (status, [(header::CACHE_CONTROL, NO_STORE)], body).into_response()
}

fn error(status: StatusCode, code: &str, message: &str) -> Response {
    uncached(status, Json(json!({ "error": { "code": code, "message": message } })))
}

fn limited() -> Response {
    let mut response =
        error(StatusCode::TOO_MANY_REQUESTS, "rate_limited", "Too many requests");
    response.headers_mut().insert(header::RETRY_AFTER, HeaderValue::from_static("30"));
    response
}

fn required(gate: &Gate) -> Response {
    let body = json!({
        "error": {
            "code": "pow_required",
            "message": REQUIRED,
        },
        "pow": {
            "url": "/api/pow",
            "difficulty": gate.pow.difficulty(),
            "solutions": SOLUTIONS,
            "scrypt": { "n": 1 << SCRYPT_LOG_COST, "r": SCRYPT_BLOCK_SIZE, "p": 1 },
        },
    });
    uncached(StatusCode::UNAUTHORIZED, Json(body))
}

fn with_cookie(mut response: Response, token: &str, kind: Kind) -> Response {
    let seconds = kind.seconds();
    let cookie =
        format!("t={token}; Path=/; Max-Age={seconds}; Secure; HttpOnly; SameSite=Lax");
    if let Ok(value) = HeaderValue::from_str(&cookie) {
        response.headers_mut().insert(header::SET_COOKIE, value);
    }
    response
}

fn encode(text: &str) -> String {
    text.bytes()
        .map(|byte| match byte {
            b'A'..=b'Z'
            | b'a'..=b'z'
            | b'0'..=b'9'
            | b'-'
            | b'.'
            | b'_'
            | b'~'
            | b'/' => char::from(byte).to_string(),
            _ => format!("%{byte:02X}"),
        })
        .collect()
}

fn local(path: &str) -> &str {
    let bytes = path.as_bytes();
    let is_local = bytes.first() == Some(&b'/')
        && !matches!(bytes.get(1), Some(b'/' | b'\\'))
        && bytes.len() <= MAX_RETURN_BYTES
        && bytes.iter().all(|&byte| byte.is_ascii_graphic() && byte != b'\\');
    if is_local { path } else { "/" }
}

async fn challenge(State(gate): State<Arc<Gate>>) -> Response {
    let (challenge, difficulty) = gate.pow.challenge();
    let (cost, block) = (1 << SCRYPT_LOG_COST, SCRYPT_BLOCK_SIZE);
    let body =
        json!({ "c": challenge, "d": difficulty, "k": SOLUTIONS, "n": cost, "r": block });
    uncached(StatusCode::OK, Json(body))
}

#[derive(Deserialize)]
struct Solution {
    c: String,
    n: Vec<u32>,
}

async fn solve(
    State(gate): State<Arc<Gate>>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if !gate.pow.attempt(&network(&headers)) {
        return limited();
    }
    let client = client(&headers);
    let verify = move || {
        let solution = serde_json::from_slice::<Solution>(&body).ok()?;
        gate.pow.redeem(&solution.c, &solution.n, &client)
    };
    let token = tokio::task::spawn_blocking(verify).await.ok().flatten();
    let Some(token) = token else {
        return error(
            StatusCode::FORBIDDEN,
            "invalid_solution",
            "Invalid or reused solution",
        );
    };
    with_cookie(uncached(StatusCode::OK, Json(json!({ "t": token }))), &token, Kind::Work)
}

#[derive(Deserialize)]
struct Back {
    #[serde(default)]
    r: String,
}

async fn puzzle(State(gate): State<Arc<Gate>>, Query(back): Query<Back>) -> Response {
    gate.puzzle_page(StatusCode::OK, &back.r, "")
}

#[derive(Deserialize)]
struct Answer {
    c: String,
    a: String,
    #[serde(default)]
    r: String,
}

async fn answer(
    State(gate): State<Arc<Gate>>,
    headers: HeaderMap,
    Form(answer): Form<Answer>,
) -> Response {
    if !gate.pow.attempt(&network(&headers)) {
        let note = "Too many attempts, wait a minute. ";
        return gate.puzzle_page(StatusCode::TOO_MANY_REQUESTS, &answer.r, note);
    }
    let Some(token) = gate.pow.answer(&answer.c, &answer.a, &client(&headers)) else {
        let note = "Wrong, expired or too fast. ";
        return gate.puzzle_page(StatusCode::FORBIDDEN, &answer.r, note);
    };
    let redirect = Redirect::to(local(&answer.r)).into_response();
    with_cookie(uncached(StatusCode::SEE_OTHER, redirect), &token, Kind::Puzzle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn return_paths_stay_local() {
        assert_eq!(local("/b/1?p=2"), "/b/1?p=2");
        assert_eq!(local("//evil.example"), "/");
        assert_eq!(local("/\\evil.example"), "/");
        assert_eq!(local("https://evil.example"), "/");
        assert_eq!(local("/ä"), "/");
        assert_eq!(local("/a\\b"), "/");
        assert_eq!(local(""), "/");
        assert_eq!(local(&format!("/{}", "a".repeat(MAX_RETURN_BYTES))), "/");
        assert_eq!(encode("/s?q=a b"), "/s%3Fq%3Da%20b");
    }

    #[test]
    fn puzzle_page_escapes_the_return_path() {
        let pow = Pow::new(b"secret".to_vec(), 4, 10);
        let pages = Pages { title: "T", style: "/s/a.css", script: "/s/a.js" };
        let gate = Gate::new(pow, pages);
        let html = PuzzlePage {
            pages: &gate.pages,
            note: "",
            back: "/s?q=\"><script>",
            puzzle: gate.pow.puzzle(),
        };
        let html = html.render().expect("render");
        assert!(html.contains("&#34;&#62;&#60;script&#62;") || html.contains("&quot;"));
        assert!(!html.contains("\"><script>") && html.contains("<svg"));
    }
}
