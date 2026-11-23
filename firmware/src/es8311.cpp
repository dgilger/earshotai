#include "es8311.h"

namespace es8311 {

static TwoWire *s_wire = nullptr;
static uint8_t s_addr = 0x18;

static bool writeReg(uint8_t reg, uint8_t val) {
  s_wire->beginTransmission(s_addr);
  s_wire->write(reg);
  s_wire->write(val);
  return s_wire->endTransmission() == 0;
}

static bool readReg(uint8_t reg, uint8_t &val) {
  s_wire->beginTransmission(s_addr);
  s_wire->write(reg);
  if (s_wire->endTransmission(false) != 0) return false;
  if (s_wire->requestFrom(s_addr, (uint8_t)1) != 1) return false;
  val = s_wire->read();
  return true;
}

// Read-modify-write: keep bits in `keep`, OR in `set`.
static bool updateReg(uint8_t reg, uint8_t keep, uint8_t set) {
  uint8_t v;
  if (!readReg(reg, v)) return false;
  return writeReg(reg, (v & keep) | set);
}

bool readChipId(TwoWire &wire, uint8_t addr, uint8_t &id1, uint8_t &id2) {
  s_wire = &wire;
  s_addr = addr;
  return readReg(0xFD, id1) && readReg(0xFE, id2);
}

bool beginMic16k(TwoWire &wire, uint8_t addr, MicGain gain) {
  s_wire = &wire;
  s_addr = addr;
  bool ok = true;

  // Reset, then power on the state machine.
  ok &= writeReg(0x00, 0x1F);
  delay(20);
  ok &= writeReg(0x00, 0x00);
  ok &= writeReg(0x00, 0x80);

  // Clocks: all on, MCLK from MCLK pin, not inverted; SCLK not inverted.
  ok &= writeReg(0x01, 0x3F);
  ok &= updateReg(0x06, (uint8_t)~0x20, 0x00);

  // Dividers for MCLK 4.096 MHz / fs 16 kHz (coeff row: pre_div 1, pre_multi x1,
  // adc_div 1, dac_div 1, fs_mode single, lrck 0x00FF, bclk_div 4, osr 0x10/0x10).
  ok &= updateReg(0x02, 0x07, 0x00);
  ok &= writeReg(0x03, 0x10);
  ok &= writeReg(0x04, 0x10);
  ok &= writeReg(0x05, 0x00);
  ok &= updateReg(0x06, 0xE0, 0x03);
  ok &= updateReg(0x07, 0xC0, 0x00);
  ok &= writeReg(0x08, 0xFF);

  // Serial port: slave, I2S format, 16-bit in and out.
  ok &= updateReg(0x00, 0xBF, 0x00);
  ok &= writeReg(0x09, 0x0C);
  ok &= writeReg(0x0A, 0x0C);

  // Power up analog, ADC PGA + modulator, DAC; HP drive; ADC DC-offset cancel; DAC EQ bypass.
  ok &= writeReg(0x0D, 0x01);
  ok &= writeReg(0x0E, 0x02);
  ok &= writeReg(0x12, 0x00);
  ok &= writeReg(0x13, 0x10);
  ok &= writeReg(0x1C, 0x6A);
  ok &= writeReg(0x37, 0x08);

  // Analog mic on MIC1P/MIC1N, max PGA; ADC digital volume.
  ok &= writeReg(0x17, 0xC8);
  ok &= writeReg(0x14, 0x1A);
  ok &= setMicGain(gain);
  return ok;
}

bool setMicGain(MicGain gain) { return s_wire && writeReg(0x16, (uint8_t)gain); }

bool setAdcVolumeDb(float db) {
  const int v = 0xBF + (int)lroundf(constrain(db, -95.5f, 32.0f) * 2.0f);
  return s_wire && writeReg(0x17, (uint8_t)v);
}

}  // namespace es8311
