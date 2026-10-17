package app.aaps.wear.interaction.actions

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * Draws a BG trend arrow ("→", "↘", "⇊", ...) as a bold vector arrow at the cap height of the text around it, instead of
 * the font's thin, small arrow glyph. Display only. Symbols it does not know are drawn as text.
 */
class TrendArrowSpan(private val symbol: String, private val color: Int) : ReplacementSpan() {

    /** Direction in degrees (0 = right, 90 = down) and whether it is a double arrow, or null for an unknown symbol. */
    private val shape: Pair<Float, Boolean>? = when (symbol.trim()) {
        "→"      -> 0f to false
        "↗"      -> -45f to false
        "↑"      -> -90f to false
        "↘"      -> 45f to false
        "↓"      -> 90f to false
        "⇈", "↑↑" -> -90f to true
        "⇊", "↓↓" -> 90f to true
        else     -> null
    }

    private fun capHeight(paint: Paint): Float = Rect().also { paint.getTextBounds("H", 0, 1, it) }.height().toFloat()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val s = shape ?: return paint.measureText(symbol).roundToInt()
        fm?.let { paint.getFontMetricsInt(it) }
        val h = capHeight(paint)
        return (h * (if (s.second) 1.45f else 1.0f) + h * 0.2f).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val s = shape
        if (s == null) {
            val p = Paint(paint).apply { this.color = this@TrendArrowSpan.color }
            canvas.drawText(symbol, x, y.toFloat(), p)
            return
        }
        val h = capHeight(paint)
        val stroke = (paint.textSize * 0.11f).coerceAtLeast(2.5f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@TrendArrowSpan.color
        }
        val count = if (s.second) 2 else 1
        val gap = h * 0.45f
        val cy = y - h / 2f
        for (k in 0 until count) {
            val cx = x + h * 0.1f + h / 2f + k * gap
            canvas.save()
            canvas.rotate(s.first, cx, cy)
            // the arrow points right before the rotation; its length fills the cap height
            val half = h / 2f - stroke / 2f
            val head = h * 0.36f
            canvas.drawLine(cx - half, cy, cx + half, cy, p)
            canvas.drawPath(Path().apply {
                moveTo(cx + half - head, cy - head)
                lineTo(cx + half, cy)
                lineTo(cx + half - head, cy + head)
            }, p)
            canvas.restore()
        }
    }
}
