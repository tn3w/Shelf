mod qa;

use builder::{Built, LANGUAGE_COUNT, LANGUAGES, catalog, dumps, enrichment};
use builder::{language_index, works::Works};
use builder::{libraries, people, release, segment, sidecar, tags, translations, unify};
use catalog::{DESCRIPTION_MIN_SCORE, Entry};
use dumps::{Authors, Book, Context, Isbns, Titles};
use enrichment::Enrichment;
use libraries::{Details, Library, SYNTHETIC, Source};
use release::{CORE, Merged, PACKS, Published, Selection, State, Tracked};
use segment::{Meta, Popularity, Work};
use sidecar::{EDITION_CAP, Edition, Extras, Links, Person};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use translations::{Request, Translations};

const BUDGET_BYTES: usize = 75_000_000;
const CORE_BYTES: usize = 3_000_000;
const CORE_WORKS: usize = 20_000;
const ESTIMATED_BYTES_PER_WORK: usize = 70;
const FITTING_ROUNDS: usize = 7;
const FILL_TARGET: f64 = 0.985;
const FILLED_ENOUGH: f64 = 0.97;

struct Options {
    source: String,
    output: PathBuf,
    rebase: bool,
    previous: Option<PathBuf>,
    month: Option<String>,
    translations: Option<PathBuf>,
    requests: Option<PathBuf>,
    translator: Option<String>,
    base_url: String,
    libraries: Vec<(Source, String)>,
    built: Built,
    dump: Option<PathBuf>,
    wikidata: Option<PathBuf>,
    gutenberg: Option<String>,
}

fn usage() -> ! {
    eprintln!(
        "usage: builder links|extras|people <output> <xx-lang.bin>...\n       \
         builder show <links|extras file> <language> [work]\n       \
         builder unify <works.bin> <release dir> [previous works.bin]\n       \
         builder qa <release dir> [qa dir]\n       \
         builder <dumps-source> <out-dir> \
         [--rebase] [--previous <dir>] [--month YYYY-MM-DD[-N]] \
         [--translations <dir>] [--requests <dir>] [--translator <command>] \
         [--base-url <url>] [--languages en,de,...] [--dump <dir>] \
         [--wikidata <dir>] [--gutenberg <rdf file>] \
         [--library dnb|erb|libris|bnp|nkp|nlk|fennica|bnf|bnb|bne|cinii|lnb_lv\
=<file>[,<file>]]"
    );
    std::process::exit(2)
}

fn release_label(argument: Option<String>) -> String {
    let Some(label) = argument else { usage() };
    let parts: Vec<&str> = label.split('-').collect();
    let widths_valid = matches!(parts.as_slice(), [year, month, day, ..]
        if year.len() == 4 && month.len() == 2 && day.len() == 2);
    let numeric = parts.iter().all(|part| part.parse::<u32>().is_ok());
    if !widths_valid || !numeric || parts.len() > 4 {
        usage()
    }
    label
}

fn library(argument: Option<String>) -> (Source, String) {
    let pair = argument.as_deref().and_then(|text| text.split_once('='));
    let Some((name, location)) = pair else {
        usage()
    };
    let Some(source) = Source::named(name) else {
        usage()
    };
    (source, location.to_string())
}

fn languages(argument: Option<String>) -> Built {
    let Some(list) = argument else { usage() };
    let mut built = [false; LANGUAGE_COUNT];
    for code in list.split(',') {
        let Some(index) = LANGUAGES.iter().position(|&known| known == code) else {
            usage()
        };
        built[index] = true;
    }
    built
}

fn directory(argument: Option<String>) -> PathBuf {
    PathBuf::from(argument.unwrap_or_else(|| usage()))
}

fn options() -> Options {
    let mut arguments = std::env::args().skip(1);
    let (mut rebase, mut previous, mut month) = (false, None, None);
    let (mut translations, mut requests, mut translator, mut dump) = (None, None, None, None);
    let (mut wikidata, mut gutenberg) = (None, None);
    let mut base_url = release::RELEASE_URL.to_string();
    let mut libraries = Vec::new();
    let mut built = [true; LANGUAGE_COUNT];
    let mut positional = Vec::new();
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--rebase" => rebase = true,
            "--previous" => previous = Some(directory(arguments.next())),
            "--translations" => translations = Some(directory(arguments.next())),
            "--requests" => requests = Some(directory(arguments.next())),
            "--translator" => translator = Some(arguments.next().unwrap_or_else(|| usage())),
            "--month" => month = Some(release_label(arguments.next())),
            "--base-url" => base_url = arguments.next().unwrap_or_else(|| usage()),
            "--library" => libraries.push(library(arguments.next())),
            "--languages" => built = languages(arguments.next()),
            "--dump" => dump = Some(directory(arguments.next())),
            "--wikidata" => wikidata = Some(directory(arguments.next())),
            "--gutenberg" => gutenberg = Some(arguments.next().unwrap_or_else(|| usage())),
            _ => positional.push(argument),
        }
    }
    let Ok([source, output]) = <[String; 2]>::try_from(positional) else {
        usage()
    };
    Options {
        source,
        output: PathBuf::from(output),
        rebase,
        previous,
        month,
        translations,
        requests,
        translator,
        base_url: base_url.trim_end_matches('/').to_string(),
        libraries,
        built,
        dump,
        wikidata,
        gutenberg,
    }
}

