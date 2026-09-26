# VollaTorchStrength

An **Xposed / LSPosed** module (+ tiny root daemon) that restores **torch
brightness control (31 steps)** on the **Volla Quintus / zahedan (algiz,
mt6877)** running the A16 VollaOS port — *without* touching the vendor camera
HAL, so camera and enumeration stay 100% intact.

## Why Xposed (and not a vendor-lib patch)

On this ROM the camera device HAL ships
`getTorchStrengthLevel` / `turnOnTorchWithStrengthLevel` as **ENOSYS stubs**,
and the static metadata reports `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 1`. Every
attempt to fix that at the vendor level (swapping the DariaOS A15 camera libs)
added the levels **but broke camera enumeration** (`empty camera id`), because
the A15 build enumerates differently from this A16 base — and the A16 camera
HAL source is closed.

So we fix it one layer up, in the **Java framework**, where it's clean and
reversible:

1. **`getCameraCharacteristics` hook** → overwrite
   `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 31` (and `DEFAULT_LEVEL = 1`) so
   FlashDim and the SystemUI QS long-press slider show **31 real steps**.
2. **`turnOnTorchWithStrengthLevel(id, level)` hook** → swallow the call the
   broken HAL would reject, and instead hand the level to a root daemon.
3. **`setTorchMode(id, on)` hook** → map plain on/off to full / zero through the
   same path.

The flash LEDs accept `0..31` on
`/sys/class/leds/mt6360_flash_ch1|ch2/brightness` (verified on-device;
`max_brightness = 31`). The FlashDim/SystemUI process can't write those nodes,
so a small **root daemon** does.

## Components

- **`app/`** — the Xposed module APK (`com.blueboy.vollatorch`).
- **`daemon/vollatorchd.c`** — root helper. Listens on the **abstract** unix
  socket `\0vollatorchd`, blocking on `accept()` — **event-driven, zero
  polling, zero idle CPU**. Each connection carries one integer `0..31`; the
  daemon clamps it and writes both flash channels.
- **`ksu-module/`** — KernelSU/Magisk module that installs the daemon binary and
  starts it at boot (`service.sh`).

## Build (GitHub Actions)

The server has no room for the Android SDK, so CI builds everything:
`.github/workflows/build.yml`

- compiles `vollatorchd` for `aarch64` with the NDK,
- builds the Xposed APK,
- uploads two artifacts: `VollaTorchStrength.apk` and
  `VollaTorchStrength-daemon.zip`.

## Install

1. Flash **`VollaTorchStrength-daemon.zip`** in KernelSU, reboot.
2. Install **`VollaTorchStrength.apk`**, enable it in **LSPosed** with scope:
   **System Framework**, **SystemUI**, **FlashDim**. Reboot (or restart the
   scoped apps).
3. Open FlashDim or long-press the QS torch tile → 31-step brightness that
   actually changes the light.

## Notes / limits

- LSPosed (or another modern Xposed impl) is required — API 93+.
- The daemon writes **both** LED channels, matching the dual-LED behaviour.
- Uninstall = disable in LSPosed + remove the KernelSU module. Nothing in
  `/vendor` is modified, so it's fully reversible and OTA-safe.
