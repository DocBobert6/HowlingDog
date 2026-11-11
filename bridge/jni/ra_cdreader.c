#include "ra_cdreader.h"

#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* A raw sector's sync pattern (12 bytes). */
static const uint8_t k_sync_pattern[12] = {
    0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00
};

/* The user data in one sector (what the hash reads). */
#define RA_CD_USER_DATA_SIZE 2048

typedef struct ra_cd_track {
    FILE* bin;
    int sector_size;        /* 2352 or 2048 */
    int sector_header_size; /* 16 (MODE1/2352), 24 (MODE2/2352), 0 (MODE1/2048) */
    int64_t file_track_offset; /* byte offset of INDEX 01 in the .bin */
    int track_first_sector;    /* LBA of the track's first sector */
} ra_cd_track_t;

/* ---- small helpers ---- */

/* "mm:ss:ff" (decimal, as in a .cue) to a frame count. */
static int ra_cd_msf_to_frames(int mm, int ss, int ff) {
    return ((mm * 60) + ss) * 75 + ff;
}

/* One BCD byte (two decimal digits) to an int. */
static int ra_cd_bcd(uint8_t b) {
    return ((b >> 4) & 0x0F) * 10 + (b & 0x0F);
}

/* A raw sector header's MSF (bytes 12-14, BCD) to a CD LBA
 * (MSF minus the 150-frame lead-in). */
static int ra_cd_header_to_lba(const uint8_t header[16]) {
    int mm = ra_cd_bcd(header[12]);
    int ss = ra_cd_bcd(header[13]);
    int ff = ra_cd_bcd(header[14]);
    return ra_cd_msf_to_frames(mm, ss, ff) - 150;
}

/* Case-insensitive prefix match. */
static int ra_cd_starts_with_ci(const char* s, const char* prefix) {
    while (*prefix) {
        if (tolower((unsigned char)*s) != tolower((unsigned char)*prefix))
            return 0;
        ++s;
        ++prefix;
    }
    return 1;
}

/* The directory part of a path (up to the last '/' or '\\'),
 * or "" when there is none. The result is written to `out`
 * (which must hold at least `out_size` bytes). */
static void ra_cd_dirname(const char* path, char* out, size_t out_size) {
    const char* slash = strrchr(path, '/');
    const char* backslash = strrchr(path, '\\');
    if (backslash && (!slash || backslash > slash))
        slash = backslash;
    if (!slash) {
        out[0] = '\0';
        return;
    }
    size_t len = (size_t)(slash - path) + 1;
    if (len >= out_size)
        len = out_size - 1;
    memcpy(out, path, len);
    out[len] = '\0';
}

/* The base name of a path (after the last '/' or '\\'). */
static const char* ra_cd_basename(const char* path) {
    const char* slash = strrchr(path, '/');
    const char* backslash = strrchr(path, '\\');
    if (backslash && (!slash || backslash > slash))
        slash = backslash;
    return slash ? slash + 1 : path;
}

/* ---- .cue parsing ---- */

/* Track 1's description, as read from the .cue. */
typedef struct ra_cd_cue_track1 {
    char bin_file[512]; /* the FILE name, directory part dropped */
    int mode;           /* 0 = MODE1/2352, 1 = MODE2/2352, 2 = MODE1/2048, -1 = other */
    int index01_frames; /* INDEX 01, in frames from the file start */
    int have_index01;
} ra_cd_cue_track1_t;

/* Skip blanks. Returns the first non-blank character, or the end. */
static const char* ra_cd_skip_blanks(const char* p) {
    while (*p == ' ' || *p == '\t')
        ++p;
    return p;
}

/* Read one token (up to a blank or the end) into `out`. */
static void ra_cd_read_token(const char* p, char* out, size_t out_size) {
    size_t i = 0;
    while (*p && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') {
        if (i + 1 < out_size)
            out[i++] = *p;
        ++p;
    }
    out[i] = '\0';
}

/* Parse "mm:ss:ff" into frames. Returns -1 on a bad value. */
static int ra_cd_parse_msf(const char* s) {
    int mm = 0, ss = 0, ff = 0;
    if (sscanf(s, "%d:%d:%d", &mm, &ss, &ff) != 3)
        return -1;
    if (mm < 0 || ss < 0 || ss >= 60 || ff < 0 || ff >= 75)
        return -1;
    return ra_cd_msf_to_frames(mm, ss, ff);
}

/*
 * Parse a .cue sheet (already read into `text`) for track 1.
 * Returns 0 on success (track 1 found, with a usable mode and
 * an INDEX 01), -1 otherwise. Only track 1 is tracked — the
 * hash never opens another track.
 */
