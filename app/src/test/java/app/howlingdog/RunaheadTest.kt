package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for run-ahead (Phase 10): the frame-count
 * parsing, the global setting (off by default), and the per-game
 * override (an override wins, an unset value falls back to the
 * global, an invalid stored value reads as unset). No Android
 * framework calls — a fake in-memory store.
 */
class RunaheadTest {

    private class MapStore : OptionStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private val game = "kof98.neo"
    private val other = "mslug3.neo"

    @Test
    fun parseKnownValues() {
        assertEquals(0, Runahead.parse("off"))
        assertEquals(1, Runahead.parse("1"))
        assertEquals(2, Runahead.parse("2"))
    }

    @Test
    fun parseUnknownIsOff() {
        assertEquals(0, Runahead.parse(null))
        assertEquals(0, Runahead.parse(""))
        assertEquals(0, Runahead.parse("banana"))
        assertEquals(0, Runahead.parse("3"))
        assertEquals(0, Runahead.parse("-1"))
    }

    @Test
    fun parseIsCaseInsensitive() {
        assertEquals(0, Runahead.parse("OFF"))
        assertEquals(0, Runahead.parse("Off"))
    }

    @Test
    fun storeRoundTrips() {
        assertEquals("off", Runahead.store(0))
        assertEquals("1", Runahead.store(1))
        assertEquals("2", Runahead.store(2))
        assertEquals("off", Runahead.store(3))
        assertEquals("off", Runahead.store(-1))
    }

    @Test
    fun globalSettingIsOffByDefault() {
        val store = MapStore()
        assertEquals(0, AppSettings(store).runaheadFrames())
    }

    @Test
    fun globalSettingRoundTrips() {
        val settings = AppSettings(MapStore())
        settings.setRunaheadFrames(1)
        assertEquals(1, settings.runaheadFrames())
        settings.setRunaheadFrames(2)
        assertEquals(2, settings.runaheadFrames())
        settings.setRunaheadFrames(0)
        assertEquals(0, settings.runaheadFrames())
    }

    @Test
    fun globalSettingIgnoresBadValues() {
        val store = MapStore()
        store.map[AppSettings.KEY_RUNAHEAD] = "banana"
        assertEquals(0, AppSettings(store).runaheadFrames())
    }

    @Test
    fun perGameOverrideUnsetFallsBackToGlobal() {
        val p = PerGameSettings(MapStore())
        assertNull(p.runaheadOverride(game))
        assertEquals(1, p.resolveRunahead(game, 1))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun perGameOverrideWinsOverGlobal() {
        val p = PerGameSettings(MapStore())
        p.setRunaheadOverride(game, 2)
        assertEquals(2, p.runaheadOverride(game))
        assertEquals(2, p.resolveRunahead(game, 0))
        assertTrue(p.hasProfile(game))
    }

    @Test
    fun perGameOverrideCanForceOff() {
        val p = PerGameSettings(MapStore())
        p.setRunaheadOverride(game, 0)
        assertEquals(0, p.runaheadOverride(game))
        assertEquals(0, p.resolveRunahead(game, 2))
        assertTrue(p.hasProfile(game))
    }

    @Test
    fun perGameOverrideInvalidValueReadsAsUnset() {
        val store = MapStore()
        store.map["pergame.$game.${PerGameSettings.KEY_RUNAHEAD}"] = "banana"
        val p = PerGameSettings(store)
        assertNull(p.runaheadOverride(game))
        assertEquals(1, p.resolveRunahead(game, 1))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun perGameOverrideIsPerGameAndClears() {
        val p = PerGameSettings(MapStore())
        p.setRunaheadOverride(game, 1)
        assertNull(p.runaheadOverride(other))
        assertEquals(0, p.resolveRunahead(other, 0))
        p.setRunaheadOverride(game, null)
        assertNull(p.runaheadOverride(game))
        assertFalse(p.hasProfile(game))
        p.setRunaheadOverride(game, 2)
        p.clear(game)
        assertNull(p.runaheadOverride(game))
        assertFalse(p.hasProfile(game))
    }
}
