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
 * address space. For ARCADE (Neo Geo carts) rcheevos defines
 * no memory regions of its own (rc_console_memory_regions
 * returns none), so the frontend's mapping is "system RAM
 * followed by save RAM" (rc_libretro_memory_init_without_regions):
 * the 64 KB work RAM at 0x000000-0x00FFFF, then the save RAM
 * at 0x010000-0x01FFFF. The bridge exposes both through the
 * libretro API (retro_get_memory_data):
 *   - SYSTEM_RAM: the 64 KB work RAM array, so RA address A
 *     reads SYSTEM_RAM[A].
 *   - SAVE_RAM: the 64 KB MVS backup RAM (NVRAM, 68k
 *     0xD00000-0xD0FFFF), so RA address 0x10000 + A reads
 *     SAVE_RAM[A]. v1.5.1: before that release SAVE_RAM was
 *     NULL for carts, so backup-RAM achievements read as
 *     unmapped (0 bytes) and rc_client disabled them.
 *   - discs (.cue/.chd/.m3u, RC_CONSOLE_NEO_GEO_CD): the 2 MB
 *     PRAM, whose work RAM sits at offset 0x100000 (the 68k
 *     address of the work RAM), so RA address A reads
 *     SYSTEM_RAM[0x100000 + A]. The built-in rc_libretro helper
 *     would map it to SYSTEM_RAM[A] (wrong), so this layer does
 *     the translation itself — see ra_read_memory_mapped. The
 *     disc backup RAM (BRAM, 68k 0x800000) is NOT mapped:
 *     rcheevos's NEO_GEO_CD regions end at 0x00FFFF, so
 *     rc_client never asks for it (it would read as unmapped
 *     anyway) — only the cart backup RAM is exposed.
 * Only the work RAM and (for carts) the backup RAM are mapped.
 * Addresses outside those ranges read as unmapped (0 bytes),
 * which makes rc_client disable the achievements that
 * reference them rather than read garbage.
 *
 * Hardcore mode is OFF (the default): HowlingDog is built
 * around save states, which hardcore forbids. Achievements
 * unlock in softcore. Rich presence and leaderboards are not
 * used in v1.1.0 — their events are ignored.
 *
 * v1.2.0 (items 2 and 3): leaderboards and rich presence are
 * wired up. Leaderboard submission is automatic — rc_client
 * evaluates the trackers in rc_client_do_frame and submits an
 * attempt through the same async HTTP plumbing as achievements
 * — so the app only has to forward the leaderboard events
 * (tracker show/hide/update, scoreboard) and offer a way to
 * list the boards and fetch their entries. Rich presence is
 * likewise automatic: the periodic ping rc_client schedules at
 * game load sends the current presence string to the server
 * through the same plumbing, so the app only reads the current
 * message (for display) — there is nothing to send by hand.
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
#define RA_EVENT_LEADERBOARD_STARTED 2
#define RA_EVENT_LEADERBOARD_FAILED 3
#define RA_EVENT_LEADERBOARD_SUBMITTED 4
#define RA_EVENT_LEADERBOARD_TRACKER_SHOW 10
#define RA_EVENT_LEADERBOARD_TRACKER_HIDE 11
#define RA_EVENT_LEADERBOARD_TRACKER_UPDATE 12
#define RA_EVENT_LEADERBOARD_SCOREBOARD 13
#define RA_EVENT_GAME_COMPLETED 15

/* The core's SYSTEM_RAM, supplied by the bridge (which reads it
 * through the libretro API) or by a stub in the host test. */
typedef void (*ra_memory_provider_fn)(const uint8_t** data, size_t* size, void* ctx);

/* The core's SAVE_RAM, same shape as the memory provider: for
 * carts the 64 KB MVS backup RAM (NVRAM), for discs the 8 KB
 * backup RAM (BRAM). Only the cart one is mapped into the RA
 * address space (see the header comment); the disc one is
 * registered but never read, so the bridge registers it
 * unconditionally and this layer decides. Registered
 * separately from ra_init (see ra_set_save_ram_provider) so
 * the achievement path stays untouched. */
typedef void (*ra_save_memory_provider_fn)(const uint8_t** data, size_t* size, void* ctx);

/* A server call the app must perform asynchronously (the
 * RetroAchievements web API). The app later delivers the
 * response with ra_http_response(request_id, ...). */
typedef void (*ra_server_call_fn)(int request_id, const char* url, const char* post_data, void* ctx);

/* An achievement event (unlock, game completed). */
typedef void (*ra_event_fn)(int type, uint32_t achievement_id, const char* title, const char* description, uint32_t points, void* ctx);

/* A leaderboard event (tracker show/hide/update, attempt
 * started/failed/submitted, scoreboard). The payload is a JSON
 * string (NULL when the event carries none): the tracker events
 * carry {"display":"..."} and the scoreboard carries
 * {"submitted_score","best_score","new_rank","num_entries",
 * "top":[{"username","rank","score"}]}. The wrapper builds the
 * JSON and frees it after the callback returns, so the app must
 * copy anything it wants to keep. Registered separately from
 * ra_init (see ra_set_leaderboard_event_handler) so the
 * achievement path stays untouched. */