static int ra_cd_parse_cue(const char* text, ra_cd_cue_track1_t* out) {
    char current_file[512] = "";
    int current_track = -1;
    int current_mode = -1;
    memset(out, 0, sizeof(*out));
    out->mode = -1;
    out->index01_frames = 0;
    out->have_index01 = 0;

    const char* p = text;
    while (*p) {
        /* One line (up to \n, \r stripped). */
        const char* line_end = strchr(p, '\n');
        size_t line_len = line_end ? (size_t)(line_end - p) : strlen(p);
        char line[1024];
        if (line_len >= sizeof(line))
            line_len = sizeof(line) - 1;
        memcpy(line, p, line_len);
        line[line_len] = '\0';
        if (line_len > 0 && line[line_len - 1] == '\r')
            line[line_len - 1] = '\0';
        p = line_end ? line_end + 1 : p + line_len;

        const char* q = ra_cd_skip_blanks(line);
        if (*q == '\0')
            continue;

        if (ra_cd_starts_with_ci(q, "FILE")) {
            q = ra_cd_skip_blanks(q + 4);
            char name[512];
            if (*q == '"') {
                /* Quoted: up to the closing quote. */
                const char* end = strchr(q + 1, '"');
                size_t len = end ? (size_t)(end - (q + 1)) : strlen(q + 1);
                if (len >= sizeof(name))
                    len = sizeof(name) - 1;
                memcpy(name, q + 1, len);
                name[len] = '\0';
            } else {
                ra_cd_read_token(q, name, sizeof(name));
            }
            /* Drop any directory part: the files travel next to
             * the cue (the library does the same). */
            const char* base = ra_cd_basename(name);
            size_t len = strlen(base);
            if (len >= sizeof(current_file))
                len = sizeof(current_file) - 1;
            memcpy(current_file, base, len);
            current_file[len] = '\0';
            current_track = -1;
        } else if (ra_cd_starts_with_ci(q, "TRACK")) {
            q = ra_cd_skip_blanks(q + 5);
            char num[16], mode[32];
            ra_cd_read_token(q, num, sizeof(num));
            q = ra_cd_skip_blanks(q + strlen(num));
            ra_cd_read_token(q, mode, sizeof(mode));
            current_track = atoi(num);
            if (ra_cd_starts_with_ci(mode, "MODE1/2352"))
                current_mode = 0;
            else if (ra_cd_starts_with_ci(mode, "MODE2/2352"))
                current_mode = 1;
            else if (ra_cd_starts_with_ci(mode, "MODE1/2048"))
                current_mode = 2;
            else
                current_mode = -1;
            if (current_track == 1) {
                size_t len = strlen(current_file);
                if (len >= sizeof(out->bin_file))
                    len = sizeof(out->bin_file) - 1;
                memcpy(out->bin_file, current_file, len);
                out->bin_file[len] = '\0';
                out->mode = current_mode;
            }
        } else if (ra_cd_starts_with_ci(q, "INDEX") && current_track == 1) {
            q = ra_cd_skip_blanks(q + 5);
            char num[16], msf[32];
            ra_cd_read_token(q, num, sizeof(num));
            q = ra_cd_skip_blanks(q + strlen(num));
            ra_cd_read_token(q, msf, sizeof(msf));
            if (atoi(num) == 1) {
                int frames = ra_cd_parse_msf(msf);
                if (frames >= 0) {
                    out->index01_frames = frames;
                    out->have_index01 = 1;
                }
            }
        }
        /* PREGAP and everything else is ignored: the track's
         * first sector comes from the TOC header MSF below, which
         * already reflects the disc layout. */
    }

    if (out->bin_file[0] == '\0' || out->mode < 0 || !out->have_index01)
        return -1;
    return 0;
}

/* ---- the cdreader hooks ---- */

