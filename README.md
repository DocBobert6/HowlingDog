# HowlingDog

A standalone Neo Geo emulator for Android: an original frontend around
the Geolith libretro core (AES / MVS / CD / CDZ).

**Status: Phase 1** — the stock core builds green in CI on arm64. The
player (boot a game, video, audio, input) arrives in Phase 2. See
`PLAN.md` in `~/workspace/howlingdog/` for the locked plan; the plan
file is not part of this repo.

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
- `.github/workflows/android.yml` — CI: builds the stock core for
  arm64-v8a via ndk-build, then compiles the app (debug, unsigned)
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

## Building

CI builds on every push to `main` (see `.github/workflows/android.yml`).

Locally, with the Android SDK + NDK 27 installed:

```sh
# Stock core, arm64 only
cd libretro/jni
$ANDROID_HOME/ndk/27.2.12479018/ndk-build -j"$(nproc)" APP_ABI=arm64-v8a APP_PLATFORM=android-21
# App (debug, unsigned)
./gradlew :app:assembleDebug
```

## UI

The UI is based on other UI of the mid-90s era — a fighting-game
select grid (KOF '94/'95, Street Fighter II style) for the library —
not on the CD console, and it never shows discs or the word "CD".
The mascot is an original howling-dog silhouette against a full moon.
