"""Model files: Whisper (speech to text) and the speaker-label models.

All come from public sources, no account or token needed:
  Whisper (faster-whisper / CTranslate2 format)  Hugging Face, Systran/faster-whisper-*
  pyannote segmentation 3.0 (ONNX)                sherpa-onnx GitHub release
  NVIDIA TitaNet small (ONNX, voice prints)       sherpa-onnx GitHub release

Run to download everything now (the installer does this):
  python -m earshot.models [--model large-v3]
"""

import argparse
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

from . import config

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
SEGMENTATION_URL = f"{GH}/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
EMBEDDING_URL = f"{GH}/speaker-recongition-models/nemo_en_titanet_small.onnx"

WHISPER_MODELS = {
    "large-v3": "Best accuracy. Needs a GPU to be fast (about 3 GB).",
    "large-v3-turbo": "Almost as good as large-v3 and much faster. Best choice without a GPU (about 1.6 GB).",
    "medium.en": "English only, faster on slow computers (about 1.5 GB).",
    "small.en": "English only, fast but makes more mistakes (about 0.5 GB).",
}


def whisper_dir() -> Path:
    return config.MODELS / "whisper"


def segmentation_path() -> Path:
    return config.MODELS / "sherpa-onnx-pyannote-segmentation-3-0" / "model.onnx"


def embedding_path() -> Path:
    return config.MODELS / "nemo_en_titanet_small.onnx"


def speaker_models_ready() -> bool:
    return segmentation_path().exists() and embedding_path().exists()


def _fetch(url: str, dest: Path, label: str) -> None:
    tmp = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "earshot-server"})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if total and sys.stdout.isatty():
                print(f"\r  {label}: {done * 100 // total}% of {total // 1_000_000} MB", end="", flush=True)
    if sys.stdout.isatty():
        print()
    tmp.replace(dest)


def download_speaker_models() -> None:
    config.ensure_dirs()
    if not segmentation_path().exists():
        arc = config.MODELS / "segmentation.tar.bz2"
        _fetch(SEGMENTATION_URL, arc, "speaker turns model")
        with tarfile.open(arc) as t:
            t.extractall(config.MODELS, filter="data")
        arc.unlink()
    if not embedding_path().exists():
        _fetch(EMBEDDING_URL, embedding_path(), "voice print model")


def download_whisper(name: str) -> str:
    from faster_whisper.utils import download_model
    print(f"  Whisper {name}: downloading (this can take a few minutes)…", flush=True)
    return download_model(name, cache_dir=str(whisper_dir()))


def resolve_model(name: str, device: str) -> str:
    if name and name != "auto":
        return name
    return "large-v3" if device == "cuda" else "large-v3-turbo"


def main() -> None:
    ap = argparse.ArgumentParser(description="Download the Earshot models")
    ap.add_argument("--model", default="", help="Whisper model (default: from settings, auto = by GPU)")
    args = ap.parse_args()
    from .asr import detect_device
    s = config.load()
    device, why = detect_device(s["device"])
    name = resolve_model(args.model or s["model"], device)
    print(f"  Device: {device} ({why})")
    download_whisper(name)
    print("  Speaker label models…", flush=True)
    download_speaker_models()
    free = shutil.disk_usage(config.MODELS).free // 1_000_000_000
    print(f"  All models ready in {config.MODELS} ({free} GB disk space left)")


if __name__ == "__main__":
    main()
