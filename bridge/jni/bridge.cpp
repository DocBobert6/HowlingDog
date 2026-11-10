// HowlingDog Phase 2 — bare player spike.
//
// JNI bridge from the app.howlingdog frontend to the stock Geolith
// libretro core. The bridge lives in the frontend, NOT in the core.
// ONE exception, clearly marked TEMPORARY DIAGNOSTIC: the no-video
// bug probe (geo_diag_pal in src/geo.c) adds palette/VRAM write
// counters and a 68k-PC snapshot to the core so the DIAG readout can
// tell whether the game ever writes palette RAM. It counts only and
// does not change emulation behaviour; revert it with the rest of
// the diagnostic when the bug is fixed. Everything else under the
// core's src/ is untouched: the core is used exactly as libretro
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
//   - No save states, no pause, no core options UI. The environment
//     callback answers GET_VARIABLE with "not found" so the core runs
//     on its defaults (AES system, default crops).

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

// TEMPORARY DIAGNOSTIC (no-video bug): palette/VRAM write counters,
// first 8 palette RAM entries, palette bank, 68k PC — one-call
// snapshot from the core (src/geo.c, exported via libretro/link.T).
extern "C" void geo_diag_pal(uint32_t *out);

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

// Source probe (no-video bug): what the core actually delivered,
// sampled in bridge_video BEFORE the copy into g_frame. The on-screen
// DIAG samples the copied frame, so it cannot tell "the core
// delivers black" apart from "the copy loses the picture" — this
// can. Written under g_frame_mutex, read the same way.
static volatile long g_src_calls = 0; // bridge_video invocations
static long g_src_nonzero = 0; // non-zero uint32s in the last delivered frame
static uint32_t g_src_first = 0; // data[0] of the last delivered frame
static uint32_t g_src_first100 = 0; // data[100]
static uint32_t g_src_first1000 = 0; // data[1000]
static int g_src_w = 0;
static int g_src_h = 0;
static int g_src_pitch = 0;

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
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            // Phase 2 spike: no core-options UI, the core runs on
            // its defaults (AES system, default crops, etc.).
            return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            if (!data)
                return false;
            *(bool *)data = false;
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
    g_src_calls++;
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
    // Probe the delivered source BEFORE the copy: count non-zero
    // words over the width x height window and record the first few
    // raw words. If this is all zero while the game runs, the core
    // itself delivers black; if this is non-zero while the copied
    // frame is black, the copy is at fault. ~68k reads per frame.
    long nonzero = 0;
    for (unsigned y = 0; y < height; ++y) {
        const uint32_t *srow = src + (size_t)y * src_stride;
        for (unsigned x = 0; x < width; ++x)
            if (srow[x] != 0)
                ++nonzero;
    }
    size_t src_words = src_stride * (size_t)height;
    g_src_nonzero = nonzero;
    g_src_first = src[0];
    g_src_first100 = src_words > 100 ? src[100] : 0;
    g_src_first1000 = src_words > 1000 ? src[1000] : 0;
    g_src_w = (int)width;
    g_src_h = (int)height;
    g_src_pitch = (int)pitch;
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

// Source probe (no-video bug): what the core delivered, sampled in
// bridge_video before the copy. Returns [video calls, non-zero
// uint32s in the last delivered frame, data[0], data[100],
// data[1000], width, height, pitch bytes]. The raw words are
// returned as bit patterns (a jint holding 0xFF000000 reads
// negative); the Kotlin side formats them as hex.
JNIEXPORT jintArray JNICALL
Java_app_howlingdog_Bridge_nativeGetSrcProbe(JNIEnv *env, jobject /* thiz */) {
    jintArray out = env->NewIntArray(8);
    if (!out)
        return nullptr;
    pthread_mutex_lock(&g_frame_mutex);
    jint vals[8] = {
        (jint)g_src_calls,
        (jint)g_src_nonzero,
        (jint)g_src_first,
        (jint)g_src_first100,
        (jint)g_src_first1000,
        (jint)g_src_w,
        (jint)g_src_h,
        (jint)g_src_pitch,
    };
    pthread_mutex_unlock(&g_frame_mutex);
    env->SetIntArrayRegion(out, 0, 8, vals);
    return out;
}

// TEMPORARY DIAGNOSTIC (no-video bug): palette/VRAM write counters,
// first 8 palette RAM entries, palette bank, 68k PC — one-call
// snapshot from the core (geo_diag_pal). Returns [palram writes,
// vram writes, pal0..pal7, bank, pc]. Counts are 32-bit-truncated;
// pal values are 16-bit; PC is a 24-bit 68k address. Called from
// the emu thread in the once-a-second DIAG emission, the same thread
// that runs retro_run, so no locking is needed here.
JNIEXPORT jintArray JNICALL
Java_app_howlingdog_Bridge_nativeGetPalDiag(JNIEnv *env, jobject /* thiz */) {
    jintArray out = env->NewIntArray(12);
    if (!out)
        return nullptr;
    uint32_t vals[12];
    geo_diag_pal(vals);
    env->SetIntArrayRegion(out, 0, 12, (const jint *)vals);
    return out;
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
    pthread_mutex_lock(&g_frame_mutex);
    g_src_calls = 0;
    g_src_nonzero = 0;
    g_src_first = 0;
    g_src_first100 = 0;
    g_src_first1000 = 0;
    g_src_w = 0;
    g_src_h = 0;
    g_src_pitch = 0;
    pthread_mutex_unlock(&g_frame_mutex);
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

JNIEXPORT jstring JNICALL
Java_app_howlingdog_Bridge_nativeSystemInfo(JNIEnv *env, jobject /* thiz */) {
    struct retro_system_info info;
    memset(&info, 0, sizeof(info));
    retro_get_system_info(&info);
    char buf[128];
    snprintf(buf, sizeof(buf), "%s %s",
             info.library_name ? info.library_name : "Geolith",
             info.library_version ? info.library_version : "");
    return env->NewStringUTF(buf);
}

} // extern "C"
