"""The app matrix of tools/appcompat: which apps, which text fields in them, how to reach each field.

A field: {"id", "app" (package, for pid tracking), "kind" (text | multiline | url | email | password | number |
phone | terminal), "open" (setup ops that start the app and get to the screen; the runner's op format, see
bench/.../AppCompat.kt), "sel" (selector of the field to tap), "clear" (whether ACTION_SET_TEXT works to empty it),
"probe" (selector of a status node to record, e.g. the web page's composition line), "skip" (scenario names that
make no sense there), "resume" (how to return to it after switching apps)}.
"""

WEB_PKG = "dev.pboard.webfield"
# Chrome opens the test page over http from the host (adb reverse; run.py serves tools/appcompat/webfield/assets): it
# does not render the webfield provider's content:// page.
PAGE_PORT = 8765
PAGE = "http://localhost:%d/fields.html" % PAGE_PORT
STATUS = {"textStartsWith": "STATUS:"}

# First-run screens of the apps (clicked through whenever shown; each click is optional).
DISMISS = ["Accept & continue", "No thanks", "No Thanks", "Not now", "Got it", "Got It", "Skip", "SKIP", "OK",
           "Continue", "Use without an account", "Allow", "Dismiss", "Close", "Later", "Maybe later", "Start browsing",
           "Not interested", "Done", "I agree", "Agree", "Cancel"]


def sh(cmd):
    """A device command (run without a shell by the runner: no ; && or pipes)."""
    return {"op": "shell", "cmd": cmd}


def sleep(ms):
    return {"op": "sleep", "ms": ms}


def dismiss(*extra, **kw):
    d = {"op": "dismiss", "texts": list(extra) or DISMISS}
    d.update(kw)
    return d


def webview(kind, field_kind, skip=()):
    return {"id": "webview." + kind, "app": WEB_PKG, "kind": field_kind, "family": "System WebView (test page)",
            "open": [sh("am start -S -W -n %s/.WebFieldActivity --es field %s" % (WEB_PKG, kind)), sleep(1500)],
            "sel": {"pkg": WEB_PKG, "editable": True}, "clear": True, "probe": STATUS, "skip": list(skip)}


def chrome_page(kind, field_kind, skip=()):
    return {"id": "chrome.page." + kind, "app": "com.android.chrome", "kind": field_kind, "family": "Chrome (test page)",
            "open": [sh("am start -W -a android.intent.action.VIEW -d %s#%s -n com.android.chrome/com.google.android.apps.chrome.Main"
                        % (PAGE, kind)), sleep(2500), dismiss(max=4)],
            # the page's own field (its HTML id "f"), not the address bar, which is the first editable node
            "sel": {"pkg": "com.android.chrome", "res": "f"},
            "clear": True, "probe": STATUS, "skip": list(skip)}


