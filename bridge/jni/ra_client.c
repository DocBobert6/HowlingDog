/* HowlingDog Phase 10 — RetroAchievements client wrapper.
 * See ra_client.h for the design (the 68k memory map, the
 * async HTTP plumbing, the save-state sidecar).
 *
 * Threading: rc_client is not thread-safe on its own. Every
 * call into it happens on the thread that called the matching
 * ra_* function, except the server callback, which runs on the
 * thread that delivers the HTTP response (ra_http_response).
 * The app calls ra_do_frame once per displayed frame from the
 * emu thread and the rest from the main thread, and the HTTP
 * response is delivered from the app's HTTP thread. rc_client
 * serialises the server callbacks against the load/login state
 * internally, and the two threads never touch the runtime at
 * the same moment in this integration (a server response never
 * arrives while a frame is being evaluated — the response is
 * delivered between frames, and rc_client's own mutex guards
 * the shared state). The pending-HTTP table below is only
 * touched from the server_call path (emu/main thread) and
 * ra_http_response (HTTP thread); the request ids are unique,
 * so the two sides never claim the same slot.
 */

#include "ra_client.h"
#include "ra_cdreader.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_error.h"
#include "rc_hash.h"

/* ------------------------------------------------------------------ */
/* State                                                              */
/* ------------------------------------------------------------------ */

static rc_client_t* g_client;
static uint32_t g_console_id;

static ra_memory_provider_fn g_memory_provider;
static void* g_memory_ctx;
static ra_server_call_fn g_server_call;
static void* g_server_ctx;
static ra_event_fn g_event_cb;
static void* g_event_ctx;

/* The leaderboard event callback (v1.2.0, item 2): tracker
 * show/hide/update and the scoreboard. Registered separately
 * (ra_set_leaderboard_event_handler) so the achievement path
 * in ra_init stays untouched. */
static ra_leaderboard_event_fn g_leaderboard_event_cb;
static void* g_leaderboard_event_ctx;

/* Pending server calls (the app's async HTTP). The table is
 * small — rc_client has at most a couple of calls in flight
 * (login, game load, unlock, ping). */
#define RA_MAX_PENDING_HTTP 32
typedef struct {
    int in_use;
    int request_id;
    rc_client_server_callback_t callback;
    void* callback_data;
} ra_pending_http_t;
static ra_pending_http_t g_pending_http[RA_MAX_PENDING_HTTP];
static int g_next_request_id = 1;

/* An async result (login / game load): a heap holder passed as
 * the rc_client userdata and freed by the result callback. */
typedef struct {
    ra_result_fn fn;
    void* ctx;
} ra_result_holder_t;

static void ra_result_callback(int result, const char* error_message, rc_client_t* client, void* userdata) {
    ra_result_holder_t* holder = (ra_result_holder_t*)userdata;
    (void)client;
    if (holder) {
        if (holder->fn)
            holder->fn(result, error_message, holder->ctx);
        free(holder);
    }
}

/* ------------------------------------------------------------------ */
/* Console id by file                                                 */
/* ------------------------------------------------------------------ */

static int ra_has_extension(const char* path, const char* ext) {
    size_t path_len, ext_len;
    if (!path || !ext)
        return 0;
    path_len = strlen(path);
    ext_len = strlen(ext);
    if (path_len < ext_len)
        return 0;
    path += path_len - ext_len;
    while (*ext) {
        char a = *path++;
        char b = *ext++;
        if (a >= 'A' && a <= 'Z')
            a += 'a' - 'A';
        if (b >= 'A' && b <= 'Z')
            b += 'a' - 'A';
        if (a != b)
            return 0;
    }
    return 1;
}

uint32_t ra_console_id_for_path(const char* path) {
    if (!path)
        return 0;
    if (ra_has_extension(path, ".neo"))
        return RA_CONSOLE_ARCADE;
    if (ra_has_extension(path, ".cue") || ra_has_extension(path, ".chd") || ra_has_extension(path, ".m3u"))
        return RA_CONSOLE_NEO_GEO_CD;
    return 0;
}

