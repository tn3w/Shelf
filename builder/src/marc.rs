use crate::catalog;
use crate::dumps;
use crate::libraries::{
    COMIC, EBOOK, HARDCOVER, MAX_VOLUME, PAPERBACK, Record, SYNTHETIC, Source, clean, first_number,
    is_genre, is_numbered_genre, match_key, without_series,
};
use crate::release;
use crate::tags::Classes;

const FICTION_FORMS: &str = "1fjpdmhc";
const VOLUME_PARTS: [&str; 21] = [
    "kd", "köide", "osa", "raamat", "jagu", "vihik", "t", "tom", "cz", "część", "díl", "d", "sv",
    "svazek", "část", "nide", "tome", "vol", "volume", "livre", "partie",
];
const ELECTRONIC: [&str; 6] = [
    "epub",
    "pdf",
    "e-book",
    "e-kirja",
    "verkkoaineisto",
    "e-raamat",
];
const BOUND: [&str; 6] = [
    "köites",
    "oprawa twarda",
    "váz",
    "sidottu",
    "kovakant",
    "rel.",
];
const AUTHOR_ROLES: [&str; 3] = ["autor", "kirjoittaja", "aut"];
const JUVENILE_AUDIENCES: &str = "abcj";
const BNF_JUVENILE: &str = "abcd";
const BNF_LITERATURE: &str = "abcdefghiz";

pub struct Profile {
    language: &'static str,
    literature: &'static [&'static str],
    skipped: &'static [&'static str],
    subjects: &'static [(&'static str, &'static str)],
    kids: &'static [&'static str],
    teens: &'static [&'static str],
}

pub const ERB: Profile = Profile {
    language: "est",
    literature: &[
        "ilukirjandus",
        "romaanid",
        "jutustused",
        "jutud",
        "luuletused",
        "näidendid",
        "koomiksid",
    ],
    skipped: &[
        "värvimisraamatud",
        "kleepsud",
        "vigurraamatud",
        "tegelusraamatud",
        "pusled",
        "nuputamisülesanded",
        "ülesanded",
        "harjutused",
        "õpikud",
        "kooliõpikud",
        "õppematerjalid",
        "töövihikud",
        "lugemikud",
        "aabitsad",
        "kalendrid",
        "laulikud",
        "lauluraamatud",
        "kokaraamatud",
        "toiduretseptid",
    ],
    subjects: &[
        ("romaanid", "fiction"),
        ("jutustused", "fiction"),
        ("lühiromaanid", "fiction"),
        ("psühholoogilised romaanid", "fiction / literary"),
        ("perekonnaromaanid", "fiction / literary"),
        ("armastusromaanid", "fiction / romance"),
        ("kriminaalromaanid", "fiction / mystery & detective"),
        ("kriminaaljutud", "fiction / mystery & detective"),
        ("põnevusromaanid", "fiction / thrillers"),
        ("spiooniromaanid", "fiction / thrillers"),
        ("ajaloolised romaanid", "fiction / historical"),
        ("fantaasiaromaanid", "fiction / fantasy"),
        ("ulmeromaanid", "fiction / science fiction"),
        ("ulmejutud", "fiction / science fiction"),
        ("õudusromaanid", "fiction / horror"),
        ("õudusjutud", "fiction / horror"),
        ("seiklusromaanid", "fiction / action & adventure"),
        ("sõjaromaanid", "war stories"),
        ("huumor", "humor"),
        ("humoreskid", "humor"),
        ("komöödiad", "comedies"),
        ("novellid", "fiction / short stories"),
        ("jutud", "fiction / short stories"),
        ("antoloogiad", "anthologies"),
        ("luuletused", "poetry"),
        ("poeemid", "poetry"),
        ("näidendid", "drama"),
        ("koomiksid", "comics & graphic novels"),
        ("graafilised romaanid", "comics & graphic novels"),
        ("muinasjutud", "fairy tales"),
        ("rahvajutud", "folklore"),
        ("legendid", "legends"),
        ("pildiraamatud", "picture books"),
        ("mälestused", "memoir"),
        ("autobiograafiad", "autobiography"),
        ("biograafiad", "biography"),
    ],
    kids: &["lastekirjandus", "pildiraamatud"],
    teens: &["noorsookirjandus"],
};