static void* ra_cd_open_track(const char* path, uint32_t track) {
    if (track != 1 || !path)
        return NULL;
    /* Only .cue + .bin: a .chd (or anything else) has no reader
     * here, so the hash fails and RA stays inactive for it. */
    size_t path_len = strlen(path);
    if (path_len < 4 || strcasecmp(path + path_len - 4, ".cue") != 0)
        return NULL;

    FILE* cue = fopen(path, "rb");
    if (!cue)
        return NULL;
    fseek(cue, 0, SEEK_END);
    long cue_size = ftell(cue);
    fseek(cue, 0, SEEK_SET);
    if (cue_size <= 0 || cue_size > 65536) {
        fclose(cue);
        return NULL;
    }
    char* cue_text = (char*)malloc((size_t)cue_size + 1);
    if (!cue_text) {
        fclose(cue);
        return NULL;
    }
    if (fread(cue_text, 1, (size_t)cue_size, cue) != (size_t)cue_size) {
        free(cue_text);
        fclose(cue);
        return NULL;
    }
    cue_text[cue_size] = '\0';
    fclose(cue);

    ra_cd_cue_track1_t t1;
    if (ra_cd_parse_cue(cue_text, &t1) != 0) {
        free(cue_text);
        return NULL;
    }
    free(cue_text);

    /* The .bin lives next to the .cue. */
    char dir[1024];
    ra_cd_dirname(path, dir, sizeof(dir));
    char bin_path[1024];
    size_t dir_len = strlen(dir);
    size_t bin_len = strlen(t1.bin_file);
    if (dir_len + bin_len + 1 >= sizeof(bin_path))
        return NULL;
    memcpy(bin_path, dir, dir_len);
    memcpy(bin_path + dir_len, t1.bin_file, bin_len);
    bin_path[dir_len + bin_len] = '\0';

    FILE* bin = fopen(bin_path, "rb");
    if (!bin)
        return NULL;

    ra_cd_track_t* cd = (ra_cd_track_t*)calloc(1, sizeof(*cd));
    if (!cd) {
        fclose(bin);
        return NULL;
    }
    cd->bin = bin;
    switch (t1.mode) {
        case 0: /* MODE1/2352 */
            cd->sector_size = 2352;
            cd->sector_header_size = 16;
            break;
        case 1: /* MODE2/2352 */
            cd->sector_size = 2352;
            cd->sector_header_size = 24;
            break;
        default: /* MODE1/2048 */
            cd->sector_size = 2048;
            cd->sector_header_size = 0;
            break;
    }
    cd->file_track_offset = (int64_t)t1.index01_frames * cd->sector_size;
    cd->track_first_sector = 0;

    /* The first sector of track 1, from the TOC sector's header
     * MSF (the reference implementation's method): the TOC is
     * sector 16 of the track, so its LBA minus 16 is the
     * track's first sector. A header with no usable MSF (a raw
     * rip whose headers start at 00:00:00, or a cooked image
     * with no header at all) falls back to 0 — the track starts
     * at the file's INDEX 01, which is LBA 0 for a Neo Geo CD. */
    if (cd->sector_header_size > 0) {
        uint8_t header[16];
        int64_t toc_offset = cd->file_track_offset + (int64_t)16 * cd->sector_size;
        if (fseek(bin, toc_offset, SEEK_SET) == 0 &&
            fread(header, 1, sizeof(header), bin) == sizeof(header) &&
            memcmp(header, k_sync_pattern, 12) == 0) {
            int first = ra_cd_header_to_lba(header) - 16;
            if (first >= 0)
                cd->track_first_sector = first;
        }
    }
    return cd;
}

static size_t ra_cd_read_sector(void* track_handle, uint32_t sector, void* buffer, size_t requested_bytes) {
    ra_cd_track_t* cd = (ra_cd_track_t*)track_handle;
    uint8_t* out = (uint8_t*)buffer;
    size_t total_read = 0;
    if (!cd || !buffer || requested_bytes == 0)
        return 0;
    if (sector < (uint32_t)cd->track_first_sector)
        return 0;

    int64_t sector_start = (int64_t)(sector - (uint32_t)cd->track_first_sector) * cd->sector_size +
        cd->sector_header_size + cd->file_track_offset;

    /* The hash never asks for more than a sector's user data,
     * but read in whole sectors first (the reference
     * implementation's loop), then the remainder. */
    while (requested_bytes > (size_t)RA_CD_USER_DATA_SIZE) {
        if (fseek(cd->bin, sector_start, SEEK_SET) != 0)
            return total_read;
        size_t n = fread(out, 1, RA_CD_USER_DATA_SIZE, cd->bin);
        total_read += n;
        if (n < (size_t)RA_CD_USER_DATA_SIZE)
            return total_read;
        out += RA_CD_USER_DATA_SIZE;
        sector_start += cd->sector_size;
        requested_bytes -= RA_CD_USER_DATA_SIZE;
    }
    if (fseek(cd->bin, sector_start, SEEK_SET) != 0)
        return total_read;
    size_t n = fread(out, 1, requested_bytes, cd->bin);
    total_read += n;
    return total_read;
}

static void ra_cd_close_track(void* track_handle) {
    ra_cd_track_t* cd = (ra_cd_track_t*)track_handle;
    if (!cd)
        return;
    if (cd->bin)
        fclose(cd->bin);
    free(cd);
}

static uint32_t ra_cd_first_track_sector(void* track_handle) {
    ra_cd_track_t* cd = (ra_cd_track_t*)track_handle;
    if (!cd)
        return 0;
    return (uint32_t)cd->track_first_sector;
}

void ra_cdreader_get(rc_hash_cdreader_t* cdreader) {
    memset(cdreader, 0, sizeof(*cdreader));
    cdreader->open_track = ra_cd_open_track;
    cdreader->read_sector = ra_cd_read_sector;
    cdreader->close_track = ra_cd_close_track;
    cdreader->first_track_sector = ra_cd_first_track_sector;
    cdreader->open_track_iterator = NULL;
}
