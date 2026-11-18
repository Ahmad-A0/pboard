#!/usr/bin/env python3
"""Reference-screenshot capture for keyboard look-and-feel diffing.

Drives an emulator with a given IME through a fixed list of keyboard states and writes one
PNG per state plus manifest.tsv (state, file, keyboard_top_px) so the same states can be
captured for Gboard and pboard and compared pixel-for-pixel.

Usage (normally via capture.sh):
  capture.py --serial emulator-5554 --ime <pkg/.Service> --out docs/ref/pboard [--only REGEX] [--full]

Touches are injected through the emulator console ("event mouse"), which gives precise
press/hold timing (adb `input` costs ~150-300 ms per call). Requires the FieldZoo helper app
(tools/ref/fieldzoo) to be installed; capture.sh builds/installs it.

Key coordinates are derived from the measured Gboard geometry in dp,
anchored at the IME's touchable-region top, so a keyboard that matches the spec gets hit
exactly where Gboard does.
"""
import argparse, hashlib, os, re, socket, subprocess, sys, threading, time

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--ime', required=True)
p.add_argument('--out', required=True)
p.add_argument('--only', default=None, help='regex: only capture states whose name matches')
p.add_argument('--full', action='store_true', help='also long-press every letter (26 extra shots)')
p.add_argument('--themes', default='light,dark')
p.add_argument('--profile', default='gboard15', choices=['gboard15', 'gboard18'],
               help='key geometry used to aim touches')
p.add_argument('--touch', default=None, choices=['console', 'adb'],
               help='touch injection: emulator console (exact timing) or adb input (any device); default by serial')
p.add_argument('--guard', default=None,
               help='guard script; `GUARD --check` must pass before every injection/screenshot (dev phone)')
p.add_argument('--restore-ime', default=None, help='IME to select again when done (e.g. dev.pboard/.ime.PboardIme)')
args = p.parse_args()

ADB = [os.environ.get('ADB', 'adb'), '-s', args.serial]
OUT = args.out
os.makedirs(OUT, exist_ok=True)
manifest = []


def sh(*a):
    return subprocess.run(ADB + ['shell', *a], capture_output=True, text=True).stdout


def grab():
    return subprocess.run(ADB + ['exec-out', 'screencap'], capture_output=True).stdout


def settle(maxt=8.0):
    """Wait until two consecutive grabs of the lower 45% of the screen (the keyboard) are identical.

    Only the bottom part is compared so the blinking text cursor does not keep us waiting; the hash
    is computed on the device so nothing large crosses a slow adb link."""
    n = int(W * H * 4 * 0.45)
    t, last = time.time(), None
    while time.time() - t < maxt:
        h = sh(f'screencap | tail -c {n} | md5sum').split()[0:1]
        if h and h == last:
            return True
        last = h
        time.sleep(0.25)
    return False


# ---------------------------------------------------------------- touch injection
class Console:
    """Emulator console (telnet) touch injection with accurate timing."""

    def __init__(self, port):
        self.s = socket.create_connection(('127.0.0.1', port))
        self._read()
        tok = open(os.path.expanduser('~/.emulator_console_auth_token')).read().strip()
        self.cmd('auth ' + tok)

    def _read(self):
        out = b''
        while not (out.endswith(b'OK\r\n') or b'KO:' in out):
            out += self.s.recv(4096)
        return out

    def cmd(self, c):
        self.s.sendall((c + '\r\n').encode())
        return self._read()

    def down(self, x, y): self.cmd(f'event mouse {int(x)} {int(y)} 0 1')
    move = down
    def up(self, x, y): self.cmd(f'event mouse {int(x)} {int(y)} 0 0')


class AdbTouch:
    """Touch injection through `adb shell input` (works on real devices; ~0.1-0.3 s per call)."""
    def down(self, x, y): check(); sh('input', 'motionevent', 'DOWN', str(int(x)), str(int(y)))
    def move(self, x, y): sh('input', 'motionevent', 'MOVE', str(int(x)), str(int(y)))
    def up(self, x, y): sh('input', 'motionevent', 'UP', str(int(x)), str(int(y)))


class GuardFailed(Exception):
    pass


def check():
    """With --guard: refuse to continue unless one of pboard's own test screens is in front."""
    if args.guard and subprocess.run([args.guard, '--check']).returncode != 0:
        raise GuardFailed('guard check failed - foreground is not a pboard test screen')


