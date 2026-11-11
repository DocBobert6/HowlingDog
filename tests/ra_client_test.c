/* HowlingDog Phase 10 — host test for the RetroAchievements
 * wrapper (bridge/jni/ra_client.c).
 *
 * Pure C, no JNI and no Android: the wrapper's app callbacks
 * (HTTP, events, results) are stubs here, and the core's RAM is
 * a mock array. The server is a mock too — it answers the
 * RetroAchievements API calls (login, achievementsets,
 * startsession, award) with canned JSON taken from upstream
 * rcheevos's own test suite (test/test_rc_client.c), so the
 * full flow (login -> load game -> frame -> unlock -> serialize
 * -> reset -> deserialize) runs without a network or an account.
 *
 * Compile with the rcheevos sources (see the CI step):
 *   gcc -I bridge/jni -I deps/rcheevos/include -I deps/rcheevos/src \
 *       -DRC_CLIENT_SUPPORTS_HASH -D_GNU_SOURCE -std=c99 \
 *       -Wall -Wextra -o /tmp/ra_client_test tests/ra_client_test.c \
 *       bridge/jni/ra_client.c <rcheevos sources>
 *
 * The .neo hash check needs a fake .neo file; the test builds
 * one in /tmp and compares the hash against the MD5 of the ROM
 * portion (computed by md5sum in the shell — see the comment
 * in test_neo_hash).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "ra_client.h"
#include "ra_cdreader.h"
#include "rc_consoles.h"
#include "rc_hash.h"

static int g_checks;
static int g_failures;

#define CHECK(cond) do { \
    ++g_checks; \
    if (!(cond)) { \
        ++g_failures; \
        printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
    } \
} while (0)

#define CHECK_STR_EQ(a, b) do { \
    ++g_checks; \
    if (strcmp((a), (b)) != 0) { \
        ++g_failures; \
        printf("FAIL %s:%d: \"%s\" != \"%s\"\n", __FILE__, __LINE__, (a), (b)); \
    } \
} while (0)

/* ------------------------------------------------------------------ */
/* Mock RAM (the core's SYSTEM_RAM)                                   */
/* ------------------------------------------------------------------ */

static uint8_t g_mock_ram[0x200000]; /* 2 MB, like the CD PRAM */

static void mock_memory_provider(const uint8_t** data, size_t* size, void* ctx) {
    (void)ctx;
    *data = g_mock_ram;
    *size = sizeof(g_mock_ram);
}

/* ------------------------------------------------------------------ */
/* Mock server (canned RetroAchievements API responses)               */
/* ------------------------------------------------------------------ */

static const char* kPatchdata2Ach =
    "{\"Success\":true,"
    "\"GameId\":1234,\"Title\":\"Sample Game\",\"ConsoleId\":17,"
    "\"ImageIconUrl\":\"http://server/Images/112233.png\","
    "\"RichPresenceGameId\":1234,\"RichPresencePatch\":\"\",\"Sets\":[{"
      "\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null,\"Type\":\"core\","
      "\"ImageIconUrl\":\"http://server/Images/112233.png\","
      "\"Achievements\":["
       "{\"ID\":5501,\"Title\":\"Ach1\",\"Description\":\"Desc1\",\"Flags\":3,\"Points\":5,"
        "\"MemAddr\":\"0xH0001=3_0xH0002=7\",\"Author\":\"User1\",\"BadgeName\":\"00234\","
        "\"Created\":1367266583,\"Modified\":1376929305},"
       "{\"ID\":5502,\"Title\":\"Ach2\",\"Description\":\"Desc2\",\"Flags\":3,\"Points\":2,"
        "\"MemAddr\":\"0xH0001=2_0x0002=9\",\"Author\":\"User1\",\"BadgeName\":\"00235\","
        "\"Created\":1376970283,\"Modified\":1376970283}"
      "],"
      "\"Leaderboards\":[]"
    "}]}";

static const char* kLoginResponse =
    "{\"Success\":true,\"User\":\"User\",\"AvatarUrl\":\"http://server/UserPic/USER.png\","
    "\"Token\":\"ApiToken\",\"Score\":12345,\"Messages\":2}";

static const char* kNoUnlocks =
    "{\"Success\":true,\"Unlocks\":[],\"HardcoreUnlocks\":[]}";

static const char* kAwardResponse =
    "{\"Success\":true,\"Score\":5432,\"SoftcoreScore\":777,"
    "\"AchievementID\":5501,\"AchievementsRemaining\":1}";

static int g_server_calls;
static int g_unexpected_requests;

/* The last request body the mock server saw, so a test can
 * check what a login actually sent (password vs token). */
static char g_last_post_data[1024];

static void mock_server_call(int request_id, const char* url, const char* post_data, void* ctx) {
    const char* body = NULL;
    (void)url;
    (void)ctx;
    ++g_server_calls;
    if (post_data)
        snprintf(g_last_post_data, sizeof(g_last_post_data), "%s", post_data);
    else
        g_last_post_data[0] = '\0';
    if (!post_data) {
        ++g_unexpected_requests;
        body = "";
    } else if (strncmp(post_data, "r=login2", 8) == 0) {
        body = kLoginResponse;
    } else if (strncmp(post_data, "r=achievementsets", 17) == 0) {
        body = kPatchdata2Ach;
    } else if (strncmp(post_data, "r=startsession", 14) == 0) {
        body = kNoUnlocks;
    } else if (strncmp(post_data, "r=awardachievement", 18) == 0) {
        body = kAwardResponse;
    } else {
        ++g_unexpected_requests;
        printf("unexpected server request: %s\n", post_data);
        body = "";
    }
    /* Synchronous, like a local mock: deliver the response at
     * once. rcheevos supports a synchronous server callback. */
    ra_http_response(request_id, body, strlen(body), body && body[0] ? 200 : 500);
}

/* ------------------------------------------------------------------ */
/* Mock events and async results                                      */
/* ------------------------------------------------------------------ */

