#!/usr/bin/env python3
"""Translate builder description requests into a translations-<lang>.bin segment."""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import zlib
from pathlib import Path

MAGIC = b"SHLT"
VERSION = 1
MODEL = "Helsinki-NLP/opus-mt-tc-bible-big-deu_eng_fra_por_spa-ine"
NLLB_CODES = {"en": "eng_Latn", "de": "deu_Latn", "fr": "fra_Latn", "es": "spa_Latn"}
OPUS_CODES = {"de": "deu", "fr": "fra", "es": "spa"}
MULTI_TARGET_SUFFIXES = ("-ine", "-gem", "-gmw", "-itc", "-mul")
PLACEHOLDER = "@{}@"
PLACEHOLDER_PATTERN = re.compile(r"@\s?(\d{1,2})\s?@")
CITATION = re.compile(r"\s?\[\d{1,3}\]")
QUOTES = "\"\u201c\u201d\u201e"
SENTENCE_BREAK = re.compile(r"(?<=[.!?…])[ \t]+(?=[\"'“„(\[]?[A-Z0-9])")
LONG_SENTENCE = re.compile(r"(?<=[;:,])[ \t]+")
MAX_SENTENCE_CHARS = 400
MIN_SHOUT_BYTES = 4
MIN_SHOUTED_WORDS = 3
ABBREVIATIONS = {
    "st", "mr", "mrs", "ms", "dr", "prof", "vol", "no", "jr", "sr", "vs", "etc",
    "ed", "pp", "al", "inc", "ltd", "co", "dept", "est", "ca", "approx",
}
MIN_LENGTH_RATIO = 0.5
MAX_LENGTH_RATIO = 2.5
REPEAT_LIMIT = 3


