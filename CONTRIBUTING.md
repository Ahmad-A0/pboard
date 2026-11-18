# Contributing to pboard

pboard is a privacy preserving Gboard alternative: an Android keyboard that aims for the same visual design, feel
and typing quality (autocorrect, prediction, glide typing), with everything staying on the device.

## Building

Everything runs inside the Nix flake devshell (Android SDK platforms 36/37, build-tools 37, emulator, adb, JDK 17,
ImageMagick, ffmpeg, Python 3):

```
nix develop --command ./gradlew :app:assembleDebug
nix develop --command ./gradlew help          # checks that the build configures
```

Gradle 9 with AGP 9 (Kotlin is built into AGP). Machine-specific settings (a test phone's adb serial, bind
addresses) go in an untracked `local.env` at the repository root; see `local.env.example`. Nothing machine-specific
belongs in tracked files.

Gradle device tasks (`install*`, `connected*`, `generate*BaselineProfile`) require `ANDROID_SERIAL=<one device>`;
without it AGP would act on every attached device, so the build refuses.

## Modules

- `:engine` — pure Kotlin/JVM, no Android dependencies: decoding, language model, glide typing. Its contract is
  `engine/src/main/kotlin/dev/pboard/engine/Api.kt`.
- `:app` — the IME (`dev.pboard.ime`), the Compose settings UI and `PlaygroundActivity` (a test text field:
  `adb shell am start -n dev.pboard/.PlaygroundActivity`).
- `:translate` — on-device translation engine; `:translatepack`, `:scanpack`, `:voicepack`, `:voicepackaccurate` —
  optional pack APKs with models; `:bench`, `:baselineprofile`, `:testapp` — test and measurement helpers.
- `tools/` — Python/shell data pipelines, benchmark and conformance harnesses. `docs/` — specs and findings.

## Tests

```
nix develop --command ./gradlew :engine:test :translate:test :app:testDebugUnitTest
nix develop --command ./gradlew :app:assembleRelease :bench:assembleDebug
```

These are the merge gate: a change must build and pass them. Claims about typing quality or behavioural parity
need evidence from the benchmark (`tools/bench`, `docs/bench/README.md`) or conformance harness
(`tools/conformance/README.md`), not only unit tests. Changes to the language-model assets
(`app/src/main/assets/lm`) need the engine's held-out evaluation (`docs/lm-eval.md`).

## Privacy rules (not negotiable)

- No `INTERNET` permission, no analytics, no network code at all. The build checks the merged manifest and fails on
  any network-related permission.
- All learning is on-device. Respect `IME_FLAG_NO_PERSONALIZED_LEARNING` and password/sensitive fields: no learning,
  and no suggestions in password fields.
- Personal data (user dictionary, learned n-grams) stays in app-private storage and is excluded from backup.

## Clean-room rules

- Other keyboards (Gboard, SwiftKey, ...) are references for behaviour only: how something looks, moves and
  responds. Never copy their code, drawables, icons, fonts, sounds, dictionaries or ML models into this repository;
  write our own implementation. Reference screenshots of other products stay out of the repository
  (`docs/ref/` subfolders are gitignored).
- HeliBoard is GPL-3.0: do not copy its code (pboard's licence must stay permissive).
- AOSP LatinIME is Apache-2.0: code and algorithms may be ported; record the provenance in `NOTICE`.
- Icons: Material Symbols (Apache-2.0) or hand-drawn vectors. Fonts: the system font or OFL-licensed fonts only.
- Data: only openly licensed corpora, recorded in `docs/data-licenses.md` and `NOTICE`.

## Code and commits

- Kotlin with coroutines; Compose for settings and panels; a custom hardware-accelerated `View` for the key surface
  (latency matters there).
- Commit in small, self-contained steps. Commit messages are plain and descriptive: say what changed and why. No
  `Co-Authored-By` or other attribution trailers.
- By contributing you agree that your contribution is licensed under the repository's licence (MIT; see `LICENSE`
  and `NOTICE` for the parts under other licences).
