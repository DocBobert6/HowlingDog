// HowlingDog Phase 2 — bare player spike.
//
// JNI bridge from the app.howlingdog frontend to the stock Geolith
// libretro core. The bridge lives in the frontend, NOT in the core.
// The core's src/ is untouched: the core is used exactly as libretro
// defines it: the frontend supplies the environment, video, audio
// and input callbacks, and drives retro_run() once per frame from
// its own thread.
//
// SPIKE-ONLY shortcuts, all marked as such — do not mistake them for
// the final implementation:
//   - Video is a Bitmap copy path (framebuffer -> int[] -> Bitmap).
//     Correct, but it copies every pixel every frame. The final
//     renderer (Phase 4+) will draw the core's framebuffer directly.
//   - Audio is resampled to 48 kHz in the bridge with a linear
//     interpolator, because the core's native rates (55943 Hz AES,
//     55555 Hz MVS, 44396 Hz CD) are outside what AudioTrack accepts
//     reliably. A proper resampler belongs in a later phase.
//   - Input is ports 0 and 1 (players 1 and 2). Player 1 is the
//     touch overlay and the first gamepad; player 2 is a second
//     gamepad (v1.5, feature 5). Ports 2 and 3 (the NEO-FTC1B
//     4-player extension board) are unanswered and read as 0.
//   - Core options are settable through nativeSetOption (Phase 4):
//     the bridge answers GET_VARIABLE from a mutable table seeded
//     with the core's documented defaults; a set flags
//     GET_VARIABLE_UPDATE so the core re-reads the live options on
//     the next frame. System types are first-run only in the core,
//     so a game's mode must be set before retro_init.
//   - Save states (Phase 4): nativeSerialize / nativeUnserialize
//     wrap the core's retro_serialize family. They must only be
//     called while the emulation thread is paused (never
//     concurrently with retro_run).

#include <jni.h>

#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libretro.h"
#include "runahead.h"
#include "ra_client.h"

// HowlingDog extension defined in libretro/libretro.c and exported
// via libretro/link.T: flush the in-memory save data (NVRAM /
// cartridge RAM / memory card / CD backup RAM) to disk without
// unloading the game. Not part of the libretro API.
extern "C" bool geo_flush_savedata(void);

#define LOG_TAG "HowlingDog"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

static char *g_system_dir = NULL; // BIOS directory (frontend-owned copy)
static char *g_save_dir = NULL;   // Save directory (app-private files)
static bool g_inited = false;
static bool g_loaded = false;

// Video: the core hands us a framebuffer pointer that is only valid
// for the duration of the video callback, so we copy it here.
static uint32_t *g_frame = NULL; // ARGB, max LSPC size (320x256)
static int g_frame_w = 0;
static int g_frame_h = 0;
static volatile bool g_frame_ready = false;
static pthread_mutex_t g_frame_mutex = PTHREAD_MUTEX_INITIALIZER;

#define FRAME_MAX_W 320
#define FRAME_MAX_H 256

// Audio: the core delivers batches at its own sample rate; we resample
// to 48 kHz (spike-only linear resampler, see header comment) into a
// ring buffer the Kotlin side drains into AudioTrack.
#define AUDIO_OUT_RATE 48000
#define AUDIO_RING_FRAMES 16384 // stereo frames
static int16_t g_audio_ring[AUDIO_RING_FRAMES * 2];
static int g_audio_head = 0; // write position (frames)
static int g_audio_tail = 0; // read position (frames)
static int g_audio_count = 0; // frames held
static bool g_audio_overflow = false;
static pthread_mutex_t g_audio_mutex = PTHREAD_MUTEX_INITIALIZER;

// Resampler state (input rate set from av_info after load).
static double g_resample_rate = 0.0; // core sample rate
static double g_resample_pos = 0.0;  // position in input frames
static int16_t g_resample_prev_l = 0;
static int16_t g_resample_prev_r = 0;
static bool g_resample_have_prev = false;

// Input: player-1 joypad bitmask, bits are (1 << RETRO_DEVICE_ID_JOYPAD_*).
static volatile int16_t g_input_mask = 0;
// v1.5, feature 5 — two-player multiplayer: the player-2 joypad
// bitmask, same bit layout. Set by nativeSetInput2; the core reads
// it when it polls port 1 (P2 directions/buttons, Coin 2, P2 Start).
static volatile int16_t g_input_mask_p2 = 0;

// Run-ahead (Phase 10): the state ring lives here, on the bridge,
// because the state bytes are native. The requested frame count is
// stored separately from the ring so it survives an unload and is
// re-applied (re-sized for the new game) on the next load. The ring
// is only touched on the emu thread (nativeRunFrameRunahead and
// nativeSetRunahead are both called from it or before it starts),
// so no lock is needed. When run-ahead is off the flag is 0 and
// nativeRunFrame() is used instead, so the off path is exactly the
// v1.0.0 frame path.
static runahead_t g_runahead;
static int g_runahead_frames = 0;

// Audio suppression for run-ahead re-simulation runs: while set,
// bridge_audio_batch drops the batch instead of queueing it, so
// the discarded ahead frames never reach the speakers.
static volatile bool g_audio_suppress = false;

// ---------------------------------------------------------------------------
// RetroAchievements (Phase 10)
// ---------------------------------------------------------------------------
//
// The RA client (ra_client.c) is pure C; this section is the JNI
// glue between it and the Kotlin side (Bridge / RaClient). The
// client is created once (nativeRaInit) and lives for the app's
// lifetime — the login persists across games, so it is NOT shut
// down in nativeDeinit; only the loaded game is unloaded, in
// nativeUnloadGame.
//
// Callbacks from native into Kotlin (all methods on the Bridge
// object, cached at nativeRaInit):
//   raHttpRequest(requestId, url, postData) — perform the HTTP
//     request asynchronously; deliver the result with
//     nativeRaHttpResponse.
//   raEventUnlocked(id, title, description, points) — an
//     achievement unlocked; the Kotlin side shows the popup.
//   raLeaderboardEvent(type, leaderboardId, json) — a
//     leaderboard event (tracker show/hide/update, scoreboard);
//     the Kotlin side shows the tracker or the scoreboard.
//   raLeaderboardEntriesResult(success, error, json) — the
//     entries fetch finished; the Kotlin side shows the list.
//   raLoginResult(success, error) — the login finished.
//   raLoadGameResult(success, error) — the game load finished.
//
// The callbacks run on the emu thread (unlock events, from
// do_frame) or the app's HTTP thread (login/load results, from
// the server response). Both are JVM-attached threads, but the
// helper attaches defensively if GetEnv reports detached.

