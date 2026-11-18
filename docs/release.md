# Releases, signing, versioning and updates

pboard has no network permission and never will (the build fails if one appears, see "Build checks"). Updates
therefore reach a phone through an external updater app or adb; pboard itself is never involved.

## TL;DR

```
tools/release.sh --tag v0.2.0      # from a clean main checkout: tag, build, sign, verify, notes, update repo
tools/release/serve_repo.sh        # optional: serve dist/repo on a private address for Obtainium on the phone
```

Output in `dist/<versionName>/`: `pboard-<versionName>.apk`, `.apk.sha256`, `RELEASE_NOTES.md` (merges since the
previous tag), `CHANGELOG.md` (all releases), `release.json` (version, commit, signer, size, permissions) and
`mapping.txt` (R8 mapping, needed to read crash stack traces; keep it, do not publish it). `dist/` is gitignored.

## Signing

| Build | Key | Used for |
|---|---|---|
| any build by default (`assembleRelease`, `assembleDebug`, `bench`) | the machine's debug key (`~/.android/debug.keystore`) | development, emulators, conformance, benchmarks, and the dev phone **today** |
| `tools/release.sh` (`-Ppboard.release=true`) | the release key (below) + the key-rotation lineage | distributed releases |
| `tools/release.sh --allow-debug-key` (`-Ppboard.allowDebugSigning=true`) | debug key, versionName `+debugsigned`, file `-DEBUGSIGNED` | CI without secrets only; such an APK cannot update a release install |

Release signing is opt-in rather than automatic because the dev phone's installed pboard is debug-signed: Android
refuses an update signed by a different key, and the only other way is an uninstall, which wipes learned data. See
"Moving the dev phone to release builds".

Where the release key comes from (`app/build.gradle.kts`), first match wins:
1. env `PBOARD_KEYSTORE`, `PBOARD_KEYSTORE_PASSWORD`, `PBOARD_KEY_ALIAS` (default `pboard`), `PBOARD_KEY_PASSWORD`
   (CI secrets);
2. the properties file `$PBOARD_SIGNING_PROPERTIES`, default `~/.config/pboard/signing.properties`:
   `storeFile` (relative to the file), `storePassword`, `keyAlias`, `keyPassword`, and optionally `lineage`.

Without either, a release build fails (unless `allowDebugSigning`). Secrets never live in the repository.

### The keys

The release keystore, its `signing.properties` (keystore path, alias, password) and the key-rotation lineage are kept
outside the repo; see your local `signing.properties` for where. Keep the directory mode 700 and the files 600.

| File | What | Secret? |
|---|---|---|
| release keystore | release key: PKCS12, RSA 4096, SHA256withRSA, alias `pboard`, `CN=pboard release, O=pboard`, valid to 2076-11-07 | **yes** |
| `signing.properties` | keystore path, alias and the (random, 40-char) password | **yes** |
| rotation lineage | APK Signature Scheme v3 proof that the release key succeeds the debug key | no |

Release certificate SHA-256: `1c0551f97e3f12aa6130d51272b5961d84bc065c7e988de12c728d1624c2ce98`
(`1C:05:51:F9:7E:3F:12:AA:61:30:D5:12:72:B5:96:1D:84:BC:06:5C:7E:98:8D:E1:2C:72:8D:16:24:C2:CE:98`).
The dev phone's current pboard is signed by a debug key, SHA-256
`ded7dde83f97d073970b89fa842ceafc3d705fa94440f2b22c069aedfb56055e`.

They were made by `tools/release/make_key.sh` (refuses to overwrite an existing key) and
`tools/release/make_rotation.sh`.

### Back up the key (do this now)

If the release key is lost, no installed copy can ever be updated again: every phone would need an uninstall
(learned data lost) and a new key. If it leaks, someone else can sign "updates". So:

1. Back up the three files above together. Until the dev phone has moved to release builds, also back up the debug
   keystore that signed its current install (it is the only key that can update it).
2. Make an encrypted archive and keep it in two places that are not the build machine (e.g. a password manager
   attachment and an offline USB stick):
   ```
   tar -czf - <signing-dir> <debug-keystore> | gpg --symmetric --cipher-algo AES256 \
     > pboard-signing-$(date +%F).tar.gz.gpg
   ```
   Store the gpg passphrase in your password manager (the keystore password is inside the archive, in
   `signing.properties`).
3. Check the backup restores: decrypt to a temporary directory and run `keytool -list -v -keystore` on the restored
   keystore with the password from that `signing.properties`; the SHA-256 must be the one above.
4. Never commit these files, paste the password into an issue or chat, or copy them to shared or rented machines.
   CI that needs to sign gets the `PBOARD_KEYSTORE*` variables from its secret store.

## Versioning

