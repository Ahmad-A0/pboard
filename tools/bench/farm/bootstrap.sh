#!/usr/bin/env bash
# Turn a fresh Linux host into a benchmark emulator farm, driven from here over ssh.
#
#   tools/bench/farm/bootstrap.sh <ssh-host> [--count N] [--golden /var/tmp/pboard-avd/gb18-1.avd]
#                                 [--repo-dir pboard-bench] [--avd-dir /var/tmp/pboard-avd] [--no-start]
#
# Design: the harness runs ON the farm (injection timing and snapshot loads stay local to the
# emulators; no adb traffic crosses the network) and results are pulled back (farm/run_remote.sh).
# Steps:
#   1. check_host.sh on the host: fail fast unless /dev/kvm is usable (nested virtualisation on);
#      report capacity -> number of emulators (2 vCPU / ~3 GB / ~7 GB disk each, at most 20).
#   2. Nix with flakes on the host (single-user install if missing; needs sudo once for /nix).
#   3. rsync this checkout (sources, flake.lock, human sets; no build outputs, no out/) to
#      ~/<repo-dir>, plus the prebuilt APKs (playground/IME release builds under
#      tools/bench/farm/apks/, :bench debug APK) so the farm needs no Gradle build.
#   4. rsync the golden AVD directory (stopped!) and write its .ini; `nix develop` on the host provides
#      the same pinned SDK + system image, so the AVD resolves identically.
#   5. make_bench_avds.sh --golden on the host: byte-identical clones bench-1..N started headless
#      from the snapshot on ports 5570, 5572, ...; device.sh check on each.
# Prints the device list for run.sh at the end (also saved on the host in ~/<repo-dir>/farm_devices).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HOST="${1:?ssh host}"; shift
COUNT=""; GOLDEN=/var/tmp/pboard-avd/gb18-1.avd; REPO=pboard-bench; AVD_DIR=/var/tmp/pboard-avd; START=1
while [ $# -gt 0 ]; do
  case "$1" in
    --count) COUNT="$2"; shift 2;; --golden) GOLDEN="$2"; shift 2;; --repo-dir) REPO="$2"; shift 2;;
    --avd-dir) AVD_DIR="$2"; shift 2;; --no-start) START=0; shift;;
    *) sed -n '2,24p' "$0"; exit 2;;
  esac
done
GNAME="$(basename "$GOLDEN" .avd)"
[ -d "$GOLDEN/snapshots/gboard_bench_base" ] || { echo "!! $GOLDEN has no gboard_bench_base snapshot"; exit 2; }
if pgrep -f -- "-avd $GNAME( |$)" >/dev/null; then echo "!! $GNAME is running here; stop it before copying"; exit 2; fi
SSH=(ssh -o BatchMode=yes "$HOST")

echo "== 1. host check"
CAP="$("${SSH[@]}" "bash -s -- $AVD_DIR" < "$ROOT/tools/bench/farm/check_host.sh")" || exit 1
echo "$CAP"
N="${COUNT:-$(sed -n 's/^EMULATORS=//p' <<<"$CAP")}"
[ "${N:-0}" -ge 1 ] || { echo "!! host has no room for an emulator"; exit 1; }

echo "== 2. nix"
"${SSH[@]}" 'bash -s' <<'EOF'
set -e
if ! command -v nix >/dev/null 2>&1 && [ ! -x "$HOME/.nix-profile/bin/nix" ]; then
  sudo install -d -m 0755 -o "$(id -u)" -g "$(id -g)" /nix
  sh <(curl -fsSL https://nixos.org/nix/install) --no-daemon --yes
fi
mkdir -p ~/.config/nix
grep -q flakes ~/.config/nix/nix.conf 2>/dev/null || echo "experimental-features = nix-command flakes" >> ~/.config/nix/nix.conf
EOF

echo "== 3. sources and APKs -> $HOST:~/$REPO"
"${SSH[@]}" "mkdir -p ~/$REPO/tools/bench/farm/apks ~/$REPO/bench/build/outputs/apk/debug ~/$REPO/app/build/outputs/apk/release"
rsync -az --delete --exclude '.git/' --exclude 'build/' --exclude '.gradle/' --exclude 'tools/bench/out/' \
  --exclude 'tools/bench/farm/apks/' --exclude 'docs/ref/' --exclude '.kotlin/' "$ROOT/" "$HOST:$REPO/"
git -C "$ROOT" rev-parse HEAD | "${SSH[@]}" "cat > ~/$REPO/.harness_commit"
rsync -az "$ROOT/bench/build/outputs/apk/debug/bench-debug.apk" "$HOST:$REPO/bench/build/outputs/apk/debug/"
[ -f "$ROOT/app/build/outputs/apk/release/app-release.apk" ] && rsync -az "$ROOT/app/build/outputs/apk/release/app-release.apk" "$HOST:$REPO/app/build/outputs/apk/release/"
[ -d "$ROOT/tools/bench/farm/apks" ] && rsync -az "$ROOT/tools/bench/farm/apks/" "$HOST:$REPO/tools/bench/farm/apks/"
echo "== warming the devshell (first run downloads the pinned SDK)"
"${SSH[@]}" "cd ~/$REPO && nix develop --command bash -c 'adb version | head -1; emulator -version | head -1'"

echo "== 4. golden AVD $GNAME -> $HOST:$AVD_DIR"
"${SSH[@]}" "mkdir -p $AVD_DIR ~/.android/avd"
rsync -a --sparse --info=progress2 "$GOLDEN/" "$HOST:$AVD_DIR/$GNAME.avd/"
"${SSH[@]}" "rm -f $AVD_DIR/$GNAME.avd/*.lock; printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-36\n' $AVD_DIR/$GNAME.avd $GNAME > ~/.android/avd/$GNAME.ini"

echo "== 5. $N clones"
NS=""; [ "$START" = 1 ] || NS=--no-start
"${SSH[@]}" "cd ~/$REPO && nix develop --command tools/bench/make_bench_avds.sh --golden $GNAME --count $N --avd-dir $AVD_DIR $NS"
DEVS="$(seq 0 $((N - 1)) | awk '{printf "%semulator-%d", (NR>1?",":""), 5570 + 2*$1}')"
echo "$DEVS" | "${SSH[@]}" "cat > ~/$REPO/farm_devices"
echo "== farm ready: $N emulators"
echo "DEVICES=$DEVS"
