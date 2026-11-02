//! One book layer across the language catalogues (`works.bin`), see CONTRIBUTING.
//!
//! Unification only links across languages: a unified work holds at most one work per
//! language, every merge checks it and the result is asserted. Within-language dedupe
//! stays in the per-language builds.

use crate::LANGUAGES;
use crate::libraries::{SYNTHETIC, match_key};
use crate::reader::Catalogue;
use crate::sidecar::{LINKS, Links, Sidecar};
use crate::works::Works;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;

const VOTE_RECORDS: u32 = 2;
const VOTE_SHARE: f64 = 0.6;
const ALIAS_STEPS: usize = 8;

pub struct Node {
    pub language: usize,
    pub work: u32,
    pub titles: Vec<String>,
    pub links: Links,
}

impl Node {
    fn is_open_library(&self) -> bool {
        self.work < SYNTHETIC
    }

    fn language_known(&self) -> bool {
        !self.links.language.is_empty()
    }

    fn other_language(&self, other: &Node) -> bool {
        self.language_known()
            && other.language_known()
            && self.links.language != other.links.language
    }

    /// The original title most records name, when it holds `VOTE_SHARE` of the votes.
    fn voted_original(&self) -> Option<(&str, u32)> {
        let total: u32 = self.links.originals.iter().map(|(_, count)| count).sum();
        let (key, count) = self.links.originals.first()?;
        let share = f64::from(*count) / f64::from(total);
        (share >= VOTE_SHARE).then_some((key.as_str(), *count))
    }

    fn original_title(&self) -> Option<&str> {
        if self.links.originals.is_empty() {
            let key = self.links.original_title.as_str();
            return (!key.is_empty()).then_some(key);
        }
        self.voted_original().map(|(key, _)| key)
    }
}

fn shares_authority(left: &Node, right: &Node) -> bool {
    let theirs = &right.links.authorities;
    left.links.authorities.iter().any(|id| theirs.contains(id))
}

fn authorities_conflict(left: &Node, right: &Node) -> bool {
    let scheme = |id: &String| {
        id.split_once(':')
            .map_or(id.clone(), |(scheme, _)| scheme.to_string())
    };
    let theirs: HashSet<String> = right.links.authorities.iter().map(scheme).collect();
    left.links
        .authorities
        .iter()
        .any(|id| theirs.contains(&scheme(id)))
        && !shares_authority(left, right)
}

fn same_author(left: &Node, right: &Node) -> bool {
    let named = !left.links.author.is_empty() && left.links.author == right.links.author;
    shares_authority(left, right) || named && !authorities_conflict(left, right)
}

#[derive(Default)]
pub struct Stats {
    pub nodes: usize,
    pub same_id: usize,
    pub wikidata: usize,
    pub votes: usize,
    pub library: usize,
    pub ambiguous: usize,
    pub occupied: usize,
    pub wikidata_conflicts: usize,
}

struct Clusters {
    of: Vec<usize>,
    members: Vec<Vec<usize>>,
    languages: Vec<u32>,
}

impl Clusters {
    fn new(nodes: &[Node]) -> Clusters {
        Clusters {
            of: (0..nodes.len()).collect(),
            members: (0..nodes.len()).map(|node| vec![node]).collect(),
            languages: nodes.iter().map(|node| 1 << node.language).collect(),
        }
    }

    /// Joins two clusters unless they share a language; true when joined.
    fn merge(&mut self, left: usize, right: usize) -> bool {
        let (left, right) = (self.of[left], self.of[right]);
        if left == right {
            return true;
        }
        if self.languages[left] & self.languages[right] != 0 {
            return false;
        }
        let (kept, moved) = if self.members[left].len() >= self.members[right].len() {
            (left, right)
        } else {
            (right, left)
        };
        let members = std::mem::take(&mut self.members[moved]);
        members.iter().for_each(|&node| self.of[node] = kept);
        self.members[kept].extend(members);
        self.languages[kept] |= self.languages[moved];
        true
    }

    fn languages_of(&self, node: usize) -> u32 {
        self.languages[self.of[node]]
    }

