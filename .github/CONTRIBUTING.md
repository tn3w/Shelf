# Contributing

Contributions are welcome: bug reports, translations, catalogue ideas, code. Forking and
shipping your own build is fine too; the MIT license asks only for attribution.

## Setup

| Part | Needs |
|---|---|
| `android/` | JDK 25, Android SDK 37. `./gradlew assembleGithubDebug` |
| `tooling/builder/` | Rust stable. `cargo build --release` |
| `tooling/server/` | Rust stable, `nasm`. `cargo test --workspace` |
| `tooling/` | Python 3.11+ |

Open `android/` in Android Studio for the app; the repo root works for everything else.

## Workflow

1. Fork, branch off `master`.
2. Keep the change focused; one topic per pull request.
3. Run `./gradlew lintGithubDebug testGithubDebugUnitTest` in `android/`,
   `cargo fmt --check && cargo clippy && cargo test` in `tooling/builder/`, or
   `cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings &&
   cargo test --workspace` in `tooling/server/`.
4. Update `README.md` (users) or this file (internals) when behaviour or layout changes.
5. Open the pull request and describe what changed and how you tested it.

## Style

Follow the surrounding code. Self-documenting names, early returns, shallow nesting, no
commentary that the code already states. `ktlint -F "android/app/src/**/*.kt"` for
Kotlin (configured in `.editorconfig`), `cargo fmt` for Rust.

## How it works

```mermaid
flowchart LR
    A[Open Library + national library dumps] -->|monthly| B[Rust builder]
    B -->|GitHub release| C[Catalogue packs]
    C -->|download once| D[Shelf]
    D --> E[Your library stays on device]
```

## Search and sort

**Search**: typo-tolerant, completes words, finds authors and ISBNs. Your own books
show first. Filter by genre or publication year, sort by relevance, popularity or date.

**Library**: search, shelf chips, and a sort and filter sheet (sort, genre, grid or
list). Genres group books automatically, no collections to maintain.

**Tablets**: navigation rail; wide windows (≥ 840 dp) open books in a pane beside the
list, back closes it. Grids and list view add columns with width. Reader text capped at
640 dp per line, extra width → margins. Window narrows → open book moves to full screen.

## Own books

