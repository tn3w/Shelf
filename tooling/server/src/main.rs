mod http;
mod images;
mod library;
mod pages;
mod release;
#[cfg(test)]
mod tests;
mod v1;

use axum::Router;
use axum::extract::{Path, Query, State};
use axum::http::{StatusCode, Uri};
use axum::middleware::{from_fn, from_fn_with_state};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use builder::libraries::SYNTHETIC;
use http::{NO_STORE, with_cache};
use images::{Images, Outcome, Shape, author_url, cover_url};
use library::Library;
use pow::{DEFAULT_DIFFICULTY, DEFAULT_REQUESTS_PER_MINUTE, Gate, Pages, Pow};
use sha2::{Digest, Sha256};
use std::path::{Path as FilePath, PathBuf};
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::sync::Semaphore;

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

pub mod assets {
    pub struct Asset {
        pub name: &'static str,
        pub content_type: &'static str,
        pub plain: &'static [u8],
        pub brotli: &'static [u8],
    }

    include!(concat!(env!("OUT_DIR"), "/assets.rs"));
}

const REFRESH: Duration = Duration::from_secs(1800);
const CONCURRENT_QUERIES: usize = 16;
const FOUND: &str = "public, max-age=31536000, immutable";
const MISSING: &str = "public, max-age=86400, s-maxage=604800";
const INVALID: &str = "public, max-age=3600, s-maxage=86400";

pub struct App {
    library: RwLock<Option<Arc<Library>>>,
    images: Images,
    queries: Semaphore,
}

fn short_hash(text: &str) -> String {
    Sha256::digest(text)[..4].iter().map(|byte| format!("{byte:02x}")).collect()
}

impl App {
    pub fn new(library: Option<Library>, images: Images) -> App {
        App {
            library: RwLock::new(library.map(Arc::new)),
            images,
            queries: Semaphore::new(CONCURRENT_QUERIES),
        }
    }

    pub fn library(&self) -> Option<Arc<Library>> {
        self.library.read().expect("library").clone()
    }

    fn replace(&self, library: Library) {
        *self.library.write().expect("library") = Some(Arc::new(library));
    }

    pub fn etag(&self, uri: &Uri) -> Option<String> {
        let release = self.library()?.release.clone();
        let path = uri.path_and_query().map_or("", |path| path.as_str());
        let version = short_hash(&format!("{}{release}", assets::BUILD));
        Some(format!("\"{version}{}\"", short_hash(path)))
    }
}

/// Runs `task` on the blocking pool, `None` until a catalogue is loaded.
pub async fn blocking<T: Send + 'static>(
    app: &App,
    task: impl FnOnce(&Library) -> T + Send + 'static,
) -> Option<T> {
    let library = app.library()?;
    let _slot = app.queries.acquire().await.expect("query semaphore");
    tokio::task::spawn_blocking(move || task(&library)).await.ok()
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
            let headers = [(axum::http::header::CONTENT_TYPE, "image/avif")];
            with_cache((headers, bytes).into_response(), FOUND)
        }
        Outcome::Missing => with_cache(StatusCode::NOT_FOUND.into_response(), MISSING),
        Outcome::Unavailable => {
            with_cache(StatusCode::SERVICE_UNAVAILABLE.into_response(), NO_STORE)
        }
    }
}

fn invalid() -> Response {
    with_cache(StatusCode::NOT_FOUND.into_response(), INVALID)
}

async fn book_image(
    State(app): State<Arc<App>>,
    Path((release, language, work, file)): Path<(String, String, String, String)>,
) -> Response {
    let (Some(work), Some(width)) = (number(&work), width(&file, Shape::Book)) else {
        return invalid();
    };
    if !release::is_release(&release) {
        return invalid();
    }
    let Some(library) = app.library() else {
        return with_cache(StatusCode::SERVICE_UNAVAILABLE.into_response(), NO_STORE);
    };
    let Some(url) = library.cover(&language, work).and_then(cover_url) else {
        return with_cache(StatusCode::NOT_FOUND.into_response(), MISSING);
    };
    image(&app, url, Shape::Book, width).await
}

/// `/a/{author}/{96,192}.avif` is the photo, `/a/{author}/{language}` the author page.
async fn author_file(
    State(app): State<Arc<App>>,
    Path((author, file)): Path<(String, String)>,
    Query(params): Query<pages::ListParams>,
) -> Response {
    if !file.ends_with(".avif") {
        return pages::author_in(app, author, file, params).await;
    }
    let (Some(author), Some(width)) = (number(&author), width(&file, Shape::Author))
    else {
        return invalid();
    };
    if author == 0 || author >= SYNTHETIC {
        return invalid();
    }
    image(&app, author_url(author), Shape::Author, width).await
}

async fn health(State(app): State<Arc<App>>) -> Response {
    let response = match app.library() {
        Some(library) => library.release.clone().into_response(),
        None => StatusCode::SERVICE_UNAVAILABLE.into_response(),
    };
    with_cache(response, NO_STORE)
}

async fn missing(uri: Uri) -> Response {
    if uri.path().starts_with("/api/") {
        return v1::fallback().await.into_response();
    }
    pages::not_found()
}

pub fn gate(pow: Pow) -> Arc<Gate> {
    let pages = Pages { title: "Shelf", style: assets::STYLE, script: assets::SCRIPT };
    Gate::new(pow, pages)
}

