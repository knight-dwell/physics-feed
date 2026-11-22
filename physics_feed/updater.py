"""Self-update from GitHub: compare the installed commit with the newest one on the repo's main branch,
download that version and run its install.sh (user-level, no system packages)."""
import json
import os
import subprocess
import tarfile
import tempfile
import urllib.request

REPO = "knight-dwell/physics-feed"
HERE = os.path.dirname(os.path.abspath(__file__))
INSTALLED = os.path.join(HERE, "INSTALLED.json")
UA = "physics-feed-updater/1.0"


def _get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def installed():
    """{"commit": sha, "date": ...} written by install.sh; empty when run from a source folder."""
    try:
        with open(INSTALLED) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def latest():
    """Newest commit on main: {"commit", "date", "message"}."""
    c = json.loads(_get(f"https://api.github.com/repos/{REPO}/commits/main"))
    return {"commit": c["sha"], "date": c["commit"]["committer"]["date"][:10],
            "message": c["commit"]["message"].splitlines()[0]}


def update_available():
    """(newer: bool, latest info). Unknown installed version counts as 'newer available'."""
    new = latest()
    return new["commit"] != installed().get("commit"), new


def install(commit):
    """Download that commit and run its installer. Returns (ok, log text)."""
    with tempfile.TemporaryDirectory(prefix="physics-feed-update-") as tmp:
        tgz = os.path.join(tmp, "src.tar.gz")
        with open(tgz, "wb") as fh:
            fh.write(_get(f"https://codeload.github.com/{REPO}/tar.gz/{commit}", timeout=120))
        with tarfile.open(tgz) as tf:
            root = os.path.realpath(tmp)
            for m in tf.getmembers():   # refuse paths that would land outside the temp folder
                if not os.path.realpath(os.path.join(tmp, m.name)).startswith(root + os.sep):
                    return False, f"unsafe path in download: {m.name}"
            tf.extractall(tmp)
        src = next(os.path.join(tmp, d) for d in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, d)))
        env = dict(os.environ, PF_COMMIT=commit)
        r = subprocess.run(["bash", os.path.join(src, "install.sh"), "--no-deps", "--yes"], cwd=src, env=env,
                           capture_output=True, text=True, timeout=600)
        return r.returncode == 0, (r.stdout + r.stderr)[-3000:]
