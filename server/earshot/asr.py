"""Speech to text with speaker labels.

1. Whisper (faster-whisper) turns speech into text with a time for every word. It runs on an
   NVIDIA GPU when one works, else on the CPU.
2. pyannote segmentation 3.0 + TitaNet (through sherpa-onnx, CPU) find who spoke when.
3. Each word gets the speaker who talked at that moment; lines break where the speaker changes.

Audio from the phone is raw 16 kHz 16-bit mono PCM with an index ("<sample offset>\\t<epoch ms>\\t<samples>"
per speech segment), so each line gets the wall-clock time it was said. Other audio files are
decoded with ffmpeg.
"""

import logging
import os
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import config, db, models

log = logging.getLogger("earshot.asr")
RATE = 16000


def detect_device(pref: str = "auto") -> tuple[str, str]:
    """('cuda' or 'cpu', reason in plain words)."""
    if pref == "cpu":
        return "cpu", "set to CPU in settings"
    try:
        import ctranslate2
        n = ctranslate2.get_cuda_device_count()
    except Exception as e:  # noqa: BLE001
        n, err = 0, str(e)
    else:
        err = ""
    if n > 0:
        return "cuda", f"{n} NVIDIA GPU{'s' if n > 1 else ''} found"
    if pref == "cuda":
        return "cpu", "GPU asked for but none usable" + (f" ({err})" if err else "")
    return "cpu", "no NVIDIA GPU found"


def decode_file(path: Path) -> np.ndarray:
    """Any audio or video file to 16 kHz mono float32, with ffmpeg."""
    p = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(path),
                        "-f", "s16le", "-ac", "1", "-ar", str(RATE), "-"],
                       capture_output=True, check=False)
    if p.returncode != 0:
        raise RuntimeError("could not read the audio: " + p.stderr.decode(errors="replace").strip()[-300:])
    return np.frombuffer(p.stdout, dtype="<i2").astype(np.float32) / 32768


def decode_pcm(path: Path) -> np.ndarray:
    return np.fromfile(path, dtype="<i2").astype(np.float32) / 32768


@dataclass
class Seg:
    offset: int   # sample offset in the audio
    ms: int       # wall-clock time of that sample
    length: int


def parse_index(text: str, start_ms: int, n_samples: int) -> list[Seg]:
    segs = []
    for line in text.splitlines():
        p = line.split("\t")
        if len(p) >= 3:
            try:
                segs.append(Seg(int(p[0]), int(p[1]), int(p[2])))
            except ValueError:
                pass
    return sorted(segs, key=lambda s: s.offset) or [Seg(0, start_ms, n_samples)]


def clock(segs: list[Seg], sample: int) -> int:
    """Wall-clock ms of a sample offset."""
    cur = segs[0]
    for s in segs:
        if s.offset <= sample:
            cur = s
        else:
            break
    return cur.ms + (sample - cur.offset) * 1000 // RATE


