use brotli::CompressorWriter;
use lightningcss::stylesheet::{
    MinifyOptions, ParserOptions, PrinterOptions, StyleSheet,
};
use oxc::allocator::Allocator;
use oxc::codegen::{Codegen, CodegenOptions, CommentOptions};
use oxc::minifier::{CompressOptions, MangleOptions, Minifier, MinifierOptions};
use oxc::parser::Parser;
use oxc::span::SourceType;
use sha2::{Digest, Sha256};
use std::fmt::Write as _;
use std::io::Write as _;
use std::path::{Path, PathBuf};

const FAVICON: &str = "../../android/design/shelf-icon.svg";

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn minify_css(source: &str) -> Vec<u8> {
    let mut sheet =
        StyleSheet::parse(source, ParserOptions::default()).expect("parse css");
    sheet.minify(MinifyOptions::default()).expect("minify css");
    let printer = PrinterOptions { minify: true, ..PrinterOptions::default() };
    sheet.to_css(printer).expect("print css").code.into_bytes()
}

fn minify_script(source: &str) -> Vec<u8> {
    let allocator = Allocator::default();
    let parsed = Parser::new(&allocator, source, SourceType::script()).parse();
    assert!(parsed.diagnostics.is_empty(), "parse js: {:?}", parsed.diagnostics);
    let mut program = parsed.program;
    let options = MinifierOptions {
        mangle: Some(MangleOptions::default()),
        mangle_properties: None,
        compress: Some(CompressOptions::smallest()),
    };
    let minified = Minifier::new(options).minify(&allocator, &mut program);
    let printer = CodegenOptions {
        minify: true,
        comments: CommentOptions::disabled(),
        ..CodegenOptions::default()
    };
    let output = Codegen::new()
        .with_options(printer)
        .with_scoping(minified.scoping)
        .build(&program);
    output.code.into_bytes()
}

fn minify_svg(source: &str) -> Vec<u8> {
    let lines = source.lines().map(str::trim).filter(|line| !line.is_empty());
    lines.collect::<Vec<_>>().join(" ").replace("> <", "><").into_bytes()
}

fn compress(bytes: &[u8]) -> Vec<u8> {
    let mut output = Vec::new();
    let mut writer = CompressorWriter::new(&mut output, 4096, 11, 22);
    writer.write_all(bytes).expect("compress");
    drop(writer);
    output
}

struct Built {
    name: String,
    content_type: &'static str,
    stem: String,
    bytes: Vec<u8>,
}

fn built(
    stem: &str,
    extension: &str,
    content_type: &'static str,
    bytes: Vec<u8>,
) -> Built {
    let name = format!("{}.{extension}", &hex(&Sha256::digest(&bytes))[..12]);
    Built { name, content_type, stem: stem.to_string(), bytes }
}

fn main() {
    let output_directory = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR"));
    let directories = ["static", "pow/static"];
    for directory in directories.iter().chain(&["templates", "pow/templates", FAVICON]) {
        println!("cargo:rerun-if-changed={directory}");
    }
    let mut entries: Vec<PathBuf> = directories
        .iter()
        .flat_map(|directory| std::fs::read_dir(directory).expect("asset directory"))
        .map(|entry| entry.expect("entry").path())
        .collect();
    entries.sort();
    let mut scripts = String::new();
    let mut assets = Vec::new();
    for path in entries {
        let source = std::fs::read_to_string(&path).expect("read asset");
        let stem = path.file_stem().and_then(|value| value.to_str()).expect("file stem");
        match path.extension().and_then(|value| value.to_str()) {
            Some("css") => {
                assets.push(built(stem, "css", "text/css", minify_css(&source)))
            }
            Some("js") => scripts.push_str(&source),
            other => panic!("unsupported asset type {other:?}"),
        }
    }
    let script = minify_script(&format!("(() => {{\n{scripts}}})();"));
    assets.push(built("script", "js", "text/javascript", script));
    let icon = std::fs::read_to_string(FAVICON).expect("read favicon");
    assets.push(built("favicon", "svg", "image/svg+xml", minify_svg(&icon)));
    let mut build = Sha256::new();
    let mut table = String::new();
    let mut constants = String::new();
    for asset in assets {
        let Built { name, content_type, stem, bytes } = asset;
        std::fs::write(output_directory.join(&name), &bytes).expect("write asset");
        std::fs::write(output_directory.join(format!("{name}.br")), compress(&bytes))
            .expect("write asset");
        build.update(&bytes);
        let _ = writeln!(
            table,
            "    Asset {{ name: \"{name}\", content_type: \"{content_type}\", \
plain: include_bytes!(concat!(env!(\"OUT_DIR\"), \"/{name}\")), \
brotli: include_bytes!(concat!(env!(\"OUT_DIR\"), \"/{name}.br\")) }},"
        );
        let _ = writeln!(
            constants,
            "pub const {}: &str = \"/s/{name}\";",
            stem.to_uppercase()
        );
    }
    for directory in ["templates", "pow/templates"] {
        for entry in std::fs::read_dir(directory).expect("templates directory") {
            build.update(
                std::fs::read(entry.expect("entry").path()).expect("read template"),
            );
        }
    }
    let build_id = &hex(&build.finalize())[..12];
    let generated = format!(
        "pub const BUILD: &str = \"{build_id}\";\n{constants}\
pub const ASSETS: &[Asset] = &[\n{table}];\n"
    );
    std::fs::write(Path::new(&output_directory).join("assets.rs"), generated)
        .expect("write assets.rs");
}
