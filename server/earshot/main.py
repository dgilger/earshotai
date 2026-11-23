"""Earshot server: HTTP API for the phone app, web portal, and MCP endpoint.

Run:  python -m earshot.main      (the installer sets it up as the 'earshot' system service)
"""

import contextlib
import io
import logging
import re
import secrets
import shutil
import threading
import time
from collections import defaultdict, deque
from pathlib import Path

import uvicorn
from fastapi import Body, Depends, FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles

from . import __version__, asr, config, db, models
from .mcp_tools import http_app as mcp_http_app, mcp

log = logging.getLogger("earshot")
PORTAL = Path(__file__).resolve().parent / "portal"
COOKIE = "earshot"

engine = asr.Engine()
worker: asr.Worker | None = None
_mcp_app = mcp_http_app()


@contextlib.asynccontextmanager
async def lifespan(_app):
    global worker
    config.ensure_dirs()
    db.init()
    config.token()
    worker = asr.Worker(engine)
    async with mcp.session_manager.run():
        yield


app = FastAPI(title="Earshot", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

MAX_CHUNK_BYTES = 300 * 1024 * 1024        # a phone chunk is at most ~20 MB (10 min of speech)
MAX_UPLOAD_BYTES = 4 * 1024 * 1024 * 1024  # files from the portal

# Pages may only load their own files; no other site may frame them.
CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
       "connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")


@app.middleware("http")
async def security_headers(request: Request, call_next):
    resp = await call_next(request)
    h = resp.headers
    h.setdefault("X-Content-Type-Options", "nosniff")
    h.setdefault("X-Frame-Options", "DENY")
    h.setdefault("Referrer-Policy", "no-referrer")
    h.setdefault("Content-Security-Policy", CSP)
    if request.url.path.startswith("/api/"):
        h.setdefault("Cache-Control", "no-store")
    return resp


# ---- failed sign-in limit ----
# The token has 192 random bits, so guessing cannot work; this only stops endless tries filling the logs.
FAIL_WINDOW_S = 600
FAIL_LIMIT = 30
_fails: dict[str, deque] = defaultdict(deque)
_fails_lock = threading.Lock()


def _client_ip(scope_or_request) -> str:
    client = scope_or_request.client if hasattr(scope_or_request, "client") else scope_or_request.get("client")
    return (client[0] if isinstance(client, tuple) else getattr(client, "host", "")) or "?"


def too_many_fails(ip: str) -> bool:
    now = time.monotonic()
    with _fails_lock:
        q = _fails[ip]
        while q and now - q[0] > FAIL_WINDOW_S:
            q.popleft()
        return len(q) >= FAIL_LIMIT


def note_fail(ip: str) -> None:
    with _fails_lock:
        _fails[ip].append(time.monotonic())
        if len(_fails) > 10000:   # keep memory bounded
            _fails.clear()


def save_limited(src, path: Path, limit: int) -> int:
    """Copies an upload to disk; stops and deletes it past [limit] bytes."""
    n = 0
    with open(path, "wb") as f:
        while chunk := src.read(1 << 20):
            n += len(chunk)
            if n > limit:
                f.close()
                path.unlink(missing_ok=True)
                raise HTTPException(413, f"File too big (limit {limit // (1024 * 1024)} MB)")
            f.write(chunk)
    return n
app.mount("/static", StaticFiles(directory=PORTAL), name="static")


# ---- auth ----

def bearer(request: Request) -> str:
    h = request.headers.get("authorization", "")
    return h[7:].strip() if h.lower().startswith("bearer ") else ""


def auth(request: Request) -> None:
    if config.check_token(bearer(request) or request.cookies.get(COOKIE) or request.headers.get("x-earshot-token")):
        return   # the right token always works, so strangers can't lock you out
    ip = _client_ip(request)
    if too_many_fails(ip):
        raise HTTPException(429, "Too many wrong tokens. Wait 10 minutes.")
    note_fail(ip)
    raise HTTPException(401, "Wrong or missing access token")


