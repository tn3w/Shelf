//! Downloads covers the way the app does, re-encodes them as AVIF and keeps them on disk.

use builder::covers::Cover;
use builder::reader::isbn_of;
use image::imageops::FilterType;
use image::{DynamicImage, GrayImage};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::time::Duration;
use tokio::sync::Semaphore;

/// Same agent as the app, see `USER_AGENT` in `Network.kt`.
pub const USER_AGENT: &str = "Shelf (https://github.com/tn3w/Shelf)";
/// Bump to re-encode every cached image.
const ENCODER: &str = "1";
const BOOK_WIDTHS: [u32; 3] = [160, 320, 640];
const AUTHOR_WIDTHS: [u32; 2] = [96, 192];
const SPEED: u8 = 7;
const MIN_SIDE: u32 = 60;
const MIN_CONTRAST: f32 = 6.0;
const MIN_DOWNLOAD: usize = 1000;
const MAX_DOWNLOAD: usize = 8 << 20;
const DOWNLOADS: usize = 32;

const PLACEHOLDER_HASHES: [&str; 7] = [
    "8bf2a9cd37b94da1ed83f086f43a738f1f019deb65cf985e3e0e3c67c1cccfdf",
    "bb49df40d81c350fec267825e160f2c535df58a90e2196e6576095983e5aa36d",
    "a9af512c1e52ed9cafd06b8f212bf13940976703ee3a38573df558e28ce31a21",
    "e3f8c414b288cbdf4e6d1e00eb6d3826157d10a5b5628b9318f726ea490eca12",
    "9a7d8d19a70d6b6011b74850b98f18228e8a97bfb673e0268fa2ab8ce54f1690",
    "b5ef1d4bff2d0f057b6b8239c03c08bfe44206c7f5ae92fe05864f22ad3eb7b9",
    "f84e75694fef8121d7fed94ab7f1a92751e48188e512d66207498f6825fc9408",
];

const OPEN_LIBRARY: u8 = 0;
const DNB: u8 = 1;
const DIGAR: u8 = 2;
const ISBN_COVER: u8 = 3;
const BOOKBITES: u8 = 4;

/// The address the app itself would load, see `Book.coverUrl("L")`.
pub fn cover_url(cover: Cover) -> Option<String> {
    let isbn = || isbn_of(cover.key);
    Some(match cover.source {
        OPEN_LIBRARY => {
            format!("https://covers.openlibrary.org/b/id/{}-L.jpg", cover.key)
        }
        DNB => format!("https://portal.dnb.de/opac/mvb/cover?isbn={}", isbn()),
        DIGAR => format!("https://www.digar.ee/arhiiv/nlib-digar:{}?thumb=1", cover.key),
        ISBN_COVER => {
            format!(
                "https://covers.openlibrary.org/b/isbn/{}-L.jpg?default=false",
                isbn()
            )
        }
        BOOKBITES => format!("https://bookbites-prod-se.covers.bookbites.com/{}", isbn()),
        _ => return None,
    })
}

/// Author photo address as in the app's `Author.photoUrl("M")`.
pub fn author_url(number: u32) -> String {
    format!("https://covers.openlibrary.org/a/olid/OL{number}A-M.jpg?default=false")
}

/// Box an image is scaled to cover, like `ContentScale.Crop` in the app.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Shape {
    Book,
    Author,
}

impl Shape {
    pub fn widths(self) -> &'static [u32] {
        match self {
            Shape::Book => &BOOK_WIDTHS,
            Shape::Author => &AUTHOR_WIDTHS,
        }
    }

    fn height(self, width: u32) -> u32 {
        match self {
            Shape::Book => width * 3 / 2,
            Shape::Author => width,
        }
    }
}

pub enum Outcome {
    Found(Vec<u8>),
    Missing,
    Unavailable,
}

pub struct Images {
    client: reqwest::Client,
    directory: PathBuf,
    downloads: Semaphore,
    encodes: Semaphore,
}

pub fn key(url: &str) -> String {
    let hash = Sha256::digest(format!("{ENCODER}\n{url}"));
    hash[..12].iter().map(|byte| format!("{byte:02x}")).collect()
}

