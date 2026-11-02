use crate::{Kind, Pow, expires_at, hex, now, random, unhex};
use std::fmt::Write as _;

pub const DIGITS: usize = 5;
const PUZZLE_SECONDS: u32 = 600;
const MIN_SOLVE_SECONDS: u32 = 3;
const PUZZLE_BYTES: usize = 24;
const MAC_BYTES: usize = 12;
const WIDTH: f32 = 300.0;
const HEIGHT: f32 = 100.0;
const NOISE_CURVES: usize = 5;

#[rustfmt::skip]
const GLYPHS: [&[(i8, i8)]; 10] = [
    &[(5, 0), (1, 3), (0, 8), (1, 13), (5, 16), (9, 13), (10, 8), (9, 3), (5, 0)],
    &[(2, 3), (6, 0), (6, 16)],
    &[(0, 4), (2, 1), (5, 0), (8, 1), (10, 4), (9, 7), (0, 16), (10, 16)],
    &[
        (0, 2), (4, 0), (8, 1), (9, 4), (8, 7), (4, 8),
        (9, 10), (10, 13), (8, 15), (4, 16), (0, 14),
    ],
    &[(7, 16), (7, 0), (0, 11), (10, 11)],
    &[(9, 0), (1, 0), (0, 7), (5, 6), (9, 8), (10, 12), (8, 15), (4, 16), (0, 14)],
    &[
        (8, 1), (5, 0), (2, 2), (0, 7), (0, 12), (2, 15), (5, 16),
        (8, 15), (10, 12), (9, 9), (5, 7), (2, 8), (0, 11),
    ],
    &[(0, 0), (10, 0), (4, 16)],
    &[
        (5, 8), (1, 6), (1, 2), (5, 0), (9, 2), (9, 6), (5, 8),
        (0, 11), (1, 15), (5, 16), (9, 15), (10, 11), (5, 8),
    ],
    &[
        (10, 5), (8, 8), (5, 9), (2, 8), (0, 5), (1, 1), (5, 0),
        (9, 1), (10, 5), (10, 10), (8, 14), (5, 16), (2, 15),
    ],
];

pub struct Puzzle {
    pub challenge: String,
    pub drawing: String,
}

struct Random(u64);

impl Random {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut value = self.0;
        value = (value ^ (value >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        value ^ (value >> 31)
    }

    fn between(&mut self, low: f32, high: f32) -> f32 {
        low + (self.next() >> 40) as f32 / (1 << 24) as f32 * (high - low)
    }

    fn below(&mut self, limit: usize) -> usize {
        (self.next() % limit as u64) as usize
    }
}

fn digits(mac: &[u8; 32]) -> String {
    let secret = &mac[MAC_BYTES..MAC_BYTES + DIGITS];
    secret.iter().map(|byte| char::from(b'0' + byte % 10)).collect()
}

fn polyline(points: &[(f32, f32)]) -> String {
    let mut path = String::new();
    for (index, (x, y)) in points.iter().enumerate() {
        let command = if index == 0 { 'M' } else { 'L' };
        let _ = write!(path, "{command}{x:.0} {y:.0}");
    }
    path
}

fn glyph(random: &mut Random, digit: usize, center_x: f32) -> [Vec<(f32, f32)>; 2] {
    let (sin, cos) = random.between(-0.3, 0.3).sin_cos();
    let scale = random.between(3.6, 4.4);
    let skew = random.between(-0.3, 0.3);
    let center_y = HEIGHT / 2.0 + random.between(-5.0, 5.0);
    let points: Vec<(f32, f32)> = GLYPHS[digit]
        .iter()
        .map(|&(x, y)| {
            let y = f32::from(y) - 8.0 + random.between(-0.4, 0.4);
            let x = f32::from(x) - 5.0 + skew * y + random.between(-0.4, 0.4);
            let turned = (x * cos - y * sin, x * sin + y * cos);
            (center_x + scale * turned.0, center_y + scale * turned.1)
        })
        .collect();
    let split = 1 + random.below(points.len() - 2);
    [points[..=split].to_vec(), points[split..].to_vec()]
}

fn curve(random: &mut Random) -> String {
    let start_x = random.between(0.0, WIDTH / 3.0);
    let end_x = random.between(WIDTH * 2.0 / 3.0, WIDTH);
    let control_x = random.between(0.0, WIDTH);
    let control_y = random.between(-20.0, HEIGHT + 20.0);
    let [start_y, end_y] = [(); 2].map(|_| random.between(10.0, HEIGHT - 10.0));
    format!(
        "M{start_x:.0} {start_y:.0}Q{control_x:.0} {control_y:.0} {end_x:.0} {end_y:.0}"
    )
}

fn drawing(answer: &str, seed: u64) -> String {
    let mut random = Random(seed);
    let spacing = WIDTH / DIGITS as f32;
    let mut strokes = Vec::new();
    for (position, digit) in answer.bytes().enumerate() {
        let center_x = spacing * (position as f32 + 0.5) + random.between(-4.0, 4.0);
        let pieces = glyph(&mut random, usize::from(digit - b'0'), center_x);
        strokes.extend(pieces.iter().map(|points| polyline(points)));
    }
    strokes.extend((0..NOISE_CURVES).map(|_| curve(&mut random)));
    for index in (1..strokes.len()).rev() {
        strokes.swap(index, random.below(index + 1));
    }
    strokes.concat()
}

impl Pow {
    pub fn puzzle(&self) -> Puzzle {
        self.puzzle_at(now())
    }

