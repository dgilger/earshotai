// Earshot — Milestone 3: tap the screen (or send 'r' over serial) to record a 10 s clip and POST it
// to the server. Also: live mic levels (M2), screen + Wi-Fi (M1).
#include <Arduino.h>
#include <ESP_I2S.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <Wire.h>
#include <esp_mac.h>
#include <math.h>

#include "board_pins.h"
#include "display.h"
#include "es8311.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Missing include/secrets.h — copy include/secrets.example.h to include/secrets.h and fill it in."
#endif

static const uint32_t SAMPLE_RATE = 16000;
static const uint32_t WIFI_TIMEOUT_MS = 15000;
static const uint32_t HEARTBEAT_MS = 5000;
static const uint32_t LEVEL_PRINT_MS = 500;
static const size_t FRAMES_PER_READ = SAMPLE_RATE / 10;  // 100 ms
static const es8311::MicGain MIC_GAIN = es8311::GAIN_42DB;  // analog, max
static const float ADC_VOLUME_DB = 10.0f;                     // digital, on top of MIC_GAIN
static const uint32_t CLIP_SECONDS = 10;
static const size_t CLIP_FRAMES = SAMPLE_RATE * CLIP_SECONDS;  // mono 16-bit -> 320 KB
static const uint32_t UPLOAD_TIMEOUT_MS = 20000;

static LGFX lcd;
static I2SClass i2s;
static bool audioOk = false;
static int16_t audioBuf[FRAMES_PER_READ * 2];  // interleaved L/R

// Clip recording (buffer lives in PSRAM). The codec puts the same mono mic signal on
// both I2S slots, so only the left slot is kept.
static int16_t *clip = nullptr;
static size_t clipFrames = 0;
static bool recording = false;
static volatile bool recordRequested = false;

// ---------- screen ----------

static void lcdRow(int row, uint16_t color, const char *fmt, ...) {
  char buf[64];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);

  const int y = 40 + row * 24;
  lcd.fillRect(0, y, lcd.width(), 24, TFT_BLACK);
  lcd.setTextColor(color, TFT_BLACK);
  lcd.drawString(buf, 8, y + 4);
}

static void initDisplay() {
  lcd.init();
  lcd.setRotation(0);  // portrait, 240x320
  lcd.setBrightness(160);
  lcd.fillScreen(TFT_BLACK);
  lcd.fillRect(0, 0, lcd.width(), 32, TFT_NAVY);
  lcd.setFont(&fonts::FreeSansBold9pt7b);
  lcd.setTextColor(TFT_WHITE, TFT_NAVY);
  lcd.drawString("Earshot", 8, 8);
  lcd.setFont(&fonts::Font2);
}

// Level bar: -60 dBFS (empty) .. 0 dBFS (full), with a peak tick.
static void lcdLevelBar(int row, const char *label, float rmsDb, float peakDb) {
  const int y = 40 + row * 24;
  const int x0 = 28, w = lcd.width() - x0 - 8, h = 16;
  auto toPx = [&](float db) {
    float f = (db + 60.0f) / 60.0f;
    return (int)(constrain(f, 0.0f, 1.0f) * w);
  };
  lcd.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  lcd.drawString(label, 8, y + 4);
  const int fill = toPx(rmsDb);
  const uint16_t color = rmsDb > -6 ? TFT_RED : rmsDb > -20 ? TFT_YELLOW : TFT_GREEN;
  lcd.fillRect(x0, y + 4, fill, h, color);
  lcd.fillRect(x0 + fill, y + 4, w - fill, h, TFT_DARKGREY);
  const int pk = toPx(peakDb);
  lcd.drawFastVLine(x0 + min(pk, w - 1), y + 2, h + 4, TFT_WHITE);
}

// ---------- system info / Wi-Fi ----------

