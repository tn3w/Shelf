"""Build voices.json, the manifest of optional models the app can download.

python -I manifest.py voices.json GGUF_FOLDER

GGUF_FOLDER holds the converted Pocket TTS files and their .sha256 (convert_pocket.py),
uploaded to the same release. Every model and voice carries its licence; only OPEN
licences are written.
"""

import json
import re
import sys
import urllib.request
from pathlib import Path

FORMAT = 1
OPEN = {"Apache-2.0", "MIT", "BSD-3-Clause", "CC-BY-4.0", "CC0-1.0"}
RELEASE = "voices-2026-11"
GITHUB = f"https://api.github.com/repos/tn3w/Shelf/releases/tags/{RELEASE}"
SPEAKER = "shelf-speaker-qwen3-1.7b-q4_k_m.gguf"
DOWNLOAD = f"https://github.com/tn3w/Shelf/releases/download/{RELEASE}"
POCKET = "kyutai/pocket-tts-without-voice-cloning"
CODE = "kyutai-labs/pocket-tts"
CODE_COMMIT = "c91f50f38c6b914d49dbdff51b8d322edf843db2"
WEIGHTS = re.compile(r"weights_path_without_voice_cloning: hf://[^/]+/[^/]+/(\S+)@(\w+)")
VOICE_REVISION = re.compile(r"embeddings/\{name\}\.safetensors@(\w+)")
POCKET_ATTRIBUTION = (
    f"Pocket TTS by Kyutai, CC BY 4.0 (huggingface.co/{POCKET}); converted to GGUF "
    "(Q8_0/F16) by Shelf"
)
VCTK = "VCTK {}, CSTR, University of Edinburgh, CC BY 4.0"
LIBRIVOX = "{}, LibriVox via Voice-Zero, CC0"
DONATION = "Unmute Voice Donation Project, CC0"

VOICES = {
    "alba": ("Female", None, "CC-BY-4.0", "Alba MacKenna, CC BY 4.0"),
    "anna": ("Female", 22, "CC-BY-4.0", VCTK.format("p228")),
    "azelma": ("Female", 24, "CC-BY-4.0", VCTK.format("p303")),
    "bill_boerst": ("Male", None, "CC0-1.0", LIBRIVOX.format("Bill Boerst")),
    "caro_davy": ("Female", None, "CC0-1.0", LIBRIVOX.format("Caro Davy")),
    "charles": ("Male", 21, "CC-BY-4.0", VCTK.format("p254")),
    "eponine": ("Female", 23, "CC-BY-4.0", VCTK.format("p262")),
    "estelle": ("Female", None, "CC0-1.0", "Kyutai recording, CC0"),
    "eve": ("Female", 19, "CC-BY-4.0", VCTK.format("p361")),
    "fantine": ("Female", 22, "CC-BY-4.0", VCTK.format("p244")),
    "george": ("Male", 18, "CC-BY-4.0", VCTK.format("p315")),
    "giovanni": ("Male", None, "CC0-1.0", "Mozilla Common Voice (it), CC0"),
    "jane": ("Female", 21, "CC-BY-4.0", VCTK.format("p339")),
    "javert": ("Male", None, "CC0-1.0", DONATION),
    "lola": ("Female", None, "CC0-1.0", "Mozilla Common Voice (es), CC0"),
    "marius": ("Male", None, "CC0-1.0", DONATION),
    "mary": ("Female", 19, "CC-BY-4.0", VCTK.format("p333")),
    "michael": ("Male", 19, "CC-BY-4.0", VCTK.format("p360")),
    "paul": ("Male", 23, "CC-BY-4.0", VCTK.format("p259")),
    "peter_yearsley": ("Male", None, "CC0-1.0", LIBRIVOX.format("Peter Yearsley")),
    "stuart_bell": ("Male", None, "CC0-1.0", LIBRIVOX.format("Stuart Bell")),
    "vera": ("Female", 23, "CC-BY-4.0", VCTK.format("p229")),
}
EXCLUDED = {
    "cosette": "Expresso, CC BY-NC 4.0",
    "jean": "EARS, CC BY-NC 4.0",
    "juergen": "source and licence not stated",
    "rafael": "source and licence not stated",
    "daan": "source and licence not stated",
}
FOLDERS = {
    "de": "german",
    "fr": "french",
    "es": "spanish",
    "pt": "portuguese",
    "it": "italian",
    "nl": "dutch",
}
TTS = [
    *[(f"tts-{code}", code, name, "standard") for code, name in FOLDERS.items()],
    *[(f"tts-{code}-24l", code, f"{name}_24l", "high") for code, name in FOLDERS.items()],
    ("tts-en-fast", "en", "english_18m_2026-10", "fast"),
    ("tts-en", "en", "english_2026-10", "standard"),
    ("tts-en-24l", "en", "english_2026-09_24l", "high"),
]
RUNTIME_RAM = 150_000_000
REVISIONS = {f"tts-{code}-24l": 2 for code in ("de", "en", "es", "nl", "pt")}


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Shelf manifest"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def speaker():
    release = json.loads(fetch(GITHUB))
    assets = {asset["name"]: asset for asset in release["assets"]}
    model = assets[SPEAKER]
    checksum = fetch(assets[SPEAKER + ".sha256"]["browser_download_url"]).split()[0]
    return {
        "id": "speaker",
        "kind": "speaker",
        "version": RELEASE.removeprefix("voices-"),
        "licence": "Apache-2.0",
        "attribution": "Qwen3-1.7B by Alibaba Qwen, Apache 2.0; fine-tuned by Shelf on "
        "PDNC (CC BY 4.0) and public-domain books",
        "source": f"https://github.com/tn3w/Shelf/releases/tag/{RELEASE}",
        "ram": 1_300_000_000,
        "files": [
            {
                "name": SPEAKER,
                "url": model["browser_download_url"],
                "size": model["size"],
                "sha256": checksum.decode(),
            }
        ],
    }