    fn puzzle_at(&self, moment: u32) -> Puzzle {
        let nonce: [u8; 8] = random();
        let expires = (moment + PUZZLE_SECONDS).to_be_bytes();
        let mac = self.mac(&[b"puzzle", &nonce, &expires]);
        let seed = u64::from_be_bytes(mac[24..].try_into().unwrap_or_default());
        Puzzle {
            challenge: hex(&[&nonce[..], &expires, &mac[..MAC_BYTES]].concat()),
            drawing: drawing(&digits(&mac), seed),
        }
    }

    pub fn answer(&self, challenge: &str, guess: &str, client: &[u8]) -> Option<String> {
        self.answer_at(now(), challenge, guess, client)
    }

    fn answer_at(
        &self,
        moment: u32,
        challenge: &str,
        guess: &str,
        client: &[u8],
    ) -> Option<String> {
        let bytes: [u8; PUZZLE_BYTES] = unhex(challenge)?;
        let (nonce, expires) = (&bytes[..8], &bytes[8..12]);
        let mac = self.mac(&[b"puzzle", nonce, expires]);
        if mac[..MAC_BYTES] != bytes[12..] {
            return None;
        }
        let expiry = expires_at(expires);
        let earliest = expiry.saturating_sub(PUZZLE_SECONDS) + MIN_SOLVE_SECONDS;
        if moment > expiry || moment < earliest {
            return None;
        }
        self.spend(nonce.try_into().ok()?, expiry, moment)?;
        (guess.trim() == digits(&mac)).then(|| self.token(moment, Kind::Puzzle, client))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Check, DEFAULT_DIFFICULTY, DEFAULT_REQUESTS_PER_MINUTE};

    const CLIENT: &[u8] = b"203.0.113\0Firefox";

    fn pow() -> Pow {
        Pow::new(b"secret".to_vec(), DEFAULT_DIFFICULTY, DEFAULT_REQUESTS_PER_MINUTE)
    }

    fn solution(pow: &Pow, challenge: &str) -> String {
        let bytes: [u8; PUZZLE_BYTES] = unhex(challenge).expect("challenge");
        digits(&pow.mac(&[b"puzzle", &bytes[..8], &bytes[8..12]]))
    }

    #[test]
    fn right_answer_after_the_wait_yields_token_once() {
        let pow = pow();
        let start = now();
        let puzzle = pow.puzzle_at(start);
        let answer = solution(&pow, &puzzle.challenge);
        let moment = start + MIN_SOLVE_SECONDS;
        let token = pow.answer_at(moment, &puzzle.challenge, &answer, CLIENT);
        let token = token.expect("token");
        assert!(matches!(pow.check(&token, CLIENT), Check::Valid));
        assert_eq!(token.as_bytes()[24..26], *b"01");
        assert!(pow.answer_at(moment, &puzzle.challenge, &answer, CLIENT).is_none());
    }

    #[test]
    fn early_late_wrong_and_forged_answers_fail() {
        let pow = pow();
        let puzzle = pow.puzzle_at(1_000);
        let answer = solution(&pow, &puzzle.challenge);
        let early = 1_000 + MIN_SOLVE_SECONDS - 1;
        assert!(pow.answer_at(early, &puzzle.challenge, &answer, CLIENT).is_none());
        let late = 1_000 + PUZZLE_SECONDS + 1;
        assert!(pow.answer_at(late, &puzzle.challenge, &answer, CLIENT).is_none());
        let other = Pow::new(b"other".to_vec(), 4, DEFAULT_REQUESTS_PER_MINUTE);
        assert!(other.answer_at(1_010, &puzzle.challenge, &answer, CLIENT).is_none());
        let wrong = if answer == "00000" { "11111" } else { "00000" };
        assert!(pow.answer_at(1_010, &puzzle.challenge, wrong, CLIENT).is_none());
        assert!(pow.answer_at(1_010, &puzzle.challenge, &answer, CLIENT).is_none());
    }

    #[test]
    fn drawing_mixes_split_digits_and_noise() {
        let puzzle = pow().puzzle();
        assert_eq!(puzzle.challenge.len(), PUZZLE_BYTES * 2);
        let strokes = puzzle.drawing.matches('M').count();
        assert_eq!(strokes, DIGITS * 2 + NOISE_CURVES);
        assert!(puzzle.drawing.len() < 1_500);
    }
}
