# Voice models

Tooling for the optional models the app downloads (`data/Models.kt`).

## Manifest

The app lists models from `voices.json` in the release named by `VOICES_RELEASE`.
`manifest.py` builds it: the speaker model (GitHub release, hash from its `.sha256`)
and Pocket TTS per language in standard (6 layers) and high (24 layers) quality, plus
English fast, standard and high, all pinned to one Hugging Face revision.

```sh
python -I manifest.py voices.json GGUF_FOLDER
gh release upload voices-2026-11 voices.json --clobber -R tn3w/Shelf
```

Every model and voice carries licence, attribution and source; the script and the app
accept only open licences. Voices also carry gender and, where documented, age in
years (VCTK speaker info); the app turns them into the casting pool.

Voices: 22 of 27 Pocket presets. Excluded: cosette (Expresso, CC BY-NC), jean (EARS,
CC BY-NC), juergen, rafael, daan (source and licence not stated).

Genders not taken from VCTK were checked by median pitch (librosa pYIN) and
`alefiury/wav2vec2-large-xlsr-53-gender-recognition-librispeech`, on the source
recordings and on English speech generated from each preset (VCTK anna and paul as
controls). Agree with the manifest: caro_davy, bill_boerst, peter_yearsley,
stuart_bell, estelle, giovanni, lola, javert. Disagree: alba is listed Female but
measures male (115–124 Hz, classifier male). Unclear: marius is listed Male and
classified male, but its pitch is 244–287 Hz (possibly a young speaker).

## Pocket TTS to GGUF

`convert_pocket.py` turns each Pocket model plus its open voices into one GGUF
(Q8_0/F16 weights, F16 voice states, config and tokenizer embedded) with a `.sha256`.

```sh
uv run --with gguf --with numpy -- python -I convert_pocket.py OUT [CONFIG ...]
```

## Native speaker benchmark

`bench.c` times prompt, greedy and constrained generation of
`android/app/src/main/cpp/speaker.c` and prints resident memory after loading. Token
ids are comma separated.

```sh
NDK=~/Android/Sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin
C=android/app/src/main/cpp
$NDK/aarch64-linux-android26-clang -O2 -I$C -DVARIANT= -march=armv8.2-a+dotprod \
  -o bench-dot tooling/voices/bench.c $C/speaker.c -lm
$NDK/aarch64-linux-android26-clang -O2 -I$C -DVARIANT= \
  -o bench-base tooling/voices/bench.c $C/speaker.c -lm
adb push bench-dot bench-base speaker.gguf prompt.ids /data/local/tmp/
adb shell 'cd /data/local/tmp && ./bench-dot speaker.gguf prompt.ids 32 4'
```

Pixel 7a, 4 threads, 551-token prompt, same tokens on every path, 60 s cool-down:

| Path | Prompt | Generation | Constrained | Resident after load |
|---|---|---|---|---|
| dotprod | 76.3 tok/s | 12.9 tok/s | 17.1 tok/s | 1.01 GB |
| NEON without dotprod | 30.6 tok/s | 11.5 tok/s | 15.5 tok/s | 1.01 GB |

The source pages of repacked layers are dropped after loading (1.84 GB resident
before). Workers spin briefly and then sleep, so an idle model costs no CPU. 32-bit
and x86 builds use the scalar path.

`libshelf.so` is byte-identical when built from two different checkout paths (all
four ABIs, NDK 30.0.16248370, CMake 3.31.6).