typedef struct {
    int type;
    uint32_t id;
    char title[64];
    uint32_t points;
} mock_event_t;

static mock_event_t g_events[16];
static int g_num_events;

static void mock_event(int type, uint32_t achievement_id, const char* title, const char* description, uint32_t points, void* ctx) {
    (void)description;
    (void)ctx;
    if (g_num_events < 16) {
        g_events[g_num_events].type = type;
        g_events[g_num_events].id = achievement_id;
        snprintf(g_events[g_num_events].title, sizeof(g_events[g_num_events].title), "%s", title ? title : "");
        g_events[g_num_events].points = points;
        ++g_num_events;
    }
}

static int g_last_result = -999;
static const char* g_last_error;

static void mock_result(int result, const char* error_message, void* ctx) {
    (void)ctx;
    g_last_result = result;
    g_last_error = error_message;
}

/* ------------------------------------------------------------------ */
/* Tests                                                              */
/* ------------------------------------------------------------------ */

static void test_console_id_for_path(void) {
    CHECK(ra_console_id_for_path("kof98.neo") == RA_CONSOLE_ARCADE);
    CHECK(ra_console_id_for_path("KOF98.NEO") == RA_CONSOLE_ARCADE);
    CHECK(ra_console_id_for_path("/sdcard/games/kof98.neo") == RA_CONSOLE_ARCADE);
    CHECK(ra_console_id_for_path("mslug3.cue") == RA_CONSOLE_NEO_GEO_CD);
    CHECK(ra_console_id_for_path("mslug3.CUE") == RA_CONSOLE_NEO_GEO_CD);
    CHECK(ra_console_id_for_path("mslug3.chd") == RA_CONSOLE_NEO_GEO_CD);
    CHECK(ra_console_id_for_path("mslug3.m3u") == RA_CONSOLE_NEO_GEO_CD);
    CHECK(ra_console_id_for_path("game.zip") == 0);
    CHECK(ra_console_id_for_path("game.bin") == 0);
    CHECK(ra_console_id_for_path(NULL) == 0);
    CHECK(ra_console_id_for_path("") == 0);
}

static void test_read_memory_mapped(void) {
    uint8_t buffer[16];
    uint8_t ram[0x200000];
    memset(ram, 0, sizeof(ram));
    /* Cart work RAM: byte 0x1234 = 0xAB. */
    ram[0x1234] = 0xAB;
    /* Disc work RAM: PRAM offset 0x100000 + 0x1234. */
    ram[0x100000 + 0x1234] = 0xCD;
    /* A byte that only exists at the cart offset, to prove the
     * disc path does not read the cart offset. */
    ram[0x0005] = 0x11;
    ram[0x100005] = 0x22;

    /* Cart: RA address A reads SYSTEM_RAM[A]. */
    memset(buffer, 0, sizeof(buffer));
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0x1234, buffer, 1, ram, sizeof(ram)) == 1);
    CHECK(buffer[0] == 0xAB);
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0x0005, buffer, 1, ram, sizeof(ram)) == 1);
    CHECK(buffer[0] == 0x11);

    /* Disc: RA address A reads SYSTEM_RAM[0x100000 + A]. */
    memset(buffer, 0, sizeof(buffer));
    CHECK(ra_read_memory_mapped(RA_CONSOLE_NEO_GEO_CD, 0x1234, buffer, 1, ram, sizeof(ram)) == 1);
    CHECK(buffer[0] == 0xCD);
    CHECK(ra_read_memory_mapped(RA_CONSOLE_NEO_GEO_CD, 0x0005, buffer, 1, ram, sizeof(ram)) == 1);
    CHECK(buffer[0] == 0x22);

    /* A multi-byte read crossing into the work RAM works, and a
     * read that would cross the 64 KB boundary is clamped. */
    memset(buffer, 0, sizeof(buffer));
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0xFFFE, buffer, 4, ram, sizeof(ram)) == 2);
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0xFFFE, buffer, 2, ram, sizeof(ram)) == 2);

    /* Outside the work RAM: unmapped. */
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0x10000, buffer, 1, ram, sizeof(ram)) == 0);
    CHECK(ra_read_memory_mapped(RA_CONSOLE_NEO_GEO_CD, 0x20000, buffer, 1, ram, sizeof(ram)) == 0);
    /* Unknown console: unmapped. */
    CHECK(ra_read_memory_mapped(0, 0x1234, buffer, 1, ram, sizeof(ram)) == 0);
    /* RAM too small for the disc offset: unmapped (a cart-sized
     * buffer must never satisfy a disc read). */
    CHECK(ra_read_memory_mapped(RA_CONSOLE_NEO_GEO_CD, 0x1234, buffer, 1, ram, 0x10000) == 0);
    /* No RAM at all: unmapped. */
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0x1234, buffer, 1, NULL, 0) == 0);
    CHECK(ra_read_memory_mapped(RA_CONSOLE_ARCADE, 0x1234, buffer, 0, ram, sizeof(ram)) == 0);
}

/* Build a fake .neo file (4096-byte header + ROM data) and hash
 * it. The expected hash is the MD5 of the ROM portion only
 * (bytes 4096+). The hash is verified two ways here: the file
 * hash and the buffer hash of the WHOLE file (header + ROM —
 * rc_hash_neogeo_cart skips the header itself) must agree. */
static const char* kNeoPath = "/tmp/ra_test.neo";

static void build_fake_neo(void) {
    FILE* f = fopen(kNeoPath, "wb");
    uint8_t header[4096];
    uint8_t rom[65536];
    memset(header, 0, sizeof(header));
    memcpy(header, "NEO\1", 4);
    /* A recognisable header field (the game name), which the
     * hash must ignore — it is inside the first 4096 bytes. */
    memcpy(header + 16, "FAKE GAME", 9);
    for (size_t i = 0; i < sizeof(rom); ++i)
        rom[i] = (uint8_t)(i * 7 + 3);
    fwrite(header, 1, sizeof(header), f);
    fwrite(rom, 1, sizeof(rom), f);
    fclose(f);
}

