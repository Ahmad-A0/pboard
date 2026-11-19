# pboard

A privacy preserving Gboard alternative.

![demo](docs/media/demo.gif)

Android keyboard with on-device autocorrect, prediction and glide typing. It has no `INTERNET` permission.
English (UK/US) only for now.

## Install

Get the APK from [Releases](https://github.com/Ahmad-A0/pboard/releases), or add this repo to
[Obtainium](https://obtainium.imranr.dev/) for updates.

Voice typing, translation (de/es/fr) and text scanning are separate APKs on the same release page. The keyboard
can't download anything, so they are installed by hand.

## Accuracy

Top-1 word accuracy, replaying recorded human typing through each keyboard on a device:

| Set | pboard | Gboard 18.6 | FUTO Keyboard 0.1.30 |
|---|---|---|---|
| FUTO swipe | 92.8% | 86.0% | **93.0%** |
| How We Swipe | **79.3%** | 75.8% | **79.3%** |
| VTAP (taps) | 95.8% | **96.2%** | 93.0% |
| TSI (taps) | **93.1%** | 92.4% | 79.2% |

FUTO Keyboard was measured in a separate run, alongside pboard: [docs/bench/futo-keyboard](docs/bench/futo-keyboard/README.md).
Harness: `tools/bench`. Method: `docs/bench/README.md`.

## Privacy

- No network permission. The build fails if one appears in the manifest.
- Learned words stay in app storage and are excluded from Android backup. Nothing is learned in password fields.
- Optional permissions: microphone (voice typing) and contacts (name suggestions, off by default).

## Missing

- Languages other than English.
- GIF and sticker search (needs network).

## Build

```
nix develop --command ./gradlew :app:assembleDebug
nix develop --command ./gradlew :engine:test :translate:test :app:testDebugUnitTest
```

Or without Nix: JDK 17, Android SDK platform 36, build-tools 37. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. AOSP-derived parts are Apache-2.0 and the language model data is CC BY-SA 4.0; see [NOTICE](NOTICE).