m = re.match(r'emulator-(\d+)$', args.serial)
touch = args.touch or ('console' if m else 'adb')
if touch == 'console':
    if not m:
        sys.exit('console touch injection needs an emulator serial (emulator-NNNN)')
    T = Console(int(m.group(1)))
else:
    T = AdbTouch()


def tap(x, y, hold=0.05):
    if touch == 'adb':
        check(); sh('input', 'tap', str(int(x)), str(int(y))); return
    T.down(x, y); time.sleep(hold); T.up(x, y)


# ---------------------------------------------------------------- geometry
W, H = (int(v) for v in re.search(r'(\d+)x(\d+)', sh('wm', 'size')).groups())
_d = sh('wm', 'density')
DENS = int((re.search(r'Override density: (\d+)', _d) or re.search(r'Physical density: (\d+)', _d)).group(1))
DP = DENS / 160.0


def kb_top():
    """Top of the IME's touchable region in screen px (= top edge of the keyboard incl. toolbar)."""
    d = sh('dumpsys', 'window', 'windows')
    i = d.find(' InputMethod}:')
    if i < 0:
        return None
    seg = d[i:i + 4000]
    r = re.search(r'touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)', seg)
    return int(r.group(2)) if r else None


# Geometry profiles in dp, anchored at the screen bottom (the IME's touchable top is not the visual top on 18.x).
PROFILES = {
    #            nav band, gap under bottom row, row pitch, key height, toolbar items
    'gboard15': dict(nav=48, gap=12.6, pitch=58.9, keyh=46.0, tools=7),
    'gboard18': dict(nav=48, gap=10.2, pitch=50.5, keyh=40.7, tools=5),
}


def keymap(top=None):
    """Key centres for the chosen geometry profile (portrait phone, number row off)."""
    g = PROFILES[args.profile]
    pad = 2 * DP
    cell = (W - 2 * pad) / 10
    row4_bottom = H - (g['nav'] + g['gap']) * DP
    def row_y(r): return row4_bottom - (3 - r) * g['pitch'] * DP - g['keyh'] * DP / 2
    k = {}
    for i, c in enumerate('qwertyuiop'):
        k[c] = (pad + cell * (i + .5), row_y(0))
    for i, c in enumerate('asdfghjkl'):
        k[c] = (pad + cell * (i + 1.0), row_y(1))
    for i, c in enumerate('zxcvbnm'):
        k[c] = (pad + cell * (i + 2.0), row_y(2))
    k['shift'] = (pad + cell * 0.75, row_y(2))
    k['del'] = (W - pad - cell * 0.75, row_y(2))
    y4 = row_y(3)
    k.update({'sym': (pad + cell * .75, y4), 'comma': (pad + cell * 2, y4), 'emoji': (pad + cell * 3, y4),
              'space': (W / 2 + cell * .5, y4), 'period': (pad + cell * 8, y4), 'enter': (W - pad - cell * .75, y4)})
    row1_top = row_y(0) - g['keyh'] * DP / 2
    k['visual_top'] = row1_top - (44 + 8) * DP          # 44 dp header + 8 dp body padding
    n = g['tools']
    k['toolbar'] = [(k['q'][0] + i * (k['p'][0] - k['q'][0]) / (n - 1), k['visual_top'] + 22 * DP) for i in range(n)]
    return k


# ---------------------------------------------------------------- state helpers
def want(name):
    return args.only is None or re.search(args.only, name)


def shot(name):
    path = os.path.join(OUT, name + '.png')
    if args.guard:
        # The guard checks the foreground before and after the capture and discards the image on a mismatch.
        if subprocess.run([args.guard, '--shot', path]).returncode != 0:
            raise GuardFailed('guard screenshot refused - foreground is not a pboard test screen')
    else:
        with open(path, 'wb') as f:
            subprocess.run(ADB + ['exec-out', 'screencap', '-p'], stdout=f)
    top = int(keymap()['visual_top']) if args.profile == 'gboard18' else kb_top()
    manifest.append((name, os.path.basename(path), top))
    print(f'{name:40s} kb_top={top}', flush=True)
    # Another tool driving the same emulator (a latency run, a second capture) silently ruins a set: flag it.
    # Checked after the screenshot so held-key states keep their timing (a slow shot turned a held shift into
    # caps lock).
    if not name.endswith(('_preview', 'press_shift', 'press_sym', 'press_space')) and 'longpress' not in name:
        ime_now = sh('settings', 'get', 'secure', 'default_input_method').strip()
        norm = lambda x: x.replace('/.', '/' + x.split('/')[0] + '.')
        if norm(ime_now) != norm(args.ime):
            print(f'WARNING {name}: current IME is {ime_now}, expected {args.ime} (is something else using the emulator?)',
                  flush=True)
        top_act = re.search(r'topResumedActivity=\S+ \S+ (\S+)', sh('dumpsys', 'activity', 'activities'))
        if top_act and 'fieldzoo' not in top_act.group(1):
            print(f'WARNING {name}: foreground is {top_act.group(1)}, not FieldZoo', flush=True)