- `versionCode` = number of commits reachable from HEAD (`git rev-list --count HEAD`). main only moves forward, so
  every main build has a higher code than the previous one. A build from a branch based on an older main can have a
  LOWER code than what is installed; Android then refuses it as a downgrade (`adb install -r -d` is needed, which
  only works for debuggable APKs or on userdebug/eng system builds; releases never need it).
- `versionName` = from the last `vX.Y.Z` tag: `0.2.0` exactly on the tag, `0.2.0-5-gabc1234567` five commits later,
  `-dirty` with uncommitted changes; before the first tag the base is `pboard.versionBase` in `gradle.properties`
  (`0.1.0`), e.g. `0.1.0-371-g4f9000e2b1`. Without git: versionCode 1, `<base>-nogit`.
- Tags are created only by `tools/release.sh --tag` (annotated, local; push them if you publish a repo).
- The dev phone currently has versionCode 1, so any git-versioned build is an upgrade.

## Changelog

`tools/release/changelog.py` lists the merge commits on main's first-parent line (one
`Merge: <description>` per change), grouped under the release tags. `--since vX` gives one release's notes, `--all`
everything. `tools/release.sh` writes both into the release directory.

## Build checks

- Permission gate (`app/build.gradle.kts`, task `check<Variant>ManifestPermissions`, a dependency of every
  `package<Variant>` and of `check`): reads the merged manifest (including what libraries add) and FAILS the build if
  it requests INTERNET, ACCESS_NETWORK_STATE, any Wi-Fi/Bluetooth/NFC/UWB/location permission, push/ad-ID permissions,
  or any permission not on the allowlist. The allowlist is androidx core's signature-level
  `dev.pboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `RECORD_AUDIO` (opt-in on-device voice typing) and
  `READ_CONTACTS` (opt-in "Suggest contact names": display names, in memory only). Requested permissions are written to
  `app/build/reports/permissions/<variant>.txt`. `tools/release.sh` re-checks the final APK with `aapt2`.
- R8: release builds are minified and resource-shrunk (`proguard-android-optimize.txt`, no custom rules needed so far).
  The conformance quick gate and the benchmark harness always run the minified release build, so a stripped class
  shows up there.
- `tools/release.sh` verifies the signature (`apksigner verify --min-sdk-version 29`), refuses a release that ends up
  debug-signed, and records the signer, size and permissions in `release.json`.

## Data durability across updates

An update (same signer, higher versionCode) keeps all app data. Only an uninstall, "Clear storage", or a signer
change (which forces an uninstall) loses it. pboard writes nothing to cache or external storage, and backup / device
transfer are disabled for privacy, so the data below exists only on the phone.

| Data | Where | Format version | Rule / migration | Test |
|---|---|---|---|---|
| Learned data (learned words, touch offsets, removed suggestions, undone corrections, LM user n-grams) | `noBackupFilesDir/user_data.bin` | container `PBUD` v2 (`UserDataCodec.FORMAT_VERSION`), nested user model `PBUM` v1 (`UserNgramModel.FORMAT_VERSION`) | every older version must keep loading; v1 -> v2 moves legacy learned words into the LM user model. A file the build cannot read (corrupt, or from a NEWER version after a downgrade) is renamed `user_data.v<N>.unreadable` instead of being overwritten | `UserDataFormatTest` loads frozen v1/v2/user-model-v1 files in `engine/src/test/resources/userdata/` and requires one per version |
| Settings | device-protected `shared_prefs/pboard_settings.xml` | `prefs_schema_version` = 1 (`PrefsSchema.CURRENT`; stores without it are v0 = v1) | renaming/removing/retyping a key needs a schema bump + migration; adding keys does not. A store from a newer schema is left untouched | `PrefsSchemaTest`: frozen key/type list `app/src/test/resources/prefs/keys_v1.txt`, a pre-versioning store migrates with its values, newer store untouched |
| Personal dictionary | `noBackupFilesDir/personal_dictionary.tsv` | format 1 (`PersonalDictionary.FORMAT_VERSION`) | a format 2 must use a new file name and migrate from the v1 file (keeping it for downgrades). If the file exists but cannot be read, nothing is written over it | `PersonalDictionaryTest` loads a frozen v1 file |
| Emoji recents | `shared_prefs/emoji.xml`, key `recent` (U+0001-separated) | implicit v1 | cosmetic; a changed encoding must use a new key | - |
| Custom theme images | `filesDir/themes/<id>.jpg` (ids in the `custom_themes` setting) | JPEG | - | - |
| Clipboard history | memory only, never written | - | lost on every process restart, by design | - |

When changing a format: bump its version constant, add the migration (old -> new) to its reader, add the new
version's frozen sample (the `writeSamples` / `writeKeys` test helpers write the current version's sample only if it
is missing; never regenerate an old one), and keep every old sample passing.

## Getting updates to a phone without adb

pboard has no network code; an updater app on the phone (with its own network permission) fetches release APKs and
hands them to Android's package installer. Android itself guarantees the update is signed by the same key (or a
successor in the rotation lineage), so a tampered APK cannot replace pboard.

### Option A (prepared): self-hosted static repo + Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) (GPL-3.0, on F-Droid) checks a source for new APKs and installs
them. `tools/release.sh` adds each release to `dist/repo/`: `pboard-<versionCode:06d>-<versionName>.apk` (+ sha256,
notes), `pboard-latest.apk`, `index.html` (newest first) and `index.json`.

- Serve it on a private address only: `tools/release/serve_repo.sh` (python http.server on
  `<repo-bind-address>:8737`, from `PBOARD_REPO_BIND` in `local.env`). Run it under systemd/tmux if it should stay
  up, or copy `dist/repo/` to any static web host.
- On the phone: install Obtainium, "Add app", URL `http://<repo-bind-address>:8737/`, source "HTML". Use an APK link filter
  like `pboard-\d{6}-.*\.apk` and version extraction `pboard-\d{6}-(.+)\.apk` (group 1), so the
  zero-padded versionCode orders the links. Obtainium then shows and installs new releases.