pub fn router(app: Arc<App>, gate: Arc<Gate>) -> Router {
    let content = Router::new()
        .route("/", get(pages::home))
        .route("/s", get(pages::search))
        .route("/b/{work}", get(pages::book))
        .route("/b/{work}/{language}", get(pages::book_in))
        .route("/b/{release}/{language}/{work}/{file}", get(book_image))
        .route("/a/{author}", get(pages::author))
        .route("/a/{author}/{file}", get(author_file))
        .route("/series/{id}", get(pages::series))
        .route("/series/{id}/{language}", get(pages::series_in))
        .route("/l/{language}", get(pages::catalogue))
        .route("/isbn/{isbn}", get(pages::isbn))
        .route("/random", get(pages::random))
        .route("/api/v1/search", get(v1::search))
        .route("/api/v1/works/{id}", get(v1::work))
        .route("/api/v1/authors/{id}", get(v1::author))
        .route("/api/v1/series/{id}", get(v1::series))
        .route("/api/v1/isbn/{isbn}", get(v1::isbn))
        .route("/api/v1/suggest", get(v1::suggest))
        .route("/api/v1/similar/{id}", get(v1::similar))
        .route("/api/v1/languages", get(v1::languages))
        .route("/api/v1/catalogue/{language}", get(v1::catalogue))
        .fallback(missing)
        .layer(from_fn_with_state(app.clone(), http::cached))
        .layer(from_fn_with_state(gate.clone(), pow::guard))
        .layer(from_fn(http::canonical));
    let open = Router::new()
        .route("/health", get(health))
        .route("/s/{name}", get(http::asset))
        .route("/robots.txt", get(http::robots));
    content
        .merge(open)
        .with_state(app)
        .merge(pow::routes(gate))
        .layer(from_fn(http::harden))
}

async fn open(directory: PathBuf) -> Result<Library, String> {
    let opened = tokio::task::spawn_blocking(move || Library::open(&directory)).await;
    opened.map_err(|error| error.to_string())?
}

async fn refresh(app: &App, data: &FilePath) -> Result<(), String> {
    let client = release::client()?;
    let current = app.library().map(|library| library.release.clone());
    let Some(published) = release::newer(&client, current.as_deref()).await? else {
        return Ok(());
    };
    let (installed, next) = (data.join("catalogue"), data.join("next"));
    release::mirror(&client, &published, &installed, &next).await?;
    app.replace(open(next.clone()).await?);
    let _ = tokio::fs::remove_dir_all(&installed).await;
    tokio::fs::rename(&next, &installed).await.map_err(|error| error.to_string())?;
    eprintln!("serving catalogue {}", published.release);
    Ok(())
}

async fn keep_current(app: Arc<App>, data: PathBuf) {
    let installed = data.join("catalogue");
    if installed.join("manifest.json").exists() {
        match open(installed).await {
            Ok(library) => {
                eprintln!("serving installed catalogue {}", library.release);
                app.replace(library);
            }
            Err(error) => eprintln!("installed catalogue unusable: {error}"),
        }
    }
    loop {
        if let Err(error) = refresh(&app, &data).await {
            eprintln!("release check failed: {error}");
        }
        tokio::time::sleep(REFRESH).await;
    }
}

fn variable<T: std::str::FromStr>(name: &str, default: T) -> T {
    std::env::var(name).ok().and_then(|value| value.parse().ok()).unwrap_or(default)
}

fn secret() -> Vec<u8> {
    let directory =
        std::env::var("CREDENTIALS_DIRECTORY").expect("$CREDENTIALS_DIRECTORY");
    let secret = std::fs::read(PathBuf::from(directory).join("secrets")).expect("secret");
    assert!(secret.len() >= 16, "secret must have at least 16 bytes");
    secret
}

async fn shutdown() {
    let kind = tokio::signal::unix::SignalKind::terminate();
    let mut terminate = tokio::signal::unix::signal(kind).expect("signal handler");
    tokio::select! {
        _ = terminate.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
}

#[tokio::main]
async fn main() {
    let pow = Pow::new(
        secret(),
        variable("SHELF_DIFFICULTY", DEFAULT_DIFFICULTY),
        variable("SHELF_RATE", DEFAULT_REQUESTS_PER_MINUTE),
    );
    let data = PathBuf::from(variable("SHELF_DATA", "data".to_string()));
    let app = Arc::new(App::new(None, Images::new(data.join("images"))));
    tokio::spawn(keep_current(app.clone(), data));
    let router = router(app, gate(pow));
    if let Ok(address) = std::env::var("SHELF_LISTEN") {
        let listener = tokio::net::TcpListener::bind(&address).await.expect("bind");
        eprintln!("listening on http://{address}");
        let serving = axum::serve(listener, router).with_graceful_shutdown(shutdown());
        return serving.await.expect("serve");
    }
    let socket = listenfd::ListenFd::from_env()
        .take_unix_listener(0)
        .expect("socket")
        .expect("systemd socket on fd 3");
    socket.set_nonblocking(true).expect("nonblocking socket");
    let listener = tokio::net::UnixListener::from_std(socket).expect("unix listener");
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown())
        .await
        .expect("serve");
}
