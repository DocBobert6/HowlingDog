package app.howlingdog

/**
 * Pure logic for the first-run SYSTEM SETUP screen — the onboarding
 * in the Neo Geo debug-menu style (audit 2, §2.3). No Compose
 * dependency, so it is unit-testable on the plain JVM.
 *
 * The screen is a checklist of four rows, each with a status on the
 * right, derived from the real state (the BIOS status, the library
 * count, the picked game) so it never lies. Row 4 (PLAY) is only
 * ready when rows 1-3 are done.
 */
data class SetupState(
    /** How many of the four system files are present. */
    val biosReady: Int,
    /** The four system files (always 4). */
    val biosTotal: Int = 4,
    /** How many games are in the library. */
    val gameCount: Int,
    /** The picked game's display name, or null when none is picked. */
    val pickedName: String?,
) {
    /** Row 1 is done when every system file is present. */
    val systemFilesDone: Boolean get() = biosTotal > 0 && biosReady >= biosTotal

    /** Row 2 is done when at least one game is in the library. */
    val addGamesDone: Boolean get() = gameCount > 0

    /** Row 3 is done when a game is picked (the library always picks the first game). */
    val pickGameDone: Boolean get() = pickedName != null

    /** Row 4 is ready only when rows 1-3 are all done. */
    val playReady: Boolean get() = systemFilesDone && addGamesDone && pickGameDone

    /** Row 1 status: "NOT SET" until a file is present, then "N/4 READY". */
    fun systemFilesStatus(): String =
        if (biosReady <= 0) "NOT SET" else "$biosReady/$biosTotal READY"

    /** Row 2 status: "0 GAMES" until a game is added, then "N GAMES". */
    fun addGamesStatus(): String =
        if (gameCount <= 0) "0 GAMES" else "$gameCount GAMES"

    /** Row 3 status: the picked game's name, or "NOT PICKED". */
    fun pickGameStatus(): String = pickedName ?: "NOT PICKED"

    /** Row 4 status: "WAITING" until rows 1-3 are done, then "READY". */
    fun playStatus(): String = if (playReady) "READY" else "WAITING"
}

/**
 * Whether the setup screen should be offered on this launch: a
 * genuinely fresh install (no system files set AND no games in the
 * library) that has never seen the screen before. A user who already
 * has games never sees it; once dismissed it never reappears on its
 * own (it stays reachable from Settings as the Setup guide).
 */
fun shouldShowSetup(biosReady: Int, gameCount: Int, setupSeen: Boolean): Boolean =
    !setupSeen && biosReady <= 0 && gameCount <= 0
