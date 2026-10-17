package app.aaps.wear.watchfaces.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one always-on (ambient) look of the Halo, Glass and Orbit faces (owner, 17 Oct 2026, after seeing the coloured
 * AOD live: "Always on screen is too much information. It should be simple and black and white only.").
 *
 * BLACK AND WHITE ONLY: a dimmed white and greys on pure black, in every mode (normal, burn-in protection, low-bit).
 * SIMPLE: the time on top (secondary), the BG with its trend arrow in the middle (the most prominent element) and a
 * small battery of the watch at the bottom; no date, delta, ring, card or discs (owner, 17 Oct 2026 ~22:20: "But the
 * watch is missing on the always-on display. Make it smaller. It's too big. Actually, it needs a little battery.").
 * Samsung's status icons (top, about y 15-55) and media chip (bottom, about y 405-430) keep their zones.
 * Because colour is gone, the safety states are said in words, on at most ONE line under the BG:
 * - urgent low / very high: "TIEF" / "HOCH" in a solid white pill with black letters (burn-in: a white outline pill);
 * - low: the white word "TIEF" (no pill), so the pill always means a danger level;
 * - old data: the BG grey, smaller and struck through, with "Keine Daten · 17′" / "vor 17′" in grey;
 * - aging data (from 6′): "vor 8′" in grey;
 * - loop stopped, phone lost, no profile (W1-W6): the face's one short warning in grey.
 * The battery is grey, and white at [BATTERY_LOW] % or less (still no colour).
 * The face shifts the canvas by a few px every minute under burn-in protection; low-bit draws pure white without
 * anti-aliasing. The bolus ball is never drawn in ambient. All coordinates are on the 450 px design grid.
 *
 * There is no setting: this is the only always-on look (the former face_aod_style choice is gone).
 */
object MonoAod {

    /** The word under the BG for a range that colour used to carry. */
    enum class Badge {
        /** Low: the plain word "TIEF". */
        LOW,
        /** Urgent low: "TIEF" in a solid pill. */
        URGENT_LOW,
        /** Very high: "HOCH" in a solid pill. */
        VERY_HIGH
    }

    /** What an always-on frame shows; built by the face from its own state once a minute (or on new data). */
    data class Model(
        /** The BG as the face writes it ("6,4", "224", "---", "<2,2"). */
        val hero: String,
        /** Old or no data: the BG in grey. */
        val grey: Boolean,
        /** A real but old reading: smaller and struck through. */
        val struck: Boolean,
        /** Arrow direction in degrees clockwise from 12 o'clock and the number of heads (0: no arrow). */
        val arrowDeg: Float,
        val arrowHeads: Int,
        val badge: Badge?,
        /** The one grey word line ("Loop 23′", "Telefon getrennt · 14′", "Keine Daten · 17′", "vor 8′"), or null. */
        val words: String?
    ) {

        /** Something is drawn under the BG. */
        val lineShown: Boolean get() = badge != null || words != null
    }

    /**
     * The model from the family state every face has. [warning] is the face's one ambient warning (W1-W6) in words, or
     * null; [age] the data age ("8′"), shown as "vor 8′" when the data is aging or old and no warning speaks for it.
     */
    fun model(
        hero: String, range: FaceState.Range, bgAge: FaceState.BgAge, caption: FaceState.Caption,
        arrowDeg: Float, arrowHeads: Int, warning: String?, age: String, agoFormat: String
    ): Model {
        val dashes = hero.trim() == FaceState.NO_BG
        val old = FaceState.isOld(bgAge)
        val fresh = !old && !dashes
        val badge = when {
            !fresh                                  -> null
            caption == FaceState.Caption.LOW_BADGE  -> Badge.URGENT_LOW
            caption == FaceState.Caption.LOW_WORD   -> Badge.LOW
            caption == FaceState.Caption.HIGH_WORD  -> Badge.VERY_HIGH
            else                                    -> null
        }
        val showAge = !dashes && age.isNotBlank() && (old || bgAge == FaceState.BgAge.AGING)
        val words = warning?.takeIf { it.isNotBlank() } ?: if (showAge) agoFormat.format(age) else null
        return Model(
            hero = hero,
            grey = !fresh || range == FaceState.Range.STALE,
            struck = FaceState.isStruck(bgAge) && !dashes,
            arrowDeg = arrowDeg,
            arrowHeads = if (fresh) arrowHeads else 0,
            badge = badge,
            words = words
        )
    }

    /** The BG and the arrow: a dimmed white, about 90 % (a pure white AMOLED field is harsh at night). */
    const val WHITE_D = 0xFFE6E6E6.toInt()
    /** Old data (the struck BG). */
    const val GREY_BG = 0xFF8A8A8A.toInt()
    /** The word line (warnings, data age). */
    const val GREY_WORDS = 0xFFA6A6A6.toInt()

    /** The time: a light grey, quieter than the BG. */
    const val GREY_TIME = 0xFFC4C4C4.toInt()

    /** Layout on the 450 grid (centre lines): the time on top, the BG in the middle, the one line, the battery. */
    const val C = 225f
    const val TIME_Y = 130f
    const val TIME_SIZE = 52f
    const val HERO_Y = 228f
    /** About 65 % of the former 172 px (owner: "Make it smaller. It's too big."); still the biggest element. */
    const val HERO_SIZE = 112f
    const val HERO_MAX_W = 300f
    const val LINE_Y = 314f
    const val BATTERY_Y = 376f
    const val BATTERY_SIZE = 24f
    /** The battery glyph (body without the nub), its outline and the nub. */
    const val BATTERY_W = 28f
    const val BATTERY_H = 14f
    const val BATTERY_RING = 2f
    const val BATTERY_NUB_W = 3f
    const val BATTERY_NUB_H = 6f
    const val BATTERY_GAP = 8f
    /** At or below this the battery is drawn white (and bold), so it stands out without colour. */
    const val BATTERY_LOW = 15

    /** Samsung's zones the always-on look keeps clear (450 grid, rows): status icons on top, the media chip below. */
    const val SAMSUNG_TOP_Y0 = 15f
    const val SAMSUNG_TOP_Y1 = 55f
    const val SAMSUNG_BOTTOM_Y0 = 405f
    const val SAMSUNG_BOTTOM_Y1 = 430f

    const val ARROW_BOX = 0.46f
    const val ARROW_GAP = 0.06f
    /** Close to the digits' stroke at weight 700 (about 0.14 em), so the arrow is as heavy as the number. */
    const val ARROW_STROKE = 0.13f
    const val ARROW_STROKE_DOUBLE = 0.10f
    const val LINE_SIZE = 30f
    const val BADGE_SIZE = 30f
    const val BADGE_H = 44f
    const val STALE_K = 0.75f
    /** Cap height of the digits in em (Roboto about 0.71). */
    const val CAP_K = 0.71f
    /** The strike's centre as a share of the cap height above the baseline (off the digits' waist, where 8 / 0 / 6 differ). */
    const val STRIKE_Y = 0.64f
    const val STRIKE_K = 0.06f
    const val STRIKE_MIN = 8f
    const val STRIKE_GAP = 2f
    const val STRIKE_OVER = 20f
    const val BADGE_PAD = 12f
    const val BADGE_RING = 4f
    const val LINE_GAP = 14f
    const val MIN_K = 0.72f

    /** The watch's battery in percent from level and scale (as in ACTION_BATTERY_CHANGED), null when unknown. */
    fun batteryPercent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100f / scale).roundToInt().coerceIn(0, 100)

    /** The battery's words: "82 %" ([format] is the locale's "%d %%" or "%d%%"). */
    fun batteryText(percent: Int, format: String = "%d %%"): String = format.format(percent)

    /** A low battery is drawn white (no colour exists here). */
    fun batteryLow(percent: Int): Boolean = percent <= BATTERY_LOW

    /** The time without AM/PM (simple): "07:05" in 24 h, "7:05" in 12 h. */
    fun timeText(hour: Int, minute: Int, is24h: Boolean): String {
        val h = if (is24h) hour else (hour % 12).let { if (it == 0) 12 else it }
        val mm = if (minute < 10) "0$minute" else "$minute"
        return if (is24h && h < 10) "0$h:$mm" else "$h:$mm"
    }

    /**
     * The watch's own battery from the sticky ACTION_BATTERY_CHANGED broadcast (no receiver, no polling: read when an
     * always-on frame is drawn, at most once a minute). [read] returns that sticky intent; injectable for tests.
     */
    class Battery(private val read: () -> Intent?) {

        constructor(context: Context) : this({
            try {
                context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (_: Exception) {
                null
            }
        })

        private var minute = Long.MIN_VALUE
        private var last: Int? = null

        /** The percent at [nowMs], read again only when the minute changed; null when unknown (nothing is drawn). */
        fun percent(nowMs: Long): Int? {
            val m = nowMs / FaceState.MINUTE_MS
            if (m != minute) {
                minute = m
                last = read()?.let { batteryPercent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) }
            }
            return last
        }
    }

    /** Half chord of the dial at [y] (450 grid, radius 225), less [margin] on each side. */
    fun halfChord(y: Float, margin: Float = 40f): Float {
        val dy = y - C
        return max(0f, sqrt(max(0f, 225f * 225f - dy * dy)) - margin)
    }

    /** Draws a [Model] on the 450 grid; all paints are its own, so the interactive face's paints stay untouched. */
    class Painter {

        private val text = Paint()
        private val stroke = Paint()
        private val fm = Paint.FontMetrics()
        private val arrow = Path()
        private val box = RectF()
        private val faces = HashMap<Int, Typeface>()

        var lowWord = "TIEF"
        var highWord = "HOCH"
        /** The battery's words for the locale ("%d %%" in German, "%d%%" in English). */
        var batteryFormat = "%d %%"

        /** Lit area of the last frame's BG group (design px), for the harness. */
        val heroBox = RectF()
        /** Lit area of the last frame's time and battery (design px; empty when not drawn), for tests. */
        val timeBox = RectF()
        val batteryBox = RectF()

        private fun tf(weight: Int): Typeface = faces.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }

        private fun setText(size: Float, weight: Int, colour: Int, aa: Boolean) {
            text.reset()
            // Paint.reset() does not clear letterSpacing on every runtime: the badge's tracking must not leak
            text.letterSpacing = 0f
            text.isAntiAlias = aa
            text.textAlign = Paint.Align.LEFT
            text.typeface = tf(weight)
            text.textSize = size
            text.color = colour
        }

        private fun baseline(centreY: Float): Float {
            text.getFontMetrics(fm)
            return centreY - (fm.ascent + fm.descent) / 2f
        }

        private fun setStroke(width: Float, colour: Int, aa: Boolean) {
            stroke.reset()
            stroke.isAntiAlias = aa
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = width
            stroke.color = colour
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
        }

        /** Low-bit: pure white for everything (no greys exist there). */
        private fun c(colour: Int, lowBit: Boolean) = if (lowBit) Color.WHITE else colour

        /** One frame: [time] on top, the BG, the one line, and [battery] (percent, null: not drawn) at the bottom. */
        fun draw(canvas: Canvas, m: Model, time: String, battery: Int?, lowBit: Boolean, burnIn: Boolean) {
            drawTime(canvas, time, lowBit, burnIn)
            drawHero(canvas, m, lowBit, burnIn)
            if (m.lineShown) drawLine(canvas, m, lowBit, burnIn)
            batteryBox.setEmpty()
            if (battery != null) drawBattery(canvas, battery, lowBit, burnIn)
        }

        private fun drawTime(canvas: Canvas, time: String, lowBit: Boolean, burnIn: Boolean) {
            timeBox.setEmpty()
            if (time.isBlank()) return
            setText(TIME_SIZE, if (burnIn) 400 else 500, c(GREY_TIME, lowBit), !lowBit)
            val w = text.measureText(time)
            val by = baseline(TIME_Y)
            canvas.drawText(time, C - w / 2f, by, text)
            timeBox.set(C - w / 2f, by - CAP_K * TIME_SIZE, C + w / 2f, by)
        }

        /** A small battery glyph (outline, nub, a bar for the level) and "82 %", centred as one group. */
        private fun drawBattery(canvas: Canvas, percent: Int, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val low = batteryLow(percent)
            val colour = c(if (low) WHITE_D else GREY_WORDS, lowBit)
            val words = batteryText(percent, batteryFormat)
            setText(BATTERY_SIZE, if (low) 700 else if (burnIn) 400 else 500, colour, aa)
            val tw = text.measureText(words)
            val gw = BATTERY_W + BATTERY_NUB_W + BATTERY_GAP + tw
            val x0 = C - gw / 2f
            val y = BATTERY_Y
            // body outline
            setStroke(BATTERY_RING, colour, aa)
            stroke.strokeJoin = Paint.Join.MITER
            box.set(x0, y - BATTERY_H / 2f, x0 + BATTERY_W, y + BATTERY_H / 2f)
            box.inset(BATTERY_RING / 2f, BATTERY_RING / 2f)
            canvas.drawRoundRect(box, 2.5f, 2.5f, stroke)
            // nub and the level bar (at least a sliver, so an empty battery still shows a mark)
            stroke.style = Paint.Style.FILL
            canvas.drawRect(x0 + BATTERY_W, y - BATTERY_NUB_H / 2f, x0 + BATTERY_W + BATTERY_NUB_W, y + BATTERY_NUB_H / 2f, stroke)
            val inner = BATTERY_RING + 1.5f
            val fullW = BATTERY_W - 2f * inner
            val fillW = max(2f, fullW * percent / 100f)
            canvas.drawRect(x0 + inner, y - BATTERY_H / 2f + inner, x0 + inner + fillW, y + BATTERY_H / 2f - inner, stroke)
            val tx = x0 + BATTERY_W + BATTERY_NUB_W + BATTERY_GAP
            val by = baseline(y)
            canvas.drawText(words, tx, by, text)
            batteryBox.set(x0, min(y - BATTERY_H / 2f, by - CAP_K * BATTERY_SIZE), tx + tw, max(y + BATTERY_H / 2f, by))
        }

        private fun drawHero(canvas: Canvas, m: Model, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val colour = c(if (m.grey) GREY_BG else WHITE_D, lowBit)
            setText(HERO_SIZE, if (burnIn) 600 else 700, colour, aa)
            text.letterSpacing = -0.02f
            val arrowOn = m.arrowHeads > 0
            // old data: about 75 % size, so the "Keine Daten · 17′" line weighs as much as the number
            var size = if (m.struck) STALE_K * HERO_SIZE else HERO_SIZE
            fun groupW(sz: Float): Float = text.measureText(m.hero) * sz / HERO_SIZE + if (arrowOn) (ARROW_GAP + ARROW_BOX) * sz else 0f
            val maxW = min(HERO_MAX_W, 2f * halfChord(HERO_Y, 30f)) - if (m.struck) 2f * STRIKE_OVER else 0f
            val w0 = groupW(size)
            if (w0 > maxW) size *= maxW / w0
            val gw = groupW(size)
            text.textSize = size
            val digitsW = text.measureText(m.hero)
            val x0 = C - gw / 2f
            val by = baseline(HERO_Y)
            val cy = by - CAP_K / 2f * size
            canvas.drawText(m.hero, x0, by, text)
            heroBox.set(x0, by - 0.72f * size, x0 + gw, by)
            if (m.struck) {
                // a bar cut out of the digits by a thin black gap (low-bit too), so the last value stays readable
                val bar = max(STRIKE_MIN, STRIKE_K * size)
                val sy = by - STRIKE_Y * CAP_K * size
                setStroke(bar + 2f * STRIKE_GAP, Color.BLACK, aa)
                stroke.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(x0 - STRIKE_OVER, sy, x0 + digitsW + STRIKE_OVER, sy, stroke)
                setStroke(bar, colour, aa)
                stroke.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(x0 - STRIKE_OVER, sy, x0 + digitsW + STRIKE_OVER, sy, stroke)
            }
            if (arrowOn) {
                val b = ARROW_BOX * size
                val ax = x0 + digitsW + ARROW_GAP * size + b / 2f
                buildArrow(m.arrowDeg, m.arrowHeads, b)
                setStroke(max(5f, (if (m.arrowHeads > 1) ARROW_STROKE_DOUBLE else ARROW_STROKE) * size), colour, aa)
                canvas.save()
                canvas.translate(ax, cy)
                canvas.drawPath(arrow, stroke)
                canvas.restore()
            }
        }

        private fun buildArrow(deg: Float, heads: Int, b: Float) {
            arrow.rewind()
            val rad = Math.toRadians(deg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val shaft = 0.84f * b
            val spread = if (heads > 1) 0.30f * b else 0f
            val headLen = if (heads > 1) 0.26f * b else 0.36f * b
            for (k in 0 until heads) {
                val off = if (heads > 1) (k - (heads - 1) / 2f) * 2f * spread else 0f
                val ox = -dy * off
                val oy = dx * off
                val tipX = ox + dx * shaft / 2f
                val tipY = oy + dy * shaft / 2f
                arrow.moveTo(ox - dx * shaft / 2f, oy - dy * shaft / 2f)
                arrow.lineTo(tipX, tipY)
                for (sign in intArrayOf(-1, 1)) {
                    val a = rad + Math.PI + sign * Math.toRadians(40.0)
                    arrow.moveTo(tipX, tipY)
                    arrow.lineTo(tipX + sin(a).toFloat() * headLen, tipY - cos(a).toFloat() * headLen)
                }
            }
        }

        /** The badge (TIEF / HOCH) and the grey words after it, centred as one group on the one line. */
        private fun drawLine(canvas: Canvas, m: Model, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val y = LINE_Y
            val maxW = 2f * halfChord(y, 44f)
            val word = when (m.badge) {
                Badge.LOW, Badge.URGENT_LOW -> lowWord
                Badge.VERY_HIGH             -> highWord
                null                        -> null
            }
            val pill = m.badge == Badge.URGENT_LOW || m.badge == Badge.VERY_HIGH
            val white = c(WHITE_D, lowBit)
            val grey = c(GREY_WORDS, lowBit)
            setText(BADGE_SIZE, 700, white, aa)
            text.letterSpacing = 0.06f
            val bw = if (word != null) text.measureText(word) + (if (pill) 2f * BADGE_PAD else 0f) else 0f
            setText(LINE_SIZE, if (burnIn) 500 else 600, grey, aa)
            val ww = m.words?.let { text.measureText(it) } ?: 0f
            val gap = if (word != null && m.words != null) LINE_GAP else 0f
            val total = bw + gap + ww
            val k = if (total > maxW) max(MIN_K, maxW / total) else 1f
            var x = C - total * k / 2f
            if (word != null) {
                setText(BADGE_SIZE * k, 700, white, aa)
                text.letterSpacing = 0.06f
                val by = baseline(y)
                if (pill) {
                    // a danger level: a small solid white pill with black letters; burn-in protection: a white outline
                    val h = BADGE_H * k
                    box.set(x, y - h / 2f, x + bw * k, y + h / 2f)
                    stroke.reset()
                    stroke.isAntiAlias = aa
                    stroke.color = white
                    if (burnIn) {
                        stroke.style = Paint.Style.STROKE
                        stroke.strokeWidth = BADGE_RING
                        box.inset(BADGE_RING / 2f, BADGE_RING / 2f)
                    } else stroke.style = Paint.Style.FILL
                    canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, stroke)
                    text.color = if (burnIn) white else Color.BLACK
                    canvas.drawText(word, x + BADGE_PAD * k, by, text)
                } else canvas.drawText(word, x, by, text)
                x += (bw + gap) * k
            }
            m.words?.let {
                setText(LINE_SIZE * k, if (burnIn) 500 else 600, grey, aa)
                canvas.drawText(it, x, baseline(y), text)
            }
        }
    }
}