#[derive(Clone, Copy)]
struct Job<'a> {
    language: usize,
    month: &'a str,
    base: &'a str,
    previous: Option<&'a State>,
    books: &'a [Book],
    authors: &'a Authors,
    isbns: &'a Isbns,
    output: &'a Path,
    translations: &'a Translations,
    requests: Option<&'a Path>,
    sources: Option<&'a Path>,
    translator: Option<&'a str>,
    aliases: &'a [(u32, u32)],
    dump: Option<&'a Path>,
    titles: &'a Titles,
    enrichment: &'a Enrichment,
    details: &'a HashMap<u32, Details>,
    people: &'a HashMap<u32, Person>,
}

#[derive(Clone, Copy)]
struct Placed<'a> {
    pack: u8,
    work: Work<'a>,
    book: &'a Book,
}

fn native_description<'a>(job: &Job<'a>, book: &'a Book) -> Option<&'a str> {
    if !book.description.is_empty() && book.description_language == Some(job.language) {
        return Some(&book.description);
    }
    let library = job.details.get(&book.work).and_then(|details| {
        let mut found = details.descriptions.iter();
        found.find(|(language, _)| *language == job.language)
    });
    if let Some((_, text)) = library {
        return Some(text);
    }
    let gutenberg = job.enrichment.gutenberg.get(&book.work)?.first()?;
    let english = job.language == language_index("en") && !gutenberg.summary.is_empty();
    english.then_some(gutenberg.summary.as_str())
}

fn description<'a>(job: &Job<'a>, book: &'a Book) -> (&'a str, bool) {
    if book.scores[job.language] < DESCRIPTION_MIN_SCORE {
        return ("", false);
    }
    if let Some(text) = native_description(job, book) {
        return (text, false);
    }
    if book.description.is_empty() {
        return ("", false);
    }
    let translated = job.translations.get(book.work);
    let native = |text: &&str| {
        catalog::detect_language(text).is_none_or(|language| language == job.language)
    };
    match translated.filter(native) {
        Some(text) => (text, true),
        None => ("", false),
    }
}

fn placed_works<'a>(job: &Job<'a>, selection: &'a Selection) -> Vec<Placed<'a>> {
    let mut placed: Vec<Placed> = selection
        .chosen
        .iter()
        .map(|chosen| {
            let (entry, book) = (chosen.entry, chosen.book);
            let retitled = entry.title != book.title;
            let (description, translated) = description(job, book);
            let work = Work {
                id: book.work,
                title: &entry.title,
                subtitle: if retitled { "" } else { &book.subtitle },
                alternate: &entry.alternate,
                authors: &book.authors,
                year: book.year,
                cover: entry.cover,
                tags: &book.tags,
                series: chosen
                    .series
                    .map(|(slot, order)| (&selection.series[slot], order)),
                description,
                translated,
                isbns: job.isbns.of(book.work, job.language),
                library_cover: book
                    .library_cover
                    .filter(|cover| entry.cover == 0 && cover.language == job.language)
                    .map(|cover| (cover.source, cover.key)),
            };
            Placed {
                pack: chosen.pack,
                work,
                book,
            }
        })
        .collect();
    placed.sort_by_key(|placed| placed.work.id);
    placed
}

fn export_requests(job: &Job, placed: &[Placed]) {
    let Some(directory) = job.requests else {
        return;
    };
    let untranslated = placed.iter().filter(|placed| {
        placed.work.description.is_empty()
            && placed
                .book
                .description_language
                .is_some_and(|source| source != job.language)
            && placed.book.scores[job.language] >= DESCRIPTION_MIN_SCORE
    });
    let requests: Vec<Request> = untranslated
        .map(|placed| translations::request(placed.book, placed.work.title, job.authors))
        .collect();
    let file = directory.join(format!("requests-{}.jsonl", LANGUAGES[job.language]));
    translations::write_requests(&file, &requests);
}