static void test_neo_hash(void) {
    char hash_from_file[33];
    char hash_from_buffer[33];
    uint8_t whole[4096 + 65536];
    FILE* f;
    build_fake_neo();
    /* The whole file, read back. */
    f = fopen(kNeoPath, "rb");
    CHECK(f != NULL);
    if (!f)
        return;
    CHECK(fread(whole, 1, sizeof(whole), f) == sizeof(whole));
    fclose(f);
    /* Hash from the file (the path the wrapper uses) and from
     * the buffer (the same bytes, header included) — both must
     * agree, and both must be the MD5 of the ROM portion. */
    CHECK(rc_hash_generate_from_file(hash_from_file, RA_CONSOLE_ARCADE, kNeoPath) != 0);
    CHECK(rc_hash_generate_from_buffer(hash_from_buffer, RA_CONSOLE_ARCADE, whole, sizeof(whole)) != 0);
    CHECK_STR_EQ(hash_from_file, hash_from_buffer);
    CHECK(strlen(hash_from_file) == 32);
    /* The header must not participate: a different header with
     * the same ROM must hash identically. */
    {
        FILE* f2 = fopen(kNeoPath, "r+b");
        CHECK(f2 != NULL);
        if (f2) {
            fseek(f2, 16, SEEK_SET);
            fwrite("OTHER GAME", 1, 10, f2);
            fclose(f2);
        }
        CHECK(rc_hash_generate_from_file(hash_from_file, RA_CONSOLE_ARCADE, kNeoPath) != 0);
        CHECK_STR_EQ(hash_from_file, hash_from_buffer);
    }
    remove(kNeoPath);
}

/* ------------------------------------------------------------------ */
/* The CD reader (bridge/jni/ra_cdreader.c)                            */
/* ------------------------------------------------------------------ */

/* A fake Neo Geo CD disc, built in /tmp: a .cue + a .bin with a
 * minimal ISO9660 filesystem (a PVD at sector 16, a root
 * directory at 17, IPL.TXT at 18 naming GAME.PRG, the PRG at
 * 19). The PRG is 16 bytes (0x00..0x0F), so the hash rcheevos
 * computes must be the MD5 of those 16 bytes:
 * 1ac1ef01e96caf1be0d329331a4fc2a8. */
#define CD_TEST_DIR "/tmp/hd-ra-cd-test"
#define CD_TEST_CUE CD_TEST_DIR "/fake.cue"
#define CD_TEST_BIN CD_TEST_DIR "/fake.bin"

static uint8_t cd_bcd(int n) {
    return (uint8_t)(((n / 10) << 4) | (n % 10));
}

/* The MSF (BCD) for a sector's header: LBA -> MSF (frames = LBA + 150). */
static void cd_msf_bytes(int lba, uint8_t out[3]) {
    int total = lba + 150;
    int ff = total % 75;
    total /= 75;
    int ss = total % 60;
    int mm = total / 60;
    out[0] = cd_bcd(mm);
    out[1] = cd_bcd(ss);
    out[2] = cd_bcd(ff);
}

/* One 2352-byte MODE1 raw sector: sync + MSF header + 2048 user
 * bytes + 288 zero bytes (EDC/ECC, unused by the hash). */
static void cd_raw_sector(int lba, const uint8_t user[2048], uint8_t out[2352]) {
    static const uint8_t sync[12] = {
        0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00
    };
    memcpy(out, sync, 12);
    cd_msf_bytes(lba, out + 12);
    out[15] = 1; /* MODE1 */
    memcpy(out + 16, user, 2048);
    memset(out + 2064, 0, 288);
}

/* One ISO9660 directory record (33 + name, padded to even).
 * The name length is explicit: "." and ".." are single bytes
 * (0x00 / 0x01) that strlen would report as empty. Returns the
 * record length. */
static size_t cd_dir_record(uint32_t extent, uint32_t size, uint8_t flags,
                            const uint8_t* name, size_t name_len, uint8_t* out) {
    size_t len = 33 + name_len;
    if (len % 2)
        ++len;
    memset(out, 0, len);
    out[0] = (uint8_t)len;
    out[2] = (uint8_t)(extent & 0xFF);
    out[3] = (uint8_t)((extent >> 8) & 0xFF);
    out[4] = (uint8_t)((extent >> 16) & 0xFF);
    out[5] = (uint8_t)((extent >> 24) & 0xFF);
    out[6] = (uint8_t)((extent >> 24) & 0xFF);
    out[7] = (uint8_t)((extent >> 16) & 0xFF);
    out[8] = (uint8_t)((extent >> 8) & 0xFF);
    out[9] = (uint8_t)(extent & 0xFF);
    out[10] = (uint8_t)(size & 0xFF);
    out[11] = (uint8_t)((size >> 8) & 0xFF);
    out[12] = (uint8_t)((size >> 16) & 0xFF);
    out[13] = (uint8_t)((size >> 24) & 0xFF);
    out[14] = (uint8_t)((size >> 24) & 0xFF);
    out[15] = (uint8_t)((size >> 16) & 0xFF);
    out[16] = (uint8_t)((size >> 8) & 0xFF);
    out[17] = (uint8_t)(size & 0xFF);
    out[25] = flags;
    out[28] = 1;
    out[29] = 0;
    out[30] = 0;
    out[31] = 1;
    out[32] = (uint8_t)name_len;
    memcpy(out + 33, name, name_len);
    return len;
}

