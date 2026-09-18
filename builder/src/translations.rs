use crate::dumps::{Authors, Book};
use flate2::read::DeflateDecoder;
use serde::Serialize;
use std::collections::HashMap;
use std::io::Read;
use std::path::Path;

const MAGIC: &[u8; 4] = b"SHLT";
const VERSION: u32 = 1;

#[derive(Serialize)]
pub struct Request<'a> {
    pub work: u32,
    pub title: &'a str,
    pub source_title: &'a str,
    pub authors: Vec<&'a str>,
    pub text: &'a str,
}

#[derive(Default)]
pub struct Translations(HashMap<u32, String>);

fn varint(bytes: &[u8], cursor: &mut usize) -> Option<u32> {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = *bytes.get(*cursor)?;
        *cursor += 1;
        value |= u32::from(byte & 0x7f) << shift;
        if byte < 0x80 {
            return Some(value);
        }
    }
    None
}

fn records(body: &[u8]) -> HashMap<u32, String> {
    let mut translations = HashMap::new();
    let mut cursor = 0;
    let mut work = 0;
    while cursor < body.len() {
        let Some(delta) = varint(body, &mut cursor) else {
            break;
        };
        let Some(length) = varint(body, &mut cursor) else {
            break;
        };
        let Some(text) = body.get(cursor..cursor + length as usize) else {
            break;
        };
        cursor += length as usize;
        work += delta;
        let Ok(text) = str::from_utf8(text) else {
            break;
        };
        translations.insert(work, text.to_string());
    }
    translations
}

impl Translations {
    pub fn load(path: &Path) -> Self {
        let Ok(bytes) = std::fs::read(path) else {
            return Self::default();
        };
        let header = bytes.get(..8).unwrap_or_default();
        let version = u32::from_le_bytes(header[4..].try_into().unwrap_or_default());
        assert!(
            header.starts_with(MAGIC) && version == VERSION,
            "{}: not a version {VERSION} translation file",
            path.display()
        );
        let mut body = Vec::new();
        DeflateDecoder::new(&bytes[8..])
            .read_to_end(&mut body)
            .unwrap_or_else(|error| panic!("{}: {error}", path.display()));
        let translations = records(&body);
        eprintln!("{}: {} translations", path.display(), translations.len());
        Self(translations)
    }

    pub fn get(&self, work: u32) -> Option<&str> {
        self.0.get(&work).map(String::as_str)
    }
}

pub fn request<'a>(book: &'a Book, title: &'a str, authors: &'a Authors) -> Request<'a> {
    Request {
        work: book.work,
        title,
        source_title: &book.title,
        authors: book
            .authors
            .iter()
            .filter_map(|&id| authors.get(id))
            .map(|author| author.name)
            .collect(),
        text: &book.description,
    }
}

pub fn write_requests(path: &Path, requests: &[Request]) {
    let lines: String = requests
        .iter()
        .map(|request| serde_json::to_string(request).expect("request json") + "\n")
        .collect();
    std::fs::write(path, lines).unwrap_or_else(|error| panic!("write {}: {error}", path.display()));
    eprintln!("{}: {} requests", path.display(), requests.len());
}
