use crate::App;
use crate::assets::ASSETS;
use axum::Json;
use axum::extract::{Path, Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use std::sync::Arc;

pub const NO_STORE: &str = "no-store";
pub const CACHE_LONG: &str = "public, max-age=600, s-maxage=86400";
pub const CACHE_SHORT: &str = "public, max-age=60, s-maxage=3600";
pub const CACHE_DAILY: &str = "public, max-age=600, s-maxage=3600";
const CACHE_IMMUTABLE: &str = "public, max-age=31536000, immutable";
const POLICY: &str = "default-src 'none'; style-src 'self'; script-src 'self'; \
img-src 'self' data:; connect-src 'self'; worker-src 'self'; base-uri 'none'; \
form-action 'self'; frame-ancestors 'none'";
const QUERY_KEYS: [&str; 5] = ["q", "p", "page", "limit", "language"];
const ROBOTS: &str = "User-agent: *\nDisallow: /api/\nDisallow: /s?\nDisallow: /random\n";

#[derive(Serialize)]
struct Detail {
    code: &'static str,
    message: String,
}

#[derive(Serialize)]
pub struct ApiError {
    #[serde(skip)]
    status: StatusCode,
    error: Detail,
}

impl ApiError {
    pub fn new(
        status: StatusCode,
        code: &'static str,
        message: impl Into<String>,
    ) -> ApiError {
        ApiError { status, error: Detail { code, message: message.into() } }
    }

    pub fn not_found(what: &str) -> ApiError {
        ApiError::new(StatusCode::NOT_FOUND, "not_found", format!("{what} not found"))
    }

    pub fn invalid(message: impl Into<String>) -> ApiError {
        ApiError::new(StatusCode::BAD_REQUEST, "invalid_parameter", message)
    }

    pub fn unavailable() -> ApiError {
        let message = "Catalogue is loading";
        ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "unavailable", message)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(self)).into_response()
    }
}

pub fn is_image(path: &str) -> bool {
    path.ends_with(".avif") && (path.starts_with("/b/") || path.starts_with("/a/"))
}

fn canonical_path(path: &str) -> String {
    if path.starts_with("/isbn/") {
        return path.to_string();
    }
    let segments = path.split('/').map(|segment| {
        let padded = segment.len() > 1
            && segment.starts_with('0')
            && segment.bytes().all(|byte| byte.is_ascii_digit());
        let trimmed = segment.trim_start_matches('0');
        match padded {
            true if trimmed.is_empty() => "0",
            true => trimmed,
            false => segment,
        }
    });
    segments.collect::<Vec<_>>().join("/")
}

fn canonical_query(query: &str) -> String {
    let known = |pair: &&str| QUERY_KEYS.contains(&pair.split('=').next().unwrap_or(""));
    query.split('&').filter(known).collect::<Vec<_>>().join("&")
}

pub async fn canonical(request: Request, next: Next) -> Response {
    let uri = request.uri();
    let path = canonical_path(uri.path());
    let query = canonical_query(uri.query().unwrap_or_default());
    let target = if query.is_empty() { path } else { format!("{path}?{query}") };
    if uri.path_and_query().is_some_and(|original| original.as_str() == target) {
        return next.run(request).await;
    }
    let mut response =
        (StatusCode::MOVED_PERMANENTLY, [(header::LOCATION, target)]).into_response();
    label(&mut response, CACHE_SHORT, None);
    response
}

pub async fn harden(request: Request, next: Next) -> Response {
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    headers.insert(header::CONTENT_SECURITY_POLICY, HeaderValue::from_static(POLICY));
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    headers.insert(header::REFERRER_POLICY, HeaderValue::from_static("no-referrer"));
    response
}

pub async fn cached(
    State(app): State<Arc<App>>,
    request: Request,
    next: Next,
) -> Response {
    let path = request.uri().path();
    if !matches!(*request.method(), Method::GET | Method::HEAD) || is_image(path) {
        return next.run(request).await;
    }
    let etag = app.etag(request.uri());
    if etag.as_ref().is_some_and(|etag| matches_etag(request.headers(), etag)) {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        label(&mut response, CACHE_LONG, etag.as_deref());
        return response;
    }
    let mut response = next.run(request).await;
    if response.headers().contains_key(header::CACHE_CONTROL) {
        return response;
    }
    let status = response.status();
    match status {
        StatusCode::OK => label(&mut response, CACHE_LONG, etag.as_deref()),
        _ if status.is_redirection() || status.is_client_error() => {
            label(&mut response, CACHE_SHORT, None)
        }
        _ => label(&mut response, NO_STORE, None),
    }
    response
}

fn matches_etag(headers: &HeaderMap, etag: &str) -> bool {
    let Some(value) =
        headers.get(header::IF_NONE_MATCH).and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    value.trim() == "*"
        || value
            .split(',')
            .any(|candidate| candidate.trim().trim_start_matches("W/") == etag)
}

pub fn label(response: &mut Response, policy: &'static str, etag: Option<&str>) {
    let headers = response.headers_mut();
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static(policy));
    if let Some(value) = etag.and_then(|etag| HeaderValue::from_str(etag).ok()) {
        headers.insert(header::ETAG, value);
    }
}

pub fn with_cache(mut response: Response, policy: &'static str) -> Response {
    label(&mut response, policy, None);
    response
}

pub async fn asset(Path(name): Path<String>, headers: HeaderMap) -> Response {
    let Some(asset) = ASSETS.iter().find(|asset| asset.name == name) else {
        return with_cache(StatusCode::NOT_FOUND.into_response(), CACHE_SHORT);
    };
    let accepts_brotli = headers
        .get(header::ACCEPT_ENCODING)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.split(',').any(|part| part.trim().starts_with("br")));
    let (body, encoding) =
        if accepts_brotli { (asset.brotli, Some("br")) } else { (asset.plain, None) };
    let mut response = Response::new(axum::body::Body::from(body));
    let headers = response.headers_mut();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(asset.content_type));
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static(CACHE_IMMUTABLE));
    headers.insert(header::VARY, HeaderValue::from_static("accept-encoding"));
    if let Some(encoding) = encoding {
        headers.insert(header::CONTENT_ENCODING, HeaderValue::from_static(encoding));
    }
    response
}

pub async fn robots() -> Response {
    with_cache(ROBOTS.into_response(), CACHE_LONG)
}