static void printChipInfo() {
  Serial.println();
  Serial.println("=== Earshot firmware (ES3C28P) ===");
  Serial.printf("Chip:   %s rev %d, %d core(s) @ %lu MHz\n", ESP.getChipModel(),
                ESP.getChipRevision(), ESP.getChipCores(),
                (unsigned long)ESP.getCpuFreqMHz());
  Serial.printf("Flash:  %lu bytes\n", (unsigned long)ESP.getFlashChipSize());
  Serial.printf("PSRAM:  %lu bytes (%lu free)\n", (unsigned long)ESP.getPsramSize(),
                (unsigned long)ESP.getFreePsram());
  Serial.printf("Heap:   %lu bytes free\n", (unsigned long)ESP.getFreeHeap());
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  Serial.printf("MAC:    %02x:%02x:%02x:%02x:%02x:%02x\n", mac[0], mac[1], mac[2],
                mac[3], mac[4], mac[5]);

  lcdRow(0, TFT_LIGHTGREY, "%s rev %d", ESP.getChipModel(), ESP.getChipRevision());
  lcdRow(1, TFT_LIGHTGREY, "Flash %luMB  PSRAM %luMB",
         (unsigned long)(ESP.getFlashChipSize() >> 20),
         (unsigned long)(ESP.getPsramSize() >> 20));
}

static bool connectWiFi() {
  Serial.printf("Wi-Fi:  connecting to \"%s\"", WIFI_SSID);
  lcdRow(2, TFT_YELLOW, "Wi-Fi: connecting...");
  WiFi.disconnect();  // abort any attempt still in progress
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
    delay(500);
    Serial.print('.');
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("Wi-Fi:  FAILED (status %d)\n", WiFi.status());
    lcdRow(2, TFT_RED, "Wi-Fi: FAILED (status %d)", WiFi.status());
    return false;
  }
  Serial.printf("Wi-Fi:  connected, IP %s, RSSI %d dBm\n",
                WiFi.localIP().toString().c_str(), WiFi.RSSI());
  lcdRow(2, TFT_GREEN, "%s  %s", WIFI_SSID, WiFi.localIP().toString().c_str());
  return true;
}

// ---------- audio ----------

static void scanI2C() {
  Serial.print("I2C:    devices at");
  int n = 0;
  for (uint8_t a = 1; a < 127; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) {
      Serial.printf(" 0x%02X", a);
      n++;
    }
  }
  Serial.println(n ? "" : " (none)");
}

static bool initAudio() {
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 400000);
  scanI2C();

  uint8_t id1 = 0, id2 = 0;
  if (!es8311::readChipId(Wire, I2C_ADDR_ES8311, id1, id2)) {
    Serial.println("ES8311: not responding on I2C");
    lcdRow(4, TFT_RED, "ES8311: no I2C reply");
    return false;
  }
  Serial.printf("ES8311: chip ID %02X %02X (expect 83 11)\n", id1, id2);

  // Start I2S first so MCLK is running when the codec powers up its clocks.
  i2s.setPins(PIN_I2S_BCLK, PIN_I2S_WS, PIN_I2S_DOUT, PIN_I2S_DIN, PIN_I2S_MCLK);
  if (!i2s.begin(I2S_MODE_STD, SAMPLE_RATE, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO)) {
    Serial.println("I2S:    begin FAILED");
    lcdRow(4, TFT_RED, "I2S: begin failed");
    return false;
  }

  if (!es8311::beginMic16k(Wire, I2C_ADDR_ES8311, MIC_GAIN) ||
      !es8311::setAdcVolumeDb(ADC_VOLUME_DB)) {
    Serial.println("ES8311: init FAILED (I2C write error)");
    lcdRow(4, TFT_RED, "ES8311: init failed");
    return false;
  }
  Serial.printf("Audio:  %lu Hz, 16-bit, mic gain %d dB + digital %.1f dB\n",
                (unsigned long)SAMPLE_RATE, (int)MIC_GAIN * 6, ADC_VOLUME_DB);
  lcdRow(4, TFT_GREEN, "Mic: ES8311 %lukHz +%ddB", (unsigned long)(SAMPLE_RATE / 1000),
         (int)MIC_GAIN * 6);
  return true;
}

static float toDb(float v) { return v > 0 ? 20.0f * log10f(v / 32768.0f) : -96.0f; }

// ---------- clip record + upload ----------

static void deviceId(char *out, size_t n) {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  snprintf(out, n, "earshot-%02x%02x%02x", mac[3], mac[4], mac[5]);
}

