"""Transcript storage: one SQLite file with full-text search.

recordings  one per recording (a phone recording from REC to STOP, a call, or an uploaded file)
jobs        one per piece of audio waiting for or done with transcription
lines       transcript lines (time, speaker, text); speaker numbers restart for each job
lines_fts   full-text index over lines.text
"""

import re
import sqlite3
import threading
import time
from datetime import datetime

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS recordings (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    source TEXT NOT NULL,            -- phone | call | upload
    device TEXT NOT NULL DEFAULT '',
    started_ms INTEGER NOT NULL,
    ended_ms INTEGER,
    finished INTEGER NOT NULL DEFAULT 0,
    created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
    chunk_key TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL,              -- pcm (from the phone) | file (any audio file)
    audio_path TEXT NOT NULL,
    index_text TEXT NOT NULL DEFAULT '',
    start_ms INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued',   -- queued | working | done | failed
    error TEXT NOT NULL DEFAULT '',
    audio_s REAL NOT NULL DEFAULT 0,
    took_s REAL NOT NULL DEFAULT 0,
    created_ms INTEGER NOT NULL,
    done_ms INTEGER
);
CREATE INDEX IF NOT EXISTS jobs_state ON jobs(state, id);
CREATE TABLE IF NOT EXISTS lines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
    job_id INTEGER NOT NULL,
    t_ms INTEGER NOT NULL,
    speaker INTEGER NOT NULL DEFAULT 0,
    text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS lines_rec ON lines(recording_id, job_id, t_ms);
CREATE VIRTUAL TABLE IF NOT EXISTS lines_fts USING fts5(text, content='lines', content_rowid='id');
CREATE TRIGGER IF NOT EXISTS lines_ai AFTER INSERT ON lines BEGIN
    INSERT INTO lines_fts(rowid, text) VALUES (new.id, new.text);
