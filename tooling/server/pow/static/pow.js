const ORDER = [0, 4, 8, 12, 5, 9, 13, 1, 10, 14, 2, 6, 15, 3, 7, 11,
  0, 1, 2, 3, 5, 6, 7, 4, 10, 11, 8, 9, 15, 12, 13, 14];
const SCRIPT = self.document?.currentScript.src;
const rotate = (value, bits) => (value << bits) | (value >>> (32 - bits));

function salsa(block, scratch) {
  scratch.set(block);
  for (let round = 0; round < 4; round++) {
    for (let at = 0; at < 32; at += 4) {
      const a = ORDER[at], b = ORDER[at + 1], c = ORDER[at + 2], d = ORDER[at + 3];
      scratch[b] ^= rotate(scratch[a] + scratch[d], 7);
      scratch[c] ^= rotate(scratch[b] + scratch[a], 9);
      scratch[d] ^= rotate(scratch[c] + scratch[b], 13);
      scratch[a] ^= rotate(scratch[d] + scratch[c], 18);
    }
  }
  for (let index = 0; index < 16; index++) block[index] += scratch[index];
}

function mixer(blockSize) {
  const words = 32 * blockSize;
  const last = new Uint32Array(16);
  const scratch = new Uint32Array(16);
  const output = new Uint32Array(words);
  return (block) => {
    last.set(block.subarray(words - 16));
    for (let part = 0; part < 2 * blockSize; part++) {
      for (let index = 0; index < 16; index++) last[index] ^= block[part * 16 + index];
      salsa(last, scratch);
      output.set(last, ((part & 1) * blockSize + (part >> 1)) * 16);
    }
    block.set(output);
  };
}

async function solve({ c: challenge, d: difficulty, n: cost, r: blockSize, ...range }) {
  const secret = Uint8Array.from(challenge.match(/../g), (pair) => parseInt(pair, 16));
  const hmac = { name: 'HMAC', hash: 'SHA-256' };
  const key = await crypto.subtle.importKey('raw', secret, hmac, false, ['sign']);
  const derive = async (salt, blocks) => {
    const output = new Uint8Array(blocks * 32);
    await Promise.all(Array.from({ length: blocks }, async (_, index) => {
      const input = new Uint8Array([...salt, 0, 0, 0, index + 1]);
      const part = await crypto.subtle.sign('HMAC', key, input);
      output.set(new Uint8Array(part), index * 32);
    }));
    return output.buffer;
  };
  const words = 32 * blockSize;
  const memory = new Uint32Array(cost * words);
  const mix = mixer(blockSize);
  for (let counter = range.start; ; counter += range.step) {
    const salt = [counter >>> 24, counter >>> 16, counter >>> 8, counter];
    const block = new Uint32Array(await derive(salt, words / 8));
    for (let round = 0; round < cost; round++) {
      memory.set(block, round * words);
      mix(block);
    }
    for (let round = 0; round < cost; round++) {
      const pick = (block[words - 16] & (cost - 1)) * words;
      for (let index = 0; index < words; index++) block[index] ^= memory[pick + index];
      mix(block);
    }
    const hash = new DataView(await derive(new Uint8Array(block.buffer), 1));
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
