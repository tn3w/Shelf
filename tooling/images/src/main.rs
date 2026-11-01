mod images;
mod index;
#[cfg(test)]
mod tests;

use axum::Router;
use axum::extract::{Path, State};
use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use images::{Images, Outcome, Shape};
use index::{Index, author_url, is_release};
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::time::Duration;

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

pub const SYNTHETIC: u32 = 1_000_000_000;
const REFRESH: Duration = Duration::from_secs(1800);
const FOUND: &str = "public, max-age=31536000, immutable";
const MISSING: &str = "public, max-age=86400, s-maxage=604800";
const INVALID: &str = "public, max-age=3600, s-maxage=86400";

pub struct App {
    index: RwLock<Option<Arc<Index>>>,
    images: Arc<Images>,
}

pub fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/b/{release}/{language}/{work}/{file}", get(book))
        .route("/a/{author}/{file}", get(author))
        .route("/health", get(health))
        .fallback(|| async { cached(StatusCode::NOT_FOUND, INVALID) })
        .with_state(app)
}

fn cached(status: StatusCode, cache: &'static str) -> Response {
    let mut response = status.into_response();
    let value = HeaderValue::from_static(cache);
    response.headers_mut().insert(header::CACHE_CONTROL, value);
    response
}

fn number(text: &str) -> Option<u32> {
    let value: u32 = text.parse().ok()?;
    (value.to_string() == text).then_some(value)
}

fn width(file: &str, shape: Shape) -> Option<u32> {
    let width = number(file.strip_suffix(".avif")?)?;
    shape.widths().contains(&width).then_some(width)
}

async fn image(app: &App, url: String, shape: Shape, width: u32) -> Response {
    match app.images.get(&url, shape, width).await {
        Outcome::Found(bytes) => {
            let mut response = ([(header::CONTENT_TYPE, "image/avif")], bytes).into_response();
            let value = HeaderValue::from_static(FOUND);
            response.headers_mut().insert(header::CACHE_CONTROL, value);
            response
        }
        Outcome::Missing => cached(StatusCode::NOT_FOUND, MISSING),
        Outcome::Unavailable => cached(StatusCode::SERVICE_UNAVAILABLE, "no-store"),
    }
}

async fn book(
    State(app): State<Arc<App>>,
    Path((release, language, work, file)): Path<(String, String, String, String)>,
) -> Response {
    let (Some(work), Some(width)) = (number(&work), width(&file, Shape::Book)) else {
        return cached(StatusCode::NOT_FOUND, INVALID);
    };
    if !is_release(&release) {
        return cached(StatusCode::NOT_FOUND, INVALID);
    }
    let Some(index) = app.index.read().expect("index").clone() else {
        return cached(StatusCode::SERVICE_UNAVAILABLE, "no-store");
    };
    let Some(url) = index.cover(&language, work).and_then(|cover| cover.url()) else {
        return cached(StatusCode::NOT_FOUND, MISSING);
    };
    image(&app, url, Shape::Book, width).await
}

async fn author(
    State(app): State<Arc<App>>,
    Path((author, file)): Path<(String, String)>,
) -> Response {
    let (Some(author), Some(width)) = (number(&author), width(&file, Shape::Author)) else {
        return cached(StatusCode::NOT_FOUND, INVALID);
    };
    if author == 0 || author >= SYNTHETIC {
        return cached(StatusCode::NOT_FOUND, INVALID);
    }
    image(&app, author_url(author), Shape::Author, width).await
}

async fn health(State(app): State<Arc<App>>) -> Response {
    match app.index.read().expect("index").as_ref() {
        Some(index) => index.release.clone().into_response(),
        None => StatusCode::SERVICE_UNAVAILABLE.into_response(),
    }
}

async fn keep_current(app: Arc<App>) {
    loop {
        let current = app.index.read().expect("index").clone();
        match index::newer(current.as_deref()).await {
            Ok(Some(index)) => *app.index.write().expect("index") = Some(Arc::new(index)),
            Ok(None) => {}
            Err(error) => eprintln!("release check failed: {error}"),
        }
        tokio::time::sleep(REFRESH).await;
    }
}

async fn shutdown() {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("signal handler");
    tokio::select! {
        _ = terminate.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
}

#[tokio::main]
async fn main() {
    let data = std::env::var("SHELF_IMAGES_DATA").unwrap_or_else(|_| "data".to_string());
    let images = Images::new(PathBuf::from(data));
    let app = Arc::new(App {
        index: RwLock::new(None),
        images: Arc::new(images),
    });
    tokio::spawn(keep_current(app.clone()));
    let socket = listenfd::ListenFd::from_env()
        .take_unix_listener(0)
        .expect("socket")
        .expect("systemd socket on fd 3");
    socket.set_nonblocking(true).expect("nonblocking socket");
    let listener = tokio::net::UnixListener::from_std(socket).expect("unix listener");
    axum::serve(listener, router(app))
        .with_graceful_shutdown(shutdown())
        .await
        .expect("serve");
}
