package app.aaps.wear.watchfaces.orbit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.aaps.wear.watchfaces.common.AodStyle
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Trend
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit always-on (AOD round 2, owner: "too hard to read with the fine, thin lines, and it's too much information").
 *
 * Every look is BOLD and sparse: solid digits in a dimmed range colour (no outlines, no hairlines), the trend arrow
 * beside them, and at most ONE line of words under them (TIEF / HOCH, "Keine Daten · 17′", "Loop 23′", "Loop aus",
 * "Telefon getrennt · 14′", or the amber data age from 6′). No ring, discs, chart or date. Burn-in safety comes from
 * the dim colours, the per-minute pixel shift and a low lit share (about 3-8 % of the dial), not from thin strokes.
 * Low-bit: white, no anti-aliasing. Stale data is grey and struck through, and its words say how old it is.
 *
 * The shared setting face_aod_style keeps its values; Orbit maps them to its own looks ([look]).
 */
object OrbitAod {

    /** The explored looks (AOD round 2). Only [look] decides which ones the setting offers. */
    enum class Look {
        /** Huge BG + arrow, nothing else (the one warning line only when needed). */
        BG_ONLY,
        /** Small time on top, huge BG + arrow (the default: still a watch, still one glance). */
        BG_TIME,
        /** Huge BG + arrow, the time small under it. */
        TIME_BELOW,
        /** A big dim time, the BG medium under it. */
        BIG_TIME,
        /** Small time, BG + arrow and a tiny 1 h trend line. */
        BG_SPARK,
        /** Small time, BG + arrow and the delta with the data age ("+0,1 · 2′") under it. */
        BG_DELTA
    }

    /**
     * Orbit's looks for the shared values (key and values unchanged for compatibility): "outline" (the default)
     * is BG + time, "minimal" the BG alone, "glance" BG + time + delta and age.
     */
    fun look(style: AodStyle): Look = when (style) {
        AodStyle.OUTLINE -> Look.BG_TIME
        AodStyle.MINIMAL -> Look.BG_ONLY
        AodStyle.GLANCE  -> Look.BG_DELTA
    }

    enum class Hue { IN, HIGH, LOW, STALE }
    enum class Level { INFO, WARN, ALERT }

