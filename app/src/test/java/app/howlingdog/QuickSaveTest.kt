package app.howlingdog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM unit tests for the save-state slots: three slots per
 * game, slot 1 the legacy `<mainFile>.state` file the quick path
 * uses, slots 2 and 3 `<mainFile>.state2` / `.state3`, and the
 * occupancy check the pause menu's slot picker shows.
 */
class QuickSaveTest {

    @Test
    fun stateFileIsThePauseMenuSlot() {
        // Slot 1 is "<mainFile>.state" in the saves folder — the
        // same path the pause menu's save/load used before slots
        // existed, and the path the quick save/load still uses, so
        // the two paths always see the same state on slot 1.
        val saves = File("/tmp/hd-test-saves")
        assertEquals(
            File(saves, "kof98.neo.state"),
            QuickSave.stateFile(saves, "kof98.neo"),
        )
        assertEquals(
            File(saves, "shocktro.neo.state"),
            QuickSave.stateFile(saves, "shocktro.neo"),
        )
    }

    @Test
    fun loadAvailableOnlyWhenAFileExists() {
        val dir = File("/tmp/hd-test-saves-quick")
        dir.mkdirs()
        val missing = File(dir, "nosuchgame.neo.state")
        assertFalse(QuickSave.loadAvailable(missing))
        // A directory with the slot name is not a loadable save.
        val asDir = File(dir, "adir.neo.state")
        asDir.mkdirs()
        assertFalse(QuickSave.loadAvailable(asDir))
        val present = File(dir, "kof98.neo.state")
        present.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(QuickSave.loadAvailable(present))
        present.delete()
        assertFalse(QuickSave.loadAvailable(present))
        asDir.delete()
        dir.delete()
    }

    @Test
    fun slotFilesFollowTheScheme() {
        val saves = File("/tmp/hd-test-saves-slots")
        assertEquals(File(saves, "kof98.neo.state"), QuickSave.stateFile(saves, "kof98.neo", 1))
        assertEquals(File(saves, "kof98.neo.state2"), QuickSave.stateFile(saves, "kof98.neo", 2))
        assertEquals(File(saves, "kof98.neo.state3"), QuickSave.stateFile(saves, "kof98.neo", 3))
    }

    @Test
    fun slotOneIsTheLegacyFile() {
        // The default slot is slot 1, and slot 1's file is exactly
        // the legacy one-slot file — a save made before slots
        // existed is slot 1's save, untouched.
        val saves = File("/tmp/hd-test-saves-slots")
        assertEquals(
            QuickSave.stateFile(saves, "kof98.neo"),
            QuickSave.stateFile(saves, "kof98.neo", QuickSave.QUICK_SLOT),
        )
        assertEquals(1, QuickSave.QUICK_SLOT)
        assertEquals(3, QuickSave.SLOT_COUNT)
    }

    @Test
    fun slotsOutsideOneToThreeAreRejected() {
        val saves = File("/tmp/hd-test-saves-slots")
        for (bad in listOf(0, -1, 4, 99)) {
            try {
                QuickSave.stateFile(saves, "kof98.neo", bad)
                fail("slot $bad should be rejected")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun occupiedSlotsListsOnlySlotsWithFiles() {
        val dir = File("/tmp/hd-test-saves-occupied")
        dir.mkdirs()
        assertEquals(emptyList<Int>(), QuickSave.occupiedSlots(dir, "kof98.neo"))
        assertFalse(QuickSave.slotOccupied(dir, "kof98.neo", 1))
        File(dir, "kof98.neo.state").writeBytes(byteArrayOf(1))
        assertEquals(listOf(1), QuickSave.occupiedSlots(dir, "kof98.neo"))
        assertTrue(QuickSave.slotOccupied(dir, "kof98.neo", 1))
        assertFalse(QuickSave.slotOccupied(dir, "kof98.neo", 2))
        File(dir, "kof98.neo.state3").writeBytes(byteArrayOf(2))
        assertEquals(listOf(1, 3), QuickSave.occupiedSlots(dir, "kof98.neo"))
        assertTrue(QuickSave.slotOccupied(dir, "kof98.neo", 3))
        // A directory with a slot name is not an occupied slot.
        File(dir, "kof98.neo.state2").mkdirs()
        assertEquals(listOf(1, 3), QuickSave.occupiedSlots(dir, "kof98.neo"))
        assertFalse(QuickSave.slotOccupied(dir, "kof98.neo", 2))
        File(dir, "kof98.neo.state").delete()
        File(dir, "kof98.neo.state3").delete()
        File(dir, "kof98.neo.state2").delete()
        assertEquals(emptyList<Int>(), QuickSave.occupiedSlots(dir, "kof98.neo"))
        dir.delete()
    }

    @Test
    fun raStateFileIsTheStateFilePlusRa() {
        // Phase 10 — RetroAchievements: the sidecar is the state
        // file's path with ".ra" appended, so each slot's RA
        // progress travels with that slot's state.
        val saves = File("/tmp/hd-test-saves")
        assertEquals(
            File(saves, "kof98.neo.state.ra"),
            QuickSave.raStateFile(saves, "kof98.neo"),
        )
        assertEquals(
            File(saves, "kof98.neo.state2.ra"),
            QuickSave.raStateFile(saves, "kof98.neo", 2),
        )
        assertEquals(
            File(saves, "kof98.neo.state3.ra"),
            QuickSave.raStateFile(saves, "kof98.neo", 3),
        )
    }
}
