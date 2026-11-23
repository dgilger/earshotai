"""MCP endpoint, so AI tools (Claude and others) can list, search and read transcripts.

Address: <public url>/mcp, with the access token in one of these:
  - Authorization: Bearer <token> header (Claude Code, Claude Desktop config, most clients)
  - the address itself: <public url>/mcp/<token> (for apps that only take a URL, e.g. Claude custom connectors)
Read only: AI tools can't change or delete anything.
"""

from datetime import datetime, timedelta

from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings

from . import db

mcp = MCPServer(
    "Earshot",
    instructions=(
        "Earshot holds transcripts of the user's own voice recordings and phone calls, made by their "
        "wearable/phone recorder and transcribed on their own server. Use search_transcripts to find what was "
        "said about a topic, list_transcripts to see recent recordings, and get_transcript to read one in full. "
        "Transcripts come from speech recognition: expect some misheard words. 'Speaker 1/2' labels restart "
        "after each blank line. Times are the server's local time."),
)


def _brief(r: dict) -> dict:
    return {"id": r["id"], "title": r["title"], "started": r["started"], "ended": r["ended"],
            "source": r["source"], "minutes_of_speech": round(r["audio_s"] / 60, 1),
            "status": r["status"], "preview": r["preview"]}


@mcp.tool()
def list_transcripts(days: int = 7, limit: int = 30, date: str = "") -> list[dict]:
    """List recordings, newest first.

    days: how many days back to look (default 7). Ignored when date is given.
    limit: at most this many (default 30, max 200).
    date: one day only, as YYYY-MM-DD (optional).
    """
    limit = max(1, min(limit, 200))
    if date:
        d = datetime.strptime(date, "%Y-%m-%d")
        since, until = d, d + timedelta(days=1)
        rows = db.recordings(limit, since_ms=int(since.timestamp() * 1000), until_ms=int(until.timestamp() * 1000))
    else:
        since = datetime.now() - timedelta(days=max(1, days))
        rows = db.recordings(limit, since_ms=int(since.timestamp() * 1000))
    return [_brief(r) for r in rows]


@mcp.tool()
def search_transcripts(query: str, limit: int = 20) -> list[dict]:
    """Full-text search over all transcripts. Returns matching lines with their recording id, title and time,
    best matches first; matched words are in [brackets]. Use get_transcript with the id to read the whole thing.

    query: plain words; every word must appear in the line (the last word also matches as a prefix).
    """
    return db.search(query, max(1, min(limit, 100)))


@mcp.tool()
def get_transcript(id: str) -> str:
    """The full text of one recording's transcript (from list_transcripts or search_transcripts)."""
    text = db.transcript_text(id)
    return text or f"No recording with id {id!r}."


def http_app():
    return mcp.streamable_http_app(
        streamable_http_path="/mcp", stateless_http=True, json_response=True,
        # The server is reached through a tunnel under its own domain; the access token protects it.
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False))
