# App-compatibility matrix: pboard in real apps' text fields

Drives pboard and Gboard 18.6 through the same scenarios in real apps on farm pool devices (read-only instances of
the golden `gb18-1` image) and compares the field after every step. Results: [docs/app-compat.md](../../docs/app-compat.md).

```
tools/appcompat/fetch_apks.py                          # FOSS apps from F-Droid (verified, see below) -> tools/appcompat/apks/
nix develop -c tools/appcompat/webfield/build.sh       # the test page app (System WebView + content provider for Chrome)
tools/appcompat/matrix.sh <serial> <out> [fields] [scenarios]   # both keyboards on one leased device, then compare
tools/appcompat/run.py --serial S --ime gboard|pboard --out DIR [--fields a,b] [--scenarios x,y]
tools/appcompat/compare.py <gboard-dir> <pboard-dir> --md out.md
tools/appcompat/report.py <gboard-dir> <before-dir> <after-dir> --md out.md   # before/after matrix (docs/app-compat.md)
tools/appcompat/run.py --serial x --list               # fields and the scenarios each one gets
```

Core set used for docs/app-compat.md: fields `chrome.url,chrome.url.history,chrome.page.text,
chrome.page.textarea,chrome.page.editable,zulip.server,saber.title,termux.shell,messages.compose,contacts.name,
contacts.phone` (+ the WebView test page), scenarios `autocorrect,backspace_correction,paste,midword,appswitch,rotate,
app_changes,url_autocomplete,terminal,digits`. Regression scenarios of the fixes: `*/rotate` (lost input after a
rotation mid-word) and `webview.upper/app_changes` (word doubled when the page drops the composition).

Devices come from the pool only (`tools/farm/pool.sh lease|hold`); never start emulators for this.

## Pieces

| file | what |
|---|---|
| `apps.py` | the field matrix: app, how to open it (intents, first-run screens clicked through), the field's selector, its kind (text, multiline, url, email, password, number, phone, terminal) |
| `scenarios.py` | scenarios by field kind; key positions from the conformance builder (`tools/conformance/scenarios.py`, the `gboard_pixel8` geometry both keyboards share at 420 dpi); a word is one real-time burst, editing keys one tap per step |
| `bench/.../AppCompat.kt` | on-device runner (`am instrument ... dev.pboard.bench/.AppCompat`): setup ops (shell, click/dismiss by accessibility, focus a field and wait for the IME), steps (touch strokes, accessibility selection / text / paste, key events, rotation, home + resume), and after each step the focused field's text and selection (accessibility, with `refresh()`), the IME window, the front app and the IME/app pids |
| `run.py` | prepares a device (settings, APKs, Chrome without first-run, IME selected; pboard `pm clear`ed) and runs one IME over the fields, one instrumentation per field; collects the crash buffer after each |
| `compare.py` | per scenario, the first step where pboard's field differs from Gboard's decides: pass / word-choice / cursor-desync / lost-input / garbled / stuck-composing / no-keyboard / crash / setup |
| `webfield/` | tiny app (no Gradle): `fields.html` with one field per load (`#text`, `#password`, `#number`, `#textarea`, `#editable` (contenteditable), `#upper` (a controlled field that rewrites its value), `#chat` (Enter clears it)) in the System WebView, and the same page as `content://dev.pboard.webfield/fields.html` for Chrome. A status line (`STATUS: comp=...`) reports open compositions (compositionstart/end), input and key events; the runner records it |

## Observation

After each step the runner waits 300 ms, then reads the focused field every 100 ms until text, selection and the
page status are unchanged for 3 samples over >= 450 ms (5 s at most). Password fields expose their text because
`speak_password` is set on the device. Composition state is only observable on the test page (status line);
in native apps a stuck composition shows up as a text difference in the steps after it (the scenarios edit
after every app-side change).

## FOSS apps and how they are verified

`fetch_apks.py` trusts nothing but F-Droid's published repository key: `entry.jar` must verify (`jarsigner`) and be
signed by the pinned certificate `43238d51...a9c9ccab`; `index-v2.json` must match the SHA-256 in the signed entry;
each APK must match the index's SHA-256 and be signed by a certificate the index lists for it (`apksigner`). The chosen
versions, hashes, signers and source tarballs go to `apks/manifest.json`. The APKs are not committed.

| app | why |
|---|---|
| Fennec F-Droid (Firefox) | GeckoView: its own IME bridge |
| Zulip (legacy) | React Native `TextInput` |
| Saber | Flutter `TextField` |
| Termux | terminal view (raw key input) |
| K-9 Mail | account setup: e-mail and password fields |
| Fossify Notes | plain multi-line `EditText` (successor of Simple Notes) |

System apps from the image: Chrome (address bar, test page), System WebView (test page), Google Messages, Google
Contacts, Settings search.
