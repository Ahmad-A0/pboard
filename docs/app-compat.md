# pboard in real apps: compatibility matrix, stability and idle behaviour

2026-11-08. Harness: [tools/appcompat](../tools/appcompat/README.md) and [tools/soak](../tools/soak/README.md).

## How it was measured

- Every scenario is played identically through **Gboard 18.6** and **pboard** on read-only instances of the golden
  `gb18-1` image (Android 16, Pixel 8 profile, 420 dpi, Gboard 18.6.3 fully provisioned, Chrome and System WebView
  of the image) leased from the farm pool. The two keyboards share the key geometry there, so the same touches hit
  the same keys. pboard is `pm clear`ed before each field; Gboard keeps its state through a run.
- After every step the focused field's text and selection are read through accessibility; on the test page also
  the page's own record of compositions, input and key events. `compare.py` classifies the first step where pboard's
  field differs from Gboard's: crash, no keyboard, lost input, garbled, stuck composition, cursor desync,
  capitalisation only, word choice (one word decoded differently: decoder/LM, not compatibility).
- Every scenario ends by hiding the keyboard, so a composition left open or text changed on hiding shows up.
- **Before** = pboard main at the start of this work (IME code of 0384600). **After** = with the fixes below.
- Caveat: the emulator farm ran at load 80-130 on 48 cores during the runs (other benchmarks), above the level where
  Gboard's decoder results are trusted for benchmarks. That affects *which word* Gboard picks (word-choice), not the
  editing mechanics compared here.

## Apps and fields

