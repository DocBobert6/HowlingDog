/* HowlingDog Phase 10 — RetroAchievements client wrapper.
 *
 * Pure C, no JNI and no Android dependencies: the app-specific
 * pieces (the HTTP request, the unlock event, the async result)
 * are callbacks supplied by the bridge (bridge/jni/bridge.cpp),
 * which performs them through JNI into Kotlin. The host test
 * (tests/ra_client_test.c) supplies stubs for the same callbacks,
 * so this layer is fully testable on the host with a mock server
 * and mock RAM.
 *
 * The 68k memory map (the main technical risk, assessed in
 * PLAN.md §10): rcheevos addresses achievements in the console
 * address space, which for Neo Geo is the 64 KB work RAM at
 * 0x000000-0x00FFFF. The bridge exposes that RAM through the
 * libretro API (retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)):
 *   - carts (.neo, RC_CONSOLE_ARCADE): the 64 KB work RAM array,
 *     so RA address A reads SYSTEM_RAM[A].
 *   - discs (.cue/.chd/.m3u, RC_CONSOLE_NEO_GEO_CD): the 2 MB
 *     PRAM, whose work RAM sits at offset 0x100000 (the 68k
 *     address of the work RAM), so RA address A reads
 *     SYSTEM_RAM[0x100000 + A]. The built-in rc_libretro helper
 *     would map it to SYSTEM_RAM[A] (wrong), so this layer does
 *     the translation itself — see ra_read_memory_mapped.
 * Only the work RAM is mapped. Addresses outside 0x000000-
 * 0x00FFFF read as unmapped (0 bytes), which makes rc_client
 * disable the achievements that reference them rather than
 * read garbage — the cart's backup RAM (the ARCADE map's
 * SAVE_RAM region) is not exposed by the core's libretro API.
 *
 * Hardcore mode is OFF (the default): HowlingDog is built
 * around save states, which hardcore forbids. Achievements
 * unlock in softcore. Rich presence and leaderboards are not
 * used in v1.1.0 — their events are ignored.
 */

#ifndef RA_CLIENT_H
#define RA_CLIENT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Console ids (rcheevos include/rc_consoles.h). Neo Geo AES/MVS
 * carts are filed under ARCADE — rcheevos hashes .neo files
 * there (MD5 of the file after the 4096-byte header) — and
 * Neo Geo CD discs under NEO_GEO_CD. */
#define RA_CONSOLE_ARCADE 27
#define RA_CONSOLE_NEO_GEO_CD 56

/* Event types forwarded to the app (rcheevos
 * RC_CLIENT_EVENT_*). Only the two below are forwarded. */
#define RA_EVENT_ACHIEVEMENT_TRIGGERED 1
#define RA_EVENT_GAME_COMPLETED 15

/* The core's SYSTEM_RAM, supplied by the bridge (which reads it
 * through the libretro API) or by a stub in the host test. */
typedef void (*ra_memory_provider_fn)(const uint8_t** data, size_t* size, void* ctx);

/* A server call the app must perform asynchronously (the
 * RetroAchievements web API). The app later delivers the
 * response with ra_http_response(request_id, ...). */
typedef void (*ra_server_call_fn)(int request_id, const char* url, const char* post_data, void* ctx);

/* An achievement event (unlock, game completed). */
typedef void (*ra_event_fn)(int type, uint32_t achievement_id, const char* title, const char* description, uint32_t points, void* ctx);

/* An async result (login, game load). result is 0 (RC_OK) on
 * success, non-zero on failure; error_message may be NULL. */
typedef void (*ra_result_fn)(int result, const char* error_message, void* ctx);

/* Console id for a game file, by extension. 0 when the file is
 * not a RetroAchievements game for this frontend (the caller
 * keeps RA inactive for it). */
uint32_t ra_console_id_for_path(const char* path);

/* The RA address -> core RAM translation (the 68k memory map).
 * Returns the number of bytes read (0 = unmapped). Pure and
 * host-testable: the RAM is passed in, not read globally. */
uint32_t ra_read_memory_mapped(uint32_t console_id, uint32_t address, uint8_t* buffer, uint32_t num_bytes,
                               const uint8_t* ram, size_t ram_size);

/* Lifecycle. Returns 0 on success. The callbacks are the
 * bridge's JNI implementations (or stubs in the host test). */
int ra_init(ra_memory_provider_fn memory_provider, void* memory_ctx,
            ra_server_call_fn server_call, void* server_ctx,
            ra_event_fn event_cb, void* event_ctx);
void ra_shutdown(void);
int ra_is_ready(void);

/* Login. Password login is the only login the app offers
 * (v1.1.2 — the API-key login is gone): the user types their
 * RetroAchievements username and website password, the server
 * hands back a session token, and the app stores the token
 * (never the password) for the next launch, which re-logs in
 * with it. Async: the result is delivered through result_cb. */
int ra_login_with_password(const char* username, const char* password, ra_result_fn result_cb, void* result_ctx);
int ra_login_with_token(const char* username, const char* token, ra_result_fn result_cb, void* result_ctx);
void ra_logout(void);
int ra_is_logged_in(void);
const char* ra_logged_in_username(void);
/* The session token of the logged-in user (NULL when logged
 * out) — the token the password login returned, for the app to
 * store and re-log in with on the next launch. */
const char* ra_logged_in_token(void);

/* Load a game (hash it and fetch its achievement set). Async:
 * the result is delivered through result_cb. console_id must
 * be RA_CONSOLE_ARCADE or RA_CONSOLE_NEO_GEO_CD. */
int ra_load_game(uint32_t console_id, const char* file_path, ra_result_fn result_cb, void* result_ctx);
void ra_unload_game(void);
int ra_is_game_loaded(void);
const char* ra_game_title(void);

/* Per-frame evaluation — call once per displayed frame, only
 * while a game is loaded. With run-ahead on, the re-simulation
 * runs must not call this; the Kotlin loop calls it once after
 * the displayed frame, so that holds. */
void ra_do_frame(void);

/* Reset the runtime (all achievements back to their initial
 * state) — used when a save state is loaded without an RA
 * sidecar, so the progress matches the rewound game state. */
void ra_reset(void);

/* Progress (the save-state sidecar). ra_progress_size is 0
 * when no game is loaded. */
size_t ra_progress_size(void);
int ra_serialize_progress(uint8_t* buffer, size_t buffer_size);
int ra_deserialize_progress(const uint8_t* buffer, size_t buffer_size);

/* The achievements list as a JSON array string (malloc'd; the
 * caller frees it). NULL when no game is loaded. Each entry:
 * id, title, description, points, state (0 inactive, 1 active,
 * 2 unlocked, 3 disabled), unlocked (0/1), badge_url, rarity,
 * measured_progress, measured_percent. */
char* ra_get_achievements_json(void);

/* Deliver an HTTP response for a pending server call (called by
 * the bridge when the app's HTTP request finishes). */
void ra_http_response(int request_id, const char* body, size_t body_length, int http_status_code);

#ifdef __cplusplus
}
#endif

#endif /* RA_CLIENT_H */
