use crate::images::{Images, key};
use crate::library::{Library, isbn_key};
use crate::{App, assets, gate, router};
use axum::Router;
use axum::body::Body;
use axum::http::{Request, Response, StatusCode, header};
use builder::dumps::Authors;
use builder::reader::isbn_of;
use builder::release::{Published, Series, write_manifest};
use builder::segment::{self, Meta, Popularity, Work};
use builder::sidecar::{self, EXTRAS, Edition, Extras, Person};
use builder::works::Works;
use builder::{LANGUAGE_COUNT, LANGUAGES};
use http_body_util::BodyExt;
use pow::{DEFAULT_REQUESTS_PER_MINUTE, Pow, SOLUTIONS};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, OnceLock};
use tower::ServiceExt;

const RELEASE: &str = "2026-11-05";
const DIFFICULTY: u32 = 1;
const STONE_ISBN: &str = "9780747532699";
const GERMAN_STONE: u32 = 2_000_010;
const GERMAN_PRISONER: u32 = 2_000_011;
const SWEDISH_LATER: u32 = 2_000_020;
const ROWLING_TWIN: u32 = 103;
const COVER_URL: &str = "https://covers.openlibrary.org/b/id/300-L.jpg";
const GERMAN_COVER_KEY: u32 = 355_155_167;

static SCRATCH: AtomicU32 = AtomicU32::new(0);

fn scratch(name: &str) -> PathBuf {
    let number = SCRATCH.fetch_add(1, Ordering::Relaxed);
    let directory = std::env::temp_dir()
        .join(format!("shelf-server-{}-{number}-{name}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    directory
}

fn work<'a>(id: u32, title: &'a str, authors: &'a [u32], tags: &'a [u8]) -> Work<'a> {
    Work {
        id,
        title,
        subtitle: "",
        alternate: "",
        authors,
        year: 0,
        cover: 0,
        tags,
        series: None,
        description: "",
        translated: false,
        isbns: &[],
        library_cover: None,
    }
}

fn popularity(id: u32, score: f32) -> Popularity {
    Popularity { id, score, readers: 0, ratings: 0, mean_rating: 0.0, editions: 1 }
}

fn tag(slug: &str) -> u8 {
    builder::tags::tag(slug)
}

fn extras(work: u32, publisher: &str) -> Extras {
    Extras {
        work,
        descriptions: vec![("fr".to_string(), "Un garçon apprend la magie.".to_string())],
        identifiers: vec!["ol:OL82563W".to_string(), "wikidata:Q43361".to_string()],
        titles: vec![("ja".to_string(), "ハリー・ポッターと賢者の石".to_string())],
        translators: vec!["Klaus Fritz".to_string()],
        original_title: "Harry Potter and the Philosopher's Stone".to_string(),
        editions: vec![Edition {
            year: 1997,
            publisher: publisher.to_string(),
            isbn: STONE_ISBN.to_string(),
            format: 1,
            identifier: String::new(),
        }],
        contributors: vec![("illustrator".to_string(), "Mary GrandPré".to_string())],
    }
}

struct Language<'a> {
    code: &'a str,
    works: Vec<Work<'a>>,
    ranks: Vec<Popularity>,
    aliases: Vec<(u32, u32)>,
    extras: Vec<Extras>,
}

fn write_language(
    directory: &Path,
    language: &Language,
    authors: &Authors,
) -> Vec<Published> {
    let index = LANGUAGES.iter().position(|&code| code == language.code).unwrap();
    let meta = Meta { language: index, pack: "core", month: RELEASE, base: RELEASE };
    let core = segment::segment(&meta, &language.works, &[], authors);
    let ranks = segment::ranks(
        index,
        RELEASE,
        &language.works,
        &language.ranks,
        authors,
        &language.aliases,
    );
    let code = language.code;
    let extras = sidecar::extras_file(RELEASE, code, &language.extras);
    let files = [
        (format!("{code}-core-{RELEASE}.bin"), core),
        (format!("{code}-ranks-{RELEASE}.bin"), ranks),
        (format!("extras-{code}.bin"), extras),
    ];
    for (name, bytes) in &files {
        std::fs::write(directory.join(name), bytes).unwrap();
    }
    let published = |pack: &str, file: &str| Published {
        language: index,
        pack: pack.to_string(),
        month: RELEASE.to_string(),
        file: file.to_string(),
    };
    vec![published("core", &files[0].0), published("ranks", &files[1].0)]
}

