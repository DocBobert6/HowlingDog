package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** v1.5, feature 5 — two-player multiplayer: the gamepad-to-player assignment. */
@RunWith(JUnit4::class)
class PlayerAssignmentTest {

    @Test
    fun firstDeviceIsPlayer1() {
        val assignment = PlayerAssignment()
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(7))
        assertEquals(7, assignment.deviceP1)
        assertEquals(PlayerAssignment.NONE, assignment.deviceP2)
    }

    @Test
    fun secondDeviceIsPlayer2() {
        val assignment = PlayerAssignment()
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(7))
        assertEquals(PlayerAssignment.PLAYER_2, assignment.playerFor(9))
        assertEquals(7, assignment.deviceP1)
        assertEquals(9, assignment.deviceP2)
    }

    @Test
    fun assignedDeviceKeepsItsPlayer() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.playerFor(9)
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(7))
        assertEquals(PlayerAssignment.PLAYER_2, assignment.playerFor(9))
        assertEquals(7, assignment.deviceP1)
        assertEquals(9, assignment.deviceP2)
    }

    @Test
    fun thirdDeviceIsIgnored() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.playerFor(9)
        assertEquals(PlayerAssignment.IGNORED, assignment.playerFor(11))
        assertEquals(7, assignment.deviceP1)
        assertEquals(9, assignment.deviceP2)
    }

    @Test
    fun releasedPlayer1SlotGoesToTheNextDevice() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.playerFor(9)
        assignment.release(7)
        assertEquals(PlayerAssignment.NONE, assignment.deviceP1)
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(11))
        assertEquals(11, assignment.deviceP1)
        assertEquals(9, assignment.deviceP2)
    }

    @Test
    fun releasedPlayer2SlotGoesToTheNextDevice() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.playerFor(9)
        assignment.release(9)
        assertEquals(PlayerAssignment.NONE, assignment.deviceP2)
        assertEquals(PlayerAssignment.PLAYER_2, assignment.playerFor(11))
        assertEquals(7, assignment.deviceP1)
        assertEquals(11, assignment.deviceP2)
    }

    @Test
    fun releasingAnUnknownDeviceChangesNothing() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.release(99)
        assertEquals(7, assignment.deviceP1)
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(7))
    }

    @Test
    fun clearForgetsBothPlayers() {
        val assignment = PlayerAssignment()
        assignment.playerFor(7)
        assignment.playerFor(9)
        assignment.clear()
        assertEquals(PlayerAssignment.NONE, assignment.deviceP1)
        assertEquals(PlayerAssignment.NONE, assignment.deviceP2)
        assertEquals(PlayerAssignment.PLAYER_1, assignment.playerFor(11))
    }
}