typedef void (*ra_leaderboard_event_fn)(int type, uint32_t leaderboard_id, const char* json, void* ctx);

/* An async JSON result (a leaderboard entries fetch). json is
 * NULL on failure (result != 0); on success it is the entries
 * object, and the app must copy anything it wants to keep — the
 * wrapper frees it after the callback returns. */
typedef void (*ra_json_result_fn)(int result, const char* error_message, const char* json, void* ctx);

/* An async result (login, game load). result is 0 (RC_OK) on
 * success, non-zero on failure; error_message may be NULL. */
typedef void (*ra_result_fn)(int result, const char* error_message, void* ctx);

/* Console id for a game file, by extension. 0 when the file is
 * not a RetroAchievements game for this frontend (the caller
 * keeps RA inactive for it). */
uint32_t ra_console_id_for_path(const char* path);

/* The RA address -> core RAM translation (the 68k memory map).
 * Returns the number of bytes read (0 = unmapped). Pure and
 * host-testable: the RAM is passed in, not read globally.
 *   - work RAM: RA address A (0x000000-0x00FFFF) reads ram[A]
 *     for carts, ram[0x100000 + A] for discs.
 *   - cart backup RAM: RA address 0x10000 + A (0x010000-
 *     0x01FFFF) reads save_ram[A] — carts only, and only when
 *     a save-RAM provider is registered (save_ram NULL or
 *     save_ram_size 0 reads as unmapped). Discs and unknown
 *     consoles read 0x010000+ as unmapped.
 * A read is clamped at the end of the region it starts in: it
 * never crosses from the work RAM into the backup RAM (or past
 * the end of either buffer). The save region starts at the
 * fixed 0x10000 — the ARCADE work RAM is always 64 KB — not
 * at ram_size, so a larger SYSTEM_RAM buffer (a disc PRAM, a
 * test mock) cannot move it. */
uint32_t ra_read_memory_mapped(uint32_t console_id, uint32_t address, uint8_t* buffer, uint32_t num_bytes,
                               const uint8_t* ram, size_t ram_size,
                               const uint8_t* save_ram, size_t save_ram_size);

/* Cheats (v1.5, feature 4). A cheat writes one byte to the 68k
 * work RAM (0x100000-0x10FFFF) after every displayed frame.
 * ra_cheat_offset translates a cheat address to the SYSTEM_RAM
 * buffer offset for a console — the same map as
 * ra_read_memory_mapped (carts read the work RAM from offset 0,
 * discs from the PRAM at 0x100000) — or (size_t)-1 when the
 * address is outside the work RAM, the console is unknown, or
 * the offset would run past the RAM. Pure and host-testable.
 * ra_cheat_write writes the byte through the memory provider
 * (registered by ra_init): 1 on success, 0 when no game is
 * loaded, the provider is missing, or the address is invalid.
 * It works whether or not the user is logged in to RA — the
 * provider is registered at init, before any login. */
size_t ra_cheat_offset(uint32_t console_id, uint32_t address, size_t ram_size);
int ra_cheat_write(uint32_t address, uint8_t value);

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

/* Leaderboards (v1.2.0, item 2). The list is the loaded game's
 * boards as a JSON array string (malloc'd; the caller frees
 * it). NULL when no game is loaded. Each entry: id, title,
 * description, format (0 time, 1 score, 2 value),
 * lower_is_better (0/1), state, tracker_value ("" when the
 * board is not being attempted). The entries fetch is async:
 * around_user fetches the entries around the logged-in user,
 * otherwise the top entries (10, from the top); the result is
 * delivered through result_cb, exactly once. Returns -1 when
 * no game is loaded (the callback is not called). */
void ra_set_leaderboard_event_handler(ra_leaderboard_event_fn fn, void* ctx);

/* Register the SAVE_RAM provider (v1.5.1: the cart backup
 * RAM). Registered separately from ra_init, like the
 * leaderboard handler, so the achievement path stays
 * untouched. A NULL provider (the default) leaves the
 * backup-RAM range unmapped. */
void ra_set_save_ram_provider(ra_save_memory_provider_fn fn, void* ctx);
int ra_has_leaderboards(void);
char* ra_get_leaderboards_json(void);
int ra_fetch_leaderboard_entries(uint32_t leaderboard_id, int around_user, ra_json_result_fn result_cb, void* ctx);

/* Rich presence (v1.2.0, item 2). The presence string is sent
 * to the server automatically by rc_client's periodic ping
 * (driven by ra_do_frame), so the app never sends it by hand —
 * these only read the current state, for display. Returns 0
 * (and an empty message) when the game has no rich presence. */
int ra_has_rich_presence(void);
size_t ra_get_rich_presence(char* buffer, size_t buffer_size);

#ifdef __cplusplus
}
#endif

#endif /* RA_CLIENT_H */