def https(request: Request) -> bool:
    return request.headers.get("x-forwarded-proto", request.url.scheme) == "https"


def base_url(request: Request) -> str:
    pub = config.load()["public_url"].rstrip("/")
    if pub:
        return pub
    proto = request.headers.get("x-forwarded-proto", request.url.scheme)
    return f"{proto}://{request.headers.get('host', request.url.netloc)}"


@app.post("/api/login")
def login(request: Request, response: Response, token: str = Body(..., embed=True)):
    ip = _client_ip(request)
    ok = config.check_token(token)
    if not ok and too_many_fails(ip):
        raise HTTPException(429, "Too many wrong tokens. Wait 10 minutes.")
    if not ok:
        note_fail(ip)
        raise HTTPException(401, "That access token is not right. Find it with: sudo earshot token")
    response.set_cookie(COOKIE, token.strip(), max_age=400 * 86400, httponly=True, samesite="strict", secure=https(request))
    return {"ok": True}


@app.post("/api/logout")
def logout(response: Response):
    response.delete_cookie(COOKIE)
    return {"ok": True}


# ---- public pages ----

@app.get("/health")
def health():
    return {"ok": True, "app": "earshot", "version": __version__}


@app.get("/", response_class=HTMLResponse)
def portal():
    return FileResponse(PORTAL / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/pair", response_class=HTMLResponse)
def pair_page():
    return FileResponse(PORTAL / "pair.html", headers={"Cache-Control": "no-cache"})


# ---- phone app ----

def clean_id(s: str, n: int = 80) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "", s)[:n]


@app.post("/api/chunks", dependencies=[Depends(auth)])
def upload_chunk(
    audio: UploadFile = File(...),
    recording: str = Form(...),          # the phone's id for this recording (its start time in ms)
    start_ms: int = Form(...),           # start time of this chunk (epoch ms)
    index: str = Form(""),               # "<sample offset>\t<epoch ms>\t<samples>" per speech segment
    device: str = Form("phone"),
    source: str = Form("phone"),         # phone | call
):
    """One chunk of speech from the phone: 16 kHz 16-bit mono PCM. Safe to send again (same chunk = same job)."""
    dev = clean_id(device, 40) or "phone"
    rid = f"{dev}-{clean_id(recording)}"
    key = f"{rid}:{start_ms}"
    old = db.job_by_key(key)
    if old and Path(old["audio_path"]).exists() or old and old["state"] == "done":
        if old["state"] == "failed":  # the phone tries again: so does the server
            db.retry_job(old["id"])
            if worker:
                worker.poke()
        return {"job": old["id"], "state": db.job(old["id"])["state"], "recording": rid}
    try:
        rec_ms = int(recording)
    except ValueError:
        rec_ms = start_ms
    db.ensure_recording(rid, "call" if source == "call" else "phone", rec_ms, dev)
    path = config.AUDIO / f"{clean_id(key.replace(':', '_'), 160)}.pcm"
    save_limited(audio.file, path, MAX_CHUNK_BYTES)
    if old:  # failed earlier and its audio is gone: take the new copy
        db.conn().execute("DELETE FROM jobs WHERE id=?", (old["id"],))
    j = db.add_job(rid, key, "pcm", str(path), start_ms, index)
    if worker:
        worker.poke()
    return {"job": j["id"], "state": j["state"], "recording": rid}


@app.get("/api/jobs/{jid}", dependencies=[Depends(auth)])
def job_state(jid: int):
    j = db.job(jid)
    if not j:
        raise HTTPException(404, "No such job")
    out = {"job": jid, "state": j["state"], "error": j["error"], "recording": j["recording_id"],
           "queue": db.queue_size()}
    if j["state"] == "done":
        out["lines"] = db.job_lines(jid)
    return out