    fn has_open_library(&self, nodes: &[Node], node: usize) -> bool {
        self.members[self.of[node]]
            .iter()
            .any(|&member| nodes[member].is_open_library())
    }

    /// The one Wikidata item of a cluster: None without, Err on disagreement.
    fn wikidata(&self, nodes: &[Node], node: usize) -> Option<Result<u32, ()>> {
        let mut items = self.members[self.of[node]]
            .iter()
            .map(|&member| nodes[member].links.wikidata)
            .filter(|&item| item != 0);
        let first = items.next()?;
        Some(if items.all(|item| item == first) {
            Ok(first)
        } else {
            Err(())
        })
    }

    /// Applies `(source, target)` proposals; sources fighting over one language refuse.
    fn apply(&mut self, proposals: Vec<(usize, usize)>, refused: &mut usize) -> usize {
        let mut by_target: BTreeMap<usize, Vec<usize>> = BTreeMap::new();
        for (source, target) in proposals {
            by_target.entry(self.of[target]).or_default().push(source);
        }
        let mut merged = 0;
        for (target, sources) in by_target {
            let (mut once, mut twice) = (0u32, 0u32);
            for &source in &sources {
                let languages = self.languages_of(source);
                twice |= once & languages;
                once |= languages;
            }
            let conflicting = twice | once & self.languages_of(target);
            for source in sources {
                let free = self.languages_of(source) & conflicting == 0;
                if free && self.merge(source, target) {
                    merged += 1;
                } else {
                    *refused += 1;
                }
            }
        }
        merged
    }
}

fn index_by<'a, K: std::hash::Hash + Eq>(
    nodes: &'a [Node],
    keep: impl Fn(usize) -> bool,
    keys: impl Fn(&'a Node) -> Vec<K>,
) -> HashMap<K, Vec<usize>> {
    let mut index: HashMap<K, Vec<usize>> = HashMap::new();
    for (position, node) in nodes
        .iter()
        .enumerate()
        .filter(|(position, _)| keep(*position))
    {
        for key in keys(node) {
            index.entry(key).or_default().push(position);
        }
    }
    index
}

fn same_ids(nodes: &[Node], clusters: &mut Clusters, stats: &mut Stats) {
    let by_work = index_by(nodes, |_| true, |node| vec![node.work]);
    for same in by_work.values().filter(|same| same.len() > 1) {
        for &node in &same[1..] {
            assert!(clusters.merge(same[0], node), "one id twice in a language");
            stats.same_id += 1;
        }
    }
}

fn wikidata_merges(nodes: &[Node], clusters: &mut Clusters, stats: &mut Stats) {
    let mut by_item: BTreeMap<u32, Vec<usize>> = BTreeMap::new();
    let mut seen = HashSet::new();
    for node in 0..nodes.len() {
        if !clusters.has_open_library(nodes, node) || !seen.insert(clusters.of[node]) {
            continue;
        }
        match clusters.wikidata(nodes, node) {
            Some(Ok(item)) => by_item.entry(item).or_default().push(node),
            Some(Err(())) => stats.wikidata_conflicts += 1,
            None => {}
        }
    }
    for group in by_item.values().filter(|group| group.len() > 1) {
        let masks = group.iter().map(|&node| clusters.languages_of(node));
        let union = masks.clone().fold(0, |union, mask| union | mask);
        if masks.map(u32::count_ones).sum::<u32>() != union.count_ones() {
            stats.ambiguous += 1;
            continue;
        }
        for &node in &group[1..] {
            assert!(clusters.merge(group[0], node), "disjoint languages merge");
            stats.wikidata += 1;
        }
    }
}

fn wikidata_clash(nodes: &[Node], clusters: &Clusters, left: usize, right: usize) -> bool {
    let item = |node| clusters.wikidata(nodes, node);
    match (item(left), item(right)) {
        (Some(Ok(left)), Some(Ok(right))) => left != right,
        (Some(Err(())), _) | (_, Some(Err(()))) => true,
        _ => false,
    }
}

fn distinct_clusters(clusters: &Clusters, found: impl Iterator<Item = usize>) -> Vec<usize> {
    let mut targets: Vec<usize> = found.map(|node| clusters.of[node]).collect();
    targets.sort_unstable();
    targets.dedup();
    targets
}

