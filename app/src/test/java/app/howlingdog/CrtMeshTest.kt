package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM unit tests for the CRT mesh (CrtMesh): the
 * letterbox, the flat mesh, the curve pull-in, the corner
 * rounding, and the UV orientation (top-down Bitmap, no V
 * flip — get it backwards and the picture shows upside-down).
 * No Android framework calls.
 */
class CrtMeshTest {

    private fun vertex(mesh: CrtMesh.Mesh, ix: Int, iy: Int): FloatArray {
        val n = CrtMesh.GRID
        val base = (iy * (n + 1) + ix) * 4
        return floatArrayOf(
            mesh.verts[base], mesh.verts[base + 1],
            mesh.verts[base + 2], mesh.verts[base + 3],
        )
    }

    @Test
    fun letterboxPreservesAspectAndCentres() {
        val rect = CrtMesh.letterboxRect(304, 224, 800, 600)
        // Scale is min(800/304, 600/224) = 800/304: the picture
        // fills the width and centres vertically.
        assertEquals(800f, rect.width, 0.01f)
        assertEquals(800f * 224f / 304f, rect.height, 0.01f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals((600f - rect.height) / 2f, rect.top, 0.01f)
        assertEquals(304f / 224f, rect.width / rect.height, 0.001f)
    }

    @Test
    fun letterboxRejectsNonPositiveSizes() {
        val rect = CrtMesh.letterboxRect(0, 224, 800, 600)
        assertEquals(0f, rect.width, 0f)
        assertEquals(0f, rect.height, 0f)
    }

    @Test
    fun flatMeshHasTheRightCountsAndCorners() {
        val rect = CrtMesh.letterboxRect(304, 224, 800, 600)
        val mesh = CrtMesh.build(rect, 0f, 0f, 800, 600)
        val n = CrtMesh.GRID
        assertEquals((n + 1) * (n + 1), mesh.vertexCount)
        assertEquals(n * n * 6, mesh.indices.size)
        // Top-left vertex: NDC x = left edge, y = top edge, uv (0, 0).
        // The picture fills the width but not the height, so the
        // top edge is below NDC +1 by the letterbox margin.
        val tl = vertex(mesh, 0, 0)
        assertEquals(-1f, tl[0], 0.001f)
        assertEquals(1f - 2f * rect.top / 600f, tl[1], 0.001f)
        assertEquals(0f, tl[2], 0.001f)
        assertEquals(0f, tl[3], 0.001f)
        // Bottom-right vertex: NDC (+1, -top margin), uv (1, 1).
        val br = vertex(mesh, n, n)
        assertEquals(1f, br[0], 0.001f)
        assertEquals(-(1f - 2f * rect.top / 600f), br[1], 0.001f)
        assertEquals(1f, br[2], 0.001f)
        assertEquals(1f, br[3], 0.001f)
        // Centre vertex: the quad centre, uv (0.5, 0.5).
        val c = vertex(mesh, n / 2, n / 2)
        assertEquals(0f, c[0], 0.001f)
        assertEquals(0f, c[1], 0.02f)
        assertEquals(0.5f, c[2], 0.001f)
        assertEquals(0.5f, c[3], 0.001f)
        // Every index is a valid vertex.
        for (idx in mesh.indices) {
            assertTrue(idx >= 0 && idx < mesh.vertexCount)
        }
    }

    @Test
    fun curvedMeshPullsCornersInAndKeepsCentre() {
        val rect = CrtMesh.letterboxRect(304, 224, 800, 600)
        val flat = CrtMesh.build(rect, 0f, 0f, 800, 600)
        val curved = CrtMesh.build(rect, 0.006f, 0f, 800, 600)
        val n = CrtMesh.GRID
        val flatTl = vertex(flat, 0, 0)
        val curvedTl = vertex(curved, 0, 0)
        // The corner is pulled toward the centre on both axes.
        assertTrue("curved corner x must pull in", kotlin.math.abs(curvedTl[0]) < kotlin.math.abs(flatTl[0]))
        assertTrue("curved corner y must pull in", kotlin.math.abs(curvedTl[1]) < kotlin.math.abs(flatTl[1]))
        // The centre does not move (nx = ny = 0 there).
        val flatC = vertex(flat, n / 2, n / 2)
        val curvedC = vertex(curved, n / 2, n / 2)
        assertEquals(flatC[0], curvedC[0], 0.001f)
        assertEquals(flatC[1], curvedC[1], 0.001f)
        // UVs are unaffected by the curve.
        assertEquals(flatTl[2], curvedTl[2], 0.001f)
        assertEquals(flatTl[3], curvedTl[3], 0.001f)
    }

    @Test
    fun roundedMeshClampsCornersToTheRadius() {
        val rect = CrtMesh.letterboxRect(304, 224, 800, 600)
        val roundK = 0.20f
        val mesh = CrtMesh.build(rect, 0f, roundK, 800, 600)
        val n = CrtMesh.GRID
        val rad = roundK * 0.25f * minOf(rect.width, rect.height)
        // The top-left corner vertex, back in pixel space, must
        // sit within the corner radius of the corner centre.
        val tl = vertex(mesh, 0, 0)
        val px = (tl[0] + 1f) * 0.5f * 800f
        val py = (1f - tl[1]) * 0.5f * 600f
        val ccx = rect.left + rad
        val ccy = rect.top + rad
        val d = sqrt((px - ccx) * (px - ccx) + (py - ccy) * (py - ccy))
        assertTrue("corner vertex must be within the radius (d=$d, rad=$rad)", d <= rad + 0.5f)
        // The centre is untouched by rounding.
        val c = vertex(mesh, n / 2, n / 2)
        assertEquals(0f, c[0], 0.001f)
    }

    @Test
    fun flatMeshWithRoundingOffLeavesCornersSquare() {
        val rect = CrtMesh.letterboxRect(304, 224, 800, 600)
        val mesh = CrtMesh.build(rect, 0f, 0f, 800, 600)
        val tl = vertex(mesh, 0, 0)
        // No rounding: the corner vertex is exactly the quad corner.
        assertEquals(rect.left / 800f * 2f - 1f, tl[0], 0.001f)
    }
}
