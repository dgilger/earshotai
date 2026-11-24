# Earshot server

Your own transcription server. The phone app sends each recording here; the server turns it into
text with speaker labels, keeps the transcripts, shows them in a web portal, and lets AI tools
(Claude and others) search and read them over MCP. Nothing goes to a transcription company.

```
phone app ──https──► Cloudflare Tunnel / Tailscale Funnel ──► Earshot server (your Linux PC)
 (audio kept until                                             Whisper large-v3 (GPU or CPU)
  the text is back)                                            + speaker labels (pyannote + TitaNet)
                                                               SQLite transcripts + search
                                                               web portal  ·  MCP for AI tools
```

## Install (one command)

On a Linux computer (Ubuntu 22.04/24.04 or Debian 12; a Proxmox VM or container works):

```
curl -fsSL https://raw.githubusercontent.com/dgilger/earshotai/main/server/install.sh | sudo bash
```

While the project is private, download it first and run the installer from the copy:

```
git clone https://github.com/dgilger/earshotai && sudo bash earshotai/server/install.sh
```

Running the installer again on a server that already has Earshot only updates it (no questions;
transcripts, settings and remote access stay). To answer all the setup questions again: `--setup`.

The installer guides you through every step and checks each one:

1. Checks the computer (memory, disk, internet).
2. Installs git, ffmpeg and curl.
3. Looks for an NVIDIA graphics card. Uses it if its driver works; can install the driver on Ubuntu.
4. Downloads Earshot to `/opt/earshot`.
5. Installs the speech software in its own Python environment (your system Python is not touched).
6. Suggests a speech model for this computer and makes the access token.
7. Downloads the models once (1–3 GB, no account or token needed).
8. Starts Earshot as a service (it starts again by itself after a reboot).
9. Optional: an https address for use away from home (see below).
10. Shows a QR code for the phone and the address for AI tools.

Run it again at any time: it continues where it stopped and keeps your transcripts.

Needs: 4 GB memory (8 GB+ better), 8 GB free disk. A GPU is optional (about 10× faster).

## Away from home, without a VPN

The phone needs an https address on the internet. The installer offers two free ways. Neither
needs router changes or port forwarding, and the phone needs no extra app:

| | Tailscale Funnel (recommended) | Cloudflare Tunnel |
|---|---|---|
| Address | `https://earshot.<name>.ts.net` | `https://earshot.your-domain.com` |
| Needs | free Tailscale account | free Cloudflare account + a domain (~$10/year) |
| Setup | sign in once, click Enable | paste one token from the Cloudflare dashboard |
| Privacy | encryption ends on your server | Cloudflare decrypts traffic on its servers, so it could see recordings |

Every request needs your access token. Set it up later with `sudo earshot remote`.
Without either, the phone reaches the server on your home Wi-Fi only.

## Connect the phone

Open the portal (the address the installer shows) → **Connect**, and scan the QR code with the
phone camera. Tap **Connect Earshot** on the page that opens. Or, in the app: Settings → Server,
and type the address and token.

When a server is connected, the phone only records and finds speech. The server does the
transcription. If the server can't be reached, the audio stays on the phone and is sent later.
Without a server, the app transcribes on the phone as before.

## Connect AI tools (MCP)

Portal → **Connect** shows your MCP address: `https://<server>/mcp/<token>`.

- **Claude** (web, desktop, phone): claude.ai → Settings → Connectors → Add custom connector → paste the address.
- **Claude Code**: `claude mcp add --transport http earshot https://<server>/mcp/<token>`
- **Other clients**: `https://<server>/mcp` with the header `Authorization: Bearer <token>`.

Tools (read only): `list_transcripts`, `search_transcripts`, `get_transcript`.
The MCP address has its own read-only token, so it can't be used to upload, change or delete.
If the MCP address gets out, open Connect in the portal and click **Make a new MCP address**. The old address stops working at once; the phone is not affected.

## Security

What protects your transcripts:
- **Tokens.** Every request needs a random 192-bit token. The phone and the portal use the full token.
  AI tools get a separate read-only token. Both are in `/var/lib/earshot` (readable only by the service).
  `sudo earshot new-token` replaces both.
- **Wrong-token limit.** After 30 wrong tokens in 10 minutes from one address, more tries are refused
  (the right token always works).
- **Service.** Runs as its own user `earshot`, with a read-only system, no access to home folders,
  and private files (`umask 077`). Data folder is `700`.
- **Web pages** send strict security headers (no framing, no outside scripts). Uploads have size limits.
- **Audio** is deleted after transcription (unless you turn on "Keep audio files").
- The installer log does not keep GitHub or Cloudflare tokens.

What to know:
- Anyone with the full token can read and delete everything. Treat it like a password. The QR code
  on the Connect page holds it, so don't show that page to others.
- With **Cloudflare Tunnel**, Cloudflare decrypts traffic at its edge. Use **Tailscale Funnel** if that matters.
- On **home Wi-Fi with `http://`**, the token and audio travel unencrypted on your network.
- Transcripts are stored unencrypted on the server disk. Use full-disk encryption (e.g. Proxmox/LUKS)
  if someone could take the disk.
- The AI tool you connect (for example Claude) stores the MCP address and reads transcripts you ask about.

## Updates

The portal shows a dot on **Update** when a newer version is on GitHub (it looks once a day), with the list of
changes. Click **Update now**; Earshot updates itself and restarts. Your transcripts and settings stay.
Or run `sudo earshot update` on the server.

## Everyday commands

```
sudo earshot status      is it running, what is it doing
sudo earshot token       show the access token (to sign in to the portal)
sudo earshot pair        phone QR code and AI address again
sudo earshot remote      set up access from outside your home
sudo earshot update      newest version from GitHub, then restart
sudo earshot logs        follow the log
sudo earshot new-token   new token (phone and AI tools must connect again)
sudo earshot uninstall   remove (asks before deleting transcripts)
```

(In a Proxmox container you are root already: leave out `sudo`.)

## Where things are

| Path | What |
|---|---|
| `/opt/earshot` | code and Python environment |
| `/var/lib/earshot/earshot.db` | transcripts (SQLite with full-text search) |
| `/var/lib/earshot/models` | speech models |
| `/var/lib/earshot/token` | access token |
| `/var/lib/earshot/settings.json` | settings changed in the portal |
| `/etc/earshot/earshot.env` | port, data folder, GPU library path |

Audio is deleted after transcription unless "Keep audio files" is on (portal → Server).

## API (for the phone app)

All need `Authorization: Bearer <token>`.

| | |
|---|---|
| `POST /api/chunks` | multipart: `audio` (16 kHz 16-bit mono PCM), `recording`, `start_ms`, `index`, `device`, `source`. Same chunk twice = same job. |
| `GET /api/jobs/{id}` | `state` queued/working/done/failed; `lines` when done |
| `POST /api/recordings/{id}/finish` | JSON `end_ms`, `label`, `device` |
| `POST /api/upload` | any audio/video file (portal) |
| `GET /api/recordings?q=` | list or search |
| `GET /api/recordings/{id}/text` | transcript as text |

## Develop

```
cd server
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements-server.txt
EARSHOT_DATA=./data-hub .venv/bin/python -m earshot.main      # http://localhost:8000
```

The older `earshot_server.py` (ESP32 prototype, raw PCM to `/upload`) still works on its own.