fn vote_merges(nodes: &[Node], clusters: &mut Clusters, stats: &mut Stats) {
    let open_library = |position: usize| nodes[position].is_open_library();
    let by_title = index_by(nodes, open_library, |node| {
        node.titles.iter().map(String::as_str).collect()
    });
    let mut proposals = Vec::new();
    for (position, node) in nodes.iter().enumerate() {
        if !node.is_open_library() || node.links.compilation || !node.language_known() {
            continue;
        }
        let Some((key, support)) = node.voted_original() else {
            continue;
        };
        if support < VOTE_RECORDS {
            continue;
        }
        let found = by_title
            .get(key)
            .into_iter()
            .flatten()
            .copied()
            .filter(|&other| {
                let candidate = &nodes[other];
                clusters.of[other] != clusters.of[position]
                    && !candidate.links.compilation
                    && node.other_language(candidate)
                    && node.links.records <= candidate.links.records
                    && same_author(node, candidate)
                    && !wikidata_clash(nodes, clusters, position, other)
            });
        let found: Vec<usize> = found.collect();
        match distinct_clusters(clusters, found.iter().copied()).len() {
            0 => {}
            1 => proposals.push((position, found[0])),
            _ => stats.ambiguous += 1,
        }
    }
    stats.votes += clusters.apply(proposals, &mut stats.occupied);
}

fn library_evidence(
    nodes: &[Node],
    clusters: &Clusters,
    member: usize,
    indexes: &LibraryIndexes,
    stats: &mut Stats,
) -> Vec<usize> {
    let node = &nodes[member];
    let item = node.links.wikidata;
    let mut found: Vec<usize> = Vec::new();
    if item != 0 {
        found.extend(indexes.items.get(&item).into_iter().flatten());
    }
    let comparable = |other: &usize| node.other_language(&nodes[*other]);
    if let Some(original) = node.original_title().filter(|_| !node.links.compilation) {
        let titled = indexes.titles.get(original).into_iter().flatten().copied();
        let authored = titled.filter(|&other| shares_authority(node, &nodes[other]));
        found.extend(authored.filter(comparable));
    }
    if !node.links.series.is_empty() && node.links.position > 0 && !node.links.compilation {
        let key = (node.links.series.clone(), node.links.position);
        let volumes = indexes.series.get(&key).into_iter().flatten().copied();
        let authored = volumes.filter(|&other| same_author(node, &nodes[other]));
        found.extend(authored.filter(comparable));
    }
    let clashes = |other: &usize| {
        let theirs = clusters.wikidata(nodes, *other);
        item != 0 && theirs.is_some_and(|theirs| theirs != Ok(item))
    };
    if found.iter().any(clashes) {
        stats.wikidata_conflicts += 1;
        return Vec::new();
    }
    found
}

struct LibraryIndexes<'a> {
    items: HashMap<u32, Vec<usize>>,
    titles: HashMap<&'a str, Vec<usize>>,
    series: HashMap<(String, u16), Vec<usize>>,
}