    /** What an always-on frame shows; built once per minute (or data change) by the face, drawn by [Painter]. */
    data class Model(
        val hero: String,
        val hue: Hue,
        /** A real but old reading: grey and struck through. */
        val struck: Boolean,
        val trend: Trend,
        val time: String,
        /** "TIEF" (urgent low) / "HOCH" (very high) as a solid pill; a plain low or high says it by colour + arrow. */
        val badge: String?,
        val badgeBoxed: Boolean,
        /** The one warning in words (W1-W6), or null. */
        val warning: String?,
        val warningLevel: Level,
        /** Delta as sent ("+0,1", "-0.0", "+2"), shown only by [Look.BG_DELTA]. */
        val delta: String,
        /** Data age ("2′"); [ageAmber] from 6′ (then every look shows it). */
        val age: String,
        val ageAmber: Boolean,
        /** The last hour as x/y pairs in 0..1 (y up), or empty; only for fresh data. */
        val spark: FloatArray = FloatArray(0)
    ) {

        /** A line under the BG in every look: the badge and/or the warning, or the delta row with the amber age. */
        val lineShown: Boolean get() = badge != null || warning != null || ageAmber

        // field by field: the generated equals would compare the FloatArray by identity
        override fun equals(other: Any?): Boolean = other is Model && hero == other.hero && hue == other.hue &&
            struck == other.struck && trend == other.trend && time == other.time && badge == other.badge &&
            badgeBoxed == other.badgeBoxed && warning == other.warning && warningLevel == other.warningLevel &&
            delta == other.delta && age == other.age && ageAmber == other.ageAmber && spark.contentEquals(other.spark)

        override fun hashCode(): Int =
            listOf(hero, hue, struck, trend, time, badge, badgeBoxed, warning, warningLevel, delta, age, ageAmber).hashCode() * 31 +
                spark.contentHashCode()
    }

    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, time: String, caption: FaceState.Caption, slot: Slot?,
        deltaRow: OrbitFaceLogic.DeltaRow, delta: String, spark: FloatArray = FloatArray(0)
    ): Model {
        val dashes = hero == "---"
        val old = OrbitFaceLogic.isOld(bgAge)
        val hue = when {
            old || dashes                                -> Hue.STALE
            range == Range.LOW || range == Range.URGENT_LOW -> Hue.LOW
            range == Range.HIGH || range == Range.VERY_HIGH -> Hue.HIGH
            range == Range.STALE                         -> Hue.STALE
            else                                         -> Hue.IN
        }
        val badge = when {
            dashes || old                       -> null
            // only the two danger levels get words, as equally weighted pills; a plain low (3,4) stays wordless like a
            // plain high: red + arrow say it, and one word for two levels would hide which one is urgent
            caption == FaceState.Caption.LOW_BADGE -> "LOW"
            caption == FaceState.Caption.HIGH_WORD -> "HIGH"
            else                                -> null
        }
        val warning = slot?.takeIf { it.rank in 1..6 && it.text.isNotEmpty() }
        return Model(
            hero = hero,
            hue = hue,
            struck = OrbitFaceLogic.isStruck(bgAge) && !dashes,
            trend = if (old || dashes) Trend.NONE else trend,
            time = time,
            badge = badge,
            badgeBoxed = caption == FaceState.Caption.LOW_BADGE || caption == FaceState.Caption.HIGH_WORD,
            warning = warning?.text,
            warningLevel = when (warning?.tone) {
                Tone.ALERT -> Level.ALERT
                Tone.WARN  -> Level.WARN
                else       -> Level.INFO
            },
            delta = FaceState.deltaText(delta),
            age = deltaRow.agePart,
            ageAmber = deltaRow.ageAmber && !old && !dashes,
            spark = if (old || dashes) FloatArray(0) else spark
        )
    }

    /**
     * The last [windowMs] of readings (timestamp, mg/dL), oldest first, as x/y pairs in 0..1 (y up). The y range is
     * at least [MIN_SPAN_MGDL] tall, so a calm hour stays a calm line.
     */
    fun sparkPoints(ts: LongArray, mgdl: DoubleArray, now: Long, windowMs: Long = 60 * 60_000L): FloatArray {
        val from = now - windowMs
        val idx = ts.indices.filter { ts[it] in from..now && mgdl[it] > 0 }.sortedBy { ts[it] }
        if (idx.size < 2) return FloatArray(0)
        var lo = idx.minOf { mgdl[it] }
        var hi = idx.maxOf { mgdl[it] }
        if (hi - lo < MIN_SPAN_MGDL) {
            val mid = (hi + lo) / 2
            lo = mid - MIN_SPAN_MGDL / 2
            hi = mid + MIN_SPAN_MGDL / 2
        }
        val out = FloatArray(idx.size * 2)
        idx.forEachIndexed { k, i ->
            out[2 * k] = ((ts[i] - from).toFloat() / windowMs).coerceIn(0f, 1f)
            out[2 * k + 1] = ((mgdl[i] - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    const val MIN_SPAN_MGDL = 54.0

    // dimmed design-system colours (AOD round 2): bold digits at about 60-70 % of the interactive brightness
    const val MINT = 0xFF8FD8B4.toInt()
    const val AMBER_D = 0xFFD9A048.toInt()
    const val RED_D = 0xFFE0566A.toInt()
    const val STALE_D = 0xFF7A8296.toInt()
    const val TIME_D = 0xFFA3AAB8.toInt()
    const val INFO_D = 0xFF8E95A3.toInt()

    /** Vertical layout of a look on the 450 grid (centre lines). 0 means "not drawn". */
    data class Spec(
        val timeY: Float, val timeSize: Float, val timeWeight: Int,
        val heroY: Float, val heroSize: Float, val heroMaxW: Float,
        val lineY: Float, val sparkY: Float = 0f
    )

    fun spec(look: Look): Spec = when (look) {
        Look.BG_ONLY    -> Spec(0f, 0f, 0, heroY = 212f, heroSize = 172f, heroMaxW = 330f, lineY = 334f)
        Look.BG_TIME    -> Spec(98f, 40f, 600, heroY = 220f, heroSize = 156f, heroMaxW = 320f, lineY = 335f)
        Look.TIME_BELOW -> Spec(338f, 40f, 600, heroY = 182f, heroSize = 156f, heroMaxW = 320f, lineY = 284f)
        Look.BIG_TIME   -> Spec(160f, 104f, 500, heroY = 280f, heroSize = 84f, heroMaxW = 250f, lineY = 350f)
        Look.BG_SPARK   -> Spec(98f, 40f, 600, heroY = 210f, heroSize = 140f, heroMaxW = 310f, lineY = 352f, sparkY = 304f)
        Look.BG_DELTA   -> Spec(98f, 40f, 600, heroY = 216f, heroSize = 150f, heroMaxW = 320f, lineY = 327f)
    }

    /** Half chord of the dial at [y] (450 grid, radius 225), less [margin] on each side. */
    fun halfChord(y: Float, margin: Float = 40f): Float {
        val dy = y - OrbitFaceLogic.CY
        return max(0f, sqrt(max(0f, 225f * 225f - dy * dy)) - margin)
    }

    /** Draws a [Model] in a [Look]; all paints are its own, so the interactive face's paints stay untouched. */
    class Painter {

        private val text = Paint()
        private val stroke = Paint()
        private val fm = Paint.FontMetrics()
        private val arrow = Path()
        private val spark = Path()
        private val box = RectF()
        private val faces = HashMap<Int, Typeface>()

        /** Labels of the TIEF / HOCH badge (the model carries LOW / HIGH). */
        var lowWord = "TIEF"
        var highWord = "HOCH"

        /** The age on its own ("Nur BZ" from 6′): "vor %s" / "%s ago". */
        var agoFormat = "vor %s"

        private fun tf(weight: Int): Typeface = faces.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }

        private fun hue(h: Hue, lowBit: Boolean): Int = if (lowBit) Color.WHITE else when (h) {
            Hue.IN    -> MINT
            Hue.HIGH  -> AMBER_D
            Hue.LOW   -> RED_D
            Hue.STALE -> STALE_D
        }

        private fun level(l: Level, lowBit: Boolean): Int = if (lowBit) Color.WHITE else when (l) {
            Level.INFO  -> INFO_D
            Level.WARN  -> AMBER_D
            Level.ALERT -> RED_D
        }

        private fun setText(size: Float, weight: Int, colour: Int, aa: Boolean) {
            text.reset()
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

        /** Lit area of the last frame's hero group (design px), for the harness. */
        val heroBox = RectF()

        fun draw(canvas: Canvas, m: Model, look: Look, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val s = spec(look)
            // time
            if (s.timeY > 0f && m.time.isNotEmpty()) {
                setText(s.timeSize, if (burnIn) max(400, s.timeWeight - 100) else s.timeWeight, if (lowBit) Color.WHITE else TIME_D, aa)
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(m.time, OrbitFaceLogic.CX, baseline(s.timeY), text)
            }
            drawHero(canvas, m, s, lowBit, burnIn)
            if (s.sparkY > 0f && m.spark.size >= 4) drawSpark(canvas, m, s, lowBit)
            val line = lineFor(m, look)
            if (line != null) drawLine(canvas, m, line, s.lineY, lowBit, burnIn, ageOnly = look == Look.BG_ONLY)
        }

        /** What the row under the BG shows in [look]: the warning words, or (BG_DELTA) the delta and age. */
        private fun lineFor(m: Model, look: Look): Line? = when {
            m.badge != null || m.warning != null -> Line.WARNING
            // from 6′ every look warns with the amber age; BG_ONLY ("Nur BZ") shows the age alone, the others "+0,1 · 8′"
            look == Look.BG_DELTA || m.ageAmber  -> Line.DELTA
            else                                 -> null
        }

        private enum class Line { WARNING, DELTA }

        private fun drawHero(canvas: Canvas, m: Model, s: Spec, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val colour = hue(m.hue, lowBit)
            val weight = if (burnIn) 600 else 700
            setText(s.heroSize, weight, colour, aa)
            text.letterSpacing = -0.02f
            val arrowOn = m.trend != Trend.NONE
            // old data: about 75 % size, so the "Keine Daten · 17′" line weighs as much as the number
            var size = if (m.struck) STALE_K * s.heroSize else s.heroSize
            fun groupW(sz: Float): Float {
                val k = sz / s.heroSize
                val digits = text.measureText(m.hero) * k
                return digits + if (arrowOn) (ARROW_GAP + ARROW_BOX) * sz else 0f
            }
            val maxW = min(s.heroMaxW, 2f * halfChord(s.heroY, 30f)) - if (m.struck) 2f * STRIKE_OVER else 0f
            val w0 = groupW(size)
            if (w0 > maxW) size *= maxW / w0
            text.textSize = size
            val digitsW = text.measureText(m.hero)
            val gw = groupW(size)
            val x0 = OrbitFaceLogic.CX - gw / 2f
            val by = baseline(s.heroY)
            // digits' optical centre: the cap height (about 0.71 em) above the baseline
            val cy = by - CAP_K / 2f * size
            canvas.drawText(m.hero, x0, by, text)
            heroBox.set(x0, by - 0.72f * size, x0 + gw, by)
            if (m.struck) {
                // a bar cut out of the digits by a thin black gap, so it never melts into the strokes (low-bit too). It
                // sits above the digits' middle (STRIKE_Y of the cap height), not across their waist, where 8 / 0 / 6 /
                // 9 / 5 / 3 differ: the last value must stay readable (panel round 3)
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
                val box = ARROW_BOX * size
                val ax = x0 + digitsW + ARROW_GAP * size + box / 2f
                buildArrow(m.trend, box)
                // a double arrow's two shafts need air between them, so it draws a little lighter
                val k = if (m.trend.heads > 1) ARROW_STROKE_DOUBLE else ARROW_STROKE
                setStroke(max(5f, k * size), colour, aa)
                canvas.save()
                canvas.translate(ax, cy)
                canvas.drawPath(arrow, stroke)
                canvas.restore()
            }
        }

        private fun buildArrow(trend: Trend, box: Float) {
            arrow.rewind()
            val rad = Math.toRadians(trend.deg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val shaft = 0.84f * box
            val heads = trend.heads.coerceAtLeast(1)
            val spread = if (heads > 1) 0.30f * box else 0f
            val headLen = if (heads > 1) 0.26f * box else 0.36f * box
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

        private fun drawSpark(canvas: Canvas, m: Model, s: Spec, lowBit: Boolean) {
            val w = SPARK_W
            val h = SPARK_H
            val x0 = OrbitFaceLogic.CX - w / 2f
            val y1 = s.sparkY + h / 2f
            spark.rewind()
            val p = m.spark
            var k = 0
            while (k < p.size) {
                val x = x0 + p[k] * w
                val y = y1 - p[k + 1] * h
                if (k == 0) spark.moveTo(x, y) else spark.lineTo(x, y)
                k += 2
            }
            val colour = hue(m.hue, lowBit)
            setStroke(3f, colour, !lowBit)
            canvas.drawPath(spark, stroke)
            stroke.style = Paint.Style.FILL
            canvas.drawCircle(x0 + p[p.size - 2] * w, y1 - p[p.size - 1] * h, 5f, stroke)
        }

        private fun drawLine(canvas: Canvas, m: Model, line: Line, y: Float, lowBit: Boolean, burnIn: Boolean, ageOnly: Boolean) {
            val aa = !lowBit
            val maxW = 2f * halfChord(y, 44f)
            when (line) {
                Line.DELTA -> {
                    // "Nur BZ": the age alone reads "vor 8′", so it cannot pass for a number
                    val age = if (ageOnly && m.age.isNotEmpty()) agoFormat.format(m.age) else m.age
                    val d = when {
                        ageOnly && m.age.isNotEmpty() -> ""
                        m.age.isEmpty()               -> m.delta
                        else                          -> "${m.delta} · "
                    }
                    var size = DELTA_SIZE
                    setText(size, if (burnIn) 500 else 600, if (lowBit) Color.WHITE else TIME_D, aa)
                    val wd = text.measureText(d)
                    val wa = text.measureText(age)
                    if (wd + wa > maxW) size *= maxW / (wd + wa)
                    text.textSize = size
                    val k = size / DELTA_SIZE
                    val x = OrbitFaceLogic.CX - (wd + wa) * k / 2f
                    val by = baseline(y)
                    canvas.drawText(d, x, by, text)
                    text.color = if (m.ageAmber) level(Level.WARN, lowBit) else text.color
                    canvas.drawText(age, x + wd * k, by, text)
                }

                Line.WARNING -> drawWarning(canvas, m, y, maxW, lowBit, burnIn)
            }
        }

        /** The TIEF / HOCH badge (a pill: urgent low / very high) and the warning words after it, centred as one group. */
        private fun drawWarning(canvas: Canvas, m: Model, y: Float, maxW: Float, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val word = when (m.badge) {
                "LOW"  -> lowWord
                "HIGH" -> highWord
                else   -> null
            }
            val badgeColour = if (m.badge == "HIGH") level(Level.WARN, lowBit) else level(Level.ALERT, lowBit)
            val warnColour = level(m.warningLevel, lowBit)
            var k = 1f
            setText(BADGE_SIZE, 700, badgeColour, aa)
            text.letterSpacing = 0.06f
            val bw = if (word != null) text.measureText(word) + (if (m.badgeBoxed) 2f * BADGE_PAD else 0f) else 0f
            setText(LINE_SIZE, if (burnIn) 500 else 600, warnColour, aa)
            val ww = m.warning?.let { text.measureText(it) } ?: 0f
            val gap = if (word != null && m.warning != null) LINE_GAP else 0f
            val total = bw + gap + ww
            if (total > maxW) k = max(MIN_K, maxW / total)
            var x = OrbitFaceLogic.CX - total * k / 2f
            if (word != null) {
                setText(BADGE_SIZE * k, 700, badgeColour, aa)
                text.letterSpacing = 0.06f
                val by = baseline(y)
                if (m.badgeBoxed) {
                    // urgent low (red) / very high (amber): a small solid pill with black words (the one fill in AOD,
                    // about 2 % of the dial and only at a danger level): it must out-shout every other state. In burn-in
                    // mode it is a thick outline with coloured words, so no filled area is left
                    val h = BADGE_H * k
                    box.set(x, y - h / 2f, x + bw * k, y + h / 2f)
                    stroke.reset()
                    stroke.isAntiAlias = aa
                    stroke.color = badgeColour
                    if (burnIn) {
                        stroke.style = Paint.Style.STROKE
                        stroke.strokeWidth = BADGE_RING
                        box.inset(BADGE_RING / 2f, BADGE_RING / 2f)
                    } else stroke.style = Paint.Style.FILL
                    canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, stroke)
                    text.color = if (burnIn) badgeColour else Color.BLACK
                    canvas.drawText(word, x + BADGE_PAD * k, by, text)
                } else canvas.drawText(word, x, by, text)
                x += (bw + gap) * k
            }
            m.warning?.let {
                setText(LINE_SIZE * k, if (burnIn) 500 else 600, warnColour, aa)
                canvas.drawText(it, x, baseline(y), text)
            }
        }
    }

    const val ARROW_BOX = 0.46f
    const val ARROW_GAP = 0.06f
    /** Close to the digits' stroke at weight 700 (about 0.14 em), so the arrow is as heavy as the number. */
    const val ARROW_STROKE = 0.13f
    const val ARROW_STROKE_DOUBLE = 0.10f
    const val SPARK_W = 150f
    const val SPARK_H = 34f
    const val DELTA_SIZE = 36f
    const val LINE_SIZE = 34f
    const val BADGE_SIZE = 34f
    const val BADGE_H = 50f
    const val STALE_K = 0.75f
    /** Cap height of the digits in em (Roboto about 0.71). */
    const val CAP_K = 0.71f
    /** The strike's centre as a share of the cap height above the baseline (off the digits' waist). */
    const val STRIKE_Y = 0.64f
    const val STRIKE_K = 0.06f
    const val STRIKE_MIN = 8f
    const val STRIKE_GAP = 2f
    const val STRIKE_OVER = 20f
    const val BADGE_PAD = 14f
    const val BADGE_RING = 4f
    const val LINE_GAP = 14f
    const val MIN_K = 0.72f
}
