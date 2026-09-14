<div align="center">

<img src="android/design/shelf-icon.svg" width="96" alt="Shelf icon">

# Shelf

**Offline book catalogue for Android.**
Search, explore, track reading streaks, read your own files.

[![Release](https://img.shields.io/github/v/release/tn3w/Shelf?filter=v*&label=release&color=4c8)](https://github.com/tn3w/Shelf/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tn3w/Shelf/total?color=48c)](https://github.com/tn3w/Shelf/releases)
![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)

<a href="https://github.com/tn3w/Shelf/releases/latest/download/shelf.apk"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" height="60" alt="Get it on GitHub"></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%257B%2522id%2522%253A%2522dev.tn3w.shelf%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Ftn3w%252FShelf%2522%252C%2522author%2522%253A%2522tn3w%2522%252C%2522name%2522%253A%2522Shelf%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522filterReleaseTitlesByRegEx%255C%2522%253A%2520%255C%2522%255EShelf%2520v%255C%2522%252C%2520%255C%2522apkFilterRegEx%255C%2522%253A%2520%255C%2522shelf-github-%255C%2522%252C%2520%255C%2522fallbackToOlderReleases%255C%2522%253A%2520true%252C%2520%255C%2522includePrereleases%255C%2522%253A%2520false%257D%2522%257D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="60" alt="Get it on Obtainium"></a>

<p align="center">
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/1_home.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/1_home.jpg" width="15%" alt="home"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/2_library.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/2_library.jpg" width="15%" alt="library"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/3_book.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/3_book.jpg" width="15%" alt="book"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/4_series.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/4_series.jpg" width="15%" alt="series"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/5_explore.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/5_explore.jpg" width="15%" alt="explore"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="android/design/screenshots/dark/6_reader.jpg"><img src="android/fastlane/metadata/android/en-US/images/phoneScreenshots/6_reader.jpg" width="15%" alt="reader"></picture>
</p>

</div>

The catalogue is built monthly from the [Open Library dumps](https://openlibrary.org/developers/dumps)
by `builder/` and published as GitHub releases (`db-YYYY-MM`).

<details>
<summary><b>Contents</b></summary>

- [Android app](#android-app): [Features](#features) · [Install](#install) ·
  [Privacy](#permissions-and-privacy) · [Build](#build) · [Release](#release)
- [Layout](#layout) · [Catalogue](#catalogue) · [Update model](#update-model) ·
  [Manifest](#manifest)
- [Segment format v1](#segment-format-v1) · [Builder](#builder) ·
  [Database workflow](#database-workflow)

</details>

## Android app

### Features

- **Home:** daily goal + streak, continue reading, want-to-read, recommendations, genres.
- **Explore:** popular works, genres, audiences, formats, topics → tag and author pages.
- **Search:** typo-tolerant (trigrams + edit distance), prefix completions, authors.
- **Book:** facts from ranks (rating, readers, editions), tags, description, series in
  reading order, more by author, similar books; shelves Want / Reading / Finished.
- **Library:** shelves; entries store the Open Library work number plus title, author and
  cover → survive pack, language and catalogue changes.
- **Reader:** EPUB, PDF, TXT/Markdown, HTML, FB2, CBZ; pagination, chapters, text size,
  progress → pages count toward the streak.
- **Settings:** book language, packs (installed / update / download / remove), download
  all, storage used, app language (Android 13+), theme, daily goal, covers, updates.
- **About:** version + flavor, database month per language, privacy, Open Library data
  license, source, license, update check (`github` flavor).
- **First launch:** system language (`LocaleList`) → `en`/`de`/`fr`/`es` preselected
  (fallback `en`), pack choice with sizes, *Download* or *Later*. Core works offline.
- Edge-to-edge, predictive back, shared cover transition (lists → book; not between
  rows on a book page), animated lists, skeletons,
  animated download progress; animations off when the system animator scale is 0.

### Install

- **GitHub:** [latest release](https://github.com/tn3w/Shelf/releases/latest) `v*` → `shelf-github-<versionCode>.apk` (built-in updater),
  same file as `shelf.apk` for a stable download link, checksums in `SHA256SUMS`.
- **F-Droid:** `fdroid` flavor, metadata in `android/fastlane/`, draft recipe in
  `android/fdroid/dev.tn3w.shelf.yml`.

### Permissions and privacy

| Flavor | Permissions |
|---|---|
| `fdroid` | `INTERNET` |
| `github` | `INTERNET`, `REQUEST_INSTALL_PACKAGES` |

No trackers, no Google Play Services, no Firebase. Network requests: GitHub (manifest,
packs, app updates) and Open Library covers when enabled. Library-merged
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` removed. Backup limited to the library
DataStore.

### Languages

UI: English, German, French, Spanish (`res/values*`, per-app language via
`generateLocaleConfig` + `LocaleManager`). Tag labels come localized from segments.

### Catalogue on device

- APK bundles `core` + `ranks` of all four languages (~17 MB of 19 MB APK) → every
  language offline, correct popularity and term statistics.
- Downloads land in `filesDir/catalogue/`: manifest from the newest `db-*` release (GitHub
  API), missing segments only, SHA-256 verified, `.part` → atomic rename; segments not in
  the manifest for that pack (older than a new base) deleted.
- Load per language: segments mmapped (`FileChannel.map`), per pack the newest base + its
  deltas, merged per README *Client merge* (newest wins, tombstones hide records and
  postings), newest ranks file for scoring.
- Check on start ≤1×/30 days; installed-pack updates ≤5 MB download automatically, larger
  ones (January rebase) show as *Update* in Settings. No background service.

### Updates (`github` flavor)

≤1×/day on start (toggle): newest `v*` release → `shelf-github-<versionCode>.apk` newer
than `BuildConfig.VERSION_CODE` → dialog → streamed into a `PackageInstaller` session,
checked against `SHA256SUMS`. Hidden when installed by F-Droid, F-Droid Basic, Droid-ify,
Neo Store or Aurora.

### Build

```sh
cd android
./gradlew assembleGithubDebug assembleFdroidRelease lint testGithubDebugUnitTest
```

- `downloadCatalogue` fetches bundled segments from release `catalogueRelease`
  (`app/build.gradle.kts`), SHA-256 checked against its manifest.
- Unit tests (`CatalogueTest`) use `en-*`/`de-*` of that release (`downloadTestCatalogue`):
  German core title, English Harry Potter series order, `lightnig thief` search, fake delta
  tombstone.
- Release builds are unsigned without `KEYSTORE_FILE`; reproducible settings: pinned
  versions, `dependenciesInfo` off, literal `versionCode`.

- Tabs: tap → saved tab state; tap active tab → back to its root screen.
- Grids (tag, author, library): adaptive columns ≥96 dp, tiles fill cells → even padding.

### Screenshots

`tooling/screenshots.py [en de fr es]`: rooted emulator (`google_apis` image, AVD
`shelf-screenshots`, Pixel 9, API 36), debug APK installed. Animations off, demo status
bar; seeds DataStore (shelves, progress, 14-day streak, searches) + Gutenberg EPUB per
language, downloads all packs, waits for covers + stable frame, saves 720 px JPEG (~100
KB) to `fastlane/metadata/android/<locale>/images/phoneScreenshots/`. English also in dark
theme → `android/design/screenshots/dark/` (README; fastlane has no dark variant).

### Release

`.github/workflows/android.yml` on tag `v*`: JDK 25, Gradle cache, unit tests, both
release APKs, signing from `release` environment secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `gh release create` with APKs +
`SHA256SUMS`. Bump `versionCode` / `versionName` and add
`fastlane/metadata/android/*/changelogs/<versionCode>.txt` first.

### Sources

| File | Role |
|---|---|
| `data/Segment.kt` | Tokenizer, segment + ranks parsing (sections, DEFLATE blocks, terms) |
| `data/Catalogue.kt` | Merge, visibility bitsets, lookups, tags, series, authors |
| `data/Search.kt` | Candidates, fuzzy terms, completions, rerank |
| `data/Recommend.kt` | Tag profile, retrieval, diversity, series starts |
| `data/Packs.kt` | Local files, manifest, downloads, pack states |
| `data/Library.kt` | DataStore: shelves, progress, streaks, settings |
| `data/Documents.kt` | Document parsing for the reader |
| `ShelfApp.kt`, `MainActivity.kt` | State, downloads, navigation |
| `ui/*` | Theme, components, screens |
| `github/`, `fdroid/` `Updater.kt` | Updater / no-op |

## Layout

| Path | Purpose |
|---|---|
| `android/` | Android app (flavors `github`, `fdroid`) |
| `builder/` | Rust catalogue builder (6 source files) |
| `builder/tags.json` | Tag rules, localized labels, BISAC mapping |
| `tooling/screenshots.py` | Fastlane + README screenshots from emulator |
| `.github/workflows/database.yml` | Monthly build and release |
| `.github/workflows/android.yml` | App release on tag `v*` |

## Catalogue

Four languages: `en`, `de`, `fr`, `es`. Each language is split into disjoint packs; every
work lives in exactly one pack.

| Pack | Content |
|---|---|
| `core` | Top ~20k works by score, ≤3 MB, bundled in the APK |
| `fantasy` | Fantasy, epic/urban fantasy, paranormal |
| `scifi` | Science fiction, space opera, dystopian |
| `mystery` | Mystery, cozy mystery, thriller, crime |
| `romance` | Romance and its subgenres |
| `kids` | Children's, picture books, middle grade |
| `young-adult` | Young adult |
| `nonfiction` | Nonfiction |
| `general` | Everything else |

Primary pack: audience first (`kids`, `young-adult`), then the strongest genre tag, else
`general`. All packs of a language together stay ≤75 MB.

Sizes from the 2026-09 dumps:

| Language | Works | Packs | Ranks | State |
|---|---|---|---|---|
| `en` | 900k | 74.4 MB | 16.2 MB | 11.7 MB |
| `de` | 62k | 6.3 MB | 1.7 MB | 0.8 MB |
| `fr` | 82k | 7.4 MB | 1.9 MB | 1.1 MB |
| `es` | 91k | 8.3 MB | 2.1 MB | 1.2 MB |

### Selection

Per language, from the same dump passes:

- **Eligible:** known non-organization author, title without report/proceedings patterns,
  ≥1 readable edition in the language, ISBN or readers, subjects or readers, cover or
  readers or ≥2 editions, no junk subject unless ≥3 readers, score ≥150.
- **Score:** `100·ln(1+attention) + 60·ln(1+ratings) + 20·ln(1+editions)
  + 30·ln(1+language editions) + 45·ln(languages)` + mean rating bonus + metadata bonuses
  (cover, description, subjects, ISBN, publisher, year ≥1950). Attention = 3·read +
  2·reading + want.
- **Title:** most common spelling among editions in the language (editions without a
  language count as English; titles clearly in another language are ignored). Works without
  such a title are skipped; sticky works fall back to the most common spelling overall.
  The original title becomes the alternate.
  If the winner only names the series (strict prefix of the series name, e.g. *Percy
  Jackson*), capitalized edition subtitles vote instead (*Diebe im Olymp*).
  Known limit: rare wrong picks when a series names volumes by edition (*Death Note* →
  *Black Edition, Volume 6*).
- **Core:** top-ranked works regardless of genre or audience; other packs get the rest.
  Filled to 3 MB with full descriptions, so English holds ~14.4k works, others 20k.
- **Description:** kept when detected in the language and the score is ≥400.
- **Dedupe:** same title key and primary author → highest score wins.
- **Tags:** subject rules + BISAC paths + edition class hints (Dewey, LCC, age bands),
  support weighted. Audience from editions: juvenile share <10% → adult, ≥20% → young, kid
  vs. teen bands; unknown audience → `young-adult` replaces `childrens` when YA support is
  higher. Untagged works borrow genre tags shared by ≥70% of the author's tagged works.
- **Series:** edition `series` field ("Harry Potter, #2" → name, position), grouped by
  name, dominant author >50%, 2–40 members, ordered by position when all distinct, else by
  year.
- **Budget:** works are added by score until all packs reach ~75 MB and `core` ≤3 MB.

## Update model

- **Sticky:** an included work stays in its pack until Open Library deletes it.
- **Deltas:** every month each pack gets `<lang>-<pack>-<YYYY-MM>.bin` with only new or
  changed records and tombstones. Empty deltas are not written. The content hash
  (FNV-1a 64) excludes popularity.
- **Ranks:** `<lang>-ranks-<YYYY-MM>.bin` holds popularity and global term statistics and is
  fully replaced every month.
- **Rebase:** every January (or `--rebase`) all packs are rebuilt in full from a fresh
  selection; the delta chain resets.
- **State:** `state-<lang>.bin` (work id → pack, hash) is published with each release;
  the next run needs only this, never an old database.

### Client merge

Load the segments of a language in manifest order. The newest segment wins per work id.
A tombstone or a newer record hides the work in older segments, including their postings.
Series with the same name: the newest segment wins.

## Manifest

`manifest.json` lists every file a client needs: base segments, all deltas since, and the
current ranks.

```json
{
  "format": 1,
  "month": "2026-10",
  "segments": [
    {
      "id": "en-kids-2026-10",
      "language": "en",
      "pack": "kids",
      "month": "2026-10",
      "size": 7558,
      "sha256": "…",
      "url": "https://github.com/tn3w/Shelf/releases/download/db-2026-10/en-kids-2026-10.bin"
    }
  ]
}
```

Ranks entries use pack `ranks`. Entries are ordered oldest first.

## Segment format v1

Little-endian, mmap-able. `text` = varint byte length + UTF-8. Postings = ascending
varint deltas. Offset table = `u32 count, u32 offsets[count+1], data`. Record blocks = offset
table of raw DEFLATE blocks of 32 records, each `varint length + bytes`.

Header: `SHLF`, `u32 1`, `u32 section count`, then per section 16-byte name, `u32 offset`,
`u32 length`.

| Section | Content |
|---|---|
| `meta` | `key=value` lines: `format`, `language`, `pack`, `month`, `base`, `works`, `authors`, `terms`, `records_per_block`, `terms_per_block` |
| `works` | `u32[]` Open Library work numbers, ascending; position = local work index |
| `tombstones` | `u32[]` deleted work numbers, ascending |
| `head_dictionary` | DEFLATE preset dictionary for `heads` |
| `text_dictionary` | DEFLATE preset dictionary for `descriptions` |
| `facts` | Record blocks: `varint n`, n author slots, year − 1400, cover id, `varint n`, n `u8` tag ids, series slot + 1 (0 = none), order in series |
| `heads` | Record blocks: title `\x1f` subtitle `\x1f` alternate (trailing empty fields dropped) |
| `authors` | Record blocks per author slot: author number, birth year, `text` name, postings of local work indices |
| `tags` | Offset table, one entry per rule in `tags.json` order: `text` slug, `text` localized label, `text` category, `varint` count, postings |
| `series` | Offset table: `text` name, `varint n`, n work numbers in reading order (whole series, may span packs) |
| `terms` | Offset table of blocks of 16 sorted terms: shared prefix length, `text` suffix, title count, author count, title postings bytes, author postings bytes, postings (local works, author slots) |
| `completions` | Offset table: `text` prefix (2–4 bytes, >48 terms), `varint 12`, 12 term ids by frequency |
| `grams` | Offset table: `text` trigram of `$term$`, postings of term ids |
| `descriptions` | `u32` block count, `u32` first local index per block, offset table of DEFLATE blocks of `varint (index − first)`, `text` |

Term id = position in the sorted term list. Tokens: lowercase ASCII alphanumerics, Latin
accents folded (precomposed or combining marks), apostrophes removed, max 24 bytes.

### Ranks file

Same header. Sections: `meta` (`format`, `language`, `pack=ranks`, `month`, `works`,
`authors`, `terms`, `terms_per_block`, `ranks_per_block`), `popularity` (`u32` block count,
`u32` first work number per block, offset table of raw DEFLATE blocks of 4096 works: varint
work number − previous (0 for first), varint score × 10, varint readers, varint ratings,
`u8` mean rating × 20, `u8` editions capped at 255), `terms` (blocks of 16: shared prefix,
suffix, title document frequency, author document frequency across all packs).

### State file

`SHST`, `u32 1`, 7-byte base month, `u32` count, then 13 bytes per work ascending:
`u32` work number, `u8` pack index (order of the pack table above), `u64` content hash.

## Builder

```sh
cd builder
cargo build --release
target/release/builder <dumps-source> <out-dir> [--rebase] [--previous <dir>] [--month YYYY-MM]
```

- `<dumps-source>`: `https://openlibrary.org/data` streams
  `ol_dump_<name>_latest.txt.gz` over HTTP → gzip → parse, resuming dropped connections with
  range requests. A local directory with `ol_dump_<name>[_latest].txt.gz` works for dev runs.
- `--previous`: directory with the last `state-*.bin` and `manifest.json`. Missing state →
  full build for that language.
- `--month`: release month; default is the newest modification month in the works dump.
- Output: segments, ranks, state and manifest for this month only.

Pipeline: ratings, reading log, authors and editions are streamed concurrently into compact
per-id arrays; the works dump is streamed last. One pass per dump, all languages from the
same passes. Output is deterministic.

Local run on the 2026-09 dumps, pinned to 4 cores: 196 s, peak RSS 8.1 GB, output
127 MB; the next-month delta was 115 KB of segments (621 new works, no changes).

Sources: `main.rs` (CLI, budget fitting, deltas), `dumps.rs` (streams, passes),
`catalog.rs` (scoring, titles, series, packs), `tags.rs` (taxonomy), `segment.rs`
(encoding), `release.rs` (state, manifest).

## Database workflow

`.github/workflows/database.yml` runs on the 5th of every month (after the dump) and on
manual dispatch with a `rebase` input:

1. Cache Cargo, build the builder.
2. Download `state-*.bin` and `manifest.json` from the latest earlier `db-*` release.
3. Run the builder against `https://openlibrary.org/data`.
4. Replace or create release `db-YYYY-MM` with all output files.