fn library_merges(nodes: &[Node], clusters: &mut Clusters, stats: &mut Stats) {
    let targets: Vec<bool> = (0..nodes.len())
        .map(|node| clusters.has_open_library(nodes, node))
        .collect();
    let target = |position: usize| targets[position];
    let indexes = LibraryIndexes {
        items: index_by(nodes, target, |node| {
            [node.links.wikidata]
                .into_iter()
                .filter(|&item| item != 0)
                .collect()
        }),
        titles: index_by(nodes, target, |node| {
            node.titles.iter().map(String::as_str).collect()
        }),
        series: index_by(nodes, target, |node| {
            let numbered = !node.links.series.is_empty() && node.links.position > 0;
            let key = (node.links.series.clone(), node.links.position);
            numbered.then_some(key).into_iter().collect()
        }),
    };
    let mut proposals = Vec::new();
    let mut unclaimed: BTreeMap<u32, Vec<usize>> = BTreeMap::new();
    let mut seen = HashSet::new();
    for (position, &target) in targets.iter().enumerate() {
        if target || !seen.insert(clusters.of[position]) {
            continue;
        }
        let members = clusters.members[clusters.of[position]].clone();
        let found: Vec<usize> = members
            .iter()
            .flat_map(|&member| library_evidence(nodes, clusters, member, &indexes, stats))
            .collect();
        match distinct_clusters(clusters, found.iter().copied()).len() {
            0 => match clusters.wikidata(nodes, position) {
                Some(Ok(item)) => unclaimed.entry(item).or_default().push(position),
                Some(Err(())) => stats.wikidata_conflicts += 1,
                None => {}
            },
            1 => proposals.push((position, found[0])),
            _ => stats.ambiguous += 1,
        }
    }
    stats.library += clusters.apply(proposals, &mut stats.occupied);
    for group in unclaimed.values().filter(|group| group.len() > 1) {
        let proposals = group[1..].iter().map(|&node| (node, group[0])).collect();
        stats.library += clusters.apply(proposals, &mut stats.ambiguous);
    }
}

fn assert_invariant(nodes: &[Node], clusters: &Clusters) {
    let mut placed = 0;
    for members in &clusters.members {
        let languages: HashSet<usize> = members.iter().map(|&node| nodes[node].language).collect();
        assert_eq!(
            languages.len(),
            members.len(),
            "two works of one language in a unified work"
        );
        assert!(
            members.len() <= LANGUAGES.len(),
            "unified work larger than the languages"
        );
        placed += members.len();
    }
    assert_eq!(placed, nodes.len(), "every work placed once");
}

/// Smallest Open Library id, else the oldest synthetic id: a previous unified id, then a
/// previous member, then the smallest.
fn unified_id(nodes: &[Node], members: &[usize], before: &[HashSet<u32>; 2]) -> u32 {
    let works = || members.iter().map(|&node| nodes[node].work);
    let open_library = works().filter(|&work| work < SYNTHETIC).min();
    let oldest = before
        .iter()
        .find_map(|known| works().filter(|work| known.contains(work)).min());
    open_library
        .or(oldest)
        .or_else(|| works().min())
        .expect("members")
}

struct Current<'a> {
    unified: &'a HashMap<u32, u32>,
    nodes: &'a HashSet<u32>,
    aliases: &'a HashMap<u32, u32>,
}

impl Current<'_> {
    fn id_of(&self, mut work: u32) -> Option<u32> {
        for _ in 0..ALIAS_STEPS {
            if let Some(&unified) = self.unified.get(&work) {
                return Some(unified);
            }
            if self.nodes.contains(&work) {
                return Some(work);
            }
            work = *self.aliases.get(&work)?;
        }
        None
    }
}

fn aliases(previous: &Works, current: &Current) -> Vec<(u32, u32)> {
    let mut moved: HashMap<u32, u32> = HashMap::new();
    for (old, members) in &previous.works {
        let locals = members.iter().map(|&(_, local)| local);
        let found = current
            .id_of(*old)
            .or_else(|| locals.into_iter().find_map(|local| current.id_of(local)));
        moved.extend(found.map(|new| (*old, new)));
    }
    let mut aliases: Vec<(u32, u32)> = moved
        .iter()
        .map(|(&old, &new)| (old, new))
        .chain(previous.aliases.iter().filter_map(|&(from, to)| {
            let new = moved.get(&to).copied().or_else(|| current.id_of(to))?;
            Some((from, new))
        }))
        .filter(|&(from, to)| from != to && !current.unified.values().any(|&id| id == from))
        .collect();
    aliases.sort_unstable();
    aliases.dedup_by_key(|&mut (from, _)| from);
    aliases
}

