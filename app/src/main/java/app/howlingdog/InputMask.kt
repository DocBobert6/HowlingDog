package app.howlingdog

/**
 * Player-1 joypad bitmask for the libretro core (see libretro.h:
 * RETRO_DEVICE_ID_JOYPAD_*). The core reads the mask through
 * RETRO_DEVICE_ID_JOYPAD_MASK; bit i is (1 << id).
 *
 * Neo Geo buttons map to joypad buttons as the core's input
 * descriptors define them (libretro/libretro.c):
 * Neo A -> joypad B, Neo B -> joypad A, Neo C -> joypad Y,
 * Neo D -> joypad X, Coin -> Select, Start -> Start.
 *
 * Pure Kotlin, no Android dependencies, so it is unit-tested on the
 * JVM (InputMaskTest).
 */
object InputMask {
    // libretro joypad button ids (libretro.h)
    const val JOY_B = 0
    const val JOY_Y = 1
    const val JOY_SELECT = 2
    const val JOY_START = 3
    const val JOY_UP = 4
    const val JOY_DOWN = 5
    const val JOY_LEFT = 6
    const val JOY_RIGHT = 7
    const val JOY_A = 8
    const val JOY_X = 9

    /** Neo Geo A button -> joypad B. */
    fun neoA(): Int = 1 shl JOY_B

    /** Neo Geo B button -> joypad A. */
    fun neoB(): Int = 1 shl JOY_A

    /** Neo Geo C button -> joypad Y. */
    fun neoC(): Int = 1 shl JOY_Y

    /** Neo Geo D button -> joypad X. */
    fun neoD(): Int = 1 shl JOY_X

    /** Build the player-1 mask from the currently held Neo Geo inputs. */
    fun build(
        up: Boolean = false,
        down: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        a: Boolean = false,
        b: Boolean = false,
        c: Boolean = false,
        d: Boolean = false,
        start: Boolean = false,
        coin: Boolean = false,
    ): Int {
        var mask = 0
        if (up) mask = mask or (1 shl JOY_UP)
        if (down) mask = mask or (1 shl JOY_DOWN)
        if (left) mask = mask or (1 shl JOY_LEFT)
        if (right) mask = mask or (1 shl JOY_RIGHT)
        if (a) mask = mask or neoA()
        if (b) mask = mask or neoB()
        if (c) mask = mask or neoC()
        if (d) mask = mask or neoD()
        if (start) mask = mask or (1 shl JOY_START)
        if (coin) mask = mask or (1 shl JOY_SELECT)
        return mask
    }
}