pub const BNP: Profile = Profile {
    language: "pol",
    literature: &[
        "literatura piękna",
        "proza",
        "powieść",
        "opowiadani",
        "nowel",
    ],
    skipped: &[
        "podręcznik",
        "ćwiczeni",
        "kolorowank",
        "łamigłówk",
        "kalendarz",
        "śpiewnik",
        "książka kucharska",
        "przepisy kulinarne",
    ],
    subjects: &[
        ("powieść", "fiction"),
        ("proza", "fiction"),
        ("opowiadani", "fiction / short stories"),
        ("kryminał", "fiction / mystery & detective"),
        ("sensacj", "fiction / thrillers"),
        ("thriller", "fiction / thrillers"),
        ("fantasy", "fiction / fantasy"),
        ("fantastyka", "fiction / science fiction"),
        ("science fiction", "fiction / science fiction"),
        ("romans", "fiction / romance"),
        ("powieść historyczna", "fiction / historical"),
        ("horror", "fiction / horror"),
        ("grozy", "fiction / horror"),
        ("przygodow", "fiction / action & adventure"),
        ("obyczajow", "fiction / literary"),
        ("psychologiczn", "fiction / literary"),
        ("humor", "humor"),
        ("komiks", "comics & graphic novels"),
        ("poezja", "poetry"),
        ("wiersze", "poetry"),
        ("dramat", "drama"),
        ("baśń", "fairy tales"),
        ("baśni", "fairy tales"),
        ("książki obrazkowe", "picture books"),
    ],
    kids: &["literatura dziecięca", "dla dzieci", "książki obrazkowe"],
    teens: &["literatura młodzieżowa", "dla młodzieży"],
};

pub const NKP: Profile = Profile {
    language: "cze",
    literature: &["romány", "povídky", "novely", "beletrie", "próza"],
    skipped: &[
        "učebnice",
        "pracovní sešity",
        "omalovánky",
        "hádanky",
        "kalendáře",
        "zpěvníky",
        "kuchařky",
    ],
    subjects: &[
        ("romány", "fiction"),
        ("povídky", "fiction / short stories"),
        ("detektiv", "fiction / mystery & detective"),
        ("thriller", "fiction / thrillers"),
        ("napínav", "fiction / thrillers"),
        ("fantasy", "fiction / fantasy"),
        ("vědeckofantast", "fiction / science fiction"),
        ("sci-fi", "fiction / science fiction"),
        ("milostn", "fiction / romance"),
        ("historické romány", "fiction / historical"),
        ("horor", "fiction / horror"),
        ("dobrodružn", "fiction / action & adventure"),
        ("psychologick", "fiction / literary"),
        ("humoristick", "humor"),
        ("komiks", "comics & graphic novels"),
        ("poezie", "poetry"),
        ("básně", "poetry"),
        ("divadelní hry", "drama"),
        ("pohádky", "fairy tales"),
        ("obrázkové knihy", "picture books"),
        ("leporela", "picture books"),
    ],
    kids: &["pro děti", "dětsk", "obrázkové knihy", "leporela"],
    teens: &["pro mládež"],
};

