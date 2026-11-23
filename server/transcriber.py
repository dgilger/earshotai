"""Background transcription with faster-whisper (milestone 4).

Clips are queued after upload and transcribed one at a time on a worker thread, so uploads
return immediately. Results are written next to the WAV:
  <name>.txt   plain transcript
  <name>.json  segments with timestamps + timing info

Run directly to (re)transcribe existing files:
  .venv/bin/python transcriber.py data/*.wav
"""

import json
import logging
import queue
import sys
import threading
import time
import wave
from pathlib import Path

import numpy as np

log = logging.getLogger("transcriber")

MODEL_NAME = "large-v3-turbo"  # ~7 s per 10 s clip on the dev PC CPU; "small.en" ~2 s but less accurate
COMPUTE_TYPE = "int8"
WHISPER_RATE = 16000


def load_wav(path: Path) -> np.ndarray:
    """Read a 16-bit PCM WAV as mono float32 at 16 kHz."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path.name}: expected 16-bit PCM")
        rate, channels = w.getframerate(), w.getnchannels()
        audio = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != WHISPER_RATE:  # simple linear resample; the device already sends 16 kHz
        n = int(len(audio) * WHISPER_RATE / rate)
        audio = np.interp(np.linspace(0, len(audio), n, endpoint=False), np.arange(len(audio)), audio)
        audio = audio.astype(np.float32)
    return audio


class Transcriber:
    def __init__(self, model_name: str = MODEL_NAME, compute_type: str = COMPUTE_TYPE):
        from faster_whisper import WhisperModel

        t0 = time.monotonic()
        self.model_name = model_name
        try:  # use the cached model without contacting Hugging Face
            self.model = WhisperModel(model_name, device="cpu", compute_type=compute_type,
                                      local_files_only=True)
        except Exception:
            log.info("%s not cached yet, downloading once from Hugging Face", model_name)
            self.model = WhisperModel(model_name, device="cpu", compute_type=compute_type)
        log.info("loaded %s (%s) in %.1fs", model_name, compute_type, time.monotonic() - t0)

    def transcribe_file(self, wav: Path) -> dict:
        audio = load_wav(wav)
        t0 = time.monotonic()
        segments, info = self.model.transcribe(audio, beam_size=5, vad_filter=True, language="en",
                                                condition_on_previous_text=False)
        segs = [{"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()}
                for s in segments]
        elapsed = time.monotonic() - t0
        text = " ".join(s["text"] for s in segs).strip()

        result = {
            "file": wav.name,
            "model": self.model_name,
            "duration_s": round(info.duration, 2),
            "transcribe_s": round(elapsed, 2),
            "text": text,
            "segments": segs,
        }
        wav.with_suffix(".txt").write_text(text + "\n")
        wav.with_suffix(".json").write_text(json.dumps(result, indent=2) + "\n")
        return result


class TranscriptionWorker:
    """Single background thread that transcribes (and optionally summarises) queued WAVs in order."""

    def __init__(self, transcriber: Transcriber, summarizer=None):
        self.transcriber = transcriber
        self.summarizer = summarizer  # summarizer.Summarizer or None
        self.queue: "queue.Queue[Path]" = queue.Queue()
        threading.Thread(target=self._run, name="transcriber", daemon=True).start()

    def submit(self, wav: Path) -> None:
        self.queue.put(wav)

    def _run(self) -> None:
        while True:
            wav = self.queue.get()
            try:
                r = self.transcriber.transcribe_file(wav)
                log.info("transcribed %s (%.1fs audio in %.1fs): %s", wav.name,
                         r["duration_s"], r["transcribe_s"], r["text"] or "(no speech)")
            except Exception:
                log.exception("transcription failed for %s", wav.name)
                continue
            if self.summarizer and r["text"]:
                try:
                    notes = self.summarizer.summarize_result(wav.with_suffix(".json"))
                    if notes:
                        log.info("summary (%s, %.1fs): %s | %s", notes["model"], notes["summarize_s"],
                                 notes["title"], notes["summary"])
                except Exception:
                    log.exception("summary failed for %s", wav.name)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    t = Transcriber()
    for arg in sys.argv[1:]:
        r = t.transcribe_file(Path(arg))
        print(f"{r['file']}: {r['text'] or '(no speech)'}")
