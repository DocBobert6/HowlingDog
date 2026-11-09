package app.howlingdog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Spike-only video view: draws the latest frame Bitmap, letterboxed
 * to preserve the core's aspect ratio. The Bitmap is swapped (never
 * mutated) while it is on screen: PlayerSession double-buffers, so
 * onDraw only ever reads a finished Bitmap.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun setBitmap(b: Bitmap) {
        bitmap = b
        postInvalidate()
    }

    fun clearBitmap() {
        bitmap = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        canvas.drawColor(Color.BLACK)
        if (b == null) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = minOf(vw / b.width, vh / b.height)
        val dw = (b.width * scale).toInt()
        val dh = (b.height * scale).toInt()
        val left = ((vw - dw) / 2f).toInt()
        val top = ((vh - dh) / 2f).toInt()
        srcRect.set(0, 0, b.width, b.height)
        dstRect.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(b, srcRect, dstRect, paint)
    }
}
