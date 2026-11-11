package app.howlingdog

/**
 * Phase 9, item 9 — Universe BIOS mid-game switching.
 *
 * Core check (verified in the core source, no core change needed):
 * the UniBIOS in-game menu is implemented by the UniBIOS ROM
 * itself — 68k code reading the input ports — not by the core.
 * The core passes every button the menu combo needs:
 *  - P1CNT (0x300000): Neo A/B/C/D via geo_input_poll_js
 *    (libretro/libretro.c) and its bindmap_js.
 *  - STATUS_B (0x380000): P1 Start and P1 Select via
 *    geo_input_poll_stat_b — Select is read whenever the system
 *    type is not MVS, so under SYSTEM_UNI it IS read (the MVS-only
 *    skip is exactly the case that does not apply).
 *  - The bridge answers RETRO_DEVICE_ID_JOYPAD_MASK with the full
 *    mask (bridge/jni/bridge.cpp), so all the combo's buttons
 *    arrive together, and it passes the "uni" / "cdz_unibios"
 *    system types straight through nativeSetOption to the core's
 *    check_variables(), which matches them exactly
 *    (SYSTEM_UNI -> uni-bios_4_0.rom from neogeo.zip,
 *    SYSTEM_CDU -> uni-bioscd33.rom from neocdz.zip).
 * The documented combo (UniBIOS official instructions, via the
 * ConsoleMods Wiki) is START+SELECT mid-game (START+COIN and
 * START+A+B+C open the same menu). The in-game menu is where the
 * region and Arcade/Home-console mode are switched mid-game.
 *
 * Two frontend gaps blocked it, and this object is the pure half
 * of the fix: the app had no Universe BIOS boot mode (a game could
 * never boot under UniBIOS), and the gamepad START+SELECT combo is
 * swallowed by the frontend's own pause hotkey (MainActivity
 * onKeyDown pauses the moment the second of Start/Select goes
 * down). So the pause menu offers a button that resumes the game
 * and injects START+SELECT into the input mask for one hold
 * window instead — the same combo, delivered by the app, which
 * works for touch and gamepad alike and never fights the pause
 * hotkey. The UniBIOS opens its menu within a frame or two of
 * seeing the combo; the hold is far longer than that, and the
 * release timer guarantees the combo can never stick.
 *
 * Pure Kotlin, no Android dependencies, unit-tested on the JVM
 * (UniBiosMenuTest).
 */
object UniBiosMenu {
    /** How long the injected combo is held: ~30 frames at 60 fps. */
    const val HOLD_MS = 500L

    /** The UniBIOS in-game menu combo: START+SELECT. */
    fun comboMask(): Int = InputMask.build(start = true, coin = true)

    /** True when [mode] boots a game under the Universe BIOS. */
    fun isUniBiosMode(mode: String): Boolean =
        mode == "uni" || mode == "cdz_unibios"

    /**
     * True while an injection whose hold ends at [untilMs] is
     * still held at [nowMs] (both on the uptimeMillis clock).
     * A zero [untilMs] means no injection is armed.
     */
    fun holdActive(untilMs: Long, nowMs: Long): Boolean =
        untilMs > 0L && nowMs < untilMs
}