fn write_release(directory: &Path) {
    let mut authors = Authors::default();
    authors.add(100, "J. K. Rowling");
    authors.add(101, "Frank Herbert");
    authors.add(102, "Isaac Asimov");
    authors.add(ROWLING_TWIN, "J. K. Rowling");
    let potter = Series { name: "Harry Potter".to_string(), members: vec![10, 11] };
    let german_potter = Series {
        name: "Harry Potter".to_string(),
        members: vec![11, GERMAN_STONE, GERMAN_PRISONER],
    };
    let swedish_potter =
        Series { name: "Harry Potter".to_string(), members: vec![10, SWEDISH_LATER] };
    let fantasy = [tag("fiction"), tag("fantasy"), tag("childrens")];
    let space = [tag("fiction"), tag("science-fiction")];
    let mut stone =
        work(10, "Harry Potter and the Philosopher's Stone", &[100], &fantasy);
    stone.year = 1997;
    stone.cover = 300;
    stone.description = "A boy learns he is a wizard.";
    stone.series = Some((&potter, 1));
    let isbns = [isbn_key(STONE_ISBN).unwrap()];
    stone.isbns = &isbns;
    let mut chamber =
        work(11, "Harry Potter and the Chamber of Secrets", &[100], &fantasy);
    chamber.series = Some((&potter, 2));
    let mut dune = work(12, "Dune", &[101], &space);
    dune.year = 1965;
    dune.cover = 301;
    let foundation = work(13, "Foundation", &[102], &space);
    let mut german =
        work(GERMAN_STONE, "Harry Potter und der Stein der Weisen", &[100], &fantasy);
    german.description = "Ein Junge erfährt, dass er ein Zauberer ist.";
    german.library_cover = Some((1, GERMAN_COVER_KEY));
    german.series = Some((&german_potter, 1));
    let prisoner_title = "Harry Potter und der Gefangene von Askaban";
    let mut prisoner = work(GERMAN_PRISONER, prisoner_title, &[ROWLING_TWIN], &fantasy);
    prisoner.series = Some((&german_potter, 3));
    let mut german_chamber =
        work(11, "Harry Potter und die Kammer des Schreckens", &[100], &fantasy);
    german_chamber.series = Some((&german_potter, 2));
    let mut swedish_stone = work(10, "Harry Potter och de vises sten", &[100], &fantasy);
    swedish_stone.series = Some((&swedish_potter, 1));
    let mut swedish_later = work(SWEDISH_LATER, "Nästa serie, del ett", &[100], &fantasy);
    swedish_later.series = Some((&swedish_potter, 5));
    let english = Language {
        code: "en",
        ranks: vec![
            popularity(10, 900.0),
            popularity(11, 500.0),
            popularity(12, 800.0),
            popularity(13, 300.0),
        ],
        works: vec![stone, chamber, dune, foundation],
        aliases: vec![(9, 12)],
        extras: vec![extras(10, "Bloomsbury")],
    };
    let german = Language {
        code: "de",
        ranks: vec![popularity(GERMAN_STONE, 400.0), popularity(GERMAN_PRISONER, 200.0)],
        works: vec![german_chamber, german, prisoner],
        aliases: Vec::new(),
        extras: vec![extras(GERMAN_STONE, "Carlsen")],
    };
    let swedish = Language {
        code: "sv",
        ranks: vec![popularity(10, 100.0), popularity(SWEDISH_LATER, 50.0)],
        works: vec![swedish_stone, swedish_later],
        aliases: Vec::new(),
        extras: Vec::new(),
    };
    let mut published = write_language(directory, &english, &authors);
    published.extend(write_language(directory, &german, &authors));
    published.extend(write_language(directory, &swedish, &authors));
    let built = [true; LANGUAGE_COUNT];
    let rebased = [false; LANGUAGE_COUNT];
    let base = "https://example.invalid";
    write_manifest(None, directory, RELEASE, &rebased, &published, base, &built);
    let merged = |kind: &str| -> Vec<PathBuf> {
        ["en", "de", "sv"]
            .iter()
            .map(|code| directory.join(format!("{kind}-{code}.bin")))
            .collect()
    };
    let extras = sidecar::merge(EXTRAS, &merged("extras")).unwrap();
    std::fs::write(directory.join("extras.bin"), extras).unwrap();
    let rowling = Person {
        number: 100,
        bio: "British author of the Harry Potter books.".to_string(),
        names: vec!["Joanne Rowling".to_string()],
        identifiers: vec!["viaf:116796842".to_string()],
        ..Person::default()
    };
    let people = sidecar::people_file(RELEASE, "en", &[rowling]);
    std::fs::write(directory.join("people.bin"), people).unwrap();
    let works = Works {
        languages: ["en", "de", "sv"].map(str::to_string).to_vec(),
        works: vec![
            (10, vec![(0, 10), (1, GERMAN_STONE), (2, 10)]),
            (11, vec![(0, 11), (1, 11)]),
        ],
        aliases: vec![(5, 10)],
        authors: vec![(ROWLING_TWIN, 100)],
    };
    std::fs::write(directory.join("works.bin"), works.encode()).unwrap();
}