fn is_placeholder(bytes: &[u8]) -> bool {
    let hash: String =
        Sha256::digest(bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    PLACEHOLDER_HASHES.contains(&hash.as_str())
}

fn blank_depth(limit: u32, length: u32, sample: impl Fn(u32, u32) -> u8) -> u32 {
    let is_blank = |&depth: &u32| {
        let (low, high) = (0..length)
            .map(|position| sample(depth, position))
            .fold((u8::MAX, u8::MIN), |(low, high), value| {
                (low.min(value), high.max(value))
            });
        high - low <= 20 && (low >= 225 || high <= 30)
    };
    (0..limit).take_while(is_blank).count() as u32
}

/// Cuts white or black scanner margins.
fn trim_borders(image: DynamicImage) -> DynamicImage {
    let gray = image.to_luma8();
    let (width, height) = gray.dimensions();
    let (limit_x, limit_y) = (width / 8, height / 8);
    let top = blank_depth(limit_y, width, |depth, at| gray.get_pixel(at, depth)[0]);
    let bottom = blank_depth(limit_y, width, |depth, at| {
        gray.get_pixel(at, height - 1 - depth)[0]
    });
    let left = blank_depth(limit_x, height, |depth, at| gray.get_pixel(depth, at)[0]);
    let right = blank_depth(limit_x, height, |depth, at| {
        gray.get_pixel(width - 1 - depth, at)[0]
    });
    image.crop_imm(left, top, width - left - right, height - top - bottom)
}

fn contrast(gray: &GrayImage) -> f32 {
    let count = gray.pixels().len() as f32;
    let mean = gray.pixels().map(|pixel| f32::from(pixel[0])).sum::<f32>() / count;
    let variance =
        gray.pixels().map(|pixel| (f32::from(pixel[0]) - mean).powi(2)).sum::<f32>();
    (variance / count).sqrt()
}

/// Scales down until the image just covers `width` by the shape's height. The aspect
/// ratio stays, so the app can still pick the front of a wide cover spread.
fn cover(image: &DynamicImage, shape: Shape, width: u32) -> DynamicImage {
    let height = shape.height(width);
    let scale = f64::max(
        f64::from(width) / f64::from(image.width()),
        f64::from(height) / f64::from(image.height()),
    );
    if scale >= 1.0 {
        return image.clone();
    }
    let scaled = |side: u32| ((f64::from(side) * scale).round() as u32).max(1);
    image.resize_exact(
        scaled(image.width()),
        scaled(image.height()),
        FilterType::Lanczos3,
    )
}

/// Smaller images get more quality per pixel; their artefacts show more.
fn quality(width: u32) -> f32 {
    if width <= 200 { 62.0 } else { 56.0 }
}

/// 8-bit is twice as fast as ravif's default 10-bit at the same size for 8-bit JPEG
/// sources. Speed 7 keeps files about a fifth smaller than 9 and 10, which matters more
/// than encode time since every image is encoded once and then served from the edge.
fn avif(image: &DynamicImage) -> Option<Vec<u8>> {
    use rgb::FromSlice;
    let rgb = image.to_rgb8();
    let (width, height) = (rgb.width() as usize, rgb.height() as usize);
    let encoder = ravif::Encoder::new()
        .with_quality(quality(rgb.width()))
        .with_speed(SPEED)
        .with_bit_depth(ravif::BitDepth::Eight)
        .with_num_threads(Some(1));
    let pixels = rgb.as_raw().as_rgb();
    let encoded = encoder.encode_rgb(ravif::Img::new(pixels, width, height)).ok()?;
    Some(encoded.avif_file)
}

/// Decodes a download and scales it to `width`, `None` when it is not a real cover.
fn encode(bytes: &[u8], shape: Shape, width: u32) -> Option<Vec<u8>> {
    if is_placeholder(bytes) {
        return None;
    }
    let image = trim_borders(image::load_from_memory(bytes).ok()?);
    if image.width() < MIN_SIDE || image.height() < MIN_SIDE {
        return None;
    }
    if contrast(&image.to_luma8()) < MIN_CONTRAST {
        return None;
    }
    avif(&cover(&image, shape, width))
}

impl Images {
    pub fn new(directory: PathBuf) -> Images {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(12))
            .user_agent(USER_AGENT)
            .build()
            .expect("http client");
        let cpus = std::thread::available_parallelism().map_or(2, usize::from);
        Images {
            client,
            directory,
            downloads: Semaphore::new(DOWNLOADS),
            encodes: Semaphore::new(cpus),
        }
    }

    fn file(&self, url: &str, width: u32) -> PathBuf {
        let key = key(url);
        self.directory.join(&key[..2]).join(format!("{key}.{width}.avif"))
    }

    /// `Ok(None)` when the source answered without a usable image.
    async fn download(&self, url: &str) -> Result<Option<Vec<u8>>, ()> {
        let _permit = self.downloads.acquire().await.map_err(drop)?;
        let response = self.client.get(url).send().await.map_err(drop)?;
        let status = response.status();
        if status.is_server_error() || status.as_u16() == 429 {
            return Err(());
        }
        if !status.is_success() {
            return Ok(None);
        }
        let bytes = response.bytes().await.map_err(drop)?;
        Ok((MIN_DOWNLOAD..=MAX_DOWNLOAD).contains(&bytes.len()).then(|| bytes.to_vec()))
    }

    async fn store(&self, path: PathBuf, bytes: &[u8]) {
        if let Some(parent) = path.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }
        let temporary = path.with_extension("part");
        if tokio::fs::write(&temporary, bytes).await.is_ok() {
            let _ = tokio::fs::rename(&temporary, &path).await;
        }
    }

    /// The image at `url` as AVIF at `width`, one of the shape's widths.
    pub async fn get(&self, url: &str, shape: Shape, width: u32) -> Outcome {
        let path = self.file(url, width);
        if let Ok(bytes) = tokio::fs::read(&path).await {
            return Outcome::Found(bytes);
        }
        let bytes = match self.download(url).await {
            Ok(Some(bytes)) => bytes,
            Ok(None) => return Outcome::Missing,
            Err(()) => return Outcome::Unavailable,
        };
        let Ok(_permit) = self.encodes.acquire().await else {
            return Outcome::Unavailable;
        };
        let encoded =
            tokio::task::spawn_blocking(move || encode(&bytes, shape, width)).await;
        let Some(avif) = encoded.ok().flatten() else {
            return Outcome::Missing;
        };
        self.store(path, &avif).await;
        Outcome::Found(avif)
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;
    use image::{ImageFormat, Rgb, RgbImage};
    use std::io::Cursor;

    pub fn png(image: &RgbImage) -> Vec<u8> {
        let mut bytes = Cursor::new(Vec::new());
        image.write_to(&mut bytes, ImageFormat::Png).unwrap();
        bytes.into_inner()
    }

    pub fn gradient(width: u32, height: u32) -> RgbImage {
        RgbImage::from_fn(width, height, |x, y| {
            Rgb([(x % 256) as u8, (y % 256) as u8, 90])
        })
    }

    #[test]
    fn small_flat_and_broken_images_are_rejected() {
        assert!(encode(&png(&gradient(40, 40)), Shape::Book, 160).is_none());
        let flat = RgbImage::from_pixel(200, 300, Rgb([120, 130, 140]));
        assert!(encode(&png(&flat), Shape::Book, 160).is_none());
        assert!(encode(b"<html>not an image</html>", Shape::Book, 160).is_none());
    }

    #[test]
    fn scanner_borders_are_trimmed() {
        let mut scan = RgbImage::from_pixel(200, 300, Rgb([255, 255, 255]));
        for (x, y, pixel) in gradient(160, 260).enumerate_pixels() {
            scan.put_pixel(x + 20, y + 20, *pixel);
        }
        let trimmed = trim_borders(DynamicImage::ImageRgb8(scan));
        assert_eq!((trimmed.width(), trimmed.height()), (160, 260));
    }

    #[test]
    fn widths_are_encoded_as_avif() {
        let source = png(&gradient(700, 1050));
        let small = encode(&source, Shape::Book, 160).unwrap();
        let large = encode(&source, Shape::Book, 640).unwrap();
        assert_eq!(&small[4..12], b"ftypavif");
        assert!(small.len() < large.len());
    }

    #[test]
    fn images_cover_their_box_without_upscaling() {
        let spread =
            cover(&DynamicImage::ImageRgb8(gradient(1200, 600)), Shape::Book, 320);
        assert_eq!((spread.width(), spread.height()), (960, 480));
        let tall = cover(&DynamicImage::ImageRgb8(gradient(400, 1200)), Shape::Book, 160);
        assert_eq!((tall.width(), tall.height()), (160, 480));
        let small = cover(&DynamicImage::ImageRgb8(gradient(80, 120)), Shape::Book, 320);
        assert_eq!((small.width(), small.height()), (80, 120));
        let face = cover(&DynamicImage::ImageRgb8(gradient(300, 400)), Shape::Author, 96);
        assert_eq!((face.width(), face.height()), (96, 128));
    }

    #[test]
    fn urls_match_the_app() {
        let cover = |source, key| cover_url(Cover { work: 1, source, key }).unwrap();
        assert_eq!(
            cover(OPEN_LIBRARY, 42),
            "https://covers.openlibrary.org/b/id/42-L.jpg"
        );
        assert_eq!(isbn_of(917_629_168), "9789176291689");
        assert!(cover(DNB, 357_050_132).ends_with("isbn=9783570501320"));
        assert_eq!(
            cover(BOOKBITES, 917_629_168),
            "https://bookbites-prod-se.covers.bookbites.com/9789176291689"
        );
        assert_eq!(
            cover(DIGAR, 123),
            "https://www.digar.ee/arhiiv/nlib-digar:123?thumb=1"
        );
        assert!(cover_url(Cover { work: 1, source: 9, key: 1 }).is_none());
        assert_eq!(
            author_url(7),
            "https://covers.openlibrary.org/a/olid/OL7A-M.jpg?default=false"
        );
    }

    #[test]
    fn keys_depend_on_the_source() {
        assert_eq!(key("a"), key("a"));
        assert_ne!(key("a"), key("b"));
        assert_eq!(key("a").len(), 24);
    }
}
