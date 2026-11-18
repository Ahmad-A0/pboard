#!/usr/bin/env bash
# Runs ON a candidate farm host (bootstrap.sh pipes it over ssh). Fails fast without usable KVM,
# then prints the host's capacity and how many benchmark emulators it can hold:
#   EMULATORS=<n>  (2 vCPU, ~3 GB RAM, ~7 GB disk each, keeping 8 vCPU / 16 GB / 30 GB in reserve)
set -euo pipefail
AVD_DIR="${1:-/var/tmp/pboard-avd}"
fail() { echo "!! $*" >&2; exit 1; }
[ "$(uname -m)" = x86_64 ] || fail "need an x86_64 host (got $(uname -m))"
[ -e /dev/kvm ] || fail "/dev/kvm is missing: no hardware virtualisation. On a cloud VM pick a size that supports nested virtualisation and check that the image exposes KVM."
[ -r /dev/kvm ] && [ -w /dev/kvm ] || fail "/dev/kvm exists but $(id -un) cannot use it: sudo usermod -aG kvm $(id -un) (then log in again)"
grep -qE '\b(vmx|svm)\b' /proc/cpuinfo || fail "CPU does not advertise vmx/svm (nested virtualisation is off)"
# A KVM_CREATE_VM probe: python opens /dev/kvm and issues the ioctl.
python3 - <<'EOF' || fail "/dev/kvm does not accept KVM_CREATE_VM (nested virtualisation not functional)"
import fcntl, os
fd = os.open("/dev/kvm", os.O_RDWR)
vm = fcntl.ioctl(fd, 0xAE01, 0)   # KVM_CREATE_VM
os.close(vm); os.close(fd)
EOF
CPUS=$(nproc)
MEM_GB=$(awk '/MemTotal/ {print int($2/1024/1024)}' /proc/meminfo)
mkdir -p "$AVD_DIR"
DISK_GB=$(df -Pk "$AVD_DIR" | awk 'NR==2 {print int($4/1024/1024)}')
by_cpu=$(( (CPUS - 8) / 2 )); by_mem=$(( (MEM_GB - 16) / 3 )); by_disk=$(( (DISK_GB - 30) / 7 ))
n=$by_cpu; [ $by_mem -lt $n ] && n=$by_mem; [ $by_disk -lt $n ] && n=$by_disk; [ $n -gt 20 ] && n=20; [ $n -lt 0 ] && n=0
echo "== host $(hostname): $CPUS vCPU, ${MEM_GB} GB RAM, ${DISK_GB} GB free in $AVD_DIR, KVM ok"
echo "== capacity: cpu $by_cpu, memory $by_mem, disk $by_disk emulators"
echo "EMULATORS=$n"
