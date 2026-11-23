# Earshot custom board — rev A spec

Status: **draft, parts selection in progress.** Nothing ordered.

## Goal
Smallest practical wearable recorder that JLCPCB can fully assemble, so copies are repeatable.
Same circuit family as the Seeed XIAO nRF52840 Sense (firmware dev board), plus storage, a
second mic, a button and a fuel gauge.

Audio path: mics → nRF52840 (Opus encode) → microSD card buffer (SPI mode) → Bluetooth LE → phone/PC → server.

## Block diagram
```
 USB-C ──VBUS──┬──────────────► nRF52840 VBUS (USB data D+/D-)
               └─► charger ──► LiPo (protected cell, JST-SH 2-pin or pads)
                   BQ25101         │
                                   ├─► fuel gauge (I2C) ──► nRF
                                   └─► 3.0 V LDO (low Iq) ──► 3V0 rail
                                                              │
     ┌────────────────────────────────────────────────────────┤
     │                     │                     │            │
  nRF52840 module     2× PDM mic (stereo:   microSD socket  button, LED,
  (PCB antenna)       shared CLK/DATA,      SPI mode,      SWD pads
                      L/R select pin)       power-gated
```

## Parts
Stock and price checked against LCSC (= JLCPCB assembly library) on 2026-11-01. Stock moves; recheck before ordering.

| Function | Part | LCSC | Stock / unit price | Status | Notes |
|---|---|---|---|---|---|
| MCU + radio | Ebyte E73-2G4M08S1C (nRF52840) | C356849 | not checked (see note) | **picked (2026-11-05)** | 13 × 18 mm, PCB antenna, 43 pads (castellated + inner row), no 32.768 kHz crystal inside. Replaced the Raytac MDBT50Q-1MV2 (not on LCSC). Stock could not be read from the cloud session on 2026-11-05 and was 0 on 2026-11-01: **check before ordering** |
| MCU + radio (old) | Raytac MDBT50Q-1MV2 (nRF52840) | — (global sourcing) | DigiKey/Mouser | replaced 2026-11-05 | 10.5 × 15.5 × 2.05 mm, chip antenna, pre-certified. Not on LCSC: order via JLCPCB Global Sourcing (DigiKey/Mouser, ~9–15 days, no cancel/return) |
| Mics ×2 | MEMSensing MSM261DDB019 | C51928210 | 5324 / $0.72 | **picked** | PDM, **bottom port** (PCB sound hole), LGA-5 3.5 × 2.65 mm, 1.6–3.6 V, L/R select pin, −26 dBFS, 66 dB SNR. See clock note below |
| Storage | SOFNG TF-002X-H18 microSD socket, hinged lid | C125814 | 4,017 / from $0.056 (LCSC page, 2026-11-05) | **picked (2026-11-05)** | SMD, top side, 14.5 × 13.6 × 1.8 mm, flip-up hinged lid (no spring eject), no card-detect switch. Same body, pinout and land pattern as the Molex 47219-2001 (C164170). Card is user-supplied (any microSD, SPI mode) |
| Storage (old) | Crystal Flash FLSD064GDUMT SD NAND, 8 GB | C56204104 | not checked | replaced 2026-11-05 by the microSD socket | LGA-16 9 × 12.5 mm, SD/SPI, 2.7–3.6 V, 54 mA max / 40 mA average operating, 135 µA standby, built-in controller (FTL, ECC), so firmware uses a FAT filesystem or raw sectors. ~1,100 h of 16 kbps Opus. **Check stock before ordering** |
| Storage (old) | 3× Winbond W25N02KVZEIR SPI NAND, 256 MB each (768 MB) | C3010298 | 1909 / $1.83 | replaced 2026-11-05 | WSON-8 8 × 6 mm, on-chip ECC + bad-block table, ~105 h of 16 kbps Opus total. Replaced the Zetta ZDSD02GLGEAG SD NAND ($8.60 for 256 MB). Raw NAND: firmware keeps a bad-block-aware ring buffer (no FTL needed for append-only audio) |
| Charger | TI BQ25101YFPR | C478468 | 5054 / $0.82 | picked | 250 mA max, 6-ball WCSP, same family as the XIAO |
| Fuel gauge | Maxim MAX17048G+T10 | C2682616 | 23633 / $2.11 | **picked** | DFN-8 2 × 2 mm, I2C. Earlier "not on JLCPCB" was wrong; it is an extended part |
| 3.0 V regulator | TI TPS7A0230PDBVR | C3747031 | 30390 / $0.32 | picked | 200 mA, 25 nA Iq, SOT-23-5 with enable. DSBGA-4 C3747620 if space is tight |
| Storage power switch | AOS AO3401A P-FET | C15127 | 1.2 M / $0.10 | picked | High-side switch on the card's VDD rail (`SD_3V0`), gate pull-up to 3V0, driven by a GPIO |
| USB-C | Hroparts TYPE-C-31-M-12 (16-pin) | C165948 | 179k / $0.19 | picked | USB 2.0 only, 2× 5.1 kΩ CC pull-downs (C23186) |
| USB ESD | ST USBLC6-2SC6 | C7519 | 40k / $0.18 | picked | D+/D−/VBUS |
| Battery connector | SHOU HAN SH-MX1.25-2PWT (1.25 mm, 2-pin, SMD R/A) | C53477378 | 1320 / $0.03 | picked | Mates the cell's "JST 1.25" (PicoBlade-style) plug. Pin 2 = +, pin 1 = − (matches jljlup cell). Meter before first plug-in |
| Button | XKB TS-1187A-B-A-B | C318884 | 799k / $0.02 | picked | 5.1 × 5.1 mm. Swap for a side-actuated switch once the enclosure exists |
| LED | single indicator LED, 0603/0805 | basic part | — | pick at schematic | |
| Programming | Tag-Connect TC2030-NL footprint (SWD) | — | — | — | No part placed; flash UF2 bootloader once, then USB |

