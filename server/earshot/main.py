"""Earshot server: HTTP API for the phone app, web portal, and MCP endpoint.

Run:  python -m earshot.main      (the installer sets it up as the 'earshot' system service)
"""

import contextlib
import io
import logging
import re
import secrets
import shutil
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


app = FastAPI(title="Earshot", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=PORTAL), name="static")


# ---- auth ----

def bearer(request: Request) -> str:
    h = request.headers.get("authorization", "")
    return h[7:].strip() if h.lower().startswith("bearer ") else ""


def auth(request: Request) -> None:
    if not config.check_token(bearer(request) or request.cookies.get(COOKIE) or request.headers.get("x-earshot-token")):
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
    if not config.check_token(token):
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
    with open(path, "wb") as f:
        shutil.copyfileobj(audio.file, f, 1 << 20)
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
    """New access token. The phone and AI tools must be set up again."""
    t = config.new_token()
    response.set_cookie(COOKIE, t, max_age=400 * 86400, httponly=True, samesite="strict", secure=https(request))
    return {"token": t}


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
    return {"url": url, "token": t, "pair_link": link, "mcp_url": f"{url}/mcp/{t}",
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
    path = config.AUDIO / f"{rid}{Path(name).suffix[:10]}"
    with open(path, "wb") as f:
        shutil.copyfileobj(file.file, f, 1 << 20)
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
            if not config.check_token(token):
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
    uvicorn.run(root, host=config.HOST, port=config.PORT, proxy_headers=True, forwarded_allow_ips="*",
                log_level="warning", lifespan="on")


if __name__ == "__main__":
    main()
