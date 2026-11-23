//! Second pass over a built release: language versions joined in `works.bin` lend each other
//! descriptions to translate and themes, patched into the release's own segments.

use builder::catalog::{DESCRIPTION_MIN_SCORE, SOURCE_LANGUAGES, detect_language};
use builder::reader::{Catalogue, Description, Ranks, Segment, parse_name};
use builder::sidecar::{EXTRAS, Sidecar};
use builder::translations::{Request, Translations, write_requests};
use builder::works::Works;
use builder::{LANGUAGES, language_index, segment, tags};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::Path;

#[derive(Default)]
struct Group {
    titles: HashMap<usize, String>,
    descriptions: Vec<(usize, String)>,
    themes: Vec<(u16, u8)>,
}

impl Group {
    fn add_theme(&mut self, theme: u16) {
        match self.themes.iter_mut().find(|(known, _)| *known == theme) {
            Some(entry) => entry.1 = entry.1.saturating_add(1),
            None => self.themes.push((theme, 1)),
        }
    }

    fn add_description(&mut self, language: usize, text: String) {
        if !text.is_empty()
            && self
                .descriptions
                .iter()
                .all(|(known, _)| *known != language)
        {
            self.descriptions.push((language, text));
        }
    }

    fn source(&self, target: usize) -> Option<&(usize, String)> {
        let foreign = self
            .descriptions
            .iter()
            .filter(|(language, _)| *language != target);
        foreign.min_by_key(|(language, text)| (*language, usize::MAX - text.len()))
    }

    fn ranked_themes(&self) -> Vec<u16> {
        let mut ranked = self.themes.clone();
        ranked.sort_by_key(|&(theme, count)| (std::cmp::Reverse(count), theme));
        ranked.into_iter().map(|(theme, _)| theme).collect()
    }
}

fn source_index(code: &str) -> Option<usize> {
    SOURCE_LANGUAGES.iter().position(|&known| known == code)
}

fn open_catalogues(release: &Path) -> Vec<Option<Catalogue>> {
    let open = |code: &&str| Catalogue::open(release, code).ok();
    let opened = LANGUAGES.iter().map(open);
    opened
        .map(|catalogue| catalogue.filter(|found| found.work_count() > 0))
        .collect()
}

type Member = (usize, u32);

fn groups(works: &Works) -> (Vec<Vec<Member>>, HashMap<Member, usize>) {
    let mut members = Vec::new();
    let mut group_of = HashMap::new();
    for (_, listed) in &works.works {
        let joined: Vec<Member> = listed
            .iter()
            .map(|&(language, work)| (language_index(&works.languages[language as usize]), work))
            .collect();
        joined.iter().for_each(|&member| {
            group_of.insert(member, members.len());
        });
        members.push(joined);
    }
    (members, group_of)
}

fn collect_descriptions(
    release: &Path,
    group_of: &HashMap<Member, usize>,
    pooled: &mut [Group],
    catalogues: &[Option<Catalogue>],
) {
    let bytes = std::fs::read(release.join("extras.bin")).expect("read extras.bin");
    let extras = Sidecar::parse(EXTRAS, &bytes).expect("extras sidecar");
    for (language, catalogue) in catalogues.iter().enumerate() {
        let Some(catalogue) = catalogue else {
            continue;
        };
        let code = LANGUAGES[language];
        for found in extras.extras(code).unwrap_or_default() {
            let Some(&group) = group_of.get(&(language, found.work)) else {
                continue;
            };
            let translated = catalogue
                .description(found.work)
                .is_some_and(|own| own.translated);
            for (source, text) in found.descriptions {
                let Some(source) = source_index(&source) else {
                    continue;
                };
                if source == language && translated {
                    continue;
                }
                pooled[group].add_description(source, text);
            }
        }
        eprintln!("{code}: descriptions pooled");
    }
}