def shown():
    return 'mInputShown=true' in sh('dumpsys', 'input_method')


def field(kind, wait=1.5):
    sh('am', 'start', '-S', '-n', 'dev.pboard.fieldzoo/.FieldActivity', '--es', 'field', kind)
    time.sleep(wait)
    for _ in range(10):
        if shown():
            break
        check()
        sh('input', 'tap', str(W // 2), str(int(150 * DP)))
        time.sleep(0.8)
    time.sleep(0.5)
    settle()
    return keymap(kb_top() or int(H * 0.63))


def clear():
    check()
    sh('input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_A')
    sh('input', 'keyevent', 'KEYCODE_DEL')


def hold_shot(xy, wait, name):
    """Press and hold, screenshot while held, then slide off the keyboard and release (cancel)."""
    if not want(name):
        return
    T.down(*xy); time.sleep(wait); shot(name)
    T.move(W / 2, H * 0.2); time.sleep(0.05); T.up(W / 2, H * 0.2); time.sleep(0.4)


def ftap(xy):
    """Finger tap via `adb input` (slow, ~0.2 s per call, but reported as a finger).

    Console "event mouse" touches arrive from a touchscreen that also advertises STYLUS; Gboard
    then skips its typing decoder (no candidates, no gesture typing). Use finger events for any
    state that depends on the language model."""
    check()
    sh('input', 'tap', str(int(xy[0])), str(int(xy[1])))


def typ(K, s, gap=0.05):
    for ch in s:
        ftap(K[{' ': 'space', ',': 'comma', '.': 'period'}.get(ch, ch.lower())]); time.sleep(gap)


def swipe(K, word, mid_shot=None):
    """Gesture-type `word` with finger events (adb input motionevent, ~0.2 s per point).

    With mid_shot, a screenshot is taken before lifting the finger so the trail is visible; the
    long pause usually makes Gboard drop the gesture, so use a second swipe for the committed state."""
    pts = []
    for c in word:  # drop repeated letters: a ~0.4 s dwell on one spot makes Gboard abandon the gesture
        if not pts or pts[-1] != K[c]:
            pts.append(K[c])
    check()
    sh('input', 'motionevent', 'DOWN', str(int(pts[0][0])), str(int(pts[0][1])))
    for a, b in zip(pts, pts[1:]):
        mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        for q in (mid, b):
            sh('input', 'motionevent', 'MOVE', str(int(q[0])), str(int(q[1])))
    if mid_shot:
        shot(mid_shot)
    sh('input', 'motionevent', 'UP', str(int(pts[-1][0])), str(int(pts[-1][1])))
    time.sleep(0.8)


# ---------------------------------------------------------------- the state list
orig_night = 'yes' if 'yes' in sh('cmd', 'uimode', 'night') else 'no'
sh('ime', 'enable', args.ime)
sh('ime', 'set', args.ime)
if touch == 'console':
    sh('settings', 'put', 'secure', 'stylus_handwriting_enabled', '0')  # console touches come from a stylus-capable device

try:
    for theme in args.themes.split(','):
        sh('cmd', 'uimode', 'night', 'yes' if theme == 'dark' else 'no')
        time.sleep(2)
        P = theme + '_'

        K = field('text')
        time.sleep(1.0); settle()  # auto-capitalisation can arrive a moment after the keyboard is shown
        if want(P + 'text_autocap'): shot(P + 'text_autocap')
        tap(*K['shift']); time.sleep(0.5); settle()
        if want(P + 'qwerty_lower'): shot(P + 'qwerty_lower')
        tap(*K['shift']); time.sleep(0.12); tap(*K['shift']); time.sleep(0.5); settle()
        if want(P + 'qwerty_capslock'): shot(P + 'qwerty_capslock')
        tap(*K['shift']); time.sleep(0.5)

        K = field('nosuggest')
        tap(*K['sym']); time.sleep(0.3); settle()
        if want(P + 'symbols1'): shot(P + 'symbols1')
        tap(*K['shift']); time.sleep(0.3); settle()
        if want(P + 'symbols2'): shot(P + 'symbols2')
        K = field('nosuggest')  # fresh field = letters page, unshifted (more reliable than tapping ABC)

        # press states (screenshot while held; letter keys show the preview bubble). Each from a fresh field: shift
        # and ?123 act on touch-down, so a cancelled press can leave the next state shifted or on another page.
        for k, name in (('g', 'press_g_preview'), ('shift', 'press_shift'), ('sym', 'press_sym'), ('space', 'press_space')):
            if want(P + name):
                K = field('nosuggest')
                hold_shot(K[k], 0.12, P + name)
        # long-press popups. Each one starts from a fresh field (letters page, unshifted): ?123 acts on touch-down
        # and a cancelled popup can leave the keyboard on another page, which made later states incomparable.
        for k in (['e', 'a', 'o', 'u', 'n', 'c', 's', 'y', 'z', 'q', 'p'] if not args.full else list('qwertyuiopasdfghjklzxcvbnm')) + \
                ['comma', 'period', 'enter', 'emoji', 'space']:
            if want(P + 'longpress_' + k):
                K = field('nosuggest')
                hold_shot(K[k], 1.2, P + 'longpress_' + k)
        clear()

        # suggestions / autocorrect / predictions
        K = field('text')
        typ(K, 'hel'); time.sleep(1.0); settle()
        if want(P + 'suggest_hel'): shot(P + 'suggest_hel')
        clear(); time.sleep(0.3)
        # "becuase" is a word Gboard 15.1 reliably autocorrects ("teh" and "helo" are not).
        typ(K, 'thr becuase'); time.sleep(1.0); settle()
        if want(P + 'suggest_becuase'): shot(P + 'suggest_becuase')
        typ(K, ' '); time.sleep(1.0); settle()
        if want(P + 'autocorrected_next_word'): shot(P + 'autocorrected_next_word')
        ftap(K['del']); time.sleep(0.8); settle()
        if want(P + 'autocorrect_reverted'): shot(P + 'autocorrect_reverted')
        clear(); time.sleep(0.5); settle()

        # gesture typing
        if want(P + 'swipe_mid_hello'):
            K = field('text')
            swipe(K, 'hello', mid_shot=P + 'swipe_mid_hello')
            clear(); time.sleep(0.5)
        if want(P + 'swipe_committed'):
            K = field('text')  # fresh field: a gesture right after the cancelled one above is often dropped
            swipe(K, 'hello'); time.sleep(1.0); settle()
            shot(P + 'swipe_committed')
        clear()

        # panels
        K = field('text')
        tap(*K['emoji']); time.sleep(1.0); settle()
        if want(P + 'emoji_panel'): shot(P + 'emoji_panel')
        sh('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(0.5)
        K = field('text')
        # clipboard item on the bar: Gboard's 4th slot (sticker, GIF, clipboard...); pboard's default bar starts with it
        tap(*K['toolbar'][1 if args.ime.startswith('dev.pboard/') else 3]); time.sleep(1.0); settle()
        if want(P + 'clipboard_panel'): shot(P + 'clipboard_panel')
        sh('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(0.5)

        # one shot per EditorInfo variant
        for kind in ['multiline', 'search', 'send', 'go', 'next', 'done', 'previous', 'email', 'url', 'number',
                     'decimal', 'signed', 'phone', 'datetime', 'date', 'time', 'password', 'numberpassword', 'name']:
            if want(P + 'field_' + kind):
                field(kind)
                shot(P + 'field_' + kind)
except GuardFailed as e:
    print('ABORTED:', e, flush=True)
finally:
    sh('cmd', 'uimode', 'night', orig_night)
    if args.restore_ime:
        sh('ime', 'set', args.restore_ime)

# merge into manifest.tsv (a partial --only run updates just its own rows)
mpath = os.path.join(OUT, 'manifest.tsv')
rows = {}
if os.path.exists(mpath):
    for line in open(mpath):
        parts = line.rstrip('\n').split('\t')
        if len(parts) == 3:
            rows[parts[0]] = parts
for row in manifest:
    rows[row[0]] = [str(c) for c in row]
with open(mpath, 'w') as f:
    for name in sorted(rows):
        f.write('\t'.join(rows[name]) + '\n')
print('wrote', len(manifest), 'shots to', OUT)
