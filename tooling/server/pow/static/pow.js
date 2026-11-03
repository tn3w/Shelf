const SCRIPT = self.document?.currentScript.src;

function salsa(block) {
  let x0 = block[0], x1 = block[1], x2 = block[2], x3 = block[3];
  let x4 = block[4], x5 = block[5], x6 = block[6], x7 = block[7];
  let x8 = block[8], x9 = block[9], x10 = block[10], x11 = block[11];
  let x12 = block[12], x13 = block[13], x14 = block[14], x15 = block[15];
  for (let round = 0; round < 8; round += 2) {
    let u;
    u = x0 + x12; x4 ^= u << 7 | u >>> 25; u = x4 + x0; x8 ^= u << 9 | u >>> 23;
    u = x8 + x4; x12 ^= u << 13 | u >>> 19; u = x12 + x8; x0 ^= u << 18 | u >>> 14;
    u = x5 + x1; x9 ^= u << 7 | u >>> 25; u = x9 + x5; x13 ^= u << 9 | u >>> 23;
    u = x13 + x9; x1 ^= u << 13 | u >>> 19; u = x1 + x13; x5 ^= u << 18 | u >>> 14;
    u = x10 + x6; x14 ^= u << 7 | u >>> 25; u = x14 + x10; x2 ^= u << 9 | u >>> 23;
    u = x2 + x14; x6 ^= u << 13 | u >>> 19; u = x6 + x2; x10 ^= u << 18 | u >>> 14;
    u = x15 + x11; x3 ^= u << 7 | u >>> 25; u = x3 + x15; x7 ^= u << 9 | u >>> 23;
    u = x7 + x3; x11 ^= u << 13 | u >>> 19; u = x11 + x7; x15 ^= u << 18 | u >>> 14;
    u = x0 + x3; x1 ^= u << 7 | u >>> 25; u = x1 + x0; x2 ^= u << 9 | u >>> 23;
    u = x2 + x1; x3 ^= u << 13 | u >>> 19; u = x3 + x2; x0 ^= u << 18 | u >>> 14;
    u = x5 + x4; x6 ^= u << 7 | u >>> 25; u = x6 + x5; x7 ^= u << 9 | u >>> 23;
    u = x7 + x6; x4 ^= u << 13 | u >>> 19; u = x4 + x7; x5 ^= u << 18 | u >>> 14;
    u = x10 + x9; x11 ^= u << 7 | u >>> 25; u = x11 + x10; x8 ^= u << 9 | u >>> 23;
    u = x8 + x11; x9 ^= u << 13 | u >>> 19; u = x9 + x8; x10 ^= u << 18 | u >>> 14;
    u = x15 + x14; x12 ^= u << 7 | u >>> 25; u = x12 + x15; x13 ^= u << 9 | u >>> 23;
    u = x13 + x12; x14 ^= u << 13 | u >>> 19; u = x14 + x13; x15 ^= u << 18 | u >>> 14;
  }
  block[0] += x0; block[1] += x1; block[2] += x2; block[3] += x3;
  block[4] += x4; block[5] += x5; block[6] += x6; block[7] += x7;
  block[8] += x8; block[9] += x9; block[10] += x10; block[11] += x11;
  block[12] += x12; block[13] += x13; block[14] += x14; block[15] += x15;
}

function mixer(blockSize) {
  const words = 32 * blockSize;
  const carry = new Int32Array(16);
  return (input, output) => {
    carry.set(input.subarray(words - 16));
    for (let part = 0; part < 2 * blockSize; part++) {
      for (let index = 0; index < 16; index++) carry[index] ^= input[part * 16 + index];
      salsa(carry);
      output.set(carry, ((part & 1) * blockSize + (part >> 1)) * 16);
    }
  };
}

async function solve({ c: challenge, d: difficulty, n: cost, r: blockSize, ...range }) {
  const secret = Uint8Array.from(challenge.match(/../g), (pair) => parseInt(pair, 16));
  const usage = ['deriveBits'];
  const key = await crypto.subtle.importKey('raw', secret, 'PBKDF2', false, usage);
  const derive = (salt, bytes) => crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 1 }, key, bytes * 8);
  const words = 32 * blockSize;
  const memory = new Int32Array(cost * words);
  const mix = mixer(blockSize);
  let spare = new Int32Array(words);
  for (let counter = range.start; ; counter += range.step) {
    const salt = new Uint8Array([counter >>> 24, counter >>> 16, counter >>> 8, counter]);
    let block = new Int32Array(await derive(salt, words * 4));
    for (let round = 0; round < cost; round++) {
      memory.set(block, round * words);
      mix(block, spare);
      [block, spare] = [spare, block];
    }
    for (let round = 0; round < cost; round++) {
      const pick = (block[words - 16] & (cost - 1)) * words;
      for (let index = 0; index < words; index++) block[index] ^= memory[pick + index];
      mix(block, spare);
      [block, spare] = [spare, block];
    }
    const hash = new DataView(await derive(block, 32));
    if (Math.clz32(hash.getUint32(0)) >= difficulty) postMessage(counter >>> 0);
  }
}

async function verify(progress) {
  const challenge = await (await fetch('/api/pow')).json();
  const threads = Math.min(navigator.hardwareConcurrency || 2, 8);
  const found = new Set();
  const workers = [];
  await new Promise((resolve) => {
    for (let start = 0; start < threads; start++) {
      const worker = new Worker(SCRIPT);
      worker.onmessage = ({ data }) => {
        found.add(data);
        if (progress) progress.value = found.size;
        if (found.size === challenge.k) resolve();
      };
      worker.postMessage({ ...challenge, start, step: threads });
      workers.push(worker);
    }
  });
  workers.forEach((worker) => worker.terminate());
  const body = JSON.stringify({ c: challenge.c, n: [...found] });
  if (!(await fetch('/api/pow', { method: 'POST', body })).ok) throw 0;
}

const manual = () =>
  location.replace(`/pow?r=${encodeURIComponent(location.pathname + location.search)}`);
let unlocking;
let solved;

function unlock() {
  if (solved) manual();
  return (unlocking ??= verify().then(() => (solved = true), manual));
}

const broken = () =>
  [...document.images].filter((image) => image.complete && !image.naturalWidth);
let probing;

function probe(image) {
  if (probing || !image.src) return;
  probing = fetch(image.src, { cache: 'no-store' }).then(async ({ status }) => {
    if (status !== 401) return (probing = null);
    await unlock();
    broken().forEach((each) => (each.src = each.src));
  }, () => (probing = null));
}

const progress = self.document?.querySelector('progress');
if (!SCRIPT) {
  onmessage = ({ data }) => solve(data);
} else if (progress) {
  setTimeout(manual, 30000);
  verify(progress).then(() => location.reload(), manual);
} else {
  const failed = ({ target }) => target.tagName === 'IMG' && probe(target);
  addEventListener('error', failed, true);
  broken().forEach(probe);
}
