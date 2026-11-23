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
MCP_TOKEN_FILE = DATA / "mcp_token"   # read-only token for AI tools
SETTINGS_FILE = DATA / "settings.json"

DEFAULTS = {
    "model": "auto",          # auto | large-v3 | large-v3-turbo | medium.en | small.en
    "device": "auto",         # auto | cuda | cpu
    "language": "en",         # "" = detect
    "diarize": True,          # speaker labels
    "skip_background": False,  # leave out voices far quieter than the conversation (TV across the room)
    "keep_audio": False,      # keep audio files after transcription
    "public_url": "",         # https address the phone and AI tools use (set by the installer)
}

_lock = threading.Lock()


def ensure_dirs() -> None:
    for d in (DATA, MODELS, AUDIO):
        d.mkdir(parents=True, exist_ok=True)


SETTINGS_REV = 2   # 2: background filter off by default (it dropped people a few steps away)


def load() -> dict:
    s = dict(DEFAULTS)
    try:
        saved = json.loads(SETTINGS_FILE.read_text())
        if saved.get("rev", 1) < 2:
            saved.pop("skip_background", None)   # saved with the old default: use the new one
        s.update(saved)
    except (FileNotFoundError, ValueError):
        pass
    s["rev"] = SETTINGS_REV
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


def _read_or_make(path: Path) -> str:
    try:
        t = path.read_text().strip()
        if t:
            return t
    except FileNotFoundError:
        pass
    return _write_token(path)


def token() -> str:
    """The full-access token for the phone and the portal. Made on first use."""
    with _lock:
        return _read_or_make(TOKEN_FILE)


def mcp_token() -> str:
    """A separate read-only token for AI tools (MCP). A leaked MCP address can only read transcripts."""
    with _lock:
        return _read_or_make(MCP_TOKEN_FILE)


def new_token() -> str:
    """New tokens for everything (phone, portal and AI tools). Returns the new full-access token."""
    with _lock:
        _write_token(MCP_TOKEN_FILE)
        return _write_token(TOKEN_FILE)


def new_mcp_token() -> str:
    """A new read-only token for AI tools only. The old MCP address stops working; the phone keeps working."""
    with _lock:
        return _write_token(MCP_TOKEN_FILE)


def _write_token(path: Path) -> str:
    ensure_dirs()
    t = secrets.token_urlsafe(24)   # 192 random bits
    tmp = path.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)   # never readable by others, not even briefly
    with os.fdopen(fd, "w") as f:
        f.write(t + "\n")
    tmp.replace(path)
    return t


def check_token(given: str | None) -> bool:
    return bool(given) and secrets.compare_digest(given.strip().encode(), token().encode())


def check_mcp_token(given: str | None) -> bool:
    """AI tools may use the read-only MCP token or the full token."""
    return bool(given) and (secrets.compare_digest(given.strip().encode(), mcp_token().encode())
                            or check_token(given))
