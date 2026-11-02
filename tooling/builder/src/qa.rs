//! `builder qa <release dir> [qa dir]`: unify, then check `gold.tsv` and rank
//! `queries.tsv` (built in, or from `qa dir`) with a unified search: every catalogue,
//! merged by unified id.

use builder::reader::{Book, Catalogue};
use builder::search::{Hit, Searcher, main_title, search_all};
use builder::unify;
use builder::works::Works;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt::Write;
use std::path::Path;
use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

const MATCH_DEPTH: usize = 10;
const RESULTS: usize = 30;
const TOP: usize = 5;
const ISBN_BASE: u64 = 978_000_000_000;
const GOLD: &str = include_str!("../../qa/gold.tsv");
const QUERIES: &str = include_str!("../../qa/queries.tsv");

fn normalized(text: &str) -> String {
    let folded: String = text
        .nfkd()
        .filter(|character| !is_combining_mark(*character))
        .flat_map(char::to_lowercase)
        .map(|character| {
            if character.is_alphanumeric() {
                character
            } else {
                ' '
            }
        })
        .collect();
    folded.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn by_author(book: &Book, pattern: &str) -> bool {
    if pattern == "-" {
        return true;
    }
    let names: Vec<String> = book
        .authors
        .iter()
        .map(|author| normalized(&author.name))
        .collect();
    pattern
        .split('|')
        .map(normalized)
        .any(|wanted| names.iter().any(|name| name.contains(&wanted)))
}

fn titled(book: &Book, title: &str) -> bool {
    let wanted = [normalized(title), normalized(main_title(title))];
    let own =
        [&book.title, &book.alternate].map(|text| [normalized(text), normalized(main_title(text))]);
    own.iter()
        .flatten()
        .any(|text| !text.is_empty() && wanted.contains(text))
}

fn isbn_key(isbn: &str) -> Option<u32> {
    let digits: String = isbn.chars().filter(char::is_ascii_digit).collect();
    let twelve: u64 = digits
        .get(..12)
        .filter(|_| digits.len() == 13)?
        .parse()
        .ok()?;
    u32::try_from(twelve.checked_sub(ISBN_BASE)?).ok()
}

struct Context<'a> {
    catalogues: &'a [Catalogue],
    unified: HashMap<u32, u32>,
}

impl Context<'_> {
    fn unified_of(&self, work: u32) -> u32 {
        self.unified.get(&work).copied().unwrap_or(work)
    }

    /// One book per catalogue: the best scoring match of any title (`|`), else the first
    /// ISBN (`|`, `-` none) the catalogue knows. A unified work holds one per language.
    fn found(&self, titles: &str, author: &str, isbns: &str) -> Vec<(String, Book)> {
        std::thread::scope(|scope| {
            let searches: Vec<_> = self
                .catalogues
                .iter()
                .map(|catalogue| scope.spawn(move || best(catalogue, titles, author, isbns)))
                .collect();
            let finished = searches
                .into_iter()
                .map(|search| search.join().expect("search"));
            finished.flatten().collect()
        })
    }

    fn describe(&self, found: &[(String, Book)]) -> String {
        let parts: Vec<String> = found
            .iter()
            .map(|(language, book)| {
                let unified = self.unified_of(book.work);
                format!("{language}:{} → {unified} «{}»", book.work, book.title)
            })
            .collect();
        parts.join(", ")
    }

    fn unified_ids(&self, found: &[(String, Book)]) -> HashSet<u32> {
        found
            .iter()
            .map(|(_, book)| self.unified_of(book.work))
            .collect()
    }

    fn search(&self, query: &str) -> Vec<Hit> {
        search_all(
            self.catalogues,
            |work| self.unified_of(work),
            query,
            RESULTS,
        )
    }
}

fn best(catalogue: &Catalogue, titles: &str, author: &str, isbns: &str) -> Option<(String, Book)> {
    let searcher = Searcher::new(catalogue);
    let matches = titles.split('|').filter_map(|title| {
        let ranked = searcher.ranked(&searcher.lookup(title), MATCH_DEPTH);
        let mut matching = ranked.into_iter();
        matching.find(|(book, _)| titled(book, title) && by_author(book, author))
    });
    let titled = matches
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(book, _)| book);
    let mut known = isbns.split('|').filter_map(isbn_key);
    let book = titled.or_else(|| known.find_map(|key| catalogue.book_of_isbn(key)))?;
    Some((catalogue.language.clone(), book))
}

fn relevant(book: &Book, expected: &str, author: &str) -> bool {
    let title = expected == "-"
        || expected
            .split('|')
            .any(|wanted| match wanted.strip_prefix('=') {
                Some(exact) => titled(book, exact),
                None => {
                    let text = format!("{} {} {}", book.title, book.subtitle, book.alternate);
                    normalized(&text).contains(&normalized(wanted))
                }
            });
    title && by_author(book, author)
}

#[derive(Default)]
struct Gold {
    passed: usize,
    failed: usize,
    missing: usize,
}

fn rows(text: &str) -> Vec<Vec<String>> {
    let lines = text.lines().filter(|line| !line.trim().is_empty());
    lines
        .map(|line| line.split('\t').map(str::to_string).collect())
        .collect()
}