Book missing from the catalogue? Library › **+**, or search it and tap
**Add "…" as own book**. Title, author and shelf stay on device. Cover drawn on device,
or tap it, in the sheet or on the book page, to pick your own image.
Library › file icon imports a book file instead (see [Formats](#formats)). Same file
twice → same book. Imported files and own covers move along on device transfer; cloud
backup keeps the library only, so re-import files there.

## Formats

Detected by content (magic bytes), extension only as fallback: a CBR that is really a ZIP
still opens. Title and author read on import where the format has them. Without a table
of contents, chapters start at `h1`–`h3` headings or a short centered first line. Same
file under another extension → same book, progress kept.

<table>
<tr><th width="1%">Kind</th><th width="1%">Extensions</th><th width="1%">Reads</th></tr>
<tr><td>EPUB</td><td><code>epub</code> <code>kepub</code></td><td>spine, nav/NCX chapters (also <code>#anchors</code> inside one file), images, styles</td></tr>
<tr><td>Kindle</td><td><code>mobi</code> <code>azw</code> <code>azw3</code> <code>kf8</code> <code>prc</code></td><td>MOBI 6 and KF8 (also joint files), PalmDOC and HUFF/CDIC, images, EXTH metadata; DRM refused. Chapters: MOBI 6 guide TOC, else page breaks</td></tr>
<tr><td>Palm</td><td><code>pdb</code></td><td>PalmDOC text or HTML, MOBI</td></tr>
<tr><td>FictionBook</td><td><code>fb2</code> <code>fbz</code> <code>fb2.zip</code></td><td>sections, styles, embedded images</td></tr>
<tr><td>PDF</td><td><code>pdf</code></td><td>pages via <code>PdfRenderer</code></td></tr>
<tr><td>Comics</td><td><code>cbz</code> <code>cbr</code> <code>cb7</code> <code>cbt</code></td><td>image pages, natural order; CBR only as ZIP</td></tr>
<tr><td>Office</td><td><code>docx</code> <code>odt</code> <code>rtf</code></td><td>headings (any Word language), bold/italic (DOCX), images (DOCX, ODT); comments skipped</td></tr>
<tr><td>Web, text</td><td><code>html</code> <code>htm</code> <code>xhtml</code> <code>htmlz</code> <code>txt</code> <code>txtz</code> <code>md</code></td><td>headings, styles; UTF-8 or Windows-1252</td></tr>
</table>

A plain `zip` opens as whatever it holds: EPUB, DOCX, ODT, FB2, HTML, images or text.
ZIP, 7z and tar via `java.util.zip` and
[Commons Compress](https://commons.apache.org/proper/commons-compress/); 7z and tar pages
unpack to the cache while open (2 GB cap), cleared on start. Kindle reader in
`data/Mobi.kt`, everything else in `data/Documents.kt`. Long scans use one `Matcher`
(`Regex.scan`): `findAll` re-copies the input per match on Android.

## Free books

Settings › **Free books**: one switch per source, all off by default. Book page › cloud
icon (online, no file yet) → sheet. Only switched-on sources serving the catalogue
language are contacted, in parallel. Per source: up to 8 title/author matches → their
`steps` (file, size, cover) → up to 5 offers. Tap one → file saved like an import,
reader opens. Failed or cancelled → nothing kept, tap to retry.

### Sources

Every source is a JSON recipe: data only, nothing executed. Built-ins ship in
[`assets/sources/`](../android/app/src/main/assets/sources) (file name = id) and serve as examples:
[Standard Ebooks](../android/app/src/main/assets/sources/standard-ebooks.json) (Atom feed),
[Project Gutenberg](../android/app/src/main/assets/sources/gutenberg.json) (OPDS + step),
[Wikisource](../android/app/src/main/assets/sources/wikisource.json) (JSON API, `skip`),
[Internet Archive](../android/app/src/main/assets/sources/internet-archive.json) (Lucene query, regex over JSON).

Own sources: Settings › Free books › **Own sources**. Create in form or file, import or
export `shelf-sources.json` (device only, not backed up). **Test** traces each request.

| Key | Meaning |
|---|---|
| `name`, `about` | settings row; `about` = language → text |
| `search` | `url`, optional `body` (POST), `items` rule, `fields` (`title` required) |
| `steps` | extra requests per candidate; each adds its `fields` |
| `download`, `cover` | file URL, optional cover URL |
| `format`, `languages` | `epub` default; empty `languages` = all |
| `headers` | sent only to the search host |

Rules: `css` → `json` (dot path) → `attr` → `regex` (first group); `<?xml` pages parse as
XML. Fields `size`, `format` describe the file, `skip` drops the item. `{name}` inserts
URL-encoded, `{+name}` raw. Variables: `{query}`, `{title}`, `{author}`,
`{titleWords}`, `{surname}`, `{language}`, `{language3}` (`ger`), `{languageName}`
(`German`), `{isbn}`, `{isbns}`, plus every field. Limits: https only, pages 5 MB,
files 200 MB, regex 2 s.

## Scan a cover

Search › scan icon (hidden without camera). Hold a cover in view: frames are matched
live against the **selected catalogue**; clear leader → vibration, frozen frame with
dots on the read title, sheet with book, shelf picker, **Scan again**. Tap → capture
with a lower threshold. ISBN barcodes (EAN-13, QR) are read with ZXing and looked up in
every installed catalogue.

On device, pure Kotlin, no network; camera via CameraX:

- `data/Lines.kt`: 1280×960 grayscale, deskew and line finding at ≤ 640 px, 48 px crops
  from the full frame
- `data/Recognizer.kt`: CNN for `assets/scan.bin` (PP-OCRv6 tiny, Apache-2.0,
  300 Latin characters, 1.2 MB), warmed up on open
- `data/Scan.kt`: orientation probed once and kept, top 8 lines read in parallel,
  typo-tolerant title/author matching; generic titles need the author, series names
  count half; stop rule over decaying scores

`scan.bin`: `SCAN`, version 3, line height, alphabet, layers (grouped/strided conv,
8/16-bit weights, squeeze-excite, pool). Built from source, byte for byte:

```sh
apt install python3-onnx python3-yaml python3-numpy   # or pip install onnx pyyaml numpy
python3 tooling/scan.py [out]                         # default: assets/scan.bin
```

`tooling/scan.py` downloads `PaddlePaddle/PP-OCRv6_tiny_rec_onnx` at a pinned Hugging
Face commit, checks SHA-256, folds batch norm, keeps Latin characters, quantizes to
16 bit. Math in float64 → same bytes across numpy/BLAS versions. CI `scan` job
rebuilds it on Debian trixie and fails on any diff. F-Droid recipe: `rm` the asset,
`sudo` installs the three packages, `build: python3 ../../tooling/scan.py`.
Bench data and training: gitignored `scanbench/`; `./gradlew scanBench` simulates
real-time scanning (`--tune`, `--rematch` rescores stored lines without OCR).

## Catalogue

<table>
<tr><th width="1%">Type</th><th width="1%">Contents</th></tr>
<tr><td>Languages</td><td>English · Deutsch · Français · Español · Eesti · Svenska · Polski · Čeština · 한국어 · Suomi · 日本語 · Latviešu</td></tr>
<tr><td>Genre packs</td><td>fantasy · scifi · mystery · romance</td></tr>
<tr><td>More packs</td><td>kids · young-adult · nonfiction · general</td></tr>
</table>

English `core` and `ranks` ship in the APK (`bundledLanguage`). Other languages and
packs download on demand, verified by SHA-256; onboarding requires the chosen
language's `core` unless it is installed. Language pickers (app and catalogue) show
native names as chips: English, Deutsch, Français, Español plus the chosen and installed
ones; **Show all** reveals the rest. ✓ marks the chosen one, a download mark installed
catalogues. Catalogue language ≠ app language: `LANGUAGES` (catalogues) extends
`APP_LANGUAGES` (Weblate strings) in `Packs.kt`.
Packs include ISBNs of editions in their language; older apps ignore them.

<table>
<tr><th width="1%">Source</th><th width="1%">Use</th><th width="1%">Licence</th></tr>
<tr><td><a href="https://openlibrary.org/developers/dumps">Open Library</a></td><td>works, editions, readers, covers</td><td>CC0</td></tr>
<tr><td><a href="https://data.dnb.de/opendata/">DNB</a> title dump</td><td>German editions, series, volumes, ISBNs, audience</td><td>CC0</td></tr>
<tr><td><a href="https://data.digar.ee/">ERB</a> Estonian books</td><td>Estonian editions, series, volumes, ISBNs, genres, DIGAR covers</td><td><a href="https://doi.org/10.5281/zenodo.8228805">CC0</a></td></tr>
<tr><td><a href="https://www.bnf.fr/fr/les-donnees-de-la-bnf">BnF</a> Bibliographie nationale</td><td>French editions, series, volumes, ISBNs, audience</td><td>Licence Ouverte</td></tr>
<tr><td><a href="https://libris.kb.se/">LIBRIS</a> instances + persons</td><td>Swedish editions, series, ISBNs, genres, authors</td><td>CC0</td></tr>
<tr><td><a href="https://data.bn.org.pl/">BN</a> books, e-books</td><td>Polish editions, series, volumes, ISBNs, genres</td><td>CC0</td></tr>
<tr><td><a href="https://aleph.nkp.cz/data/">NKP</a> national catalogue</td><td>Czech editions, series, volumes, ISBNs, genres</td><td>CC0</td></tr>
<tr><td><a href="https://lod.nl.go.kr/">NLK</a> LOD books</td><td>Korean editions, series, ISBNs, KDC forms</td><td>KOGL type 1</td></tr>
<tr><td><a href="https://data.nationallibrary.fi/">Fennica</a></td><td>Finnish editions, series, volumes, ISBNs, genres</td><td>CC0</td></tr>
<tr><td><a href="https://bl.iro.bl.uk/server/api/core/items/d194ffaf-4d53-4313-b6f2-25a97f92e99f">BNB</a> linked data (2018)</td><td>English series and titles for Open Library works</td><td>CC0</td></tr>
<tr><td><a href="https://www.bne.es/es/catalogos/datos-enlazados">BNE</a> MARC21 + persona authorities</td><td>Spanish editions, series, volumes, ISBNs, genres, translators, VIAF/ISNI/Wikidata authors</td><td>CC0</td></tr>
<tr><td><a href="https://support.nii.ac.jp/en/cinii/api/api_outline">CiNii Books</a> (NACSIS-CAT)</td><td>Japanese editions (NCID), ISBNs, series, translators</td><td>CC BY 4.0, NII</td></tr>
<tr><td><a href="https://dati.lnb.lv/">LNB</a> Latvian bibliography 2014–2023</td><td>Latvian editions, ISBNs, genres, original language</td><td>CC0</td></tr>
<tr><td><a href="https://www.wikidata.org">Wikidata</a> via <a href="https://qlever.dev">QLever</a></td><td>work Q ids, labels, original language, OL ids, author GND/VIAF/ISNI (sidecars, author guard)</td><td>CC0</td></tr>
<tr><td><a href="https://www.gutenberg.org/ebooks/offline_catalogs.html">Project Gutenberg</a> RDF</td><td><code>pg:</code> ids, summaries (sidecars)</td><td>public domain</td></tr>
</table>

One library per catalogue: DNB → de, ERB → et, BnF → fr, LIBRIS → sv, BN → pl, NKP → cs,
NLK → ko, Fennica → fi, BNB → en (joins only, adds no works), BNE → es, CiNii → ja, LNB →
lv. CiNii has no genre: Japanese records (kana in title or publisher) with ISBN
join; only titles or series naming a literary form (小説, 文庫, 童話, 詩集, …) add works.
Join Open Library works by ISBN, else title or original title + author; per language, so
editions never cross catalogues. Missing works get stable ids `1,000,000,000 + base +
local id` (`ID_RANGES`, test asserts no overlap and < 2³¹); later Open Library match →
alias. Rank below Open Library books with readers.

| Library | Base | Size | Local id |
|---|---|---|---|
| DNB | 0 | 200 M | record number |
| LIBRIS, BN, NKP, NLK, Fennica, BnF, BNB | 200 M … 800 M | 100 M each | number or hash |
| BNE | 900 M | 100 M | Alma MMS sequence (`99` + n + check + `8606`) − 10⁹ |
| ERB | 1,000 M | 20 M | `b` number |
| CiNii | 1,020 M | 27 M | NCID prefix slot + 7 digits (`NCID_RANGES`, 12.8 M NCIDs) |
| LNB Latvia | 1,047 M | 10 M | `LNC04`/`NBA02` slot + number |

Free: 1,057 M – 1,147 M. Hashed ids that collide are probed to the next free id in the
range (sorted by record id, so stable).

Join guards (`libraries.rs`, named constants):

- Compilations never join by title: ISBD `A ; B`, `A / B`, MARC 245 `$a` ending in `;`,
  and `A and B` (`y`, `und`, `et`, …) when both halves are titles of the same writer on
  ≥ 3 records. Open Library titles with ` ; ` or ` / ` never claim by title.
- Rare subtitles never split: `Title: subtitle` on < 2 records also keys on `Title`.
- Original titles join only when the main title is rare (< 3 records) or the same
  title → original pair is on ≥ 2 records.
- Authority ids beat names: two records with different first-author authorities (GND,
  BNE persona → VIAF/ISNI/Wikidata, MARC `100 $0`) never join by title; an Open Library
  author whose Wikidata GND/VIAF/ISNI differ from the record's never claims it.
- Weak Open Library authors (no birth year, no Wikidata link) claim by title only when
  the library record carries the same full name (all name words co-occur), not just
  surname + initial. Joined works keep Open Library title unless every
local Open Library edition title is foreign, take library series. Series with ≥ 3
writers whose titles don't name it are publisher series and dropped. Genre-only pulp
issues (`Dr. Norden 631 - Arztroman`) skipped.

MARC libraries (ERB, BN, NKP, Fennica, BNE, LNB Latvia; `marc.rs`, binary or XML):
language from 008/35,
literature from 008/33 fiction form, UDC `821`, YKL 80–85 or a literary 655/380 genre;
textbook, activity, colouring, song books skipped; 008/22 audience; genres → tags;
245 `$n`/`$p`, 490/830 → series (`/ author` cut); volumes over 40 keep `Title N` without
series; 240 or 246 (translations) → original title; 041 `$h` or UDC `821.xxx` → original
language; 700 translator roles, 264/260 `$b` publisher, 520 description, 100 `$0` authority.
BNE persona file: name + birth year → VIAF/ISNI/Wikidata (name/title authorities skipped,
ambiguous names dropped). BnF UNIMARC: 101 `fre`, 105/11
literature code, 100/17 audience, 686 class (803 novels, 801 poetry, 802 drama, 805
comics, 809 children), 200 `$h`/`$i` volumes, 225/461 series, 454 original.
LIBRIS (`linked.rs`): Swedish instances with literary genre/form term or SAB `H`
class; authors inline, from the persons dump, else statement of responsibility.
NLK: Korean books with KDC 8x1–8x3 or literary keyword; `(…)` title prefixes dropped.
Korean titles prefer Hangul spellings over romanized Open Library ones.

Titles drop series/volume prefixes (`Gregs Tagebuch 01 . Von Idioten umzingelt!` →
`Von Idioten umzingelt!`, `Band 2: Orwood` → `Orwood`), edition tags, `. Roman.`,
trailing dots. Prefix naming writer's series → joins it. Series names searchable. Same
title + writer (surname, first initial) → one book.

Cleaning (all sources): control/zero-width chars, mojibake, entities fixed; MARC
`/ responsibility`, store suffixes, `(Series #2)` tails dropped (tail → series); junk and
low-content titles rejected. Authors normalized (`Last, First`, roles, dates, caps);
same-name Open Library authors merged. Kids pack skips horror/thriller unless
picture-book/middle-grade.

Descriptions (works scoring ≥ `DESCRIPTION_MIN_SCORE` in the catalogue language), native
first: Open Library text in that language → best library text in that language (MARC 520,
UNIMARC 330, Libris, CiNii; every record scored: length, names the title,
≥ 3 ` -- `/` ; ` contents separators −600, cut mid-sentence −700; language detected from
the text, else the record's) → Gutenberg summary (English) → NLLB translation of the Open
Library text. Works with native text request no translation.

Covers: Open Library → DNB (ISBN), DIGAR thumbnail (ERB 856 `nlib-digar` id), Bookbites
(LIBRIS, ISBN) or Open Library by ISBN (other libraries), view time only → drawn. Wide
jacket scans show front half. Author photos from Open Library follow **Online covers**.

Search folds Latin accents (Polish, Czech, Latvian too), maps full-width
ASCII, keeps Hangul syllables and splits kana/kanji runs into overlapping bigrams
(`ノルウェイの森` → `ノル ルウ ウェ ェイ イの の森`, `・` breaks runs); builder and app
tokenize alike (`catalog.rs` / `Search.kt`, same test strings).

Sidecars (server only, not in `manifest.segments`, `sidecar.rs`): `links.bin` (`SHLK`),
`extras.bin` (`SHEX`) and `people.bin` (`SHPE`, keyed by author number), merged from
per-language files. Layout: magic,
version 1, section table, `meta`, one section per language = block index (count, first work
per block, offsets) over raw deflate blocks of 64 records sorted by work (varint delta,
varint length, record; texts length + UTF-8, lists count + items).

| File | Per work |
|---|---|
| `links` | Wikidata Q, OL work ids (with aliases), `pg` ids, original title key, original language, first-author authorities (`gnd:` `viaf:` `wikidata:` `ol:` `isni:` `lc:` `bne:`) + name key, series key + position, record count, majority language (empty = unknown), original-title votes (key + records, most first), compilation (most records or ` ; `/` / ` title) |
| `extras` | best description per language (OL, library 520/330, Gutenberg summary, NLLB), identifiers, Wikidata labels, translators, original title, editions (year, publisher, ISBN, format, record id; ≤ 300), contributors (role + name, ≤ 8; trailing, older files lack it) |
| `people` | per credited author: death year, bio (Markdown and source lines cut, ≤ 1,200 chars), other names (≤ 6), authority ids (OL `remote_ids`, Wikidata, first-author library authorities) |

Contributor roles (`people.rs`): translator, illustrator, editor, narrator from MARC
700/702 `$e`/`$4` (codes and terms in every catalogue language, UNIMARC `730`/`440`/`340`)
and Open Library edition `contributors` / `contributions` (`Name (Role)`); names
deduplicated ignoring case. Translators, contributors and library editions are kept per
record (or edition) language: each catalogue's extras hold only its own.

`builder show <file> <language> [work|author]` prints a record (or the count).

Works layer (`works.bin`, `SHWK`, server only, release asset outside the manifest): one
book across all catalogues, built in `publish` by `builder unify` from the release's
segments (each pack's current chain + newest ranks, ALIA renames applied), manifest and
`links`. No global union-find (it chains translations into garbage): a unified work
holds **≤ 1 work per language**, every merge checks it, `unify` asserts it (job fails).
Within-language dedupe stays in the per-language builds.

1. Same work id in several catalogues → one work (ids are global: OL ids, library ranges).
2. Open Library works merge only when Wikidata names both (same Q, no member with another
   Q), or by translation vote: the smaller work (fewer records) votes, ≥ 2 records and
   ≥ 60 % of its original-title votes name a title key of the other, both majority
   languages known and different, same author, no compilation, no Wikidata conflict.
3. Library-only works (≥ 1,000,000,000) attach to one unified work with an OL member on
   strong evidence: same Wikidata Q; original title key (≥ 60 % of its votes, else the
   links key) + shared author authority id + different language; author + series key +
   position + different language. Library-only works with the same Q join each other.
4. Series alignment: numbered volumes per (language, series key, first-author name key),
   from the segments, so catalogues without `links` take part. Two series of one author
   in different languages align when a unified work holds a volume of each at the same
   position and none at different positions, and each has only that partner in the other
   language; their other volumes join position by position (translated series names).
   Up to 3 rounds, new joins give new anchors; each pair proposed once. Every phase counts
   proposals from one cluster once (its members in several languages proposing the same
   target are not a language conflict).
5. Refused, works stay apart: a work with evidence for two unified works, two works of one
   language claiming one unified work (or its language is taken), Wikidata disagreeing.

Author aliases: author numbers credited under one name key in one unified work are one
person (twin Open Library records, library ids); canonical number = smallest OL id, else
smallest; groups over 8 numbers stay apart.

Named constants (`VOTE_RECORDS`, `VOTE_SHARE`), data shape only, never titles or authors.
Unified id: smallest OL id of the members, else the oldest synthetic id (previous unified
id, then previous member, then smallest). Changed ids → alias old → new (previous aliases
carried), so old URLs redirect. Layout (little endian): magic, u32 version 1, u8 language
count + (u8 length, code), u32 count + per multi-language work (ascending) u32 unified id,
u8 members + (u8 language index, u32 local id), u32 alias count + (u32 old, u32 new).
Version 2 appends u32 count + (u32 alias, u32 author) pairs; version 1 still reads. Works
in one catalogue only are left out (unified id = local id).

The builder crate is a library (`src/lib.rs`: every module, `LANGUAGES`) plus a thin CLI
(`src/main.rs`: options, build orchestration, `show`, `unify`, `qa.rs`). The server takes it
as a path dependency, readers for serving and writers for its test fixture: `reader.rs`
reads SHLF v2 like `Segment.kt`/`Catalogue.kt` (records, terms, ranks, ISBNs, covers,
descriptions, completions, tombstones, chains like `Packs.kt`), `search.rs` holds the
shared tokenizer, ranks like `Search.kt` and `search_all` (every catalogue in parallel,
merged by unified id, best score, best scoring book first; used by `qa` and the server),
`works.rs` reads/writes `works.bin`, `sidecar.rs` reads and writes the sidecars.

Local check (2026-10-31 Open Library and library dumps; works = placed in packs):

| Language | Before | After | Library adds (unique works) | OL works enriched |
|---|---|---|---|---|
| es | 132,730 | 418,934 | BNE 264,392 | 58,838 |
| ja | – | 157,253 | CiNii 147,593 | 5,637 |
| lv | – | 7,347 | LNB 6,014 | 821 |
| fr | 659,383 | 660,030 | BnF 515,758 (510,219) | 74,669 (75,649) |
| et | 41,539 | 41,603 | ERB 32,017 (31,662) | 10,584 (10,693) |
| ko | 187,285 | 187,865 | NLK 186,133 (185,543) | 431 (439) |
| fi | 105,233 | 105,298 | Fennica 85,262 (84,647) | 18,899 (19,118) |

Brackets: before the guards. Wikidata links 87,983 OL works and 293,514 authors;
Gutenberg matches ≈ 2,000 works.

## Build

```sh
cd android
./gradlew assembleGithubDebug
./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest
./gradlew assembleGithubRelease -PlocalCatalogue=<builder out dir>
```

```sh
cd tooling/builder && cargo build --release
target/release/builder <dumps> <out> [--previous <dir>] [--base-url <url>] \
  [--languages sv,fi] [--dump <dir>] \
  [--library dnb=<dnb-all_lds.rdf.gz or url>] \
  [--library erb=<ERB_eestikeelne_raamat.zip or url>] \
  [--library bnf=<P174_*.zip>]... \
  [--library libris=<libris-instances.ndjsonld.gz>,<libris-persons.ndjsonld.gz>] \
  [--library bnp=<bibs-ksiazka.marc>] [--library bnp=<bibs-e_book.marc>] \
  [--library nkp=<nkc.xml.gz>] [--library nlk=<book_json_*.zip>] \
  [--library fennica=<Fennica-bib-*.zip>] [--library bnb=<BNBLODBooks_*_nt.zip>] \
  [--library bne=<monomodernas-MARC.mrc>[,<persona-MARC.mrc>]]... \
  [--library cinii=<cinii.jsonl[.gz]>] [--library lnb_lv=<natl_bibliography-*-marc.zip>] \
  [--wikidata <dir of tooling/wikidata.sh TSVs>] [--gutenberg <rdf-files as .rdf.gz>]
target/release/builder links|extras|people <out.bin> <kind-xx.bin>...
target/release/builder unify <works.bin> <release dir> [previous works.bin]
target/release/builder qa <release dir> [qa dir]
```

Source helpers: `tooling/wikidata.sh <dir>` (QLever, one TSV per `tooling/wikidata/*.rq`,
labels per language), `tar -xjOf rdf-files.tar.bz2 | gzip -1 > gutenberg.rdf.gz`.

BNE (Cloudflare blocks CI) and CiNii (multi-day OpenSearch crawl) come from a snapshot
release `sources-<YYYY-MM>`: `tooling/snapshot.sh <bne dir> <cinii pages dir> <out>`
zstd -19's them into ≤ 1.9 GB parts, prints the `gh release create` command and uploads only
after confirmation. The workflow takes the newest `sources-*`; prune only touches
`catalogue-*`.

`--languages` builds only those catalogues: other languages' editions, titles and
eligibility are skipped (faster, less RAM); `manifest.json` lists only them. Libraries
load in parallel; zips are read entry by entry. `--dump` writes `<language>.tsv` (id,
score, pack, title, alternate, authors, series, year, ISBNs) for review.

`.github/workflows/database.yml` (monthly), every source downloaded once:

| Job | Does |
|---|---|
| `prepare` | release name, previous catalogue tag |
| `dumps` | Open Library dumps from archive.org → artifact `openlibrary` (1 day) |
| `enrichment` | Wikidata TSVs (`tooling/wikidata.sh`) + Gutenberg RDF → artifact `enrichment` (1 day) |
| `tools` | builder binary → artifact `builder`; NLLB 3.3B → int8 model cache (converted only on cache miss) |
| `build` × 12 | one per language, in parallel: artifacts + cached model + its library dump (BnF via `tooling/bnf.py`, `BNF_LINK`/`BNF_FOLDERS` change yearly; NLK and Fennica names discovered; BNE, CiNii from `sources-*`) → `--languages <lang>` build and translation |
| `publish` | merge per-language manifests, links, extras, people; download the chain's older segments, `unify` (with previous `works.bin`) + `qa` → job summary; create release (+ `works.bin`), prune `catalogue-*` |

Flavors, set in `distributions` (`app/build.gradle.kts`):

<table>
<tr><th width="1%">Flavor</th><th width="1%">Catalogue</th><th width="1%">scan.bin</th><th width="1%">Updater</th><th width="1%">Transfer service</th><th width="1%">Release asset</th></tr>
<tr><td><code>github</code></td><td>core, ranks</td><td>yes</td><td>yes</td><td>yes</td><td><code>shelf.apk</code></td></tr>
<tr><td><code>update</code></td><td>manifest only</td><td>no</td><td>yes</td><td>yes</td><td><code>shelf-update.apk</code></td></tr>
<tr><td><code>fdroid</code></td><td>manifest only</td><td>yes</td><td>no</td><td>no</td><td><code>shelf-fdroid.apk</code></td></tr>
</table>

`fdroid` permissions: camera, internet only. `src/fdroid/AndroidManifest.xml` removes
install, foreground service and androidx dynamic receiver permissions, so transfers run
without a foreground notification.

In-app update downloads `shelf-update.apk` (smaller). Before install, bundled packs and
`scan.bin` are copied to app storage, so they survive; a full APK deletes the copies on
start. `shelf-fdroid.apk` is the F-Droid recipe's `Binaries:` reference: F-Droid
rebuilds it from source and ships it only if the builds match.

Size: only app locales and compressed native libs ship. `inter.ttf` is Inter at `opsz`
14, `wght` 400–800, without private-use glyphs (`fonttools varLib.instancer`,
`pyftsubset`).

Format with `ktlint -F "android/app/src/**/*.kt"`.

Store screenshots (root AVD `shelf-screenshots` or `SHELF_AVD`, app installed, `ffmpeg`):
`python3 tooling/screenshots.py [--only 7_scan|8_settings]`.
Scan shot: emulator camera plays a rendered book-on-table loop; taps to capture after 30 s.
`scroll_to(label)` scrolls until nothing visible sits above that section header, so use it
for any section shot. Tab, settings, cover and scan labels come from
`values-<lang>/strings.xml`, so Weblate translations reach the screenshots. Only headlines
and demo books live in the script.
Each language downloads its packs via Settings › Catalogue (Russian has none and uses the
English catalogue). Demo books are picked per catalogue: titles and covers from that
catalogue, a series book for the series shot. The scan shot needs a Latin-script cover:
the recognizer reads Latin text only. Demo mode is re-applied after each theme switch;
Hangul headlines use Noto Sans CJK KR (`fc-match`).

GitHub social preview: `python3 tooling/thumbnail.py [out]` → `android/design/thumbnail.jpg`
(1280×640, phones cut from en-US store screenshots). Upload it in repo Settings › Social
preview; Pages serves it as `og:image`.

Scanner bench (local `scanbench/cache` clips; `--tune` grid-searches the stop rule):

```sh
cd android
./gradlew scanBench --args="bench tune [--reuse] [--tune]"
```

## QA sets

`tooling/qa/gold.tsv`: `work⇥author⇥titles (|)⇥ISBNs (|, - none)` = one book in every
listed language, must be one work; `apart⇥author⇥title⇥author⇥title` = must stay two
works. `tooling/qa/queries.tsv`: `class⇥query⇥expected (=title|…)⇥author` for search
ranking (classes: title, author, foreign, partial, prefix, single, stopword, translit,
typo). Eval data, not unit-test fixtures.

`builder qa <release dir> [qa dir]` (sets built in, or read from `qa dir`) unifies like
`unify`, then checks gold: per catalogue the best search hit of any listed title (same
author), else a listed ISBN; `work` passes when all hits are one unified work, `apart`
when the two sides share none. Queries run a unified search (every catalogue, merged by
unified id, best score) → P@5 (relevant of top 5 ÷ 5) and MRR per class; a unified work is
relevant when one of its books matches (`=` exact title, else contained; author substring;
`-` any). Prints Markdown (unify counts, gold, ranking, failures) for the job summary;
exits non-zero only on invariant violations.

Local run (2026-10-31 dumps, all 12 languages, no DNB/LIBRIS/BN):

| Unify | Count |
|---|---|
| works / unified / multi-language | 2,611,557 / 2,413,157 / 124,066 |
| languages per work | 2: 81,369 · 3: 25,388 · 4: 9,857 · 5: 3,831 · 6: 1,800 · 7: 940 · 8: 521 · 9: 229 · 10: 80 · 11: 40 · 12: 11 |
| links: same id / Wikidata / votes / library | 191,982 / 48 / 2,679 / 3,691 |
| refused: ambiguous / language taken / Wikidata conflict | 993 / 2,311 / 32 |

Gold: work 136 pass, 67 fail; apart 66 pass, 0 fail, 9 not found. Queries P@5 / MRR:
author .47/.85, foreign .47/.75, partial .63/1.00, prefix .40/1.00, single .32/.80,
stopword .50/.75, title .38/1.00, translit .43/.65, typo .47/.92, all .45/.86. Work
failures are translations kept as separate Open Library or library works without
Wikidata, votes or shared authority (refused, not merged) and per-language join errors
(e.g. et «Kolm musketäri» on the Alice OL work). unify ≈ 16 s, qa ≈ 90 s.

## Website

`.github/site/` → https://shelf.tn3w.dev via `.github/workflows/pages.yml` on push to
`master`. Workflow adds icon, thumbnail (`og:image`), covers and screenshots as WebP,
then minifies.

## Project

<table>
<tr><th width="1%">Path</th><th width="1%">Role</th></tr>
<tr><td><code>android/</code></td><td>App</td></tr>
<tr><td><code>tooling/builder/</code></td><td>Catalogue builder (Rust)</td></tr>
<tr><td><code>tooling/server/</code></td><td>Book pages, API, AVIF covers, proof of work at <code>shelf-books.tn3w.dev</code> (<a href="../tooling/server/README.md">README</a>)</td></tr>
<tr><td><code>tooling/qa/</code></td><td>Matching and search eval sets</td></tr>
<tr><td><code>tooling/</code></td><td>Screenshots, thumbnail, translation, promo, scanner model</td></tr>
<tr><td><code>.github/site/</code></td><td>Static website</td></tr>
</table>

## Translate

App strings (component `app`) and store listing (component `fastlane`: title, short and
full description, changelogs `1`–`16` per language under
`android/app/fastlane/metadata/android/<lang>/`) are translated on
[Hosted Weblate](https://hosted.weblate.org/engage/shelf/). Weblate opens pull requests
from its `weblate` branch. Store texts are limited to 30 (title), 80 (short), 4000 (full)
and 500 (changelog) characters. App languages: `APP_LANGUAGES` (the first ten catalogue
languages) plus Russian; 日本語 and Latviešu are catalogue-only.

## Reporting

Use the issue templates. Security issues go to [SECURITY.md](SECURITY.md) instead of the
tracker.
