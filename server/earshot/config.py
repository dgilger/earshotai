"""Settings.

Fixed settings come from the environment (the installer writes them to /etc/earshot/earshot.env):
  EARSHOT_DATA   data folder (default /var/lib/earshot, or server/data-hub when run from a checkout)
  EARSHOT_HOST   listen address (default 0.0.0.0)
  EARSHOT_PORT   listen port (default 8000)

Settings that the portal can change live in <data>/settings.json. The access token lives in
<data>/token (made on first start).
"""

import json
import os
import secrets
import threading
from pathlib import Path

_default_data = Path("/var/lib/earshot") if Path("/var/lib/earshot").exists() else \
    Path(__file__).resolve().parent.parent / "data-hub"
DATA = Path(os.environ.get("EARSHOT_DATA", str(_default_data)))
HOST = os.environ.get("EARSHOT_HOST", "0.0.0.0")
PORT = int(os.environ.get("EARSHOT_PORT", "8000"))

MODELS = DATA / "models"
AUDIO = DATA / "audio"
DB_PATH = DATA / "earshot.db"
TOKEN_FILE = DATA / "token"
SETTINGS_FILE = DATA / "settings.json"

DEFAULTS = {
    "model": "auto",          # auto | large-v3 | large-v3-turbo | medium.en | small.en
    "device": "auto",         # auto | cuda | cpu
    "language": "en",         # "" = detect
    "diarize": True,          # speaker labels
    "skip_background": True,  # leave out voices much quieter than the conversation (TV, next table)
    "keep_audio": False,      # keep audio files after transcription
    "public_url": "",         # https address the phone and AI tools use (set by the installer)
}

_lock = threading.Lock()


def ensure_dirs() -> None:
    for d in (DATA, MODELS, AUDIO):
        d.mkdir(parents=True, exist_ok=True)


def load() -> dict:
    s = dict(DEFAULTS)
    try:
        s.update(json.loads(SETTINGS_FILE.read_text()))
    except (FileNotFoundError, ValueError):
        pass
    return s


def save(changes: dict) -> dict:
    with _lock:
        s = load()
        s.update({k: v for k, v in changes.items() if k in DEFAULTS})
        ensure_dirs()
        tmp = SETTINGS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(s, indent=2) + "\n")
        tmp.replace(SETTINGS_FILE)
        return s


def token() -> str:
    """The access token for the phone, the portal and AI tools. Made on first use."""
    with _lock:
        try:
            t = TOKEN_FILE.read_text().strip()
            if t:
                return t
        except FileNotFoundError:
            pass
        return _write_token()


def new_token() -> str:
    with _lock:
        return _write_token()


def _write_token() -> str:
    ensure_dirs()
    t = secrets.token_urlsafe(24)
    tmp = TOKEN_FILE.with_suffix(".tmp")
    tmp.write_text(t + "\n")
    os.chmod(tmp, 0o600)
    tmp.replace(TOKEN_FILE)
    return t


def check_token(given: str | None) -> bool:
    return bool(given) and secrets.compare_digest(given.strip(), token())
