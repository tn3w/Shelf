"""Convert Pocket TTS models plus their open-licensed voices to one GGUF each.

uv run --with gguf --with numpy -- python -I convert_pocket.py OUT [CONFIG ...]

CONFIG is a Pocket config name (german_24l, english_2026-10, ...); default: every model
in manifest.TTS. Sources are cached in ~/.cache/shelf-voices/pocket/src.
The Mimi encoder (voice cloning only) is dropped and "_transformer.transformer."
shortens to "_tf." to fit GGUF names. Large 2D weights become Q8_0, other matrices and
convolutions F16, vectors F32, voice KV caches F16. Voices come from the revision the
upstream runtime pins, which can differ from the weights' revision. The config YAML and
the tokenizer it names (tokenizer.json as metadata, or a SentencePiece model as an I8
tensor) are embedded.
"""

import hashlib
import json
import re
import struct
import sys
import urllib.request
from pathlib import Path

import gguf
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from manifest import (  # noqa: E402
    CODE,
    CODE_COMMIT,
    POCKET,
    TTS,
    VOICES,
    WEIGHTS,
    config_text,
    voice_revision,
)

TOKENIZER = re.compile(r"tokenizer_path: hf://[^/]+/[^/]+/(\S+)@(\w+)")
SOURCES = Path.home() / ".cache/shelf-voices/pocket/src"
QUANTIZED_MIN = 65536
MAX_NAME = 63
DTYPES = {"BF16": np.uint16, "F32": np.float32, "I64": np.int64}
ENCODER = ("mimi.encoder", "mimi.downsample")


def fetch(url, target=None):
    if target and target.exists():
        return target.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "Shelf converter"})
    with urllib.request.urlopen(request, timeout=600) as response:
        data = response.read()
    if target:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return data


def read_safetensors(data):
    length = struct.unpack("<Q", data[:8])[0]
    header = json.loads(data[8 : 8 + length])
    header.pop("__metadata__", None)
    body = memoryview(data)[8 + length :]
    for name, info in header.items():
        start, end = info["data_offsets"]
        array = np.frombuffer(body[start:end], dtype=DTYPES[info["dtype"]])
        if info["dtype"] == "BF16":
            array = (array.astype(np.uint32) << 16).view(np.float32)
        yield name, array.reshape(info["shape"])


def add(writer, name, array):
    if len(name) > MAX_NAME:
        sys.exit(f"tensor name too long: {name}")
    if array.dtype.kind == "i":
        return writer.add_tensor(name, array.astype(np.int32))
    array = array.astype(np.float32)
    quantize = array.ndim == 2 and array.shape[-1] % 32 == 0
    if quantize and array.size >= QUANTIZED_MIN:
        data = gguf.quants.quantize(array, gguf.GGMLQuantizationType.Q8_0)
        return writer.add_tensor(name, data, raw_dtype=gguf.GGMLQuantizationType.Q8_0)
    writer.add_tensor(name, array.astype(np.float16 if array.ndim > 1 else np.float32))


def voice_tensors(name, data):
    for key, array in read_safetensors(data):
        layer = re.search(r"layers\.(\d+)\.self_attn/(\w+)", key)
        if not layer:
            sys.exit(f"unexpected voice tensor {key}")
        index, part = layer.groups()
        if part == "cache":
            yield f"voice.{name}.{index}.cache", array.squeeze(1).astype(np.float16)
        else:
            yield f"voice.{name}.{index}.{part}", array


def convert(config, output, model_id, language, quality):
    text = config_text(config)
    path, revision = WEIGHTS.search(text).groups()
    folder = path.rsplit("/", 1)[0]
    base = f"https://huggingface.co/{POCKET}/resolve/{revision}/{folder}"
    cache = SOURCES / config / revision

    tokenizer_path, tokenizer_revision = TOKENIZER.search(text).groups()
    tokenizer_name = tokenizer_path.rsplit("/", 1)[-1]
    tokenizer = fetch(
        f"https://huggingface.co/{POCKET}/resolve/{tokenizer_revision}/{tokenizer_path}",
        SOURCES / config / tokenizer_revision / tokenizer_name,
    )

    def source(name):
        return fetch(f"{base}/{name}", cache / name)

    voices_revision = voice_revision()

    def voice(name):
        path = f"{folder}/embeddings/{name}.safetensors"
        url = f"https://huggingface.co/{POCKET}/resolve/{voices_revision}/{path}"
        target = SOURCES / config / voices_revision / "embeddings" / f"{name}.safetensors"
        return fetch(url, target)

    writer = gguf.GGUFWriter(output, "pocket-tts")
    writer.add_name(f"Pocket TTS {config}")
    writer.add_string("general.license", "CC-BY-4.0")
    source_url = f"https://huggingface.co/{POCKET}/tree/{revision}/{folder}"
    writer.add_string("general.source.url", source_url)
    writer.add_string(
        "general.description",
        f"Pocket TTS by Kyutai (CC BY 4.0), config from {CODE}@{CODE_COMMIT[:7]} (MIT). "
        "Converted by Shelf: weights quantized to Q8_0/F16, voice states to F16.",
    )
    writer.add_string("pocket.model_id", model_id)
    writer.add_string("pocket.language", language)
    writer.add_string("pocket.quality", quality)
    writer.add_string("pocket.config", text)
    if tokenizer_name.endswith(".json"):
        writer.add_string("pocket.tokenizer", "tokenizers")
        writer.add_string("tokenizer.json", tokenizer.decode())
    else:
        writer.add_string("pocket.tokenizer", "sentencepiece")
        pieces = np.frombuffer(tokenizer, dtype=np.int8)
        writer.add_tensor("tokenizer.sentencepiece", pieces)
    for name, array in read_safetensors(source("model.safetensors")):
        if not name.startswith(ENCODER):
            add(writer, name.replace("_transformer.transformer.", "_tf."), array)
    names = []
    for name in VOICES:
        data = voice(name)
        names.append(name)
        for key, array in voice_tensors(name, data):
            add(writer, key, array)
    writer.add_array("pocket.voices", names)
    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()
    digest = hashlib.sha256(Path(output).read_bytes()).hexdigest()
    Path(f"{output}.sha256").write_text(f"{digest}  {Path(output).name}\n")
    return digest


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    wanted = set(sys.argv[2:])
    for model_id, language, config, quality in TTS:
        if wanted and config not in wanted:
            continue
        target = out / f"shelf-{model_id}-q8_0.gguf"
        digest = convert(config, target, model_id, language, quality)
        megabytes = target.stat().st_size / 1e6
        print(f"{target.name:32} {megabytes:8.1f} MB  {digest[:12]}", flush=True)


if __name__ == "__main__":
    main()
