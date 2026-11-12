# Shelf server

Book pages, JSON API and AVIF covers at `shelf-books.tn3w.dev`, behind Cloudflare. The app
loads covers from it when "Fast covers" is on (Android 12+), solving the proof of work
itself (`ProofOfWork` in `Pow.kt`).

| Part | Holds |
| --- | --- |
| `src/main.rs` | State, router, image routes, refresh loop, listener |
| `src/library.rs` | Loaded release: unified works, author index, similar books |
| `src/release.rs` | Mirrors the newest `catalogue-*` release into `SHELF_DATA` |
| `src/pages.rs`, `templates/` | HTML pages (askama) |
| `src/v1.rs` | JSON API |
| `src/http.rs` | Canonical URLs, cache and security headers, ETag, assets |
| `src/images.rs` | Cover fetch, trim, AVIF encode, disk cache |
| `pow/` | Proof-of-work gate: challenges, tokens, rate limits, no-script puzzle |
| `../builder` | Readers (segments, ranks, `works.bin`, sidecars), unified search |

## Data

Every 30 min: newest release with `manifest.json` → `SHELF_DATA/next` (segments of every
chain, ranks, `works.bin`, `extras.bin`, `people.bin`; unchanged files hard-linked, new ones
SHA-256 checked) → atomic swap → `SHELF_DATA/catalogue`. Broken download keeps the old
release. Before the first load everything but `/health` answers 503. The sidecars are
optional: without them works stand alone and pages drop extras.

Ids: works are unified ids (`works.bin`); `/b/{work}` shows one member,
`/b/{work}/{language}` a chosen one. Old ids and aliases answer 301. Authors are segment
author numbers; alias numbers (`works.bin` v2: twin Open Library records, library ids) 301
to one, whose page joins all their books. Series are the unified id of their first
volume, languages two-letter codes.

Language order: a work shows its member in the first catalogue of `LANGUAGES` (English,
German, French, …): search rows (among matching books), author and home lists. Home
picks only books shown in the first language.

Language pages (`/b/{work}/{language}`, `/a/{author}/{language}`,
`/series/{id}/{language}`): titles, author and series names, descriptions, tag labels,
translators and editions come from that catalogue; series, "More by" and similar rows show
their books in it where present and link on in it. Other members fill only gaps
(descriptions of other languages, ids, original title, illustrators); the UI stays
English.

Series: the member's catalogue series (else one its title names, else `LANGUAGES` order),
gaps filled from the other catalogues' series of the work when every shared volume sits
at the same position; a series with gaps only fills holes up to the end of the longest
gap-free one (a catalogue numbering Heroes of Olympus on from Percy Jackson). Volumes shown in the
page language where present. Rows show
`Series #n`, tags add those only other members carry.

## Routes

| Path | Answer |
| --- | --- |
| `/` | Counts, languages, 12 popular books (daily) |
| `/s?q=&p=` | Unified search; ISBN → 303 |
| `/b/{work}[/{language}]` | Book: cover, description, tags, links, translators, illustrators, editors, narrators, other languages, series, more by author, similar, editions |
| `/a/{author}[/{language}]?p=` | Author: dates, other names, bio, authority links (Open Library, VIAF, GND, ISNI, LoC, BNE, Wikidata), books |
| `/series/{id}[/{language}]?p=`, `/l/{language}?p=` | Series in reading order, language catalogue |
| `/isbn/{isbn}`, `/random` | 303 to a book |
| `/b/{work}/{language}/{160,320,640}.avif` | Book cover, 2:3: catalogue work id, cover from the loaded release's segments (aliases followed) |
| `/a/{author}/{96,192}.avif` | Open Library author photo, square |
| `/api/v1/…` | `search`, `works/{id}`, `authors/{id}`, `series/{id}`, `isbn/{isbn}`, `suggest`, `similar/{id}`, `languages`, `catalogue/{language}` |

Open without token: `/health`, `/robots.txt`, `/s/{asset}`, `/api/pow`, `/pow`, `*.avif`. Errors:
`{"error":{"code","message"}}` with `invalid_parameter` 400, `pow_required` 401,
`invalid_solution` 403, `not_found` 404, `rate_limited` 429, `unavailable` 503. JSON
leaves out `null` and empty-text fields.

## Proof of work

1. `GET /api/pow` → `{"c": <48 hex>, "d": bits, "k": 12, "n": 4096, "r": 8}`. `c` = 8 B
   nonce, 4 B expiry (60 s), 1 B difficulty, 11 B HMAC: signed, stateless.
2. Find `k` distinct `u32` counters `x` with
   `scrypt(hex-decoded c, salt = x big-endian, N = n, r = r, p = 1, 32 B)` starting with
   ≥ `d` zero bits.
3. `POST /api/pow` `{"c", "n": [x, …]}` → `{"t"}` + cookie `__Host-t` (Secure, HttpOnly,
   SameSite=Lax); one attempt per challenge.
4. Send `Authorization: Bearer <t>` (app, JSON) or the cookie (pages). Guarded 401s carry
   `WWW-Authenticate: Bearer realm="shelf"` → solve again.

Cost: `k · 2^d` = 384 scrypt hashes of 4 MiB. Measured with 8 workers: Chromium ≈ 0.7 s,
LibreWolf ≈ 1.5 s, JVM ≈ 0.5 s; native ≈ 1.6 CPU-s per token; server checks 12 hashes
(≈ 50 ms). `pow.js` runs an unrolled Salsa20/8 and WebCrypto HMAC (Firefox rejects long
PBKDF2 outputs), ≈ 2.4× native per core.

Tokens: 6 h, `SHELF_RATE` requests/min, bound to `CF-Connecting-IP` (IPv6 /64) and
`User-Agent`. Difficulty `SHELF_DIFFICULTY`, +1 bit per 8 solves in 10 s, up to +3
(serving challenges costs nothing, so fetching them never raises it). ≤ 30 solves per
IP and minute. No-script fallback: SVG digit puzzle at `/pow?r=<path>` (1 h token, ¼
rate).

## Caching

No cookie or token in responses, so the URL is the Cloudflare cache key. Pages and JSON
`max-age=600, s-maxage=86400` + ETag; 3xx/4xx `s-maxage=3600`; images and `/s/` assets
`immutable`; 401/403/429, `/api/pow`, `/random`, `/health`, 5xx `no-store`. Edge hits need
no token. Junk URLs (leading zeros, unknown query keys) 301 to one spelling. CSP
`default-src 'none'`, `nosniff`, `no-referrer` everywhere.

## Running

| Variable | Default | Meaning |
| --- | --- | --- |
| `$CREDENTIALS_DIRECTORY/secrets` | required | HMAC key, ≥ 16 bytes |
| `SHELF_DATA` | `data` | Release mirror and AVIF cache |
| `SHELF_LISTEN` | unset | TCP `host:port`; unset → systemd socket fd 3 |
| `SHELF_DIFFICULTY` | 5 | PoW bits |
| `SHELF_RATE` | 300 | Requests per minute per token |

```bash
cargo test --workspace
mkdir -p data/credentials && head -c 32 /dev/urandom > data/credentials/secrets
CREDENTIALS_DIRECTORY=data/credentials SHELF_LISTEN=127.0.0.1:8080 target/release/server
```

First start downloads the release (≈ 270 MB, RSS ≈ 1 GB); pages answer in 0.2–36 ms.
Deployment: `deploy/deploy-shelf-books.sh [purge]` in the server repository.