static void startRecording() {
  if (recording) return;
  if (!clip) {
    Serial.println("REC:    no clip buffer");
    return;
  }
  clipFrames = 0;
  recording = true;
  Serial.printf("REC:    recording %lus...\n", (unsigned long)CLIP_SECONDS);
  lcdRow(3, TFT_RED, "REC 0s / %lus", (unsigned long)CLIP_SECONDS);
}

static bool uploadClip() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("UPLOAD: Wi-Fi not connected");
    lcdRow(3, TFT_RED, "Upload: no Wi-Fi");
    return false;
  }
  const size_t bytes = clipFrames * sizeof(int16_t);
  char id[24];
  deviceId(id, sizeof(id));

  Serial.printf("UPLOAD: POST %u bytes to %s\n", (unsigned)bytes, SERVER_URL);
  lcdRow(3, TFT_YELLOW, "Uploading %uKB...", (unsigned)(bytes / 1024));
  const uint32_t t0 = millis();

  HTTPClient http;
  http.setTimeout(UPLOAD_TIMEOUT_MS);
  if (!http.begin(SERVER_URL)) {
    Serial.println("UPLOAD: bad SERVER_URL");
    lcdRow(3, TFT_RED, "Upload: bad URL");
    return false;
  }
  http.addHeader("Content-Type", "application/octet-stream");
  http.addHeader("X-Sample-Rate", String(SAMPLE_RATE));
  http.addHeader("X-Bits", "16");
  http.addHeader("X-Channels", "1");
  http.addHeader("X-Device", id);
  const int code = http.POST((uint8_t *)clip, bytes);
  const String body = code > 0 ? http.getString() : http.errorToString(code);
  http.end();

  const uint32_t ms = millis() - t0;
  if (code == 200) {
    Serial.printf("UPLOAD: OK in %lums: %s\n", (unsigned long)ms, body.c_str());
    lcdRow(3, TFT_GREEN, "Uploaded %lus clip (%lums)", (unsigned long)CLIP_SECONDS,
           (unsigned long)ms);
    return true;
  }
  Serial.printf("UPLOAD: FAILED (%d) %s\n", code, body.c_str());
  lcdRow(3, TFT_RED, "Upload failed (%d)", code);
  return false;
}

static void appendToClip(size_t frames) {
  const size_t n = min(frames, CLIP_FRAMES - clipFrames);
  for (size_t i = 0; i < n; i++) clip[clipFrames + i] = audioBuf[2 * i];
  clipFrames += n;

  static uint32_t lastShown = UINT32_MAX;
  const uint32_t secs = clipFrames / SAMPLE_RATE;
  if (secs != lastShown) {
    lastShown = secs;
    lcdRow(3, TFT_RED, "REC %lus / %lus", (unsigned long)secs, (unsigned long)CLIP_SECONDS);
  }

  if (clipFrames >= CLIP_FRAMES) {
    recording = false;
    lastShown = UINT32_MAX;
    Serial.println("REC:    done");
    uploadClip();
  }
}

// ---------- audio ----------

static void processAudio() {
  size_t got = i2s.readBytes((char *)audioBuf, sizeof(audioBuf));
  size_t frames = got / (2 * sizeof(int16_t));
  if (!frames) return;
  if (recording) appendToClip(frames);

  double sumL = 0, sumR = 0, meanL = 0, meanR = 0;
  int peakL = 0, peakR = 0;
  for (size_t i = 0; i < frames; i++) {
    meanL += audioBuf[2 * i];
    meanR += audioBuf[2 * i + 1];
  }
  meanL /= frames;
  meanR /= frames;
  for (size_t i = 0; i < frames; i++) {
    double l = audioBuf[2 * i] - meanL, r = audioBuf[2 * i + 1] - meanR;
    sumL += l * l;
    sumR += r * r;
    peakL = max(peakL, abs((int)audioBuf[2 * i]));
    peakR = max(peakR, abs((int)audioBuf[2 * i + 1]));
  }
  const float rmsL = toDb(sqrt(sumL / frames)), rmsR = toDb(sqrt(sumR / frames));
  const float pkL = toDb(peakL), pkR = toDb(peakR);

  lcdLevelBar(5, "L", rmsL, pkL);
  lcdLevelBar(6, "R", rmsR, pkR);

  static uint32_t lastPrint = 0;
  if (!recording && millis() - lastPrint >= LEVEL_PRINT_MS) {
    lastPrint = millis();
    Serial.printf("mic  L rms %6.1f dBFS pk %5d (dc %6.0f) | R rms %6.1f dBFS pk %5d (dc %6.0f)\n",
                  rmsL, peakL, meanL, rmsR, peakR, meanR);
  }
}