@app.post("/api/recordings/{rid}/finish", dependencies=[Depends(auth)])
def finish(rid: str, end_ms: int = Body(...), label: str = Body(""), device: str = Body("phone")):
    """The phone stopped recording. rid is the phone's own id; the server's id is <device>-<rid>."""
    full = f"{clean_id(device, 40) or 'phone'}-{clean_id(rid)}"
    return {"ok": db.finish_recording(full, end_ms, label[:120]), "recording": full}


# ---- portal ----

@app.get("/api/status", dependencies=[Depends(auth)])
def status(request: Request):
    s = config.load()
    cur = worker.current if worker else None
    free = shutil.disk_usage(config.DATA).free
    return {
        "version": __version__, "state": engine.state, "error": engine.error,
        "device": engine.device, "device_note": engine.device_note, "model": engine.model_name or s["model"],
        "speaker_models": models.speaker_models_ready(), "working_on": cur["recording_id"] if cur else None,
        "disk_free_gb": round(free / 1e9, 1), "url": base_url(request), "settings": s, **db.stats(),
    }


@app.get("/api/settings", dependencies=[Depends(auth)])
def get_settings():
    return {"settings": config.load(), "models": models.WHISPER_MODELS}


@app.put("/api/settings", dependencies=[Depends(auth)])
def put_settings(changes: dict = Body(...)):
    s = config.save(changes)
    if worker:
        worker.poke()
    return {"settings": s}


@app.post("/api/token", dependencies=[Depends(auth)])
def rotate_token(request: Request, response: Response):
    """New tokens (full and MCP). The phone and AI tools must be set up again."""
    t = config.new_token()
    response.set_cookie(COOKIE, t, max_age=400 * 86400, httponly=True, samesite="strict", secure=https(request))
    return {"token": t}


@app.post("/api/mcp-token", dependencies=[Depends(auth)])
def rotate_mcp_token(request: Request):
    """New read-only MCP token. Old MCP addresses stop working at once; the phone is not touched."""
    t = config.new_mcp_token()
    return {"mcp_url": f"{base_url(request)}/mcp/{t}"}


# ---- updates from the portal ----
# The service can't update itself (it runs as a locked-down user). It writes a request file;
# a root systemd unit (earshot-updater, set up by the installer) does the work and writes
# update-status.json and update.log back into the data folder.
UPDATE_REQ = config.DATA / "update-request"
UPDATE_STATUS = config.DATA / "update-status.json"
UPDATE_LOG = config.DATA / "update.log"


@app.get("/api/update", dependencies=[Depends(auth)])
def update_status():
    import json
    try:
        s = json.loads(UPDATE_STATUS.read_text())
    except (FileNotFoundError, ValueError):
        s = {"state": "unknown", "available": False, "notes": [], "current": "", "latest": "", "message": ""}
    s["pending"] = UPDATE_REQ.exists()   # asked, the updater has not picked it up yet
    s["installed"] = Path("/etc/systemd/system/earshot-updater.path").exists()
    s["version"] = __version__
    try:
        lines = UPDATE_LOG.read_text(errors="replace").splitlines()
        s["log"] = [ln.strip() for ln in lines if ln.strip()][-14:] if s.get("state") in ("updating", "error", "done") else []
    except FileNotFoundError:
        s["log"] = []
    return s


@app.post("/api/update", dependencies=[Depends(auth)])
def update_request(action: str = Body("check", embed=True)):
    if action not in ("check", "update"):
        raise HTTPException(400, "action must be check or update")
    UPDATE_REQ.write_text(action + "\n")
    return {"ok": True}


@app.get("/api/pair", dependencies=[Depends(auth)])
def pair_info(request: Request):
    import qrcode
    import qrcode.image.svg
    url = base_url(request)
    t = config.token()
    link = f"{url}/pair#{t}"
    img = qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage, box_size=10, border=2)
    buf = io.BytesIO()
    img.save(buf)
    return {"url": url, "token": t, "pair_link": link, "mcp_url": f"{url}/mcp/{config.mcp_token()}",
            "qr_svg": buf.getvalue().decode(), "local": not url.startswith("https://")}


