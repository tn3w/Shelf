mod catalog;
mod dumps;
mod release;
mod segment;
mod tags;
mod translations;

use catalog::{DESCRIPTION_MIN_SCORE, Entry};
use dumps::{Authors, Book, Context, Titles};
use release::{CORE, Merged, PACKS, Published, Selection, State, Tracked};
use segment::{Meta, Popularity, Work};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use translations::{Request, Translations};

pub const LANGUAGES: [&str; 4] = ["en", "de", "fr", "es"];
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
}

fn usage() -> ! {
    eprintln!(
        "usage: builder <dumps-source> <out-dir> \
         [--rebase] [--previous <dir>] [--month YYYY-MM-DD[-N]] \
         [--translations <dir>] [--requests <dir>] [--translator <command>]"
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

fn directory(argument: Option<String>) -> PathBuf {
    PathBuf::from(argument.unwrap_or_else(|| usage()))
}

fn options() -> Options {
    let mut arguments = std::env::args().skip(1);
    let (mut rebase, mut previous, mut month) = (false, None, None);
    let (mut translations, mut requests, mut translator) = (None, None, None);
    let mut positional = Vec::new();
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--rebase" => rebase = true,
            "--previous" => previous = Some(directory(arguments.next())),
            "--translations" => translations = Some(directory(arguments.next())),
            "--requests" => requests = Some(directory(arguments.next())),
            "--translator" => translator = Some(arguments.next().unwrap_or_else(|| usage())),
            "--month" => month = Some(release_label(arguments.next())),
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
    output: &'a Path,
    translations: &'a Translations,
    requests: Option<&'a Path>,
    sources: Option<&'a Path>,
    translator: Option<&'a str>,
}

#[derive(Clone, Copy)]
struct Placed<'a> {
    pack: u8,
    work: Work<'a>,
    book: &'a Book,
}

fn description<'a>(job: &Job<'a>, book: &'a Book) -> (&'a str, bool) {
    if book.scores[job.language] < DESCRIPTION_MIN_SCORE {
        return ("", false);
    }
    if book.description_language == Some(job.language) {
        return (&book.description, false);
    }
    match job.translations.get(book.work) {
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
    let Some(directory) = job.requests.filter(|_| job.language != 0) else {
        return;
    };
    let untranslated = placed.iter().filter(|placed| {
        placed.work.description.is_empty()
            && placed.book.description_language == Some(0)
            && placed.book.scores[job.language] >= DESCRIPTION_MIN_SCORE
    });
    let requests: Vec<Request> = untranslated
        .map(|placed| translations::request(placed.book, placed.work.title, job.authors))
        .collect();
    let file = directory.join(format!("requests-{}.jsonl", LANGUAGES[job.language]));
    translations::write_requests(&file, &requests);
}

fn translate(job: &Job) -> Option<Translations> {
    let command = job.translator.filter(|_| job.language != 0)?;
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
    segment::ranks(job.language, job.month, &works, &rows, job.authors)
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
    let state = State {
        base: job.base.to_string(),
        works: tracked,
    };
    state.save(
        &job.output
            .join(format!("state-{}.bin", LANGUAGES[language])),
    );
    Some(published)
}

fn sticky_works(states: &[Option<State>]) -> Vec<bool> {
    let ids = states
        .iter()
        .flatten()
        .flat_map(|state| state.works.iter().map(|tracked| tracked.work));
    let mut sticky = Vec::new();
    for id in ids {
        if sticky.len() <= id as usize {
            sticky.resize(id as usize + 1, false);
        }
        sticky[id as usize] = true;
    }
    sticky
}

fn main() {
    let options = options();
    std::fs::create_dir_all(&options.output).expect("create output directory");
    let states: Vec<Option<State>> = LANGUAGES
        .iter()
        .map(|language| {
            let directory = options.previous.as_ref()?;
            State::load(&directory.join(format!("state-{language}.bin")))
        })
        .collect();
    let sticky = sticky_works(&states);

    let (signals, authors, (facts, titles, shapes)) = thread::scope(|scope| {
        let signals = scope.spawn(|| dumps::signals(&options.source));
        let authors = scope.spawn(|| dumps::authors(&options.source));
        let editions = scope.spawn(|| dumps::editions(&options.source));
        let joined = (signals.join(), authors.join(), editions.join());
        (
            joined.0.expect("signals"),
            joined.1.expect("authors"),
            joined.2.expect("editions"),
        )
    });
    let context = Context {
        signals: &signals,
        facts: &facts,
        authors: &authors,
        shapes: &shapes,
        sticky: &sticky,
    };
    let works = dumps::works(&options.source, &context);
    drop((signals, facts, shapes));

    let month = options.month.clone().unwrap_or(works.month);
    let mut books = works.books;
    eprintln!("month {month}, {} books", books.len());
    tags::fill_author_tags(&mut books);

    if let Some(directory) = &options.requests {
        std::fs::create_dir_all(directory).expect("create requests directory");
    }
    let loaded: Vec<Translations> = LANGUAGES
        .iter()
        .map(|language| match &options.translations {
            Some(directory) => {
                Translations::load(&directory.join(format!("translations-{language}.bin")))
            }
            None => Translations::default(),
        })
        .collect();

    let mut rebased = [false; 4];
    let mut published = Vec::new();
    for (language, state) in states.iter().enumerate() {
        let same_year = |state: &&State| state.base[..4] == month[..4];
        let previous = state.as_ref().filter(same_year).filter(|_| !options.rebase);
        rebased[language] = previous.is_none();
        let base = previous.map_or(month.as_str(), |state| state.base.as_str());
        let job = Job {
            language,
            month: &month,
            base,
            previous,
            books: &books,
            authors: &authors,
            output: &options.output,
            translations: &loaded[language],
            requests: options.requests.as_deref(),
            sources: options.translations.as_deref(),
            translator: options.translator.as_deref(),
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
        published.extend(built);
    }
    release::write_manifest(
        options.previous.as_deref(),
        &options.output,
        &month,
        &rebased,
        &published,
    );
}