### Storage choice (2026-11-05, later): microSD socket
The soldered SD NAND is replaced by a hinged microSD socket (J4, SOFNG TF-002X-H18): any size of card, swappable,
and the card is just a normal SD card in SPI mode, so the firmware is the same as for the SD NAND. No card-detect
switch on this socket; firmware finds the card by initialising it. The card is reached only with the board out of
the case (see Enclosure).

### Storage choice (2026-11-05, superseded)
One 8 GB SD NAND (FLSD064GDUMT) in SPI mode replaces the three W25N02KV chips: 10× the space, one chip select, and the
chip's own controller handles wear levelling and bad blocks, so firmware can use Zephyr's SD-over-SPI disk driver.

### Storage choice (2026-11-02, superseded)
Switched from SD NAND to SPI NAND to cut cost: 256 MB for $1.83 instead of $8.60. Bigger SPI NAND on LCSC/JLC was
poor value on 2026-11-02 (GD5F4GM8 512 MB $18.85 with 1 in stock; XTX XT26G04D 512 MB ~$24), so the board carries
three 256 MB chips on one SPI bus with separate chip selects. Goal: storage roughly matching battery life
(~125–200 h estimated). 768 MB ≈ 105 h at 16 kbps Opus, ≈ 140 h at 12 kbps, more with voice activity detection.
Second source with the same WSON-8 8 × 6 footprint: GigaDevice GD5F2GQ5UEYIGR (C2880463), check pinout before swapping.

### SD NAND options considered earlier (all LGA-8, 2.7–3.6 V; LCSC, 2026-11-01)
In stock:
| Part | Capacity | LCSC | Package | Stock | Price (1 pc) |
|---|---|---|---|---|---|
| Zetta ZDSD512MLGEAG | 64 MB | C22379765 | 6 × 8 | 560 | $6.98 |
| Zetta ZDSD01GLGEAG | 128 MB | C2830323 | 6 × 8 | 405 | $7.58 |
| CS CSNP1GCR01-BOW | 128 MB | C2691593 | 6 × 8 | 3023 | $7.88 (25 MHz) |
| **Zetta ZDSD02GLGEAG** | **256 MB** | **C2875853** | 6 × 8 | 1840 | **$8.60** |
| MK MKDV1GIL-AS | 128 MB | C726726 | 6 × 8 | 83 | $8.69 |
| Zetta ZDSD01GLGIAG | 128 MB | C5349317 | 6 × 8 | 487 | $9.45 (industrial) |
| MK MKDV4GCL-ABB | 512 MB | C7500178 | 6 × 8 | 4589 | $14.35 |
| Zetta ZDSD16GLGIAG | 2 GB | C19727141 | 6.2 × 8 | 1 | $18.38 |
| MK MKDV4GIL-AST | 512 MB | C26159626 | 6 × 8 | 1687 | $18.95 |
| MK MKDV8GIL-AST | 1 GB | C26159627 | 6 × 8 | 1968 | $29.24 (min 3) |
| CS CSNP32GCR01-AOW | 4 GB | C2841139 | 6.2 × 8 | 313 | $46.08 |
| MK MKDV32GCL-STP | 4 GB | C7500179 | 6.6 × 8 | 509 | $49.64 |
| MK MKDV16GIL-STPA | 2 GB | C20607687 | 6.6 × 8 | 593 | $50.30 |