fn collect_facts(members: &[Vec<Member>], pooled: &mut [Group], catalogues: &[Option<Catalogue>]) {
    for (group, listed) in pooled.iter_mut().zip(members) {
        for &(language, work) in listed {
            let Some(catalogue) = &catalogues[language] else {
                continue;
            };
            let Some(book) = catalogue.book(work) else {
                continue;
            };
            group.titles.insert(language, book.title);
            book.themes.iter().for_each(|&theme| group.add_theme(theme));
            let series = catalogue.series(work).map(|series| series.name);
            let named = series.iter().flat_map(|name| tags::heading_themes(name));
            named.for_each(|theme| group.add_theme(theme));
        }
    }
}

fn request(
    catalogue: &Catalogue,
    work: u32,
    group: &Group,
    source: &(usize, String),
) -> Option<Request> {
    let book = catalogue.book(work)?;
    let (language, text) = source;
    Some(Request {
        work,
        source_title: group.titles.get(language).unwrap_or(&book.title).clone(),
        title: book.title,
        authors: book.authors.into_iter().map(|author| author.name).collect(),
        source_language: Some(SOURCE_LANGUAGES[*language]),
        text: text.clone(),
    })
}

/// `builder pool <release dir> <out dir>` writes `requests-<language>.jsonl` and
/// `themes-<language>.tsv` for the works another language version can supplement.
pub fn pool(arguments: &[String]) {
    let [release, output] = arguments else {
        crate::usage()
    };
    let (release, output) = (Path::new(release), Path::new(output));
    std::fs::create_dir_all(output).expect("create pool directory");
    let works = Works::load(&release.join("works.bin")).expect("read works.bin");
    let (members, group_of) = groups(&works);
    let catalogues = open_catalogues(release);
    let mut pooled: Vec<Group> = members.iter().map(|_| Group::default()).collect();
    collect_descriptions(release, &group_of, &mut pooled, &catalogues);
    collect_facts(&members, &mut pooled, &catalogues);
    for (language, catalogue) in catalogues.iter().enumerate() {
        let Some(catalogue) = catalogue else {
            continue;
        };
        let code = LANGUAGES[language];
        let translations = Translations::load(&release.join(format!("translations-{code}.bin")));
        let mut requests = Vec::new();
        let mut themes = String::new();
        for (group, listed) in pooled.iter().zip(&members) {
            for &(_, work) in listed.iter().filter(|(member, _)| *member == language) {
                let needs_description = catalogue.description(work).is_none()
                    && translations.get(work).is_none()
                    && catalogue.score(work) >= f64::from(DESCRIPTION_MIN_SCORE);
                let source = group.source(language).filter(|_| needs_description);
                let wanted = source.and_then(|source| request(catalogue, work, group, source));
                requests.extend(wanted.map(|request| (catalogue.score(work), request)));
                let ranked = group.ranked_themes();
                if !ranked.is_empty() {
                    let listed: Vec<String> = ranked.iter().map(u16::to_string).collect();
                    themes += &format!("{work}\t{}\n", listed.join(","));
                }
            }
        }
        requests.sort_by(|left, right| right.0.total_cmp(&left.0));
        let requests: Vec<Request> = requests.into_iter().map(|(_, request)| request).collect();
        write_requests(&output.join(format!("requests-{code}.jsonl")), &requests);
        std::fs::write(output.join(format!("themes-{code}.tsv")), themes).expect("write themes");
    }
}

fn read_themes(path: &Path) -> HashMap<u32, Vec<u16>> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let rows = text.lines().filter_map(|line| line.split_once('\t'));
    rows.filter_map(|(work, themes)| {
        let themes = themes
            .split(',')
            .filter_map(|theme| theme.parse().ok())
            .collect();
        Some((work.parse().ok()?, themes))
    })
    .collect()
}

fn ranks(directory: &Path, code: &str) -> Option<Ranks> {
    let files = std::fs::read_dir(directory)
        .ok()?
        .flatten()
        .map(|entry| entry.path());
    let mut found: Vec<_> = files
        .filter(|path| {
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default();
            parse_name(name).is_some_and(|(language, pack, _)| language == code && pack == "ranks")
        })
        .collect();
    found.sort();
    Ranks::open(found.last()?)
}

