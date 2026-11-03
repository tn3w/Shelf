mod puzzle;
mod web;

pub use puzzle::{DIGITS, Puzzle};
pub use web::{Gate, Pages, guard, routes};

use hmac::{Hmac, KeyInit, Mac};
use scrypt::Params;
use sha2::Sha256;
use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const CHALLENGE_SECONDS: u32 = 60;
pub const DEFAULT_DIFFICULTY: u32 = 4;
pub const DEFAULT_REQUESTS_PER_MINUTE: u32 = 300;
pub const SOLUTIONS: usize = 8;
pub const SCRYPT_LOG_COST: u8 = 10;
pub const SCRYPT_BLOCK_SIZE: u32 = 8;
const ATTEMPTS_PER_MINUTE: u32 = 30;
const LOAD_WINDOW_SECONDS: u32 = 10;
const CHALLENGES_PER_EXTRA_BIT: u32 = 32;
const MAX_EXTRA_BITS: u32 = 3;
const MAX_DIFFICULTY: u32 = 16;
const SLOTS: usize = 1 << 16;
const MAX_PENDING: usize = 1_000_000;
const CHALLENGE_BYTES: usize = 24;
const TOKEN_BYTES: usize = 32;

pub enum Check {
    Valid,
    Invalid,
    Limited,
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Kind {
    Work = 0,
    Puzzle = 1,
}

pub struct Pow {
    secret: Vec<u8>,
    base_difficulty: u32,
    requests_per_minute: u32,
    load: AtomicU64,
    spent: Mutex<HashMap<[u8; 8], u32>>,
    usage: Vec<AtomicU64>,
    attempts: Vec<AtomicU64>,
}

impl Kind {
    pub fn seconds(self) -> u32 {
        match self {
            Kind::Work => 6 * 3600,
            Kind::Puzzle => 3600,
        }
    }

    fn rate_share(self) -> u32 {
        match self {
            Kind::Work => 1,
            Kind::Puzzle => 4,
        }
    }
}

fn now() -> u32 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs() as u32)
}

fn random<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).expect("random bytes");
    bytes
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn unhex<const N: usize>(text: &str) -> Option<[u8; N]> {
    if text.len() != N * 2 || !text.is_ascii() {
        return None;
    }
    let mut bytes = [0u8; N];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(bytes)
}

fn leading_zero_bits(hash: &[u8]) -> u32 {
    let mut bits = 0;
    for byte in hash {
        if *byte != 0 {
            return bits + byte.leading_zeros();
        }
        bits += 8;
    }
    bits
}

fn work(challenge: &[u8], counter: u32) -> u32 {
    let params = Params::new(SCRYPT_LOG_COST, SCRYPT_BLOCK_SIZE, 1).expect("params");
    let mut output = [0u8; 32];
    scrypt::scrypt(challenge, &counter.to_be_bytes(), &params, &mut output)
        .expect("scrypt");
    leading_zero_bits(&output)
}

fn expires_at(bytes: &[u8]) -> u32 {
    u32::from_be_bytes(bytes[..4].try_into().unwrap_or_default())
}

fn distinct(counters: &[u32]) -> bool {
    let mut sorted = counters.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    sorted.len() == counters.len()
}

fn within(slot: &AtomicU64, minute: u32, limit: u32) -> bool {
    let update = |packed: u64| {
        let count = if (packed >> 32) as u32 == minute { packed as u32 + 1 } else { 1 };
        Some(u64::from(minute) << 32 | u64::from(count))
    };
    let packed = slot.try_update(Ordering::Relaxed, Ordering::Relaxed, update);
    let packed = packed.unwrap_or_default();
    (packed >> 32) as u32 != minute || (packed as u32) < limit
}

pub fn solve(challenge: &str, difficulty: u32) -> Option<Vec<u32>> {
    let bytes: [u8; CHALLENGE_BYTES] = unhex(challenge)?;
    let found = (0u32..).filter(|&counter| work(&bytes, counter) >= difficulty);
    Some(found.take(SOLUTIONS).collect())
}

impl Pow {
    pub fn new(secret: Vec<u8>, base_difficulty: u32, requests_per_minute: u32) -> Pow {
        let table = || (0..SLOTS).map(|_| AtomicU64::new(0)).collect();
        Pow {
            secret,
            base_difficulty: base_difficulty.min(MAX_DIFFICULTY),
            requests_per_minute,
            load: AtomicU64::new(0),
            spent: Mutex::new(HashMap::new()),
            usage: table(),
            attempts: table(),
        }
    }

