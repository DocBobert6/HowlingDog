package app.howlingdog

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The CRT renderer: a GLSurfaceView.Renderer that draws the
 * latest frame Bitmap as a GL texture through the per-system
 * CRT shader chain (CrtShaders) and the curve/round mesh
 * (CrtMesh) — the Mariner way, in Kotlin/GLES20 instead of
 * Mariner's native shim. No native code, no core changes: the
 * frame still arrives as a Bitmap from PlayerSession, and this
 * renderer only presents it.
 *
 * The mesh is built once into a VBO and redrawn from it; only
 * a layout, curve, round or window-size change rebuilds it
 * (Mariner's cache). The texture is re-uploaded only when a
 * new frame arrives (texSubImage2D when the size is unchanged,
 * texImage2D when it changes). The view renders on demand
 * (RENDERMODE_WHEN_DIRTY): a new frame or a settings change
 * requests a redraw, and the last frame stays on screen while
 * paused.
 *
 * If the CRT program fails to compile, the plain fallback
 * program (a bare texture sample) is used instead, so the game
 * stays visible and the failure is only a log line.
 */
class CrtRenderer : GLSurfaceView.Renderer {

    @Volatile
    private var pendingBitmap: Bitmap? = null

    @Volatile
    private var preset: ShaderPreset = ShaderPresets.forMode("aes")

    @Volatile
    private var shaderOn: Boolean = true

    @Volatile
    private var curveOn: Boolean = true

    /**
     * 2.0, feature 7 — the edge-smoothing mode. When it is on,
     * the edge program draws the frame and the CRT program is
     * not used (they fight visually); when it is off, the CRT
     * program draws when shaderOn, and the plain program when
     * the CRT is off too.
     *
     * The edge shader needs hard texels: it detects edges and
     * dither from the texel grid, so while it is on the texture
     * magnification filter is GL_NEAREST (the shader does all
     * the smoothing). With GL_LINEAR the dither would already be
     * blurred into mud before the shader ever saw it. The filter
     * is switched back to GL_LINEAR when the edge mode turns
     * off. [lastEdgeMagNearest] tracks the filter in effect so
     * the parameter is only set when the mode changes.
     */
    @Volatile
    private var edgeMode: EdgeMode = EdgeMode.OFF
    private var lastEdgeMagNearest: Boolean? = null

    /**
     * 2.1.0 — the reconstruction mode (which system's
     * reconstruction is on) and the grid-on-reconstruction
     * toggle. When a reconstruction is on, drawRecon() draws
     * the frame (Pass A1 -> A2 -> B) and the CRT / edge / plain
     * programs are not used. Like the edge shader, the
     * reconstruction needs hard texels, so the magnification
     * filter is GL_NEAREST while it is on.
     *
     * [lastDrawPath] names the draw path that actually drew
     * (the dev-stats diagnostic's ground truth): "recon-mvs",
     * "recon-aes", "recon-cd", "recon-cdz", "edge",
     * "sharp-crt", "crt", "plain". It is set in onDrawFrame
     * (on the GL thread) from the program that drew, so a
     * failed recon program falling back to the CRT / plain
     * program reads as the fallback, not the reconstruction.
     */
    @Volatile
    private var reconMode: ReconMode = ReconMode.OFF

    @Volatile
    private var reconGrid: Boolean = false

    @Volatile
    private var lastDrawPath: String = "plain"

    private var reconA1Program = 0
    private var reconA2Program = 0
    private var reconBProgram = 0
    private var reconFboId = 0
    private var reconTexAId = 0
    private var reconTexBId = 0
    private var reconTexW = 0
    private var reconTexH = 0
    private var reconFlatVbo = 0

    private var program = 0
    private var edgeProgram = 0
    private var plainProgram = 0
    private var textureId = 0
    private var vbo = 0
    private var ibo = 0
    private var meshCount = 0
    private var meshKey: String? = null
    private var viewW = 0
    private var viewH = 0
    private var frameW = 0
    private var frameH = 0
    private var uploadedW = 0
    private var uploadedH = 0

    /** Uniform locations for the CRT program, cached at link time. */
    private val uniformLocs = HashMap<String, Int>()

    fun setFrame(bitmap: Bitmap) {
        pendingBitmap = bitmap
    }

    fun setPreset(
        p: ShaderPreset,
        shader: Boolean,
        curve: Boolean,
        edge: EdgeMode = EdgeMode.OFF,
        recon: ReconMode = ReconMode.OFF,
        reconGrid: Boolean = false,
    ) {
        preset = p
        shaderOn = shader
        curveOn = curve
        edgeMode = edge
        this.reconMode = recon
        this.reconGrid = reconGrid
        meshKey = null
    }

    /** The draw path that actually drew the last frame (the dev-stats diagnostic's ground truth). */
    fun drawPath(): String = lastDrawPath

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        program = buildProgram(CrtShaders.VERTEX, CrtShaders.FRAGMENT)
        if (program == 0) {
            Log.e(TAG, "CRT program failed to compile; using the plain fallback")
        }
        // 2.0, feature 7 — the edge-smoothing program. If it
        // fails to compile, the edge mode falls back to the CRT
        // program (or the plain one), so the game stays visible.
        edgeProgram = buildProgram(CrtShaders.VERTEX, CrtShaders.EDGE_FRAGMENT)
        if (edgeProgram == 0) {
            Log.e(TAG, "Edge program failed to compile; the edge mode falls back to the CRT program")
        }
        plainProgram = buildProgram(CrtShaders.VERTEX, CrtShaders.PLAIN_FRAGMENT)
        // 2.1.0 — the reconstruction's three programs (all
        // through RECON_VERTEX, which carries the uFlipV
        // uniform). If any fails to compile, the reconstruction
        // falls back to the CRT / plain program, so the game
        // stays visible.
        reconA1Program = buildProgram(ReconShaders.RECON_VERTEX, ReconShaders.RECON_A1_FRAGMENT)
        if (reconA1Program == 0) {
            Log.e(TAG, "Recon A1 program failed to compile; the reconstruction falls back to the CRT program")
        }
        reconA2Program = buildProgram(ReconShaders.RECON_VERTEX, ReconShaders.RECON_A2_FRAGMENT)
        if (reconA2Program == 0) {
            Log.e(TAG, "Recon A2 program failed to compile; the reconstruction falls back to the CRT program")
        }
        reconBProgram = buildProgram(ReconShaders.RECON_VERTEX, ReconShaders.RECON_B_FRAGMENT)
        if (reconBProgram == 0) {
            Log.e(TAG, "Recon B program failed to compile; the reconstruction falls back to the CRT program")
        }
        // 2.1.0 — the reconstruction's own framebuffer and its
        // two ping-pong textures (A1 -> texA, A2 -> texB).
        // Created here (and re-created if the EGL context is
        // ever lost) so the data is never stale; storage is
        // allocated on first use, at the frame's native size
        // (see ensureReconTex).
        val reconFbo = IntArray(1)
        GLES20.glGenFramebuffers(1, reconFbo, 0)
        reconFboId = reconFbo[0]
        val reconTex = IntArray(2)
        GLES20.glGenTextures(2, reconTex, 0)
        reconTexAId = reconTex[0]
        reconTexBId = reconTex[1]
        reconTexW = 0
        reconTexH = 0
        // 2.1.0 — the flat fullscreen quad for the recon's
        // FBO passes (A1, A2): a triangle strip, NDC with the
        // mesh's tex convention (aTex.y = 0 at the top, 1 at
        // the bottom — the Bitmap is top-down, so no flip).
        val flat = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        val flatBuf: FloatBuffer = ByteBuffer
            .allocateDirect(flat.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(flat); position(0) }
        val flatVbo = IntArray(1)
        GLES20.glGenBuffers(1, flatVbo, 0)
        reconFlatVbo = flatVbo[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, reconFlatVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            flat.size * 4,
            flatBuf,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        uploadedW = 0
        uploadedH = 0
        meshKey = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w
        viewH = h
        GLES20.glViewport(0, 0, w, h)
        meshKey = null
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val bmp = pendingBitmap
        if (bmp != null) {
            pendingBitmap = null
            if (textureId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                if (bmp.width == uploadedW && bmp.height == uploadedH) {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                } else {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    uploadedW = bmp.width
                    uploadedH = bmp.height
                }
                if (bmp.width != frameW || bmp.height != frameH) {
                    frameW = bmp.width
                    frameH = bmp.height
                    meshKey = null
                }
            }
        }
        if (textureId == 0 || frameW <= 0 || viewW <= 0 || viewH <= 0) return
        // 2.1.0 — the reconstruction wins when it is on (the
        // chain and the edge are off then). A recon program
        // that failed to compile, or a recon framebuffer that
        // cannot be created, falls through to the normal path,
        // so the game stays visible.
        if (reconMode != ReconMode.OFF && reconA1Program != 0 && reconA2Program != 0 && reconBProgram != 0 && ensureReconTex(frameW, frameH)) {
            drawRecon()
            return
        }
        // 2.0, feature 7 — the edge program wins when the edge
        // mode is on (the CRT is off then); otherwise the CRT
        // program when the shader is on, else the plain one. A
        // program that failed to compile falls through to the
        // next one, so the game stays visible.
        val prog = when {
            edgeMode != EdgeMode.OFF && edgeProgram != 0 -> edgeProgram
            shaderOn && program != 0 -> program
            else -> plainProgram
        }
        if (prog == 0) return
        lastDrawPath = when (prog) {
            edgeProgram -> if (edgeMode == EdgeMode.SHARP_CRT) "sharp-crt" else "edge"
            program -> "crt"
            else -> "plain"
        }
        ensureMesh()
        GLES20.glUseProgram(prog)
        GLES20.glUniform1i(uniform(prog, "uTex"), 0)
        if (prog == edgeProgram) {
            // 2.0, feature 7 — the edge program's uniforms.
            // Scanlines only for the "sharp CRT" combined mode,
            // and only at the preset's scan strength.
            GLES20.glUniform2f(uniform(prog, "uTexSize"), frameW.toFloat(), frameH.toFloat())
            GLES20.glUniform1f(uniform(prog, "uScan"), if (edgeMode == EdgeMode.SHARP_CRT) preset.scan else 0f)
        } else if (prog == program) {
            // The chain's uniforms; all zero/neutral when the
            // shader is off, so the pass is a plain sample.
            val on = shaderOn
            GLES20.glUniform2f(uniform(prog, "uTexSize"), frameW.toFloat(), frameH.toFloat())
            GLES20.glUniform1f(uniform(prog, "uScan"), if (on) preset.scan else 0f)
            GLES20.glUniform1f(uniform(prog, "uGrille"), if (on) preset.grille else 0f)
            GLES20.glUniform1f(uniform(prog, "uBlur"), if (on) preset.blur else 0f)
            GLES20.glUniform1f(uniform(prog, "uSat"), if (on) preset.color.saturation else 1f)
            GLES20.glUniform1f(uniform(prog, "uWarm"), if (on) preset.color.warm else 0f)
            GLES20.glUniform1f(uniform(prog, "uGamma"), if (on) preset.color.gamma else 1f)
            GLES20.glUniform1f(uniform(prog, "uBright"), if (on) preset.color.bright else 1f)
            GLES20.glUniform1f(uniform(prog, "uBlack"), if (on) preset.color.black else 0f)
            GLES20.glUniform1f(uniform(prog, "uVignette"), if (on) 0.18f else 0f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        ensureMagFilter(edgeMode != EdgeMode.OFF)
        drawMesh(prog)
    }

    /** The mesh (rebuilt only when its key changes): the curve and round values come from this system's preset. */
    private fun ensureMesh() {
        val curveK = ShaderPresets.curvePull(preset, curveOn, preset.curveAmount)
        val roundK = ShaderPresets.roundStrength(preset, curveOn)
        val rect = CrtMesh.letterboxRect(frameW, frameH, viewW, viewH)
        val key = "$curveK|$roundK|${rect.left}|${rect.top}|${rect.width}|${rect.height}|$viewW|$viewH"
        if (key != meshKey) {
            val mesh = CrtMesh.build(rect, curveK, roundK, viewW, viewH)
            uploadMesh(mesh)
            meshKey = key
        }
    }

    /**
     * The magnification filter follows the edge / recon mode:
     * GL_NEAREST while either is on (both need hard texels),
     * GL_LINEAR otherwise. Only set when the mode changed, so
     * it is not a per-frame state change.
     */
    private fun ensureMagFilter(nearest: Boolean) {
        if (lastEdgeMagNearest != nearest) {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR,
            )
            lastEdgeMagNearest = nearest
        }
    }

    /** Draw the curved mesh through [prog] (the mesh must already be built by ensureMesh()). */
    private fun drawMesh(prog: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, meshCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Draw the flat fullscreen quad (triangle strip) through [prog]. */
    private fun drawFlatQuad(prog: Int) {
        if (reconFlatVbo == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, reconFlatVbo)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * The reconstruction's framebuffer and its two ping-pong
     * textures, at the frame's native size. GL_NEAREST on
     * both: A2 reads A1's class channel (alpha) as a code, and
     * B reads B's class channel the same way — linear
     * filtering would blend the codes across texels and decode
     * the wrong class. A fresh texture's default min filter is
     * NEAREST_MIPMAP_LINEAR, which without mipmaps makes the
     * texture incomplete (samples as black), so the parameters
     * are mandatory.
     */
    private fun ensureReconTex(w: Int, h: Int): Boolean {
        if (w == reconTexW && h == reconTexH) return true
        if (reconFboId == 0 || reconTexAId == 0 || reconTexBId == 0) return false
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reconTexAId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reconTexBId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reconFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, reconTexAId, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Recon framebuffer incomplete: $status; reconstruction falls back to plain")
            reconTexW = 0
            reconTexH = 0
            return false
        }
        reconTexW = w
        reconTexH = h
        return true
    }

    /**
     * 2.1.0 — the reconstruction: Pass A1 (detectors + raw
     * colour, native size, into texA), Pass A2 (field + class
     * + collapse, native size, into texB), Pass B (the line
     * reconstruction, display size, to the screen through the
     * curved mesh). The flip constants are the triple the flip
     * fix proved (A1 1, A2 0, B 1); see ReconShaders.
     */
    private fun drawRecon() {
        val params = ReconParams.forMode(
            when (reconMode) {
                ReconMode.MVS -> "mvs"
                ReconMode.AES -> "aes"
                ReconMode.CD -> "cd"
                ReconMode.CDZ -> "cdz"
                ReconMode.OFF -> "mvs"
            },
        )
        val displayScale = minOf(
            viewW.toFloat() / frameW,
            viewH.toFloat() / frameH,
        )
        // The reconstruction needs hard texels (like the edge
        // shader), so the frame texture's magnification filter
        // is GL_NEAREST while it is on.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        ensureMagFilter(true)
        // Pass A1 — detectors + raw colour, native size, into
        // texA. The source is the frame Bitmap, and a Bitmap
        // texture reads true row v — but the pass WRITES into
        // an FBO, whose rows run bottom-first, so the stored
        // texture comes out flipped unless uFlipV is 1.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reconFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, reconTexAId, 0,
        )
        GLES20.glViewport(0, 0, frameW, frameH)
        GLES20.glUseProgram(reconA1Program)
        GLES20.glUniform1f(uniform(reconA1Program, "uFlipV"), if (ReconShaders.RECON_A1_FLIP_V) 1f else 0f)
        GLES20.glUniform1i(uniform(reconA1Program, "uTex"), 0)
        GLES20.glUniform2f(
            uniform(reconA1Program, "uTexSize"),
            frameW.toFloat(),
            frameH.toFloat(),
        )
        GLES20.glUniform1f(uniform(reconA1Program, "uDitherTol"), params.ditherTol)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        drawFlatQuad(reconA1Program)
        // Pass A2 — field + class + collapse, native size,
        // into texB. Reads texA (unit 0) and the raw frame
        // (unit 1, for the text ring's raw luma). texA is
        // stored unflipped now (A1's uFlipV 1), so the two
        // inputs agree row for row.
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, reconTexBId, 0,
        )
        GLES20.glViewport(0, 0, frameW, frameH)
        GLES20.glUseProgram(reconA2Program)
        GLES20.glUniform1f(uniform(reconA2Program, "uFlipV"), if (ReconShaders.RECON_A2_FLIP_V) 1f else 0f)
        GLES20.glUniform1i(uniform(reconA2Program, "uTex"), 0)
        GLES20.glUniform1i(uniform(reconA2Program, "uRawTex"), 1)
        GLES20.glUniform2f(
            uniform(reconA2Program, "uTexSize"),
            frameW.toFloat(),
            frameH.toFloat(),
        )
        GLES20.glUniform1f(uniform(reconA2Program, "uDitherTol"), params.ditherTol)
        GLES20.glUniform1f(uniform(reconA2Program, "uDitherGuardHi"), params.ditherGuardHi)
        GLES20.glUniform1f(uniform(reconA2Program, "uTextAlign"), params.textAlign)
        GLES20.glUniform1f(uniform(reconA2Program, "uEdgeLo"), params.edgeLo)
        GLES20.glUniform1f(uniform(reconA2Program, "uTextStroke"), if (params.textStroke) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reconTexAId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        drawFlatQuad(reconA2Program)
        // Pass B — the line reconstruction, display size, to
        // the screen through the curved mesh (the same mesh as
        // the normal path, so the curve/round settings apply).
        // The source is texB (a framebuffer texture), so V
        // flips (uFlipV 1).
        ensureMesh()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glUseProgram(reconBProgram)
        GLES20.glUniform1f(uniform(reconBProgram, "uFlipV"), if (ReconShaders.RECON_B_FLIP_V) 1f else 0f)
        GLES20.glUniform1i(uniform(reconBProgram, "uTex"), 0)
        GLES20.glUniform2f(
            uniform(reconBProgram, "uTexSize"),
            frameW.toFloat(),
            frameH.toFloat(),
        )
        GLES20.glUniform1f(uniform(reconBProgram, "uDisplayScale"), displayScale)
        GLES20.glUniform1f(
            uniform(reconBProgram, "uSnap12"),
            if (params.snap12) 1f else 0f,
        )
        GLES20.glUniform1f(
            uniform(reconBProgram, "uGrid"),
            if (reconGrid) ReconShaders.RECON_GRID_STRENGTH else 0f,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reconTexBId)
        drawMesh(reconBProgram)
        // The dev-stats readout: the reconstruction drew — the
        // token names the system.
        lastDrawPath = when (reconMode) {
            ReconMode.MVS -> "recon-mvs"
            ReconMode.AES -> "recon-aes"
            ReconMode.CD -> "recon-cd"
            ReconMode.CDZ -> "recon-cdz"
            ReconMode.OFF -> "plain"
        }
    }

    private fun uniform(prog: Int, name: String): Int {
        val key = "$prog:$name"
        return uniformLocs.getOrPut(key) { GLES20.glGetUniformLocation(prog, name) }
    }

    private fun uploadMesh(mesh: CrtMesh.Mesh) {
        if (vbo == 0) {
            val b = IntArray(1)
            GLES20.glGenBuffers(1, b, 0)
            vbo = b[0]
        }
        if (ibo == 0) {
            val b = IntArray(1)
            GLES20.glGenBuffers(1, b, 0)
            ibo = b[0]
        }
        val verts: FloatBuffer = ByteBuffer
            .allocateDirect(mesh.verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(mesh.verts); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            mesh.verts.size * 4,
            verts,
            GLES20.GL_STATIC_DRAW,
        )
        val idx: ShortBuffer = ByteBuffer
            .allocateDirect(mesh.indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(mesh.indices); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indices.size * 2,
            idx,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        meshCount = mesh.indices.size
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc) ?: return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            ?: run {
                GLES20.glDeleteShader(vs)
                return 0
            }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    companion object {
        private const val TAG = "CrtRenderer"
    }
}