static JavaVM *g_ra_vm = NULL;
static jobject g_ra_bridge = NULL; // global ref to the Bridge object
static jmethodID g_ra_http_request_method = NULL;
static jmethodID g_ra_event_unlocked_method = NULL;
static jmethodID g_ra_leaderboard_event_method = NULL;
static jmethodID g_ra_leaderboard_entries_result_method = NULL;
static jmethodID g_ra_login_result_method = NULL;
static jmethodID g_ra_load_game_result_method = NULL;

static JNIEnv *ra_get_env() {
    JNIEnv *env = NULL;
    if (!g_ra_vm)
        return NULL;
    if (g_ra_vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_ra_vm->AttachCurrentThread(&env, NULL) != JNI_OK)
            return NULL;
    }
    return env;
}

static void ra_call_http_request(int request_id, const char *url, const char *post_data) {
    JNIEnv *env = ra_get_env();
    jstring jurl;
    jstring jpost;
    if (!env || !g_ra_bridge || !g_ra_http_request_method)
        return;
    jurl = env->NewStringUTF(url ? url : "");
    jpost = post_data ? env->NewStringUTF(post_data) : NULL;
    env->CallVoidMethod(g_ra_bridge, g_ra_http_request_method, (jint)request_id, jurl, jpost);
    if (jurl)
        env->DeleteLocalRef(jurl);
    if (jpost)
        env->DeleteLocalRef(jpost);
    if (env->ExceptionCheck())
        env->ExceptionClear();
}

static void ra_call_event_unlocked(uint32_t id, const char *title, const char *description, uint32_t points) {
    JNIEnv *env = ra_get_env();
    jstring jtitle;
    jstring jdesc;
    if (!env || !g_ra_bridge || !g_ra_event_unlocked_method)
        return;
    jtitle = env->NewStringUTF(title ? title : "");
    jdesc = env->NewStringUTF(description ? description : "");
    env->CallVoidMethod(g_ra_bridge, g_ra_event_unlocked_method, (jint)id, jtitle, jdesc, (jint)points);
    if (jtitle)
        env->DeleteLocalRef(jtitle);
    if (jdesc)
        env->DeleteLocalRef(jdesc);
    if (env->ExceptionCheck())
        env->ExceptionClear();
}

static void ra_call_result(jmethodID method, int result, const char *error_message) {
    JNIEnv *env = ra_get_env();
    jstring jerror;
    if (!env || !g_ra_bridge || !method)
        return;
    jerror = error_message ? env->NewStringUTF(error_message) : NULL;
    env->CallVoidMethod(g_ra_bridge, method, result == 0 ? JNI_TRUE : JNI_FALSE, jerror);
    if (jerror)
        env->DeleteLocalRef(jerror);
    if (env->ExceptionCheck())
        env->ExceptionClear();
}

// The core's SYSTEM_RAM, through the libretro API. After the
// Phase 10 port-layer fix this is the 2 MB PRAM in CD mode and
// the 64 KB work RAM in cart mode (see libretro/libretro.c).
static void bridge_ra_memory_provider(const uint8_t **data, size_t *size, void *ctx) {
    void *ptr;
    size_t sz;
    (void)ctx;
    ptr = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
    sz = retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    *data = (const uint8_t *)ptr;
    *size = ptr ? sz : 0;
}

// The core's SAVE_RAM, through the libretro API. In cart mode
// this is the 64 KB MVS backup RAM (NVRAM); in CD mode the 8 KB
// backup RAM (BRAM). Registered as the RA save-RAM provider
// (v1.5.1) so achievements referencing the cart backup RAM
// read real values instead of unmapped zeroes.
static void bridge_ra_save_memory_provider(const uint8_t **data, size_t *size, void *ctx) {
    void *ptr;
    size_t sz;
    (void)ctx;
    ptr = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    *data = (const uint8_t *)ptr;
    *size = ptr ? sz : 0;
}

static void bridge_ra_server_call(int request_id, const char *url, const char *post_data, void *ctx) {
    (void)ctx;
    ra_call_http_request(request_id, url, post_data);
}

static void bridge_ra_event(int type, uint32_t achievement_id, const char *title, const char *description, uint32_t points, void *ctx) {
    (void)ctx;
    if (type == RA_EVENT_ACHIEVEMENT_TRIGGERED)
        ra_call_event_unlocked(achievement_id, title, description, points);
    /* GAME_COMPLETED has no popup in v1.1.0. */
}

// v1.2.0 (item 2): a leaderboard event — tracker show/hide/
// update or the scoreboard — forwarded to Kotlin with the
// JSON payload the wrapper built (NULL when the event carries
// none). Runs on the emu thread, like the unlock events.
static void bridge_ra_leaderboard_event(int type, uint32_t leaderboard_id, const char *json, void *ctx) {
    JNIEnv *env = ra_get_env();
    jstring jjson;
    (void)ctx;
    if (!env || !g_ra_bridge || !g_ra_leaderboard_event_method)
        return;
    jjson = json ? env->NewStringUTF(json) : NULL;
    env->CallVoidMethod(g_ra_bridge, g_ra_leaderboard_event_method, (jint)type, (jint)leaderboard_id, jjson);
    if (jjson)
        env->DeleteLocalRef(jjson);
    if (env->ExceptionCheck())
        env->ExceptionClear();
}