class Engine:
    """Holds the loaded models. One job at a time."""

    def __init__(self):
        self.lock = threading.Lock()
        self.whisper = None
        self.loaded = ""          # "<model>@<device>/<compute>"
        self.device = "cpu"
        self.device_note = ""
        self.model_name = ""
        self.state = "starting"   # what it is doing, for the portal
        self.error = ""

    def load(self) -> None:
        s = config.load()
        device, why = detect_device(s["device"])
        name = models.resolve_model(s["model"], device)
        compute = "float16" if device == "cuda" else "int8"
        key = f"{name}@{device}/{compute}"
        if key == self.loaded:
            return
        from faster_whisper import WhisperModel
        self.state = f"loading {name}"
        t0 = time.monotonic()
        root = str(models.whisper_dir())
        try:
            try:
                m = WhisperModel(name, device=device, compute_type=compute, download_root=root, local_files_only=True)
            except Exception:  # noqa: BLE001  not downloaded yet
                self.state = f"downloading {name}"
                m = WhisperModel(name, device=device, compute_type=compute, download_root=root)
            if device == "cuda":  # the GPU libraries only fail on first use; try it now
                list(m.transcribe(np.zeros(RATE, dtype=np.float32), language="en")[0])
        except Exception as e:  # noqa: BLE001
            if device != "cuda":
                raise
            log.warning("GPU failed (%s); using the CPU instead", e)
            why = f"GPU found but it did not work ({str(e)[:160]}), so using the CPU"
            device, compute = "cpu", "int8"
            name = models.resolve_model(s["model"], "cpu")
            m = WhisperModel(name, device="cpu", compute_type="int8", download_root=root)
        self.whisper, self.device, self.device_note, self.model_name = m, device, why, name
        self.loaded = f"{name}@{device}/{compute}"
        log.info("loaded Whisper %s on %s (%s) in %.0f s", name, device, compute, time.monotonic() - t0)

    def speakers(self, audio: np.ndarray) -> list[tuple[float, float, int]]:
        """(start s, end s, speaker) turns, or [] if speaker models are missing or audio is short."""
        if len(audio) < RATE * 3 or not models.speaker_models_ready():
            return []
        import sherpa_onnx as so
        threads = max(1, min(8, (os.cpu_count() or 2) - 1))
        cfg = so.OfflineSpeakerDiarizationConfig(
            segmentation=so.OfflineSpeakerSegmentationModelConfig(
                pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(models.segmentation_path())),
                num_threads=threads),
            embedding=so.SpeakerEmbeddingExtractorConfig(model=str(models.embedding_path()), num_threads=threads),
            clustering=so.FastClusteringConfig(num_clusters=-1, threshold=0.8),
            min_duration_on=0.3, min_duration_off=0.5)
        sd = so.OfflineSpeakerDiarization(cfg)
        res = sd.process(audio).sort_by_start_time()
        return [(t.start, t.end, t.speaker) for t in res]

    def transcribe(self, audio: np.ndarray, segs: list[Seg]) -> list[tuple[int, int, str]]:
        """(epoch ms, speaker, text) lines. Speaker 0 = only one voice heard."""
        s = config.load()
        self.load()
        turns = self.speakers(audio) if s["diarize"] else []
        self.state = "transcribing"
        segments, _info = self.whisper.transcribe(
            audio, language=s["language"] or None, beam_size=5, vad_filter=True,
            vad_parameters={"threshold": 0.35},   # default 0.5 missed quiet voices a few steps away
            word_timestamps=True, condition_on_previous_text=False)
        words = []  # (start s, end s, text)
        for seg in segments:
            if seg.words:
                words += [(w.start, w.end, w.word) for w in seg.words]
            elif seg.text.strip():
                words.append((seg.start, seg.end, " " + seg.text.strip()))
            words.append(None)  # segment end: a good place to break a long line

        def who(a: float, b: float) -> int:
            best, spk = 0.0, -1
            for ta, tb, sp in turns:
                if tb < a:
                    continue
                if ta > b:
                    break
                ov = min(b, tb) - max(a, ta)
                if ov > best:
                    best, spk = ov, sp
            if spk < 0 and turns:  # in a gap: nearest turn
                mid = (a + b) / 2
                spk = min(turns, key=lambda t: min(abs(t[0] - mid), abs(t[1] - mid)))[2]
            return spk

        # Group words into lines: new line on speaker change, or at a segment end once long enough.
        lines: list[list] = []  # [start s, speaker, text, level samples]
        for w in words:
            if w is None:
                if lines and len(lines[-1][2]) > 200:
                    lines.append(None)
                continue
            a, b, text = w
            spk = who(a, b)
            last = lines[-1] if lines else None
            if last is not None and last[1] == spk:
                last[2] += text
                last[3] = b
            else:
                if last is None and lines:
                    lines.pop()
                lines.append([a, spk, text, b])
        lines = [ln for ln in lines if ln]

        if s["skip_background"]:
            lines = self._drop_quiet(audio, lines)

        order = list(dict.fromkeys(ln[1] for ln in lines if ln[1] >= 0))
        out = []
        for a, spk, text, _b in lines:
            text = text.strip()
            if not text:
                continue
            label = order.index(spk) + 1 if len(order) > 1 and spk >= 0 else 0
            out.append((clock(segs, int(a * RATE)), label, text))
        self.state = "idle"
        return out

    @staticmethod
    def _drop_quiet(audio: np.ndarray, lines: list) -> list:
        """Leave out a speaker more than 24 dB quieter than the loudest one (a TV across the room).
        14 dB also dropped a person 1.5 m away talking normally."""
        if len(lines) < 2:
            return lines

        def rms(ln):
            x = audio[int(ln[0] * RATE):int(ln[3] * RATE)]
            x = x[x != 0]
            return float(np.sqrt(np.mean(x * x))) if len(x) else 0.0
        levels = [rms(ln) for ln in lines]
        by = {}
        for ln, lv in zip(lines, levels):
            by.setdefault(ln[1], []).append(lv)
        med = {k: float(np.median(v)) for k, v in by.items()}
        ref = max(med.values())
        keep = [ln for ln in lines if med[ln[1]] >= ref * 0.063]
        return keep or lines

    def run_job(self, j: dict) -> None:
        path = Path(j["audio_path"])
        t0 = time.monotonic()
        audio = decode_pcm(path) if j["kind"] == "pcm" else decode_file(path)
        segs = parse_index(j["index_text"], j["start_ms"], len(audio))
        speech_s = sum(sg.length for sg in segs) / RATE if j["index_text"] else len(audio) / RATE
        with self.lock:
            out = self.transcribe(audio, segs)
        db.job_done(j["id"], j["recording_id"], out, round(speech_s, 1), round(time.monotonic() - t0, 1))
        log.info("job %s: %.0f s of audio in %.0f s, %d lines", j["id"], speech_s, time.monotonic() - t0, len(out))
        if not config.load()["keep_audio"]:
            path.unlink(missing_ok=True)


class Worker:
    """Background thread: takes queued jobs from the database, one at a time."""

    def __init__(self, engine: Engine):
        self.engine = engine
        self.wake = threading.Event()
        self.current: dict | None = None
        threading.Thread(target=self._run, name="worker", daemon=True).start()

    def poke(self) -> None:
        self.wake.set()

    def _run(self) -> None:
        try:
            self.engine.load()
            self.engine.state = "idle"
        except Exception as e:  # noqa: BLE001
            log.exception("could not load Whisper")
            self.engine.error = f"Could not load the speech model: {e}"
            self.engine.state = "error"
        while True:
            j = db.next_job()
            if not j:
                self.wake.wait(30)
                self.wake.clear()
                continue
            self.current = j
            try:
                self.engine.run_job(j)
                self.engine.error = ""
            except Exception as e:  # noqa: BLE001
                log.exception("job %s failed", j["id"])
                db.job_failed(j["id"], str(e) or type(e).__name__)
                self.engine.error = f"Last job failed: {e}"
                self.engine.state = "idle"
            finally:
                self.current = None
