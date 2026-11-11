#ifndef RA_CDREADER_H
#define RA_CDREADER_H

#include "rc_hash.h"

/**
 * Phase 10 — RetroAchievements: a minimal CD reader for disc
 * games, so rcheevos can hash Neo Geo CD discs.
 *
 * rcheevos's Neo Geo CD hash (rc_hash_neogeo_cd) opens track 1,
 * finds IPL.TXT in the ISO9660 directory, and hashes the .PRG
 * files it names. That needs a CD-reader hook
 * (rc_hash_callbacks.cdreader); rcheevos ships none of its own.
 * This reader supplies one, for the .cue + .bin discs HowlingDog
 * loads and (v1.2.0, item 3) for .chd discs, through the
 * vendored libchdr — the same library the core itself uses to
 * play .chd discs, compiled into the bridge as a static
 * library (no new vendoring). Track 1 only — the data track,
 * which is all the hash needs.
 *
 * The reader returns the 2048-byte user data of a sector (what
 * the hash code expects): MODE1/2352 has a 16-byte header,
 * MODE2/2352 a 24-byte header, MODE1/2048 none; a .chd frame is
 * MODE1_RAW (2352 + 96 subcode) with the same 16-byte header,
 * or cooked 2048. Sector numbers are volume LBAs, as the
 * rcheevos contract requires (the PVD is first_track_sector +
 * 16, and the directory extents are volume LBAs passed raw):
 * the .bin reader's first sector comes from the TOC sector's
 * header MSF, falling back to 0 when the header carries no
 * usable MSF (a raw rip whose headers start at 00:00:00), and
 * the .chd reader's is 0 — the volume starts at track 1's
 * data, the pregap excluded, in both cases.
 */
void ra_cdreader_get(rc_hash_cdreader_t* cdreader);

#endif /* RA_CDREADER_H */
