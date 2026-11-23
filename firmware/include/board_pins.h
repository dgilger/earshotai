// ES3C28P pin map. Source: official schematic + IO allocation table
// (https://www.lcdwiki.com/2.8inch_ESP32-S3_Display), checked 2026-11-01.
#pragma once

// Shared I2C bus: ES8311 codec (0x18) + FT6336G touch (0x38)
#define PIN_I2C_SDA     16
#define PIN_I2C_SCL     15
#define I2C_ADDR_ES8311 0x18
#define I2C_ADDR_FT6336 0x38

// ES8311 I2S. Net names on the schematic are from the codec's side:
// codec ASDOUT (mic/ADC data) -> GPIO6, GPIO8 -> codec DSDIN (DAC/speaker data).
#define PIN_I2S_MCLK    4
#define PIN_I2S_BCLK    5
#define PIN_I2S_WS      7
#define PIN_I2S_DIN     6   // ESP32 input  <- mic
#define PIN_I2S_DOUT    8   // ESP32 output -> speaker
#define PIN_AMP_EN      1   // FM8002E shutdown, low = amp on

// ILI9341V LCD, 4-wire SPI. Reset is tied to the ESP32 EN line (no GPIO).
#define PIN_LCD_SCLK    12
#define PIN_LCD_MOSI    11
#define PIN_LCD_MISO    13
#define PIN_LCD_CS      10
#define PIN_LCD_DC      46
#define PIN_LCD_BL      45  // high = backlight on

// FT6336G capacitive touch
#define PIN_TOUCH_INT   17  // low on touch
#define PIN_TOUCH_RST   18  // low = reset

// microSD, SDIO 4-bit
#define PIN_SD_CLK      38
#define PIN_SD_CMD      40
#define PIN_SD_D0       39
#define PIN_SD_D1       41
#define PIN_SD_D2       48
#define PIN_SD_D3       47

#define PIN_BAT_ADC     9
#define PIN_WS2812      42  // RGB LED (framework already defines PIN_RGB_LED)
#define PIN_BOOT_BTN    0