/* ------------------------------------------------------------------ */
/* The 68k memory map                                                 */
/* ------------------------------------------------------------------ */

uint32_t ra_read_memory_mapped(uint32_t console_id, uint32_t address, uint8_t* buffer, uint32_t num_bytes,
                               const uint8_t* ram, size_t ram_size) {
    size_t base;
    size_t offset;

    if (!buffer || num_bytes == 0 || !ram || ram_size == 0)
        return 0;
    /* Only the 64 KB work RAM is mapped (0x000000-0x00FFFF). */
    if (address >= 0x10000)
        return 0;
    if ((size_t)address + num_bytes > 0x10000)
        num_bytes = (uint32_t)(0x10000 - (size_t)address);

    /* Carts read the work RAM from offset 0; discs read it from
     * the PRAM at offset 0x100000 (the 68k address of the work
     * RAM). See ra_client.h. */
    if (console_id == RA_CONSOLE_NEO_GEO_CD)
        base = 0x100000;
    else if (console_id == RA_CONSOLE_ARCADE)
        base = 0;
    else
        return 0;

    offset = base + (size_t)address;
    if (offset + num_bytes > ram_size)
        return 0;
    memcpy(buffer, ram + offset, num_bytes);
    return num_bytes;
}

static uint32_t ra_read_memory(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client) {
    const uint8_t* ram = NULL;
    size_t ram_size = 0;
    (void)client;
    if (g_memory_provider)
        g_memory_provider(&ram, &ram_size, g_memory_ctx);
    if (!ram || ram_size == 0)
        return 0;
    return ra_read_memory_mapped(g_console_id, address, buffer, num_bytes, ram, ram_size);
}

/* ------------------------------------------------------------------ */
/* Cheats (v1.5, feature 4)                                           */
/* ------------------------------------------------------------------ */

size_t ra_cheat_offset(uint32_t console_id, uint32_t address, size_t ram_size) {
    size_t base;
    size_t offset;
    /* The cheat address is a 68k work-RAM address. */
    if (address < 0x100000 || address > 0x10FFFF)
        return (size_t)-1;
    /* The same map as ra_read_memory_mapped: carts read the work
     * RAM from offset 0, discs from the PRAM at 0x100000. */
    if (console_id == RA_CONSOLE_NEO_GEO_CD)
        base = 0x100000;
    else if (console_id == RA_CONSOLE_ARCADE)
        base = 0;
    else
        return (size_t)-1;
    offset = base + (size_t)(address - 0x100000);
    if (offset >= ram_size)
        return (size_t)-1;
    return offset;
}

int ra_cheat_write(uint32_t address, uint8_t value) {
    const uint8_t* ram_const = NULL;
    size_t ram_size = 0;
    size_t offset;
    if (!g_memory_provider)
        return 0;
    /* No game loaded (or an unknown console): nothing to write
     * to — a cheat must never touch RAM it cannot place. */
    if (g_console_id != RA_CONSOLE_ARCADE && g_console_id != RA_CONSOLE_NEO_GEO_CD)
        return 0;
    g_memory_provider(&ram_const, &ram_size, g_memory_ctx);
    if (!ram_const || ram_size == 0)
        return 0;
    offset = ra_cheat_offset(g_console_id, address, ram_size);
    if (offset == (size_t)-1)
        return 0;
    /* The provider's buffer is the core's live RAM: the const is
     * the provider's signature, not the memory's nature — the
     * core writes to it every frame, and so does this, once per
     * displayed frame. Called from the emu thread between
     * frames, so no retro_run is in progress. */
    ((uint8_t*)ram_const)[offset] = value;
    return 1;
}

/* ------------------------------------------------------------------ */
/* Server calls (async HTTP)                                          */
/* ------------------------------------------------------------------ */

