/*
 * Host test for the Phase 10 run-ahead state machine
 * (bridge/jni/runahead.c). Compiles on the host with a plain C
 * compiler — no core sources, no Android NDK, no game files:
 *
 *   gcc -I bridge/jni -o /tmp/runahead_test tests/runahead_test.c bridge/jni/runahead.c && /tmp/runahead_test
 *
 * The stub callbacks record the exact call sequence, so a wrong
 * ordering (a re-simulation run whose audio is not suppressed, a
 * displayed frame that is not the last run, a slot that is read
 * before it is primed) fails here instead of on device. The
 * state machine is the exact code the bridge calls.
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#include "runahead.h"

static int failures = 0;
static int checks = 0;

#define CHECK(desc, got, want) do { \
    ++checks; \
    unsigned _got = (unsigned)(got); \
    unsigned _want = (unsigned)(want); \
    if (_got != _want) { \
        printf("FAIL %s: got %u, want %u\n", (desc), _got, _want); \
        ++failures; \
    } else { \
        printf("ok   %s: %u\n", (desc), _got); \
    } \
} while (0)

/* --- Stub core: records the call sequence --- */
static char log[64];
static int log_n;
static int serialize_fail;
static int unserialize_fail;

static void logc(char c) {
    if (log_n < (int)sizeof(log) - 1)
        log[log_n++] = c;
    log[log_n] = '\0';
}

static void stub_run(void *ctx) {
    (void)ctx;
    logc('R'); /* one core run */
}

static bool stub_serialize(void *ctx, uint8_t *buf) {
    (void)ctx;
    logc('S'); /* save into a ring slot */
    if (serialize_fail)
        return false;
    memset(buf, 0xA5, 64);
    return true;
}

static bool stub_unserialize(void *ctx, const uint8_t *buf) {
    (void)ctx;
    logc('L'); /* restore from a ring slot */
    if (unserialize_fail)
        return false;
    (void)buf;
    return true;
}

static void stub_suppress(void *ctx, bool suppress) {
    (void)ctx;
    logc(suppress ? '(' : ')'); /* audio suppress on / off */
}

static runahead_ops_t ops = {
    stub_run, stub_serialize, stub_unserialize, stub_suppress, NULL,
};

static void reset_log(void) {
    log_n = 0;
    log[0] = '\0';
    serialize_fail = 0;
    unserialize_fail = 0;
}

int main(void)
{
    runahead_t ra;

    /* --- Off: exactly one plain run, nothing else --- */
    runahead_init(&ra, 64, 0);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("off: one run", log_n, 1);
    CHECK("off: run is plain", log[0] == 'R', 1);
    runahead_shutdown(&ra);

    /* --- Frames=1: bootstrap then steady state --- */
    runahead_init(&ra, 64, 1);
    CHECK("frames=1: one slot allocated", ra.slots[0] != NULL, 1);
    CHECK("frames=1: second slot stays NULL", ra.slots[1] == NULL, 1);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=1 bootstrap: run then save", strcmp(log, "RS") == 0, 1);
    CHECK("frames=1 bootstrap: slot primed", ra.primed[0], 1);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=1 steady: restore, run, save", strcmp(log, "LRS") == 0, 1);
    CHECK("frames=1 steady: no audio suppress", strchr(log, '(') == NULL, 1);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=1 steady again: restore, run, save", strcmp(log, "LRS") == 0, 1);
    runahead_shutdown(&ra);
    CHECK("shutdown: frames cleared", ra.frames, 0);
    CHECK("shutdown: slot freed", ra.slots[0] == NULL, 1);

    /* --- Frames=2: two bootstraps, then alternating slots --- */
    runahead_init(&ra, 64, 2);
    CHECK("frames=2: two slots allocated", ra.slots[0] != NULL && ra.slots[1] != NULL, 1);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=2 bootstrap slot 0: run then save", strcmp(log, "RS") == 0, 1);
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=2 bootstrap slot 1: run then save", strcmp(log, "RS") == 0, 1);
    CHECK("frames=2: both slots primed", ra.primed[0] && ra.primed[1], 1);
    reset_log();
    runahead_step(&ra, &ops);
    /* Restore slot 0, suppress audio, one re-sim run, unsuppress,
       the displayed run, save into slot 0. */
    CHECK("frames=2 steady slot 0: full sequence", strcmp(log, "L(R)RS") == 0, 1);
    CHECK("frames=2 steady slot 0: suppress opened", strchr(log, '(') != NULL, 1);
    CHECK("frames=2 steady slot 0: suppress closed", strchr(log, ')') != NULL, 1);
    /* The displayed run is the last R, after the suppress close. */
    {
        char *close = strchr(log, ')');
        char *last_run = strrchr(log, 'R');
        CHECK("frames=2 steady: displayed run after unsuppress", close != NULL && last_run != NULL && last_run > close, 1);
    }
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("frames=2 steady slot 1: full sequence", strcmp(log, "L(R)RS") == 0, 1);
    runahead_shutdown(&ra);

    /* --- Restore failure: plain run, then re-prime --- */
    runahead_init(&ra, 64, 1);
    runahead_step(&ra, &ops); /* prime */
    reset_log();
    unserialize_fail = 1; /* set AFTER reset_log: it clears the flags */
    runahead_step(&ra, &ops);
    CHECK("restore fail: plain run then save", strcmp(log, "LRS") == 0, 1);
    CHECK("restore fail: slot re-primed", ra.primed[0], 1);
    unserialize_fail = 0;
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("restore fail: next step is steady again", strcmp(log, "LRS") == 0, 1);
    runahead_shutdown(&ra);

    /* --- Save failure: slot unprimed, next step re-primes --- */
    runahead_init(&ra, 64, 1);
    reset_log();
    serialize_fail = 1; /* set AFTER reset_log: it clears the flags */
    runahead_step(&ra, &ops);
    CHECK("save fail bootstrap: run then failed save", strcmp(log, "RS") == 0, 1);
    CHECK("save fail: slot stays unprimed", ra.primed[0], 0);
    serialize_fail = 0;
    reset_log();
    runahead_step(&ra, &ops);
    CHECK("save fail: next step re-primes", strcmp(log, "RS") == 0, 1);
    CHECK("save fail: slot primed again", ra.primed[0], 1);
    runahead_shutdown(&ra);

    /* --- Clamping --- */
    runahead_init(&ra, 64, 5);
    CHECK("frames clamped to 2", ra.frames, 2);
    runahead_shutdown(&ra);
    runahead_init(&ra, 64, -1);
    CHECK("negative frames clamped to 0", ra.frames, 0);
    runahead_shutdown(&ra);

    /* --- set_frames reconfigures and clears the ring --- */
    runahead_init(&ra, 64, 1);
    runahead_step(&ra, &ops); /* prime slot 0 */
    CHECK("reconfigure: slot primed before", ra.primed[0], 1);
    runahead_set_frames(&ra, 64, 2);
    CHECK("reconfigure: new frames", ra.frames, 2);
    CHECK("reconfigure: priming cleared", ra.primed[0] == 0 && ra.primed[1] == 0, 1);
    CHECK("reconfigure: counter reset", ra.frame_counter, 0);
    runahead_shutdown(&ra);

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