struct Supplements<'a> {
    language: usize,
    ranks: &'a Ranks,
    translations: &'a Translations,
    themes: &'a HashMap<u32, Vec<u16>>,
}

impl Supplements<'_> {
    fn description(&self, work: u32) -> Option<Description> {
        let score = self.ranks.popularity(work).map_or(0.0, |found| found.score);
        if score < f64::from(DESCRIPTION_MIN_SCORE) {
            return None;
        }
        let text = self.translations.get(work)?;
        let native = detect_language(text).is_none_or(|found| found == self.language);
        native.then(|| Description {
            text: text.to_string(),
            translated: true,
        })
    }

    fn themes(&self, work: u32, tags: &[u8], own: &[u16]) -> Vec<u16> {
        let pooled = self.themes.get(&work).into_iter().flatten();
        let mut merged: Vec<(u16, u8)> = Vec::new();
        for &theme in own.iter().chain(pooled) {
            if merged.iter().all(|(known, _)| *known != theme) {
                merged.push((theme, 0));
            }
        }
        tags::chosen_themes(tags, &merged)
    }

    fn patched(&self, segment: &Segment) -> Option<Vec<u8>> {
        let mut changed = 0;
        let mut descriptions = Vec::new();
        let mut themes = Vec::new();
        for local in 0..segment.work_count() {
            let work = segment.work(local);
            let own = segment.description(local);
            let added = own.is_none().then(|| self.description(work)).flatten();
            changed += usize::from(added.is_some());
            descriptions.push(own.or(added));
            let facts = segment.facts(local);
            let merged = self.themes(work, &facts.tags, &facts.themes);
            changed += usize::from(merged != facts.themes);
            themes.push(merged);
        }
        eprintln!(
            "{} {}: {changed} works supplemented",
            segment.language, segment.pack
        );
        (changed > 0).then(|| segment::rewrite(segment, &descriptions, &themes))
    }
}

fn update_manifest(path: &Path, file: &str, bytes: &[u8]) {
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    let mut manifest: serde_json::Value = serde_json::from_str(&text).expect("parse manifest");
    let sha256: String = Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    let entries = manifest["segments"].as_array_mut().into_iter().flatten();
    let id = file.trim_end_matches(".bin");
    for entry in entries.filter(|entry| entry["id"] == id) {
        entry["size"] = bytes.len().into();
        entry["sha256"] = sha256.clone().into();
    }
    let text = serde_json::to_string_pretty(&manifest).expect("manifest json");
    std::fs::write(path, text).expect("write manifest");
}

/// `builder patch <release dir> <language> <pool dir>` adds stored translations and pooled
/// themes to that language's segments in the directory and updates `manifest-<language>.json`.
pub fn patch(arguments: &[String]) {
    let [directory, code, pool] = arguments else {
        crate::usage()
    };
    let directory = Path::new(directory);
    let translations = Translations::load(&directory.join(format!("translations-{code}.bin")));
    let themes = read_themes(&Path::new(pool).join(format!("themes-{code}.tsv")));
    let ranks = ranks(directory, code).expect("ranks segment");
    let supplements = Supplements {
        language: language_index(code),
        ranks: &ranks,
        translations: &translations,
        themes: &themes,
    };
    let manifest = directory.join(format!("manifest-{code}.json"));
    let mut files: Vec<_> = std::fs::read_dir(directory)
        .expect("read directory")
        .flatten()
        .collect();
    files.sort_by_key(|entry| entry.file_name());
    for path in files.into_iter().map(|entry| entry.path()) {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or_default()
            .to_string();
        let Some((language, pack, _)) = parse_name(&name) else {
            continue;
        };
        if language != code || pack == "ranks" {
            continue;
        }
        let segment = Segment::open(&path).expect("read segment");
        let Some(bytes) = supplements.patched(&segment) else {
            continue;
        };
        std::fs::write(&path, &bytes).expect("write segment");
        update_manifest(&manifest, &name, &bytes);
    }
}