def load_requests(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def protected_terms(request):
    terms = [request["source_title"], *request["authors"]]
    parts = [part for term in terms for part in term.split(", ")]
    wanted = {term.strip() for term in terms + parts if len(term.strip()) > 3}
    return sorted(wanted, key=len, reverse=True)


def local_title(request, term, text):
    if term != request["source_title"]:
        return term
    quoted = f'"{term}"' in text or f"'{term}'" in text
    if not text.startswith(term) and not quoted:
        return term
    return request["title"].rstrip(" .")


def protect(text, request):
    replacements = {}
    for term in protected_terms(request):
        if term not in text:
            continue
        token = PLACEHOLDER.format(len(replacements))
        replacements[token] = (term, local_title(request, term, text))
        text = text.replace(term, token)
    return text, replacements


def balanced(text):
    if not text.startswith(tuple(QUOTES)):
        return text
    if sum(text.count(quote) for quote in QUOTES) % 2 == 0:
        return text
    return text[1:].lstrip()


def restore(text, replacements, original=False):
    text = CITATION.sub("", text).replace("**", "")
    text = PLACEHOLDER_PATTERN.sub(lambda match: PLACEHOLDER.format(match[1]), text)
    for token, (term, value) in replacements.items():
        text = text.replace(token, term if original else value)
    return text if original else balanced(text)


def abbreviated(sentence):
    last = sentence.split()[-1] if sentence.split() else ""
    if not last.endswith("."):
        return False
    word = last[:-1].strip("(\"'").lower()
    return word in ABBREVIATIONS or len(word) == 1


def joined(pieces):
    merged = []
    for piece in pieces:
        if merged and abbreviated(merged[-1]):
            merged[-1] = f"{merged[-1]} {piece}"
            continue
        merged.append(piece)
    return merged


def sentences(paragraph):
    pieces = []
    for sentence in joined(SENTENCE_BREAK.split(paragraph)):
        if len(sentence) <= MAX_SENTENCE_CHARS:
            pieces.append(sentence)
            continue
        pieces.extend(chunked(sentence))
    return [piece for piece in pieces if piece.strip()]


def chunked(sentence):
    chunks, current = [], ""
    for part in LONG_SENTENCE.split(sentence):
        if current and len(current) + len(part) > MAX_SENTENCE_CHARS:
            chunks.append(current)
            current = ""
        current = f"{current} {part}".strip()
    return chunks + [current] if current else chunks


def unshout(text):
    words = text.split(" ")
    shouted = [word for word in words if word.isupper() and len(word) >= MIN_SHOUT_BYTES]
    if len(shouted) < MIN_SHOUTED_WORDS:
        return text
    return " ".join(word.title() if word in shouted else word for word in words)


def layout(text):
    paragraphs = unshout(text).split("\n\n")
    return [sentences(paragraph) for paragraph in paragraphs]


def rejoin(paragraphs):
    return "\n\n".join(" ".join(paragraph) for paragraph in paragraphs)


def repeats(text):
    words = text.split()
    grams = [" ".join(words[index : index + 3]) for index in range(len(words) - 2)]
    runs, previous, run = 1, None, 1
    for gram in grams:
        run = run + 1 if gram == previous else 1
        runs = max(runs, run)
        previous = gram
    return runs


def rejection(source, target, replacements):
    if not target.strip():
        return "empty"
    ratio = len(target) / max(len(source), 1)
    if not MIN_LENGTH_RATIO <= ratio <= MAX_LENGTH_RATIO:
        return f"length {ratio:.2f}"
    if any(token not in target for token in replacements):
        return "placeholder lost"
    if repeats(target) >= REPEAT_LIMIT:
        return "repetition"
    if target.strip() == source.strip():
        return "untranslated"
    return None


class Engine:
    def __init__(self, model, models_dir, device, compute_type, language="de"):
        import ctranslate2
        from transformers import AutoTokenizer

        self.name = model
        self.target = NLLB_CODES[language] if "nllb" in model else None
        multi_target = model.endswith(MULTI_TARGET_SUFFIXES)
        self.prefix = f">>{OPUS_CODES[language]}<< " if multi_target else ""
        path = convert(model, models_dir, compute_type)
        self.translator = ctranslate2.Translator(
            str(path), device=device, compute_type=compute_type
        )
        self.tokenizer = AutoTokenizer.from_pretrained(model)
        if self.target:
            self.tokenizer.src_lang = NLLB_CODES["en"]

    def translate(self, pieces, beam_size, batch_tokens):
        encoded = self.tokenizer([self.prefix + piece for piece in pieces]).input_ids
        tokens = [self.tokenizer.convert_ids_to_tokens(ids) for ids in encoded]
        prefix = [[self.target]] * len(tokens) if self.target else None
        results = self.translator.translate_batch(
            tokens,
            target_prefix=prefix,
            beam_size=beam_size,
            max_batch_size=batch_tokens,
            batch_type="tokens",
            max_decoding_length=512,
            replace_unknowns=True,
        )
        return [self.decode(result.hypotheses[0]) for result in results]

    def decode(self, hypothesis):
        tokens = hypothesis[1:] if self.target else hypothesis
        ids = self.tokenizer.convert_tokens_to_ids(tokens)
        return self.tokenizer.decode(ids, skip_special_tokens=True)


def convert(model, models_dir, compute_type):
    path = Path(models_dir) / model.split("/")[-1]
    if path.exists():
        return path
    command = [
        "ct2-transformers-converter",
        "--model",
        model,
        "--output_dir",
        str(path),
        "--quantization",
        compute_type,
    ]
    subprocess.run(command, check=True)
    return path


def cache_key(model, text):
    return hashlib.sha256(f"{model}\n{text}".encode()).hexdigest()[:32]


def load_cache(path):
    if not path or not Path(path).exists():
        return {}
    with open(path, encoding="utf-8") as handle:
        rows = (json.loads(line) for line in handle if line.strip())
        return {row["key"]: row["text"] for row in rows}


def save_cache(path, fresh):
    if not path or not fresh:
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key, text in fresh.items():
            handle.write(json.dumps({"key": key, "text": text}) + "\n")


def varint(buffer, value):
    while value >= 0x80:
        buffer.append(value & 0x7F | 0x80)
        value >>= 7
    buffer.append(value)


def read_segment(path):
    if not path or not Path(path).exists():
        return {}
    blob = Path(path).read_bytes()
    body = zlib.decompressobj(-zlib.MAX_WBITS).decompress(blob[8:])
    translations, cursor, work = {}, 0, 0
    while cursor < len(body):
        delta, cursor = read_varint(body, cursor)
        length, cursor = read_varint(body, cursor)
        work += delta
        translations[work] = body[cursor : cursor + length].decode("utf-8")
        cursor += length
    return translations


def read_varint(body, cursor):
    value, shift = 0, 0
    while True:
        byte = body[cursor]
        cursor += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, cursor
        shift += 7


def write_segment(path, translations):
    body = bytearray()
    previous = 0
    for work in sorted(translations):
        varint(body, work - previous)
        text = translations[work].encode("utf-8")
        varint(body, len(text))
        body += text
        previous = work
    compressor = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    blob = compressor.compress(bytes(body)) + compressor.flush()
    Path(path).write_bytes(MAGIC + VERSION.to_bytes(4, "little") + blob)


def prepare(requests):
    prepared = []
    for request in requests:
        masked, replacements = protect(request["text"], request)
        prepared.append((request, masked, replacements, layout(masked)))
    return prepared


def flatten(prepared):
    pieces = []
    for _, _, _, paragraphs in prepared:
        pieces.extend(piece for paragraph in paragraphs for piece in paragraph)
    return pieces


def regroup(prepared, translated):
    grouped, cursor = [], 0
    for _, _, _, paragraphs in prepared:
        shaped = []
        for paragraph in paragraphs:
            shaped.append(translated[cursor : cursor + len(paragraph)])
            cursor += len(paragraph)
        grouped.append(shaped)
    return grouped


def retry_unmasked(engine, prepared, grouped, options):
    pieces, spots = [], []
    for index, ((_, _, replacements, paragraphs), translated) in enumerate(
        zip(prepared, grouped)
    ):
        joined = " ".join(piece for paragraph in translated for piece in paragraph)
        if all(token in joined for token in replacements):
            continue
        for number, paragraph in enumerate(paragraphs):
            for position, source in enumerate(paragraph):
                pieces.append(restore(source, replacements, original=True))
                spots.append((index, number, position))
        replacements.clear()
    if not pieces:
        return grouped
    print(f"retranslating {len(spots)} sentences unmasked", file=sys.stderr)
    for spot, text in zip(spots, engine.translate(pieces, options.beam, options.batch)):
        index, number, position = spot
        grouped[index][number][position] = text
    return grouped


def translate_requests(engine, requests, options):
    prepared = prepare(requests)
    pieces = flatten(prepared)
    cache = load_cache(options.cache)
    missing = [piece for piece in pieces if cache_key(engine.name, piece) not in cache]
    unique = sorted(set(missing), key=len, reverse=True)
    started = time.time()
    fresh = {}
    for index in range(0, len(unique), options.chunk):
        batch = unique[index : index + options.chunk]
        done = engine.translate(batch, options.beam, options.batch)
        for piece, text in zip(batch, done):
            fresh[cache_key(engine.name, piece)] = text
        report(index + len(batch), len(unique), started)
    save_cache(options.cache, fresh)
    cache.update(fresh)
    translated = [cache[cache_key(engine.name, piece)] for piece in pieces]
    grouped = regroup(prepared, translated)
    return prepared, retry_unmasked(engine, prepared, grouped, options)


def report(done, total, started):
    elapsed = time.time() - started
    rate = done / max(elapsed, 0.001)
    remaining = (total - done) / max(rate, 0.001)
    print(
        f"{done}/{total} sentences, {rate:.0f}/s, {remaining / 60:.1f} min left",
        file=sys.stderr,
        flush=True,
    )


def collect(prepared, grouped):
    translations, rejected = {}, {}
    for (request, masked, replacements, _), paragraphs in zip(prepared, grouped):
        target = rejoin(paragraphs)
        reason = rejection(masked, target, replacements)
        if reason:
            rejected[request["work"]] = reason
            continue
        translations[request["work"]] = restore(target, replacements)
    return translations, rejected


def summarize(requests, translations, rejected, merged):
    print(f"requests {len(requests)}", file=sys.stderr)
    print(f"translated {len(translations)}, total {len(merged)}", file=sys.stderr)
    reasons = {}
    for reason in rejected.values():
        key = reason.split(" ")[0]
        reasons[key] = reasons.get(key, 0) + 1
    for reason, count in sorted(reasons.items(), key=lambda row: -row[1]):
        print(f"rejected {reason}: {count}", file=sys.stderr)


def options(argument_list=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("requests", help="requests-<lang>.jsonl from the builder")
    parser.add_argument("output", help="translations-<lang>.bin to write")
    parser.add_argument("--language", required=True, choices=sorted(OPUS_CODES))
    parser.add_argument("--model", default=MODEL)
    parser.add_argument("--previous", help="translations-<lang>.bin to merge into")
    parser.add_argument("--models-dir", default="models")
    parser.add_argument("--cache", default="cache.jsonl")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--compute-type", default="int8_float16")
    parser.add_argument("--beam", type=int, default=1)
    parser.add_argument("--batch", type=int, default=8192)
    parser.add_argument("--chunk", type=int, default=20000)
    parser.add_argument("--limit", type=int, help="translate only the first N requests")
    return parser.parse_args(argument_list)


def main():
    chosen = options()
    requests = load_requests(chosen.requests)[: chosen.limit]
    engine = Engine(
        chosen.model,
        chosen.models_dir,
        chosen.device,
        chosen.compute_type,
        chosen.language,
    )
    prepared, grouped = translate_requests(engine, requests, chosen)
    translations, rejected = collect(prepared, grouped)
    merged = read_segment(chosen.previous) | translations
    write_segment(chosen.output, merged)
    summarize(requests, translations, rejected, merged)


if __name__ == "__main__":
    main()
