# rcheevos (vendored)

The official RetroAchievements client library, vendored for
HowlingDog Phase 10 (RetroAchievements).

- **Version:** 12.5.0 (release, 2026)
- **Source:** https://github.com/RetroAchievements/rcheevos
  (tag `v12.5.0`, tarball from codeload.github.com)
- **License:** MIT (see `LICENSE` in this directory)

## What is vendored

The public headers (`include/`) and the library sources needed
for the `rc_client` integration:

- `src/rc_client.c`, `rc_compat.c`, `rc_util.c`, `rc_version.c`
  (+ the private headers `rc_client_internal.h`,
  `rc_compat.h`, `rc_version.h`)
- `src/rcheevos/` — the achievement runtime (triggers,
  conditions, leaderboards, rich presence, memory references)
- `src/rhash/` — game hashing (MD5, .neo cart hash, disc hash,
  zip hash; the disc hash needs a CD-reader hook supplied by
  the app — see `bridge/jni/ra_client.c`)
- `src/rapi/` — the RetroAchievements web-API request builders

NOT vendored (not needed for this integration):

- `src/rc_libretro.c` / `rc_libretro.h` — the libretro memory-map
  helper. HowlingDog supplies its own `read_memory` callback
  (the Neo Geo CD work RAM sits at PRAM offset 0x100000, which
  the helper would map to PRAM offset 0 — see `ra_client.c`).
- `src/rc_client_external.c`, `rc_client_raintegration.c` —
  the Raintegration external-client support.
- `test/`, `validator/`, `.github/` — upstream's tests and CI.

## Build

`Android.mk` in this directory builds a static library
(`rcheevos`) linked into the `howlingdog-bridge` module
(see `bridge/jni/Android.mk`). The define
`RC_CLIENT_SUPPORTS_HASH` is required — it enables
`rc_client_begin_identify_and_load_game` and the game hashing
used to identify a loaded game. The host test
`tests/ra_client_test.c` compiles the same sources with gcc
(`-DRC_CLIENT_SUPPORTS_HASH`).

## Updating

To update to a newer rcheevos release, replace `include/` and
`src/` from the new release tarball, keep this README's file
list in sync, and re-run the host test plus CI. Do not edit
the vendored sources — all HowlingDog-specific behaviour lives
in `bridge/jni/ra_client.c`.