fn check_gold(context: &Context, text: &str, failures: &mut Vec<String>) -> [Gold; 2] {
    let (mut works, mut apart) = (Gold::default(), Gold::default());
    for row in rows(text) {
        match row
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>()
            .as_slice()
        {
            ["work", author, titles, isbns] => {
                let found = context.found(titles, author, isbns);
                match context.unified_ids(&found).len() {
                    0 => works.missing += 1,
                    1 => works.passed += 1,
                    _ => {
                        works.failed += 1;
                        failures.push(format!("work {titles}: {}", context.describe(&found)));
                    }
                }
            }
            ["apart", first_author, first, second_author, second] => {
                let left = context.found(first, first_author, "-");
                let right = context.found(second, second_author, "-");
                if left.is_empty() || right.is_empty() {
                    apart.missing += 1;
                } else if context
                    .unified_ids(&left)
                    .is_disjoint(&context.unified_ids(&right))
                {
                    apart.passed += 1;
                } else {
                    apart.failed += 1;
                    let (left, right) = (context.describe(&left), context.describe(&right));
                    failures.push(format!("apart {first} / {second}: {left} | {right}"));
                }
            }
            _ => failures.push(format!("gold row not understood: {}", row.join("⇥"))),
        }
    }
    [works, apart]
}

#[derive(Default)]
struct Ranking {
    queries: usize,
    precision: f64,
    reciprocal: f64,
}

fn check_queries(
    context: &Context,
    text: &str,
    failures: &mut Vec<String>,
) -> BTreeMap<String, Ranking> {
    let mut classes: BTreeMap<String, Ranking> = BTreeMap::new();
    for row in rows(text) {
        let [class, query, expected, author] = row.as_slice() else {
            failures.push(format!("query row not understood: {}", row.join("⇥")));
            continue;
        };
        if expected == "-" && author == "-" {
            continue;
        }
        let ranked = context.search(query);
        let hits: Vec<bool> = ranked
            .iter()
            .map(|hit| {
                hit.books
                    .iter()
                    .any(|book| relevant(book, expected, author))
            })
            .collect();
        let top = hits.iter().take(TOP).filter(|hit| **hit).count();
        let first = hits.iter().position(|hit| *hit);
        let ranking = classes.entry(class.clone()).or_default();
        ranking.queries += 1;
        ranking.precision += top as f64 / TOP as f64;
        ranking.reciprocal += first.map_or(0.0, |rank| 1.0 / (rank + 1) as f64);
        if top == 0 {
            let shown: Vec<&str> = ranked
                .iter()
                .take(TOP)
                .map(|hit| hit.books[0].title.as_str())
                .collect();
            failures.push(format!(
                "query {class} «{query}»: top {}",
                shown.join(" · ")
            ));
        }
    }
    classes
}

fn report(summary: &str, gold: &[Gold; 2], classes: &BTreeMap<String, Ranking>) -> String {
    let mut text = format!("## Unify\n\n{summary}\n## QA\n\n| Gold | Pass | Fail | Not found |\n");
    text += "|---|---|---|---|\n";
    for (name, counts) in ["work", "apart"].iter().zip(gold) {
        let line = format!(
            "| {name} | {} | {} | {} |\n",
            counts.passed, counts.failed, counts.missing
        );
        text += &line;
    }
    text += "\n| Class | Queries | P@5 | MRR |\n|---|---|---|---|\n";
    let mut total = Ranking::default();
    for (class, ranking) in classes {
        let queries = ranking.queries as f64;
        let (precision, reciprocal) = (ranking.precision / queries, ranking.reciprocal / queries);
        let _ = writeln!(
            text,
            "| {class} | {} | {precision:.2} | {reciprocal:.2} |",
            ranking.queries
        );
        total.queries += ranking.queries;
        total.precision += ranking.precision;
        total.reciprocal += ranking.reciprocal;
    }
    let queries = total.queries.max(1) as f64;
    let _ = writeln!(
        text,
        "| all | {} | {:.2} | {:.2} |",
        total.queries,
        total.precision / queries,
        total.reciprocal / queries
    );
    text
}

pub fn command(arguments: &[String]) {
    let [directory, rest @ ..] = arguments else {
        crate::usage()
    };
    let read = |name: &str| {
        let path = Path::new(rest.first()?).join(name);
        Some(std::fs::read_to_string(&path).unwrap_or_else(|error| panic!("read {name}: {error}")))
    };
    let gold = read("gold.tsv").unwrap_or_else(|| GOLD.to_string());
    let queries = read("queries.tsv").unwrap_or_else(|| QUERIES.to_string());
    let release = unify::load(Path::new(directory));
    let (works, stats) = unify::unify(&release.nodes, None, &release.aliases);
    let context = Context {
        catalogues: &release.catalogues,
        unified: Works::unified_ids(&works),
    };
    let mut failures = Vec::new();
    let gold = check_gold(&context, &gold, &mut failures);
    let classes = check_queries(&context, &queries, &mut failures);
    print!(
        "{}",
        report(&unify::summary(&works, &stats), &gold, &classes)
    );
    println!(
        "\n<details><summary>{} failures</summary>\n",
        failures.len()
    );
    failures.iter().for_each(|failure| println!("- {failure}"));
    println!("\n</details>");
}
