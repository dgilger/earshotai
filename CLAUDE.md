# Earshot — DIY AI note taker

A self-hosted wearable note taker. The device records audio, uploads it to a server on the Proxmox homelab, which transcribes it and writes a summary with the local LLM. Nothing goes to a third-party cloud.

## Goal for now
Desk prototype that proves the full loop:
**mic → ESP32-S3 → Wi-Fi upload → server → transcript → summary**

Size, battery life and enclosure come later.

## Repo layout
- `firmware/` — PlatformIO project for the ESP32-S3 (run `pio` commands from here).
  - `include/secrets.h` — gitignored; copy from `include/secrets.example.h`.
- `server/` — upload/transcribe/summarize service (Docker).
- `hardware/` — custom PCB (KiCad 9); `SPEC.md` holds the spec and parts list.
- `android/` — phone app alternative: REC/STOP (optional auto-record on open, off by default), transcribes on the phone with speaker labels (sherpa-onnx: Parakeet only + pyannote, downloaded on first launch), deletes audio, saves each transcript as .txt into a folder picked with Android's folder picker (no Google sign-in; a sync app copies it to Drive). Also transcribes new files from the phone's call recorder folder. Optional own server (Settings → Server) does the transcription instead. No summaries in the app. APK built by the `Android APK` GitHub Actions workflow (cloud sessions can't download the Android SDK). See `android/README.md`.

## Hardware

### Prototype board: Hosyond 2.8" ESP32-S3 touchscreen (ES3C28P)
Confirm the board label reads ES3C28P on arrival.

- ESP32-S3, 8MB PSRAM (OPI), 16MB flash
- Built-in MEMS mic through an **ES8311 audio codec** — configure the codec over I2C before capturing over I2S
- microSD slot (SDIO 4-bit)
- LiPo connector with onboard charging (battery not included)
- 2.8" 240x320 IPS, ILI9341V driver, capacitive touch
- Speaker connector via FM8002E amp
- USB-C for power and programming

Pins, checked against the official schematic and IO allocation table (also in `firmware/include/board_pins.h`):
| Function | GPIO |
|---|---|
| I2C SDA / SCL (ES8311 @ 0x18, FT6336G touch @ 0x38) | 16 / 15 |
| I2S MCLK / BCLK / WS | 4 / 5 / 7 |
| I2S data **in** (mic, codec ASDOUT → ESP32) | 6 |
| I2S data **out** (speaker, ESP32 → codec DSDIN) | 8 |
| Speaker amp enable (low = on) | 1 |
| LCD SCLK / MOSI / MISO / CS / DC | 12 / 11 / 13 / 10 / 46 |
| LCD backlight (high = on) | 45 |
| LCD reset | tied to ESP32 EN, no GPIO |
| Touch INT / RST | 17 / 18 |
| SD CLK / CMD | 38 / 40 |
| SD DATA0–3 | 39, 41, 48, 47 |
| Battery ADC | 9 |
| RGB LED (WS2812B) | 42 |
| Free on headers | 2, 3, 14, 21 (43/44 = UART0) |

- The schematic names the I2S nets from the codec's side (`I2S_DO` = codec ASDOUT). The community BSP README gets DIN/DOUT backwards; trust the schematic.
- USB-C goes directly to GPIO19/20: native USB, no USB-UART chip.

References:
- Official docs, schematic, examples: https://www.lcdwiki.com/2.8inch_ESP32-S3_Display
- Community board support package with pin map: https://github.com/ngttai/esp32_s3_es3c28p

### Spare: ESP32-S3 SuperMini
On hand but has no mic, SD or charger. Not used for this project unless needed for a quick Wi-Fi/upload test.

### Wearable direction (decided 2026-11-01)
- Chip: **nRF52840** (Bluetooth LE only, far lower power than ESP32). Audio goes device → BLE → phone (or a PC receiver script during development) → server. Opus-compress on the device.
- Firmware dev board: **Seeed XIAO nRF52840 Sense** (PDM mic, IMU, LiPo charger, 2 MB flash). Firmware is Nordic nRF Connect SDK (Zephyr), not Arduino. Can be hand-wired with SD NAND storage, a button and a battery.
- Custom board rev A: nRF52840 module, 2 PDM mics, hinged microSD socket (SPI mode), charger, fuel gauge, USB-C, button, JLCPCB-assembled. Battery LP803040 1000 mAh (8 × 30 × 42 mm, JST 1.25 plug: pad 1 = −, pad 2 = +) under a 30 × 50 mm board (top 8 mm overhangs the battery for the antenna and mic holes). Spec and part list: `hardware/SPEC.md`. KiCad 9.
- Dev PC has Bluetooth (hci0), so it can stand in for the phone.
- Reference designs: XIAO nRF52840 Sense schematic; Omi (nRF52840 v1, nRF5340 v2).

## Audio format
16 kHz, 16-bit, mono PCM. Wrap as WAV on the server side.

## Firmware
- Toolchain: PlatformIO CLI, Arduino framework (ESP-IDF acceptable if the ES8311 examples need it).
- Board config must enable 16MB flash and OPI PSRAM.
- Check whether serial is over native USB or a USB-UART chip; if native, add `-DARDUINO_USB_CDC_ON_BOOT=1`.
- Find the port with `pio device list`; flash with `pio run -t upload`.
- If upload fails: hold BOOT, tap RESET, release BOOT, retry.
- `pio device monitor` is interactive — run it with a timeout or in the background so it doesn't hang the session.
- Wi-Fi credentials and server URL in a gitignored `secrets.h`; commit a `secrets.example.h`.

## Screen UI (simple, prototype only)
Record/stop button, elapsed time, Wi-Fi status, upload status, battery level. Turn the backlight down or off while recording to save power.

## Server (Proxmox homelab)
- Receives audio chunks over HTTP.
- Transcription: faster-whisper (WhisperX later for speaker labels).
- Summary: Claude API or the local LLM box on Proxmox, switchable.
- Follow existing self-hosting conventions (Docker, owned infrastructure).
- Currently runs on the dev PC on the LAN (the board's `SERVER_URL` in `secrets.h` points here). Move to Proxmox/Docker later.
- Setup: `cd server && uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt`
- Run: `.venv/bin/python -u earshot_server.py` (port 8000). Saves `data/<time>_<device>.wav` (peak-normalised), then `.txt` + `.json` transcripts.
- Re-transcribe files: `.venv/bin/python transcriber.py data/*.wav`
- Summaries (`summarizer.py`): backend chosen by `SUMMARY_BACKEND` in `server/.env` (gitignored; template `server/.env.example`): `claude` (default, Claude Haiku 5.5, needs `ANTHROPIC_API_KEY`), `local` (any OpenAI-compatible server: `LOCAL_LLM_URL` + `LOCAL_LLM_MODEL`), or `none`. Writes `<name>.summary.md` and a `summary` key in the `.json`. Server starts without summaries if the backend isn't configured.
- Re-summarise: `.venv/bin/python summarizer.py [--backend claude|local] data/*.json`
- Whisper is fed numpy arrays, not file paths: faster-whisper 1.2.1's file decoder breaks with PyAV 19.
- Model `large-v3-turbo` int8 on CPU, about 7 s per 10 s clip (`small.en` is ~2 s but mishears more words). Loads from the local cache only (no Hugging Face call after the first download).

## Self-hosted server (server/earshot/, the main path for the phone app)
- One-command guided installer `server/install.sh` (Debian/Ubuntu, systemd): checks, GPU detection, uv venv in `/opt/earshot`, models, `earshot` service on port 8000, Cloudflare Tunnel or Tailscale Funnel for https without a VPN, phone pairing QR. Clone URL is the `REPO_URL` constant at the top.
- FastAPI app `python -m earshot.main`: phone chunk API (`/api/chunks` + job polling), portal (`earshot/portal/`, plain JS), MCP at `/mcp` (token in Bearer header or `/mcp/<token>`), SQLite + FTS5 in `/var/lib/earshot`.
- Speech: faster-whisper (large-v3 on GPU, large-v3-turbo on CPU, falls back to CPU if CUDA fails) + sherpa-onnx pyannote 3.0/TitaNet speaker labels (GitHub-hosted models, no Hugging Face token).
- App: Settings → Server (or earshot://pair link from the portal QR). With a server set the phone only runs VAD; chunks stay on the phone until the server returns their text. See `server/README.md`.

## Milestones
1. Flash a hello-world: serial output, screen shows text, Wi-Fi connects.
2. Initialize the ES8311 and print mic audio levels to serial/screen.
3. Record a 10s clip and POST it to the server; server saves a WAV that plays back cleanly.
4. Server transcribes the clip with faster-whisper.
5. Server summarizes the transcript with the local LLM.
6. Touch record/stop button; continuous recording in chunks, buffered to SD if Wi-Fi drops.
7. Voice activity detection to skip silence (biggest battery win later).

## Notes
- Recording consent laws vary by state; some require everyone's consent.
