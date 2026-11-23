// Minimal ES8311 driver: ADC (mic) path, codec as I2S slave, MCLK from pin.
// Register sequence follows Espressif's esp-bsp es8311 component.
#pragma once
#include <Arduino.h>
#include <Wire.h>

namespace es8311 {

// Mic PGA "gain scale" (ADC_REG16), 6 dB steps.
enum MicGain : uint8_t { GAIN_0DB = 0, GAIN_6DB, GAIN_12DB, GAIN_18DB, GAIN_24DB, GAIN_30DB, GAIN_36DB, GAIN_42DB };

// Reads chip ID registers 0xFD/0xFE (expect 0x83/0x11). Returns false on I2C failure.
bool readChipId(TwoWire &wire, uint8_t addr, uint8_t &id1, uint8_t &id2);

// Initialise for 16 kHz, 16-bit I2S, MCLK = 256 * 16 kHz = 4.096 MHz supplied on the MCLK pin.
// Returns false if any I2C write fails.
bool beginMic16k(TwoWire &wire, uint8_t addr, MicGain gain);

bool setMicGain(MicGain gain);

// ADC digital volume, -95.5..+32 dB in 0.5 dB steps (0 dB = 0xBF).
bool setAdcVolumeDb(float db);

}  // namespace es8311