@app.get("/api/recordings", dependencies=[Depends(auth)])
def list_recordings(q: str = "", limit: int = 50, offset: int = 0):
    limit = max(1, min(limit, 200))
    return {"recordings": db.search_recordings(q, limit) if q.strip() else db.recordings(limit, offset),
            "total": db.count_recordings()}


@app.get("/api/recordings/{rid}", dependencies=[Depends(auth)])
def get_recording(rid: str):
    r = db.recording(rid)
    if not r:
        raise HTTPException(404, "No such recording")
    return {**r, "lines": db.lines(rid)}


@app.get("/api/recordings/{rid}/text", dependencies=[Depends(auth)])
def recording_text(rid: str, download: bool = False):
    text = db.transcript_text(rid)
    if not text:
        raise HTTPException(404, "No such recording")
    r = db.recording(rid)
    name = re.sub(r"[^\w .()-]", "", r["title"]).strip() or "transcript"
    headers = {"Content-Disposition": f'attachment; filename="{name}.txt"'} if download else {}
    return PlainTextResponse(text, headers=headers)


@app.patch("/api/recordings/{rid}", dependencies=[Depends(auth)])
def rename_recording(rid: str, title: str = Body(..., embed=True)):
    db.rename(rid, title)
    return db.recording(rid)


@app.delete("/api/recordings/{rid}", dependencies=[Depends(auth)])
def delete_recording(rid: str):
    for p in db.delete(rid):
        Path(p).unlink(missing_ok=True)
    return {"ok": True}


@app.post("/api/recordings/{rid}/retry", dependencies=[Depends(auth)])
def retry(rid: str):
    n = db.retry_failed(rid)
    if worker:
        worker.poke()
    return {"retried": n}


@app.post("/api/upload", dependencies=[Depends(auth)])
def upload_file(file: UploadFile = File(...), started_ms: int = Form(0)):
    """Any audio or video file from the portal (or the phone's call recorder)."""
    start = started_ms or db.now_ms()
    rid = f"upload-{start}-{secrets.token_hex(3)}"
    name = Path(file.filename or "audio").name
    title = Path(name).stem[:120] or "Uploaded audio"
    db.ensure_recording(rid, "upload", start, "upload", title)
    path = config.AUDIO / f"{rid}{clean_id(Path(name).suffix, 10)}"
    save_limited(file.file, path, MAX_UPLOAD_BYTES)
    j = db.add_job(rid, rid, "file", str(path), start)
    if worker:
        worker.poke()
    return {"recording": rid, "job": j["id"]}


# ---- MCP, wrapped around the app: /mcp (token in header) or /mcp/<token> ----

class Root:
    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        if scope["type"] == "http" and (path == "/mcp" or path.startswith("/mcp/")):
            token = path[5:].strip("/") or None
            if not token:
                for k, v in scope.get("headers", []):
                    if k == b"authorization" and v[:7].lower() == b"bearer ":
                        token = v[7:].decode(errors="replace")
            ip = _client_ip(scope)
            if not config.check_mcp_token(token):
                if too_many_fails(ip):
                    return await JSONResponse({"error": "Too many wrong tokens"}, status_code=429)(scope, receive, send)
                note_fail(ip)
                resp = JSONResponse({"error": "Wrong or missing access token"}, status_code=401)
                return await resp(scope, receive, send)
            scope = dict(scope, path="/mcp", raw_path=b"/mcp")
            return await _mcp_app(scope, receive, send)
        return await self.inner(scope, receive, send)


root = Root(app)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    config.ensure_dirs()
    db.init()
    log.info("Earshot %s: data in %s, port %s", __version__, config.DATA, config.PORT)
    uvicorn.run(root, host=config.HOST, port=config.PORT, proxy_headers=True,
                # Only the tunnel on this computer (cloudflared, tailscale) may say who the client is.
                forwarded_allow_ips="127.0.0.1,::1",
                log_level="warning", lifespan="on")


if __name__ == "__main__":
    main()