static void ra_server_call(const rc_api_request_t* request, rc_client_server_callback_t callback, void* callback_data, rc_client_t* client) {
    int slot = -1;
    int request_id;
    (void)client;
    for (int i = 0; i < RA_MAX_PENDING_HTTP; ++i) {
        if (!g_pending_http[i].in_use) {
            slot = i;
            break;
        }
    }
    if (slot < 0 || !g_server_call) {
        /* No room, or no app handler: fail the call at once so
         * rc_client does not wait forever. The callback may be
         * invoked synchronously — rcheevos supports that. */
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.body = "";
        response.body_length = 0;
        response.http_status_code = 500;
        callback(&response, callback_data);
        return;
    }
    request_id = g_next_request_id++;
    g_pending_http[slot].in_use = 1;
    g_pending_http[slot].request_id = request_id;
    g_pending_http[slot].callback = callback;
    g_pending_http[slot].callback_data = callback_data;
    g_server_call(request_id, request->url, request->post_data, g_server_ctx);
}

void ra_http_response(int request_id, const char* body, size_t body_length, int http_status_code) {
    for (int i = 0; i < RA_MAX_PENDING_HTTP; ++i) {
        if (g_pending_http[i].in_use && g_pending_http[i].request_id == request_id) {
            rc_client_server_callback_t callback = g_pending_http[i].callback;
            void* callback_data = g_pending_http[i].callback_data;
            rc_api_server_response_t response;
            g_pending_http[i].in_use = 0;
            memset(&response, 0, sizeof(response));
            response.body = body ? body : "";
            response.body_length = body_length;
            response.http_status_code = http_status_code;
            callback(&response, callback_data);
            return;
        }
    }
    /* Unknown id — the call already completed. Nothing to do. */
}

/* A growable string buffer for the JSON builder. */
typedef struct {
    char* data;
    size_t len;
    size_t cap;
} ra_json_buf_t;

static int ra_json_reserve(ra_json_buf_t* buf, size_t extra) {
    size_t needed = buf->len + extra + 1;
    char* grown;
    if (needed <= buf->cap)
        return 0;
    while (buf->cap < needed)
        buf->cap = buf->cap ? buf->cap * 2 : 256;
    grown = (char*)realloc(buf->data, buf->cap);
    if (!grown)
        return -1;
    buf->data = grown;
    return 0;
}

static int ra_json_append(ra_json_buf_t* buf, const char* text) {
    size_t len = strlen(text);
    if (ra_json_reserve(buf, len) != 0)
        return -1;
    memcpy(buf->data + buf->len, text, len);
    buf->len += len;
    buf->data[buf->len] = '\0';
    return 0;
}

static int ra_json_append_char(ra_json_buf_t* buf, char c) {
    char text[2];
    text[0] = c;
    text[1] = '\0';
    return ra_json_append(buf, text);
}

/* Append a JSON string (quoted, with ", \ and control characters
 * escaped). NULL renders as "". */
static int ra_json_append_string(ra_json_buf_t* buf, const char* text) {
    if (ra_json_append_char(buf, '"') != 0)
        return -1;
    if (text) {
        for (const unsigned char* p = (const unsigned char*)text; *p; ++p) {
            char escaped[8];
            if (*p == '"') {
                if (ra_json_append(buf, "\\\"") != 0)
                    return -1;
            } else if (*p == '\\') {
                if (ra_json_append(buf, "\\\\") != 0)
                    return -1;
            } else if (*p < 0x20) {
                snprintf(escaped, sizeof(escaped), "\\u%04x", *p);
                if (ra_json_append(buf, escaped) != 0)
                    return -1;
            } else {
                if (ra_json_append_char(buf, (char)*p) != 0)
                    return -1;
            }
        }
    }
    return ra_json_append_char(buf, '"');
}

