package app.howlingdog

import kotlin.math.min
import kotlin.math.sqrt

/**
 * The CRT mesh — Mariner's curve/round mesh, in pure Kotlin
 * (no Android, no GL), so the geometry is unit-testable on the
 * plain JVM. The renderer (CrtRenderer) uploads the mesh to a
 * VBO and redraws it; only a layout, curve, round or window-size
 * change rebuilds it.
 *
 * A grid of (GRID+1)x(GRID+1) vertices over the letterboxed
 * picture. Each vertex is pulled toward the picture centre by
 * f = 1 - curveK * (nx^2 + ny^2) — the CRT tube bulge, so the
 * corners pull in and the centre stays — then, when rounding is
 * on, clamped to the corner radius. nx runs -1 (left) to +1
 * (right); ny runs -1 (top) to +1 (bottom), matching the
 * screen's top-down pixel coordinates.
 *
 * UVs are NOT flipped: the frame arrives as a top-down Bitmap
 * (row 0 = the picture's top) uploaded with texImage2D, so
 * texture v=0 is the picture's top row and the quad's top edge
 * (ny=-1) takes v=0. Mariner flips V because its core texture
 * is bottom-up; HowlingDog's Bitmap is top-down, so no flip —
 * get this backwards and the picture shows upside-down.
 */
object CrtMesh {

    /** Vertices per side of the grid (Mariner uses 48). */
    const val GRID = 48

    /** A picture rect in pixels, y down: left, top, width, height. */
    data class RectF(val left: Float, val top: Float, val width: Float, val height: Float)

    /** A built mesh: interleaved x, y, u, v per vertex (x, y in NDC), plus triangle indices. */
    data class Mesh(val verts: FloatArray, val indices: ShortArray) {
        val vertexCount: Int get() = verts.size / 4
    }

    /**
     * The letterboxed picture rect (pixels, y down) for a frame
     * of [frameW]x[frameH] in a view of [viewW]x[viewH] — the
     * same letterbox the old Canvas GameView used (scale to fit,
     * centred, aspect preserved). An empty rect when any size
     * is not positive.
     */
    fun letterboxRect(frameW: Int, frameH: Int, viewW: Int, viewH: Int): RectF {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) {
            return RectF(0f, 0f, 0f, 0f)
        }
        val scale = minOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val dw = frameW * scale
        val dh = frameH * scale
        return RectF((viewW - dw) / 2f, (viewH - dh) / 2f, dw, dh)
    }

    /**
     * Build the mesh for a picture rect.
     * [curveK] is the mesh pull-in (0 = flat); [roundK] is the
     * corner-round strength 0..1 (radius = roundK * 0.25 * the
     * picture's smaller side); [winW]/[winH] are the view size
     * in pixels.
     */
    fun build(rect: RectF, curveK: Float, roundK: Float, winW: Int, winH: Int): Mesh {
        val n = GRID
        // NDC centre and half-extents of the picture (y up in NDC).
        val cx = (rect.left + rect.width / 2f) / winW * 2f - 1f
        val cy = 1f - (rect.top + rect.height / 2f) / winH * 2f
        val hx = rect.width / winW
        val hy = rect.height / winH
        val pxL = rect.left
        val pxR = rect.left + rect.width
        val pyT = rect.top
        val pyB = rect.top + rect.height
        val rad = roundK * 0.25f * min(rect.width, rect.height)
        val verts = FloatArray((n + 1) * (n + 1) * 4)
        var vi = 0
        for (iy in 0..n) {
            val ny = iy.toFloat() / n * 2f - 1f // -1 top, +1 bottom
            for (ix in 0..n) {
                val nx = ix.toFloat() / n * 2f - 1f // -1 left, +1 right
                val f = 1f - curveK * (nx * nx + ny * ny)
                var vx = cx + nx * hx * f
                var vy = cy - ny * hy * f
                if (roundK > 0f) {
                    // Corner rounding, in pixel space (Mariner's
                    // clamp): a vertex inside a corner zone is
                    // pulled back onto the corner circle.
                    var px = (vx + 1f) * 0.5f * winW
                    var py = (1f - vy) * 0.5f * winH
                    val corners = arrayOf(
                        floatArrayOf(pxL + rad, pyT + rad),
                        floatArrayOf(pxR - rad, pyT + rad),
                        floatArrayOf(pxL + rad, pyB - rad),
                        floatArrayOf(pxR - rad, pyB - rad),
                    )
                    for (ci in 0..3) {
                        val ccx = corners[ci][0]
                        val ccy = corners[ci][1]
                        val inZone =
                            (if (ci % 2 == 0) px < ccx else px > ccx) &&
                                (if (ci < 2) py < ccy else py > ccy)
                        if (!inZone) continue
                        val dx = px - ccx
                        val dy = py - ccy
                        val d = sqrt(dx * dx + dy * dy)
                        if (d > rad && d > 0f) {
                            px = ccx + rad * dx / d
                            py = ccy + rad * dy / d
                        }
                    }
                    vx = px * 2f / winW - 1f
                    vy = 1f - py * 2f / winH
                }
                verts[vi++] = vx
                verts[vi++] = vy
                verts[vi++] = (nx + 1f) * 0.5f // u: 0 left, 1 right
                verts[vi++] = (ny + 1f) * 0.5f // v: 0 top, 1 bottom (top-down Bitmap)
            }
        }
        val indices = ShortArray(n * n * 6)
        var ii = 0
        for (iy in 0 until n) {
            for (ix in 0 until n) {
                val a = (iy * (n + 1) + ix).toShort()
                val b = (a + 1).toShort()
                val c = (a + n + 1).toShort()
                val d = (c + 1).toShort()
                indices[ii++] = a
                indices[ii++] = b
                indices[ii++] = c
                indices[ii++] = b
                indices[ii++] = d
                indices[ii++] = c
            }
        }
        return Mesh(verts, indices)
    }
}
