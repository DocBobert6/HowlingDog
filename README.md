# HowlingDog

A standalone Neo Geo emulator for Android: an original frontend around
the Geolith libretro core (AES / MVS / CD / CDZ).

**Status: Phase 2** — the bare player spike: the JNI bridge boots a
game with video, audio, and touch input. See `PLAN.md` in
`~/workspace/howlingdog/` for the locked plan; the plan file is not
part of this repo.

## Core pin

Fork of `libretro/geolith-libretro`, pinned at upstream commit
`2dc88e3fa72ff404213a65d6eb5bb33baa687f73`
("m68k: avoid the signal-mask save in the bus error trap on
BSD/Darwin"). The core tree is imported verbatim at the repo root
(`src/`, `libretro/`, `deps/`); **no core changes in Phase 1**.
Later phases may optimise the core, but only against the regression
baseline defined in the plan — never silently.

## Layout

- `src/`, `libretro/`, `deps/` — Geolith core, BSD-3-Clause (see `LICENSE`)
- `frontend/` — the Android frontend, GPL-3.0 (see `frontend/LICENSE`)
- `app/` — the Android app module (`app.howlingdog`), Kotlin/Compose
- `bridge/` — the JNI bridge (C++), built by ndk-build; it links the
  prebuilt core and is the only native code the frontend owns
- `.github/workflows/android.yml` — CI: builds the stock core, then
  the bridge, for arm64-v8a via ndk-build; stages both .so files;
  runs the unit tests (incl. the Robolectric startup gate); then
  compiles the app (debug, unsigned)
- `NOTICE` — full licence texts for every library in the compiled core

## Licences

- Frontend (`frontend/`, `app/`): GNU GPL-3.0 — `frontend/LICENSE`.
- Core (`src/`, `libretro/`, `deps/`): permissive, see `LICENSE` and
  `NOTICE` (Geolith BSD-3-Clause; ymfm BSD-3-Clause; Speex BSD-3-Clause;
  zstd BSD; Musashi MIT; Jolly Good Z80 MIT; miniz MIT; libretro MIT;
  LZMA public domain). The core is bundled in the APK; the notices in
  `NOTICE` satisfy the binary-redistribution conditions.

HowlingDog is not affiliated with or endorsed by SNK Corporation.

## Games and BIOS — not included

No games, BIOS files, or ROM-download links are in this repo, and
none ever will be. To play, supply your own:

- Cartridges: `.NEO` files (TerraOnion format); convert a MAME set
  with [lithogen](https://github.com/carmiker/lithogen).
- Discs: `.bin/.cue` or `.chd`.
- BIOS zips from a recent MAME set: `aes.zip` (AES), `neogeo.zip`
  (MVS + Universe BIOS), `neocd.zip` + `neocdz.zip` (CD),
  `neocdz.zip` (CDZ), `irrmaze.zip` (Irritating Maze, MVS).

## Playing (Phase 2 spike)

The spike UI is plain and temporary — the fighting-game select grid
library arrives in a later phase. To test on a device, install the
debug APK from the CI artifact (`howlingdog-apk-debug`) and supply
your own files:

1. A game file: one `.NEO` cartridge file (`.cue` / `.chd` also
   work the same way, with the matching BIOS below).
2. A folder holding the BIOS zips: `aes.zip` for home-console games,
   `neogeo.zip` for arcade games; `neocd.zip` + `neocdz.zip` for
   disc games, `neocdz.zip` for CDZ games.

In the app: tap **Pick game file**, pick the `.NEO`; tap **Pick BIOS
folder**, pick the folder holding the zips; tap **Start**. The game
boots fullscreen with touch controls (directions, A/B/C/D, Start,
Coin). **Stop** (or Back) returns to the setup screen.

Honest limits of the spike, stated plainly:

- No game has been booted locally yet — the sandbox has no games
  and must not download any, so the first real boot happens on the
  test device. What CI verifies: the core and bridge build, the
  bridge links the core and exposes all 11 JNI entry points, the
  unit tests pass (incl. the Robolectric startup gate, which
  launches the app with no native code present), and the debug APK
  assembles.
- Video is a Bitmap copy path (framebuffer -> int array -> Bitmap):
  correct, but it copies every pixel every frame. The final
  renderer will draw the core's framebuffer directly.
- Audio is resampled to 48 kHz in the bridge with a linear
  interpolator, because the core's native rates (55943 Hz AES,
  55555 Hz MVS, 44396 Hz CD) sit outside what AudioTrack accepts
  reliably. A proper resampler belongs in a later phase.
- Input is player 1, touch only. Gamepad input arrives in Phase 4.
- No save states, no pause menu, no core-options UI: the core runs
  on its defaults (AES system unless a game needs MVS).

## Building

CI builds on every push to `main` (see `.github/workflows/android.yml`).

Locally, with the Android SDK + NDK 27 installed:

```sh
# Stock core, arm64 only
cd libretro/jni
$ANDROID_HOME/ndk/27.2.12479018/ndk-build -j"$(nproc)" APP_ABI=arm64-v8a APP_PLATFORM=android-21
# Bridge (after the core — it links the prebuilt libretro.so)
cd ../../bridge/jni
$ANDROID_HOME/ndk/27.2.12479018/ndk-build -j"$(nproc)" APP_ABI=arm64-v8a APP_PLATFORM=android-21
# Stage both .so files for Gradle (build artifacts, never committed)
cd ../..
mkdir -p app/src/main/jniLibs/arm64-v8a
cp bridge/libs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
# Unit tests (incl. the Robolectric startup gate)
./gradlew :app:testDebugUnitTest
# App (debug, unsigned)
./gradlew :app:assembleDebug
```

## UI

The UI is based on other UI of the mid-90s era — a fighting-game
select grid (KOF '94/'95, Street Fighter II style) for the library —
not on the CD console, and it never shows discs or the word "CD".
The mascot is an original howling-dog silhouette against a full moon.