fn release() -> &'static Path {
    static DIRECTORY: OnceLock<PathBuf> = OnceLock::new();
    DIRECTORY.get_or_init(|| {
        let directory = scratch("release");
        write_release(&directory);
        directory
    })
}

fn pow() -> Pow {
    Pow::new(
        b"test secret, long enough".to_vec(),
        DIFFICULTY,
        DEFAULT_REQUESTS_PER_MINUTE,
    )
}

fn app_with(library: Option<Library>, images: &Path) -> Router {
    let app = App::new(library, Images::new(images.to_path_buf()));
    router(Arc::new(app), gate(pow()))
}

fn app() -> Router {
    app_with(Some(Library::open(release()).expect("fixture")), &scratch("images"))
}

async fn send(app: &Router, request: Request<Body>) -> Response<Body> {
    app.clone().oneshot(request).await.expect("response")
}

async fn text_of(response: Response<Body>) -> String {
    let bytes = response.into_body().collect().await.expect("body").to_bytes();
    String::from_utf8_lossy(&bytes).into_owned()
}

fn get(uri: &str, token: Option<&str>) -> Request<Body> {
    let mut request = Request::get(uri);
    if let Some(token) = token {
        request = request.header(header::COOKIE, format!("__Host-t={token}"));
    }
    request.body(Body::empty()).expect("request")
}

fn header_of(response: &Response<Body>, name: header::HeaderName) -> &str {
    response.headers().get(name).and_then(|value| value.to_str().ok()).unwrap_or_default()
}

async fn token(app: &Router) -> String {
    let challenge = send(app, get("/api/pow", None)).await;
    assert_eq!(header_of(&challenge, header::CACHE_CONTROL), "no-store");
    let body: serde_json::Value =
        serde_json::from_str(&text_of(challenge).await).unwrap();
    let challenge = body["c"].as_str().expect("challenge").to_string();
    let counters = pow::solve(&challenge, DIFFICULTY).expect("solution");
    let payload = serde_json::json!({ "c": challenge, "n": counters }).to_string();
    let request = Request::post("/api/pow").body(Body::from(payload)).unwrap();
    let response = send(app, request).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body: serde_json::Value = serde_json::from_str(&text_of(response).await).unwrap();
    body["t"].as_str().expect("token").to_string()
}

async fn page(app: &Router, uri: &str, token: &str) -> String {
    let response = send(app, get(uri, Some(token))).await;
    assert_eq!(response.status(), StatusCode::OK, "{uri}");
    text_of(response).await
}

async fn json(app: &Router, uri: &str, token: &str) -> serde_json::Value {
    let response = send(app, get(uri, Some(token))).await;
    assert_eq!(header_of(&response, header::CONTENT_TYPE), "application/json", "{uri}");
    serde_json::from_str(&text_of(response).await).expect("json")
}

async fn redirect(app: &Router, uri: &str, token: Option<&str>) -> (StatusCode, String) {
    let response = send(app, get(uri, token)).await;
    (response.status(), header_of(&response, header::LOCATION).to_string())
}

#[test]
fn isbns_become_segment_keys() {
    assert_eq!(isbn_key("978-0-7475-3269-9"), isbn_key("0747532699"));
    assert_eq!(isbn_of(isbn_key(STONE_ISBN).unwrap()), STONE_ISBN);
    assert!(isbn_key("9780747532698").is_none() && isbn_key("074753269X").is_none());
    assert!(isbn_key("1984").is_none() && isbn_key("dune").is_none());
    assert!(isbn_key("9791032305690").is_some());
}