    fn mac(&self, parts: &[&[u8]]) -> [u8; 32] {
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.secret).expect("hmac key");
        parts.iter().for_each(|part| mac.update(part));
        mac.finalize().into_bytes().into()
    }

    fn slot<'a>(&self, table: &'a [AtomicU64], key: &[u8]) -> &'a AtomicU64 {
        let hash = self.mac(&[b"slot", key]);
        &table[u64::from_be_bytes(hash[..8].try_into().unwrap_or_default()) as usize
            % SLOTS]
    }

    pub fn attempt(&self, network: &[u8]) -> bool {
        within(self.slot(&self.attempts, network), now() / 60, ATTEMPTS_PER_MINUTE)
    }

    fn count_in_window(&self, window: u32, increment: u32) -> u32 {
        let update = |packed: u64| {
            let (stored, count) = ((packed >> 32) as u32, packed as u32);
            let count = if stored == window { count + increment } else { increment };
            Some(u64::from(window) << 32 | u64::from(count))
        };
        let previous = self.load.try_update(Ordering::Relaxed, Ordering::Relaxed, update);
        let packed = previous.unwrap_or_default();
        if (packed >> 32) as u32 == window { packed as u32 } else { 0 }
    }

    pub fn difficulty(&self) -> u32 {
        let count = self.count_in_window(now() / LOAD_WINDOW_SECONDS, 0);
        let steps = count / CHALLENGES_PER_EXTRA_BIT;
        let extra = (u32::BITS - steps.leading_zeros()).min(MAX_EXTRA_BITS);
        (self.base_difficulty + extra).min(MAX_DIFFICULTY)
    }

    pub fn challenge(&self) -> (String, u32) {
        self.count_in_window(now() / LOAD_WINDOW_SECONDS, 1);
        let difficulty = self.difficulty();
        (self.challenge_at(now(), difficulty), difficulty)
    }

    fn challenge_at(&self, moment: u32, difficulty: u32) -> String {
        let nonce: [u8; 8] = random();
        let expires = (moment + CHALLENGE_SECONDS).to_be_bytes();
        let mac = self.mac(&[b"challenge", &nonce, &expires, &[difficulty as u8]]);
        hex(&[&nonce[..], &expires, &[difficulty as u8], &mac[..11]].concat())
    }

    pub fn redeem(
        &self,
        challenge: &str,
        counters: &[u32],
        client: &[u8],
    ) -> Option<String> {
        self.redeem_at(now(), challenge, counters, client)
    }

    fn redeem_at(
        &self,
        moment: u32,
        challenge: &str,
        counters: &[u32],
        client: &[u8],
    ) -> Option<String> {
        let bytes: [u8; CHALLENGE_BYTES] = unhex(challenge)?;
        let (nonce, expires, difficulty) = (&bytes[..8], &bytes[8..12], bytes[12]);
        let mac = self.mac(&[b"challenge", nonce, expires, &[difficulty]]);
        if mac[..11] != bytes[13..] || expires_at(expires) < moment {
            return None;
        }
        if counters.len() != SOLUTIONS || !distinct(counters) {
            return None;
        }
        self.spend(nonce.try_into().ok()?, expires_at(expires), moment)?;
        let difficulty = u32::from(difficulty);
        if counters.iter().any(|&counter| work(&bytes, counter) < difficulty) {
            return None;
        }
        Some(self.token(moment, Kind::Work, client))
    }

    fn token(&self, moment: u32, kind: Kind, client: &[u8]) -> String {
        let id: [u8; 8] = random();
        let expires = (moment + kind.seconds()).to_be_bytes();
        let mac = self.mac(&[b"token", &id, &expires, &[kind as u8], client]);
        hex(&[&id[..], &expires, &[kind as u8], &mac[..19]].concat())
    }

    fn spend(&self, nonce: [u8; 8], expires: u32, moment: u32) -> Option<()> {
        let mut spent = self.spent.lock().ok()?;
        if spent.len() >= MAX_PENDING {
            spent.retain(|_, expiry| *expiry >= moment);
        }
        if spent.len() >= MAX_PENDING {
            return None;
        }
        spent.insert(nonce, expires).is_none().then_some(())
    }

    pub fn check(&self, token: &str, client: &[u8]) -> Check {
        let Some(bytes) = unhex::<TOKEN_BYTES>(token) else {
            return Check::Invalid;
        };
        let kind = match bytes[12] {
            0 => Kind::Work,
            1 => Kind::Puzzle,
            _ => return Check::Invalid,
        };
        let mac =
            self.mac(&[b"token", &bytes[..8], &bytes[8..12], &[kind as u8], client]);
        if mac[..19] != bytes[13..] || expires_at(&bytes[8..12]) < now() {
            return Check::Invalid;
        }
        let limit = self.requests_per_minute / kind.rate_share();
        let slot =
            &self.usage[u64::from_be_bytes(bytes[..8].try_into().unwrap_or_default())
                as usize
                % SLOTS];
        if within(slot, now() / 60, limit) { Check::Valid } else { Check::Limited }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HARNESS: &str = "const source = require('fs')
  .readFileSync(process.argv[1], 'utf8');
globalThis.self = globalThis;
const found = [];
globalThis.postMessage = (counter) => {
  found.push(counter);
  if (found.length === Number(process.argv[3])) {
    console.log(found.join(','));
    process.exit();
  }
};
(0, eval)(source);
const data = { c: process.argv[2], d: 3, n: 1024, r: 8 };
onmessage({ data: { ...data, start: 0, step: 1 } });";

    #[test]
    fn javascript_solver_matches_scrypt() {
        let script = concat!(env!("CARGO_MANIFEST_DIR"), "/static/pow.js");
        let (challenge, _) = pow(4).challenge();
        let arguments = ["-e", HARNESS, script, &challenge, &SOLUTIONS.to_string()];
        let Ok(output) = std::process::Command::new("node").args(arguments).output()
        else {
            eprintln!("node missing, skipped");
            return;
        };
        let expected = solve(&challenge, 3).expect("solution");
        let found: Vec<u32> = String::from_utf8_lossy(&output.stdout)
            .trim()
            .split(',')
            .filter_map(|counter| counter.parse().ok())
            .collect();
        assert_eq!(found, expected, "{}", String::from_utf8_lossy(&output.stderr));
    }

    const CLIENT: &[u8] = b"203.0.113\0Firefox";

    fn pow(difficulty: u32) -> Pow {
        Pow::new(b"secret".to_vec(), difficulty, DEFAULT_REQUESTS_PER_MINUTE)
    }

    #[test]
    fn solved_challenge_yields_bound_token_once() {
        let pow = pow(2);
        let (challenge, difficulty) = pow.challenge();
        let counters = solve(&challenge, difficulty).expect("solution");
        let token = pow.redeem(&challenge, &counters, CLIENT).expect("token");
        assert!(matches!(pow.check(&token, CLIENT), Check::Valid));
        assert!(matches!(pow.check(&token, b"198.51.100\0Firefox"), Check::Invalid));
        assert!(pow.redeem(&challenge, &counters, CLIENT).is_none());
        assert!(matches!(pow.check("00", CLIENT), Check::Invalid));
    }

    #[test]
    fn wrong_or_tampered_solutions_fail_and_burn_the_challenge() {
        let pow = pow(4);
        let (challenge, difficulty) = pow.challenge();
        let counters = solve(&challenge, difficulty).expect("solution");
        let lowered = format!("{}{:02x}{}", &challenge[..24], 1, &challenge[26..]);
        let easy = solve(&lowered, 1).expect("solution");
        assert!(pow.redeem(&lowered, &easy, CLIENT).is_none());
        assert!(pow.redeem(&challenge, &[counters[0]; SOLUTIONS], CLIENT).is_none());
        assert!(pow.redeem(&challenge, &counters[1..], CLIENT).is_none());
        let other = Pow::new(b"other".to_vec(), 4, DEFAULT_REQUESTS_PER_MINUTE);
        assert!(other.redeem(&challenge, &counters, CLIENT).is_none());
        let bytes: [u8; CHALLENGE_BYTES] = unhex(&challenge).expect("challenge");
        let failing = (0..).find(|n| !counters.contains(n) && work(&bytes, *n) < 4);
        let mut wrong = counters.clone();
        wrong[0] = failing.expect("failing counter");
        assert!(pow.redeem(&challenge, &wrong, CLIENT).is_none());
        assert!(pow.redeem(&challenge, &counters, CLIENT).is_none());
    }

    #[test]
    fn expired_challenge_is_rejected() {
        let pow = pow(1);
        let challenge = pow.challenge_at(1_000, 1);
        let counters = solve(&challenge, 1).expect("solution");
        let late = 1_000 + CHALLENGE_SECONDS + 1;
        assert!(pow.redeem_at(late, &challenge, &counters, CLIENT).is_none());
        let in_time = 1_000 + CHALLENGE_SECONDS;
        assert!(pow.redeem_at(in_time, &challenge, &counters, CLIENT).is_some());
    }

    #[test]
    fn rates_are_limited_per_minute() {
        let slot = AtomicU64::new(0);
        let allowed = (0..40).filter(|_| within(&slot, 100, 30)).count();
        assert_eq!(allowed, 30);
        assert!(within(&slot, 101, 30));
        let pow = pow(1);
        assert_eq!((0..40).filter(|_| pow.attempt(b"203.0.113.9")).count(), 30);
        assert!(pow.attempt(b"198.51.100.1"));
    }

    #[test]
    fn puzzle_tokens_are_shorter_and_slower() {
        assert!(Kind::Puzzle.seconds() < Kind::Work.seconds());
        assert!(Kind::Puzzle.rate_share() > Kind::Work.rate_share());
    }

    #[test]
    fn difficulty_rises_with_load_up_to_a_cap() {
        let pow = pow(5);
        assert_eq!(pow.difficulty(), 5);
        for _ in 0..CHALLENGES_PER_EXTRA_BIT * 64 {
            pow.challenge();
        }
        assert_eq!(pow.difficulty(), 5 + MAX_EXTRA_BITS);
    }
}