def config_text(config):
    url = f"https://raw.githubusercontent.com/{CODE}/{CODE_COMMIT}/pocket_tts/config"
    return fetch(f"{url}/{config}.yaml").decode()


def voice_revision():
    url = f"https://raw.githubusercontent.com/{CODE}/{CODE_COMMIT}/pocket_tts/utils/utils.py"
    return VOICE_REVISION.search(fetch(url).decode()).group(1)


def pocket_source(config):
    path, revision = WEIGHTS.search(config_text(config)).groups()
    return path.rsplit("/", 1)[0], revision


def voice(name, file):
    gender, years, licence, attribution = VOICES[name]
    entry = {"id": name, "file": file, "gender": gender}
    if years:
        entry["years"] = years
    return entry | {"licence": licence, "attribution": attribution}


def tts(folder, model_id, language, config, quality):
    path = Path(folder) / f"shelf-{model_id}-q8_0.gguf"
    checksum = Path(f"{path}.sha256").read_text().split()[0]
    size = path.stat().st_size
    base, revision = pocket_source(config)
    file = {"name": path.name, "url": f"{DOWNLOAD}/{path.name}", "size": size}
    version = RELEASE.removeprefix("voices-")
    if model_id in REVISIONS:
        version += f".{REVISIONS[model_id]}"
    return {
        "id": model_id,
        "kind": "tts",
        "version": version,
        "licence": "CC-BY-4.0",
        "language": language,
        "quality": quality,
        "attribution": POCKET_ATTRIBUTION,
        "source": f"https://huggingface.co/{POCKET}/tree/{revision}/{base}",
        "ram": size + RUNTIME_RAM,
        "files": [file | {"sha256": checksum}],
        "voices": [voice(name, path.name) for name in VOICES],
    }


def check(model):
    licences = [model["licence"], *(v["licence"] for v in model.get("voices", []))]
    closed = [licence for licence in licences if licence not in OPEN]
    if closed:
        sys.exit(f"{model['id']}: licences {closed} are not open")
    return model


def main():
    models = [speaker(), *(tts(sys.argv[2], *spec) for spec in TTS)]
    manifest = {"format": FORMAT, "models": [check(model) for model in models]}
    with open(sys.argv[1], "w") as output:
        json.dump(manifest, output, indent=1)
        output.write("\n")
    for model in models:
        size = sum(file["size"] for file in model["files"]) / 1e6
        voices = len(model.get("voices", []))
        print(f"{model['id']:14} {size:8.0f} MB  {voices:2} voices")
    print("excluded voices:", ", ".join(f"{k} ({v})" for k, v in EXCLUDED.items()))


if __name__ == "__main__":
    main()