pub const FENNICA: Profile = Profile {
    language: "fin",
    literature: &[
        "romaanit",
        "kertomukset",
        "novellit",
        "kaunokirjallisuus",
        "proosa",
    ],
    skipped: &[
        "oppikirjat",
        "harjoituskirjat",
        "värityskirjat",
        "tehtäväkirjat",
        "kalenterit",
        "laulukirjat",
        "keittokirjat",
    ],
    subjects: &[
        ("romaanit", "fiction"),
        ("kertomukset", "fiction"),
        ("novellit", "fiction / short stories"),
        ("dekkarit", "fiction / mystery & detective"),
        ("salapoliisi", "fiction / mystery & detective"),
        ("rikosromaanit", "fiction / mystery & detective"),
        ("jännitys", "fiction / thrillers"),
        ("trillerit", "fiction / thrillers"),
        ("fantasia", "fiction / fantasy"),
        ("tieteis", "fiction / science fiction"),
        ("rakkausromaanit", "fiction / romance"),
        ("historialliset romaanit", "fiction / historical"),
        ("kauhu", "fiction / horror"),
        ("seikkailu", "fiction / action & adventure"),
        ("huumori", "humor"),
        ("sarjakuvat", "comics & graphic novels"),
        ("runot", "poetry"),
        ("näytelmät", "drama"),
        ("sadut", "fairy tales"),
        ("kuvakirjat", "picture books"),
    ],
    kids: &["lastenkirjallisuus", "kuvakirjat"],
    teens: &["nuortenkirjallisuus"],
};

const BNF_SUBJECTS: [(&str, &str); 13] = [
    ("policier", "fiction / mystery & detective"),
    ("science-fiction", "fiction / science fiction"),
    ("fantasy", "fiction / fantasy"),
    ("sentimental", "fiction / romance"),
    ("d'amour", "fiction / romance"),
    ("historique", "fiction / historical"),
    ("horreur", "fiction / horror"),
    ("thriller", "fiction / thrillers"),
    ("aventure", "fiction / action & adventure"),
    ("albums", "picture books"),
    ("bandes dessinées", "comics & graphic novels"),
    ("contes", "fairy tales"),
    ("humour", "humor"),
];

const BNF_CLASSES: [(&str, &str); 5] = [
    ("801", "poetry"),
    ("802", "drama"),
    ("803", "fiction"),
    ("805", "comics & graphic novels"),
    ("809", "juvenile fiction"),
];

pub struct Field<'a> {
    pub tag: &'a str,
    pub value: &'a str,
    pub subfields: Vec<(char, &'a str)>,
}

impl<'a> Field<'a> {
    fn get(&self, code: char) -> Option<&'a str> {
        let found = self.subfields.iter().find(|(known, _)| *known == code);
        found.map(|&(_, value)| value)
    }
}

#[derive(Default)]
pub struct Marc<'a> {
    pub leader: &'a str,
    pub fields: Vec<Field<'a>>,
}

fn local_name(name: &str) -> &str {
    name.rsplit(':').next().unwrap_or(name)
}

fn attribute<'a>(tag: &'a str, name: &str) -> &'a str {
    let pattern = format!("{name}=\"");
    let Some(start) = tag.find(&pattern).map(|at| at + pattern.len()) else {
        return "";
    };
    tag[start..].split('"').next().unwrap_or_default()
}

