# Earshot for Android

Record, transcribe on the phone, save the transcript as a text file in a folder you choose. That's all.
Notes and summaries are made later by Claude, reading the transcripts from Google Drive.

```
REC ─► mic ─► Silero VAD ─► speech chunks ─► who spoke when ─► Parakeet ─► transcript.txt ─► your folder
              (silence       (closed after    (pyannote +                  (audio           (a sync app can
               dropped)       1 min quiet)     TitaNet)                     deleted)         copy it to Drive)
```

All speech processing runs on the phone with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Audio never
leaves the phone and is deleted once its text is saved.

## Using it
- On first launch the speech models (about 530 MB) download by themselves. Progress shows under the button.
- Tap **REC** to record and **STOP** to finish. The notification has a Stop button too.
- **Start recording when Earshot opens** (Settings, off by default): opening the app, for example with a
  gesture, starts a recording at once. It does not start during a phone call.
- Each part is transcribed after a minute of quiet (or 10 minutes of speech). After STOP, Earshot finishes
  the last part and saves one `.txt` file for the whole recording, named `Earshot 2026-11-23 16.52.txt`
  (calls: `Earshot call …`). Speaker labels restart after each blank line.
- Tap a transcript in the list to read it, or **Share** it to any app.
- **Leave out quiet background voices** (Settings, on by default): drops speech much quieter than the main
  speakers, such as a TV or people at the next table. Turn it off if a quiet speaker goes missing.
- No live (word-by-word) transcription: earlier tests were unreliable, and speaker labels need whole parts.

### Getting transcripts into Google Drive (no Google Cloud setup)
Settings → **Transcript folder → Choose folder** opens Android's folder picker. If Google Drive shows in it,
pick a Drive folder directly. On most phones it doesn't, because the Drive app doesn't offer its folders to
other apps. Then pick or make a phone folder such as `Documents/Earshot` and install a sync app, for example
"Autosync for Google Drive" (MetaCtrl), set to copy that folder to a Drive folder.

### Phone calls
Android does not let apps hear calls: during a call other apps get silence. Instead, if your phone's call
recorder saves calls as files (Samsung, Xiaomi, Google Phone in some regions…), choose that folder under
**Settings → Phone calls**. Earshot checks it every 15 minutes and whenever the app is open, transcribes each new
call file (with speaker labels) and saves it like a recording. Only calls recorded after you choose the folder
are used. The call file itself is only read.

## Install
1. Download `earshot-apk` from the latest run of the **Android APK** workflow (GitHub → Actions), unzip it.
2. Copy the `.apk` to the phone and open it (allow installing from that app when asked).
3. Open Earshot (models start downloading), then **Settings → Choose folder**. Allow microphone and
   notifications on first REC, and "unrestricted" battery use.

Every build is signed with the same committed debug key (`debug.keystore`), so new APKs install over
old ones and keep your data. It is not a secure release key.

## What is stored on the phone
All in the app's private storage (`/data/data/app.earshot/files/`):

| Path | What | Kept |
|---|---|---|
| `chunks/<start ms>/` | speech of a recording waiting to be transcribed | deleted as soon as its text is saved |
| `transcripts/<start ms>.txt` | finished transcript not yet saved to your folder | until saved |
| `transcripts/uploaded/` | copies of saved transcripts (for the list) | until the app is uninstalled |
| `models/` | speech and speaker models, downloaded once | |

Old day transcripts from earlier versions move to `transcripts/old/`; old summary and Moonshine models are deleted.

## Models
Downloaded once from the sherpa-onnx GitHub releases on first launch.

| Model | Size | Notes |
|---|---|---|
| Parakeet TDT 0.6B v2, int8 | 480 MB | NVIDIA, English, punctuation |
| Silero VAD | 0.6 MB | skips silence |
| pyannote segmentation 3.0 + TitaNet small | 7 + 40 MB | speaker labels (setting, on by default) |

## Build
CI builds the APK (`.github/workflows/android.yml`). Locally, with Android Studio or the SDK + NDK:
```
cd android && ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```
The `fetchSherpa` Gradle task downloads the prebuilt sherpa-onnx v1.13.8 Android libraries
(`libsherpa-onnx-jni.so`, `libonnxruntime.so`) from its GitHub release; the Kotlin API in
`app/src/main/java/com/k2fsa/sherpa/onnx/` is copied from the same tag and must match it.

## Icons
The launcher, notification and header icons are vectors traced from `hardware/earshot-revA/logo.png`.
After changing the logo, regenerate them:
```
uvx --with opencv-python-headless --with numpy python android/tools/logo_to_vector.py \
    hardware/earshot-revA/logo.png android/app/src/main/res/drawable/
```

## Look
Neon colours on near-black; every shade is ordered (Bayer 8x8) dithering between palette colours, drawn in
chunky pixels. Fonts: Silkscreen and Space Mono (SIL Open Font License, `licenses/`). See `Ui.kt` and `Views.kt`
(record orb with level ring, level scope).

## Code
`app/src/main/java/app/earshot/`
- `MainActivity.kt` REC/STOP screen, auto-start on open, transcript list
- `ListenService.kt` mic capture, microphone foreground service; stays up after STOP until the transcript is done
- `Speech.kt` VAD collector, chunk files, and the chunk pipeline: speaker turns → speech-to-text → lines
- `Processor.kt` finished chunks → text → one transcript per recording, then saves it to the folder
- `Uploader.kt` writes transcripts into the chosen folder; call-check job
- `CallRecordings.kt` watches the call recorder's folder, decodes new files into the same pipeline
- `SettingsActivity.kt`, `Settings.kt`, `Store.kt` (files), `Models.kt` (downloads), `Ui.kt` + `Views.kt` (look)
