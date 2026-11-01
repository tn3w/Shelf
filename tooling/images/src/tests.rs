use crate::images::Images;
use crate::index::{DNB, Index, OPEN_LIBRARY, fixture};
use crate::{App, router};
use axum::Router;
use axum::body::Body;
use axum::http::{Request, Response, StatusCode, header};
use http_body_util::BodyExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::RwLock;
use std::sync::atomic::{AtomicU32, Ordering};
use tower::ServiceExt;

const RELEASE: &str = "2026-11-05";
const COVER: &str = "https://covers.openlibrary.org/b/id/300-L.jpg";

static SCRATCH: AtomicU32 = AtomicU32::new(0);

fn scratch() -> PathBuf {
    let number = SCRATCH.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!("shelf-images-{}-{number}", std::process::id()))
}

fn app(directory: &Path) -> Router {
    let file = fixture::file(
        RELEASE,
        &[("en", &[(2, OPEN_LIBRARY, 300)]), ("de", &[(2, DNB, 5)])],
    );
    let images = Images::new(directory.to_path_buf());
    let index = Index::parse(&file).map(Arc::new);
    router(Arc::new(App {
        index: RwLock::new(index),
        images: Arc::new(images),
    }))
}

fn cache(directory: &Path, url: &str, width: u32, bytes: &[u8]) {
    let key = crate::images::key(url);
    let folder = directory.join(&key[..2]);
    std::fs::create_dir_all(&folder).unwrap();
    std::fs::write(folder.join(format!("{key}.{width}.avif")), bytes).unwrap();
}

async fn get(app: &Router, path: &str) -> Response<Body> {
    app.clone()
        .oneshot(Request::get(path).body(Body::empty()).unwrap())
        .await
        .unwrap()
}

fn header_of(response: &Response<Body>, name: header::HeaderName) -> &str {
    response
        .headers()
        .get(name)
        .map_or("", |value| value.to_str().unwrap())
}

#[tokio::test]
async fn cached_covers_are_immutable_avif() {
    let directory = scratch();
    cache(&directory, COVER, 320, b"avif bytes");
    let response = get(&app(&directory), "/b/2026-10-31/en/2/320.avif").await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(header_of(&response, header::CONTENT_TYPE), "image/avif");
    assert_eq!(
        header_of(&response, header::CACHE_CONTROL),
        "public, max-age=31536000, immutable"
    );
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(&body[..], b"avif bytes");
    let _ = std::fs::remove_dir_all(directory);
}

#[tokio::test]
async fn books_without_covers_are_cacheable_misses() {
    let directory = scratch();
    let app = app(&directory);
    for path in ["/b/2026-11-05/en/3/320.avif", "/b/2026-11-05/xx/2/320.avif"] {
        let response = get(&app, path).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
        assert_eq!(
            header_of(&response, header::CACHE_CONTROL),
            "public, max-age=86400, s-maxage=604800"
        );
    }
    let _ = std::fs::remove_dir_all(directory);
}

#[tokio::test]
async fn malformed_addresses_are_rejected_without_fetching() {
    let directory = scratch();
    let app = app(&directory);
    for path in [
        "/b/2026-11-05/en/02/320.avif",
        "/b/2026-11-05/en/2/321.avif",
        "/b/2026-11-05/en/2/320.webp",
        "/b/latest/en/2/320.avif",
        "/a/0/96.avif",
        "/a/1000000000/96.avif",
        "/a/7/320.avif",
        "/",
    ] {
        let response = get(&app, path).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
        assert_eq!(
            header_of(&response, header::CACHE_CONTROL),
            "public, max-age=3600, s-maxage=86400"
        );
    }
    let _ = std::fs::remove_dir_all(directory);
}

#[tokio::test]
async fn without_an_index_nothing_is_cached() {
    let directory = scratch();
    let app = router(Arc::new(App {
        index: RwLock::new(None),
        images: Arc::new(Images::new(directory.clone())),
    }));
    let response = get(&app, "/b/2026-11-05/en/2/320.avif").await;
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(header_of(&response, header::CACHE_CONTROL), "no-store");
    assert_eq!(
        get(&app, "/health").await.status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
}

#[tokio::test]
async fn health_reports_the_release() {
    let directory = scratch();
    let response = get(&app(&directory), "/health").await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(&body[..], RELEASE.as_bytes());
}
