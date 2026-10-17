package app.aaps.wear.watchfaces.halo

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
import app.aaps.wear.watchfaces.common.FaceState.Trend
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halo always-on, in the family look approved for Orbit (AOD round 2, owner: "too hard to read with the fine, thin
 * lines, too much information, keep it simpler").
 *
 * Every look is BOLD and sparse: solid digits in a dimmed range colour (no outlines, no hairlines), the trend arrow
 * beside them, and at most ONE line of words under them (TIEF / HOCH as a pill, "Keine Daten · 17′", "Loop 23′",
 * "Loop aus", "Telefon getrennt · 14′", or the amber data age from 6′).
 *
 * Exploration (AOD Halo round 1, renders explore_*): a dim arc of the target band alone ([Look.BAND], [Look.BAND_DOT])
 * sat lopsided in the upper left (70..180 mg/dL is 166..280 deg on Halo's scale) and read as a stray stroke, and its
 * dot fell outside it exactly when it mattered (urgent low): dropped. Halo's identity lives in the "Übersicht" look
 * instead, as its whole ring: one dim, bold 270 deg track (gap at the bottom as on the interactive face, plus a gap at
 * 12 o'clock for the system status icon) and the BG as a solid dot in the range colour ([Look.RING_DELTA]). The default
 * "BZ + Uhrzeit" and "Nur BZ" are the family looks unchanged: nothing but the essentials. The knob, tail, loop pill,
 * IOB and the bolus ball are interactive-only. Burn-in safety comes from the dim colours, the
 * per-minute pixel shift (done by the face) and a low lit share, not from thin strokes. Low-bit: white, no
 * anti-aliasing. Stale data is grey, 75 % size and struck through, and its words say how old it is.
 *
 * All coordinates are on the 450 px design grid; the face scales the canvas.
 */
object HaloAod {

    /** The explored looks. Only [look] decides which ones the setting offers. */
    enum class Look {
        /** Huge BG + arrow, nothing else (the one warning line only when needed). */
        BG_ONLY,
        /** Small time on top, huge BG + arrow (the family default). */
        BG_TIME,
        /** Small time, BG + arrow, delta and data age ("+0,1 · 2′") under it. */
        BG_DELTA,
        /** BG_TIME plus Halo's target band as one dim arc at the bezel (gap at 12 o'clock). */
        BAND,
        /** BAND plus a solid dot on the arc's scale at the BG (Halo's knob, bold, no ring). */
        BAND_DOT,
        /** A big dim time, the BG medium under it. */
        BIG_TIME,
        /** BG_TIME inside Halo's whole ring, drawn as one dim, bold 270 deg track (gap at the bottom) with the BG dot. */
        RING,
        /** BG_DELTA inside the RING: the "Übersicht" look (where the position in the range is wanted context). */
        RING_DELTA
    }

    /**
     * Halo's looks for the shared values (key and values unchanged): "outline" (default) = "BZ + Uhrzeit",
     * "minimal" = "Nur BZ", "glance" = "Übersicht" (BG + time + delta and age).
     */
    fun look(style: AodStyle): Look = when (style) {
        AodStyle.OUTLINE -> Look.BG_TIME
        AodStyle.MINIMAL -> Look.BG_ONLY
        AodStyle.GLANCE  -> Look.RING_DELTA
    }

    enum class Hue { IN, HIGH, LOW, STALE }
    enum class Level { INFO, WARN, ALERT }

    /** What an always-on frame shows; built once per frame (once a minute) from the face's family state. */
    data class Model(
        val hero: String,
        val hue: Hue,
        /** A real but old reading: grey, smaller and struck through. */
        val struck: Boolean,
        val trend: Trend,
        val time: String,
        /** TIEF (urgent low) / HOCH (very high) as a pill; a plain low or high says it by colour + arrow. */
        val badge: Badge?,
        /** The one warning in words (W1-W6), or null. */
        val warning: String?,
        val warningLevel: Level,
        /** Delta as sent ("+0,1", "-0.0", "+2"), shown only by [Look.BG_DELTA]. */
        val delta: String,
        /** Data age ("2′"); [ageAmber] from 6′ (then every look shows it). */
        val age: String,
        val ageAmber: Boolean,
        /** Ring angles (Canvas degrees, [HaloFaceLogic.angle]) of the low and high line, and of the BG (null: no dot). */
        val lowAngle: Float,
        val highAngle: Float,
        val bgAngle: Float?
    )

    enum class Badge { LOW, HIGH }

    fun model(
        hero: String, range: Range, bgAge: BgAge, trend: Trend, time: String, caption: FaceState.Caption,
        warning: FaceState.StatusText?, delta: String, age: String, lowMgdl: Double, highMgdl: Double, sgv: Double
    ): Model {
        val dashes = hero == FaceState.NO_BG
        val old = FaceState.isOld(bgAge)
        val hue = when {
            old || dashes || range == Range.STALE            -> Hue.STALE
            range == Range.LOW || range == Range.URGENT_LOW   -> Hue.LOW
            range == Range.HIGH || range == Range.VERY_HIGH   -> Hue.HIGH
            else                                              -> Hue.IN
        }
        val badge = when {
            dashes || old                            -> null
            caption == FaceState.Caption.LOW_BADGE   -> Badge.LOW
            caption == FaceState.Caption.HIGH_WORD   -> Badge.HIGH
            else                                     -> null
        }
        val w = warning?.takeIf { it.warning.ambient && it.text.isNotEmpty() }
        return Model(
            hero = hero,
            hue = hue,
            struck = FaceState.isStruck(bgAge) && !dashes,
            trend = if (old || dashes) Trend.NONE else trend,
            time = time,
            badge = badge,
            warning = w?.text,
            warningLevel = when (w?.level) {
                FaceState.Level.ALERT -> Level.ALERT
                FaceState.Level.WARN  -> Level.WARN
                else                  -> Level.INFO
            },
            delta = FaceState.deltaText(delta),
            age = age,
            ageAmber = bgAge == BgAge.AGING && !dashes,
            lowAngle = HaloFaceLogic.angle(lowMgdl),
            highAngle = HaloFaceLogic.angle(highMgdl),
            bgAngle = if (old || dashes || sgv <= 0.0) null else HaloFaceLogic.angle(sgv)
        )
    }

    /** Arrow direction (degrees clockwise from 12 o'clock) and number of heads; 0 heads = no arrow. */
    fun arrowOf(t: Trend): Pair<Float, Int> = when (t) {
        Trend.NONE   -> 0f to 0
        Trend.FLAT   -> 90f to 1
        Trend.UP45   -> 45f to 1
        Trend.UP     -> 0f to 1
        Trend.UP2,
        Trend.UP3    -> 0f to 2
        Trend.DOWN45 -> 135f to 1
        Trend.DOWN   -> 180f to 1
        Trend.DOWN2,
        Trend.DOWN3  -> 180f to 2
    }

    // dimmed design-system colours (the Orbit AOD values): bold digits at about 60-70 % of the interactive brightness
    const val MINT = 0xFF8FD8B4.toInt()
    const val AMBER_D = 0xFFD9A048.toInt()
    const val RED_D = 0xFFE0566A.toInt()
    const val STALE_D = 0xFF7A8296.toInt()
    const val TIME_D = 0xFFA3AAB8.toInt()
    const val INFO_D = 0xFF8E95A3.toInt()
    /** The target band arc: a dim green-grey, about a quarter of the digits' brightness. */
    const val BAND_D = 0xFF2E4A3E.toInt()
    /** The whole ring track (RING look): neutral and dimmer than the band. */
    const val RING_D = 0xFF2A2E36.toInt()

    /** Vertical layout of a look on the 450 grid (centre lines). 0 means "not drawn". */
    data class Spec(
        val timeY: Float, val timeSize: Float, val timeWeight: Int,
        val heroY: Float, val heroSize: Float, val heroMaxW: Float,
        val lineY: Float
    )

    fun spec(look: Look): Spec = when (look) {
        Look.BG_ONLY  -> Spec(0f, 0f, 0, heroY = 212f, heroSize = 172f, heroMaxW = 330f, lineY = 334f)
        Look.BG_TIME,
        Look.BAND,
        Look.BAND_DOT,
        Look.RING     -> Spec(98f, 40f, 600, heroY = 220f, heroSize = 156f, heroMaxW = 320f, lineY = 335f)
        Look.BG_DELTA -> Spec(98f, 40f, 600, heroY = 216f, heroSize = 150f, heroMaxW = 320f, lineY = 327f)
        // inside the ring (R 200): the widest group ("22,2⇈") keeps ~20 px to the track
        // and a step smaller than BG_DELTA, so the ring costs no extra lit share (urgent low + pill + words <= ~14 %)
        Look.RING_DELTA -> Spec(98f, 40f, 600, heroY = 216f, heroSize = 144f, heroMaxW = 300f, lineY = 327f)
        Look.BIG_TIME -> Spec(160f, 104f, 500, heroY = 280f, heroSize = 84f, heroMaxW = 250f, lineY = 350f)
    }

    /** Half chord of the dial at [y] (450 grid, radius 225), less [margin] on each side. */
    fun halfChord(y: Float, margin: Float = 40f): Float {
        val dy = y - C
        return max(0f, sqrt(max(0f, 225f * 225f - dy * dy)) - margin)
    }

    /**
     * The band arc's sweeps (start, sweep) in Canvas degrees: the target band, less [BAND_GAP_HALF] around 12 o'clock
     * (the system status icon), so 1 or 2 pieces.
     */
    fun bandSweeps(lowA: Float, highA: Float): List<Pair<Float, Float>> {
        val gapLo = 270f - BAND_GAP_HALF
        val gapHi = 270f + BAND_GAP_HALF
        val out = ArrayList<Pair<Float, Float>>(2)
        if (lowA < gapLo) out += lowA to (min(highA, gapLo) - lowA)
        if (highA > gapHi) out += max(lowA, gapHi) to (highA - max(lowA, gapHi))
        return out.filter { it.second > 0.5f }
    }

    /** Draws a [Model] in a [Look] on the 450 grid; all paints are its own. */
    class Painter {

        private val text = Paint()
        private val stroke = Paint()
        private val fm = Paint.FontMetrics()
        private val arrow = Path()
        private val box = RectF()
        private val faces = HashMap<Int, Typeface>()

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

        fun draw(canvas: Canvas, m: Model, look: Look, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val s = spec(look)
            if (look == Look.BAND || look == Look.BAND_DOT) drawBand(canvas, m, dot = look == Look.BAND_DOT, lowBit = lowBit)
            // low-bit: no ring (a white dotted track read as noise); BG, arrow and words carry everything
            if ((look == Look.RING || look == Look.RING_DELTA) && !lowBit) drawRing(canvas, m)
            if (s.timeY > 0f && m.time.isNotEmpty()) {
                setText(s.timeSize, if (burnIn) max(400, s.timeWeight - 100) else s.timeWeight, if (lowBit) Color.WHITE else TIME_D, aa)
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(m.time, C, baseline(s.timeY), text)
            }
            drawHero(canvas, m, s, lowBit, burnIn)
            val line = when {
                m.badge != null || m.warning != null -> Line.WARNING
                // from 6′ every look warns with the amber age; BG_ONLY ("Nur BZ") shows the age alone
                look == Look.BG_DELTA || look == Look.RING_DELTA || m.ageAmber -> Line.DELTA
                else                                 -> null
            }
            val ring = (look == Look.RING || look == Look.RING_DELTA) && !lowBit
            if (line != null) drawLine(canvas, m, line, s.lineY, lowBit, burnIn, ageOnly = look == Look.BG_ONLY, ring = ring)
        }

        private enum class Line { WARNING, DELTA }

        /**
         * Halo's identity in AOD: the target band as one dim arc at the bezel (no full ring, no track), with a gap at
         * 12 o'clock for the system status icon. Low-bit: a dotted white arc (no greys). [dot]: the BG as a solid dot.
         */
        private fun drawBand(canvas: Canvas, m: Model, dot: Boolean, lowBit: Boolean) {
            val aa = !lowBit
            box.set(C - BAND_R, C - BAND_R, C + BAND_R, C + BAND_R)
            setStroke(BAND_W, if (lowBit) Color.WHITE else BAND_D, aa)
            if (lowBit) {
                // white only: every other pixel run, so the arc stays as quiet as the grey one
                val unit = (Math.PI * BAND_R / 180.0).toFloat()
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(1.5f * unit, 3f * unit), 0f)
                stroke.strokeCap = Paint.Cap.BUTT
            }
            for ((start, sweep) in bandSweeps(m.lowAngle, m.highAngle)) canvas.drawArc(box, start, sweep, false, stroke)
            stroke.pathEffect = null
            val a = m.bgAngle ?: return
            if (!dot) return
            // the dot never sits under the status icon: inside the 12 o'clock gap it is not drawn
            if (kotlin.math.abs(a - 270f) < BAND_GAP_HALF) return
            stroke.style = Paint.Style.FILL
            stroke.color = hue(m.hue, lowBit)
            val r = Math.toRadians(a.toDouble())
            canvas.drawCircle(C + BAND_R * cos(r).toFloat(), C + BAND_R * sin(r).toFloat(), DOT_R, stroke)
        }

        /** The whole ring (135..405 deg) as one dim bold track, less the 12 o'clock gap, and the BG as a solid dot. */
        private fun drawRing(canvas: Canvas, m: Model) {
            box.set(C - BAND_R, C - BAND_R, C + BAND_R, C + BAND_R)
            setStroke(RING_W, RING_D, true)
            for ((start, sweep) in bandSweeps(HaloFaceLogic.RING_START, HaloFaceLogic.RING_START + HaloFaceLogic.RING_SWEEP)) canvas.drawArc(box, start, sweep, false, stroke)
            val a = m.bgAngle ?: return
            // the dot never sits under the status icon: inside the 12 o'clock gap it is not drawn
            if (kotlin.math.abs(a - 270f) < BAND_GAP_HALF) return
            stroke.style = Paint.Style.FILL
            stroke.color = hue(m.hue, false)
            val r = Math.toRadians(a.toDouble())
            canvas.drawCircle(C + BAND_R * cos(r).toFloat(), C + BAND_R * sin(r).toFloat(), RING_DOT_R, stroke)
        }

        private fun drawHero(canvas: Canvas, m: Model, s: Spec, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val colour = hue(m.hue, lowBit)
            setText(s.heroSize, if (burnIn) 600 else 700, colour, aa)
            text.letterSpacing = -0.02f
            val (deg, heads) = arrowOf(m.trend)
            val arrowOn = heads > 0
            // old data: about 75 % size, so the "Keine Daten · 17′" line weighs as much as the number
            var size = if (m.struck) STALE_K * s.heroSize else s.heroSize
            fun groupW(sz: Float): Float {
                val k = sz / s.heroSize
                return text.measureText(m.hero) * k + if (arrowOn) (ARROW_GAP + ARROW_BOX) * sz else 0f
            }
            val maxW = min(s.heroMaxW, 2f * halfChord(s.heroY, 30f)) - if (m.struck) 2f * STRIKE_OVER else 0f
            val w0 = groupW(size)
            if (w0 > maxW) size *= maxW / w0
            text.textSize = size
            val digitsW = text.measureText(m.hero)
            val x0 = C - groupW(size) / 2f
            val by = baseline(s.heroY)
            val cy = by - CAP_K / 2f * size
            canvas.drawText(m.hero, x0, by, text)
            if (m.struck) {
                // a bar cut out of the digits by a thin black gap, above the digits' waist (8 / 0 / 6 / 9 stay apart)
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
                buildArrow(deg, heads, b)
                setStroke(max(5f, (if (heads > 1) ARROW_STROKE_DOUBLE else ARROW_STROKE) * size), colour, aa)
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

        private fun drawLine(canvas: Canvas, m: Model, line: Line, y: Float, lowBit: Boolean, burnIn: Boolean, ageOnly: Boolean, ring: Boolean) {
            val aa = !lowBit
            // inside the ring the words keep ~20 px to its track ("Telefon getrennt · 14′" shrinks a little)
            val dy = y - C
            val ringHalf = if (ring) sqrt(max(0f, (BAND_R - RING_CLEAR) * (BAND_R - RING_CLEAR) - dy * dy)) else Float.MAX_VALUE
            val maxW = 2f * min(halfChord(y, 44f), ringHalf)
            when (line) {
                Line.DELTA   -> {
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
                    val x = C - (wd + wa) * k / 2f
                    val by = baseline(y)
                    canvas.drawText(d, x, by, text)
                    if (m.ageAmber) text.color = level(Level.WARN, lowBit)
                    canvas.drawText(age, x + wd * k, by, text)
                }

                Line.WARNING -> drawWarning(canvas, m, y, maxW, lowBit, burnIn)
            }
        }

        /** The TIEF / HOCH pill (urgent low / very high) and the warning words after it, centred as one group. */
        private fun drawWarning(canvas: Canvas, m: Model, y: Float, maxW: Float, lowBit: Boolean, burnIn: Boolean) {
            val aa = !lowBit
            val word = when (m.badge) {
                Badge.LOW  -> lowWord
                Badge.HIGH -> highWord
                null       -> null
            }
            val badgeColour = if (m.badge == Badge.HIGH) level(Level.WARN, lowBit) else level(Level.ALERT, lowBit)
            val warnColour = level(m.warningLevel, lowBit)
            var k = 1f
            setText(BADGE_SIZE, 700, badgeColour, aa)
            text.letterSpacing = 0.06f
            val bw = if (word != null) text.measureText(word) + 2f * BADGE_PAD else 0f
            setText(LINE_SIZE, if (burnIn) 500 else 600, warnColour, aa)
            val ww = m.warning?.let { text.measureText(it) } ?: 0f
            val gap = if (word != null && m.warning != null) LINE_GAP else 0f
            val total = bw + gap + ww
            if (total > maxW) k = max(MIN_K, maxW / total)
            var x = C - total * k / 2f
            if (word != null) {
                setText(BADGE_SIZE * k, 700, badgeColour, aa)
                text.letterSpacing = 0.06f
                val by = baseline(y)
                // a small solid pill with black words (the one fill in AOD, only at a danger level); burn-in: an outline
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
                x += (bw + gap) * k
            }
            m.warning?.let {
                setText(LINE_SIZE * k, if (burnIn) 500 else 600, warnColour, aa)
                canvas.drawText(it, x, baseline(y), text)
            }
        }
    }

    const val C = 225f
    const val ARROW_BOX = 0.46f
    const val ARROW_GAP = 0.06f
    const val ARROW_STROKE = 0.13f
    const val ARROW_STROKE_DOUBLE = 0.10f
    const val DELTA_SIZE = 36f
    const val LINE_SIZE = 34f
    const val BADGE_SIZE = 34f
    const val BADGE_H = 50f
    const val STALE_K = 0.75f
    const val CAP_K = 0.71f
    const val STRIKE_Y = 0.64f
    const val STRIKE_K = 0.06f
    const val STRIKE_MIN = 8f
    const val STRIKE_GAP = 2f
    const val STRIKE_OVER = 20f
    const val BADGE_PAD = 14f
    const val BADGE_RING = 4f
    const val LINE_GAP = 14f
    const val MIN_K = 0.72f

    /** Band arc: radius, width (bold enough to read, dim enough to stay quiet) and the 12 o'clock gap (half, degrees). */
    const val BAND_R = 197f
    const val BAND_W = 6f
    const val BAND_GAP_HALF = 14f
    const val DOT_R = 7f
    const val RING_W = 5f
    const val RING_CLEAR = 24f
    const val RING_DOT_R = 8f
}
