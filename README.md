# Earshot

A self-hosted, DIY AI note taker. Earshot records conversations, turns them into text on hardware you own,
and keeps the audio away from third-party clouds.

> **Recording consent:** laws differ by country and state. Some places require every person's consent
> before you record a conversation. Check the law where you use Earshot.

## Parts of the project

| Folder | What it is | Status |
|---|---|---|
| [`android/`](android/README.md) | Android app: record, transcribe on the phone with speaker labels, save each transcript as a `.txt` file in a folder you choose. Audio is deleted after. | Works; APK built by GitHub Actions |
| [`server/`](server/) | Python service: receives audio over HTTP, transcribes with faster-whisper, optional summary (Claude API or any OpenAI-compatible local LLM). | Desk prototype |
| [`firmware/`](firmware/) | PlatformIO firmware for an ESP32-S3 board (ES8311 codec mic, touchscreen): record and upload to the server. | Desk prototype |
| [`hardware/`](hardware/SPEC.md) | Wearable rev A: nRF52840 board in KiCad 9, with JLCPCB fab files, plus a sealed 3D-printable enclosure (CadQuery). | Designed, not built |

## Quick start

**Android app.** Open the **Actions** tab, select the latest **Android APK** run, download `earshot-apk`,
and install the APK on an arm64 Android 10+ phone. See [`android/README.md`](android/README.md).

**Server.**
```sh
cd server
uv venv --python 3.12 .venv
uv pip install --python .venv/bin/python -r requirements.txt
cp .env.example .env        # choose a summary backend, or SUMMARY_BACKEND=none
.venv/bin/python -u earshot_server.py   # listens on port 8000
```

**Self-hosted server.** Install, web portal, remote access and setup details are in
[`server/README.md`](server/README.md).

**Firmware.** Copy `firmware/include/secrets.example.h` to `secrets.h`, fill in your Wi-Fi and server URL,
then run `pio run -t upload` from `firmware/`.

## Privacy

- The Android app transcribes on the phone. No audio or text leaves the phone except through the folder you pick.
- The server runs on your own machine. Summaries go to the Claude API only if you select that backend;
  select `local` or `none` to keep everything on your network.
- Secrets (`secrets.h`, `.env`) are gitignored. Do not commit them.

## License

MIT, see [LICENSE](LICENSE). Some files keep their own licenses:
- `android/app/src/main/java/com/k2fsa/sherpa/onnx/`: from [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0.
- `android/app/src/main/res/font/`: Silkscreen and Space Mono, SIL Open Font License (see `android/licenses/`).
- `hardware/earshot-revA/lib/`: footprints and 3D models imported from LCSC/EasyEDA with easyeda2kicad.