impl<'a> Marc<'a> {
    pub fn xml(text: &'a str) -> Marc<'a> {
        let mut marc = Marc::default();
        let mut rest = text;
        while let Some(open) = rest.find('<') {
            let after = &rest[open + 1..];
            let Some(close) = after.find('>') else { break };
            let tag = &after[..close];
            rest = &after[close + 1..];
            if tag.starts_with(['/', '?', '!']) || tag.ends_with('/') {
                continue;
            }
            let name = local_name(tag.split_whitespace().next().unwrap_or_default());
            let content = &rest[..rest.find('<').unwrap_or(rest.len())];
            let field = |value| Field {
                tag: attribute(tag, "tag"),
                value,
                subfields: Vec::new(),
            };
            match name {
                "leader" => marc.leader = content,
                "controlfield" => marc.fields.push(field(content)),
                "datafield" => marc.fields.push(field("")),
                "subfield" => {
                    let code = attribute(tag, "code").chars().next().unwrap_or(' ');
                    let last = marc.fields.last_mut();
                    last.into_iter()
                        .for_each(|field| field.subfields.push((code, content)));
                }
                _ => {}
            }
        }
        marc
    }

    pub fn binary(bytes: &'a [u8]) -> Option<Marc<'a>> {
        let bytes = bytes.trim_ascii_start();
        let leader = std::str::from_utf8(bytes.get(..24)?).ok()?;
        let base: usize = leader.get(12..17)?.trim().parse().ok()?;
        let directory = bytes.get(24..base.checked_sub(1)?)?;
        let mut marc = Marc {
            leader,
            fields: Vec::new(),
        };
        for entry in directory.as_chunks::<12>().0 {
            let number = |range: std::ops::Range<usize>| -> Option<usize> {
                std::str::from_utf8(&entry[range]).ok()?.parse().ok()
            };
            let (Some(length), Some(start)) = (number(3..7), number(7..12)) else {
                continue;
            };
            let data = bytes.get(base + start..base + start + length);
            let text = data.and_then(|data| std::str::from_utf8(data).ok());
            let (Ok(tag), Some(text)) = (std::str::from_utf8(&entry[..3]), text) else {
                continue;
            };
            let text = text.trim_end_matches('\u{1e}');
            if tag < "010" {
                marc.fields.push(Field {
                    tag,
                    value: text,
                    subfields: Vec::new(),
                });
                continue;
            }
            let parts = text.split('\u{1f}').skip(1);
            let subfields = parts
                .filter_map(|part| {
                    let code = part.chars().next()?;
                    Some((code, &part[code.len_utf8()..]))
                })
                .collect();
            marc.fields.push(Field {
                tag,
                value: "",
                subfields,
            });
        }
        Some(marc)
    }

    pub fn control(&self, tag: &str) -> &'a str {
        self.fields(tag).next().map_or("", |field| field.value)
    }

    pub fn fields<'b>(&'b self, tag: &'b str) -> impl Iterator<Item = &'b Field<'a>> {
        self.fields.iter().filter(move |field| field.tag == tag)
    }

    pub fn values<'b>(&'b self, tag: &'b str, code: char) -> impl Iterator<Item = &'a str> {
        let subfields = self.fields(tag).flat_map(|field| field.subfields.iter());
        subfields
            .filter(move |(found, _)| *found == code)
            .map(|&(_, text)| text)
    }

    pub fn first(&self, tag: &str, code: char) -> &'a str {
        self.values(tag, code).next().unwrap_or_default()
    }

    fn authored_contributor(&self) -> Option<&'a str> {
        let is_author = |field: &&Field| {
            let roles = field
                .subfields
                .iter()
                .filter(|(code, _)| matches!(code, 'e' | '4'));
            let mut roles = roles.map(|(_, role)| role.to_lowercase());
            roles.any(|role| AUTHOR_ROLES.iter().any(|known| role.starts_with(known)))
        };
        self.fields("700").find(is_author)?.get('a')
    }
}

fn field_text(text: &str) -> String {
    let text = text.split(" = ").next().unwrap_or(text);
    clean(
        text.trim_end_matches([' ', ':', '/', ';', '=', ',', '.'])
            .trim_matches(['[', ']']),
    )
}

fn genre_term(term: &str) -> String {
    let term = term.split(" (").next().unwrap_or(term);
    clean(term).trim_end_matches('.').to_lowercase()
}

fn mentions(terms: &[String], stems: &[&str]) -> bool {
    let named = |term: &String| stems.iter().any(|stem| term.contains(stem));
    terms.iter().any(named)
}