Out of stock (old pre-shortage prices; don't expect them back at these prices): MK MKDV32GCL-STH 4 GB $9.19, MK MKDV8GIL-AS 1 GB $9.14, MK MKDV4GIL-AS 512 MB $4.83,
MK MKDV2GIL-AS 256 MB $4.42, XTX XTSD04GLGEAG 512 MB $7.30, CS CSNP4GCR01 512 MB $3.48–5.30, Zetta ZDSD04G/08G.

Correction: the earlier "XTSD04GLGEAG, 4 GB" was wrong; it is 4 **Gbit** (512 MB). Capacity at 16 kbps Opus (≈ 7.2 MB/hour):
128 MB ≈ 17 h, 512 MB ≈ 70 h, 1 GB ≈ 140 h, 4 GB ≈ 570 h. The SD NAND is a buffer between phone syncs, so 128 MB
covers a full day of continuous recording, and more with voice activity detection.
Footprint: lay out for 6 × 8 mm LGA-8 and confirm the pinout matches across Zetta/MK/CS/XTX datasheets so parts can be swapped at order time. 6.6 × 8 mm parts (MK -STP) need checking against the pads.

## Circuit (rev A schematic plan)
KiCad project: `hardware/earshot-revA/`. Part library `lib/earshot.*` is imported from LCSC with
`uvx easyeda2kicad --full --lcsc_id=Cxxxx --output lib/earshot`, then upgraded to KiCad 9 format.
The Tag-Connect comes from KiCad's stock libraries. The E73-2G4M08S1C and FLSD064GDUMT symbols and footprints were drawn
by hand (2026-11-05): E73 pad numbers and functions from the Ebyte manual's pin table, pad centres matching
hlord2000/nordic-lib-kicad (CERN-OHL-P, also the STEP model) and the SnapEDA land pattern; FLSD064GDUMT from its
datasheet's Figure 1 (pinout) and Figure 2 (pad dimensions).

**Power**
- USB-C VBUS → USBLC6 → `VBUS` net → BQ25101 IN (1 µF) and module VBUS pin (4.7 µF, for the USB PHY).
- CC1/CC2: 5.1 kΩ each to GND (5 V sink, USB 2.0 only). D+ and D− from both plug orientations are tied together.
- BQ25101: ISET 680 Ω (C25130) → ~200 mA charge (K = 135 AΩ) → ~5–6 h full charge of the 1000 mAh cell.
  Worst-case dissipation ≈ (5 − 3.0 V) × 0.2 A = 0.4 W in a 6-ball WCSP: give it copper and vias; the IC's thermal regulation folds back current if it gets hot. TS → 10 kΩ to GND (no NTC).
  OUT → `VBAT` (1 µF). Battery plugs into J3, a 1.25 mm PicoBlade-compatible ("MX1.25", sold as "JST 1.25") 2-pin SMD
  right-angle header, SHOU HAN SH-MX1.25-2PWT (C53477378); genuine Molex 53261-0271 (C189700) is out of stock.
  **J3 pin 1 = GND, pin 2 = VBAT** (J3 at x 122.1, y 140, moved inboard 2026-11-01 so the plug fits inside the case), from the jljlup polarity drawing: looking down on the board, plug latch up, wires
  toward you, black (−) is left over pad 1 and red (+) is right over pad 2. Silkscreen "+" beside pad 2. Polarity on
  these plugs is not standardised between sellers: **meter the cell and check red lands on pad 2 before first plug-in**
  (reverse polarity kills the charger and LDO). Solder pads TP1/TP2 stay in parallel. ~CHG (open drain) → nRF GPIO, using the nRF's internal pull-up.
- TPS7A0230: IN = VBAT, EN = VBAT (always on), OUT → `3V0` (1 µF in/out).
- Module: VDD and VDDH both on 3V0 (nRF "normal voltage" mode), 10 µF + 100 nF. DCCH not connected.
- MAX17048: VDD and CELL on VBAT (100 nF), CTG and QSTRT to GND, ~ALRT → GPIO, I2C pull-ups 10 kΩ to 3V0.
- microSD power: AO3401A high-side on 3V0 → `SD_3V0` (feeds J4 VDD). Gate 100 kΩ pull-up to 3V0, ~SD_PWR GPIO pulls it
  low. SD_3V0: 10 µF (C9) + 100 nF (C10) just right of the socket body, the closest spot to J4 pin 4 (the socket's
  signal pads sit under its body). J4 TF-002X-H18 in SPI mode: pin 1 DAT2, 2 DAT3/CD (~CS), 3 CMD (MOSI), 4 VDD, 5 CLK,
  6 VSS, 7 DAT0 (MISO), 8 DAT1, four shield pads (9) GND. No card-detect switch on this socket.
  SD_CLK goes through R13 (0 Ω, place for a 22–33 Ω damping resistor if the card line rings) to `SD_CLK_N`.
  10 kΩ pull-ups to SD_3V0: R8 CMD, R9 DAT0, R10 DAT1, R11 DAT2, R14 DAT3/~CS. Pull-ups go to SD_3V0, not 3V0, so
  nothing powers the card through them when it is off; firmware drives the SPI pins low or high-Z before switching it off.
- 3V0 budget (TPS7A0230, 200 mA), 2026-11-05 with the microSD card: card write peaks ~100 mA (SD spec allows up to
  100 mA in default-speed/SPI mode) + radio TX ~17 mA at +8 dBm with DC/DC (~33 mA if DC/DC is off) + CPU/peripherals
  ~6 mA + mics ~1.5 mA + LED ~1 mA + gauge/pull-ups ~1 mA ≈ 145 mA worst case, 72 % of the LDO rating. Dropout at
  145 mA is ~0.15–0.2 V, so 3V0 holds down to a ~3.2 V cell. **No change needed.** Microsecond spikes above that
  (card insertion, block erase) come out of C9 (10 µF) and the module's 10 µF. Switching SD_3V0 on charges 10.1 µF
  through Q1; enable it before starting the radio, and don't run the radio at full power while the card initialises
  if you ever see brown-outs.

**Audio**
- 2× MSM261DDB019 on 3V0 (100 nF each); shared PDM_CLK and PDM_DATA. Mic 1 L/R → GND, mic 2 L/R → 3V0.
  Sleep is clock-stop (< 50 kHz → 1 µA), so the mics are not power-gated.

**Clocks:** 32.768 kHz crystal Epson Q13FC13500004 (C32346, 12.5 pF) on P0.00/P0.01 with 2× 20 pF (tune at bring-up).

**nRF52840 pin map** (E73-2G4M08S1C, 2026-11-05; PDM_CLK, I2C_SDA, LED and ~CHG kept their GPIOs)
| Signal | nRF pin | E73 pad | Rev A before (MDBT50Q) |
|---|---|---|---|
| PDM_CLK | P1.00 | 36 | P1.00 |
| PDM_DATA | P0.13 | 33 | P0.16 |
| I2C_SDA | P0.07 | 22 | P0.07 |
| I2C_SCL | P0.08 | 16 | P0.27 |
| GAUGE_~ALRT | P0.05 | 15 | P0.11 |
| ~CHG | P0.17 | 30 | P0.17 |
| SD_CLK / SCK (via R13) | P0.28 | 4 | P0.20 |
| SD_CMD (MOSI) | P0.02 | 7 | P0.22 |
| SD_DAT0 (MISO) | P0.03 | 3 | P0.24 |
| SD_DAT3 (~CS) | P0.29 | 8 | P0.21 (U5); P0.31 / P0.15 for U8 / U9, removed |
| SD card detect | — | — | none: the TF-002X-H18 has no switch |
| ~SD_PWR | P1.10 | 2 | P0.19 |
| BUTTON (to GND, internal pull-up) | P0.15 | 28 | P0.29 |
| LED (sinks via 1 kΩ from 3V0) | P0.06 | 14 | P0.06 |
| XL1 / XL2 (32 kHz) | P0.00 / P0.01 | 11 / 13 | same |
| ~RESET | P0.18 | 26 | same |
| SWDIO / SWDCLK | — | 37 / 39 | — |
| USB D+ / D− / VBUS | — | 31 / 29 / 27 | — |
| VDD, VDDH (3V0) / DCCH (n.c.) / GND | — | 19, 23 / 25 / 5, 21, 24 | — |

SPI to the microSD (J4) uses P0.02 MOSI/CMD, P0.03 MISO/DAT0, P0.28 SCK, P0.29 ~CS/DAT3 (left edge of the module). Nordic's low-frequency-only pins
(P1.10, P1.13, P1.04, P1.06 on this module) carry only ~SD_PWR. Unused: P0.04, P0.12, P0.20, P0.22, P0.24, P0.26,
P0.30, P0.31, P1.02, P1.04, P1.06, P1.09, P1.11, P1.13.
The NFC pins (P0.09/P0.10) are unused. P1.00 is also SWO, so the SWO pin on the Tag-Connect is not connected.

**Passives:** 0402 basic parts (10 µF and 4.7 µF in 0603); LCSC numbers are in each symbol's `LCSC Part` field.

**Silkscreen:** Earshot logo on B.SilkS with name and rev; back side is otherwise empty. Footprint
`earshot:Earshot_Logo_10mm` (7.6 × 10 mm, strokes ~0.8 mm) traced from `earshot-revA/logo.png` by
`hardware/tools/logo_to_footprint.py`; place it on B.Cu so KiCad mirrors it to read correctly from the back.

**Debug / test:** Tag-Connect TC2030-NL (3V0, SWDIO, ~RESET, SWDCLK, GND). Test pads on VBUS, VBAT, 3V0, SD_3V0, GND.

## Battery (decided 2026-11-01)
LP803040 protected LiPo (PCM on the cell), 1000 mAh, 8 × 30 × 42 mm (seller drawing), 60–70 mm leads, "JST 1.25" plug,
~$4 (jljlup). Sits under the board. **Board outline 30 × 50 mm** (decided 2026-11-01): the battery covers the bottom
42 mm; the top 8 mm strip has nothing under it and holds the module's antenna end (E73 antenna keep-out, no metal below)
and both bottom-port mic sound holes. Device ≈ 30 × 50 × 10 mm. Roughly a week of continuous recording at an estimated 5–8 mA average
(to measure on the XIAO). Storage (a microSD card; 8 GB ≈ 1,100 h at 16 kbps) is far more than the battery needs between syncs.

## Enclosure (final design 2026-11-04; concept notes from 2026-11-01 below)
- **Final design: `enclosure/`** (CadQuery source, STEP/STL, sections, assembly steps and bought parts in
  `enclosure/README.md`). Outside **36.6 × 52.6 × 17.8 mm**, ~13 g printed. Three printed parts: front shell, back plug,
  button plunger. Changes from the concept below:
  - Seal is a **radial O-ring (stock 48 × 1.5 mm)** on a ring of the back plug, held by 4 snap latches. **No screws**: the board
    has no mounting holes, and corner screw bosses outside the board would have grown the case ~6 mm each way.
  - Board sits in a 0.15 mm-clearance pocket in the front shell, clamped between crush ribs on its top-face edge strips
    and the back plug's ring top, side tabs and mic columns. Battery in a ribbed cradle with a foam pad above it.
  - Button and LED light pipe are printed plungers, each sealed by a 4 × 2 × 1 mm O-ring. No glue anywhere.
  - Mics: 4 × 2 × 1 mm O-ring face seal from a duct column to each PCB sound hole, 0.9 mm duct out the top face, ePTFE membrane inside
    the wall. ~9 mm path, resonance above the speech band.
  - USB-C opening is the plug shell + 0.2 mm (8.65 × 2.85), receptacle face 1.35 mm below the surface. The receptacle
    itself isn't waterproof, so that port is the one leak path left.
  - Magnets are in the back floor at KiCad x ≈ 102.5/127.5, y ≈ 115 (beside and well below the antenna).
  - **microSD socket J4 (2026-11-05):** body 1.8 mm tall on the left edge (KiCad x 100.05–114.55, y 119.45–133.05);
    the front wall's underside (CEIL_Z) leaves **1.9 mm above it**. The left-edge crush rib that sat at KiCad y 125 moved
    to y 114 (between the parts in the old U5 spot); `--check` is back to the two intended interferences. The hinged
    lid stands ~12 mm above the board when open, and the board sits face-down in the front shell, so the card is
    changed with the board out of the case (open the back, lift out the cell and the board). A card slot through the
    left wall isn't possible with a hinged socket; it would need a push-pull socket and a sealed door.
  - **Battery moves 1.75 mm toward the top** (cell spans KiCad y 106.25–148.25) to make room for the seal below the USB-C.
- **Worn with a magnet clip at the top edge**: hangs from the top hem of a pocket or shirt, so it stays upright and
  the mic vents point at the pocket opening. Two 10 × 5 × 2 mm N52 bar magnets recessed in the case back in the **top
  corners**, either side of the antenna keep-out (x 106.5–123.5), about 2.5 mm beside it and ~10 mm below it. On the
  other side of the fabric: a plastic bar (about 34 × 11 × 3 mm) with matching magnets only at its ends, so no metal
  crosses the antenna. Test the hold on real pockets; thick fabric needs stronger magnets. (Considered and dropped: a
  MagSafe phone mount; magnets near the USB end.)
- **Stacked layout** (battery under the board): outside about **36 × 54 × 17.7 mm**, ~37 g plus a ~4 g magnet bar.
  Walls 1.6 mm, back 2.4 mm for the magnet recesses. Worn upright: mic vents at the top toward the mouth, USB-C at the
  bottom, button and LED on the front. **The battery plugs into J3**: J3 sits 4.5 mm in from the right board edge
  (x 122.1, y 140, opening facing the edge) so the mated plug stays within the board outline; the wires go over the
  right edge and down a 1.2 mm side gap to the cell. BAT+/BAT− pads remain as a test/backup point only.
- **As watertight as practical** (concept; see the final design above): O-ring + 4 screws; button pressed through a thin flexible spot (no hole); LED through a
  0.5 mm thin patch; mics via two 1 mm vents covered inside by ePTFE acoustic membranes; USB-C the only opening, with a
  tethered silicone plug. Print dense (SLA/MJF, or FDM at 100% infill + sealing coat).
- Battery stays the jljlup LP803040 (8 mm). A 5–6 mm cell would cut ~2–3 mm of thickness if a cheap one turns up.
- Mockup: https://claude.ai/artifact/9WQ2FG1iLQfJi91uw55pR4
- No rev A board change needed. A later revision could replace USB-C with sealed magnetic pogo charging contacts.

## Design notes
- **Antenna:** module on a board edge, antenna overhanging or with a full copper keep-out under
  and around it (E73: 2 mm beyond the module sides and top, all copper layers, down to 3.5 mm below the antenna end). Nothing metal near it in the enclosure.
- **Board:** 4-layer, 0.8 mm thick (thinner wearable), single-sided assembly (cheaper) unless size
  forces both sides. Outline 30 × 50 mm: battery under the lower 42 mm, antenna + mic holes over the free top 8 mm.
- **Mics:** both on the PDM bus in stereo (one L/R pin high, one low). Spaced ~20–30 mm apart (the larger board allows it) to
  allow beamforming/noise reduction later; one mic is enough to start. Bottom-port: 0.5–1 mm
  sound hole through the PCB under each mic, no copper/mask in the hole, gasket to the enclosure.
- **PDM clock (MSM261DDB019):** standard mode needs 1.1–4.8 MHz, low-power mode 150–900 kHz, so
  the nRF52840 default of 1.032 MHz falls in the gap between them. Use 1.280 MHz with ratio 80 for 16 kHz output.
- **Power:** cell → LDO → 3V0 rail for everything. Check whether the module exposes the nRF's
  VDDH pin (could run the nRF straight off the cell) — decide during schematic.
- **Bring-up aids on rev A:** test points on VBAT, 3V0, VBUS, SWD; LED; reset via SWD.

## Layout notes (rev A, 2026-11-01)
- Stackup: F.Cu parts + GND pour | In1 solid GND plane | In2 signals | B.Cu signals + GND pour. 0.8 mm, 4 layers.
  Tracks 0.15 mm signal / 0.3 mm power; vias 0.5/0.3 signal, 0.6/0.3 power (all 0.3 mm drill, JLCPCB standard; 0.25 mm dropped 2026-11-04); ~105 GND stitching vias on a 2 mm grid.
- Antenna: E73 footprint keep-out (all copper layers) x 108–125, y 100–103.5 (board coordinates; the old MDBT50Q board keep-out was removed 2026-11-05).
- Routing: Freerouting for the bulk, then hand fixes: charger WCSP fanout (0.2 mm stubs, locked), crystal re-placed
  (Y1 turned 180°, C13/C14 GND pads facing the pour) and re-routed short on F.Cu, and the last few nets finished with a
  small grid router (scratchpad tool, not kept). No via-in-pad anywhere.
- Footprint fixes vs. LCSC originals: USB-C locating holes and mic sound holes made NPTH; mic GND ring merged into one
  annular pad; `earshot-revA.kicad_dru` allows 0.1 mm between pads inside J1 (VBUS/GND pins of that footprint).
- Storage switch (2026-11-02/04): U5 re-footprinted to WSON-8 8 × 6 at the old SD NAND spot; U8 (left) and U9 (right)
  added beside the module's lower half, below the antenna keep-out; C15/C16 decouple U8/U9. Their nets, plus USB D+/D− and
  a few 3V0 segments that crossed the new parts, were re-routed with Freerouting (0.15 mm, 0.5/0.3 mm vias; USB pair is
  not length-matched, fine at full speed) on top of the locked existing routing. Three GND vias were added to stitch
  F.Cu pour islands at U5 and C15 (none in pads). DRC: 0 errors; silkscreen warnings only (new parts' refs hidden).
- Module and storage swap (2026-11-05): U1 is the Ebyte E73-2G4M08S1C at (116.5, 109), antenna end flush with the top
  edge; its footprint keep-out (all copper layers) covers x 108–125, y 100–103.5, replacing the old board keep-out.
  The keep-out also bans footprints. JLC's own C356849 footprint (EasyEDA) is drawn antenna-down with its origin 2 mm
  off the body centre toward the pad row, so the CPL gives U1 as 180° at (116.5, 111.0): that puts JLC's part
  antenna-up on our pads. The copper footprint itself is rotation 0, antenna at the top (checked 2026-11-05).
  U5 is the 8 GB SD NAND at (105, 112.6), rotated 270°, left of the module; U8, U9, C15, C16 removed; Q1, R7, C9, C10
  and the pull-ups moved into the old U5 spot; Y1/C13/C14 and C2/C6/C7 moved below/beside the module. Changed nets
  ripped up and re-routed with Freerouting 2.1.0 (0.15 mm signal, 0.3 mm power, 0.5/0.3 and 0.6/0.3 mm vias) on top of
  the locked charger/USB routing; GND stitching vias re-added (184 vias in all). DRC: 0 errors, 0 unconnected, schematic
  parity clean; no via-in-pad; smallest drill 0.3 mm. Same caveats as before: some autorouted tracks pass under the
  module body (F.Cu and B.Cu), and PDM_CLK/PDM_DATA run just below the antenna keep-out to reach both mics.
- **Review in KiCad before ordering:**
  1. F.Cu signal tracks threading between module pads / under the module body (autorouter). Acceptable with mask, but
     moving them to In2 is cleaner.
  2. PDM_CLK/PDM_DATA run along the top strip just below the antenna keep-out to reach the mics; check you're happy
     with clock routing that close to the antenna.
  3. Autorouted paths are not pretty (some long detours); electrically fine per DRC.
  4. USB-C THT shell legs poke through the back: put Kapton/foam between board and battery.
- microSD socket (2026-11-05, later): U5 (SD NAND) removed; **J4 SOFNG TF-002X-H18** at (107.3, 126.25), rotation 270,
  on the **left board edge** below the module (body x 100.05–114.55, y 119.45–133.05). The card end of the socket faces
  the left edge (silk/fab arrow); the top edge (antenna), bottom (J1) and right (J3) were ruled out, and the left
  edge is the only other one. Footprint `earshot:microSD_Hinged_SOFNG_TF-002X-H18`: Molex 47219-2001 land pattern
  (KiCad library, drawn from Molex's drawing) since the TF-002X-H18 has the same 14.5 × 13.6 × 1.8 body and pinout;
  8 signal pads 0.8 × 1.5 at 1.1 mm pitch, 4 shield pads 1.45 × 2.0. Courtyard = body + 0.25 mm plus 2 mm beyond the
  inner (hinge) end for the open lid. **Check the pads and the hinge end against the SOFNG drawing before ordering.**
  To make room: Y1/C13/C14, Q1, R7, R8–R11, R13, R14 and TP5 moved into the old U5 spot left of the module; U3
  (rotated 180°), C4, C5, C9, C10 moved right of the socket; J2 (Tag-Connect) moved beside the module's SWD pads
  (127, 117.7); C2 to (125.9, 112.3). Moved parts' nets and the USB pair ripped up and re-routed with Freerouting
  2.1.0 (no top-layer tracks under the socket body except at its pads; 0.3 mm edge clearance), with GND fan-out vias
  next to every moved GND pad. Two GND vias that sat inside J1's THT shell pads were removed. DRC: 0 errors,
  0 unconnected, courtyards clean, parity clean; no via-in-pad; 169 vias, smallest drill 0.3 mm; only the 5 old
  silkscreen warnings. A `Power` net class (0.3 mm, 0.6/0.3 vias) now covers 3V0, VBAT, VBUS and SD_3V0.

## Ordering rev A at JLCPCB
Files in `earshot-revA/fab/`: `earshot-revA-gerbers.zip`, `earshot-revA-BOM-JLCPCB.csv`, `earshot-revA-CPL-JLCPCB.csv`.
1. jlcpcb.com → Order now → upload `earshot-revA-gerbers.zip`. Set: **Layers 4**, **Thickness 0.8 mm**, keep the
   suggested 4-layer stackup, surface finish ENIG is nicer for the fine-pitch parts (HASL is OK). Qty 5.
2. Turn on **PCB Assembly**, top side, qty 2–5. Upload the BOM and CPL files.
3. Every BOM line has an LCSC number (U1 = C356849, J4 = C125814). Unselect TP*/J2 if they appear (bare pads).
4. In the **placement preview**, check each part's pin-1 dot against the board's pin-1 mark. Most likely to need a turn:
   D2 (LED), U1 (module: antenna end at the top board edge, pin 1 top-left next to it; CPL says 180° because JLC's
   footprint is drawn antenna-down), J4 (microSD socket: card end toward the left board edge, pin 1 (DAT2) nearest
   the module; the CPL rotation 270 is for our footprint and JLC's own C125814 footprint may be drawn another way, so
   expect to turn it in the preview), U3 (now rotation 180), U2 (charger), U4 (gauge), U6/U7 (mics, sound hole over
   the board hole), J3 (opening facing the right edge). JLCPCB's engineers also review this before building.
5. Recheck stock before paying: E73-2G4M08S1C (C356849) was not stock-checked when it went in (2026-11-05) and showed
   0 on 2026-11-01. The microSD socket C125814 showed 4,017 on 2026-11-05. Mics have thin stock too.

## Open decisions
None blocking layout.

Resolved 2026-11-01: module = MDBT50Q-1MV2 via JLCPCB Global Sourcing; SD NAND = ZDSD02GLGEAG 256 MB, replaced 2026-11-02 by 3× W25N02KV SPI NAND (768 MB), then 2026-11-05 by one 8 GB FLSD064GDUMT SD NAND, then the same day by a hinged microSD socket (TF-002X-H18); module replaced 2026-11-05 by the Ebyte E73-2G4M08S1C; mic = MSM261DDB019 (bottom port, the only stocked PDM part found); fuel gauge = MAX17048.

## Workflow checklist
- [x] Spec draft (this file)
- [x] Parts confirmed in JLCPCB assembly library (2026-11-01; recheck stock before ordering)
- [x] KiCad 9 installed (9.0.9) + KiCad MCP set up (mcp-server-kicad 0.21.1, `.mcp.json`)
- [x] Schematic + ERC (2026-11-01: 0 errors; warnings are only LCSC symbols' unspecified pin types and KiCad's missing global lib table)
- [x] Layout + DRC (2026-11-01): 0 errors, 0 unconnected, schematic parity clean; 5 cosmetic silk warnings
- [x] Final design check (2026-11-01): every IC pin-to-net checked against datasheets; module power wiring matches Raytac's "Reg0 disabled" (<3.6 V) reference circuit. Owner deferred the layout review; items 1–3 under "Layout notes" accepted as-is for rev A.
- [x] Gerbers, drill, BOM, CPL exported → `earshot-revA/fab/` (re-export after any board change)
- [ ] JLCPCB order (PCBA preview checked for part rotation)
- [ ] Bring-up: rails, SWD flash bootloader, USB, mics, microSD, BLE, charger, fuel gauge

Firmware is developed on the XIAO nRF52840 Sense in parallel; order the board once the XIAO
firmware records → stores → streams over BLE.