// v1.2.0 (item 2): the entries fetch finished — delivered on
// the app's HTTP thread, like the login/load results.
static void bridge_ra_leaderboard_entries_result(int result, const char *error_message, const char *json, void *ctx) {
    JNIEnv *env = ra_get_env();
    jstring jerror;
    jstring jjson;
    (void)ctx;
    if (!env || !g_ra_bridge || !g_ra_leaderboard_entries_result_method)
        return;
    jerror = error_message ? env->NewStringUTF(error_message) : NULL;
    jjson = json ? env->NewStringUTF(json) : NULL;
    env->CallVoidMethod(g_ra_bridge, g_ra_leaderboard_entries_result_method, result == 0 ? JNI_TRUE : JNI_FALSE, jerror, jjson);
    if (jerror)
        env->DeleteLocalRef(jerror);
    if (jjson)
        env->DeleteLocalRef(jjson);
    if (env->ExceptionCheck())
        env->ExceptionClear();
}

static void bridge_ra_login_result(int result, const char *error_message, void *ctx) {
    (void)ctx;
    ra_call_result(g_ra_login_result_method, result, error_message);
}

static void bridge_ra_load_game_result(int result, const char *error_message, void *ctx) {
    (void)ctx;
    ra_call_result(g_ra_load_game_result_method, result, error_message);
}

// AV info captured at load.
static int g_av_width = 0;
static int g_av_height = 0;
static double g_av_fps = 0.0;
static double g_av_rate = 0.0;

// Last core error line, for the UI to show on a failed load.
static char g_last_error[512] = {0};
static pthread_mutex_t g_error_mutex = PTHREAD_MUTEX_INITIALIZER;

static void set_last_error(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_error_mutex);
    strncpy(g_last_error, buf, sizeof(g_last_error) - 1);
    g_last_error[sizeof(g_last_error) - 1] = '\0';
    pthread_mutex_unlock(&g_error_mutex);
}

// ---------------------------------------------------------------------------
// Core log callback -> logcat (+ last-error capture)
// ---------------------------------------------------------------------------

static void bridge_log(enum retro_log_level level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    // The core's messages end in '\n'; logcat does not want it.
    size_t n = strlen(buf);
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r'))
        buf[--n] = '\0';
    int prio = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_ERROR)
        prio = ANDROID_LOG_ERROR;
    else if (level == RETRO_LOG_WARN)
        prio = ANDROID_LOG_WARN;
    else if (level == RETRO_LOG_DEBUG)
        prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, LOG_TAG, "%s", buf);
    if (level == RETRO_LOG_ERROR)
        set_last_error("%s", buf);
}

// ---------------------------------------------------------------------------
// libretro callbacks supplied by the frontend
// ---------------------------------------------------------------------------

// Core options (Phase 4: settable through nativeSetOption). The
// bridge answers GET_VARIABLE from a mutable table seeded with the
// core's documented defaults (libretro/libretro_core_options.h).
// The core only builds its palette LUTs inside check_variables(),
// which only runs the palette branch when GET_VARIABLE returns a
// value — answering "not found" left lut_normal/lut_shadow all
// zero, so every colour came out opaque black (the Phase 2 no-video
// bug). The value strings are static storage: the core may keep the
// pointer, so they must outlive the call. A set flags
// GET_VARIABLE_UPDATE so the core re-reads the live options on the
// next retro_run; system types are first-run only in the core, so
// they must be set before retro_init.
#define CORE_OPT_VALUE_LEN 32
static const struct {
    const char *key;
    const char *value; // documented default
} kCoreOptionDefaults[] = {
    {"geolith_system_type", "aes"},
    {"geolith_unibios_hw", "mvs"},
    {"geolith_cd_system_type", "cdz"},
    {"geolith_region", "us"},
    {"geolith_memcard", "on"},
    {"geolith_memcard_wp", "off"},
    {"geolith_settingmode", "off"},
    {"geolith_4player", "off"},
    {"geolith_freeplay", "off"},
    {"geolith_overscan_t", "8"},
    {"geolith_overscan_b", "8"},
    {"geolith_overscan_l", "8"},
    {"geolith_overscan_r", "8"},
    {"geolith_palette", "resnet"},
    {"geolith_aspect", "1:1"},
    {"geolith_sprlimit", "96"},
    {"geolith_oc", "off"},
    {"geolith_disable_adpcm_wrap", "off"},
    {"geolith_cd_speed_hack", "disabled"},
    {"geolith_cd_dma_len_limit", "disabled"},
    {"geolith_cd_skip_loading", "disabled"},
};

// Mutable current values, seeded from the defaults. Kotlin pushes
// the stored settings (and the per-game mode) through
// nativeSetOption before retro_init, and live changes while a game
// runs; GET_VARIABLE answers from here.
static char g_opt_values[sizeof(kCoreOptionDefaults) /
                         sizeof(kCoreOptionDefaults[0])][CORE_OPT_VALUE_LEN];
static bool g_opt_inited = false;
static pthread_mutex_t g_opt_mutex = PTHREAD_MUTEX_INITIALIZER;
// Set by nativeSetOption; GET_VARIABLE_UPDATE reports it once and
// clears it, so the core re-reads the live options on the next frame.
static volatile bool g_opt_dirty = false;

static void bridge_options_init(void) {
    pthread_mutex_lock(&g_opt_mutex);
    if (!g_opt_inited) {
        for (size_t i = 0;
             i < sizeof(kCoreOptionDefaults) / sizeof(kCoreOptionDefaults[0]);
             ++i) {
            strncpy(g_opt_values[i], kCoreOptionDefaults[i].value,
                    CORE_OPT_VALUE_LEN - 1);
            g_opt_values[i][CORE_OPT_VALUE_LEN - 1] = '\0';
        }
        g_opt_inited = true;
    }
    pthread_mutex_unlock(&g_opt_mutex);
}

