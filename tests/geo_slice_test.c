/*
 * Host test for the Phase 6 event-batched 68k slice computation
 * (src/geo_slice.h). Compiles on the host with a plain C compiler
 * — no core sources, no Android NDK, no game files:
 *
 *   gcc -I src -o /tmp/geo_slice_test tests/geo_slice_test.c && /tmp/geo_slice_test
 *
 * It exercises the EXACT functions geo_exec() calls, so a wrong
 * event point or a wrong bound fails here instead of on device.
 * The LSPC event points (29 / 573 / 712) are pinned by the
 * boundary cases below: if geo_lspc_run() ever moves them, these
 * tests must be updated in lockstep (see geo_slice.h).
 */

#include <stdio.h>
#include <stdint.h>

#include "geo_slice.h"

static int failures = 0;

#define CHECK(desc, got, want) do { \
    unsigned _got = (got); \
    unsigned _want = (want); \
    if (_got != _want) { \
        printf("FAIL %s: got %u, want %u\n", (desc), _got, _want); \
        ++failures; \
    } else { \
        printf("ok   %s: %u\n", (desc), _got); \
    } \
} while (0)

int main(void)
{
    /* --- LSPC cycles to the next event (inclusive: the slice must
       run one cycle PAST the event point) --- */
    CHECK("event from line start (0)", geo_lspc_cycles_to_event(0), 30);
    CHECK("event on the VBL point (29)", geo_lspc_cycles_to_event(29), 1);
    CHECK("event just past VBL (30)", geo_lspc_cycles_to_event(30), 544);
    CHECK("event mid-line (300)", geo_lspc_cycles_to_event(300), 274);
    CHECK("event on the scanline point (573)", geo_lspc_cycles_to_event(573), 1);
    CHECK("event just past scanline (574)", geo_lspc_cycles_to_event(574), 139);
    CHECK("event on the line-inc point (712)", geo_lspc_cycles_to_event(712), 1);
    CHECK("event just past line-inc (713)", geo_lspc_cycles_to_event(713), 85);
    CHECK("event at end of line (767)", geo_lspc_cycles_to_event(767), 31);

    /* --- Slice length: the LSPC bound dominates --- */
    /* cyc 30 -> 544 to the scanline event; timer off; frame full. */
    CHECK("slice LSPC-bound (oc=0)",
          geo_exec_slice_len(30, 0, 0, 0), 544);
    /* cyc 0 -> 30 to the VBL point. */
    CHECK("slice LSPC-bound at line start (oc=0)",
          geo_exec_slice_len(0, 0, 0, 0), 30);
    /* Overclocked: the 68k runs 2x, so the slice doubles for the
       same LSPC advance. */
    CHECK("slice LSPC-bound (oc=1)",
          geo_exec_slice_len(30, 0, 0, 1), 1088);

    /* --- Slice length: the IRQ2 bound dominates --- */
    /* Counter 100 pixel clocks -> ~200 68k cycles at base rate,
       shorter than the 544 to the next LSPC event. */
    CHECK("slice IRQ2-bound (oc=0)",
          geo_exec_slice_len(30, 100, 0, 0), 200);
    /* Overclocked: the counter runs at half rate relative to the
       68k, so the bound doubles. */
    CHECK("slice IRQ2-bound (oc=1)",
          geo_exec_slice_len(30, 100, 0, 1), 400);
    /* Counter 1 -> the slice ends almost immediately (2 cycles),
       so the timer interrupt is taken promptly. */
    CHECK("slice IRQ2-bound, counter 1",
          geo_exec_slice_len(30, 1, 0, 0), 2);
    /* Counter 0 = timer off: no IRQ2 bound at all. */
    CHECK("slice IRQ2 off (counter 0)",
          geo_exec_slice_len(30, 0, 0, 0), 544);

    /* --- Slice length: the frame bound dominates --- */
    /* One master cycle left in the frame -> the slice is 1. */
    CHECK("slice frame-bound, 1 master cycle left",
          geo_exec_slice_len(30, 0, GEO_MCYC_PER_FRAME - 1, 0), 1);
    /* 200 master cycles left -> 100 68k cycles, shorter than the
       544 to the next LSPC event. */
    CHECK("slice frame-bound, 200 master cycles left",
          geo_exec_slice_len(30, 0, GEO_MCYC_PER_FRAME - 200, 0), 100);
    /* Overclocked frame bound: 200 master cycles left need 200
       68k cycles (the 68k covers master cycles half as fast). */
    CHECK("slice frame-bound (oc=1)",
          geo_exec_slice_len(30, 0, GEO_MCYC_PER_FRAME - 200, 1), 200);

    /* --- Slice length is never 0 --- */
    CHECK("slice never 0 (on an event point, frame ending)",
          geo_exec_slice_len(29, 0, GEO_MCYC_PER_FRAME - 1, 0), 1);

    if (failures != 0) {
        printf("%d FAILURES\n", failures);
        return 1;
    }
    printf("all slice tests pass\n");
    return 0;
}