END;
CREATE TRIGGER IF NOT EXISTS lines_ad AFTER DELETE ON lines BEGIN
    INSERT INTO lines_fts(lines_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;
"""

_local = threading.local()


def conn() -> sqlite3.Connection:
    c = getattr(_local, "conn", None)
    if c is None:
        config.ensure_dirs()
        c = sqlite3.connect(config.DB_PATH, timeout=30, isolation_level=None, check_same_thread=False)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA foreign_keys=ON")
        _local.conn = c
    return c


def init() -> None:
    conn().executescript(SCHEMA)
    # Work interrupted by a restart starts again.
    conn().execute("UPDATE jobs SET state='queued' WHERE state='working'")


def now_ms() -> int:
    return int(time.time() * 1000)


def stamp(ms: int | None, fmt: str = "%Y-%m-%d %H:%M") -> str:
    return datetime.fromtimestamp(ms / 1000).strftime(fmt) if ms else ""


# ---- recordings ----

def ensure_recording(rid: str, source: str, started_ms: int, device: str = "", title: str = "") -> None:
    if not title:
        title = ("Call " if source == "call" else "Recording ") + stamp(started_ms)
    conn().execute(
        "INSERT OR IGNORE INTO recordings(id, title, source, device, started_ms, created_ms) VALUES (?,?,?,?,?,?)",
        (rid, title, source, device, started_ms, now_ms()))


def finish_recording(rid: str, ended_ms: int, label: str = "") -> bool:
    r = recording(rid)
    if not r:
        return False
    title = r["title"]
    if label and r["source"] == "call":
        title = f"Call: {label} ({stamp(r['started_ms'])})"
    conn().execute("UPDATE recordings SET finished=1, ended_ms=?, title=? WHERE id=?", (ended_ms, title, rid))
    return True


def rename(rid: str, title: str) -> None:
    conn().execute("UPDATE recordings SET title=? WHERE id=?", (title.strip()[:200] or "Untitled", rid))


def delete(rid: str) -> list[str]:
    """Deletes a recording and its lines; returns audio files to remove."""
    paths = [r["audio_path"] for r in conn().execute("SELECT audio_path FROM jobs WHERE recording_id=?", (rid,))]
    conn().execute("DELETE FROM lines WHERE recording_id=?", (rid,))
    conn().execute("DELETE FROM jobs WHERE recording_id=?", (rid,))
    conn().execute("DELETE FROM recordings WHERE id=?", (rid,))
    return paths


def recording(rid: str) -> dict | None:
    r = conn().execute(_REC_SELECT + " WHERE r.id=?", (rid,)).fetchone()
    return _rec(r) if r else None


_REC_SELECT = """
SELECT r.*,
  (SELECT COUNT(*) FROM jobs j WHERE j.recording_id=r.id AND j.state IN ('queued','working')) AS pending,
  (SELECT COUNT(*) FROM jobs j WHERE j.recording_id=r.id AND j.state='failed') AS failed,
  (SELECT COALESCE(SUM(audio_s),0) FROM jobs j WHERE j.recording_id=r.id) AS audio_s,
  (SELECT COUNT(*) FROM lines l WHERE l.recording_id=r.id) AS line_count,
  (SELECT substr(group_concat(text, ' '), 1, 220) FROM
      (SELECT text FROM lines l WHERE l.recording_id=r.id ORDER BY job_id, t_ms, id LIMIT 6)) AS preview
FROM recordings r"""


def _rec(r: sqlite3.Row) -> dict:
    d = dict(r)
    d["started"] = stamp(d["started_ms"])
    d["ended"] = stamp(d["ended_ms"])
    d["status"] = "transcribing" if d["pending"] else ("failed" if d["failed"] else
                                                       ("done" if d["finished"] or d["source"] == "upload" else "recording"))
    d["preview"] = d["preview"] or ""
    return d


def recordings(limit: int = 50, offset: int = 0, since_ms: int | None = None, until_ms: int | None = None) -> list[dict]:
    where, args = [], []
    if since_ms:
        where.append("r.started_ms >= ?"); args.append(since_ms)
    if until_ms:
        where.append("r.started_ms < ?"); args.append(until_ms)
    sql = _REC_SELECT + (" WHERE " + " AND ".join(where) if where else "") + \
        " ORDER BY r.started_ms DESC LIMIT ? OFFSET ?"
    return [_rec(r) for r in conn().execute(sql, (*args, limit, offset))]


def count_recordings() -> int:
    return conn().execute("SELECT COUNT(*) FROM recordings").fetchone()[0]


def fts_query(q: str) -> str:
    """Plain words to a safe FTS5 query: every word must appear (prefix match on the last)."""
    words = re.findall(r"[\w']+", q, flags=re.UNICODE)
    if not words:
        return ""
    parts = ['"' + w.replace('"', '') + '"' for w in words]
    parts[-1] += "*"
    return " ".join(parts)


def search(q: str, limit: int = 20) -> list[dict]:
    """Matching lines, best first, with the recording they belong to."""
    fq = fts_query(q)
    if not fq:
        return []
    rows = conn().execute("""
        SELECT l.recording_id, l.t_ms, l.speaker, r.title, r.started_ms,
               snippet(lines_fts, 0, '[', ']', ' … ', 24) AS snip
        FROM lines_fts JOIN lines l ON l.id = lines_fts.rowid JOIN recordings r ON r.id = l.recording_id
        WHERE lines_fts MATCH ? ORDER BY rank LIMIT ?""", (fq, limit)).fetchall()
    return [{"recording_id": r["recording_id"], "title": r["title"], "time": stamp(r["t_ms"], "%Y-%m-%d %H:%M:%S"),
             "speaker": r["speaker"], "snippet": r["snip"]} for r in rows]


def search_recordings(q: str, limit: int = 50) -> list[dict]:
    """Recordings that have a line matching q, newest first."""
    fq = fts_query(q)
    if not fq:
        return recordings(limit)
    ids = [r[0] for r in conn().execute("""
        SELECT DISTINCT l.recording_id FROM lines_fts JOIN lines l ON l.id = lines_fts.rowid
        WHERE lines_fts MATCH ? LIMIT 500""", (fq,))]
    title_ids = [r[0] for r in conn().execute(
        "SELECT id FROM recordings WHERE title LIKE ? LIMIT 200", (f"%{q.strip()}%",))]
    ids = list(dict.fromkeys(ids + title_ids))
    if not ids:
        return []
    marks = ",".join("?" * len(ids))
    return [_rec(r) for r in conn().execute(
        _REC_SELECT + f" WHERE r.id IN ({marks}) ORDER BY r.started_ms DESC LIMIT ?", (*ids, limit))]


def lines(rid: str) -> list[dict]:
    return [dict(r) for r in conn().execute(
        "SELECT job_id, t_ms, speaker, text FROM lines WHERE recording_id=? ORDER BY job_id, t_ms, id", (rid,))]


def transcript_text(rid: str) -> str:
    """The transcript as plain text, in the same layout as the phone app's .txt files."""
    r = recording(rid)
    if not r:
        return ""
    out = [r["title"],
           f"Recorded: {r['started']}" + (f" to {r['ended']}" if r["ended"] else ""),
           "Speaker numbers start again after each blank line.", ""]
    last_job = None
    for ln in lines(rid):
        if last_job is not None and ln["job_id"] != last_job:
            out.append("")
        last_job = ln["job_id"]
        who = f"Speaker {ln['speaker']}: " if ln["speaker"] else ""
        out.append(f"[{stamp(ln['t_ms'], '%H:%M:%S')}] {who}{ln['text']}")
    if r["pending"]:
        out += ["", "(More audio is still being transcribed.)"]
    return "\n".join(out) + "\n"


# ---- jobs ----

def add_job(rid: str, chunk_key: str, kind: str, audio_path: str, start_ms: int, index_text: str = "") -> dict:
    """Adds a job, or returns the existing one with the same key (the phone retries uploads)."""
    c = conn()
    old = c.execute("SELECT * FROM jobs WHERE chunk_key=?", (chunk_key,)).fetchone()
    if old:
        return dict(old)
    c.execute("INSERT INTO jobs(recording_id, chunk_key, kind, audio_path, index_text, start_ms, created_ms) "
              "VALUES (?,?,?,?,?,?,?)", (rid, chunk_key, kind, audio_path, index_text, start_ms, now_ms()))
    return dict(c.execute("SELECT * FROM jobs WHERE chunk_key=?", (chunk_key,)).fetchone())


def job(jid: int) -> dict | None:
    r = conn().execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
    return dict(r) if r else None


def job_by_key(key: str) -> dict | None:
    r = conn().execute("SELECT * FROM jobs WHERE chunk_key=?", (key,)).fetchone()
    return dict(r) if r else None


def next_job() -> dict | None:
    c = conn()
    r = c.execute("SELECT * FROM jobs WHERE state='queued' ORDER BY id LIMIT 1").fetchone()
    if not r:
        return None
    c.execute("UPDATE jobs SET state='working' WHERE id=?", (r["id"],))
    return dict(r)


def job_done(jid: int, rid: str, out: list[tuple[int, int, str]], audio_s: float, took_s: float) -> None:
    c = conn()
    c.execute("BEGIN")
    try:
        c.execute("DELETE FROM lines WHERE job_id=?", (jid,))
        c.executemany("INSERT INTO lines(recording_id, job_id, t_ms, speaker, text) VALUES (?,?,?,?,?)",
                      [(rid, jid, t, spk, text) for t, spk, text in out])
        c.execute("UPDATE jobs SET state='done', error='', audio_s=?, took_s=?, done_ms=? WHERE id=?",
                  (audio_s, took_s, now_ms(), jid))
        c.execute("COMMIT")
    except Exception:
        c.execute("ROLLBACK")
        raise


def job_failed(jid: int, error: str) -> None:
    conn().execute("UPDATE jobs SET state='failed', error=?, done_ms=? WHERE id=?", (error[:500], now_ms(), jid))


def retry_failed(rid: str | None = None) -> int:
    if rid:
        cur = conn().execute("UPDATE jobs SET state='queued', error='' WHERE state='failed' AND recording_id=?", (rid,))
    else:
        cur = conn().execute("UPDATE jobs SET state='queued', error='' WHERE state='failed'")
    return cur.rowcount


def retry_job(jid: int) -> None:
    conn().execute("UPDATE jobs SET state='queued', error='' WHERE id=? AND state='failed'", (jid,))


def job_lines(jid: int) -> list[dict]:
    return [{"t": r["t_ms"], "speaker": r["speaker"], "text": r["text"]} for r in conn().execute(
        "SELECT t_ms, speaker, text FROM lines WHERE job_id=? ORDER BY t_ms, id", (jid,))]


def queue_size() -> int:
    return conn().execute("SELECT COUNT(*) FROM jobs WHERE state IN ('queued','working')").fetchone()[0]


def stats() -> dict:
    c = conn()
    r = c.execute("SELECT COUNT(*), COALESCE(SUM(audio_s),0), COALESCE(SUM(took_s),0) FROM jobs WHERE state='done'").fetchone()
    return {"recordings": count_recordings(), "jobs_done": r[0], "audio_hours": round(r[1] / 3600, 2),
            "speed": round(r[1] / r[2], 1) if r[2] else None, "queue": queue_size(),
            "failed": c.execute("SELECT COUNT(*) FROM jobs WHERE state='failed'").fetchone()[0]}