static int ra_json_append_int(ra_json_buf_t* buf, long value) {
    char text[32];
    snprintf(text, sizeof(text), "%ld", value);
    return ra_json_append(buf, text);
}

static int ra_json_append_double(ra_json_buf_t* buf, double value) {
    char text[32];
    snprintf(text, sizeof(text), "%g", value);
    return ra_json_append(buf, text);
}

/* ------------------------------------------------------------------ */
/* Events                                                             */
/* ------------------------------------------------------------------ */

/* The leaderboard JSON payloads (v1.2.0, item 2). The tracker
 * carries its current display string; the scoreboard carries
 * the submitted and best scores (already formatted by
 * rcheevos), the new rank, the entry count, and the top
 * entries. Both are built into a caller-owned buffer and
 * freed after the callback returns. */
static char* ra_build_tracker_json(const rc_client_leaderboard_tracker_t* tracker) {
    ra_json_buf_t buf;
    memset(&buf, 0, sizeof(buf));
    ra_json_append(&buf, "{\"display\":");
    ra_json_append_string(&buf, tracker->display);
    ra_json_append(&buf, "}");
    return buf.data;
}

static char* ra_build_scoreboard_json(const rc_client_leaderboard_scoreboard_t* scoreboard) {
    ra_json_buf_t buf;
    uint32_t i;
    memset(&buf, 0, sizeof(buf));
    ra_json_append(&buf, "{\"submitted_score\":");
    ra_json_append_string(&buf, scoreboard->submitted_score);
    ra_json_append(&buf, ",\"best_score\":");
    ra_json_append_string(&buf, scoreboard->best_score);
    ra_json_append(&buf, ",\"new_rank\":");
    ra_json_append_int(&buf, scoreboard->new_rank);
    ra_json_append(&buf, ",\"num_entries\":");
    ra_json_append_int(&buf, scoreboard->num_entries);
    ra_json_append(&buf, ",\"top\":[");
    for (i = 0; i < scoreboard->num_top_entries; i++) {
        const rc_client_leaderboard_scoreboard_entry_t* entry = &scoreboard->top_entries[i];
        if (i > 0)
            ra_json_append_char(&buf, ',');
        ra_json_append(&buf, "{\"user\":");
        ra_json_append_string(&buf, entry->username);
        ra_json_append(&buf, ",\"display\":");
        ra_json_append_string(&buf, entry->score);
        ra_json_append(&buf, ",\"rank\":");
        ra_json_append_int(&buf, entry->rank);
        ra_json_append(&buf, "}");
    }
    ra_json_append(&buf, "]}");
    return buf.data;
}

static void ra_event_handler(const rc_client_event_t* event, rc_client_t* client) {
    (void)client;
    if (g_event_cb) {
        if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED && event->achievement) {
            g_event_cb(RA_EVENT_ACHIEVEMENT_TRIGGERED, event->achievement->id,
                       event->achievement->title, event->achievement->description,
                       event->achievement->points, g_event_ctx);
        } else if (event->type == RC_CLIENT_EVENT_GAME_COMPLETED) {
            g_event_cb(RA_EVENT_GAME_COMPLETED, 0, NULL, NULL, 0, g_event_ctx);
        }
    }
    /* Leaderboards (v1.2.0, item 2): the attempt is submitted
     * automatically by rc_client (the tracker is evaluated in
     * do_frame and the submission goes through the same async
     * HTTP plumbing as achievements), so the app only has to
     * show what happened. Challenge/progress indicators,
     * server errors and disconnects are still not used. */
    if (g_leaderboard_event_cb) {
        uint32_t leaderboard_id = 0;
        char* json = NULL;
        if (event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW ||
            event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE) {
            if (event->leaderboard_tracker) {
                leaderboard_id = event->leaderboard_tracker->id;
                json = ra_build_tracker_json(event->leaderboard_tracker);
            }
        } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE) {
            if (event->leaderboard_tracker)
                leaderboard_id = event->leaderboard_tracker->id;
        } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD) {
            if (event->leaderboard_scoreboard) {
                leaderboard_id = event->leaderboard_scoreboard->leaderboard_id;
                json = ra_build_scoreboard_json(event->leaderboard_scoreboard);
            }
        } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_STARTED ||
                   event->type == RC_CLIENT_EVENT_LEADERBOARD_FAILED ||
                   event->type == RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED) {
            if (event->leaderboard)
                leaderboard_id = event->leaderboard->id;
        } else {
            return;
        }
        g_leaderboard_event_cb((int)event->type, leaderboard_id, json, g_leaderboard_event_ctx);
        free(json);
    }
}