/// `language_aliases`: within-language renames (ALIA) of every catalogue, old → new.
pub fn unify(
    nodes: &[Node],
    previous: Option<&Works>,
    language_aliases: &HashMap<u32, u32>,
) -> (Works, Stats) {
    let mut stats = Stats {
        nodes: nodes.len(),
        ..Stats::default()
    };
    let mut clusters = Clusters::new(nodes);
    same_ids(nodes, &mut clusters, &mut stats);
    wikidata_merges(nodes, &mut clusters, &mut stats);
    vote_merges(nodes, &mut clusters, &mut stats);
    library_merges(nodes, &mut clusters, &mut stats);
    assert_invariant(nodes, &clusters);

    let listed = previous.into_iter().flat_map(|works| &works.works);
    let before = [
        listed.clone().map(|(unified, _)| *unified).collect(),
        listed
            .flat_map(|(_, members)| members.iter().map(|&(_, local)| local))
            .collect(),
    ];
    let mut works: Vec<(u32, Vec<(u8, u32)>)> = clusters
        .members
        .iter()
        .filter(|members| members.len() > 1)
        .map(|members| {
            let mut placed: Vec<(u8, u32)> = members
                .iter()
                .map(|&node| (nodes[node].language as u8, nodes[node].work))
                .collect();
            placed.sort_unstable();
            (unified_id(nodes, members, &before), placed)
        })
        .collect();
    works.sort_unstable();
    let unified: HashMap<u32, u32> = works
        .iter()
        .flat_map(|(id, members)| members.iter().map(move |&(_, local)| (local, *id)))
        .collect();
    let ids: HashSet<u32> = nodes.iter().map(|node| node.work).collect();
    let current = Current {
        unified: &unified,
        nodes: &ids,
        aliases: language_aliases,
    };
    let aliases = previous.map_or_else(Vec::new, |previous| aliases(previous, &current));
    let languages = LANGUAGES.iter().map(|code| code.to_string()).collect();
    let works = Works {
        languages,
        works,
        aliases,
    };
    (works, stats)
}

pub struct Release {
    pub catalogues: Vec<Catalogue>,
    pub nodes: Vec<Node>,
    pub aliases: HashMap<u32, u32>,
}

fn manifest_languages(directory: &Path) -> Vec<usize> {
    let path = directory.join("manifest.json");
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("read {}: {error}", path.display()));
    let manifest: serde_json::Value = serde_json::from_str(&text).expect("parse manifest");
    let mut languages = Vec::new();
    for entry in manifest["segments"].as_array().into_iter().flatten() {
        let id = entry["id"].as_str().unwrap_or_default();
        let file = directory.join(format!("{id}.bin"));
        assert!(
            file.exists(),
            "{} missing: download the whole chain",
            file.display()
        );
        let code = entry["language"].as_str().unwrap_or_default();
        languages.extend(LANGUAGES.iter().position(|&known| known == code));
    }
    languages.sort_unstable();
    languages.dedup();
    languages
}

fn sidecar_links(directory: &Path) -> HashMap<(usize, u32), Links> {
    let mut links = HashMap::new();
    for entry in std::fs::read_dir(directory).expect("read release directory") {
        let path = entry.expect("directory entry").path();
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or_default();
        if !name.starts_with("links") || !name.ends_with(".bin") {
            continue;
        }
        let bytes = std::fs::read(&path).expect("read links");
        let sidecar = Sidecar::parse(LINKS, &bytes)
            .unwrap_or_else(|| panic!("{} is not a links file", path.display()));
        for (code, _) in &sidecar.languages {
            let Some(language) = LANGUAGES.iter().position(|known| known == code) else {
                continue;
            };
            for found in sidecar.links(code).expect("decode links") {
                links.insert((language, found.work), found);
            }
        }
    }
    links
}

fn title_keys(title: &str, alternate: &str) -> Vec<String> {
    let mut keys: Vec<String> = [title, alternate]
        .into_iter()
        .map(match_key)
        .filter(|key| !key.is_empty())
        .collect();
    keys.dedup();
    keys
}

