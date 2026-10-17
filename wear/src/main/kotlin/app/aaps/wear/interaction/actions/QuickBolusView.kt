package app.aaps.wear.interaction.actions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.interaction.actions.QuickBolusPicker.Phase
import app.aaps.wear.interaction.actions.QuickBolusPicker.Why
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The quick-bolus picker screen, drawn on a 450 px design grid scaled to the view (round Watch5 Pro, 14 px bezel
 * margin). Draws a [QuickBolusPicker.Screen] plus a [Header]; taps go to [onAmount] / [onClose], nothing is decided
 * here. Orbit's look: flat indigo amount discs with a white ring, 1,5 with a second outer ring (not amber), an amount
 * above the max bolus dark, dashed and struck, the BG line on top (stale: grey and struck through).
 */
class QuickBolusView(context: Context) : View(context) {

    /** The top line: BG, arrow, IOB and the BG age, from the received data. */
    data class Header(
        val bgText: String,
        val range: Range,
        val trend: OrbitFaceLogic.Trend,
        val stale: Boolean,
        val iobText: String,
        val ageText: String,
        val ageWarn: Boolean
    )

    /** Strings, read once from the resources (watch locale). */
    data class Labels(
        val title: String, val iobFormat: String, val ageFormat: String, val hint: String, val hintSub: String,
        val maxFormat: String, val maxUnknown: String, val requestedFormat: String, val phoneChecks: String,
        val noMore: String, val noAnswer: String, val noAnswerSub: String, val low: String, val falling: String,
        val overMaxFormat: String, val noPhone: String, val checking: String, val stale: String, val maxNotSynced: String,
        val pending: String, val noWearControl: String, val off: String
    ) {

        companion object {

            fun from(c: Context) = Labels(
                title = c.getString(R.string.quick_bolus_title),
                iobFormat = c.getString(R.string.quick_bolus_iob),
                ageFormat = c.getString(R.string.quick_bolus_age),
                hint = c.getString(R.string.quick_bolus_hint),
                hintSub = c.getString(R.string.quick_bolus_hint_sub),
                maxFormat = c.getString(R.string.quick_bolus_max),
                maxUnknown = c.getString(R.string.quick_bolus_max_unknown),
                requestedFormat = c.getString(R.string.quick_bolus_requested),
                phoneChecks = c.getString(R.string.quick_bolus_phone_checks),
                noMore = c.getString(R.string.quick_bolus_no_more),
                noAnswer = c.getString(R.string.quick_bolus_no_answer),
                noAnswerSub = c.getString(R.string.quick_bolus_no_answer_sub),
                low = c.getString(R.string.quick_bolus_low),
                falling = c.getString(R.string.quick_bolus_falling),
                overMaxFormat = c.getString(R.string.quick_bolus_over_max),
                noPhone = c.getString(R.string.quick_bolus_why_no_phone),
                checking = c.getString(R.string.quick_bolus_why_checking),
                stale = c.getString(R.string.quick_bolus_why_stale),
                maxNotSynced = c.getString(R.string.quick_bolus_why_max_unknown),
                pending = c.getString(R.string.quick_bolus_why_pending),
                noWearControl = c.getString(R.string.quick_bolus_why_no_wear_control),
                off = c.getString(R.string.quick_bolus_why_off)
            )
        }
    }

    var screen: QuickBolusPicker.Screen? = null
        set(value) {
            field = value
            invalidate()
        }
    var header: Header? = null
        set(value) {
            field = value
            invalidate()
        }
    var labels: Labels = Labels.from(context)
    var locale: Locale = Locale.getDefault()

    /** Spinner angle of the waiting ring (degrees); the activity advances it, renders use a fixed value. */
    var spinnerDeg = -90f

    var onAmount: ((Double) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onAnyTouch: (() -> Unit)? = null
    /** The picker's input lock (a DOWN while it runs never becomes a tap, even if it lifts later). */
    var isInputLocked: () -> Boolean = { false }

    private val touch = QuickBolusTouch()
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val rect = RectF()
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(7f, 6f), 0f)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // ---------------------------------------------------------------- layout (450 design px)