fn isbn_keys<'a>(values: impl Iterator<Item = &'a str>) -> Vec<u32> {
    let mut keys = Vec::new();
    for key in values.filter_map(catalog::isbn_key) {
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    keys
}

fn heading_name(heading: &str) -> String {
    let heading = heading.trim_end_matches([',', ' ']);
    let initial = heading.chars().rev().nth(1).is_some_and(char::is_uppercase);
    let heading = clean(if initial {
        heading
    } else {
        heading.trim_end_matches('.')
    });
    match heading.split_once(", ") {
        Some((last, first)) => format!("{first} {last}"),
        None => heading,
    }
}

fn series_of(marc: &Marc, tags: &[&str], name_code: char) -> (String, u16) {
    let parsed = tags.iter().find_map(|tag| {
        let volume = first_number(marc.first(tag, 'v'))?;
        let statement = marc.first(tag, name_code);
        let name = field_text(statement.split(" / ").next().unwrap_or(statement));
        let (name, _) = release::parse_series(&[name.as_str()])?;
        (1..=MAX_VOLUME).contains(&volume).then_some((name, volume))
    });
    parsed.unwrap_or_default()
}

fn is_volume_number(text: &str) -> bool {
    let words = text.split(|character: char| !character.is_alphabetic());
    let mut words = words.filter(|word| !word.is_empty()).map(str::to_lowercase);
    words.all(|word| VOLUME_PARTS.contains(&word.as_str()))
}

fn titled(main: &str, part: &str, number: &str, series: (String, u16)) -> Option<Titled> {
    let main = field_text(main);
    let part = field_text(part);
    let volume = first_number(number).filter(|_| is_volume_number(number));
    if let Some(volume) = volume.filter(|&volume| volume > 0) {
        let title = match part.is_empty() {
            true => format!("{main} {volume}"),
            false => part,
        };
        return match volume <= MAX_VOLUME {
            true => Some((title, main, volume)),
            false => Some((title, String::new(), 0)),
        };
    }
    let (series, volume) = series;
    let title = match volume > 0 && match_key(&main) == match_key(&series) {
        true => format!("{main} {volume}"),
        false => without_series(&main, &series),
    };
    let usable = !title.is_empty() && !is_genre(&title) && !is_numbered_genre(&title);
    usable.then_some((title, series, volume))
}

type Titled = (String, String, u16);

fn literary_class(marc: &Marc) -> (bool, bool) {
    let udc: Vec<&str> = marc.values("080", 'a').collect();
    let ykl = marc
        .fields("084")
        .filter(|field| field.get('2') == Some("ykl"))
        .filter_map(|field| field.get('a'));
    let ykl: Vec<&str> = ykl.collect();
    let literary = udc.iter().any(|code| code.starts_with("821"))
        || ykl
            .iter()
            .any(|code| ("80".."86").contains(&code.get(..2).unwrap_or_default()));
    let juvenile = udc
        .iter()
        .any(|code| code.starts_with("821") && code.contains("-93"))
        || ykl.iter().any(|code| code.starts_with("85"));
    (literary, juvenile)
}

fn marc_format(marc: &Marc, terms: &[String], comics: bool) -> u8 {
    let qualifiers = marc.values("020", 'q').collect::<String>().to_lowercase();
    let online = marc.values("338", 'b').any(|carrier| carrier == "cr");
    let electronic = ELECTRONIC.iter().any(|kind| qualifiers.contains(kind));
    let digital = online || electronic || mentions(terms, &["e-raamat", "e-book"]);
    match () {
        _ if comics => COMIC,
        _ if digital => EBOOK,
        _ if BOUND.iter().any(|kind| qualifiers.contains(kind)) => HARDCOVER,
        _ => PAPERBACK,
    }
}

struct Reader {
    kid: bool,
    teen: bool,
    picture: bool,
    comics: bool,
    poetry: bool,
    drama: bool,
    fiction: bool,
}

fn classes(reader: &Reader) -> Classes {
    Classes {
        classified: 1,
        picture: u16::from(reader.picture),
        juvenile: u16::from(reader.kid || reader.teen),
        comics: u16::from(reader.comics),
        poetry: u16::from(reader.poetry),
        drama: u16::from(reader.drama),
        kid: u16::from(reader.kid),
        teen: u16::from(reader.teen),
        young_picture: u16::from(reader.picture),
    }
}

fn finished(mut subjects: Vec<&'static str>, reader: &Reader) -> Vec<&'static str> {
    let base = match () {
        _ if reader.teen => Some("young adult fiction"),
        _ if reader.kid => Some("juvenile fiction"),
        _ if reader.fiction => Some("fiction"),
        _ => None,
    };
    subjects.extend(base);
    subjects.sort_unstable();
    subjects.dedup();
    subjects
}

fn marc_id(source: Source, control: &str) -> Option<u32> {
    if source != Source::Erb {
        return Some(source.local_id(control));
    }
    let digits = control.strip_prefix('b')?;
    let number: u32 = digits.get(..digits.len().checked_sub(1)?)?.parse().ok()?;
    (number > 0 && number < SYNTHETIC).then_some(SYNTHETIC + number)
}

fn digar(marc: &Marc) -> u32 {
    let ids = marc.values("856", 'u').filter_map(|url| {
        let (_, rest) = url.split_once("nlib-digar:")?;
        rest.split(|character: char| !character.is_ascii_digit())
            .next()?
            .parse()
            .ok()
    });
    ids.into_iter().next().unwrap_or(0)
}

pub fn marc21_record(marc: &Marc, source: Source, profile: &Profile) -> Option<Record> {
    let fixed: Vec<char> = marc.control("008").chars().collect();
    let language: String = fixed.get(35..38).unwrap_or_default().iter().collect();
    if marc.leader.get(6..8) != Some("am") || language != profile.language {
        return None;
    }
    let tags = ["655", "380"].iter();
    let terms: Vec<String> = tags
        .flat_map(|tag| marc.values(tag, 'a'))
        .map(genre_term)
        .collect();
    let has = |stems: &[&str]| mentions(&terms, stems);
    let form = fixed.get(33).copied().unwrap_or(' ');
    let (classified, juvenile_class) = literary_class(marc);
    let mapped = profile.subjects.iter().filter(|(stem, _)| has(&[stem]));
    let subjects: Vec<&'static str> = mapped.map(|&(_, subject)| subject).collect();
    let literary = FICTION_FORMS.contains(form)
        || classified
        || !subjects.is_empty()
        || has(profile.literature)
        || has(profile.kids)
        || has(profile.teens);
    if !literary || has(profile.skipped) {
        return None;
    }
    let isbns = isbn_keys(marc.values("020", 'a'));
    if isbns.is_empty() {
        return None;
    }
    let series = series_of(marc, &["830", "490"], 'a');
    let (main, part) = (marc.first("245", 'a'), marc.first("245", 'p'));
    let (title, series, volume) = titled(main, part, marc.first("245", 'n'), series)?;
    let audience = fixed.get(22).copied().unwrap_or(' ');
    let picture = subjects.contains(&"picture books");
    let teen = audience == 'd' || has(profile.teens);
    let young = JUVENILE_AUDIENCES.contains(audience) || juvenile_class || has(profile.kids);
    let reader = Reader {
        kid: (young || picture) && !teen,
        teen,
        picture,
        comics: subjects.contains(&"comics & graphic novels"),
        poetry: form == 'p' || subjects.contains(&"poetry"),
        drama: form == 'd' || subjects.contains(&"drama"),
        fiction: "1fj".contains(form),
    };
    let translated = marc.values("041", 'h').next().is_some();
    let original = marc.values("240", 'a').next();
    let variant = marc.values("246", 'a').next().filter(|_| translated);
    let heading = marc.values("100", 'a').next();
    let name = heading_name(
        heading
            .or_else(|| marc.authored_contributor())
            .unwrap_or_default(),
    );
    let year: String = fixed.get(7..11).unwrap_or_default().iter().collect();
    Some(Record {
        source,
        id: marc_id(source, marc.control("001"))?,
        language: source.language(),
        isbns,
        links: Vec::new(),
        title,
        original: original.or(variant).map(field_text).unwrap_or_default(),
        series,
        volume,
        author: source.author_id(&name),
        name,
        year: dumps::year_of(&year),
        format: marc_format(marc, &terms, reader.comics),
        classes: classes(&reader),
        subjects: finished(subjects, &reader),
        digar: digar(marc),
    })
}

fn bnf_name(marc: &Marc) -> String {
    let authors = marc.fields("700").chain(marc.fields("701"));
    let mut authors = authors.filter(|field| field.get('4').is_none_or(|role| role == "070"));
    let Some(author) = authors.next() else {
        return String::new();
    };
    let surname = clean(author.get('a').unwrap_or_default());
    match author.get('b').map(clean) {
        Some(given) if !given.is_empty() => format!("{given} {surname}"),
        _ => surname,
    }
}

pub fn bnf_record(marc: &Marc) -> Option<Record> {
    let coded: Vec<char> = marc.first("100", 'a').chars().collect();
    let literature = marc.first("105", 'a').chars().nth(11).unwrap_or('y');
    if marc.leader.get(6..8) != Some("am") || marc.first("101", 'a') != "fre" {
        return None;
    }
    if !BNF_LITERATURE.contains(literature) {
        return None;
    }
    let isbns = isbn_keys(marc.values("010", 'a'));
    if isbns.is_empty() {
        return None;
    }
    let class = marc.first("686", 'a').get(..3).unwrap_or_default();
    let tags = ["606", "608"].iter();
    let terms: Vec<String> = tags
        .flat_map(|tag| marc.values(tag, 'a'))
        .map(genre_term)
        .collect();
    let mapped = BNF_SUBJECTS
        .iter()
        .filter(|(stem, _)| mentions(&terms, &[stem]));
    let mut subjects: Vec<&'static str> = mapped.map(|&(_, subject)| subject).collect();
    let classed = BNF_CLASSES.iter().filter(|(code, _)| *code == class);
    subjects.extend(classed.map(|&(_, subject)| subject));
    subjects.extend(match literature {
        'b' => Some("drama"),
        'f' => Some("fiction / short stories"),
        'g' => Some("poetry"),
        _ => None,
    });
    let audience = coded.get(17).copied().unwrap_or(' ');
    let teen = audience == 'e';
    let kid = !teen && (BNF_JUVENILE.contains(audience) || class == "809");
    let comics = subjects.contains(&"comics & graphic novels");
    let reader = Reader {
        kid,
        teen,
        picture: subjects.contains(&"picture books"),
        comics,
        poetry: subjects.contains(&"poetry"),
        drama: subjects.contains(&"drama"),
        fiction: literature == 'a' || class == "803",
    };
    let series = series_of(marc, &["225", "461"], 'a');
    let series = match series.0.is_empty() {
        true => series_of(marc, &["461"], 't'),
        false => series,
    };
    let (main, part) = (marc.first("200", 'a'), marc.first("200", 'i'));
    let (title, series, volume) = titled(main, part, marc.first("200", 'h'), series)?;
    let binding = marc.first("010", 'b');
    let format = match () {
        _ if comics => COMIC,
        _ if binding.starts_with("rel") || binding.starts_with("cart") => HARDCOVER,
        _ => PAPERBACK,
    };
    let control = marc.control("001");
    let digits: String = control
        .chars()
        .filter(char::is_ascii_digit)
        .take(8)
        .collect();
    let name = bnf_name(marc);
    let year: String = coded.get(9..13).unwrap_or_default().iter().collect();
    Some(Record {
        source: Source::Bnf,
        id: Source::Bnf.local_id(&digits),
        language: Source::Bnf.language(),
        isbns,
        links: Vec::new(),
        title,
        original: field_text(marc.first("454", 't')),
        series,
        volume,
        author: Source::Bnf.author_id(&name),
        name,
        year: dumps::year_of(&year),
        format,
        classes: classes(&reader),
        subjects: finished(subjects, &reader),
        digar: 0,
    })
}
