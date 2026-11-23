#!/usr/bin/env python3
"""Earshot upload server.

Receives raw 16-bit little-endian PCM over HTTP POST /upload, saves it as a WAV, and queues
it for transcription with faster-whisper (see transcriber.py), then summarises the transcript
with Claude or a local LLM (see summarizer.py; backend set by SUMMARY_BACKEND in server/.env).

Run from server/:  .venv/bin/python earshot_server.py
  --no-transcribe   only save WAVs
  --no-summary      transcribe but skip summaries

Request headers (all optional, defaults shown):
  X-Sample-Rate: 16000
  X-Bits: 16
  X-Channels: 1
  X-Device: <id>         used in the file name
"""

import argparse
import array
import json
import logging
import math
import re
import sys
import wave
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

log = logging.getLogger("server")
worker = None  # TranscriptionWorker, set in main() unless --no-transcribe

MAX_UPLOAD_BYTES = 64 * 1024 * 1024  # 64 MB, ~35 min of 16 kHz mono
DATA_DIR = Path(__file__).resolve().parent / "data"


NORMALIZE_PEAK_DBFS = -3.0  # target peak after normalisation
NORMALIZE_MAX_GAIN_DB = 20.0  # cap so near-silent clips don't turn into loud hiss


def to_db(v: float) -> float:
    return 20 * math.log10(v / 32768) if v > 0 else -96.0


def load_pcm(pcm: bytes) -> array.array:
    samples = array.array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def dump_pcm(samples: array.array) -> bytes:
    if sys.byteorder == "big":
        samples = array.array("h", samples)
        samples.byteswap()
    return samples.tobytes()


def pcm_stats(samples: array.array) -> tuple[float, float]:
    """Return (rms dBFS, peak dBFS)."""
    if not samples:
        return -96.0, -96.0
    peak = max(abs(s) for s in samples)
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    return to_db(rms), to_db(peak)


def normalize(samples: array.array) -> float:
    """Peak-normalise in place to NORMALIZE_PEAK_DBFS (boost only, capped). Returns gain in dB."""
    _, peak_db = pcm_stats(samples)
    gain_db = min(NORMALIZE_PEAK_DBFS - peak_db, NORMALIZE_MAX_GAIN_DB)
    if gain_db <= 0.1:
        return 0.0
    g = 10 ** (gain_db / 20)
    for i, s in enumerate(samples):
        samples[i] = max(-32768, min(32767, round(s * g)))
    return gain_db


class UploadHandler(BaseHTTPRequestHandler):
    server_version = "Earshot/0.1"

    def _reply(self, code: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._reply(200, {"ok": True})
        else:
            self._reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/upload":
            return self._reply(404, {"error": "not found"})

        try:
            length = int(self.headers.get("Content-Length", "0"))
            rate = int(self.headers.get("X-Sample-Rate", "16000"))
            bits = int(self.headers.get("X-Bits", "16"))
            channels = int(self.headers.get("X-Channels", "1"))
        except ValueError:
            return self._reply(400, {"error": "bad header value"})
        if length <= 0 or length > MAX_UPLOAD_BYTES:
            return self._reply(413 if length > 0 else 411, {"error": f"bad length {length}"})
        if bits != 16 or channels not in (1, 2) or not 8000 <= rate <= 48000:
            return self._reply(400, {"error": "expected 16-bit, 1-2 ch, 8-48 kHz PCM"})

        pcm = self.rfile.read(length)
        if len(pcm) != length:
            return self._reply(400, {"error": f"short body: {len(pcm)}/{length}"})

        samples = load_pcm(pcm)
        rms_db, peak_db = pcm_stats(samples)  # as received
        gain_db = normalize(samples)

        device = re.sub(r"[^A-Za-z0-9_-]", "", self.headers.get("X-Device", "")) or "unknown"
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        path = DATA_DIR / f"{stamp}_{device}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(channels)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(dump_pcm(samples))

        seconds = length / (rate * channels * 2)
        self.log_message("saved %s (%.1fs, rms %.1f dBFS, peak %.1f dBFS, +%.1f dB normalised)",
                         path.name, seconds, rms_db, peak_db, gain_db)
        if worker:
            worker.submit(path)
        self._reply(200, {"file": path.name, "seconds": round(seconds, 2),
                          "rms_dbfs": round(rms_db, 1), "peak_dbfs": round(peak_db, 1),
                          "gain_db": round(gain_db, 1), "transcribing": worker is not None})


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--no-transcribe", action="store_true", help="only save WAVs")
    ap.add_argument("--no-summary", action="store_true", help="transcribe but don't summarise")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")

    global worker
    if not args.no_transcribe:
        from transcriber import Transcriber, TranscriptionWorker
        summarizer = None
        if not args.no_summary:
            from summarizer import make_summarizer
            try:
                summarizer = make_summarizer()  # None if SUMMARY_BACKEND=none
            except RuntimeError as e:
                log.warning("summaries disabled: %s", e)
        worker = TranscriptionWorker(Transcriber(), summarizer)
    httpd = ThreadingHTTPServer((args.host, args.port), UploadHandler)
    print(f"Earshot server listening on {args.host}:{args.port}, saving to {DATA_DIR}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