#[tokio::test]
async fn everything_but_open_routes_needs_proof_of_work() {
    let app = app();
    let page = send(&app, get("/", None)).await;
    assert_eq!(page.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(header_of(&page, header::WWW_AUTHENTICATE), r#"Bearer realm="shelf""#);
    assert_eq!(header_of(&page, header::CACHE_CONTROL), "no-store");
    assert!(text_of(page).await.contains(assets::SCRIPT));
    let api = send(&app, get("/api/v1/search?q=dune", None)).await;
    assert_eq!(api.status(), StatusCode::UNAUTHORIZED);
    let body: serde_json::Value = serde_json::from_str(&text_of(api).await).unwrap();
    assert_eq!(body["error"]["code"], "pow_required");
    let image = send(&app, get("/b/10/en/160.avif", None)).await;
    assert_eq!(image.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(header_of(&image, header::CACHE_CONTROL), "no-store");
    let forged = send(&app, get("/", Some(&"ab".repeat(32)))).await;
    assert_eq!(forged.status(), StatusCode::UNAUTHORIZED);
    for open in ["/health", "/robots.txt", assets::STYLE, "/api/pow", "/pow"] {
        assert_eq!(send(&app, get(open, None)).await.status(), StatusCode::OK, "{open}");
    }
    let token = token(&app).await;
    assert_eq!(send(&app, get("/", Some(&token))).await.status(), StatusCode::OK);
    let bearer = Request::get("/api/v1/languages")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&app, bearer).await.status(), StatusCode::OK);
    let mut other = get("/", Some(&token));
    other.headers_mut().insert(header::USER_AGENT, "other".parse().unwrap());
    assert_eq!(send(&app, other).await.status(), StatusCode::UNAUTHORIZED);
    let replay =
        Request::post("/api/pow").body(Body::from(r#"{"c":"00","n":[1]}"#)).unwrap();
    let rejected = send(&app, replay).await;
    assert_eq!(rejected.status(), StatusCode::FORBIDDEN);
    assert_eq!(header_of(&rejected, header::CACHE_CONTROL), "no-store");
}

#[tokio::test]
async fn pages_offer_a_puzzle_without_javascript() {
    let app = app();
    let page = text_of(send(&app, get("/b/10?p=1", None)).await).await;
    assert!(page.contains(&format!("<progress max={SOLUTIONS}")));
    assert!(page.contains("url=/pow?r=/b/10%3Fp%3D1"));
    let puzzle = send(&app, get("/pow?r=/b/10", None)).await;
    assert_eq!(header_of(&puzzle, header::CACHE_CONTROL), "no-store");
    assert!(text_of(puzzle).await.contains("<svg"));
}

#[tokio::test]
async fn responses_carry_the_cache_table() {
    let app = app();
    let token = token(&app).await;
    let book = send(&app, get("/b/10", Some(&token))).await;
    assert_eq!(
        header_of(&book, header::CACHE_CONTROL),
        "public, max-age=600, s-maxage=86400"
    );
    assert!(book.headers().get(header::SET_COOKIE).is_none());
    let etag = header_of(&book, header::ETAG).to_string();
    assert!(etag.starts_with('"'));
    let conditional = Request::get("/b/10")
        .header(header::COOKIE, format!("__Host-t={token}"))
        .header(header::IF_NONE_MATCH, format!("W/{etag}"))
        .body(Body::empty())
        .unwrap();
    let unchanged = send(&app, conditional).await;
    assert_eq!(unchanged.status(), StatusCode::NOT_MODIFIED);
    assert_eq!(header_of(&unchanged, header::ETAG), etag);
    let other = send(&app, get("/b/12", Some(&token))).await;
    assert_ne!(header_of(&other, header::ETAG), etag);
    let home = send(&app, get("/", Some(&token))).await;
    assert_eq!(
        header_of(&home, header::CACHE_CONTROL),
        "public, max-age=600, s-maxage=3600"
    );
    assert!(home.headers().get(header::ETAG).is_none());
    let random = send(&app, get("/random", Some(&token))).await;
    assert_eq!(random.status(), StatusCode::SEE_OTHER);
    assert_eq!(header_of(&random, header::CACHE_CONTROL), "no-store");
    for missing in ["/b/999", "/nowhere", "/api/v1/works/999"] {
        let response = send(&app, get(missing, Some(&token))).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{missing}");
        assert_eq!(
            header_of(&response, header::CACHE_CONTROL),
            "public, max-age=60, s-maxage=3600"
        );
    }
    let robots = send(&app, get("/robots.txt", None)).await;
    assert!(header_of(&robots, header::CACHE_CONTROL).starts_with("public"));
    for response in
        [send(&app, get("/", None)).await, send(&app, get("/health", None)).await]
    {
        assert_eq!(header_of(&response, header::CACHE_CONTROL), "no-store");
    }
    let headers = book.headers();
    assert!(
        headers[header::CONTENT_SECURITY_POLICY].to_str().unwrap().contains("'none'")
    );
    assert_eq!(headers[header::X_CONTENT_TYPE_OPTIONS], "nosniff");
    assert_eq!(headers[header::REFERRER_POLICY], "no-referrer");
}

#[tokio::test]
async fn variants_redirect_to_one_url() {
    let app = app();
    for (from, to) in [
        ("/b/010", "/b/10"),
        ("/a/0100?p=1", "/a/100?p=1"),
        ("/api/v1/works/012", "/api/v1/works/12"),
        ("/b/10?utm=x", "/b/10"),
        ("/s?q=dune&x=1&p=1", "/s?q=dune&p=1"),
        ("/b/10?", "/b/10"),
        ("/b/010/en/160.avif?w=1", "/b/10/en/160.avif"),
    ] {
        let response = send(&app, get(from, None)).await;
        assert_eq!(response.status(), StatusCode::MOVED_PERMANENTLY, "{from}");
        assert_eq!(header_of(&response, header::LOCATION), to);
        assert_eq!(
            header_of(&response, header::CACHE_CONTROL),
            "public, max-age=60, s-maxage=3600"
        );
    }
    assert_eq!(
        redirect(&app, "/isbn/0747532699", None).await.0,
        StatusCode::UNAUTHORIZED
    );
    let token = token(&app).await;
    for (from, to) in [
        ("/b/5", "/b/10"),
        ("/b/5/de", "/b/10/de"),
        (&format!("/b/{GERMAN_STONE}"), "/b/10"),
        ("/b/9", "/b/12"),
        ("/series/5", "/series/10"),
        ("/api/v1/works/5?language=de", "/api/v1/works/10?language=de"),
        ("/api/v1/similar/9", "/api/v1/similar/12"),
    ] {
        let (status, location) = redirect(&app, from, Some(&token)).await;
        assert_eq!(
            (status, location.as_str()),
            (StatusCode::MOVED_PERMANENTLY, to),
            "{from}"
        );
    }
    for (from, to) in
        [("/isbn/0747532699", "/b/10/en"), ("/s?q=978-0-7475-3269-9", "/b/10/en")]
    {
        let (status, location) = redirect(&app, from, Some(&token)).await;
        assert_eq!((status, location.as_str()), (StatusCode::SEE_OTHER, to), "{from}");
    }
}

#[tokio::test]
async fn pages_render_the_catalogue() {
    let app = app();
    let token = token(&app).await;
    let home = page(&app, "/", &token).await;
    assert!(
        home.contains("<a href=/l/en>English</a>")
            && home.contains("<a href=/l/de>German")
    );
    assert!(home.contains("6 books") && home.contains("/b/10/en/160.avif"));
    let results = page(&app, "/s?q=dune", &token).await;
    assert!(
        results.contains("<a href=/b/12/en>Dune</a>")
            && results.contains("Frank Herbert")
    );
    let herbert = page(&app, "/s?q=herbert", &token).await;
    assert!(herbert.contains("<a href=/a/101>Frank Herbert</a>"));
    assert!(page(&app, "/s?q=zzzzqx", &token).await.contains("No results."));
    let stone = page(&app, "/b/10", &token).await;
    assert!(stone.contains("<h1>Harry Potter and the Philosopher&#39;s Stone</h1>"));
    assert!(stone.contains("<img src=/b/10/en/320.avif"));
    assert!(stone.contains("rel=icon href=/b/10/en/160.avif"));
    assert!(stone.contains("A boy learns he is a wizard.") && stone.contains("Fantasy"));
    assert!(stone.contains("<a href=/a/100/en>J. K. Rowling</a>"));
    let german_link =
        "<b><a href=/b/10/de>German</a></b> Harry Potter und der Stein der Weisen";
    assert!(stone.contains(german_link) && stone.contains("<b>French</b><br>Un garçon"));
    assert!(
        stone.contains("Un garçon apprend la magie.") && stone.contains("Klaus Fritz")
    );
    assert!(stone.contains("https://openlibrary.org/works/OL82563W"));
    assert!(stone.contains("https://www.wikidata.org/wiki/Q43361"));
    assert!(stone.contains("<a href=/series/10/en>Harry Potter</a> <small>3 books"));
    assert!(stone.contains("Gefangene von Askaban") && stone.contains("Harry Potter #1"));
    let series_shelf = stone.split("More by").next().unwrap();
    assert!(!series_shelf.contains("Nästa serie"));
    assert!(stone.contains("<dt>Illustrated by<dd>Mary GrandPré"));
    assert!(stone.contains("Chamber of Secrets") && stone.contains("More by"));
    assert!(stone.contains("Bloomsbury") && stone.contains(STONE_ISBN));
    let german = page(&app, "/b/10/de", &token).await;
    assert!(german.contains("<h1>Harry Potter und der Stein der Weisen</h1>"));
    assert!(german.contains("Carlsen") && german.contains("Ein Junge erfährt"));
    let german_cover = format!("/b/{GERMAN_STONE}/de/320.avif");
    assert!(german.contains(&german_cover));
    assert!(german.contains("<a href=/a/100/de>J. K. Rowling</a>"));
    assert!(german.contains("Kammer des Schreckens") && !german.contains("Bloomsbury"));
    assert!(german.contains("Romane") && !german.contains(">Fiction<"));
    let german_rowling = page(&app, "/a/100/de", &token).await;
    assert!(german_rowling.contains("Harry Potter und der Stein der Weisen"));
    assert!(!german_rowling.contains("Philosopher"));
    let swedish = page(&app, "/a/100/sv", &token).await;
    assert!(swedish.find("Nästa serie").unwrap() < swedish.find("Chamber").unwrap());
    let (status, location) =
        redirect(&app, &format!("/a/{ROWLING_TWIN}/de"), Some(&token)).await;
    assert_eq!((status, location.as_str()), (StatusCode::MOVED_PERMANENTLY, "/a/100/de"));
    let missing = send(&app, get("/a/100/xx", Some(&token))).await;
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    let dune = page(&app, "/b/12", &token).await;
    assert!(dune.contains("Similar books") && dune.contains("<a href=/b/13/en"));
    for missing in
        ["/b/10/fr", "/b/10/xx", "/a/999", "/series/12", "/l/fr", "/isbn/9780000000002"]
    {
        let response = send(&app, get(missing, Some(&token))).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{missing}");
        assert!(text_of(response).await.contains("Not found."));
    }
    let rowling = page(&app, "/a/100", &token).await;
    assert!(rowling.contains("<h1>J. K. Rowling</h1>") && rowling.contains("4 books"));
    assert!(rowling.contains("<img src=/a/100/192.avif") && rowling.contains("OL100A"));
    assert!(
        rowling.contains("British author") && rowling.contains("Also: Joanne Rowling")
    );
    assert!(rowling.contains("https://viaf.org/viaf/116796842"));
    assert!(rowling.contains(
        "Chamber of Secrets</a><br><small>J. K. Rowling · English · Harry Potter #2"
    ));
    let twin = send(&app, get(&format!("/a/{ROWLING_TWIN}"), Some(&token))).await;
    assert_eq!(twin.status(), StatusCode::MOVED_PERMANENTLY);
    assert_eq!(header_of(&twin, header::LOCATION), "/a/100");
    let series = page(&app, "/series/10", &token).await;
    assert!(
        series.contains("<h1>Harry Potter</h1>") && series.contains("Chamber of Secrets")
    );
    let english = page(&app, "/l/en", &token).await;
    assert!(
        english.contains("English books") && english.contains("4 books, most read first")
    );
    let first = english.find("Philosopher").unwrap();
    assert!(first < english.find("Dune").unwrap() && english.contains("Foundation"));
}

#[tokio::test]
async fn json_api_answers_every_route() {
    let app = app();
    let token = token(&app).await;
    let found = json(&app, "/api/v1/search?q=harry&limit=1", &token).await;
    assert_eq!(found["results"][0]["id"], 10);
    assert_eq!(found["results"][0]["authors"][0]["name"], "J. K. Rowling");
    assert_eq!(found["more"], true);
    let invalid = json(&app, "/api/v1/search?q=dune&page=0", &token).await;
    assert_eq!(invalid["error"]["code"], "invalid_parameter");
    let stone = json(&app, "/api/v1/works/10", &token).await;
    assert_eq!(stone["language"], "en");
    assert_eq!(stone["series"]["name"], "Harry Potter");
    assert_eq!(stone["series"]["position"], 1);
    assert_eq!(stone["languages"][1]["language"], "de");
    assert_eq!(stone["editions"][0]["publisher"], "Bloomsbury");
    assert_eq!(stone["description"]["text"], "A boy learns he is a wizard.");
    assert_eq!(stone["cover"], "/b/10/en/320.avif");
    assert!(stone["tags"].as_array().unwrap().iter().any(|tag| tag["slug"] == "fantasy"));
    let german = json(&app, "/api/v1/works/10?language=de", &token).await;
    assert_eq!(german["title"], "Harry Potter und der Stein der Weisen");
    let bad = json(&app, "/api/v1/works/10?language=xx", &token).await;
    assert_eq!(bad["error"]["code"], "invalid_parameter");
    assert_eq!(
        json(&app, "/api/v1/works/abc", &token).await["error"]["code"],
        "invalid_parameter"
    );
    assert_eq!(
        json(&app, "/api/v1/works/999", &token).await["error"]["code"],
        "not_found"
    );
    let dune = json(&app, "/api/v1/works/12", &token).await;
    assert!(dune["series"].is_null() && dune["editions"].as_array().unwrap().is_empty());
    let dune = dune.as_object().unwrap();
    assert!(!dune.contains_key("series") && !dune.contains_key("original_title"));
    let rowling = json(&app, "/api/v1/authors/100", &token).await;
    assert_eq!(rowling["name"], "J. K. Rowling");
    assert_eq!(
        rowling["works"],
        serde_json::json!([10, 11, GERMAN_PRISONER, SWEDISH_LATER])
    );
    assert_eq!(rowling["bio"], "British author of the Harry Potter books.");
    let series = json(&app, "/api/v1/series/10", &token).await;
    assert_eq!(series["works"][1]["work"], 11);
    assert_eq!(series["works"][2]["position"], 3);
    assert_eq!(
        json(&app, "/api/v1/series/12", &token).await["error"]["code"],
        "not_found"
    );
    let isbn = json(&app, "/api/v1/isbn/978-0-7475-3269-9", &token).await;
    assert_eq!(
        (isbn["work"].clone(), isbn["isbn"].clone()),
        (10.into(), STONE_ISBN.into())
    );
    assert_eq!(
        json(&app, "/api/v1/isbn/abc", &token).await["error"]["code"],
        "invalid_parameter"
    );
    let suggested = json(&app, "/api/v1/suggest?q=dun", &token).await;
    assert_eq!(suggested["suggestions"][0]["label"], "Dune");
    assert_eq!(suggested["suggestions"][0]["language"], "en");
    let author = json(&app, "/api/v1/suggest?q=herbert", &token).await;
    assert_eq!(author["suggestions"][0]["kind"], "author");
    let similar = json(&app, "/api/v1/similar/12", &token).await;
    assert_eq!(similar["results"][0]["title"], "Foundation");
    let languages = json(&app, "/api/v1/languages", &token).await;
    assert_eq!(languages["languages"][0]["code"], "en");
    assert_eq!(languages["languages"][0]["works"], 4);
    let catalogue = json(&app, "/api/v1/catalogue/en?limit=2", &token).await;
    assert_eq!(catalogue["results"][0]["id"], 10);
    assert_eq!(
        (catalogue["total"].clone(), catalogue["more"].clone()),
        (4.into(), true.into())
    );
    let unknown = json(&app, "/api/v1/catalogue/xx", &token).await;
    assert_eq!(unknown["error"]["code"], "invalid_parameter");
    assert_eq!(
        json(&app, "/api/v1/catalogue/fr", &token).await["error"]["code"],
        "not_found"
    );
    assert_eq!(json(&app, "/api/v1/nowhere", &token).await["error"]["code"], "not_found");
}

fn cache(images: &Path, url: &str, width: u32) {
    let directory = images.join(&key(url)[..2]);
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::write(directory.join(format!("{}.{width}.avif", key(url))), b"avif")
        .unwrap();
}

#[tokio::test]
async fn images_keep_their_own_cache_rules() {
    let images = scratch("images");
    cache(&images, COVER_URL, 160);
    let app = app_with(Some(Library::open(release()).unwrap()), &images);
    let token = token(&app).await;
    let found = send(&app, get("/b/10/en/160.avif", Some(&token))).await;
    assert_eq!(found.status(), StatusCode::OK);
    assert_eq!(header_of(&found, header::CONTENT_TYPE), "image/avif");
    assert_eq!(
        header_of(&found, header::CACHE_CONTROL),
        "public, max-age=31536000, immutable"
    );
    assert!(found.headers().get(header::ETAG).is_none());
    for path in ["/b/13/en/160.avif", "/b/10/xx/160.avif", "/b/404/en/160.avif"] {
        let missing = send(&app, get(path, Some(&token))).await;
        assert_eq!(missing.status(), StatusCode::NOT_FOUND, "{path}");
        assert_eq!(
            header_of(&missing, header::CACHE_CONTROL),
            "public, max-age=86400, s-maxage=604800"
        );
    }
    for path in [
        "/b/10/en/321.avif",
        "/b/10/en/160.webp",
        "/a/0/96.avif",
        "/a/1000000000/96.avif",
        "/a/7/320.avif",
    ] {
        let response = send(&app, get(path, Some(&token))).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
        assert_eq!(
            header_of(&response, header::CACHE_CONTROL),
            "public, max-age=3600, s-maxage=86400"
        );
    }
}

#[tokio::test]
async fn covers_follow_aliases_and_library_sources() {
    let images = scratch("images");
    cache(&images, "https://covers.openlibrary.org/b/id/301-L.jpg", 320);
    let german = format!(
        "https://portal.dnb.de/opac/mvb/cover?isbn={}",
        isbn_of(GERMAN_COVER_KEY)
    );
    cache(&images, &german, 640);
    let app = app_with(Some(Library::open(release()).unwrap()), &images);
    let token = token(&app).await;
    for path in ["/b/9/en/320.avif".to_string(), format!("/b/{GERMAN_STONE}/de/640.avif")]
    {
        let response = send(&app, get(&path, Some(&token))).await;
        assert_eq!(response.status(), StatusCode::OK, "{path}");
    }
}

#[tokio::test]
async fn nothing_is_cached_before_a_catalogue_loads() {
    let app = app_with(None, &scratch("images"));
    let token = token(&app).await;
    for path in ["/", "/b/10", "/api/v1/works/10", "/b/10/en/160.avif"] {
        let response = send(&app, get(path, Some(&token))).await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE, "{path}");
        assert_eq!(header_of(&response, header::CACHE_CONTROL), "no-store", "{path}");
    }
    let health = send(&app, get("/health", None)).await;
    assert_eq!(health.status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn health_and_assets_need_no_token() {
    let app = app();
    assert_eq!(text_of(send(&app, get("/health", None)).await).await, RELEASE);
    for asset in [assets::STYLE, assets::SCRIPT, assets::FAVICON] {
        let request = Request::get(asset).header(header::ACCEPT_ENCODING, "gzip, br");
        let response = send(&app, request.body(Body::empty()).unwrap()).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(header_of(&response, header::CONTENT_ENCODING), "br");
        assert!(header_of(&response, header::CACHE_CONTROL).contains("immutable"));
    }
    let plain = send(&app, get(assets::STYLE, None)).await;
    assert!(plain.headers().get(header::CONTENT_ENCODING).is_none());
    assert_eq!(
        send(&app, get("/s/missing.css", None)).await.status(),
        StatusCode::NOT_FOUND
    );
}

const SUGGEST_HARNESS: &str = "const source = require('fs').readFileSync(0, 'utf8');
const node = (tagName) => ({
  tagName, children: [],
  append(...items) { this.children.push(...items); },
  appendChild(item) { this.children.push(item); return item; },
  replaceChildren(...items) { this.children = items; },
});
const form = node('form');
const input = { value: 'du', form };
globalThis.self = globalThis;
globalThis.document = {
  currentScript: { src: '/s/script.js' }, images: [], createElement: node,
  querySelector: (selector) => (selector === '[name=q]' ? input : null),
};
globalThis.addEventListener = () => {};
globalThis.setTimeout = (callback) => callback();
const suggestions = [
  { kind: 'work', id: 7, language: 'en', label: 'Dune', detail: 'Frank Herbert' },
  { kind: 'author', id: 101, label: 'Frank Herbert', detail: 'Author' },
];
const response = { status: 200, ok: true, json: async () => ({ suggestions }) };
globalThis.fetch = async () => response;
(0, eval)(source);
input.oninput();
setImmediate(() => {
  const links = form.children[0].children;
  console.log(links.map((link) => link.href + ' ' + link.textContent + ' '
    + link.children[0].textContent).join('|'));
});";

#[test]
fn served_script_shows_suggestions() {
    use std::io::Write;
    let name = assets::SCRIPT.trim_start_matches("/s/");
    let script = assets::ASSETS.iter().find(|asset| asset.name == name).expect("script");
    let mut command = std::process::Command::new("node");
    command.args(["-e", SUGGEST_HARNESS]);
    command.stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped());
    let Ok(mut child) = command.stderr(std::process::Stdio::piped()).spawn() else {
        eprintln!("node missing, skipped");
        return;
    };
    child.stdin.take().expect("stdin").write_all(script.plain).expect("write");
    let output = child.wait_with_output().expect("node");
    let printed = String::from_utf8_lossy(&output.stdout);
    let errors = String::from_utf8_lossy(&output.stderr);
    let expected = "/b/7/en Dune Frank Herbert|/a/101 Frank Herbert Author";
    assert_eq!(printed.trim(), expected, "{errors}");
}

#[tokio::test]
#[ignore = "needs a real release directory in SHELF_RELEASE"]
async fn real_release_pages() {
    let directory = PathBuf::from(std::env::var("SHELF_RELEASE").expect("SHELF_RELEASE"));
    let started = std::time::Instant::now();
    let library = Library::open(&directory).expect("open release");
    eprintln!("loaded {} in {:?}", library.release, started.elapsed());
    let app = app_with(Some(library), &scratch("images"));
    let token = token(&app).await;
    let paths =
        std::env::var("SHELF_PATHS").unwrap_or_else(|_| "/ /s?q=dune".to_string());
    for path in paths.split_whitespace() {
        let started = std::time::Instant::now();
        let response = send(&app, get(path, Some(&token))).await;
        let status = response.status();
        let body = text_of(response).await;
        eprintln!("{path}: {status} {:?} {} bytes", started.elapsed(), body.len());
    }
}