void ra_set_leaderboard_event_handler(ra_leaderboard_event_fn fn, void* ctx) {
    g_leaderboard_event_cb = fn;
    g_leaderboard_event_ctx = ctx;
}

/* ------------------------------------------------------------------ */
/* Lifecycle                                                          */
/* ------------------------------------------------------------------ */

int ra_init(ra_memory_provider_fn memory_provider, void* memory_ctx,
            ra_server_call_fn server_call, void* server_ctx,
            ra_event_fn event_cb, void* event_ctx) {
    if (g_client)
        return 0;
    g_memory_provider = memory_provider;
    g_memory_ctx = memory_ctx;
    g_server_call = server_call;
    g_server_ctx = server_ctx;
    g_event_cb = event_cb;
    g_event_ctx = event_ctx;
    g_client = rc_client_create(ra_read_memory, ra_server_call);
    if (!g_client)
        return -1;
    rc_client_set_event_handler(g_client, ra_event_handler);
    /* Softcore: HowlingDog is built around save states, which
     * hardcore forbids. */
    rc_client_set_hardcore_enabled(g_client, 0);
    /* The CD reader, so disc games can be hashed (Neo Geo CD):
     * without it the identify step fails for discs and RA stays
     * inactive for them. */
    {
        rc_hash_callbacks_t hash_callbacks;
        memset(&hash_callbacks, 0, sizeof(hash_callbacks));
        ra_cdreader_get(&hash_callbacks.cdreader);
        rc_client_set_hash_callbacks(g_client, &hash_callbacks);
    }
    return 0;
}

void ra_shutdown(void) {
    if (!g_client)
        return;
    rc_client_destroy(g_client);
    g_client = NULL;
    g_console_id = 0;
    memset(g_pending_http, 0, sizeof(g_pending_http));
}

int ra_is_ready(void) {
    return g_client != NULL;
}

/* ------------------------------------------------------------------ */
/* Login                                                              */
/* ------------------------------------------------------------------ */

/* Start one login (password or token): validate the inputs,
 * hold the result callback, and hand the call to rcheevos.
 * The result is always delivered through the callback, exactly
 * once — whether the login completes asynchronously (a handle
 * is returned) or synchronously (NULL is returned because the
 * user state already changed). A NULL return is therefore not
 * a failure; only the pre-validation below can fail without
 * the callback firing. */
static int ra_begin_login(rc_client_async_handle_t* (*begin_fn)(rc_client_t*, const char*, const char*,
                                                                rc_client_callback_t, void*),
                          const char* username, const char* credential,
                          ra_result_fn result_cb, void* result_ctx) {
    ra_result_holder_t* holder;
    if (!g_client || !username || !username[0] || !credential || !credential[0])
        return -1;
    holder = (ra_result_holder_t*)malloc(sizeof(*holder));
    if (!holder)
        return -1;
    holder->fn = result_cb;
    holder->ctx = result_ctx;
    begin_fn(g_client, username, credential, ra_result_callback, holder);
    return 0;
}

int ra_login_with_password(const char* username, const char* password, ra_result_fn result_cb, void* result_ctx) {
    return ra_begin_login(rc_client_begin_login_with_password, username, password, result_cb, result_ctx);
}