| app | engine / text stack | fields |
|---|---|---|
| Test page in the System WebView (`webfield`, our tiny app) | Chromium WebView | `<input>`, `<textarea>`, contenteditable, password, number, a controlled field that upper-cases its value on every input event (as formatting React apps do), a chat field whose Enter clears it |
| Chrome (image) | Chromium | address bar; address bar with history (inline autocomplete) |
| Google Messages (image) | EditText (multi-line) | compose box |
| Google Contacts (image) | Compose text fields | new contact: name (see the coverage note) |
| Paperless Native 1.3.5 (F-Droid) | React Native `TextInput` | login screen: username, password |
| Zulip 30.0.275 (F-Droid) | Flutter `TextField` (Zulip's current app ships under the old React Native package name) | server URL field |
| Termux 0.119.0-beta.3 (F-Droid) | terminal view (raw key input) | shell |

F-Droid APKs were fetched by `tools/appcompat/fetch_apks.py`, which verifies the repository index against F-Droid's
pinned signing key, then each APK's SHA-256 and signer against the signed index. The first, wider run also covered
Settings search, Firefox (GeckoView), K-9 Mail and Fossify Notes; the matrix was narrowed to the apps above (speed
mode).

Core scenarios: typing with autocorrect, backspace through a correction, mid-word edit, paste, app switch (home and
back) mid-word; plus rotation mid-word and the app replacing the text mid-word (the two cases that broke), select and
replace, emoji, pre-filled text, the app clearing the field, Enter/send, hide and reopen the keyboard on the wider
web-field run; number pad digits in number fields; Chrome's inline autocompletion; a shell session in Termux.

## Failures found

| # | class | where | what | status |
|---|---|---|---|---|
| 1 | **lost input** | apps that keep their window through a rotation: WebView input/textarea/contenteditable/chat, Zulip (Flutter); apps that recreate their activity (Messages, Paperless) were not affected | Rotating the phone mid-word, then typing on: "hello wor" + rotate + "ld " gave **"Hello ld "**. A configuration change (rotation, dark mode, any settings change while typing) restarts the input view without finishing the composing word; pboard dropped its word but the editor kept the composing region, so the next letter replaced it. The restart also reused EditorInfo's stale initial selection (where the cursor was when the field was first focused). | **fixed** (InputController.startInput finishes a composing word and asks the editor where the cursor is now) |
| 2 | **garbled** (duplicated text) | WebView test page, controlled field that rewrites its value | The app replaced the text while a word was being composed, then rewrote it again (upper-casing): "Changed" + " text " gave **"CHANGEDCHANGED TEXT "**. The editor dropped the composing region, the selection stayed the same, so pboard took the update for its own and its next setComposingText inserted the word a second time. | **fixed** (InputController.onUpdateSelection: an editor that tracks composing and reports none while pboard composes means the region was dropped: finish and forget the word) |
| 3 | capitalisation (learning) | any field, after a few messages | After typing "Hello ..." at the start of a few messages, a mid-sentence "hello" is auto-corrected to **"Hello"** ("Because Hello world"). Fresh state: correct. The engine stores a sentence-start word in its capitalised form when the lexicon also knows that form (`PboardEngine.storedForm`). | **open** (engine, not IME code; reported) |
| 4 | capitalisation (web pages that format input) | WebView/Chrome field that upper-cases its value | pboard composes every word; such pages skip input events during composition, so the word being typed stays as typed until it is committed, and the last word if the keyboard is hidden ("HELLO wor"). Gboard 18.6 does not open compositions in web fields at all (0 compositionstart events on the test page while typing), so pages see plain inserts. | **fixed** (measured on 18.6: it never opens a composition, in WebView or native fields of any input type; pboard now edits its word with plain inserts and replacements, and the upper-casing page shows "HELLO WOR" with 0 compositions) |
| 5 | lost word (policy) | Chrome address bar | A glide in Chrome's address bar types only the key under the finger at the end ("hello" -> **"o"**): pboard turns gesture typing off in URI fields (`EditorState.gesture`, measured on Gboard 15.1); Gboard 18.6 glides there ("hello"). | **fixed** (18.6 glides in URL fields, conformance fuzz/1/6; pboard glides in every text field but passwords, fe76f23) |
| 6 | capitalisation | Google Contacts name field (Compose) | Gboard capitalises each word ("Hello Wor"), pboard nothing ("hello wor"). Probably pboard asks the editor (`getCursorCapsMode`) and this Compose field answers 0; low confidence (the harness reads this Compose screen unreliably). | **not reproduced** (first name is CAP_WORDS + person name, inputType 0x2060; pboard and Gboard both type "Hello Wor" on a pool instance) |

## Matrix

Cells: class of pboard main ("before"); "→" the fixed build ("after") where it differs; "→ -" not rerun after
(the fixes were rerun on the core set). ok = same text and cursor as Gboard 18.6 after every step;
CASE = letter case only; LOST = lost input; GARB = garbled. 113 scenario runs before, 88 after.

| app | field | app changes | app clears | appswitch | autocorrect | backspace correction | emoji | enter | hide reopen | midword | paste | prefilled | rotate | select replace | swipe | password | digits | url autocomplete | terminal |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| System WebView (test page) | webview.text | ok | ok | ok | ok | ok | ok | ok | CASE | ok | ok | ok | LOST → ok | ok | ok |  |  |  |  |
| System WebView (test page) | webview.textarea | ok | ok | ok | ok | ok | ok |  | CASE | ok | ok | ok | LOST → ok | ok | ok |  |  |  |  |
| System WebView (test page) | webview.editable | ok → - | ok → - | ok → - | ok → - | ok → - | ok → - |  | CASE → - | ok → - | ok → - | ok → - | LOST → - | ok → - | ok → - |  |  |  |  |
| System WebView (test page) | webview.password |  | ok → - | ok → - |  |  |  |  |  |  | ok → - | ok → - | ok → - |  |  | ok → - |  |  |  |
| System WebView (test page) | webview.number |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ok → - |  |  |
| System WebView (test page) | webview.upper | GARB → CASE | CASE | CASE | ok | CASE | CASE | CASE | CASE | CASE | CASE | CASE | CASE | CASE | CASE |  |  |  |  |
| System WebView (test page) | webview.chat | ok | ok | ok | ok | ok | ok | ok | CASE | ok | ok | ok | LOST → ok | ok | ok |  |  |  |  |
| Chrome | chrome.url | ok | ok → - | ok |  |  |  | ok → - | ok → - | ok | ok | ok → - | ok | ok → - |  |  |  |  |  |
| Chrome | chrome.url.history |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ok |  |
| Google Messages | messages.compose | ok |  | ok | ok | ok |  |  |  | ok | ok |  | ok |  |  |  |  |  |  |
| Zulip (Flutter) | zulip.server | ok |  | ok |  |  |  |  |  | ok | ok |  | LOST → ok |  |  |  |  |  |  |
| Paperless Native (React Native) | paperless.username | ok |  | ok | ok | ok |  |  |  | ok | ok |  | ok |  | CASE |  |  |  |  |
| Paperless Native (React Native) | paperless.password |  |  | ok |  |  |  |  |  |  | ok |  | ok |  |  | ok |  |  |  |
| Termux | termux.shell |  |  | ok |  |  |  |  |  |  |  |  | ok |  |  |  |  |  | ok |

| | lost-input | garbled | capitalization | pass |
|---|---|---|---|---|
| before | 5 | 1 | 17 | 90 |
| after | 0 | 0 | 17 | 71 |

All remaining CASE cells are findings 3 and 4 (learned capitalisation; composition vs. a page that formats its
value). Passwords (WebView, React Native), numbers (number pad), Chrome's inline autocompletion, Termux (raw key input:
typing, backspace, Enter, ls, app switch, rotation), Google Messages, the React Native and Flutter fields: no
difference from Gboard beyond those.

