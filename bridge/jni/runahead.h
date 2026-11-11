/*
 * Run-ahead latency reduction (Phase 10).
 *
 * The idea (the same as RetroArch's run-ahead): the core is kept
 * ahead of the display. Each displayed frame, the state saved at
 * the previous step is restored and the core is re-run with the
 * CURRENT input, so the game reacts to a press as if it had
 * happened one (or two) frames earlier. Per displayed frame the
 * cost is 1 state restore + N core runs + 1 state save.
 *
 * Pure C, no JNI and no Android dependencies, so it is tested on
 * the host (tests/runahead_test.c) with stub callbacks. The bridge
 * (bridge.cpp) wires the real retro_* functions in.
 *
 * Audio: the re-simulation runs' audio must be dropped, or the
 * ring buffer would run ahead of the display and stutter. The
 * bridge's audio callback checks the suppress flag the state
 * machine sets around those runs. Video needs no suppression:
 * each run overwrites the frame buffer, and the final (displayed)
 * run is the one the frontend reads.
 *
 * When frames is 0 the state machine is inert: step() runs the
 * core exactly once, so the off path is identical to a plain
 * retro_run() call.
 */
#ifndef RUNAHEAD_H
#define RUNAHEAD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RUNAHEAD_MAX_FRAMES 2

typedef struct {
    void (*run_frame)(void *ctx);
    bool (*serialize)(void *ctx, uint8_t *buf);
    bool (*unserialize)(void *ctx, const uint8_t *buf);
    void (*set_audio_suppress)(void *ctx, bool suppress);
    void *ctx;
} runahead_ops_t;

typedef struct {
    int frames; /* 0 = off, 1 or 2 frames ahead */
    size_t state_size;
    uint8_t *slots[RUNAHEAD_MAX_FRAMES];
    bool primed[RUNAHEAD_MAX_FRAMES];
    uint64_t frame_counter;
} runahead_t;

void runahead_init(runahead_t *ra, size_t state_size, int frames);
void runahead_set_frames(runahead_t *ra, size_t state_size, int frames);
void runahead_step(runahead_t *ra, const runahead_ops_t *ops);
void runahead_shutdown(runahead_t *ra);

#ifdef __cplusplus
}
#endif

#endif /* RUNAHEAD_H */
