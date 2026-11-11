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

    fun setPreset(p: ShaderPreset, shader: Boolean, curve: Boolean, edge: EdgeMode = EdgeMode.OFF) {
        preset = p
        shaderOn = shader
        curveOn = curve
        edgeMode = edge
        meshKey = null
    }

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
        // The mesh (rebuilt only when its key changes): the
        // curve and round values come from this system's preset.
        val curveK = ShaderPresets.curvePull(preset, curveOn, preset.curveAmount)
        val roundK = ShaderPresets.roundStrength(preset, curveOn)
        val rect = CrtMesh.letterboxRect(frameW, frameH, viewW, viewH)
        val key = "$curveK|$roundK|${rect.left}|${rect.top}|${rect.width}|${rect.height}|$viewW|$viewH"
        if (key != meshKey) {
            val mesh = CrtMesh.build(rect, curveK, roundK, viewW, viewH)
            uploadMesh(mesh)
            meshKey = key
        }
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
        // 2.0, feature 7 — the magnification filter follows the
        // edge mode: GL_NEAREST while the edge shader is on (it
        // needs hard texels), GL_LINEAR otherwise. Only set when
        // the mode changed, so it is not a per-frame state change.
        val wantNearest = edgeMode != EdgeMode.OFF
        if (lastEdgeMagNearest != wantNearest) {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                if (wantNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR,
            )
            lastEdgeMagNearest = wantNearest
        }
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