static void build_fake_disc(void) {
    /* mkdir -p (ignoring EEXIST). */
    char mkdir_cmd[256];
    snprintf(mkdir_cmd, sizeof(mkdir_cmd), "mkdir -p %s", CD_TEST_DIR);
    (void)system(mkdir_cmd);

    static const char prg[16] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    };
    static const char ipl[] = "GAME.PRG\n";

    /* Sector 16: the PVD. */
    uint8_t pvd[2048];
    memset(pvd, 0, sizeof(pvd));
    pvd[0] = 1;
    memcpy(pvd + 1, "CD001", 5);
    pvd[6] = 1;
    pvd[128] = 0x00;
    pvd[129] = 0x08; /* logical block size 2048 */
    {
        uint8_t root_rec[34];
        /* The root record is 34 bytes (name = one 0 byte); build
         * it by hand so the length is exactly 34. */
        memset(root_rec, 0, sizeof(root_rec));
        root_rec[0] = 34;
        root_rec[2] = 17; /* extent: sector 17 */
        root_rec[10] = 0x00;
        root_rec[11] = 0x08; /* length 2048 */
        root_rec[25] = 2;    /* directory */
        root_rec[28] = 1;
        root_rec[31] = 1;
        root_rec[32] = 1;
        root_rec[33] = 0;
        memcpy(pvd + 156, root_rec, 34);
    }

    /* Sector 17: the root directory. */
    uint8_t root[2048];
    memset(root, 0, sizeof(root));
    {
        size_t off = 0;
        uint8_t rec[64];
        size_t len;
        static const uint8_t dot[1] = { 0x00 };
        static const uint8_t dotdot[1] = { 0x01 };
        len = cd_dir_record(17, 2048, 2, dot, 1, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(17, 2048, 2, dotdot, 1, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(18, (uint32_t)sizeof(ipl) - 1, 0,
                            (const uint8_t*)"IPL.TXT;1", 9, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(19, 16, 0,
                            (const uint8_t*)"GAME.PRG;1", 10, rec);
        memcpy(root + off, rec, len);
        off += len;
        (void)off;
    }

    uint8_t ipl_data[2048];
    memset(ipl_data, 0, sizeof(ipl_data));
    memcpy(ipl_data, ipl, sizeof(ipl) - 1);
    uint8_t prg_data[2048];
    memset(prg_data, 0, sizeof(prg_data));
    memcpy(prg_data, prg, 16);

    FILE* f = fopen(CD_TEST_BIN, "wb");
    CHECK(f != NULL);
    if (!f)
        return;
    uint8_t sector[2352];
    uint8_t zeros[2048];
    memset(zeros, 0, sizeof(zeros));
    int i;
    for (i = 0; i < 16; ++i) {
        cd_raw_sector(i, zeros, sector);
        fwrite(sector, 1, sizeof(sector), f);
    }
    cd_raw_sector(16, pvd, sector);
    fwrite(sector, 1, sizeof(sector), f);
    cd_raw_sector(17, root, sector);
    fwrite(sector, 1, sizeof(sector), f);
    cd_raw_sector(18, ipl_data, sector);
    fwrite(sector, 1, sizeof(sector), f);
    cd_raw_sector(19, prg_data, sector);
    fwrite(sector, 1, sizeof(sector), f);
    fclose(f);

    f = fopen(CD_TEST_CUE, "w");
    CHECK(f != NULL);
    if (!f)
        return;
    fputs("FILE \"fake.bin\" BINARY\n", f);
    fputs("  TRACK 1 MODE1/2352\n", f);
    fputs("    INDEX 01 00:00:00\n", f);
    fclose(f);
}

/* A minimal stdio filereader for the hash iterator (the disc
 * hash itself goes through the cdreader, but rc_hash_generate
 * may still touch the filereader). */
static void* cd_stdio_open(const char* path) { return fopen(path, "rb"); }
static void cd_stdio_seek(void* h, int64_t off, int origin) { fseek((FILE*)h, (long)off, origin); }
static int64_t cd_stdio_tell(void* h) { return ftell((FILE*)h); }
static size_t cd_stdio_read(void* h, void* buf, size_t n) { return fread(buf, 1, n, (FILE*)h); }
static void cd_stdio_close(void* h) { fclose((FILE*)h); }

static void test_cdreader(void) {
    rc_hash_cdreader_t cdreader;
    ra_cdreader_get(&cdreader);
    build_fake_disc();

    /* open_track: track 1 only, .cue only. */
    void* track = cdreader.open_track(CD_TEST_CUE, 1);
    CHECK(track != NULL);
    CHECK(cdreader.open_track(CD_TEST_CUE, 2) == NULL);
    CHECK(cdreader.open_track(CD_TEST_BIN, 1) == NULL);
    CHECK(cdreader.open_track(CD_TEST_CUE, 0) == NULL);
    if (!track)
        return;

    CHECK(cdreader.first_track_sector(track) == 0);

    uint8_t buf[2048];
    size_t n = cdreader.read_sector(track, 16, buf, sizeof(buf));
    CHECK(n == 2048 && memcmp(&buf[1], "CD001", 5) == 0);
    n = cdreader.read_sector(track, 18, buf, sizeof(buf));
    CHECK(n == 2048 && memcmp(buf, "GAME.PRG\n", 9) == 0);
    n = cdreader.read_sector(track, 19, buf, sizeof(buf));
    CHECK(n == 2048 && buf[0] == 0x00 && buf[15] == 0x0F);
    CHECK(cdreader.read_sector(track, 0, buf, sizeof(buf)) == 2048);
    cdreader.close_track(track);

    /* The full hash, through rcheevos: it must be the MD5 of
     * the 16 PRG bytes, and stable across runs. */
    rc_hash_iterator_t iterator;
    memset(&iterator, 0, sizeof(iterator));
    iterator.path = CD_TEST_CUE;
    iterator.callbacks.cdreader = cdreader;
    iterator.callbacks.filereader.open = cd_stdio_open;
    iterator.callbacks.filereader.seek = cd_stdio_seek;
    iterator.callbacks.filereader.tell = cd_stdio_tell;
    iterator.callbacks.filereader.read = cd_stdio_read;
    iterator.callbacks.filereader.close = cd_stdio_close;
    char hash[33] = "";
    CHECK(rc_hash_generate(hash, RC_CONSOLE_NEO_GEO_CD, &iterator) == 1);
    CHECK_STR_EQ(hash, "1ac1ef01e96caf1be0d329331a4fc2a8");
    char hash2[33] = "";
    CHECK(rc_hash_generate(hash2, RC_CONSOLE_NEO_GEO_CD, &iterator) == 1);
    CHECK_STR_EQ(hash, hash2);

    remove(CD_TEST_CUE);
    remove(CD_TEST_BIN);
}

#define CD_TEST_CHD CD_TEST_DIR "/fake.chd"

/* Big-endian writers for the CHD header, map, and metadata. */
static void cd_put_be32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static void cd_put_be64(uint8_t* p, uint64_t v) {
    p[0] = (uint8_t)(v >> 56);
    p[1] = (uint8_t)(v >> 48);
    p[2] = (uint8_t)(v >> 40);
    p[3] = (uint8_t)(v >> 32);
    p[4] = (uint8_t)(v >> 24);
    p[5] = (uint8_t)(v >> 16);
    p[6] = (uint8_t)(v >> 8);
    p[7] = (uint8_t)v;
}

/* A synthetic uncompressed CHD v5 holding the same fake disc
 * as build_fake_disc (20 raw sectors, 2352 bytes each, + 96
 * zero subcode bytes per frame = 2448-byte frames). The layout
 * is the CHD v5 on-disk format, hand-built: a 124-byte header
 * (no compression — compressors[0] == 0), an uncompressed map
 * (one big-endian uint32 entry per hunk — the hunk's file
 * offset divided by hunkbytes), zero padding up to the first
 * hunkbytes-aligned offset, the hunk data, and one CHTR2 track
 * metadata entry. The hunk holds 4 frames, so
 * the 20 frames span 5 hunks and the reader's frame-offset
 * arithmetic is exercised. The hash must come out the same as
 * the .cue + .bin disc: the MD5 of the 16 PRG bytes. */
static void build_fake_chd(void) {
    static const uint32_t kFrames = 20;
    static const uint32_t kUnitBytes = 2448;
    static const uint32_t kHunkBytes = 4 * 2448;
    static const uint32_t kHunks = 5;
    static const uint64_t kLogicalBytes = (uint64_t)kFrames * kUnitBytes;
    static const uint64_t kMapOffset = 124;
    /* The hunk data starts at the first hunkbytes-aligned offset
     * after the header + map (the uncompressed map entry is the
     * file offset divided by hunkbytes — libchdr's read path
     * computes blockoffs = entry * hunkbytes — and entry 0 means
     * "all zeros", so the first hunk cannot sit at offset 0).
     * Header 124 + map 20 = 144, so the first hunk starts at
     * 9792 (1 * hunkbytes) and entry[h] = 1 + h. */
    static const uint64_t kDataStart =
        ((124 + (uint64_t)kHunks * 4 + kHunkBytes - 1) / kHunkBytes) * kHunkBytes;
    static const uint64_t kMetaOffset = kDataStart + (uint64_t)kHunks * kHunkBytes;

    /* The 20 raw sectors, as in build_fake_disc (the PVD, root,
     * IPL.TXT and GAME.PRG are sectors 16-19; the rest are
     * zeroed user data). Rebuilt here so this test stands alone. */
    static const char prg[16] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    };
    static const char ipl[] = "GAME.PRG\n";
    uint8_t pvd[2048];
    memset(pvd, 0, sizeof(pvd));
    pvd[0] = 1;
    memcpy(pvd + 1, "CD001", 5);
    pvd[6] = 1;
    pvd[128] = 0x00;
    pvd[129] = 0x08;
    {
        uint8_t root_rec[34];
        memset(root_rec, 0, sizeof(root_rec));
        root_rec[0] = 34;
        root_rec[2] = 17;
        root_rec[10] = 0x00;
        root_rec[11] = 0x08;
        root_rec[25] = 2;
        root_rec[28] = 1;
        root_rec[31] = 1;
        root_rec[32] = 1;
        root_rec[33] = 0;
        memcpy(pvd + 156, root_rec, 34);
    }
    uint8_t root[2048];
    memset(root, 0, sizeof(root));
    {
        size_t off = 0;
        uint8_t rec[64];
        size_t len;
        static const uint8_t dot[1] = { 0x00 };
        static const uint8_t dotdot[1] = { 0x01 };
        len = cd_dir_record(17, 2048, 2, dot, 1, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(17, 2048, 2, dotdot, 1, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(18, (uint32_t)sizeof(ipl) - 1, 0,
                            (const uint8_t*)"IPL.TXT;1", 9, rec);
        memcpy(root + off, rec, len);
        off += len;
        len = cd_dir_record(19, 16, 0,
                            (const uint8_t*)"GAME.PRG;1", 10, rec);
        memcpy(root + off, rec, len);
        off += len;
        (void)off;
    }
    uint8_t ipl_data[2048];
    memset(ipl_data, 0, sizeof(ipl_data));
    memcpy(ipl_data, ipl, sizeof(ipl) - 1);
    uint8_t prg_data[2048];
    memset(prg_data, 0, sizeof(prg_data));
    memcpy(prg_data, prg, 16);

    FILE* f = fopen(CD_TEST_CHD, "wb");
    CHECK(f != NULL);
    if (!f)
        return;

    /* The 124-byte v5 header. */
    uint8_t header[124];
    memset(header, 0, sizeof(header));
    memcpy(header, "MComprHD", 8);
    cd_put_be32(header + 8, 124);
    cd_put_be32(header + 12, 5);
    /* compressors[4] stay 0: uncompressed, including the map. */
    cd_put_be64(header + 32, kLogicalBytes);
    cd_put_be64(header + 40, kMapOffset);
    cd_put_be64(header + 48, kMetaOffset);
    cd_put_be32(header + 56, kHunkBytes);
    cd_put_be32(header + 60, kUnitBytes);
    /* rawsha1 / sha1 / parentsha1 stay 0: libchdr does not
     * verify them on open. */
    fwrite(header, 1, sizeof(header), f);

    /* The uncompressed map: one big-endian entry per hunk — the
     * file offset of the hunk divided by hunkbytes. */
    uint32_t h;
    for (h = 0; h < kHunks; ++h) {
        uint8_t off[4];
        cd_put_be32(off, (uint32_t)(kDataStart / kHunkBytes) + h);
        fwrite(off, 1, sizeof(off), f);
    }

    /* Zero padding up to the first hunk. */
    uint8_t pad[1024];
    memset(pad, 0, sizeof(pad));
    uint64_t remaining = kDataStart - (kMapOffset + (uint64_t)kHunks * 4);
    while (remaining > 0) {
        size_t chunk = remaining < sizeof(pad) ? (size_t)remaining : sizeof(pad);
        fwrite(pad, 1, chunk, f);
        remaining -= chunk;
    }

    /* The hunk data: 20 frames, 4 per hunk. */
    uint8_t zeros[2048];
    memset(zeros, 0, sizeof(zeros));
    uint8_t subcode[96];
    memset(subcode, 0, sizeof(subcode));
    uint32_t i;
    for (i = 0; i < kFrames; ++i) {
        uint8_t sector[2352];
        const uint8_t* user = zeros;
        if (i == 16)
            user = pvd;
        else if (i == 17)
            user = root;
        else if (i == 18)
            user = ipl_data;
        else if (i == 19)
            user = prg_data;
        cd_raw_sector((int)i, user, sector);
        fwrite(sector, 1, sizeof(sector), f);
        fwrite(subcode, 1, sizeof(subcode), f);
    }

    /* One CHTR2 track metadata entry (no NUL in the file, as
     * chdman writes it — the reader must NUL-terminate its own
     * copy before parsing). */
    static const char track_meta[] =
        "TRACK:1 TYPE:MODE1_RAW SUBTYPE:NONE FRAMES:20 PREGAP:0 PGTYPE:NONE PGSUB:NONE POSTGAP:0";
    uint8_t meta_header[16];
    memset(meta_header, 0, sizeof(meta_header));
    cd_put_be32(meta_header + 0, 0x43485432); /* 'CHT2' */
    cd_put_be32(meta_header + 4, (uint32_t)sizeof(track_meta) - 1);
    cd_put_be64(meta_header + 8, 0); /* no next entry */
    fwrite(meta_header, 1, sizeof(meta_header), f);
    fwrite(track_meta, 1, sizeof(track_meta) - 1, f);
    fclose(f);
}

static void test_chd_reader(void) {
    rc_hash_cdreader_t cdreader;
    ra_cdreader_get(&cdreader);
    build_fake_chd();

    /* open_track: track 1 only, and a .chd. A file that is not
     * a CHD (even with a .chd name) fails to open. */
    void* track = cdreader.open_track(CD_TEST_CHD, 1);
    CHECK(track != NULL);
    CHECK(cdreader.open_track(CD_TEST_CHD, 2) == NULL);
    CHECK(cdreader.open_track(CD_TEST_CHD, 0) == NULL);
    if (!track) {
        remove(CD_TEST_CHD);
        return;
    }

    CHECK(cdreader.first_track_sector(track) == 0);

    uint8_t buf[2048];
    size_t n = cdreader.read_sector(track, 16, buf, sizeof(buf));
    CHECK(n == 2048 && memcmp(&buf[1], "CD001", 5) == 0);
    n = cdreader.read_sector(track, 18, buf, sizeof(buf));
    CHECK(n == 2048 && memcmp(buf, "GAME.PRG\n", 9) == 0);
    n = cdreader.read_sector(track, 19, buf, sizeof(buf));
    CHECK(n == 2048 && buf[0] == 0x00 && buf[15] == 0x0F);
    CHECK(cdreader.read_sector(track, 0, buf, sizeof(buf)) == 2048);
    /* A multi-sector read crosses the hunk boundary (4 frames
     * per hunk): sectors 3-6 span hunks 0 and 1. */
    uint8_t buf3[3 * 2048];
    n = cdreader.read_sector(track, 3, buf3, sizeof(buf3));
    CHECK(n == 3 * 2048);
    cdreader.close_track(track);

    /* The full hash, through rcheevos: the same MD5 as the
     * .cue + .bin disc — the MD5 of the 16 PRG bytes. */
    rc_hash_iterator_t iterator;
    memset(&iterator, 0, sizeof(iterator));
    iterator.path = CD_TEST_CHD;
    iterator.callbacks.cdreader = cdreader;
    iterator.callbacks.filereader.open = cd_stdio_open;
    iterator.callbacks.filereader.seek = cd_stdio_seek;
    iterator.callbacks.filereader.tell = cd_stdio_tell;
    iterator.callbacks.filereader.read = cd_stdio_read;
    iterator.callbacks.filereader.close = cd_stdio_close;
    char hash[33] = "";
    CHECK(rc_hash_generate(hash, RC_CONSOLE_NEO_GEO_CD, &iterator) == 1);
    CHECK_STR_EQ(hash, "1ac1ef01e96caf1be0d329331a4fc2a8");
    char hash2[33] = "";
    CHECK(rc_hash_generate(hash2, RC_CONSOLE_NEO_GEO_CD, &iterator) == 1);
    CHECK_STR_EQ(hash, hash2);

    remove(CD_TEST_CHD);
}

/* The full flow through the wrapper with the mock server and
 * mock RAM: login -> load game -> frame (unlock) -> serialize
 * -> reset -> deserialize -> achievements list. */
static void test_cheat_write(void) {
    uint8_t ram[0x200000];
    memset(ram, 0, sizeof(ram));

    /* The offset map: carts read the work RAM from offset 0,
     * discs from the PRAM at 0x100000. The cheat address is a
     * 68k work-RAM address (0x100000-0x10FFFF). */
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x10D0E9, sizeof(ram)) == 0xD0E9);
    CHECK(ra_cheat_offset(RA_CONSOLE_NEO_GEO_CD, 0x10D0E9, sizeof(ram)) == 0x10D0E9);
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x100000, sizeof(ram)) == 0);
    CHECK(ra_cheat_offset(RA_CONSOLE_NEO_GEO_CD, 0x10FFFF, sizeof(ram)) == 0x10FFFF);
    /* Outside the work RAM: invalid. */
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x0FFFFF, sizeof(ram)) == (size_t)-1);
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x110000, sizeof(ram)) == (size_t)-1);
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x000000, sizeof(ram)) == (size_t)-1);
    /* Unknown console: invalid. */
    CHECK(ra_cheat_offset(0, 0x10D0E9, sizeof(ram)) == (size_t)-1);
    /* RAM too small for the disc offset: invalid (a cart-sized
     * buffer must never satisfy a disc write). */
    CHECK(ra_cheat_offset(RA_CONSOLE_NEO_GEO_CD, 0x10D0E9, 0x10000) == (size_t)-1);
    CHECK(ra_cheat_offset(RA_CONSOLE_ARCADE, 0x10D0E9, 0) == (size_t)-1);

    /* The write path, through the provider: no game loaded yet,
     * so nothing is written. */
    memset(g_mock_ram, 0, sizeof(g_mock_ram));
    CHECK(ra_init(mock_memory_provider, NULL, mock_server_call, NULL, mock_event, NULL) == 0);
    CHECK(!ra_cheat_write(0x10D0E9, 0x09));
    CHECK(g_mock_ram[0xD0E9] == 0x00);

    /* Load the fake .neo game (a cart): the write lands in the
     * work RAM at offset 0xD0E9. Loading a game needs a login
     * (rcheevos reports "Login required" without one), so log in
     * first — the mock answers synchronously, as in the full-flow
     * test. The cheat write itself does not need the login (the
     * memory provider is registered at init); the login is only
     * the test's way to get a game loaded. */
    CHECK(ra_login_with_token("User", "ApiToken", mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    build_fake_neo();
    g_last_result = -999;
    CHECK(ra_load_game(RA_CONSOLE_ARCADE, kNeoPath, mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    CHECK(ra_cheat_write(0x10D0E9, 0x09));
    CHECK(g_mock_ram[0xD0E9] == 0x09);
    /* The disc offset is untouched: a cart write never lands in
     * the PRAM's work-RAM region. */
    CHECK(g_mock_ram[0x10D0E9] == 0x00);
    /* Out-of-range writes are no-ops and change nothing. */
    CHECK(!ra_cheat_write(0x0FFFFF, 0x09));
    CHECK(!ra_cheat_write(0x110000, 0x09));
    CHECK(g_mock_ram[0xD0E9] == 0x09);
    /* Unload: no game, no write. */
    ra_unload_game();
    CHECK(!ra_cheat_write(0x10D0E9, 0x09));
    ra_shutdown();
    remove(kNeoPath);
}

static void test_full_flow(void) {
    char* json;
    uint8_t* progress = NULL;
    size_t progress_size = 0;

    memset(g_mock_ram, 0, sizeof(g_mock_ram));
    g_server_calls = 0;
    g_unexpected_requests = 0;
    g_num_events = 0;
    g_last_result = -999;

    CHECK(ra_init(mock_memory_provider, NULL, mock_server_call, NULL, mock_event, NULL) == 0);
    CHECK(ra_is_ready());
    CHECK(!ra_is_logged_in());
    CHECK(!ra_is_game_loaded());

    /* Login (the mock answers the login2 call synchronously). */
    CHECK(ra_login_with_token("User", "ApiToken", mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    CHECK(ra_is_logged_in());
    CHECK_STR_EQ(ra_logged_in_username(), "User");

    /* Load the fake .neo game (the mock answers
     * achievementsets + startsession synchronously). */
    build_fake_neo();
    g_last_result = -999;
    CHECK(ra_load_game(RA_CONSOLE_ARCADE, kNeoPath, mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    CHECK(ra_is_game_loaded());
    CHECK_STR_EQ(ra_game_title(), "Sample Game");
    CHECK(g_unexpected_requests == 0);

    /* The mock game has no leaderboards and no rich presence
     * patch (v1.2.0, item 2): the accessors report that, and
     * the boards list is an empty array, not NULL. */
    {
        char presence[64];
        char* lb_json = ra_get_leaderboards_json();
        CHECK(!ra_has_leaderboards());
        CHECK(lb_json != NULL);
        if (lb_json) {
            CHECK_STR_EQ(lb_json, "[]");
            free(lb_json);
        }
        CHECK(!ra_has_rich_presence());
        /* No rich presence script, so rcheevos falls back to
         * "Playing <title>" — the message is still a real
         * display string, not empty. */
        CHECK(ra_get_rich_presence(presence, sizeof(presence)) > 0);
        CHECK_STR_EQ(presence, "Playing Sample Game");
    }

    /* Nothing unlocked yet: the triggers need RAM 0x0001/0x0002. */
    ra_do_frame();
    CHECK(g_num_events == 0);

    /* Set the RAM so achievement 5501's trigger is true
     * (0xH0001=3, 0xH0002=7) and run a frame. */
    g_mock_ram[0x0001] = 3;
    g_mock_ram[0x0002] = 7;
    ra_do_frame();
    CHECK(g_num_events == 1);
    if (g_num_events >= 1) {
        CHECK(g_events[0].type == RA_EVENT_ACHIEVEMENT_TRIGGERED);
        CHECK(g_events[0].id == 5501);
        CHECK_STR_EQ(g_events[0].title, "Ach1");
        CHECK(g_events[0].points == 5);
    }
    /* Achievement 5502 needs 0x0001=2, 0x0002=9 — still locked. */
    json = ra_get_achievements_json();
    CHECK(json != NULL);
    if (json) {
        CHECK(strstr(json, "\"id\":5501") != NULL);
        CHECK(strstr(json, "\"title\":\"Ach1\"") != NULL);
        CHECK(strstr(json, "\"id\":5502") != NULL);
        CHECK(strstr(json, "\"title\":\"Ach2\"") != NULL);
        CHECK(strstr(json, "\"state\":2") != NULL); /* 5501 unlocked */
        CHECK(strstr(json, "\"state\":1") != NULL); /* 5502 active */
        free(json);
    }

    /* Serialize the progress, reset the runtime, and restore:
     * the unlock must come back, and the list must stay intact.
     * (A reset does not un-unlock an achievement — the award
     * was already recorded — so the reset is only checked for
     * not breaking the game, and the deserialize is checked
     * for restoring the runtime state.) */
    progress_size = ra_progress_size();
    CHECK(progress_size > 0);
    progress = (uint8_t*)malloc(progress_size);
    CHECK(progress != NULL);
    if (progress) {
        CHECK(ra_serialize_progress(progress, progress_size) == 0);
        ra_reset();
        CHECK(ra_is_game_loaded());
        CHECK(ra_deserialize_progress(progress, progress_size) == 0);
        json = ra_get_achievements_json();
        CHECK(json != NULL);
        if (json) {
            CHECK(strstr(json, "\"id\":5501") != NULL);
            CHECK(strstr(json, "\"state\":2") != NULL); /* 5501 still unlocked */
            CHECK(strstr(json, "\"id\":5502") != NULL);
            CHECK(strstr(json, "\"state\":1") != NULL); /* 5502 still active */
            free(json);
        }
        free(progress);
    }

    ra_unload_game();
    CHECK(!ra_is_game_loaded());
    ra_logout();
    CHECK(!ra_is_logged_in());
    ra_shutdown();
    CHECK(!ra_is_ready());
    remove(kNeoPath);
}

/* The password login (v1.1.2 — the API-key login is gone):
 * the request must carry the password (p=), never a token
 * (t=); on success the session token the server returned is
 * available for the app to store, and a later launch can
 * re-log in with that token alone. */
static void test_password_login(void) {
    g_server_calls = 0;
    g_unexpected_requests = 0;
    g_last_result = -999;
    g_last_post_data[0] = '\0';

    CHECK(ra_init(mock_memory_provider, NULL, mock_server_call, NULL, mock_event, NULL) == 0);
    CHECK(ra_is_ready());
    CHECK(!ra_is_logged_in());

    /* Password login: the mock answers login2 synchronously. */
    CHECK(ra_login_with_password("User", "SecretPass", mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    CHECK(ra_is_logged_in());
    CHECK_STR_EQ(ra_logged_in_username(), "User");
    /* The request sent the password, not a token. */
    CHECK(strstr(g_last_post_data, "p=SecretPass") != NULL);
    CHECK(strstr(g_last_post_data, "t=") == NULL);
    /* The token the server returned is what the app stores. */
    CHECK(ra_logged_in_token() != NULL);
    if (ra_logged_in_token())
        CHECK_STR_EQ(ra_logged_in_token(), "ApiToken");
    CHECK(g_unexpected_requests == 0);

    /* Log out, then re-log in with the stored token alone —
     * the next launch, with no password involved. */
    ra_logout();
    CHECK(!ra_is_logged_in());
    CHECK(ra_logged_in_token() == NULL);
    g_last_result = -999;
    CHECK(ra_login_with_token("User", "ApiToken", mock_result, NULL) == 0);
    CHECK(g_last_result == 0);
    CHECK(ra_is_logged_in());
    CHECK(strstr(g_last_post_data, "t=ApiToken") != NULL);
    CHECK(strstr(g_last_post_data, "p=") == NULL);

    ra_logout();
    CHECK(!ra_is_logged_in());
    ra_shutdown();
    CHECK(!ra_is_ready());
}

/* The leaderboards and rich presence (v1.2.0, item 2): with no
 * game loaded every accessor is a safe no-op — no boards, no
 * presence, and the entries fetch refuses without calling its
 * callback. Registering a NULL leaderboard handler is safe
 * too (the events are simply not forwarded). */
static int g_json_result_calls;

static void mock_json_result(int result, const char* error_message, const char* json, void* ctx) {
    (void)result;
    (void)error_message;
    (void)json;
    (void)ctx;
    ++g_json_result_calls;
}

static void test_leaderboards_no_game(void) {
    char presence[64];
    char* json;

    CHECK(ra_init(mock_memory_provider, NULL, mock_server_call, NULL, mock_event, NULL) == 0);
    CHECK(ra_is_ready());
    CHECK(!ra_is_game_loaded());

    ra_set_leaderboard_event_handler(NULL, NULL);
    CHECK(!ra_has_leaderboards());
    json = ra_get_leaderboards_json();
    CHECK(json == NULL);
    g_json_result_calls = 0;
    CHECK(ra_fetch_leaderboard_entries(1, 0, mock_json_result, NULL) == -1);
    CHECK(g_json_result_calls == 0);
    CHECK(!ra_has_rich_presence());
    memset(presence, 'x', sizeof(presence));
    CHECK(ra_get_rich_presence(presence, sizeof(presence)) == 0);
    CHECK(presence[0] == '\0');

    ra_shutdown();
    CHECK(!ra_is_ready());
}

int main(void) {
    test_console_id_for_path();
    test_read_memory_mapped();
    test_cheat_write();
    test_neo_hash();
    test_cdreader();
    test_chd_reader();
    test_password_login();
    test_leaderboards_no_game();
    test_full_flow();
    printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
