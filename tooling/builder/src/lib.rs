pub mod catalog;
pub mod dumps;
pub mod enrichment;
pub mod libraries;
pub mod linked;
pub mod marc;
pub mod people;
pub mod reader;
pub mod release;
pub mod search;
pub mod segment;
pub mod sidecar;
pub mod tags;
pub mod translations;
pub mod unify;
pub mod works;

pub const LANGUAGES: [&str; 15] = [
    "en", "de", "fr", "es", "et", "sv", "pl", "cs", "ko", "fi", "ja", "lv", "pt", "ru", "it",
];
pub const LANGUAGE_COUNT: usize = LANGUAGES.len();
pub type Built = [bool; LANGUAGE_COUNT];

pub fn language_index(code: &str) -> usize {
    LANGUAGES
        .iter()
        .position(|&known| known == code)
        .unwrap_or_else(|| panic!("unknown language {code}"))
}