// ---------- main ----------

static void startTouchTask();

void setup() {
  // Keep the speaker amp off; we only record.
  pinMode(PIN_AMP_EN, OUTPUT);
  digitalWrite(PIN_AMP_EN, HIGH);

  Serial.begin(115200);
  // Native USB CDC: give the host a moment to open the port so early output isn't lost.
  uint32_t start = millis();
  while (!Serial && millis() - start < 3000) delay(10);

  // Hold the touch controller out of reset (RST is active low).
  pinMode(PIN_TOUCH_RST, OUTPUT);
  digitalWrite(PIN_TOUCH_RST, HIGH);

  initDisplay();
  WiFi.mode(WIFI_STA);
  printChipInfo();
  audioOk = initAudio();
  startTouchTask();
  clip = (int16_t *)ps_malloc(CLIP_FRAMES * sizeof(int16_t));
  if (!clip) Serial.println("REC:    PSRAM alloc FAILED");
  connectWiFi();
  if (audioOk && clip) {
    Serial.println("Ready:  tap the screen or send 'r' to record a clip");
    lcdRow(3, TFT_WHITE, "Tap screen to record %lus", (unsigned long)CLIP_SECONDS);
  }
}

// ---------- touch (FT6336G) ----------
// Polled from its own task every 20 ms so a quick tap isn't missed while the main loop
// blocks on 100 ms audio reads. A new press sets recordRequested.

static bool touchReadReg(uint8_t reg, uint8_t &val) {
  Wire.beginTransmission(I2C_ADDR_FT6336);
  Wire.write(reg);
  if (Wire.endTransmission() != 0) return false;
  if (Wire.requestFrom((uint8_t)I2C_ADDR_FT6336, (uint8_t)1) != 1) return false;
  val = Wire.read();
  return true;
}

static void touchTask(void *) {
  bool wasTouched = false;
  for (;;) {
    uint8_t td = 0;
    // TD_STATUS (0x02) low nibble = number of touch points (valid: 1..2).
    const bool touched = touchReadReg(0x02, td) && (td & 0x0F) >= 1 && (td & 0x0F) <= 2;
    if (touched && !wasTouched) recordRequested = true;
    wasTouched = touched;
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

static void startTouchTask() {
  // Power mode register 0xA5: 0 = active (default after reset is monitor/low-power).
  Wire.beginTransmission(I2C_ADDR_FT6336);
  Wire.write(0xA5);
  Wire.write(0x00);
  if (Wire.endTransmission() != 0) {
    Serial.println("TOUCH:  FT6336 not responding");
    return;
  }
  xTaskCreatePinnedToCore(touchTask, "touch", 3072, nullptr, 2, nullptr, 0);
}

static void pollTriggers() {
  while (Serial.available()) {
    if (Serial.read() == 'r') recordRequested = true;
  }
  if (recordRequested && audioOk) {
    recordRequested = false;
    startRecording();
  }
}

void loop() {
  pollTriggers();
  if (audioOk) processAudio();
  else delay(100);
  if (recording) return;  // keep the heartbeat (and blocking Wi-Fi reconnects) out of a clip

  static uint32_t last = 0;
  if (millis() - last < HEARTBEAT_MS) return;
  last = millis();

  const unsigned long up = millis() / 1000;
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[%lus] Wi-Fi OK, RSSI %d dBm\n", up, WiFi.RSSI());
  } else {
    Serial.printf("[%lus] Wi-Fi down, reconnecting\n", up);
    connectWiFi();
  }
  lcdRow(8, TFT_DARKGREY, "Uptime %lus  RSSI %d", up, WiFi.RSSI());
}
