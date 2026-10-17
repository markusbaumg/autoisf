package app.aaps.wear.watchfaces.glass

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
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Tone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glass always-on (Glass AOD round 1, owner: "too hard to read with the fine, thin lines, too much information").
 *
 * The family look approved on Orbit (OrbitAod, panel round 3): BOLD solid digits in a dimmed range colour (no outlines,
 * no hairlines, no card), the trend arrow beside them, an optional small time above, and at most ONE line of words
 * under them (the TIEF / HOCH pill, "Keine Daten · 17′", "Loop 23′", "Loop aus", "Telefon getrennt · 14′", or the amber
 * data age from 6′). A stale reading is grey, at 75 % size, with a bar cut out of its digits.
 *
 * Glass keeps a subtle identity: its tight decimal separator and small sentinel marks ("<2,2", "22,3+"), its calmer
 * pale-mint in-range tint, and the time with the date at the place of the interactive top block ([Look.TIME_DATE]),
 * so the face does not jump when the wrist turns.
 *
 * Nothing here touches the bolus ball: it is never drawn in ambient.
 */
object GlassAod {

    /** The explored looks (Glass AOD round 1). Only [look] decides which ones the setting offers. */
    enum class Look {
        /** Huge BG + arrow, nothing else (the one warning line only when needed). */
        BG_ONLY,
        /** Small time on top, huge BG + arrow (Orbit's default). */
        BG_TIME,
        /** Time and date where the interactive face has them, huge BG + arrow under them (Glass's top block kept). */
        TIME_DATE,
        /** Small time, BG + the trend arrow in a solid disc (the interactive trend chip, filled). */
        CHIP,
        /** Small time, BG + arrow and a short, thick 1 h trace (the chart's gist). */
        BG_SPARK,
        /** A big dim time, the BG medium under it. */
        BIG_TIME,
        /** Time and date, BG + arrow, and the delta with the data age ("+0,1 · 2′") under it. */
        BG_DELTA
    }

    /**
     * Glass's looks for the shared values (key and values unchanged): "outline" (the default, "BZ + Uhrzeit") is the
     * time and date above the BG, "minimal" ("Nur BZ") the BG alone, "glance" ("Übersicht") adds the delta and age.
     */
    fun look(style: AodStyle): Look = when (style) {
        AodStyle.OUTLINE -> Look.TIME_DATE
        AodStyle.MINIMAL -> Look.BG_ONLY
        AodStyle.GLANCE  -> Look.BG_DELTA
    }

    enum class Hue { IN, HIGH, LOW, STALE }
    enum class Level { INFO, WARN, ALERT }

    /** What an always-on frame shows; built once per minute (or data change) by the face, drawn by [Painter]. */
    data class Model(
        val hero: String,
        val hue: Hue,
        /** A real but old reading: grey, smaller, a bar cut out of the digits. */
        val struck: Boolean,
        val trend: Trend,
        val time: String,
        val date: String,
        /** "LOW" (urgent low) / "HIGH" (very high) as a solid pill; a plain low or high says it by colour + arrow. */
        val badge: String?,
        /** The one warning in words (W1-W6), or null. */
        val warning: String?,
        val warningLevel: Level,
        /** Delta as shown ("+0,1"), only in [Look.BG_DELTA]. */
        val delta: String,
        /** Data age ("2′"); [ageAmber] from 6′ (then every look shows it). */
        val age: String,
        val ageAmber: Boolean,
        /** The last hour as x/y pairs in 0..1 (y up), or empty; only for fresh data ([Look.BG_SPARK]). */
        val spark: FloatArray = FloatArray(0)
    ) {

        /** A line under the BG: the badge and/or the warning, or the (amber) age. */
        val lineShown: Boolean get() = badge != null || warning != null || ageAmber

        // field by field: the generated equals would compare the FloatArray by identity
        override fun equals(other: Any?): Boolean = other is Model && hero == other.hero && hue == other.hue &&
            struck == other.struck && trend == other.trend && time == other.time && date == other.date && badge == other.badge &&
            warning == other.warning && warningLevel == other.warningLevel && delta == other.delta && age == other.age &&
            ageAmber == other.ageAmber && spark.contentEquals(other.spark)

        override fun hashCode(): Int =
            listOf(hero, hue, struck, trend, time, date, badge, warning, warningLevel, delta, age, ageAmber).hashCode() * 31 + spark.contentHashCode()
    }