Not reached by the harness (no result either way): the test page inside Chrome itself (Chrome's keyboard accessory
bar; the same page in the System WebView is covered), Saber (no text field without drawing a note), Google Contacts
(its Compose editor is read unreliably through accessibility: only finding 6 is reported from it), number fields in
Contacts. Settings search, Firefox, K-9 and Fossify Notes were dropped from the matrix to save time.

Regression scenarios of the fixes: `*/rotate` (finding 1) and `webview.upper/app_changes` (finding 2), in
`tools/appcompat/scenarios.py`; rerun with `tools/appcompat/matrix.sh` (see the README there).

## Soak (1 hour, randomised input)

`tools/soak/soak.py` on one pool instance with the fixed build: 1.02 h, 6 rounds over the WebView test page
(input, textarea, contenteditable, chat), Google Messages, Contacts, Zulip (Flutter), Saber, Termux; **2432 observed
input steps** (typed words from held-out sentences, glides, backspace taps and holds, cursor moves, select-and-replace,
pastes, emoji panel, symbols page, Enter, home-and-back, rotations, the app replacing the text).

| | result |
|---|---|
| pboard crashes / ANRs | **0 / 0** (crash buffer and dropbox); pboard's process never restarted (one pid all run) |
| keyboard not coming up | 0 (15 scenario starts failed on app screens the harness did not reach: Saber, Contacts) |
| PSS | 224 MB at the start (first load) -> 122-139 MB after the first GC; trend after warm-up +7 MB/h (noise level) |
| Java / native heap | trend +2 MB/h / -1.4 MB/h |
| Views, ViewRootImpl, Activities | 33-35, 1, 0: flat |
| threads | 28 -> 37 in the first minutes (engine and worker threads), then flat |
| local / proxy binders | swing 22-354 / 46-70 with GC, trend flat |
| wake locks held by pboard | 0 of 63 samples |

Earlier, longer attempts ran the same harness with Chrome in the field set: three times the *emulator process itself*
died (host side, SwiftShader/Vulkan, the log stops while Chrome renders) within minutes of Chrome typing; pboard's
process was alive until then. Those runs were restarted without Chrome; the soak harness now restarts a dead
instance from its snapshot (`--restart-lost`) and counts such deaths separately. Chrome itself is covered by the
matrix.

## Crash log (on device, private)

pboard now keeps its own crash reports in app-private storage (`files/crashes/`, excluded from backup and device
transfer like all its data): uncaught exceptions on any thread (written by a default-handler hook installed in
`PboardApp.onCreate`, before the process dies; the previous handler then runs as usual) and, at the next start, ANRs
(with the thread dump the platform keeps), native crashes and kills for excessive resource use from the platform's exit
records of our own process (`ApplicationExitInfo`). Settings > About > **Crash reports** lists them, shows each
report, shares all of them through the share sheet (plain text; nothing leaves the device unless the user picks an
app) and deletes them. At most 20 reports of at most 64 KB are kept. Nothing typed goes in: exception messages are
kept for their shape only, quoted parts are replaced by their length (`For input string: <7 chars>`). No network
code, no new permission. Unit tests: `CrashReportsTest` (redaction, causes, format/parse, caps, retention, share
bound). Checked on a pool device: `am crash dev.pboard` produced a report with the stack trace; list, report text and
share sheet shown as expected.

## Idle and battery (dev phone, headless guard)

`tools/soak/idle_check.sh` on the dev phone through `guard.sh --headless` (dumpsys and /proc only), 15.2 min with
the keyboard hidden (pboard 0.1.0 main release as the default IME, process alive throughout):

- CPU time of pboard's process over the window: **0.02 s** (0.002 % of one core); threads 25 throughout; RSS
  221.4 -> 221.9 MB.
- Wake locks held by pboard in `dumpsys power`: **0 of 16 samples**, none at the end.
- Alarms naming pboard: 0; scheduled jobs: 0; running services: only the bound IME service
  (`dev.pboard/.ime.PboardIme`, bound by the system); netstats entries for pboard's uid: 0.
- `dumpsys batterystats` was reset by the system during the window, so its per-uid deltas say little here; its
  post-reset entries for pboard were all zero.

So pboard does no background work and holds no wake locks while idle. (While typing, the vibrator service holds a
short wake lock on pboard's behalf for each haptic tick, as for any keyboard with haptics.)