FIELDS = [
    # -- System WebView: the test page in our own tiny app (tools/appcompat/webfield)
    webview("text", "text"),
    webview("textarea", "multiline"),
    webview("editable", "multiline"),
    webview("password", "password"),
    webview("number", "number"),
    webview("upper", "text"),
    webview("chat", "text"),
    # -- Chrome: the address bar (inline autocomplete) and the same test page
    {"id": "chrome.url", "app": "com.android.chrome", "kind": "url", "family": "Chrome",
     "open": [sh("am start -W -a android.intent.action.VIEW -d about:blank -n com.android.chrome/com.google.android.apps.chrome.Main"),
              sleep(2500), dismiss(max=4)],
     "sel": {"res": "com.android.chrome:id/url_bar"}, "clear": True, "skip": ["swipe", "autocorrect"]},
    # the address bar with history to inline-autocomplete from (example.com visited first)
    {"id": "chrome.url.history", "app": "com.android.chrome", "kind": "urlhistory", "family": "Chrome",
     "open": [sh("am start -W -a android.intent.action.VIEW -d https://example.com/ -n com.android.chrome/com.google.android.apps.chrome.Main"),
              sleep(4000), dismiss(max=4)],
     "sel": {"res": "com.android.chrome:id/url_bar"}, "clear": True},
    chrome_page("text", "text"),
    chrome_page("textarea", "multiline"),
    chrome_page("editable", "multiline"),
    chrome_page("password", "password"),
    chrome_page("number", "number"),
    # -- Google apps on the image
    {"id": "messages.compose", "app": "com.google.android.apps.messaging", "kind": "multiline", "family": "Google Messages",
     "open": [sh("am start -S -W -a android.intent.action.SENDTO -d sms:5551234"), sleep(2500), dismiss(max=4)],
     "sel": {"pkg": "com.google.android.apps.messaging", "editable": True}, "clear": True},
    {"id": "contacts.name", "app": "com.google.android.contacts", "kind": "text", "family": "Google Contacts",
     "open": [sh("am start -S -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact"), sleep(2500),
              dismiss("Skip", "Not now", "Got it", "OK", max=3)],  # (not "Cancel": the editor's own close button)
     "sel": {"pkg": "com.google.android.contacts", "editable": True}, "clear": True},
    {"id": "contacts.phone", "app": "com.google.android.contacts", "kind": "phone", "family": "Google Contacts",
     "open": [sh("am start -S -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact"), sleep(2500),
              dismiss("Skip", "Not now", "Got it", "OK", max=3)],
     # first name, last name, company, phone
     "sel": {"pkg": "com.google.android.contacts", "editable": True, "index": 3}, "clear": True},
    {"id": "settings.search", "app": "com.google.android.settings.intelligence", "kind": "text", "family": "Settings search",
     "open": [sh("am start -S -W -a com.android.settings.action.SETTINGS_SEARCH"), sleep(2000)],
     "sel": {"editable": True}, "clear": True},
    # -- FOSS apps (tools/appcompat/fetch_apks.py)
    {"id": "firefox.url", "app": "org.mozilla.fennec_fdroid", "kind": "url", "family": "Firefox (GeckoView)",
     "open": [sh("am start -W -n org.mozilla.fennec_fdroid/org.mozilla.fenix.HomeActivity"), sleep(3000), dismiss(max=6)],
     "sel": {"pkg": "org.mozilla.fennec_fdroid", "editable": True}, "clear": True, "skip": ["swipe", "autocorrect"]},
    {"id": "firefox.page.textarea", "app": "org.mozilla.fennec_fdroid", "kind": "multiline", "family": "Firefox (GeckoView)",
     "open": [sh("am start -W -a android.intent.action.VIEW -d '%s#textarea' -n org.mozilla.fennec_fdroid/org.mozilla.fenix.IntentReceiverActivity"
                 " --grant-read-uri-permission" % PAGE), sleep(3500), dismiss(max=6)],
     "sel": {"pkg": "org.mozilla.fennec_fdroid", "editable": True}, "clear": True, "probe": STATUS},
    # Zulip 30.x is the Flutter app (com.zulip.flutter) under the old package name
    {"id": "zulip.server", "app": "com.zulipmobile", "kind": "url", "family": "Zulip (Flutter)",
     "open": [sh("am start -S -W -n com.zulipmobile/com.zulip.flutter.MainActivity"), sleep(4000),
              {"op": "click", "sel": {"desc": "Add an account"}, "after": 1500}],
     "sel": {"pkg": "com.zulipmobile", "editable": True}, "clear": True, "skip": ["swipe", "autocorrect"]},
    # Paperless Native: React Native TextInputs on its login screen (server URL, username, password)
    {"id": "paperless.username", "app": "com.paperlessnative.mobile", "kind": "text", "family": "Paperless Native (React Native)",
     "open": [sh("am force-stop com.paperlessnative.mobile"), sh("monkey -p com.paperlessnative.mobile -c android.intent.category.LAUNCHER 1"), sleep(4000)],
     "sel": {"pkg": "com.paperlessnative.mobile", "editable": True, "index": 1}, "clear": True},
    {"id": "paperless.password", "app": "com.paperlessnative.mobile", "kind": "password", "family": "Paperless Native (React Native)",
     "open": [sh("am force-stop com.paperlessnative.mobile"), sh("monkey -p com.paperlessnative.mobile -c android.intent.category.LAUNCHER 1"), sleep(4000)],
     "sel": {"pkg": "com.paperlessnative.mobile", "editable": True, "index": 2}, "clear": True},
    {"id": "saber.title", "app": "com.adilhanney.saber", "kind": "text", "family": "Saber (Flutter)",
     "open": [sh("am start -S -W -n com.adilhanney.saber/.MainActivity"), sleep(5000), dismiss(max=3)],
     "sel": {"pkg": "com.adilhanney.saber", "editable": True}, "clear": True},
    {"id": "termux.shell", "app": "com.termux", "kind": "terminal", "family": "Termux",
     # a fresh session per scenario (force-stop ends the old one), so both keyboards see the same screen
     "open": [sh("am force-stop com.termux"), sh("am start -W -n com.termux/.app.TermuxActivity"), sleep(3500)],
     "sel": {"res": "com.termux:id/terminal_view"}, "clear": False},
    {"id": "k9.email", "app": "com.fsck.k9", "kind": "email", "family": "K-9 Mail",
     "open": [sh("am start -S -W -n com.fsck.k9/.activity.MainActivity"), sleep(3000),
              dismiss("Get started", "GET STARTED", "Next", max=2)],
     "sel": {"pkg": "com.fsck.k9", "editable": True}, "clear": True, "skip": ["swipe", "autocorrect"]},
    {"id": "notes.body", "app": "org.fossify.notes", "kind": "multiline", "family": "Fossify Notes",
     "open": [sh("am start -S -W -n org.fossify.notes/.activities.MainActivity"), sleep(2500), dismiss(max=3)],
     "sel": {"pkg": "org.fossify.notes", "editable": True}, "clear": True},
]

BY_ID = {f["id"]: f for f in FIELDS}