- Caveats: the phone must be on that private network when Obtainium checks. If the Obtainium build refuses plain HTTP, serve
  via HTTPS (e.g. caddy with an internal CA trusted on the phone) or use option B.
- Not run or published by default: starting the server is a deliberate step.

### Option B: GitHub / Codeberg / Gitea releases + Obtainium

Requires an account and a public (or token-accessible) repository. Per release:
`git push origin vX.Y.Z` then e.g. `gh release create vX.Y.Z dist/X.Y.Z/pboard-X.Y.Z.apk
dist/X.Y.Z/pboard-X.Y.Z.apk.sha256 --notes-file dist/X.Y.Z/RELEASE_NOTES.md` (Codeberg/Gitea: `tea release
create` or the web UI). Obtainium's GitHub/Codeberg/Forgejo sources read these releases directly.

### Option C: an F-Droid repository

`fdroid init` + `fdroid update` (fdroidserver, in nixpkgs) over a `repo/` of the release APKs produces a signed
F-Droid index that the F-Droid / Droid-ify / Neo Store clients understand. It adds a second key (the repo index
signing key) to back up; only worth it if several people install pboard.

## Moving the dev phone to release builds

Today the phone runs debug-signed builds (versionCode 1). The rotation lineage lets it switch WITHOUT an uninstall:
release APKs from `tools/release.sh` carry a v3 signature by the release key plus the lineage `debug key -> release
key` (v3 signature only: a v2 block would have to be signed by the oldest key, and minSdk 29 devices all verify v3),
so Android 9+ accepts them as an ordinary update of the debug-signed install and keeps all data. In the lineage the
debug key keeps the "installed data" and "permission" capabilities, which the update needs (pboard declares androidx
core's signature permission; without "permission" the install fails with INSTALL_FAILED_DUPLICATE_PERMISSION), and
has no rollback, shared-UID or auth capability: once the phone has a release build, a debug-signed build can no longer
update it.

Verified 2026-11-08 on a farm pool emulator (Android 16, user build): a debuggable debug-key build with a planted
format-2 `user_data.bin` was updated by the same build re-signed with release key + lineage: `Success`, the files in
`no_backup/` and `files/` byte-identical afterwards, the keyboard suggested the learned word from the planted data,
`dumpsys package` showed the release signer with the debug cert as past signer (flags 5), and installing the
debug-key build again failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The R8 release path (debug-key release build ->
`tools/release.sh` APK) also updated with `Success` and typed normally.

Steps:
1. `tools/release.sh` (or `--tag v0.1.0`) on a clean main checkout on the machine that holds the release key.
2. `tools/phone/guard.sh --headless -- adb -s <phone-serial> install -r dist/<ver>/pboard-<ver>.apk`.
   Expect `Success`. If it fails, do NOT uninstall; report the error.
3. Check: `dumpsys package dev.pboard` shows the new versionCode and an unchanged `firstInstallTime`; typing still
   offers learned words.
4. From then on every build installed on the phone must be release-signed. Make that the default only on the
   machine that holds the key: add `pboard.release=true` to its `~/.gradle/gradle.properties`, so
   `./gradlew assembleRelease` there signs with the release key (AGP output has no lineage; that is fine once the
   phone is on the release key). Farm emulators keep debug-signed builds. Update your local device notes accordingly.

Alternative without the lineage: uninstall + install the release build (learned words, personal dictionary,
settings and themes are lost).
