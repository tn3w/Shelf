# Contributing

Contributions are welcome: bug reports, translations, catalogue ideas, code. Forking and
shipping your own build is fine too; the MIT license asks only for attribution.

## Setup

| Part | Needs |
|---|---|
| `android/` | JDK 25, Android SDK 37. `./gradlew assembleGithubDebug` |
| `builder/` | Rust stable. `cargo build --release` |
| `tooling/` | Python 3.11+ |

Open `android/` in Android Studio for the app; the repo root works for everything else.

## Workflow

1. Fork, branch off `master`.
2. Keep the change focused; one topic per pull request.
3. Run `./gradlew lintGithubDebug testGithubDebugUnitTest` in `android/`, or
   `cargo fmt --check && cargo clippy && cargo test` in `builder/`.
4. Update `README.md` when behaviour or layout changes.
5. Open the pull request and describe what changed and how you tested it.

## Style

Follow the surrounding code. Self-documenting names, early returns, shallow nesting, no
commentary that the code already states. Kotlin official style, `cargo fmt` for Rust.

## Translations

Strings live in `android/app/src/main/res/values-<lang>/strings.xml`. Add a language by
copying `values/strings.xml` and translating the values only. Catalogue descriptions are
machine translated by `tooling/translate.py`; corrections belong in the builder pass, not
in the app.

## Reporting

Use the issue templates. Security issues go to [SECURITY.md](SECURITY.md) instead of the
tracker.
