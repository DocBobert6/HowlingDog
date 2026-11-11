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
//   - Input is port 0 only (player 1), touch only. Gamepad input
//     arrives in Phase 4.
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
    bridge_audio_push(data, frames);
    return frames;
}

static void bridge_input_poll(void) {
    // Nothing to poll: the touch UI writes the mask directly.
}

static int16_t bridge_input_state(unsigned port, unsigned device,
                                  unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD)
        return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK)
        return g_input_mask;
    if (id < 16)
        return (g_input_mask & (int16_t)(1u << id)) ? 1 : 0;
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

} // extern "C"