    /**
     * [owner] is the slot owner and [ownerText] its words as the footer / meta row says them; only W1-W6 reach the
     * always-on line (a reservoir or phone battery warning waits for the interactive face, as on Orbit).
     */
    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, time: String, date: String, caption: FaceState.Caption,
        owner: FaceState.Warning?, ownerText: String?, ownerTone: Tone?, delta: String, age: String, ageLevel: FaceState.Level,
        spark: FloatArray = FloatArray(0)
    ): Model {
        val dashes = hero == "---"
        val old = GlassFaceLogic.isStale(bgAge)
        val hue = when {
            old || dashes                                    -> Hue.STALE
            range == Range.LOW || range == Range.URGENT_LOW  -> Hue.LOW
            range == Range.HIGH || range == Range.VERY_HIGH  -> Hue.HIGH
            range == Range.STALE                             -> Hue.STALE
            else                                             -> Hue.IN
        }
        val badge = when {
            dashes || old                          -> null
            // only the two danger levels get words (the family rule): a plain low stays wordless like a plain high
            caption == FaceState.Caption.LOW_BADGE -> "LOW"
            caption == FaceState.Caption.HIGH_WORD -> "HIGH"
            else                                   -> null
        }
        val warning = ownerText?.takeIf { owner != null && owner.ambient && it.isNotBlank() }
        return Model(
            hero = hero,
            hue = hue,
            struck = GlassFaceLogic.strikeThrough(bgAge) && !dashes,
            trend = if (old || dashes) Trend.NONE else trend,
            time = time,
            date = date,
            badge = badge,
            warning = warning,
            warningLevel = when (ownerTone) {
                Tone.ALERT -> Level.ALERT
                Tone.WARN  -> Level.WARN
                else       -> Level.INFO
            },
            delta = delta,
            age = if (old || dashes) "" else age,
            ageAmber = ageLevel == FaceState.Level.WARN && !old && !dashes,
            spark = if (old || dashes) FloatArray(0) else spark
        )
    }

    /**
     * The last [windowMs] of readings (timestamp, mg/dL), as x/y pairs in 0..1 (y up), oldest first. The y range is at
     * least [MIN_SPAN_MGDL] tall, so a calm hour stays a calm line.
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

    // Dimmed design-system colours: the family's AOD tokens, the in-range tint a step paler (Glass's calmer mint)
    const val MINT = 0xFFA3DCC2.toInt()
    const val AMBER_D = 0xFFD9A048.toInt()
    const val RED_D = 0xFFE0566A.toInt()
    const val STALE_D = 0xFF7A8296.toInt()
    const val TIME_D = 0xFFA3AAB8.toInt()
    const val DATE_D = 0xFF7D8494.toInt()
    const val INFO_D = 0xFF8E95A3.toInt()

    /** Vertical layout of a look on the 450 grid (centre lines). 0 means "not drawn". */
    data class Spec(
        val timeY: Float, val timeSize: Float, val timeWeight: Int,
        val heroY: Float, val heroSize: Float, val heroMaxW: Float,
        val lineY: Float, val dateY: Float = 0f, val sparkY: Float = 0f
    )

    /**
     * Every look keeps the top centre (Samsung's status / charging icon, about y 15..55) and the bottom centre (the media
     * chip, "SONOS", from about y 405) free: the lowest line's glyphs end above y 370.
     */
    fun spec(look: Look): Spec = when (look) {
        Look.BG_ONLY   -> Spec(0f, 0f, 0, heroY = 212f, heroSize = 172f, heroMaxW = 330f, lineY = 334f)
        Look.BG_TIME   -> Spec(98f, 40f, 600, heroY = 220f, heroSize = 156f, heroMaxW = 320f, lineY = 335f)
        Look.TIME_DATE -> Spec(86f, 40f, 600, heroY = 226f, heroSize = 150f, heroMaxW = 320f, lineY = 338f, dateY = 120f)
        Look.CHIP      -> Spec(98f, 40f, 600, heroY = 220f, heroSize = 150f, heroMaxW = 330f, lineY = 335f)
        Look.BG_SPARK  -> Spec(98f, 40f, 600, heroY = 206f, heroSize = 140f, heroMaxW = 310f, lineY = 352f, sparkY = 302f)
        Look.BIG_TIME  -> Spec(160f, 104f, 500, heroY = 282f, heroSize = 84f, heroMaxW = 250f, lineY = 352f)
        Look.BG_DELTA  -> Spec(86f, 40f, 600, heroY = 220f, heroSize = 146f, heroMaxW = 320f, lineY = 330f, dateY = 120f)
    }

    /** Half chord of the dial at [y] (450 grid, radius 225), less [margin] on each side. */
    fun halfChord(y: Float, margin: Float = 40f): Float {
        val dy = y - 225f
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

        /** Lit area of the last frame's hero group (design px), for the harness. */
        val heroBox = RectF()

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
            // Paint.reset() does not clear letterSpacing on every platform/runtime: set it explicitly, so the badge's
            // 0.06 tracking never leaks into the next frame's time, date or hero (panel round 2)
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

        private fun centred(canvas: Canvas, s: String, y: Float) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(s, 225f, baseline(y), text)
            text.textAlign = Paint.Align.LEFT
        }

        fun draw(canvas: Canvas, m: Model, look: Look, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val s = spec(look)
            if (s.timeY > 0f && m.time.isNotEmpty()) {
                setText(s.timeSize, if (burnIn) max(400, s.timeWeight - 100) else s.timeWeight, if (lowBit) Color.WHITE else TIME_D, aa)
                centred(canvas, m.time, s.timeY)
            }
            if (s.dateY > 0f && m.date.isNotEmpty()) {
                setText(DATE_SIZE, if (burnIn) 400 else 500, if (lowBit) Color.WHITE else DATE_D, aa)
                centred(canvas, m.date, s.dateY)
            }
            drawHero(canvas, m, s, look == Look.CHIP, lowBit, burnIn)
            if (s.sparkY > 0f && m.spark.size >= 4 && !m.lineShown) drawSpark(canvas, m, s, lowBit)
            when {
                m.badge != null || m.warning != null -> drawWarning(canvas, m, s.lineY, lowBit, burnIn)
                look == Look.BG_DELTA || m.ageAmber  -> drawDelta(canvas, m, s.lineY, lowBit, burnIn, ageOnly = look != Look.BG_DELTA)
            }
        }

        // ---------------------------------------------------------------- digits (Glass's tight separator, small marks)

        /** Width of [num] with the tight decimal separator ([GlassFaceLogic.SEPARATOR_KERN]). */
        private fun numberWidth(num: String): Float {
            val p = GlassFaceLogic.splitDecimal(num) ?: return text.measureText(num)
            return text.measureText(p.first) + text.measureText(p.second) + text.measureText(p.third) - 2f * SEPARATOR_KERN * text.textSize
        }

        private fun markWidth(m: String): Float {
            if (m.isEmpty()) return 0f
            val size = text.textSize
            text.textSize = size * GlassFaceLogic.SENTINEL_MARK_SCALE
            val w = text.measureText(m)
            text.textSize = size
            return w + GlassFaceLogic.SENTINEL_MARK_GAP
        }

        private fun heroWidth(hero: String): Float {
            val (pre, num, suf) = GlassFaceLogic.splitSentinel(hero)
            return markWidth(pre) + numberWidth(num) + markWidth(suf)
        }

        /** [hero] from [x0] on [baseline]; returns the end of the number (without a trailing mark). */
        private fun drawDigits(canvas: Canvas, hero: String, x0: Float, baseline: Float, capCentre: Float): Float {
            val (pre, num, suf) = GlassFaceLogic.splitSentinel(hero)
            var x = x0
            val size = text.textSize
            fun mark(m: String) {
                text.textSize = size * GlassFaceLogic.SENTINEL_MARK_SCALE
                text.getFontMetrics(fm)
                // the mark centred on the digits' cap (its own x-height centre close enough at this size)
                canvas.drawText(m, x, capCentre + 0.36f * text.textSize, text)
                x += text.measureText(m) + GlassFaceLogic.SENTINEL_MARK_GAP
                text.textSize = size
            }
            if (pre.isNotEmpty()) mark(pre)
            val p = GlassFaceLogic.splitDecimal(num)
            if (p == null) {
                canvas.drawText(num, x, baseline, text)
                x += text.measureText(num)
            } else {
                val kern = SEPARATOR_KERN * size
                canvas.drawText(p.first, x, baseline, text)
                x += text.measureText(p.first) - kern
                canvas.drawText(p.second, x, baseline, text)
                x += text.measureText(p.second) - kern
                canvas.drawText(p.third, x, baseline, text)
                x += text.measureText(p.third)
            }
            val end = x
            if (suf.isNotEmpty()) mark(suf)
            return end
        }

        private fun drawHero(canvas: Canvas, m: Model, s: Spec, chip: Boolean, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val colour = hue(m.hue, lowBit)
            val weight = if (burnIn) 600 else 700
            setText(s.heroSize, weight, colour, aa)
            val arrowOn = m.trend != Trend.NONE
            val arrowBox = if (chip) CHIP_BOX else ARROW_BOX
            val arrowGap = if (chip) CHIP_GAP else ARROW_GAP
            // old data: 75 % size, so the "Keine Daten · 17′" line weighs as much as the number
            var size = if (m.struck) STALE_K * s.heroSize else s.heroSize
            fun groupW(sz: Float): Float {
                text.textSize = sz
                return heroWidth(m.hero) + if (arrowOn) (arrowGap + arrowBox) * sz else 0f
            }
            val maxW = min(s.heroMaxW, 2f * halfChord(s.heroY, 30f)) - if (m.struck) 2f * STRIKE_OVER else 0f
            val w0 = groupW(size)
            if (w0 > maxW) size *= maxW / w0
            val gw = groupW(size)
            text.textSize = size
            val x0 = 225f - gw / 2f
            val by = baseline(s.heroY)
            // the digits' optical centre: half the cap height (about 0.71 em) above the baseline
            val cy = by - CAP_K / 2f * size
            val numEnd = drawDigits(canvas, m.hero, x0, by, cy)
            val digitsEnd = x0 + heroWidth(m.hero)
            heroBox.set(x0, by - 0.72f * size, x0 + gw, by)
            if (m.struck) {
                // a bar cut out of the digits by a thin black gap, above their waist (where 8 / 0 / 6 / 9 differ), so the
                // last value stays readable (the family's stale strike)
                val bar = max(STRIKE_MIN, STRIKE_K * size)
                val sy = by - STRIKE_Y * CAP_K * size
                setStroke(bar + 2f * STRIKE_GAP, Color.BLACK, aa)
                stroke.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(x0 - STRIKE_OVER, sy, numEnd + STRIKE_OVER, sy, stroke)
                setStroke(bar, colour, aa)
                stroke.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(x0 - STRIKE_OVER, sy, numEnd + STRIKE_OVER, sy, stroke)
            }
            if (arrowOn) {
                val bx = arrowBox * size
                val ax = digitsEnd + arrowGap * size + bx / 2f
                if (chip) {
                    // the interactive trend chip, filled: a solid disc in the range colour with the arrow cut out in black
                    // (burn-in: a thick ring with the arrow in colour, no filled area)
                    val r = bx / 2f
                    stroke.reset(); stroke.isAntiAlias = aa; stroke.color = colour
                    if (burnIn) {
                        stroke.style = Paint.Style.STROKE; stroke.strokeWidth = CHIP_RING
                        canvas.drawCircle(ax, cy, r - CHIP_RING / 2f, stroke)
                    } else {
                        stroke.style = Paint.Style.FILL
                        canvas.drawCircle(ax, cy, r, stroke)
                    }
                    buildArrow(m.trend, CHIP_ARROW_K * bx)
                    val k = if (m.trend.double) ARROW_STROKE_DOUBLE else ARROW_STROKE
                    setStroke(max(4f, k * size * 0.8f), if (burnIn) colour else Color.BLACK, aa)
                } else {
                    buildArrow(m.trend, bx)
                    // a double arrow's two shafts need air between them, so it draws a little lighter
                    val k = if (m.trend.double) ARROW_STROKE_DOUBLE else ARROW_STROKE
                    setStroke(max(5f, k * size), colour, aa)
                }
                canvas.save()
                canvas.translate(ax, cy)
                canvas.drawPath(arrow, stroke)
                canvas.restore()
            }
        }

        /** Arrow pointing along the trend's angle (0 = right, 90 = up), centred on the origin, [box] long. */
        private fun buildArrow(trend: Trend, box: Float) {
            arrow.rewind()
            val rad = Math.toRadians(trend.angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = -sin(rad).toFloat()
            val shaft = 0.84f * box
            val heads = if (trend.double) 2 else 1
            val spread = if (heads > 1) 0.30f * box else 0f
            val headLen = if (heads > 1) 0.26f * box else 0.36f * box
            val dir = Math.atan2(dy.toDouble(), dx.toDouble())
            for (k in 0 until heads) {
                val off = if (heads > 1) (k - (heads - 1) / 2f) * 2f * spread else 0f
                val ox = -dy * off
                val oy = dx * off
                val tipX = ox + dx * shaft / 2f
                val tipY = oy + dy * shaft / 2f
                arrow.moveTo(ox - dx * shaft / 2f, oy - dy * shaft / 2f)
                arrow.lineTo(tipX, tipY)
                for (sign in intArrayOf(-1, 1)) {
                    val a = dir + Math.PI + sign * Math.toRadians(40.0)
                    arrow.moveTo(tipX, tipY)
                    arrow.lineTo(tipX + cos(a).toFloat() * headLen, tipY + sin(a).toFloat() * headLen)
                }
            }
        }

        private fun drawSpark(canvas: Canvas, m: Model, s: Spec, lowBit: Boolean) {
            val w = SPARK_W
            val h = SPARK_H
            val x0 = 225f - w / 2f
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
            setStroke(SPARK_STROKE, colour, !lowBit)
            canvas.drawPath(spark, stroke)
            stroke.style = Paint.Style.FILL
            canvas.drawCircle(x0 + p[p.size - 2] * w, y1 - p[p.size - 1] * h, 6f, stroke)
        }

        /** "+0,1 · 2′" (glance), or the amber age alone ("vor 8′") in the other looks. */
        private fun drawDelta(canvas: Canvas, m: Model, y: Float, lowBit: Boolean, burnIn: Boolean, ageOnly: Boolean) {
            val aa = !lowBit
            val maxW = 2f * halfChord(y, 44f)
            val age = if (ageOnly && m.age.isNotEmpty()) agoFormat.format(m.age) else m.age
            val d = when {
                ageOnly          -> ""
                m.age.isEmpty()  -> m.delta
                else             -> "${m.delta}$SEP"
            }
            if (d.isEmpty() && age.isEmpty()) return
            setText(DELTA_SIZE, if (burnIn) 500 else 600, if (lowBit) Color.WHITE else TIME_D, aa)
            val wd = text.measureText(d)
            val wa = text.measureText(age)
            val k = if (wd + wa > maxW) maxW / (wd + wa) else 1f
            text.textSize = DELTA_SIZE * k
            val x = 225f - (wd + wa) * k / 2f
            val by = baseline(y)
            canvas.drawText(d, x, by, text)
            if (m.ageAmber) text.color = level(Level.WARN, lowBit)
            canvas.drawText(age, x + wd * k, by, text)
        }

        /** The TIEF / HOCH badge (a pill: urgent low / very high) and the warning words after it, centred as one group. */
        private fun drawWarning(canvas: Canvas, m: Model, y: Float, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val maxW = 2f * halfChord(y, 44f)
            val word = when (m.badge) {
                "LOW"  -> lowWord
                "HIGH" -> highWord
                else   -> null
            }
            val badgeColour = if (m.badge == "HIGH") level(Level.WARN, lowBit) else level(Level.ALERT, lowBit)
            val warnColour = level(m.warningLevel, lowBit)
            setText(BADGE_SIZE, 700, badgeColour, aa)
            text.letterSpacing = 0.06f
            val bw = if (word != null) text.measureText(word) + 2f * BADGE_PAD else 0f
            setText(LINE_SIZE, if (burnIn) 500 else 600, warnColour, aa)
            val gap = if (word != null && m.warning != null) LINE_GAP else 0f
            // a long warning is shrunk to [MIN_K], then cut before its " · <age>" tail (the age always stays)
            val room = max(0f, maxW - bw - gap)
            val ww0 = m.warning?.let { text.measureText(it) } ?: 0f
            val k = if (bw + gap + ww0 > maxW) max(MIN_K, maxW / (bw + gap + ww0)) else 1f
            val warning = m.warning?.let { w ->
                if (ww0 * k <= room + 0.5f) w else GlassFaceLogic.ellipsizeKeepTail(w, room / k) { text.measureText(it) }
            }
            val ww = warning?.let { text.measureText(it) } ?: 0f
            val total = bw + gap + ww
            var x = 225f - total * k / 2f
            if (word != null) {
                setText(BADGE_SIZE * k, 700, badgeColour, aa)
                text.letterSpacing = 0.06f
                val by = baseline(y)
                // urgent low (red) / very high (amber): a small solid pill with black words, the one fill in AOD and only
                // at a danger level; with burn-in protection a thick outline with coloured words (no filled area)
                val h = BADGE_H * k
                box.set(x, y - h / 2f, x + bw * k, y + h / 2f)
                stroke.reset(); stroke.isAntiAlias = aa; stroke.color = badgeColour
                if (burnIn) {
                    stroke.style = Paint.Style.STROKE
                    stroke.strokeWidth = BADGE_RING
                    box.inset(BADGE_RING / 2f, BADGE_RING / 2f)
                } else stroke.style = Paint.Style.FILL
                canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, stroke)
                text.color = if (burnIn) badgeColour else Color.BLACK
                canvas.drawText(word, x + BADGE_PAD * k, by, text)
                x += (bw + gap) * k
            }
            warning?.let {
                setText(LINE_SIZE * k, if (burnIn) 500 else 600, warnColour, aa)
                canvas.drawText(it, x, baseline(y), text)
            }
        }
    }

    const val SEP = " · "
    /**
     * The decimal separator tightened a little less than the interactive digits' ([GlassFaceLogic.SEPARATOR_KERN]): at
     * weight 700 the full kern let the comma's tail touch the next digit.
     */
    const val SEPARATOR_KERN = 0.035f
    const val ARROW_BOX = 0.46f
    const val ARROW_GAP = 0.06f
    /** Close to the digits' stroke at weight 700 (about 0.14 em), so the arrow is as heavy as the number. */
    const val ARROW_STROKE = 0.13f
    const val ARROW_STROKE_DOUBLE = 0.10f
    const val CHIP_BOX = 0.50f
    const val CHIP_GAP = 0.08f
    const val CHIP_ARROW_K = 0.66f
    const val CHIP_RING = 5f
    const val SPARK_W = 170f
    const val SPARK_H = 36f
    const val SPARK_STROKE = 4f
    const val DATE_SIZE = 26f
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
