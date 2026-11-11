/*
 * Run-ahead latency reduction (Phase 10). See runahead.h.
 */
#include "runahead.h"

#include <stdlib.h>

static int clamp_frames(int frames) {
    if (frames < 0)
        return 0;
    if (frames > RUNAHEAD_MAX_FRAMES)
        return RUNAHEAD_MAX_FRAMES;
    return frames;
}

void runahead_init(runahead_t *ra, size_t state_size, int frames) {
    ra->frames = 0;
    ra->state_size = 0;
    for (int i = 0; i < RUNAHEAD_MAX_FRAMES; i++) {
        ra->slots[i] = NULL;
        ra->primed[i] = false;
    }
    ra->frame_counter = 0;
    runahead_set_frames(ra, state_size, frames);
}

void runahead_set_frames(runahead_t *ra, size_t state_size, int frames) {
    frames = clamp_frames(frames);
    for (int i = 0; i < RUNAHEAD_MAX_FRAMES; i++) {
        free(ra->slots[i]);
        ra->slots[i] = NULL;
        ra->primed[i] = false;
    }
    ra->frames = frames;
    ra->state_size = (frames > 0) ? state_size : 0;
    ra->frame_counter = 0;
    if (frames > 0 && state_size > 0) {
        for (int i = 0; i < frames; i++) {
            ra->slots[i] = (uint8_t *)calloc(1, state_size);
            if (ra->slots[i] == NULL) {
                /* Allocation failed: run-ahead stays off rather
                   than running with a half-built ring. */
                runahead_set_frames(ra, 0, 0);
                return;
            }
        }
    }
}

void runahead_step(runahead_t *ra, const runahead_ops_t *ops) {
    if (ra->frames <= 0 || ra->state_size == 0 || ra->slots[0] == NULL) {
        /* Off, or no ring: exactly one plain core run, the same
           as a frame with run-ahead disabled. */
        ops->run_frame(ops->ctx);
        return;
    }
    int slot = (int)(ra->frame_counter % (uint64_t)ra->frames);
    if (!ra->primed[slot]) {
        /* Bootstrap: run the frame normally, then save the state
           so the next cycle has something to restore. */
        ops->run_frame(ops->ctx);
        if (ops->serialize(ops->ctx, ra->slots[slot]))
            ra->primed[slot] = true;
        ra->frame_counter++;
        return;
    }
    if (!ops->unserialize(ops->ctx, ra->slots[slot])) {
        /* The restore failed: run the frame normally and re-prime
           the slot, so a bad state can never wedge the game. */
        ra->primed[slot] = false;
        ops->run_frame(ops->ctx);
        if (ops->serialize(ops->ctx, ra->slots[slot]))
            ra->primed[slot] = true;
        ra->frame_counter++;
        return;
    }
    /* Re-simulate frames-1 frames ahead with the current input.
       Their video is overwritten by the displayed run and their
       audio is dropped (see runahead.h), so only the final run
       reaches the screen and the speakers. */
    if (ra->frames > 1)
        ops->set_audio_suppress(ops->ctx, true);
    for (int i = 0; i < ra->frames - 1; i++)
        ops->run_frame(ops->ctx);
    if (ra->frames > 1)
        ops->set_audio_suppress(ops->ctx, false);
    /* The displayed frame. */
    ops->run_frame(ops->ctx);
    if (!ops->serialize(ops->ctx, ra->slots[slot]))
        ra->primed[slot] = false;
    ra->frame_counter++;
}

void runahead_shutdown(runahead_t *ra) {
    runahead_set_frames(ra, 0, 0);
}
