"""Transcript summaries (milestone 5), with Claude or a local LLM.

After a clip is transcribed, its text is sent to the configured backend, which returns a title,
a short summary, action items and people mentioned. Results are written next to the WAV:
  <name>.summary.md   readable notes
  <name>.json         gains a "summary" key

Settings come from server/.env (gitignored) or the environment:
  SUMMARY_BACKEND=claude          claude | local | none
  ANTHROPIC_API_KEY=sk-ant-...    for claude
  CLAUDE_MODEL=claude-haiku-5-5   optional override
  LOCAL_LLM_URL=http://<host>:11434/v1   for local: any OpenAI-compatible server
                                         (Ollama, llama.cpp server, vLLM, LM Studio)
  LOCAL_LLM_MODEL=<model name>    for local
  LOCAL_LLM_API_KEY=...           optional, if the local server wants one

Run directly to (re)summarise existing transcripts (--backend overrides SUMMARY_BACKEND):
  .venv/bin/python summarizer.py [--backend claude|local] data/*.json
"""

import argparse
import json
import logging
import os
import time
import urllib.request
from pathlib import Path

from pydantic import BaseModel, ValidationError

log = logging.getLogger("summarizer")

DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"  # $0.10 / $0.50 per 1M tokens: a 10 s clip ~ $0.0002
MIN_WORDS = 3  # skip near-empty transcripts (often Whisper guessing at noise)
ENV_FILE = Path(__file__).resolve().parent / ".env"

SYSTEM = """You turn transcripts from a wearable voice recorder into short notes.
The transcript comes from automatic speech recognition, so expect misheard words, missing
punctuation and fragments; infer the intended meaning where it is clear, and don't invent
details that aren't there. Clips are often short (a sentence or two) and may be partial.

- title: 3-8 words naming what the clip is about.
- summary: 1-3 plain sentences. For a very short clip, one sentence is enough.
- action_items: concrete tasks, reminders or appointments mentioned (who/what/when if stated).
  Empty list if there are none.
- people: names of people mentioned. Empty list if none."""


class Notes(BaseModel):
    title: str
    summary: str
    action_items: list[str]
    people: list[str]


def load_env_file(path: Path = ENV_FILE) -> None:
    """Minimal KEY=VALUE loader so settings can live in server/.env."""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def to_markdown(notes: dict) -> str:
    lines = [f"# {notes['title']}", "", notes["summary"], ""]
    if notes["action_items"]:
        lines += ["## Action items", *[f"- [ ] {a}" for a in notes["action_items"]], ""]
    if notes["people"]:
        lines += [f"People: {', '.join(notes['people'])}", ""]
    lines.append(f"_{notes['backend']} / {notes['model']}_")
    return "\n".join(lines) + "\n"


def user_message(transcript: str) -> str:
    return f"<transcript>\n{transcript}\n</transcript>"


class ClaudeBackend:
    name = "claude"

    def __init__(self):
        import anthropic

        if not os.environ.get("ANTHROPIC_API_KEY"):
            raise RuntimeError(f"ANTHROPIC_API_KEY not set (add it to {ENV_FILE})")
        self.client = anthropic.Anthropic()
        self.model = os.environ.get("CLAUDE_MODEL", DEFAULT_CLAUDE_MODEL)

    def run(self, transcript: str) -> tuple[Notes | None, dict]:
        response = self.client.messages.parse(
            model=self.model,
            max_tokens=4096,
            system=SYSTEM,
            output_config={"effort": "low"},
            output_format=Notes,
            messages=[{"role": "user", "content": user_message(transcript)}],
        )
        usage = {"input_tokens": response.usage.input_tokens,
                 "output_tokens": response.usage.output_tokens}
        if response.stop_reason == "refusal":
            log.warning("Claude declined to summarise (%s)", response.stop_details)
            return None, usage
        return response.parsed_output, usage


class LocalBackend:
    """Any server with an OpenAI-compatible /chat/completions endpoint."""

    name = "local"

    def __init__(self):
        self.url = os.environ.get("LOCAL_LLM_URL", "").rstrip("/")
        self.model = os.environ.get("LOCAL_LLM_MODEL", "")
        if not self.url or not self.model:
            raise RuntimeError(f"set LOCAL_LLM_URL and LOCAL_LLM_MODEL (in {ENV_FILE})")
        self.api_key = os.environ.get("LOCAL_LLM_API_KEY", "")

    def run(self, transcript: str) -> tuple[Notes | None, dict]:
        body = {
            "model": self.model,
            "messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": user_message(transcript)}],
            # Constrained JSON where the server supports it; the prompt asks for the same fields.
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "notes", "schema": Notes.model_json_schema()}},
            "temperature": 0.2,
        }
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        req = urllib.request.Request(f"{self.url}/chat/completions", json.dumps(body).encode(), headers)
        with urllib.request.urlopen(req, timeout=300) as resp:
            data = json.load(resp)
        content = data["choices"][0]["message"]["content"] or ""
        start, end = content.find("{"), content.rfind("}")  # tolerate stray text/code fences
        try:
            notes = Notes.model_validate_json(content[start:end + 1])
        except ValidationError:
            log.warning("local LLM returned unusable JSON: %r", content[:300])
            notes = None
        u = data.get("usage") or {}
        return notes, {"input_tokens": u.get("prompt_tokens"), "output_tokens": u.get("completion_tokens")}


BACKENDS = {"claude": ClaudeBackend, "local": LocalBackend}


def make_summarizer(backend: str | None = None) -> "Summarizer | None":
    """Build a Summarizer from settings; None when SUMMARY_BACKEND=none."""
    load_env_file()
    backend = (backend or os.environ.get("SUMMARY_BACKEND", "claude")).lower()
    if backend == "none":
        return None
    if backend not in BACKENDS:
        raise RuntimeError(f"SUMMARY_BACKEND must be one of {', '.join(BACKENDS)}, none")
    return Summarizer(BACKENDS[backend]())


class Summarizer:
    def __init__(self, backend):
        self.backend = backend
        log.info("summaries via %s (%s)", backend.name, backend.model)

    def summarize(self, transcript: str) -> dict | None:
        """Return notes as a dict, or None if the transcript is too short or no usable result."""
        if len(transcript.split()) < MIN_WORDS:
            return None
        t0 = time.monotonic()
        notes, usage = self.backend.run(transcript)
        if notes is None:
            return None
        return {**notes.model_dump(), "backend": self.backend.name, "model": self.backend.model,
                "summarize_s": round(time.monotonic() - t0, 2), **usage}

    def summarize_result(self, json_path: Path) -> dict | None:
        """Summarise a transcript .json written by transcriber.py and update the files."""
        result = json.loads(json_path.read_text())
        notes = self.summarize(result.get("text", ""))
        md_path = json_path.with_suffix(".summary.md")
        if notes is None:
            result.pop("summary", None)
            md_path.unlink(missing_ok=True)
        else:
            result["summary"] = notes
            md_path.write_text(to_markdown(notes))
        json_path.write_text(json.dumps(result, indent=2) + "\n")
        return notes


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    ap = argparse.ArgumentParser(description="(Re)summarise transcript files")
    ap.add_argument("--backend", choices=[*BACKENDS])
    ap.add_argument("files", nargs="+", type=Path)
    args = ap.parse_args()
    s = make_summarizer(args.backend)
    if s is None:
        raise SystemExit("SUMMARY_BACKEND=none; pass --backend claude|local")
    for f in args.files:
        path = f.with_suffix(".json")
        notes = s.summarize_result(path)
        print(f"== {path.name}")
        print(to_markdown(notes) if notes else "(too short to summarise)")