static bool bridge_environ(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (!data)
                return false;
            enum retro_pixel_format fmt = *(enum retro_pixel_format *)data;
            // Geolith only ever asks for XRGB8888.
            return fmt == RETRO_PIXEL_FORMAT_XRGB8888;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            if (!data || !g_system_dir)
                return false;
            *(const char **)data = g_system_dir;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (!data || !g_save_dir)
                return false;
            *(const char **)data = g_save_dir;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            if (!data)
                return false;
            struct retro_log_callback *log = (struct retro_log_callback *)data;
            log->log = bridge_log;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            // We answer the mask query (see bridge_input_state).
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            // Answer from the mutable table (defaults, plus anything
            // Kotlin set through nativeSetOption). Unknown keys still
            // return false.
            if (!data)
                return false;
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var->key)
                return false;
            // Seed the table on first use: GET_VARIABLE is only
            // reached after retro_init (nativeInit seeds it), but
            // never answer from a zero-initialised buffer.
            bridge_options_init();
            for (size_t i = 0;
                 i < sizeof(kCoreOptionDefaults) /
                         sizeof(kCoreOptionDefaults[0]);
                 ++i) {
                if (strcmp(var->key, kCoreOptionDefaults[i].key) == 0) {
                    var->value = g_opt_values[i];
                    return true;
                }
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            // Report a pending options change once, then clear it.
            if (!data)
                return false;
            pthread_mutex_lock(&g_opt_mutex);
            bool dirty = g_opt_dirty;
            g_opt_dirty = false;
            pthread_mutex_unlock(&g_opt_mutex);
            *(bool *)data = dirty;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            // No VFS: the core falls back to its internal stdio
            // file I/O, which is what we want (real file paths).
            return false;
        default:
            return false;
    }
}

static void bridge_video(const void *data, unsigned width, unsigned height,
                         size_t pitch) {
    if (!data || width == 0 || height == 0)
        return;
    if (width > (unsigned)FRAME_MAX_W || height > (unsigned)FRAME_MAX_H) {
        LOGW("frame %ux%u exceeds spike buffer %dx%d, dropping",
             width, height, FRAME_MAX_W, FRAME_MAX_H);
        return;
    }
    pthread_mutex_lock(&g_frame_mutex);
    const uint32_t *src = (const uint32_t *)data;
    size_t src_stride = pitch / sizeof(uint32_t);
    for (unsigned y = 0; y < height; ++y) {
        const uint32_t *srow = src + (size_t)y * src_stride;
        uint32_t *drow = g_frame + (size_t)y * width;
        for (unsigned x = 0; x < width; ++x)
            drow[x] = srow[x] | 0xFF000000u; // XRGB8888 -> ARGB_8888
    }
    g_frame_w = (int)width;
    g_frame_h = (int)height;
    g_frame_ready = true;
    pthread_mutex_unlock(&g_frame_mutex);
}

// Spike-only linear resampler: core rate -> 48 kHz, stereo.
// Appends resampled frames to the ring buffer; drops the oldest
// frames (and flags it) if the reader falls behind.
static void bridge_audio_push(const int16_t *data, size_t frames) {
    if (g_resample_rate <= 0.0)
        return;
    const double step = g_resample_rate / (double)AUDIO_OUT_RATE;
    pthread_mutex_lock(&g_audio_mutex);
    for (size_t i = 0; i < frames; ++i) {
        int16_t in_l = data[i * 2];
        int16_t in_r = data[i * 2 + 1];
        if (!g_resample_have_prev) {
            g_resample_prev_l = in_l;
            g_resample_prev_r = in_r;
            g_resample_have_prev = true;
            g_resample_pos = 0.0;
            continue;
        }
        // g_resample_pos is the position of the next output sample
        // within [prev, cur]: 0 = at prev, 1 = at cur. Emit while it
        // falls inside this interval, then carry the remainder.
        while (g_resample_pos < 1.0) {
            double t = g_resample_pos;
            int32_t out_l = (int32_t)(g_resample_prev_l +
                (in_l - g_resample_prev_l) * t);
            int32_t out_r = (int32_t)(g_resample_prev_r +
                (in_r - g_resample_prev_r) * t);
            if (g_audio_count >= AUDIO_RING_FRAMES) {
                // Reader is behind: drop the oldest frame.
                g_audio_tail = (g_audio_tail + 1) % AUDIO_RING_FRAMES;
                g_audio_count--;
                g_audio_overflow = true;
            }
            g_audio_ring[g_audio_head * 2] = (int16_t)out_l;
            g_audio_ring[g_audio_head * 2 + 1] = (int16_t)out_r;
            g_audio_head = (g_audio_head + 1) % AUDIO_RING_FRAMES;
            g_audio_count++;
            g_resample_pos += step;
        }
        g_resample_pos -= 1.0;
        g_resample_prev_l = in_l;
        g_resample_prev_r = in_r;
    }
    pthread_mutex_unlock(&g_audio_mutex);
}

static size_t bridge_audio_batch(const int16_t *data, size_t frames) {
    if (g_audio_suppress) {
        // Run-ahead re-simulation run: the batch belongs to a
        // discarded ahead frame. Claim it as consumed so the core
        // keeps its timing, but queue nothing.
        (void)data;
        return frames;
    }
    bridge_audio_push(data, frames);
    return frames;
}

// Run-ahead callbacks (runahead.h): the state machine calls these;
// the bridge wires the real retro_* functions in.
static void ra_run_frame(void *ctx) {
    (void)ctx;
    retro_run();
}

static bool ra_serialize(void *ctx, uint8_t *buf) {
    (void)ctx;
    size_t size = retro_serialize_size();
    if (size == 0)
        return false;
    return retro_serialize((void *)buf, size);
}

static bool ra_unserialize(void *ctx, const uint8_t *buf) {
    (void)ctx;
    size_t size = retro_serialize_size();
    if (size == 0)
        return false;
    return retro_unserialize((const void *)buf, size);
}

static void ra_set_audio_suppress(void *ctx, bool suppress) {
    (void)ctx;
    g_audio_suppress = suppress;
}

// (Re)size the run-ahead ring for the loaded game. Called after a
// load (the state size is only known then) and when the setting
// changes. With no game loaded the ring is freed.
static void runahead_apply(void) {
    if (g_inited && g_loaded)
        runahead_set_frames(&g_runahead, retro_serialize_size(), g_runahead_frames);
    else
        runahead_set_frames(&g_runahead, 0, 0);
}