int ra_login_with_token(const char* username, const char* token, ra_result_fn result_cb, void* result_ctx) {
    return ra_begin_login(rc_client_begin_login_with_token, username, token, result_cb, result_ctx);
}

void ra_logout(void) {
    if (g_client)
        rc_client_logout(g_client);
}

int ra_is_logged_in(void) {
    return g_client != NULL && rc_client_get_user_info(g_client) != NULL;
}

const char* ra_logged_in_username(void) {
    const rc_client_user_t* user;
    if (!g_client)
        return NULL;
    user = rc_client_get_user_info(g_client);
    return user ? user->username : NULL;
}

const char* ra_logged_in_token(void) {
    const rc_client_user_t* user;
    if (!g_client)
        return NULL;
    user = rc_client_get_user_info(g_client);
    return user ? user->token : NULL;
}

/* ------------------------------------------------------------------ */
/* Game load                                                          */
/* ------------------------------------------------------------------ */

int ra_load_game(uint32_t console_id, const char* file_path, ra_result_fn result_cb, void* result_ctx) {
    ra_result_holder_t* holder;
    if (!g_client || !file_path || !file_path[0])
        return -1;
    if (console_id != RA_CONSOLE_ARCADE && console_id != RA_CONSOLE_NEO_GEO_CD)
        return -1;
    if (rc_client_is_game_loaded(g_client))
        rc_client_unload_game(g_client);
    g_console_id = console_id;
    holder = (ra_result_holder_t*)malloc(sizeof(*holder));
    if (!holder)
        return -1;
    holder->fn = result_cb;
    holder->ctx = result_ctx;
    /* As in ra_login: the result is always delivered through the
     * callback, exactly once — a NULL return means the load
     * already finished (or failed) synchronously, not that the
     * call failed to start. */
    rc_client_begin_identify_and_load_game(g_client, console_id, file_path, NULL, 0, ra_result_callback, holder);
    return 0;
}

void ra_unload_game(void) {
    if (g_client)
        rc_client_unload_game(g_client);
    g_console_id = 0;
}

int ra_is_game_loaded(void) {
    return g_client != NULL && rc_client_is_game_loaded(g_client);
}

const char* ra_game_title(void) {
    const rc_client_game_t* game;
    if (!g_client)
        return NULL;
    game = rc_client_get_game_info(g_client);
    return game ? game->title : NULL;
}

/* ------------------------------------------------------------------ */
/* Frame / reset / progress                                           */
/* ------------------------------------------------------------------ */

void ra_do_frame(void) {
    if (g_client && rc_client_is_game_loaded(g_client))
        rc_client_do_frame(g_client);
}

void ra_reset(void) {
    if (g_client)
        rc_client_reset(g_client);
}

size_t ra_progress_size(void) {
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return 0;
    return rc_client_progress_size(g_client);
}

int ra_serialize_progress(uint8_t* buffer, size_t buffer_size) {
    if (!g_client || !buffer)
        return -1;
    return rc_client_serialize_progress_sized(g_client, buffer, buffer_size);
}

int ra_deserialize_progress(const uint8_t* buffer, size_t buffer_size) {
    if (!g_client || !buffer)
        return -1;
    return rc_client_deserialize_progress_sized(g_client, buffer, buffer_size);
}

/* ------------------------------------------------------------------ */
/* Achievements list (JSON)                                           */
/* ------------------------------------------------------------------ */