    /** Centre x of each amount button for [n] buttons. */
    fun buttonX(i: Int, n: Int): Float = if (n >= 3) CX + (i - 1) * STEP_3 else CX + (if (i == 0) -STEP_2 / 2f else STEP_2 / 2f)

    // ---------------------------------------------------------------- drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutGrid()
    }

    /** The 450 px design grid, centred in the view (touch uses it too, also before the first draw). */
    private fun layoutGrid() {
        val w = width.toFloat()
        val h = height.toFloat()
        val side = min(w, h)
        if (side <= 0f) return
        scale = side / 450f
        offX = (w - side) / 2f
        offY = (h - side) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        layoutGrid()
        canvas.drawColor(Color.BLACK)
        val s = screen ?: return
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        drawTitle(canvas)
        header?.let { drawHeader(canvas, it) }
        setStroke(1.5f, DIVIDER)
        canvas.drawLine(CX - 92f, DIVIDER_Y, CX + 92f, DIVIDER_Y, stroke)
        drawButtons(canvas, s)
        drawBottom(canvas, s)
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas) {
        drawText(canvas, labels.title, CX, TITLE_Y, 20f, tf700, TXT2, tracking = 0.08f)
    }

    private fun drawHeader(canvas: Canvas, hd: Header) {
        val bgColour = if (hd.stale) STALE_C else when (hd.range) {
            Range.IN                    -> GREEN
            Range.HIGH, Range.VERY_HIGH -> AMBER
            Range.LOW, Range.URGENT_LOW -> RED
            Range.STALE                 -> STALE_C
        }
        setText(BG_SIZE, tf700)
        val bgW = text.measureText(hd.bgText)
        val arrow = !hd.stale && hd.trend != OrbitFaceLogic.Trend.NONE
        val arrowW = if (arrow) ARROW_BOX else 0f
        setText(IOB_SIZE, tf500)
        val iobW = text.measureText(hd.iobText)
        setText(AGE_SIZE, tf500)
        val ageW = text.measureText(hd.ageText)
        val rightW = max(iobW, ageW)
        val gap = 14f
        val total = bgW + gap + (if (arrow) arrowW + gap else 0f) + rightW
        var x = CX - total / 2f
        // BG
        drawText(canvas, hd.bgText, x + bgW / 2f, HEADER_Y, BG_SIZE, tf700, bgColour)
        if (hd.stale) {
            // struck through at the digits' waist, as the faces' stale value
            setStroke(3.5f, STALE_C)
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x - 3f, HEADER_Y, x + bgW + 3f, HEADER_Y, stroke)
        }
        x += bgW + gap
        if (arrow) {
            drawArrow(canvas, x + arrowW / 2f, HEADER_Y, hd.trend, bgColour)
            x += arrowW + gap
        }
        drawText(canvas, hd.iobText, x + iobW / 2f, HEADER_Y - 12f, IOB_SIZE, tf500, IOB_C, align = Paint.Align.CENTER, leftX = x)
        drawText(canvas, hd.ageText, x + ageW / 2f, HEADER_Y + 15f, AGE_SIZE, tf500, if (hd.ageWarn) AMBER else TXT2, leftX = x)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, trend: OrbitFaceLogic.Trend, colour: Int) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(trend.deg)
        setStroke(4f, colour)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val l = 13f
        canvas.drawLine(0f, l, 0f, -l, stroke)
        for (k in 0 until trend.heads) {
            val y = -l + k * 9f
            path.rewind()
            path.moveTo(-8f, y + 8f)
            path.lineTo(0f, y)
            path.lineTo(8f, y + 8f)
            canvas.drawPath(path, stroke)
        }
        canvas.restore()
    }

    private fun drawButtons(canvas: Canvas, s: QuickBolusPicker.Screen) {
        val n = s.buttons.size
        val waiting = s.phase == Phase.WAITING || s.phase == Phase.NO_ANSWER
        s.buttons.forEachIndexed { i, b ->
            val x = buttonX(i, n)
            val chosen = waiting && s.sentAmount != null && abs(b.amount - s.sentAmount) < 1e-6
            val label = QuickBolusPicker.amountText(b.amount, locale)
            val is15 = abs(b.amount - 1.5) < 1e-6
            when {
                chosen && s.phase == Phase.NO_ANSWER -> drawFailed(canvas, x, label)
                chosen                       -> drawChosen(canvas, x, label, spinning = true)
                waiting                      -> drawDim(canvas, x, label)
                b.enabled                    -> drawActive(canvas, x, label, is15, if (s.inputLocked) LOCKED_ALPHA else 255)
                else                         -> drawOff(canvas, x, label, is15, strike = b.overMax)
            }
        }
    }

    private fun drawActive(canvas: Canvas, x: Float, label: String, is15: Boolean, alpha: Int) {
        val r = BUTTON_R
        fill.reset()
        fill.isAntiAlias = true
        fill.shader = RadialGradient(x - r * 0.3f, BUTTON_Y - r * 0.35f, r * 1.4f, FILL_LIT, BOLUS_FILL, Shader.TileMode.CLAMP)
        fill.alpha = alpha
        canvas.drawCircle(x, BUTTON_Y, r, fill)
        fill.shader = null
        setStroke(2.5f, withAlpha(Color.WHITE, IDLE_RING_ALPHA * alpha / 255))
        canvas.drawCircle(x, BUTTON_Y, r - 1.25f, stroke)
        if (is15) {
            // 1,5: a second, outer ring (shape, not colour: never amber), thin and grey, quieter than the buttons'
            // own ring so the largest dose never looks selected or focused
            setStroke(1.5f, withAlpha(OUTER_15, OUTER_15_ALPHA * alpha / 255))
            canvas.drawCircle(x, BUTTON_Y, r + OUTER_15_GAP, stroke)
        }
        drawAmount(canvas, x, label, withAlpha(Color.WHITE, alpha), withAlpha(BOLUS_TEXT, alpha))
    }

    private fun drawOff(canvas: Canvas, x: Float, label: String, is15: Boolean, strike: Boolean) {
        val r = BUTTON_R
        setFill(DISABLED_FILL)
        canvas.drawCircle(x, BUTTON_Y, r, fill)
        setStroke(2f, withAlpha(INDIGO, 140))
        stroke.pathEffect = dash
        canvas.drawCircle(x, BUTTON_Y, r - 1f, stroke)
        stroke.pathEffect = null
        if (is15) {
            setStroke(1.5f, withAlpha(OUTER_15, OUTER_15_ALPHA / 2))
            canvas.drawCircle(x, BUTTON_Y, r + OUTER_15_GAP, stroke)
        }
        if (strike) {
            setStroke(3f, TXT3)
            stroke.strokeCap = Paint.Cap.ROUND
            val k = r * 0.55f
            canvas.drawLine(x - k, BUTTON_Y + k, x + k, BUTTON_Y - k, stroke)
        }
        drawAmount(canvas, x, label, TXT3, TXT3)
    }

    private fun drawDim(canvas: Canvas, x: Float, label: String) {
        setFill(DIM_FILL)
        canvas.drawCircle(x, BUTTON_Y, BUTTON_R, fill)
        setStroke(1.5f, withAlpha(INDIGO, 70))
        canvas.drawCircle(x, BUTTON_Y, BUTTON_R - 1f, stroke)
        drawAmount(canvas, x, label, DIM_TEXT, DIM_TEXT)
    }

    private fun drawChosen(canvas: Canvas, x: Float, label: String, spinning: Boolean) {
        val r = BUTTON_R
        setFill(CHOSEN_FILL)
        canvas.drawCircle(x, BUTTON_Y, r, fill)
        setStroke(2.5f, Color.WHITE)
        canvas.drawCircle(x, BUTTON_Y, r - 1.25f, stroke)
        val rr = r + SPIN_GAP
        rect.set(x - rr, BUTTON_Y - rr, x + rr, BUTTON_Y + rr)
        setStroke(4f, withAlpha(Color.WHITE, 40))
        canvas.drawCircle(x, BUTTON_Y, rr, stroke)
        if (spinning) {
            setStroke(4f, GREEN)
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rect, spinnerDeg, 110f, false, stroke)
        }
        drawAmount(canvas, x, label, Color.WHITE, Color.WHITE)
    }

    /** NO_ANSWER: the sent amount, dimmed (not live, not tappable) with an amber outline as the "Keine Antwort" line. */
    private fun drawFailed(canvas: Canvas, x: Float, label: String) {
        val r = BUTTON_R
        setFill(DIM_FILL)
        canvas.drawCircle(x, BUTTON_Y, r, fill)
        setStroke(2f, withAlpha(TXT3, 160))
        canvas.drawCircle(x, BUTTON_Y, r - 1f, stroke)
        setStroke(2.5f, withAlpha(AMBER, 200))
        canvas.drawCircle(x, BUTTON_Y, r + SPIN_GAP, stroke)
        drawAmount(canvas, x, label, TXT3, TXT3)
    }

    private fun drawAmount(canvas: Canvas, x: Float, label: String, colour: Int, unitColour: Int) {
        drawText(canvas, label, x, BUTTON_Y - 8f, AMOUNT_SIZE, tf700, colour)
        drawText(canvas, "U", x, BUTTON_Y + 28f, UNIT_SIZE, tf600, unitColour)
    }

    private fun drawBottom(canvas: Canvas, s: QuickBolusPicker.Screen) {
        when (s.phase) {
            Phase.WAITING   -> {
                val amount = QuickBolusPicker.amountText(s.sentAmount ?: 0.0, locale)
                drawFitted(canvas, String.format(locale, labels.requestedFormat, amount), CX, LINE1_Y, 25f, tf700, Color.WHITE)
                drawFitted(canvas, labels.phoneChecks, CX, LINE2_Y + 2f, 21f, tf500, TXT2)
                drawFitted(canvas, labels.noMore, CX, CLOSE_Y + 4f, 17f, tf500, TXT3)
                return
            }

            Phase.NO_ANSWER -> {
                drawFitted(canvas, labels.noAnswer, CX, LINE1_Y, 24f, tf700, AMBER)
                drawFitted(canvas, labels.noAnswerSub, CX, LINE2_Y + 2f, 19f, tf500, TXT2)
                return
            }

            else            -> Unit
        }
        when (s.why) {
            Why.NONE                     -> {
                drawFitted(canvas, labels.hint, CX, LINE1_Y, 22f, tf500, TXT1)
                val over = s.overMaxAmount
                if (over != null) drawFitted(canvas, String.format(locale, labels.overMaxFormat, QuickBolusPicker.amountText(over, locale)), CX, LINE2_Y, 18f, tf600, AMBER)
                else drawFitted(canvas, labels.hintSub, CX, LINE2_Y, 17f, tf500, TXT3)
            }

            Why.LOW, Why.FALLING_FAST    -> drawAlertPill(canvas, if (s.why == Why.LOW) labels.low else labels.falling)
            else                         -> drawFitted(canvas, whyText(s.why), CX, LINE1_Y, 22f, tf600, TXT1)
        }
        drawCloseButton(canvas)
        val max = if (s.maxBolus.isNaN()) labels.maxUnknown
        else String.format(locale, labels.maxFormat, QuickBolusPicker.maxBolusText(s.maxBolus, locale))
        drawText(canvas, max, CX, MAX_Y, 17f, tf500, TXT3)
    }

    private fun whyText(w: Why): String = when (w) {
        Why.OFF             -> labels.off
        Why.NO_WEAR_CONTROL -> labels.noWearControl
        Why.PENDING         -> labels.pending
        Why.CHECKING_PHONE  -> labels.checking
        Why.NO_PHONE        -> labels.noPhone
        Why.STALE           -> labels.stale
        Why.NO_MAX_BOLUS    -> labels.maxNotSynced
        Why.LOW             -> labels.low
        Why.FALLING_FAST    -> labels.falling
        Why.NONE            -> ""
    }

    /** "TIEF – kein Bolus": red words in a red outlined pill (urgent, by shape and colour). */
    private fun drawAlertPill(canvas: Canvas, word: String) {
        setText(23f, tf700)
        val w = min(text.measureText(word), MAX_LINE_W - 40f) + 40f
        val h = 44f
        rect.set(CX - w / 2f, LINE1_Y + 6f - h / 2f, CX + w / 2f, LINE1_Y + 6f + h / 2f)
        setFill(RED_BODY)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        setStroke(2.5f, RED)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, stroke)
        drawFitted(canvas, word, CX, LINE1_Y + 6f, 23f, tf700, RED_TEXT, maxW = w - 32f)
    }

    private fun drawCloseButton(canvas: Canvas) {
        setStroke(2f, withAlpha(Color.WHITE, 120))
        canvas.drawCircle(CX, CLOSE_Y, CLOSE_R, stroke)
        setStroke(2.5f, TXT1)
        stroke.strokeCap = Paint.Cap.ROUND
        val k = 7.5f
        canvas.drawLine(CX - k, CLOSE_Y - k, CX + k, CLOSE_Y + k, stroke)
        canvas.drawLine(CX - k, CLOSE_Y + k, CX + k, CLOSE_Y - k, stroke)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = screen ?: return true
        val sc = max(scale, 0.01f)
        val x = (event.x - offX) / sc
        val y = (event.y - offY) / sc
        val slop = touchSlopPx / sc
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN         -> {
                onAnyTouch?.invoke()
                touch.down(targetAt(s, x, y), x, y, locked = isInputLocked())
            }

            MotionEvent.ACTION_MOVE         -> touch.move(x, y, slop)
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL       -> touch.cancel()

            MotionEvent.ACTION_UP           -> fire(s, touch.up(targetAt(s, x, y), x, y, slop))
        }
        return true
    }

    /** Runs the callback of a decided tap ([QuickBolusTouch.up]). */
    private fun fire(s: QuickBolusPicker.Screen, target: Int) {
        when {
            target == QuickBolusTouch.CLOSE -> onClose?.invoke()
            target >= 0 && target < s.buttons.size -> onAmount?.invoke(s.buttons[target].amount)
        }
    }

    /** What lies under design coordinates ([x], [y]): an amount index, [QuickBolusTouch.CLOSE] or [QuickBolusTouch.NONE]. */
    fun targetAt(s: QuickBolusPicker.Screen, x: Float, y: Float): Int {
        val n = s.buttons.size
        for (i in 0 until n) if (hypot(x - buttonX(i, n), y - BUTTON_Y) <= BUTTON_R + HIT_PAD) return i
        if (s.phase == Phase.CHOOSING && hypot(x - CX, y - CLOSE_Y) <= closeHitR()) return QuickBolusTouch.CLOSE
        return QuickBolusTouch.NONE
    }

    /** The × hit radius in design px: the family's 24 dp touch radius (never below [CLOSE_HIT_R], never into the buttons). */
    fun closeHitR(): Float {
        val dp = CLOSE_HIT_DP * resources.displayMetrics.density / max(currentScale(), 0.01f)
        return min(max(CLOSE_HIT_R, dp), CLOSE_HIT_MAX)
    }

    /** The design-to-view scale, also before the first draw. */
    private fun currentScale(): Float {
        val side = min(width, height).toFloat()
        return if (side > 0f) side / 450f else scale
    }

    // ---------------------------------------------------------------- paint helpers

    private fun setFill(colour: Int) {
        fill.reset()
        fill.isAntiAlias = true
        fill.style = Paint.Style.FILL
        fill.color = colour
    }

    private fun setStroke(width: Float, colour: Int) {
        stroke.reset()
        stroke.isAntiAlias = true
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = width
        stroke.color = colour
    }

    private fun setText(size: Float, tf: Typeface) {
        text.reset()
        text.isAntiAlias = true
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf
        text.textSize = size
    }

    private fun drawText(
        canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, tracking: Float = 0f,
        align: Paint.Align = Paint.Align.CENTER, leftX: Float? = null
    ) {
        setText(size, tf)
        text.letterSpacing = tracking
        text.color = colour
        text.getFontMetrics(fm)
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        if (leftX != null) {
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(s, leftX, baseline, text)
        } else {
            text.textAlign = align
            canvas.drawText(s, cx, baseline, text)
        }
    }

    /** Centred, shrunk (never below 15 px) to [maxW]. */
    private fun drawFitted(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, maxW: Float = MAX_LINE_W) {
        setText(size, tf)
        val w = text.measureText(s)
        val fitted = if (w > maxW) max(15f, size * maxW / w) else size
        drawText(canvas, s, cx, cy, fitted, tf, colour)
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    companion object {

        const val CX = 225f
        const val TITLE_Y = 76f
        const val HEADER_Y = 124f
        const val DIVIDER_Y = 164f
        const val BUTTON_Y = 238f
        const val BUTTON_R = 58f
        /** Three buttons: centre to centre (116 px discs with 12 px air; the 1,5 ring stays clear of its neighbour). */
        const val STEP_3 = 130f
        const val STEP_2 = 140f
        const val HIT_PAD = 4f
        const val LINE1_Y = 324f
        const val LINE2_Y = 352f
        const val CLOSE_Y = 387f
        const val CLOSE_R = 24f
        const val CLOSE_HIT_R = 30f
        /** The family's minimum touch radius (as Orbit's ball). */
        const val CLOSE_HIT_DP = 24f
        /** Stays clear of the amount buttons' hit circles (they end at y 300). */
        const val CLOSE_HIT_MAX = 80f
        const val MAX_Y = 425f
        const val MAX_LINE_W = 330f

        private const val BG_SIZE = 46f
        private const val IOB_SIZE = 22f
        private const val AGE_SIZE = 17f
        private const val ARROW_BOX = 26f
        private const val AMOUNT_SIZE = 40f
        private const val UNIT_SIZE = 20f
        private const val OUTER_15_GAP = 5f
        private const val SPIN_GAP = 7f
        private const val LOCKED_ALPHA = 150
        private const val IDLE_RING_ALPHA = 150

        // Orbit's palette (OrbitWatchface)
        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val RED_TEXT = 0xFFFF8A98.toInt()
        private const val RED_BODY = 0xFF3A0F18.toInt()
        private const val STALE_C = 0xFF8A93A8.toInt()
        private const val TXT1 = 0xFFEEF1F7.toInt()
        private const val TXT2 = 0xFF9AA6C4.toInt()
        private const val TXT3 = 0xFF6F7A99.toInt()
        private const val IOB_C = 0xFF5BB8E8.toInt()
        private const val BOLUS_FILL = 0xFF4752D6.toInt()
        private const val FILL_LIT = 0xFF6470F0.toInt()
        private const val CHOSEN_FILL = 0xFF6A76FF.toInt()
        private const val BOLUS_TEXT = 0xFFE8EBFF.toInt()
        private const val INDIGO = 0xFF7888FF.toInt()
        /** The 1,5 button's second ring: grey (txt3) at about 70 %, 1.5 px; quieter than the buttons' white ring. */
        private const val OUTER_15 = 0xFF6F7A99.toInt()
        private const val OUTER_15_ALPHA = 180
        private const val DISABLED_FILL = 0xFF12152A.toInt()
        private const val DIM_FILL = 0xFF161A33.toInt()
        private const val DIM_TEXT = 0xFF3C4466.toInt()
        private const val DIVIDER = 0xFF3A4262.toInt()

        /** The header from the received data at [now]. */
        fun header(d: ComplicationData, now: Long, labels: Labels, locale: Locale): Header {
            val bg = d.bgData
            val ageMin = FaceState.ageMinutes(bg.timeStamp, now)
            val age = FaceState.bgAge(ageMin)
            val stale = FaceState.isOld(age)
            val range = OrbitFaceLogic.range(bg.sgv, bg.sgvLevel, bg.slopeArrow, bg.deltaMgdl, age)
            val iob = d.statusData.iobSum.trim().let { if (it.isEmpty() || it == "IOB" || it.startsWith("-.--")) "--" else it }
            return Header(
                bgText = OrbitFaceLogic.heroText(bg.sgvString, bg.sgv, ageMin),
                range = if (OrbitFaceLogic.sentinelLow(bg.sgv, ageMin)) Range.URGENT_LOW else range,
                trend = if (stale) OrbitFaceLogic.Trend.NONE else OrbitFaceLogic.trend(bg.slopeArrow, bg.deltaMgdl),
                stale = stale,
                iobText = String.format(locale, labels.iobFormat, iob),
                ageText = if (ageMin == null) "--" else String.format(locale, labels.ageFormat, ageMin),
                ageWarn = FaceState.ageLevel(age) != FaceState.Level.NORMAL
            )
        }
    }
}
