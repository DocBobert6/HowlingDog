/*
Copyright (c) 2022-2026 Rupert Carmichael
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/*
 * Event-batched 68k execution (Phase 6): how long the next
 * m68k_execute() slice may run.
 *
 * geo_exec() used to call geo_m68k_run(1) once per instruction — a
 * 1-cycle budget to Musashi, which executes at least one whole
 * instruction and returns the cycles actually used, so the loop paid
 * a full m68k_execute() entry (interrupt check, trap setup) per
 * instruction. The batched loop instead runs the 68k in slices up
 * to the next event, so the per-call overhead is paid once per
 * slice instead of once per instruction.
 *
 * A slice must end where the rest of the system needs to observe
 * the 68k: Musashi only takes an interrupt on the next
 * m68k_execute() entry (per-instruction interrupt checks were
 * removed upstream), so an interrupt asserted mid-slice would not
 * be taken until the slice ends. The slice therefore ends at the
 * earliest of:
 *   - the next LSPC event point (scanline events and VBLANK),
 *   - the IRQ2 timer counter reaching 0,
 *   - the end of the frame.
 * The per-slice bookkeeping in geo_exec() (watchdog, RTC, IRQ2,
 * LSPC, CD tick, Z80 catch-up) is unchanged and runs once per
 * slice, exactly as it ran once per instruction — only the
 * granularity changes, never the order or the totals.
 *
 * Everything here is pure (no core globals), so the host test
 * tests/geo_slice_test.c exercises the exact functions geo_exec()
 * calls. The constants below DUPLICATE values that live elsewhere
 * and must be kept in sync with them:
 *   - the LSPC event points MUST match geo_lspc_run() in
 *     src/geo_lspc.c (29 / 573 / 712). Upstream calls 573 an
 *     arbitrary placement and 712 an educated guess — preserve
 *     them exactly, do not "fix" them.
 *   - GEO_MCYC_PER_FRAME / GEO_DIV_M68K MUST match MCYC_PER_FRAME
 *     / DIV_M68K in src/geo.c.
 */

#ifndef GEO_SLICE_H
#define GEO_SLICE_H

#include <stdint.h>

/* LSPC event points within a scanline, in M68K cycles. Must match
   geo_lspc_run() (src/geo_lspc.c). */
#define GEO_LSPC_EV_VBL 29
#define GEO_LSPC_EV_SCANLINE 573
#define GEO_LSPC_EV_LINEINC 712
#define GEO_LSPC_CYC_PER_LINE 768

/* Master cycles per frame (264 lines * 1536) and the M68K clock
   divider. Must match MCYC_PER_FRAME / DIV_M68K in src/geo.c. */
#define GEO_MCYC_PER_FRAME 405504
#define GEO_DIV_M68K 2

/* A slice length that means "no bound from this source". */
#define GEO_SLICE_UNBOUNDED 0x7fffffffu

/*
 * LSPC cycles from the current line position until the next LSPC
 * event fires. The count is inclusive: geo_lspc_run() fires an
 * event at X only when the run interval [start, end) contains X,
 * so the slice must run one cycle PAST the event point (start <= X
 * < end). Returning 1 when cyc is exactly on an event point means
 * the event fires on the very next run, as it did when the loop
 * ran one instruction at a time.
 *
 * cyc is the LSPC's position within the current scanline
 * (0 .. GEO_LSPC_CYC_PER_LINE - 1). The next event after the
 * line-increment point is the VBLANK point of the following line.
 */
static inline unsigned geo_lspc_cycles_to_event(unsigned cyc)
{
    unsigned x;
    if (cyc <= GEO_LSPC_EV_VBL)
        x = GEO_LSPC_EV_VBL;
    else if (cyc <= GEO_LSPC_EV_SCANLINE)
        x = GEO_LSPC_EV_SCANLINE;
    else if (cyc <= GEO_LSPC_EV_LINEINC)
        x = GEO_LSPC_EV_LINEINC;
    else
        x = GEO_LSPC_CYC_PER_LINE + GEO_LSPC_EV_VBL;
    return x - cyc + 1;
}

/*
 * 68k cycles for the next batch slice: the smallest of the cycles
 * to the next LSPC event, the cycles to the IRQ2 timer counter
 * reaching 0, and the cycles left in the frame. Always >= 1.
 *
 * lspc_cyc     LSPC position within the scanline (geo_lspc_cyc()).
 * irq2_counter IRQ2 timer counter, in pixel clocks (0 = the timer
 *              is not running, so it bounds nothing). One pixel
 *              clock is 2 M68K cycles at the base rate; the
 *              overclock shift slows the counter relative to the
 *              68k, so the bound grows by (1 << oc) as well.
 * mcycs        master cycles elapsed in this frame (< frame size).
 * oc           the 68k overclock shift (0 = base rate, 1 = 2x).
 */
static inline unsigned geo_exec_slice_len(unsigned lspc_cyc,
                                          uint32_t irq2_counter,
                                          unsigned mcycs,
                                          unsigned oc)
{
    /* LSPC: the 68k must run (cycles << oc) for the LSPC to
       advance `cycles`, because geo_lspc_run() is fed
       (icycs >> oc). */
    unsigned slice_lspc = geo_lspc_cycles_to_event(lspc_cyc) << oc;

    /* IRQ2: the counter decrements by about one pixel clock per
       2 M68K cycles (plus a fragment carry of at most one), so the
       counter reaches 0 after roughly counter * 2 * (1 << oc)
       68k cycles. Ending the slice there means the timer interrupt
       is asserted right after the slice and taken on the next
       m68k_execute() entry, as before. A counter of 0 means the
       timer is off (it underflows without firing), so no bound. */
    unsigned slice_irq2 = GEO_SLICE_UNBOUNDED;
    if (irq2_counter != 0 &&
        irq2_counter <= (GEO_SLICE_UNBOUNDED >> (1 + oc)))
        slice_irq2 = irq2_counter << (1 + oc);

    /* Frame: master cycles left, converted to 68k cycles. Clamped
       to 1 so a nearly-finished frame still runs one slice. */
    unsigned remaining = GEO_MCYC_PER_FRAME - mcycs;
    unsigned slice_frame = (remaining << oc) / GEO_DIV_M68K;
    if (slice_frame < 1)
        slice_frame = 1;

    unsigned slice = slice_lspc;
    if (slice_irq2 < slice)
        slice = slice_irq2;
    if (slice_frame < slice)
        slice = slice_frame;
    if (slice < 1)
        slice = 1;
    return slice;
}

#endif /* GEO_SLICE_H */