char* ra_get_achievements_json(void) {
    rc_client_achievement_list_t* list;
    ra_json_buf_t buf;
    int first = 1;
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return NULL;
    list = rc_client_create_achievement_list(g_client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL,
        RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if (!list)
        return NULL;
    memset(&buf, 0, sizeof(buf));
    if (ra_json_append_char(&buf, '[') != 0)
        goto fail;
    for (uint32_t b = 0; b < list->num_buckets; ++b) {
        const rc_client_achievement_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_achievements; ++i) {
            const rc_client_achievement_t* a = bucket->achievements[i];
            if (!first && ra_json_append_char(&buf, ',') != 0)
                goto fail;
            first = 0;
            if (ra_json_append(&buf, "{\"id\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)a->id) != 0) goto fail;
            if (ra_json_append(&buf, ",\"title\":") != 0) goto fail;
            if (ra_json_append_string(&buf, a->title) != 0) goto fail;
            if (ra_json_append(&buf, ",\"description\":") != 0) goto fail;
            if (ra_json_append_string(&buf, a->description) != 0) goto fail;
            if (ra_json_append(&buf, ",\"points\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)a->points) != 0) goto fail;
            if (ra_json_append(&buf, ",\"state\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)a->state) != 0) goto fail;
            if (ra_json_append(&buf, ",\"unlocked\":") != 0) goto fail;
            if (ra_json_append_int(&buf, a->unlocked ? 1 : 0) != 0) goto fail;
            if (ra_json_append(&buf, ",\"badge_url\":") != 0) goto fail;
            if (ra_json_append_string(&buf, a->badge_url) != 0) goto fail;
            if (ra_json_append(&buf, ",\"rarity\":") != 0) goto fail;
            if (ra_json_append_double(&buf, (double)a->rarity) != 0) goto fail;
            if (ra_json_append(&buf, ",\"measured_progress\":") != 0) goto fail;
            if (ra_json_append_string(&buf, a->measured_progress) != 0) goto fail;
            if (ra_json_append(&buf, ",\"measured_percent\":") != 0) goto fail;
            if (ra_json_append_double(&buf, (double)a->measured_percent) != 0) goto fail;
            if (ra_json_append_char(&buf, '}') != 0) goto fail;
        }
    }
    if (ra_json_append_char(&buf, ']') != 0)
        goto fail;
    rc_client_destroy_achievement_list(list);
    return buf.data;

fail:
    free(buf.data);
    rc_client_destroy_achievement_list(list);
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Leaderboards and rich presence (v1.2.0, item 2)                  */
/* ------------------------------------------------------------------ */

int ra_has_leaderboards(void) {
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return 0;
    return rc_client_has_leaderboards(g_client);
}

char* ra_get_leaderboards_json(void) {
    rc_client_leaderboard_list_t* list;
    ra_json_buf_t buf;
    int first = 1;
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return NULL;
    list = rc_client_create_leaderboard_list(g_client, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE);
    if (!list)
        return NULL;
    memset(&buf, 0, sizeof(buf));
    if (ra_json_append_char(&buf, '[') != 0)
        goto fail;
    for (uint32_t b = 0; b < list->num_buckets; ++b) {
        const rc_client_leaderboard_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_leaderboards; ++i) {
            const rc_client_leaderboard_t* l = bucket->leaderboards[i];
            if (!first && ra_json_append_char(&buf, ',') != 0)
                goto fail;
            first = 0;
            if (ra_json_append(&buf, "{\"id\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)l->id) != 0) goto fail;
            if (ra_json_append(&buf, ",\"title\":") != 0) goto fail;
            if (ra_json_append_string(&buf, l->title) != 0) goto fail;
            if (ra_json_append(&buf, ",\"description\":") != 0) goto fail;
            if (ra_json_append_string(&buf, l->description) != 0) goto fail;
            if (ra_json_append(&buf, ",\"format\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)l->format) != 0) goto fail;
            if (ra_json_append(&buf, ",\"lower_is_better\":") != 0) goto fail;
            if (ra_json_append_int(&buf, l->lower_is_better ? 1 : 0) != 0) goto fail;
            if (ra_json_append(&buf, ",\"state\":") != 0) goto fail;
            if (ra_json_append_int(&buf, (long)l->state) != 0) goto fail;
            if (ra_json_append(&buf, ",\"tracker_value\":") != 0) goto fail;
            if (ra_json_append_string(&buf, l->tracker_value) != 0) goto fail;
            if (ra_json_append_char(&buf, '}') != 0) goto fail;
        }
    }
    if (ra_json_append_char(&buf, ']') != 0)
        goto fail;
    rc_client_destroy_leaderboard_list(list);
    return buf.data;

fail:
    free(buf.data);
    rc_client_destroy_leaderboard_list(list);
    return NULL;
}

/* The entries fetch is async: rcheevos fetches the list and
 * calls back with the entries; the holder carries the app's
 * result callback until then (the same one-shot pattern as the
 * login/load result holders above). */
typedef struct {
    ra_json_result_fn fn;
    void* ctx;
} ra_json_result_holder_t;

static void ra_leaderboard_entries_callback(int result, const char* error_message,
        rc_client_leaderboard_entry_list_t* list, rc_client_t* client, void* callback_userdata) {
    ra_json_result_holder_t* holder = (ra_json_result_holder_t*)callback_userdata;
    ra_json_buf_t buf;
    int first = 1;
    (void)client;
    memset(&buf, 0, sizeof(buf));
    if (result != RC_OK || !list) {
        holder->fn(result, error_message, NULL, holder->ctx);
        free(holder);
        return;
    }
    ra_json_append(&buf, "{\"entries\":[");
    for (uint32_t i = 0; i < list->num_entries; ++i) {
        const rc_client_leaderboard_entry_t* entry = &list->entries[i];
        if (!first)
            ra_json_append_char(&buf, ',');
        first = 0;
        ra_json_append(&buf, "{\"user\":");
        ra_json_append_string(&buf, entry->user);
        ra_json_append(&buf, ",\"display\":");
        ra_json_append_string(&buf, entry->display);
        ra_json_append(&buf, ",\"rank\":");
        ra_json_append_int(&buf, (long)entry->rank);
        ra_json_append(&buf, ",\"index\":");
        ra_json_append_int(&buf, (long)entry->index);
        ra_json_append(&buf, ",\"submitted\":");
        ra_json_append_int(&buf, (long)entry->submitted);
        ra_json_append_char(&buf, '}');
    }
    ra_json_append(&buf, "],\"total_entries\":");
    ra_json_append_int(&buf, (long)list->total_entries);
    ra_json_append(&buf, ",\"user_index\":");
    ra_json_append_int(&buf, (long)list->user_index);
    ra_json_append(&buf, "}");
    holder->fn(RC_OK, NULL, buf.data, holder->ctx);
    free(buf.data);
    free(holder);
    rc_client_destroy_leaderboard_entry_list(list);
}

int ra_fetch_leaderboard_entries(uint32_t leaderboard_id, int around_user, ra_json_result_fn result_cb, void* ctx) {
    ra_json_result_holder_t* holder;
    if (!g_client || !rc_client_is_game_loaded(g_client) || !result_cb)
        return -1;
    holder = (ra_json_result_holder_t*)malloc(sizeof(*holder));
    if (!holder)
        return -1;
    holder->fn = result_cb;
    holder->ctx = ctx;
    if (around_user)
        rc_client_begin_fetch_leaderboard_entries_around_user(g_client, leaderboard_id, 10, ra_leaderboard_entries_callback, holder);
    else
        rc_client_begin_fetch_leaderboard_entries(g_client, leaderboard_id, 0, 10, ra_leaderboard_entries_callback, holder);
    return 0;
}

int ra_has_rich_presence(void) {
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return 0;
    return rc_client_has_rich_presence(g_client);
}

size_t ra_get_rich_presence(char* buffer, size_t buffer_size) {
    if (!buffer || buffer_size == 0)
        return 0;
    buffer[0] = '\0';
    if (!g_client || !rc_client_is_game_loaded(g_client))
        return 0;
    return rc_client_get_rich_presence_message(g_client, buffer, buffer_size);
}