fn translate(job: &Job) -> Option<Translations> {
    let command = job.translator?;
    let language = LANGUAGES[job.language];
    let requests = job.requests?.join(format!("requests-{language}.jsonl"));
    let output = job.output.join(format!("translations-{language}.bin"));
    let mut words = command.split_whitespace();
    let mut child = Command::new(words.next()?);
    child
        .args(words)
        .arg(&requests)
        .arg(&output)
        .args(["--language", language]);
    if let Some(sources) = job.sources {
        child.arg("--previous");
        child.arg(sources.join(format!("translations-{language}.bin")));
    }
    let status = child.status().expect("run translator");
    assert!(status.success(), "translator failed for {language}");
    Some(Translations::load(&output))
}

fn build_packs(job: &Job, placed: &[Placed], tombstones: &[Vec<u32>]) -> Vec<Vec<u8>> {
    thread::scope(|scope| {
        let handles: Vec<_> = (0..PACKS.len())
            .map(|pack| {
                scope.spawn(move || {
                    let works: Vec<Work> = placed
                        .iter()
                        .filter(|placed| placed.pack as usize == pack)
                        .map(|placed| placed.work)
                        .collect();
                    if works.is_empty() && tombstones[pack].is_empty() {
                        return Vec::new();
                    }
                    let meta = Meta {
                        language: job.language,
                        pack: PACKS[pack],
                        month: job.month,
                        base: job.base,
                    };
                    segment::segment(&meta, &works, &tombstones[pack], job.authors)
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|handle| handle.join().expect("pack build"))
            .collect()
    })
}

fn adjusted(limit: usize, size: usize, cap: usize, works: usize, target: f64) -> usize {
    let bytes_per_work = size as f64 / works.max(1) as f64;
    let change = (target * cap as f64 - size as f64) / bytes_per_work;
    (limit as f64 + change).max(0.0) as usize
}

fn fit(job: &Job, entries: &[Entry]) -> (usize, usize, Merged) {
    let unbudgeted = Isbns::default();
    let job = &Job {
        isbns: &unbudgeted,
        ..*job
    };
    let fresh = entries
        .iter()
        .filter(|entry| entry.sticky.is_none())
        .count();
    let sticky = entries.len() - fresh;
    let no_tombstones = vec![Vec::new(); PACKS.len()];
    let mut limit = (BUDGET_BYTES / ESTIMATED_BYTES_PER_WORK)
        .saturating_sub(sticky)
        .min(fresh);
    let mut core_limit = if job.previous.is_none() {
        CORE_WORKS
    } else {
        0
    };
    let mut merged = job
        .previous
        .map_or([false; PACKS.len()], release::previous_merged);
    let mut best: Option<(usize, usize, Merged)> = None;
    let mut smallest_core: Option<(usize, usize, Merged, usize)> = None;
    for round in 1..=FITTING_ROUNDS {
        let selection = release::select(entries, job.books, limit, core_limit, &merged);
        let placed = placed_works(job, &selection);
        let sizes: Vec<usize> = build_packs(job, &placed, &no_tombstones)
            .iter()
            .map(Vec::len)
            .collect();
        let total: usize = sizes.iter().sum();
        let core_works = placed.iter().filter(|placed| placed.pack == CORE).count();
        let fits = total <= BUDGET_BYTES && sizes[CORE as usize] <= CORE_BYTES;
        eprintln!(
            "{} round {round}: {} works, core {core_works} works {} bytes, \
             total {total} bytes",
            LANGUAGES[job.language],
            placed.len(),
            sizes[CORE as usize]
        );
        let unmerged = merged;
        release::merge_small(&mut merged, &sizes);
        let settled = merged == unmerged;
        if settled && fits && best.is_none_or(|(known, ..)| limit > known) {
            best = Some((limit, core_limit, merged));
        }
        let core_size = sizes[CORE as usize];
        let smaller = smallest_core.is_none_or(|(.., known)| core_size < known);
        if settled && total <= BUDGET_BYTES && smaller {
            smallest_core = Some((limit, core_limit, merged, core_size));
        }
        let filled = limit == fresh || total as f64 >= FILLED_ENOUGH * BUDGET_BYTES as f64;
        if settled && fits && filled {
            break;
        }
        if core_size > CORE_BYTES {
            core_limit = adjusted(core_limit, core_size, CORE_BYTES, core_works, FILLED_ENOUGH);
        }
        limit = adjusted(limit, total, BUDGET_BYTES, placed.len(), FILL_TARGET).min(fresh);
    }
    let fallback = smallest_core.filter(|_| best.is_none());
    if let Some((limit, core_limit, merged, core_size)) = fallback {
        eprintln!(
            "{}: core stays {core_size} bytes over {CORE_BYTES}, keeping the smallest",
            LANGUAGES[job.language]
        );
        return (limit, core_limit, merged);
    }
    best.unwrap_or((0, 0, merged))
}

fn publish(job: &Job, pack: &str, bytes: &[u8]) -> Published {
    let file = format!("{}-{pack}-{}.bin", LANGUAGES[job.language], job.month);
    std::fs::write(job.output.join(&file), bytes)
        .unwrap_or_else(|error| panic!("write {file}: {error}"));
    eprintln!("{file}: {} bytes", bytes.len());
    Published {
        language: job.language,
        pack: pack.to_string(),
        month: job.month.to_string(),
        file,
    }
}

fn delta<'a>(
    previous: &State,
    placed: &[Placed<'a>],
    tracked: &[Tracked],
) -> (Vec<Placed<'a>>, Vec<Vec<u32>>) {
    let mut tombstones = vec![Vec::new(); PACKS.len()];
    let is_gone = |old: &&Tracked| {
        tracked
            .binary_search_by_key(&old.work, |known| known.work)
            .is_err()
    };
    for gone in previous.works.iter().filter(is_gone) {
        tombstones[gone.pack as usize].push(gone.work);
    }
    let changed = placed
        .iter()
        .zip(tracked)
        .filter(|(_, new)| {
            previous
                .get(new.work)
                .is_none_or(|old| old.hash != new.hash)
        })
        .map(|(placed, _)| *placed)
        .collect();
    (changed, tombstones)
}

fn dump_works(job: &Job, placed: &[Placed]) {
    let Some(directory) = job.dump else {
        return;
    };
    let name = |&author: &u32| job.authors.get(author).map_or("", |known| known.name);
    let rows: Vec<String> = placed
        .iter()
        .map(|&Placed { pack, work, book }| {
            let authors: Vec<&str> = work.authors.iter().map(name).collect();
            let series = work.series.map_or(String::new(), |(series, order)| {
                format!("{} #{order}", series.name)
            });
            let score = book.scores[job.language];
            [
                work.id.to_string(),
                format!("{score:.0}"),
                PACKS[pack as usize].to_string(),
                work.title.to_string(),
                work.alternate.to_string(),
                authors.join("; "),
                series,
                work.year.to_string(),
                work.isbns.len().to_string(),
                work.tags
                    .iter()
                    .map(|&tag| tags::slug(tag))
                    .collect::<Vec<_>>()
                    .join(","),
            ]
            .join("\t")
        })
        .collect();
    let file = directory.join(format!("{}.tsv", LANGUAGES[job.language]));
    std::fs::create_dir_all(directory).expect("create dump directory");
    std::fs::write(file, rows.join("\n") + "\n").expect("write dump");
}

fn ranks(job: &Job, placed: &[Placed]) -> Vec<u8> {
    let rows: Vec<Popularity> = placed
        .iter()
        .map(|&Placed { work, book, .. }| Popularity {
            id: work.id,
            score: book.scores[job.language],
            readers: book.signal.readers(),
            ratings: book.signal.ratings,
            mean_rating: book.signal.mean_rating(),
            editions: book.editions,
        })
        .collect();
    let works: Vec<Work> = placed.iter().map(|placed| placed.work).collect();
    segment::ranks(
        job.language,
        job.month,
        &works,
        &rows,
        job.authors,
        job.aliases,
    )
}

fn build_language(job: &Job, titles: &Titles) -> Option<Vec<Published>> {
    let language = job.language;
    let entries = catalog::candidates(job.books, titles, job.authors, language, job.previous);
    eprintln!("{}: {} candidates", LANGUAGES[language], entries.len());
    let (limit, core_limit, merged) = fit(job, &entries);
    if job
        .previous
        .is_some_and(|previous| release::previous_merged(previous) != merged)
    {
        eprintln!("{}: pack merge changed, rebasing", LANGUAGES[language]);
        return None;
    }
    let selection = release::select(&entries, job.books, limit, core_limit, &merged);
    let placed = placed_works(job, &selection);
    export_requests(job, &placed);
    let fresh = translate(job);
    let job = &Job {
        translations: fresh.as_ref().unwrap_or(job.translations),
        ..*job
    };
    let placed = placed_works(job, &selection);
    let tracked: Vec<Tracked> = placed
        .iter()
        .map(|placed| Tracked {
            work: placed.work.id,
            pack: placed.pack,
            hash: segment::content_hash(&placed.work, job.authors),
        })
        .collect();
    let (changed, tombstones) = match job.previous {
        Some(previous) => delta(previous, &placed, &tracked),
        None => (placed.clone(), vec![Vec::new(); PACKS.len()]),
    };

    let segments = build_packs(job, &changed, &tombstones);
    let mut published: Vec<Published> = segments
        .iter()
        .enumerate()
        .filter(|(_, bytes)| !bytes.is_empty())
        .map(|(pack, bytes)| publish(job, PACKS[pack], bytes))
        .collect();
    published.push(publish(job, "ranks", &ranks(job, &placed)));
    write_sidecars(job, &placed);
    dump_works(job, &placed);
    let state = State {
        base: job.base.to_string(),
        works: tracked,
        aliases: job.aliases.to_vec(),
    };
    state.save(
        &job.output
            .join(format!("state-{}.bin", LANGUAGES[language])),
    );
    Some(published)
}

fn language_code(code: &str) -> String {
    let known = catalog::TARGET_CODES
        .iter()
        .position(|&target| target == code);
    known.map_or_else(|| code.to_string(), |index| LANGUAGES[index].to_string())
}

fn open_library_ids(job: &Job, work: u32) -> Vec<u32> {
    let aliased = job.aliases.iter().filter(|&&(_, to)| to == work);
    let ids = std::iter::once(work).chain(aliased.map(|&(from, _)| from));
    ids.filter(|&id| id < SYNTHETIC).collect()
}

fn original_title(job: &Job, work: u32, details: Option<&Details>) -> String {
    let local = details
        .map(|details| details.original.clone())
        .unwrap_or_default();
    if !local.is_empty() {
        return local;
    }
    let Some(linked) = job.enrichment.wikidata.works.get(&work) else {
        return String::new();
    };
    let labelled = linked
        .labels
        .iter()
        .find(|(language, _)| *language == linked.language);
    labelled.map_or_else(String::new, |(_, label)| label.clone())
}

fn gutenberg_ids(job: &Job, work: u32) -> Vec<u32> {
    let linked = job.enrichment.wikidata.works.get(&work);
    let mut ids = linked.map_or_else(Vec::new, |linked| linked.gutenberg.clone());
    let matched = job.enrichment.gutenberg.get(&work).into_iter().flatten();
    ids.extend(matched.map(|book| book.id));
    ids.sort_unstable();
    ids.dedup();
    ids
}

fn majority_language(job: &Job, book: &Book) -> Option<usize> {
    let local = job
        .titles
        .of(book.work)
        .iter()
        .map(|record| record.language as usize);
    book.main_language.or_else(|| libraries::most_common(local))
}

fn links_of(job: &Job, placed: &Placed) -> Links {
    let (work, book) = (&placed.work, placed.book);
    let details = job.details.get(&work.id);
    let linked = job.enrichment.wikidata.works.get(&work.id);
    let first = work.authors.first().copied();
    let name = first
        .and_then(|id| job.authors.get(id))
        .map(|author| author.name);
    let mut authorities = details.map_or_else(Vec::new, |details| details.authorities.clone());
    if let Some(author) = first.filter(|&id| id < SYNTHETIC) {
        authorities.push(format!("ol:OL{author}A"));
        authorities.extend(job.enrichment.author_ids(author).iter().cloned());
    }
    authorities.sort();
    authorities.dedup();
    let (series, position) = match work.series {
        Some((series, order)) => (series.name.as_str(), order),
        None => details.map_or(("", 0), |details| (details.series.as_str(), details.volume)),
    };
    let original_language = details
        .map(|details| language_code(&details.original_language))
        .filter(|language| !language.is_empty())
        .or_else(|| linked.map(|linked| linked.language.clone()))
        .unwrap_or_default();
    Links {
        work: work.id,
        wikidata: linked.map_or(0, |linked| linked.item),
        open_library: open_library_ids(job, work.id),
        gutenberg: gutenberg_ids(job, work.id),
        original_title: libraries::match_key(&original_title(job, work.id, details)),
        original_language,
        authorities,
        author: name.map(libraries::name_key).unwrap_or_default(),
        series: catalog::series_key(series),
        position,
        records: details.map_or(0, |details| details.records) + u32::from(book.editions),
        language: majority_language(job, book)
            .and_then(|language| LANGUAGES.get(language))
            .map_or_else(String::new, |code| code.to_string()),
        originals: details.map_or_else(Vec::new, |details| details.originals.clone()),
        compilation: details.is_some_and(|details| details.compilation)
            || libraries::is_compilation_title(work.title),
    }
}

fn descriptions(job: &Job, placed: &Placed, details: Option<&Details>) -> Vec<(String, String)> {
    let book = placed.book;
    let native = book
        .description_language
        .map(|language| (language, book.description.clone()));
    let translated = placed
        .work
        .translated
        .then_some((job.language, placed.work.description));
    let library = details
        .into_iter()
        .flat_map(|details| details.descriptions.iter().cloned());
    let gutenberg = job
        .enrichment
        .gutenberg
        .get(&book.work)
        .into_iter()
        .flatten();
    let english = crate::language_index("en");
    let gutenberg = gutenberg.map(|book| (english, book.summary.clone()));
    let candidates = native
        .into_iter()
        .chain(library)
        .chain(gutenberg)
        .chain(translated.map(|(language, text)| (language, text.to_string())));
    let mut chosen: Vec<(String, String)> = Vec::new();
    for (language, text) in candidates.filter(|(_, text)| !text.is_empty()) {
        let code = catalog::SOURCE_LANGUAGES[language].to_string();
        if !chosen.iter().any(|(known, _)| *known == code) {
            chosen.push((code, text));
        }
    }
    chosen
}

fn extras_of(job: &Job, placed: &Placed) -> Extras {
    let work = &placed.work;
    let details = job.details.get(&work.id);
    let linked = job.enrichment.wikidata.works.get(&work.id);
    let mut identifiers: Vec<String> = open_library_ids(job, work.id)
        .iter()
        .map(|id| format!("ol:OL{id}W"))
        .collect();
    identifiers.extend(linked.map(|linked| format!("wikidata:Q{}", linked.item)));
    identifiers.extend(
        gutenberg_ids(job, work.id)
            .iter()
            .map(|id| format!("pg:{id}")),
    );
    let own = |(language, edition): &(usize, Edition)| {
        (*language == job.language).then(|| edition.clone())
    };
    let mut editions: Vec<Edition> = details
        .into_iter()
        .flat_map(|details| details.editions.iter().filter_map(own))
        .collect();
    for &isbn in work.isbns {
        let isbn = catalog::isbn13(isbn);
        if !editions.iter().any(|edition| edition.isbn == isbn) {
            editions.push(Edition {
                isbn,
                ..Edition::default()
            });
        }
    }
    editions.truncate(EDITION_CAP);
    Extras {
        work: work.id,
        descriptions: descriptions(job, placed, details),
        identifiers,
        titles: linked.map_or_else(Vec::new, |linked| linked.labels.clone()),
        translators: details
            .into_iter()
            .flat_map(|details| &details.translators)
            .filter(|(language, _)| *language == job.language)
            .map(|(_, name)| name.clone())
            .collect(),
        original_title: original_title(job, work.id, details),
        editions,
        contributors: contributors(job, work.id, details),
    }
}

fn contributors(job: &Job, work: u32, details: Option<&Details>) -> Vec<(String, String)> {
    let library = details
        .into_iter()
        .flat_map(|details| details.contributors.iter());
    let open_library = job.titles.contributors.get(&work).into_iter().flatten();
    let own = library
        .chain(open_library)
        .filter(|(language, _)| *language == job.language);
    let mut found: Vec<(String, String)> = Vec::new();
    for (_, (role, name)) in own {
        let known = found.iter().any(|(_, seen)| people::same_name(seen, name));
        if !known && found.len() < people::MAX_CONTRIBUTORS {
            found.push((role.to_string(), name.clone()));
        }
    }
    found
}

/// Author page data of every credited author: Open Library record, Wikidata ids and the
/// authority ids libraries give the first author.
fn people_of(job: &Job, placed: &[Placed]) -> Vec<Person> {
    let mut found: BTreeMap<u32, Person> = BTreeMap::new();
    for placed in placed {
        let authorities = job.details.get(&placed.work.id).map(|d| &d.authorities);
        for (index, &number) in placed.work.authors.iter().enumerate() {
            let person = found.entry(number).or_insert_with(|| {
                let mut person = job.people.get(&number).cloned().unwrap_or_default();
                person.number = number;
                person
                    .identifiers
                    .extend(job.enrichment.author_ids(number).iter().cloned());
                person
            });
            if index == 0 {
                person
                    .identifiers
                    .extend(authorities.into_iter().flatten().cloned());
            }
        }
    }
    let mut people: Vec<Person> = found.into_values().collect();
    for person in &mut people {
        person.identifiers.sort();
        person.identifiers.dedup();
    }
    people.retain(|person| {
        person.died > 0
            || !person.bio.is_empty()
            || !person.names.is_empty()
            || !person.identifiers.is_empty()
    });
    people
}

fn write_sidecars(job: &Job, placed: &[Placed]) {
    let language = LANGUAGES[job.language];
    let links: Vec<Links> = placed.iter().map(|placed| links_of(job, placed)).collect();
    let extras: Vec<Extras> = placed.iter().map(|placed| extras_of(job, placed)).collect();
    let files = [
        ("links", sidecar::links_file(job.month, language, &links)),
        ("extras", sidecar::extras_file(job.month, language, &extras)),
        (
            "people",
            sidecar::people_file(job.month, language, &people_of(job, placed)),
        ),
    ];
    for (kind, bytes) in files {
        let file = format!("{kind}-{language}.bin");
        std::fs::write(job.output.join(&file), &bytes)
            .unwrap_or_else(|error| panic!("write {file}: {error}"));
        eprintln!("{file}: {} works, {} bytes", placed.len(), bytes.len());
    }
}

/// `builder show <links|extras|people file> <language> [work|author]` prints one record or
/// a count.
fn show(arguments: &[String]) {
    let [file, language, rest @ ..] = arguments else {
        usage()
    };
    let bytes = std::fs::read(file).unwrap_or_else(|error| panic!("read {file}: {error}"));
    let work: Option<u32> = rest
        .first()
        .map(|work| work.parse().unwrap_or_else(|_| usage()));
    if let Some(links) = sidecar::Sidecar::parse(sidecar::LINKS, &bytes) {
        eprintln!("links, month {}", links.month);
        return match work {
            Some(work) => println!("{:#?}", links.links_of(language, work)),
            None => println!("{} works", links.links(language).map_or(0, |all| all.len())),
        };
    }
    if let Some(people) = sidecar::Sidecar::parse(sidecar::PEOPLE, &bytes) {
        eprintln!("people, month {}", people.month);
        return match work {
            Some(number) => println!("{:#?}", people.person_of(language, number)),
            None => println!(
                "{} people",
                people.people(language).map_or(0, |all| all.len())
            ),
        };
    }
    let Some(extras) = sidecar::Sidecar::parse(sidecar::EXTRAS, &bytes) else {
        return eprintln!("{file}: not a sidecar");
    };
    eprintln!("extras, month {}", extras.month);
    match work {
        Some(work) => println!("{:#?}", extras.extras_of(language, work)),
        None => println!(
            "{} works",
            extras.extras(language).map_or(0, |all| all.len())
        ),
    }
}

/// `builder links|extras|people <output> <xx-lang.bin>...` joins per-language files.
fn merge_indexes(kind: &str, arguments: &[String]) {
    let [output, inputs @ ..] = arguments else {
        usage()
    };
    if inputs.is_empty() {
        usage()
    }
    let magic = match kind {
        "links" => sidecar::LINKS,
        "extras" => sidecar::EXTRAS,
        _ => sidecar::PEOPLE,
    };
    let merged =
        sidecar::merge(magic, inputs).unwrap_or_else(|error| panic!("merge {kind}: {error}"));
    std::fs::write(output, &merged).unwrap_or_else(|error| panic!("write {output}: {error}"));
    eprintln!("{output}: {} bytes", merged.len());
}

fn sticky_works(states: &[Option<State>]) -> Vec<bool> {
    let ids = states
        .iter()
        .flatten()
        .flat_map(|state| state.works.iter().map(|tracked| tracked.work))
        .filter(|&id| id < SYNTHETIC);
    let mut sticky = Vec::new();
    for id in ids {
        if sticky.len() <= id as usize {
            sticky.resize(id as usize + 1, false);
        }
        sticky[id as usize] = true;
    }
    sticky
}

fn log_outcome(outcome: &libraries::Outcome) {
    for (language, code) in LANGUAGES.iter().enumerate() {
        let (enriched, added) = (outcome.enriched[language], outcome.added[language]);
        if enriched + added > 0 {
            eprintln!("{code}: {enriched} works enriched, {added} added from libraries");
        }
    }
}

/// `builder unify <out> <release dir> [previous works.bin]`
fn unify_command(arguments: &[String]) {
    let [output, directory, rest @ ..] = arguments else {
        usage()
    };
    let previous = rest.first().and_then(|path| Works::load(Path::new(path)));
    let release = unify::load(Path::new(directory));
    let (works, stats) = unify::unify(&release.nodes, previous.as_ref(), &release.aliases);
    std::fs::write(output, works.encode())
        .unwrap_or_else(|error| panic!("write {output}: {error}"));
    eprint!("{}", unify::summary(&works, &stats));
}

fn main() {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    match arguments.first().map(String::as_str) {
        Some("show") => return show(&arguments[1..]),
        Some("unify") => return unify_command(&arguments[1..]),
        Some("qa") => return qa::command(&arguments[1..]),
        _ => {}
    }
    let merged = ["links", "extras", "people"];
    if let Some(kind) = arguments
        .first()
        .filter(|first| merged.contains(&first.as_str()))
    {
        return merge_indexes(kind, &arguments[1..]);
    }
    let options = options();
    std::fs::create_dir_all(&options.output).expect("create output directory");
    let states: Vec<Option<State>> = LANGUAGES
        .iter()
        .zip(options.built)
        .map(|(language, built)| {
            let directory = options.previous.as_ref().filter(|_| built)?;
            State::load(&directory.join(format!("state-{language}.bin")))
        })
        .collect();
    let sticky = sticky_works(&states);

    let synthetic: HashSet<u32> = states
        .iter()
        .flatten()
        .flat_map(|state| state.works.iter().map(|tracked| tracked.work))
        .filter(|&id| id >= SYNTHETIC)
        .collect();

    let (signals, mut authors, (facts, mut titles, shapes, mut isbns), mut library, loaded) =
        thread::scope(|scope| {
            let signals = scope.spawn(|| dumps::signals(&options.source));
            let authors = scope.spawn(|| dumps::authors(&options.source));
            let editions = scope.spawn(|| dumps::editions(&options.source, &options.built));
            let library = scope.spawn(|| Library::load(&options.libraries));
            let enrichment = scope.spawn(|| {
                Enrichment::load(options.wikidata.as_deref(), options.gutenberg.as_deref())
            });
            (
                signals.join().expect("signals"),
                authors.join().expect("authors"),
                editions.join().expect("editions"),
                library.join().expect("libraries"),
                enrichment.join().expect("enrichment"),
            )
        });
    let (wikidata, gutenberg) = loaded;
    library.link_authors(wikidata.authors.clone());
    library.match_editions(&isbns);
    let context = Context {
        signals: &signals,
        facts: &facts,
        authors: &authors,
        shapes: &shapes,
        sticky: &sticky,
        titles: &titles,
        library: &library,
        built: &options.built,
    };
    let works = dumps::works(&options.source, &context);
    drop((signals, facts, shapes));

    let month = options.month.clone().unwrap_or(works.month);
    let is_listed = |book: &Book| {
        let sticky = sticky.get(book.work as usize).copied().unwrap_or(false);
        sticky || book.eligible.contains(&true)
    };
    let (mut books, mut claimants): (Vec<Book>, Vec<Book>) =
        works.books.into_iter().partition(is_listed);
    eprintln!("month {month}, {} books", books.len());
    tags::fill_author_tags(&mut books);
    let gutenberg = enrichment::match_gutenberg(gutenberg, &books, &authors, &wikidata);
    let credited = books.iter().flat_map(|book| book.authors.iter().copied());
    let people = dumps::people(&options.source, &credited.collect());
    let enrichment = Enrichment {
        wikidata,
        gutenberg,
    };
    let mut details = HashMap::new();

    if let Some(directory) = &options.requests {
        std::fs::create_dir_all(directory).expect("create requests directory");
    }
    let loaded: Vec<Translations> = LANGUAGES
        .iter()
        .zip(options.built)
        .map(
            |(language, built)| match options.translations.as_ref().filter(|_| built) {
                Some(directory) => {
                    Translations::load(&directory.join(format!("translations-{language}.bin")))
                }
                None => Translations::default(),
            },
        )
        .collect();

    let enriched: Vec<usize> = options
        .libraries
        .iter()
        .map(|(source, _)| source.language())
        .collect();
    let mut order: Vec<usize> = (0..LANGUAGES.len())
        .filter(|&language| options.built[language])
        .collect();
    order.sort_by_key(|language| enriched.contains(language));
    let mut renamed = vec![Vec::new(); LANGUAGES.len()];
    let mut rebased = [false; LANGUAGE_COUNT];
    let mut published = Vec::new();
    for language in order {
        if enriched.contains(&language) && !library.is_empty() {
            let unclaimed = std::mem::take(&mut claimants);
            let outcome = library.extend(
                &mut books,
                unclaimed,
                &mut titles,
                &mut isbns,
                &mut authors,
                &synthetic,
            );
            library = Library::default();
            log_outcome(&outcome);
            details.extend(outcome.details);
            renamed = outcome.aliases;
            tags::fill_author_tags(&mut books);
        }
        let state = &states[language];
        let same_year = |state: &&State| state.base[..4] == month[..4];
        let previous = state.as_ref().filter(same_year).filter(|_| !options.rebase);
        rebased[language] = previous.is_none();
        let base = previous.map_or(month.as_str(), |state| state.base.as_str());
        let known = state
            .as_ref()
            .map_or(&[][..], |state| state.aliases.as_slice());
        let aliases = libraries::aliases(known, &renamed[language], &books);
        let job = Job {
            language,
            month: &month,
            base,
            previous,
            books: &books,
            authors: &authors,
            isbns: &isbns,
            output: &options.output,
            translations: &loaded[language],
            requests: options.requests.as_deref(),
            sources: options.translations.as_deref(),
            translator: options.translator.as_deref(),
            aliases: &aliases,
            dump: options.dump.as_deref(),
            titles: &titles,
            enrichment: &enrichment,
            details: &details,
            people: &people,
        };
        let built = build_language(&job, &titles).unwrap_or_else(|| {
            rebased[language] = true;
            let fresh = Job {
                previous: None,
                base: &month,
                ..job
            };
            build_language(&fresh, &titles).expect("fresh build has no previous merge")
        });
        published.push((language, built));
    }
    drop(claimants);
    published.sort_by_key(|&(language, _)| language);
    let published: Vec<Published> = published.into_iter().flat_map(|(_, built)| built).collect();
    release::write_manifest(
        options.previous.as_deref(),
        &options.output,
        &month,
        &rebased,
        &published,
        &options.base_url,
        &options.built,
    );
}
