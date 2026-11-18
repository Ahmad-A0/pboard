"""Machine-local settings (device serials, bind addresses) from the untracked local.env; see tools/dev/local_env.sh.

    sys.path.insert(0, <repo>/tools/dev); import local_env
    serial = local_env.get("PBOARD_PHONE_SERIAL")                   # SystemExit with a clear message when unset
    serial = local_env.get("PBOARD_PHONE_SERIAL", required=False)   # None when unset
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_LEGACY = {"PBOARD_PHONE_SERIAL": "PBOARD_PHONE"}


def _main_checkout():
    """The main checkout of a linked worktree: its .git is a file 'gitdir: <main>/.git/worktrees/<name>'."""
    g = os.path.join(ROOT, ".git")
    if os.path.isfile(g):
        with open(g, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("gitdir: ") and "/.git/worktrees/" in line:
                    return line[len("gitdir: "):].strip().rsplit("/.git/worktrees/", 1)[0]
    return None


def env_file():
    main = _main_checkout()
    for f in (os.environ.get("PBOARD_LOCAL_ENV"), os.path.join(ROOT, "local.env"),
              os.path.join(main, "local.env") if main else None, os.path.expanduser("~/.config/pboard/local.env")):
        if f and os.path.isfile(f):
            return f
    return None


def _file_values():
    f, out = env_file(), {}
    if f:
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    return out


def get(key, required=True):
    v = os.environ.get(key) or _file_values().get(key) or os.environ.get(_LEGACY.get(key, ""), "")
    if not v and required:
        raise SystemExit("%s is not set: export it, or put %s=<value> in local.env at the repository root "
                         "(untracked; see local.env.example)" % (key, key))
    return v or None
