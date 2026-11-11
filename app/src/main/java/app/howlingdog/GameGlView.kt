package app.howlingdog

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * The game view: a GLSurfaceView that presents the frame
 * Bitmap through the CRT renderer (CrtRenderer) — the shader
 * chain and the curve/round mesh. It replaces the old Canvas
 * GameView (a plain drawBitmap), which could not run a shader
 * chain. Renders on demand: a new frame or a preset change
 * requests a redraw, and the last frame stays on screen while
 * paused. The EGL context is preserved across pauses so a
 * rotation does not lose the texture (it is re-uploaded on the
 * next frame in any case).
 *
 * The touch controls are Compose content drawn over this view
 * (standard mobile-emulator layout): the surface sits behind
 * the window's view hierarchy, so the overlay draws on top.
 */
class GameGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = CrtRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        setPreserveEGLContextOnPause(true)
    }

    /** Present a new frame Bitmap (called from PlayerSession's onFrame, on the main thread). */
    fun setFrame(bitmap: Bitmap) {
        renderer.setFrame(bitmap)
        requestRender()
    }

    /**
     * Apply a system's preset and toggles (called at game start,
     * and when the game changes). The edge mode is forwarded to
     * the renderer: when it is on, the edge program draws and
     * the CRT program does not.
     */
    fun setPreset(preset: ShaderPreset, shaderOn: Boolean, curveOn: Boolean, edge: EdgeMode = EdgeMode.OFF) {
        renderer.setPreset(preset, shaderOn, curveOn, edge)
        requestRender()
    }
}
