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
 * loads (track 1 only — the data track, which is all the hash
 * needs). .chd is NOT supported here (it needs CHD decompression,
 * and libchdr is not vendored); a .chd game simply has no hash
 * and RA stays inactive for it.
 *
 * The reader returns the 2048-byte user data of a sector (what
 * the hash code expects): MODE1/2352 has a 16-byte header,
 * MODE2/2352 a 24-byte header, MODE1/2048 none. Sector numbers
 * are absolute (CD LBAs, MSF minus the 150-frame lead-in), as
 * the rcheevos contract requires; the first sector of track 1
 * is read from the TOC sector's header MSF, falling back to 0
 * when the header carries no usable MSF (a raw rip whose
 * headers start at 00:00:00).
 */
void ra_cdreader_get(rc_hash_cdreader_t* cdreader);

#endif /* RA_CDREADER_H */