static void bridge_input_poll(void) {
    // Nothing to poll: the touch UI writes the mask directly.
}

static int16_t bridge_input_state(unsigned port, unsigned device,
                                  unsigned index, unsigned id) {
    (void)index;
    if (device != RETRO_DEVICE_JOYPAD)
        return 0;
    // v1.5, feature 5 — two-player multiplayer: port 0 is player 1,
    // port 1 is player 2. Ports 2 and 3 (the NEO-FTC1B 4-player
    // extension board) are unanswered and read as 0. The core asks
    // for the whole mask (RETRO_DEVICE_ID_JOYPAD_MASK) and for single
    // buttons; both read the same per-player mask.
    int16_t mask;
    if (port == 0) {
        mask = g_input_mask;
    } else if (port == 1) {
        mask = g_input_mask_p2;
    } else {
        return 0;
    }
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
        return mask;
    if (id < 16)
        return (mask & (int16_t)(1u << id)) ? 1 : 0;
    return 0;
}

// ---------------------------------------------------------------------------
// JNI entry points (app.howlingdog.Bridge)
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeInit(JNIEnv *env, jobject /* thiz */,
                                      jstring systemDir, jstring saveDir) {
    if (g_inited)
        return JNI_TRUE;
    const char *sys = env->GetStringUTFChars(systemDir, nullptr);
    const char *sav = env->GetStringUTFChars(saveDir, nullptr);
    if (!sys || !sav) {
        if (sys)
            env->ReleaseStringUTFChars(systemDir, sys);
        if (sav)
            env->ReleaseStringUTFChars(saveDir, sav);
        return JNI_FALSE;
    }
    g_system_dir = strdup(sys);
    g_save_dir = strdup(sav);
    env->ReleaseStringUTFChars(systemDir, sys);
    env->ReleaseStringUTFChars(saveDir, sav);
    if (!g_system_dir || !g_save_dir)
        return JNI_FALSE;

    g_frame = (uint32_t *)calloc((size_t)FRAME_MAX_W * FRAME_MAX_H,
                                 sizeof(uint32_t));
    if (!g_frame)
        return JNI_FALSE;

    // Seed the option table before the core reads it in retro_init.
    bridge_options_init();
    retro_set_environment(bridge_environ);
    retro_set_video_refresh(bridge_video);
    retro_set_audio_sample_batch(bridge_audio_batch);
    retro_set_input_poll(bridge_input_poll);
    retro_set_input_state(bridge_input_state);
    retro_init();
    g_inited = true;
    LOGI("bridge initialised (system=%s save=%s)", g_system_dir, g_save_dir);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeLoadGame(JNIEnv *env, jobject /* thiz */,
                                          jstring path) {
    if (!g_inited)
        return JNI_FALSE;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p)
        return JNI_FALSE;
    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = p;
    info.data = NULL;
    info.size = 0;
    bool ok = retro_load_game(&info);
    env->ReleaseStringUTFChars(path, p);
    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    retro_get_system_av_info(&av);
    g_av_width = (int)av.geometry.base_width;
    g_av_height = (int)av.geometry.base_height;
    g_av_fps = av.timing.fps;
    g_av_rate = av.timing.sample_rate;
    g_resample_rate = g_av_rate;
    g_resample_pos = 0.0;
    g_resample_have_prev = false;
    g_loaded = true;
    // Run-ahead (Phase 10): size the ring for this game now that
    // the state size is known.
    runahead_apply();
    LOGI("game loaded: %dx%d @ %.3f fps, audio %.1f Hz",
         g_av_width, g_av_height, g_av_fps, g_av_rate);
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_app_howlingdog_Bridge_nativeGetAvInfo(JNIEnv *env, jobject /* thiz */) {
    jintArray out = env->NewIntArray(4);
    if (!out)
        return nullptr;
    jint vals[4] = {g_av_width, g_av_height, (jint)(g_av_fps * 1000.0),
                    (jint)g_av_rate};
    env->SetIntArrayRegion(out, 0, 4, vals);
    return out;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRunFrame(JNIEnv * /* env */,
                                          jobject /* thiz */) {
    if (g_inited && g_loaded)
        retro_run();
}

// Run-ahead (Phase 10): 0 = off, 1 or 2 frames ahead. The setting
// is read at game start (and takes effect on the next game start
// when changed in Settings, like the CRT shader/curvature masters).
// Clamped to 0..2; with no game loaded only the requested value is
// stored, and the ring is (re)sized by runahead_apply() on load.
JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeSetRunahead(JNIEnv * /* env */,
                                             jobject /* thiz */,
                                             jint frames) {
    if (frames < 0)
        frames = 0;
    if (frames > RUNAHEAD_MAX_FRAMES)
        frames = RUNAHEAD_MAX_FRAMES;
    g_runahead_frames = (int)frames;
    runahead_apply();
}

// One displayed frame with run-ahead. When the setting is off the
// state machine degrades to a single plain retro_run(), but the
// Kotlin side calls nativeRunFrame() instead in that case, so the
// off path is exactly the v1.0.0 frame path.
JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRunFrameRunahead(JNIEnv * /* env */,
                                                  jobject /* thiz */) {
    if (!g_inited || !g_loaded)
        return;
    runahead_ops_t ops;
    ops.run_frame = ra_run_frame;
    ops.serialize = ra_serialize;
    ops.unserialize = ra_unserialize;
    ops.set_audio_suppress = ra_set_audio_suppress;
    ops.ctx = NULL;
    runahead_step(&g_runahead, &ops);
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeGetFrame(JNIEnv *env, jobject /* thiz */,
                                          jintArray pixels, jintArray dims) {
    if (!g_frame_ready)
        return JNI_FALSE;
    pthread_mutex_lock(&g_frame_mutex);
    int w = g_frame_w;
    int h = g_frame_h;
    jsize len = env->GetArrayLength(pixels);
    if (len < w * h) {
        pthread_mutex_unlock(&g_frame_mutex);
        return JNI_FALSE;
    }
    env->SetIntArrayRegion(pixels, 0, w * h, (const jint *)g_frame);
    g_frame_ready = false;
    pthread_mutex_unlock(&g_frame_mutex);
    jint d[2] = {w, h};
    env->SetIntArrayRegion(dims, 0, 2, d);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_app_howlingdog_Bridge_nativeReadAudio(JNIEnv *env, jobject /* thiz */,
                                           jshortArray samples,
                                           jint maxFrames) {
    pthread_mutex_lock(&g_audio_mutex);
    int frames = g_audio_count < maxFrames ? g_audio_count : (int)maxFrames;
    if (frames > 0) {
        // The ring may wrap; copy in up to two chunks.
        int first = AUDIO_RING_FRAMES - g_audio_tail;
        if (first > frames)
            first = frames;
        env->SetShortArrayRegion(samples, 0, first * 2,
                                 (const jshort *)(g_audio_ring +
                                                  (size_t)g_audio_tail * 2));
        if (frames > first) {
            env->SetShortArrayRegion(samples, first * 2, (frames - first) * 2,
                                     (const jshort *)g_audio_ring);
        }
        g_audio_tail = (g_audio_tail + frames) % AUDIO_RING_FRAMES;
        g_audio_count -= frames;
    }
    pthread_mutex_unlock(&g_audio_mutex);
    return frames;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeSetInput(JNIEnv * /* env */,
                                          jobject /* thiz */, jint mask) {
    g_input_mask = (int16_t)mask;
}

// v1.5, feature 5 — two-player multiplayer: the player-2 joypad
// mask, same bit layout as player 1. The core reads it when it
// polls port 1.
JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeSetInput2(JNIEnv * /* env */,
                                           jobject /* thiz */, jint mask) {
    g_input_mask_p2 = (int16_t)mask;
}

// Set one core option (Phase 4 settings / per-game mode). The value
// is stored in the mutable table and flags GET_VARIABLE_UPDATE, so
// the core re-reads the live options on the next frame. System types
// are first-run only in the core, so a game's mode must be set before
// retro_init (Kotlin pushes it before starting the session). Returns
// false for an unknown key. Safe to call before nativeInit.
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeSetOption(JNIEnv *env, jobject /* thiz */,
                                           jstring key, jstring value) {
    bridge_options_init();
    const char *k = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char *v = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    bool ok = false;
    if (k && v) {
        pthread_mutex_lock(&g_opt_mutex);
        for (size_t i = 0;
             i < sizeof(kCoreOptionDefaults) / sizeof(kCoreOptionDefaults[0]);
             ++i) {
            if (strcmp(k, kCoreOptionDefaults[i].key) == 0) {
                strncpy(g_opt_values[i], v, CORE_OPT_VALUE_LEN - 1);
                g_opt_values[i][CORE_OPT_VALUE_LEN - 1] = '\0';
                g_opt_dirty = true;
                ok = true;
                break;
            }
        }
        pthread_mutex_unlock(&g_opt_mutex);
    }
    if (k)
        env->ReleaseStringUTFChars(key, k);
    if (v)
        env->ReleaseStringUTFChars(value, v);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Save states (Phase 4). These must only be called while the
// emulation thread is paused — never concurrently with retro_run.
// retro_serialize copies the core's internal state buffer, so the
// size from retro_serialize_size() is the exact byte count.
JNIEXPORT jbyteArray JNICALL
Java_app_howlingdog_Bridge_nativeSerialize(JNIEnv *env, jobject /* thiz */) {
    if (!g_inited || !g_loaded)
        return nullptr;
    size_t size = retro_serialize_size();
    if (size == 0)
        return nullptr;
    jbyteArray out = env->NewByteArray((jsize)size);
    if (!out)
        return nullptr;
    jbyte *buf = env->GetByteArrayElements(out, nullptr);
    if (!buf)
        return nullptr;
    bool ok = retro_serialize((void *)buf, size);
    env->ReleaseByteArrayElements(out, buf, 0);
    if (!ok)
        return nullptr;
    return out;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeUnserialize(JNIEnv *env, jobject /* thiz */,
                                             jbyteArray data) {
    if (!g_inited || !g_loaded || !data)
        return JNI_FALSE;
    jsize len = env->GetArrayLength(data);
    if (len <= 0)
        return JNI_FALSE;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (!buf)
        return JNI_FALSE;
    // The core refuses a state saved under a different system type
    // or region (returns false); the UI reports that, it is not a
    // crash. JNI_ABORT: the core never writes into the buffer.
    bool ok = retro_unserialize((const void *)buf, (size_t)len);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Flush save data (Phase 9, item 5): writes the in-memory NVRAM /
// cartridge RAM / memory card / CD backup RAM to the save directory
// without unloading the game. Must only be called while the
// emulation thread is paused — never concurrently with retro_run —
// for the same reason as the save-state calls above:
// geo_savedata_save reads the core's live RAM buffers. False when
// no game is loaded or a save failed; quitting still writes the
// card as before, so a failed flush here loses nothing that quit
// would not also retry.
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeFlushSaveData(JNIEnv * /* env */,
                                               jobject /* thiz */) {
    if (!g_inited || !g_loaded)
        return JNI_FALSE;
    return geo_flush_savedata() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeUnloadGame(JNIEnv * /* env */,
                                            jobject /* thiz */) {
    if (g_inited && g_loaded) {
        retro_unload_game();
        g_loaded = false;
    }
    // Phase 10 (RetroAchievements): the game's achievements go
    // with it; the login stays (the client lives for the app's
    // lifetime).
    ra_unload_game();
    runahead_shutdown(&g_runahead);
    g_audio_suppress = false;
    pthread_mutex_lock(&g_audio_mutex);
    g_audio_head = g_audio_tail = g_audio_count = 0;
    pthread_mutex_unlock(&g_audio_mutex);
    g_frame_ready = false;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeDeinit(JNIEnv * /* env */,
                                        jobject /* thiz */) {
    if (g_inited) {
        if (g_loaded)
            retro_unload_game();
        retro_deinit();
        g_inited = false;
        g_loaded = false;
    }
    runahead_shutdown(&g_runahead);
    g_audio_suppress = false;
    free(g_frame);
    g_frame = NULL;
    free(g_system_dir);
    g_system_dir = NULL;
    free(g_save_dir);
    g_save_dir = NULL;
}

JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeLastError(JNIEnv *env, jobject /* thiz */) {
    pthread_mutex_lock(&g_error_mutex);
    jstring out = env->NewStringUTF(g_last_error);
    pthread_mutex_unlock(&g_error_mutex);
    return out;
}

// ---------------------------------------------------------------------------
// RetroAchievements (Phase 10) — JNI entry points.
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaInit(JNIEnv *env, jobject thiz) {
    jclass bridge_class;
    if (ra_is_ready())
        return JNI_TRUE;
    if (env->GetJavaVM(&g_ra_vm) != JNI_OK)
        return JNI_FALSE;
    if (!g_ra_bridge) {
        g_ra_bridge = env->NewGlobalRef(thiz);
        if (!g_ra_bridge)
            return JNI_FALSE;
        bridge_class = env->GetObjectClass(thiz);
        g_ra_http_request_method = env->GetMethodID(bridge_class, "raHttpRequest", "(ILjava/lang/String;Ljava/lang/String;)V");
        g_ra_event_unlocked_method = env->GetMethodID(bridge_class, "raEventUnlocked", "(ILjava/lang/String;Ljava/lang/String;I)V");
        g_ra_leaderboard_event_method = env->GetMethodID(bridge_class, "raLeaderboardEvent", "(IILjava/lang/String;)V");
        g_ra_leaderboard_entries_result_method = env->GetMethodID(bridge_class, "raLeaderboardEntriesResult", "(ZLjava/lang/String;Ljava/lang/String;)V");
        g_ra_login_result_method = env->GetMethodID(bridge_class, "raLoginResult", "(ZLjava/lang/String;)V");
        g_ra_load_game_result_method = env->GetMethodID(bridge_class, "raLoadGameResult", "(ZLjava/lang/String;)V");
        env->DeleteLocalRef(bridge_class);
        if (!g_ra_http_request_method || !g_ra_event_unlocked_method ||
            !g_ra_leaderboard_event_method || !g_ra_leaderboard_entries_result_method ||
            !g_ra_login_result_method || !g_ra_load_game_result_method) {
            LOGE("nativeRaInit: a Kotlin RA callback is missing");
            return JNI_FALSE;
        }
    }
    if (ra_init(bridge_ra_memory_provider, NULL, bridge_ra_server_call, NULL, bridge_ra_event, NULL) != 0)
        return JNI_FALSE;
    ra_set_leaderboard_event_handler(bridge_ra_leaderboard_event, NULL);
    ra_set_save_ram_provider(bridge_ra_save_memory_provider, NULL);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaLogin(JNIEnv *env, jobject /* thiz */, jstring username, jstring password) {
    const char *user;
    const char *pass;
    int rc;
    if (!ra_is_ready() || !username || !password)
        return JNI_FALSE;
    user = env->GetStringUTFChars(username, NULL);
    pass = env->GetStringUTFChars(password, NULL);
    if (!user || !pass) {
        if (user)
            env->ReleaseStringUTFChars(username, user);
        if (pass)
            env->ReleaseStringUTFChars(password, pass);
        return JNI_FALSE;
    }
    // The username and password are copied into the request by
    // rcheevos before this returns, so releasing them here is
    // safe. The password is used once, for this login only — it
    // is never stored anywhere (the session token the server
    // returns is what the app keeps; see nativeRaToken).
    rc = ra_login_with_password(user, pass, bridge_ra_login_result, NULL);
    env->ReleaseStringUTFChars(username, user);
    env->ReleaseStringUTFChars(password, pass);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// Re-log in with a stored session token (the token a password
// login returned on an earlier launch). No password involved.
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaLoginWithToken(JNIEnv *env, jobject /* thiz */, jstring username, jstring token) {
    const char *user;
    const char *tok;
    int rc;
    if (!ra_is_ready() || !username || !token)
        return JNI_FALSE;
    user = env->GetStringUTFChars(username, NULL);
    tok = env->GetStringUTFChars(token, NULL);
    if (!user || !tok) {
        if (user)
            env->ReleaseStringUTFChars(username, user);
        if (tok)
            env->ReleaseStringUTFChars(token, tok);
        return JNI_FALSE;
    }
    // As above: rcheevos copies the credentials into the request
    // before this returns.
    rc = ra_login_with_token(user, tok, bridge_ra_login_result, NULL);
    env->ReleaseStringUTFChars(username, user);
    env->ReleaseStringUTFChars(token, tok);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// The session token of the logged-in user (NULL when logged
// out) — what the app stores after a password login, so the
// next launch can re-log in without the password.
JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeRaToken(JNIEnv *env, jobject /* thiz */) {
    const char *token = ra_logged_in_token();
    return token ? env->NewStringUTF(token) : NULL;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRaLogout(JNIEnv * /* env */, jobject /* thiz */) {
    ra_logout();
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaIsLoggedIn(JNIEnv * /* env */, jobject /* thiz */) {
    return ra_is_logged_in() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeRaLoggedInUsername(JNIEnv *env, jobject /* thiz */) {
    const char *user = ra_logged_in_username();
    return user ? env->NewStringUTF(user) : NULL;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaLoadGame(JNIEnv *env, jobject /* thiz */, jstring path) {
    const char *cpath;
    uint32_t console_id;
    int rc;
    if (!ra_is_ready() || !path)
        return JNI_FALSE;
    cpath = env->GetStringUTFChars(path, NULL);
    if (!cpath)
        return JNI_FALSE;
    console_id = ra_console_id_for_path(cpath);
    if (console_id == 0) {
        // Not a RetroAchievements game for this frontend
        // (or an unsupported extension) — RA stays inactive.
        env->ReleaseStringUTFChars(path, cpath);
        return JNI_FALSE;
    }
    // The path is only used synchronously (to hash the file),
    // so releasing it here is safe.
    rc = ra_load_game(console_id, cpath, bridge_ra_load_game_result, NULL);
    env->ReleaseStringUTFChars(path, cpath);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRaUnloadGame(JNIEnv * /* env */, jobject /* thiz */) {
    ra_unload_game();
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaIsGameLoaded(JNIEnv * /* env */, jobject /* thiz */) {
    return ra_is_game_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeRaGameTitle(JNIEnv *env, jobject /* thiz */) {
    const char *title = ra_game_title();
    return title ? env->NewStringUTF(title) : NULL;
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRaDoFrame(JNIEnv * /* env */, jobject /* thiz */) {
    ra_do_frame();
}

JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRaReset(JNIEnv * /* env */, jobject /* thiz */) {
    ra_reset();
}

JNIEXPORT jbyteArray JNICALL
Java_app_howlingdog_Bridge_nativeRaSerializeProgress(JNIEnv *env, jobject /* thiz */) {
    size_t size = ra_progress_size();
    uint8_t *buf;
    jbyteArray out;
    if (size == 0)
        return NULL;
    buf = (uint8_t *)malloc(size);
    if (!buf)
        return NULL;
    if (ra_serialize_progress(buf, size) != 0) {
        free(buf);
        return NULL;
    }
    out = env->NewByteArray((jsize)size);
    if (out)
        env->SetByteArrayRegion(out, 0, (jsize)size, (const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaDeserializeProgress(JNIEnv *env, jobject /* thiz */, jbyteArray data) {
    jsize len;
    uint8_t *buf;
    int rc;
    if (!data)
        return JNI_FALSE;
    len = env->GetArrayLength(data);
    if (len <= 0)
        return JNI_FALSE;
    buf = (uint8_t *)malloc((size_t)len);
    if (!buf)
        return JNI_FALSE;
    env->GetByteArrayRegion(data, 0, len, (jbyte *)buf);
    rc = ra_deserialize_progress(buf, (size_t)len);
    free(buf);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// The achievements list as UTF-8 JSON (a byte array, not a
// string, so non-ASCII titles survive the JNI crossing intact).
JNIEXPORT jbyteArray JNICALL
Java_app_howlingdog_Bridge_nativeRaGetAchievements(JNIEnv *env, jobject /* thiz */) {
    char *json = ra_get_achievements_json();
    size_t len;
    jbyteArray out;
    if (!json)
        return NULL;
    len = strlen(json);
    out = env->NewByteArray((jsize)len);
    if (out)
        env->SetByteArrayRegion(out, 0, (jsize)len, (const jbyte *)json);
    free(json);
    return out;
}

// v1.2.0 (item 2): leaderboards and rich presence.
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaHasLeaderboards(JNIEnv * /* env */, jobject /* thiz */) {
    return ra_has_leaderboards() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_app_howlingdog_Bridge_nativeRaGetLeaderboards(JNIEnv *env, jobject /* thiz */) {
    char *json = ra_get_leaderboards_json();
    size_t len;
    jbyteArray out;
    if (!json)
        return NULL;
    len = strlen(json);
    out = env->NewByteArray((jsize)len);
    if (out)
        env->SetByteArrayRegion(out, 0, (jsize)len, (const jbyte *)json);
    free(json);
    return out;
}

// The result arrives asynchronously through
// raLeaderboardEntriesResult. Returns false when no game is
// loaded (the callback is not called then).
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaFetchLeaderboardEntries(JNIEnv * /* env */, jobject /* thiz */, jint leaderboard_id, jboolean around_user) {
    return ra_fetch_leaderboard_entries((uint32_t)leaderboard_id, around_user == JNI_TRUE ? 1 : 0, bridge_ra_leaderboard_entries_result, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeRaHasRichPresence(JNIEnv * /* env */, jobject /* thiz */) {
    return ra_has_rich_presence() ? JNI_TRUE : JNI_FALSE;
}

// The current rich presence message ("" when the game has
// none). The presence itself is sent to the server
// automatically by rc_client's periodic ping, so this only
// reads it for display.
JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeRaGetRichPresence(JNIEnv *env, jobject /* thiz */) {
    char buffer[512];
    if (ra_get_rich_presence(buffer, sizeof(buffer)) == 0)
        return env->NewStringUTF("");
    return env->NewStringUTF(buffer);
}

// The HTTP response body arrives as UTF-8 bytes (a byte array,
// not a string, so the JSON parses exactly as the server sent
// it). A null body is an empty response.
JNIEXPORT void JNICALL
Java_app_howlingdog_Bridge_nativeRaHttpResponse(JNIEnv *env, jobject /* thiz */, jint request_id, jbyteArray body, jint http_status) {
    jsize len;
    uint8_t *buf;
    if (!body) {
        ra_http_response((int)request_id, "", 0, (int)http_status);
        return;
    }
    len = env->GetArrayLength(body);
    if (len <= 0) {
        ra_http_response((int)request_id, "", 0, (int)http_status);
        return;
    }
    buf = (uint8_t *)malloc((size_t)len + 1);
    if (!buf) {
        ra_http_response((int)request_id, "", 0, 500);
        return;
    }
    env->GetByteArrayRegion(body, 0, len, (jbyte *)buf);
    buf[len] = '\0';
    ra_http_response((int)request_id, (const char *)buf, (size_t)len, (int)http_status);
    free(buf);
}

// v1.5, feature 4 — cheats: write one byte to the game's work
// RAM. Called from the emu thread after every displayed frame.
// False when no game is loaded or the address is outside the
// work RAM — a bad code is a no-op, never a crash.
JNIEXPORT jboolean JNICALL
Java_app_howlingdog_Bridge_nativeCheatWrite(JNIEnv * /* env */, jobject /* thiz */, jint address, jint value) {
    return ra_cheat_write((uint32_t)address, (uint8_t)value) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
