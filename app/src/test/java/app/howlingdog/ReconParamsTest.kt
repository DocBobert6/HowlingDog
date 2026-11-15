package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the reconstruction's per-system
 * parameters (v2.1.0): the starting values and the per-mode
 * lookup. The four systems start identical (same 304x224
 * frame, same 4096-colour palette, same sprite system).
 */
class ReconParamsTest {

    @Test
    fun mvsStartsAtTheDocumentedValues() {
        val p = ReconParams.forMode("mvs")
        assertEquals(0.10f, p.ditherTol, 0.0001f)
        assertEquals(0.05f, p.edgeLo, 0.0001f)
        assertEquals(0.7f, p.ditherGuardHi, 0.0001f)
        assertEquals(0.05f, p.textAlign, 0.0001f)
        assertFalse("the stroke test is off for Neo Geo", p.textStroke)
        assertTrue("the 12-bit snap is on", p.snap12)
    }

    @Test
    fun allFourSystemsStartIdentical() {
        val mvs = ReconParams.forMode("mvs")
        assertEquals(mvs, ReconParams.forMode("aes"))
        assertEquals(mvs, ReconParams.forMode("cd"))
        assertEquals(mvs, ReconParams.forMode("cdz"))
    }

    @Test
    fun unknownModeFallsBackToTheMvsSet() {
        assertEquals(ReconParams.forMode("mvs"), ReconParams.forMode("uni"))
        assertEquals(ReconParams.forMode("mvs"), ReconParams.forMode(""))
    }
}