pub fn load(directory: &Path) -> Release {
    let mut links = sidecar_links(directory);
    let mut catalogues = Vec::new();
    let mut nodes = Vec::new();
    let mut aliases = HashMap::new();
    for language in manifest_languages(directory) {
        let code = LANGUAGES[language];
        let catalogue = Catalogue::open(directory, code).expect("open catalogue");
        for (index, segment) in catalogue.segments.iter().enumerate() {
            for (local, heads) in segment.all_heads().into_iter().enumerate() {
                if !catalogue.is_visible(index, local) {
                    continue;
                }
                let work = segment.work(local);
                nodes.push(Node {
                    language,
                    work,
                    titles: title_keys(&heads.title, &heads.alternate),
                    links: links.remove(&(language, work)).unwrap_or_default(),
                });
            }
        }
        aliases.extend(
            catalogue
                .ranks
                .iter()
                .flat_map(|ranks| ranks.aliases().iter().copied()),
        );
        eprintln!("{code}: {} works", catalogue.work_count());
        catalogues.push(catalogue);
    }
    Release {
        catalogues,
        nodes,
        aliases,
    }
}

pub fn summary(works: &Works, stats: &Stats) -> String {
    let mut sizes: BTreeMap<usize, usize> = BTreeMap::new();
    for (_, members) in &works.works {
        *sizes.entry(members.len()).or_default() += 1;
    }
    let histogram: Vec<String> = sizes
        .iter()
        .map(|(languages, count)| format!("{languages}: {count}"))
        .collect();
    let linked: usize = sizes
        .iter()
        .map(|(languages, count)| languages * count)
        .sum();
    let unified = stats.nodes - linked + works.works.len();
    format!(
        "| Unify | Count |\n|---|---|\n\
         | works (all catalogues) | {} |\n| unified works | {unified} |\n\
         | multi-language works | {} |\n| languages per work | {} |\n\
         | links: same id / Wikidata / votes / library | {} / {} / {} / {} |\n\
         | refused: ambiguous / language taken / Wikidata conflict | {} / {} / {} |\n\
         | aliases | {} |\n",
        stats.nodes,
        works.works.len(),
        histogram.join(", "),
        stats.same_id,
        stats.wikidata,
        stats.votes,
        stats.library,
        stats.ambiguous,
        stats.occupied,
        stats.wikidata_conflicts,
        works.aliases.len(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::language_index;

    const LIBRARY: u32 = SYNTHETIC;

    fn node(language: &str, work: u32, title: &str) -> Node {
        Node {
            language: language_index(language),
            work,
            titles: title_keys(title, ""),
            links: Links {
                work,
                author: "austen jane".to_string(),
                authorities: vec!["viaf:102333412".to_string()],
                language: language.to_string(),
                records: 1,
                ..Links::default()
            },
        }
    }

    fn voted(mut node: Node, votes: &[(&str, u32)]) -> Node {
        node.links.originals = votes
            .iter()
            .map(|&(key, count)| (key.to_string(), count))
            .collect();
        node.links.records = votes.iter().map(|(_, count)| count).sum();
        node
    }

    fn item(mut node: Node, wikidata: u32) -> Node {
        node.links.wikidata = wikidata;
        node
    }

    fn unified(nodes: &[Node]) -> Works {
        unify(nodes, None, &HashMap::new()).0
    }

    fn group_of(works: &Works, work: u32) -> Option<u32> {
        works.unified_ids().get(&work).copied()
    }

    #[test]
    fn translations_link() {
        let mut original = item(node("en", 30, "Dune"), 100);
        original.links.records = 50;
        let nodes = [
            original,
            node("fr", 30, "Dune"),
            item(node("de", 31, "Der Wüstenplanet"), 100),
            voted(node("es", 32, "Duna"), &[("dune", 3)]),
            voted(node("pl", LIBRARY + 7, "Diuna"), &[("dune", 1)]),
            node("ja", LIBRARY + 8, "デューン"),
        ];
        let works = unified(&nodes);
        assert_eq!(works.works.len(), 1);
        let (id, members) = &works.works[0];
        assert_eq!(*id, 30);
        let languages: Vec<u8> = members.iter().map(|&(language, _)| language).collect();
        assert_eq!(languages.len(), 5);
        assert_eq!(group_of(&works, LIBRARY + 8), None);
    }

    #[test]
    fn persuasion_emma_original_title_trap() {
        let mut emma = node("en", 10, "Emma");
        let mut persuasion = node("en", 11, "Persuasion");
        (emma.links.records, persuasion.links.records) = (40, 40);
        let nodes = [
            emma,
            persuasion,
            voted(
                node("de", LIBRARY + 1, "Emma"),
                &[("emma", 2), ("persuasion", 1)],
            ),
            voted(node("fr", LIBRARY + 2, "Persuasion"), &[("persuasion", 1)]),
            voted(node("es", 12, "Emma"), &[("emma", 2), ("persuasion", 1)]),
            voted(
                node("sv", 15, "Emma eller Persuasion"),
                &[("persuasion", 1), ("emma", 1)],
            ),
        ];
        let works = unified(&nodes);
        assert_eq!(group_of(&works, LIBRARY + 1), Some(10));
        assert_eq!(group_of(&works, 12), Some(10));
        assert_eq!(group_of(&works, LIBRARY + 2), Some(11));
        assert_eq!(group_of(&works, 15), None);
        assert_ne!(group_of(&works, 10), group_of(&works, 11));
    }

    #[test]
    fn compilations_never_link() {
        let mut both = voted(node("fr", LIBRARY + 3, "Emma ; Persuasion"), &[("emma", 4)]);
        both.links.compilation = true;
        let mut omnibus = voted(node("de", 13, "Emma / Persuasion"), &[("emma", 4)]);
        omnibus.links.compilation = true;
        let works = unified(&[node("en", 10, "Emma"), both, omnibus]);
        assert!(works.works.is_empty());
    }

    #[test]
    fn same_language_duplicates_never_merge() {
        let nodes = [
            item(node("en", 20, "Emma"), 5),
            item(node("en", 21, "Emma"), 5),
            node("en", 10, "Persuasion"),
            voted(node("de", LIBRARY + 4, "Überredung"), &[("persuasion", 1)]),
            voted(node("de", LIBRARY + 5, "Überredung"), &[("persuasion", 1)]),
        ];
        let (works, stats) = unify(&nodes, None, &HashMap::new());
        assert!(works.works.is_empty());
        assert_eq!(stats.ambiguous, 1);
        assert_eq!(stats.occupied, 2);
    }

    #[test]
    fn conflicting_evidence_refuses() {
        let nodes = [
            item(node("en", 10, "Emma"), 1),
            item(node("en", 11, "Persuasion"), 2),
            node("fr", 16, "Emma"),
            item(voted(node("de", LIBRARY + 6, "Emma"), &[("emma", 1)]), 2),
            voted(node("es", LIBRARY + 10, "Emma"), &[("emma", 1)]),
        ];
        let (works, stats) = unify(&nodes, None, &HashMap::new());
        assert!(works.works.is_empty());
        assert_eq!(stats.wikidata_conflicts, 1);
        assert_eq!(stats.ambiguous, 1);
    }

    #[test]
    fn ids_stay_stable_with_aliases() {
        let first = [
            node("en", 5, "Emma"),
            node("de", 5, "Emma"),
            item(node("de", LIBRARY + 3, "Kuu"), 9),
            item(node("fr", LIBRARY + 9, "Lune"), 9),
        ];
        let (before, _) = unify(&first, None, &HashMap::new());
        assert_eq!(
            before.works.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
            [5, LIBRARY + 3]
        );
        let second = [
            item(node("en", 5, "Emma"), 4),
            node("de", 5, "Emma"),
            item(node("fr", 2, "Emma"), 4),
            item(node("ja", LIBRARY + 1, "月"), 9),
            item(node("fr", LIBRARY + 9, "Lune"), 9),
            item(node("de", LIBRARY + 20, "Kuu"), 9),
        ];
        let renamed = HashMap::from([(LIBRARY + 3, LIBRARY + 20)]);
        let (after, _) = unify(&second, Some(&before), &renamed);
        let ids: Vec<u32> = after.works.iter().map(|(id, _)| *id).collect();
        assert_eq!(ids, [2, LIBRARY + 9]);
        assert_eq!(after.aliases, [(5, 2), (LIBRARY + 3, LIBRARY + 9)]);
        let (again, _) = unify(&second, Some(&after), &renamed);
        assert_eq!(again.works, after.works);
        assert_eq!(again.aliases, after.aliases);
    }
}
