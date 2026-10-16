package app.aaps.wear.watchfaces

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventUpdateSelectedWatchface
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.ActionResendData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.kusi.KusiFaceLogic
import app.aaps.wear.watchfaces.kusi.KusiFaceLogic.LoopState
import app.aaps.wear.watchfaces.utils.WatchFace
import app.aaps.wear.watchfaces.utils.WatchMode
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "AAPS Kusi": a calm, Canvas-drawn watch face. One hero BG, one gradient ring (BG on a 2.2..16.7 mmol/L
 * scale with the target band, a knob and a 3 h trend tail), an IOB arc, a loop pill and one secondary line.
 *
 * Optional "+X U" button (left) sends a bolus REQUEST on double-tap, exactly like BolusActivity
 * (ActionBolusPreCheck). The phone checks everything and the watch shows the standard confirmation;
 * nothing is delivered without confirming there. Off by default, interactive mode only.
 *
 * Drawn on a 450x450 design grid scaled to the screen.
 */
@SuppressLint("Deprecated")
class KusiWatchface : WatchFace() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    private val disposable = CompositeDisposable()
    private val watchfaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var data: ComplicationData = ComplicationData()

    private val gesture = BolusTapGesture(clock = { System.currentTimeMillis() })
    private val disarmRunnable = Runnable { if (gesture.expireIfDue()) invalidate() }

    // Design-grid scale (450 px) and the last drawn state, for tap handling.
    private var scale = 1f
    private var buttonShown = false
    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L

    // Paints, created once.
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val bounds = Rect()
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)
    private val blur7 = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    private val blur4 = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    private val blur3 = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.KUSI.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())

        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect {
                data = it
                invalidate()
            }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                if (!bolusButtonEnabled() && gesture.reset()) handler.removeCallbacks(disarmRunnable)
                invalidate()
            }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { invalidate() }

        rxBus.send(EventWearToMobile(ActionResendData("KusiWatchface::onCreate")))
    }

    override fun onDestroy() {
        handler.removeCallbacks(disarmRunnable)
        disposable.clear()
        watchfaceScope.cancel()
        super.onDestroy()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        if (watchMode != WatchMode.INTERACTIVE) {
            gesture.reset()
            handler.removeCallbacks(disarmRunnable)
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusButtonPref(): Boolean = sp.getBoolean(R.string.key_kusi_bolus_double_tap, false)
    private fun wearControl(): Boolean = preferences.get(BooleanKey.WearControl)
    private fun bolusAmount(): Double = BolusTapGesture.parseAmount(sp.getString(R.string.key_kusi_bolus_amount, "0.5"))

    /** The button exists only with the preference on, Wear control allowed on the phone, and in interactive mode. */
    private fun bolusButtonEnabled(): Boolean = !isAmbient && bolusButtonPref() && wearControl()

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        scale = min(w, h) / 450f
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate((w - 450f * scale) / 2f, (h - 450f * scale) / 2f)
        canvas.scale(scale, scale)
        try {
            if (isAmbient) drawAmbient(canvas) else drawInteractive(canvas)
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "KusiWatchface draw failed", e)
        }
        canvas.restore()
    }

    private val bg get() = data.bgData
    private val st get() = data.statusData

    private fun rangeColour(): Int = when (bg.sgvLevel.toInt()) {
        -1   -> RED
        1    -> AMBER
        else -> GREEN
    }

    private fun lowMgdl() = bg.low.takeIf { it > 0 } ?: KusiFaceLogic.DEFAULT_LOW_MGDL
    private fun highMgdl() = bg.high.takeIf { it > 0 } ?: KusiFaceLogic.DEFAULT_HIGH_MGDL

    private fun resetPaint(antiAlias: Boolean = true): Paint = p.apply {
        reset()
        isAntiAlias = antiAlias
    }

    private fun arcRect(r: Float): RectF = rect.apply { set(CX - r, CY - r, CX + r, CY + r) }

    private fun pointX(angle: Float, r: Float) = CX + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun pointY(angle: Float, r: Float) = CY + r * sin(Math.toRadians(angle.toDouble())).toFloat()

    private fun drawInteractive(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val colour = rangeColour()
        val hasBg = bg.timeStamp != 0L
        val stale = KusiFaceLogic.isStale(bg.timeStamp, now)
        val bgAngle = KusiFaceLogic.angle(bg.sgv)
        val lowA = KusiFaceLogic.angle(lowMgdl())
        val highA = KusiFaceLogic.angle(highMgdl())

        // 1. backdrop glow in the range colour
        resetPaint().apply {
            shader = RadialGradient(CX, CY, 147f, withAlpha(colour, 0x1A), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(CX, CY, 147f, p)

        // 2. ring: track, target band, value fill with glow, threshold cuts
        val ring = arcRect(RING_R)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND; color = TRACK }
        canvas.drawArc(ring, KusiFaceLogic.RING_START, KusiFaceLogic.RING_SWEEP, false, p)
        p.strokeCap = Paint.Cap.BUTT
        p.color = BAND
        canvas.drawArc(ring, lowA, highA - lowA, false, p)

        if (hasBg) {
            val sweep = bgAngle - KusiFaceLogic.RING_START
            val gradient = SweepGradient(CX, CY, intArrayOf(FILL_START, colour, colour), floatArrayOf(0f, ((sweep + 6f) / 360f).coerceIn(0.01f, 1f), 1f))
            gradient.setLocalMatrix(Matrix().apply { setRotate(KusiFaceLogic.RING_START - 6f, CX, CY) })
            resetPaint().apply {
                style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND
                shader = gradient; maskFilter = blur7; alpha = 0xB0
            }
            canvas.drawArc(ring, KusiFaceLogic.RING_START, sweep, false, p)
            p.maskFilter = null
            p.alpha = 0xFF
            canvas.drawArc(ring, KusiFaceLogic.RING_START, sweep, false, p)
        }

        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK }
        for (a in floatArrayOf(lowA, highA))
            canvas.drawLine(pointX(a, 122f), pointY(a, 122f), pointX(a, 146f), pointY(a, 146f), p)

        // 4. trend tail inside the ring (drawn before the knob)
        if (hasBg) drawTrendTail(canvas, now, bgAngle)

        // 3. knob
        if (hasBg) {
            val kx = pointX(bgAngle, RING_R)
            val ky = pointY(bgAngle, RING_R)
            resetPaint().apply { color = Color.WHITE; setShadowLayer(6f, 0f, 0f, withAlpha(colour, 0x99)) }
            canvas.drawCircle(kx, ky, 12f, p)
            resetPaint().apply { color = colour }
            canvas.drawCircle(kx, ky, 6f, p)
        }

        // 5. time
        drawText(canvas, timeString(), CX, 147f, 30f, tf500, TIME)

        // 6. hero BG
        drawHeroBg(canvas, stale, ambient = false)

        // 7. arrow + delta · age
        drawDeltaLine(canvas, now, colour, DELTA, ambient = false)

        // 8. IOB arc
        drawIob(canvas, ambient = false)

        // 9. loop pill
        drawLoopPill(canvas, now, ambient = false)

        // 10. secondary line
        drawText(canvas, KusiFaceLogic.secondaryLine(st.tempTarget, st.tempTargetLevel, st.currentBasal), CX, 407f, 14f, tf500, SECONDARY)

        // 11. bolus button
        buttonShown = bolusButtonEnabled()
        if (buttonShown) drawBolusButton(canvas, gesture.isArmed)
        else if (gesture.reset()) handler.removeCallbacks(disarmRunnable)
    }

    private fun drawAmbient(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val aa = !lowBitAmbient
        buttonShown = false
        val ring = arcRect(139f)
        resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(0x46, 0x46, 0x46) }
        canvas.drawArc(ring, KusiFaceLogic.RING_START, KusiFaceLogic.RING_SWEEP, false, p)
        p.color = Color.rgb(0x6E, 0x6E, 0x6E)
        for (a in floatArrayOf(KusiFaceLogic.angle(lowMgdl()), KusiFaceLogic.angle(highMgdl())))
            canvas.drawLine(pointX(a, 129f), pointY(a, 129f), pointX(a, 145f), pointY(a, 145f), p)
        if (bg.timeStamp != 0L) {
            val a = KusiFaceLogic.angle(bg.sgv)
            p.color = Color.rgb(0xDC, 0xDC, 0xDC)
            canvas.drawCircle(pointX(a, 139f), pointY(a, 139f), 7f, p)
        }
        drawText(canvas, timeString(), CX, 147f, 30f, tf400, Color.rgb(0xC8, 0xC8, 0xC8), aa)
        drawHeroBg(canvas, KusiFaceLogic.isStale(bg.timeStamp, now), ambient = true)
        drawDeltaLine(canvas, now, AMB_GREY, AMB_GREY, ambient = true)
        drawIob(canvas, ambient = true)
        drawLoopPill(canvas, now, ambient = true)
    }

    private fun drawTrendTail(canvas: Canvas, now: Long, bgAngle: Float) {
        val from = now - 3 * 60 * 60 * 1000L
        val first = data.graphData.entries.filter { it.timeStamp >= from && it.sgv > 0 }.minByOrNull { it.timeStamp } ?: return
        val startA = KusiFaceLogic.angle(first.sgv)
        if (kotlin.math.abs(bgAngle - startA) < 0.5f) return
        val r = arcRect(119f)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        val segments = 30
        val step = (bgAngle - startA) / segments
        for (i in 0 until segments) {
            p.color = withAlpha(TAIL, (255f * (i + 1) / segments).toInt())
            canvas.drawArc(r, startA + i * step, step, false, p)
        }
    }

    private fun drawHeroBg(canvas: Canvas, stale: Boolean, ambient: Boolean) {
        text.reset()
        text.isAntiAlias = !(ambient && lowBitAmbient)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 104f
        text.letterSpacing = -0.02f
        text.typeface = if (ambient) tf300 else tf600
        text.getTextBounds("9.4", 0, 3, bounds)
        val baseline = 229f + bounds.height() / 2f
        val value = bg.sgvString
        when {
            ambient -> {
                text.style = Paint.Style.STROKE
                text.strokeWidth = 2f
                text.color = Color.rgb(0xE6, 0xE6, 0xE6)
            }
            stale   -> text.color = STALE
            else    -> {
                text.color = Color.WHITE
                text.setShadowLayer(9f, 0f, 0f, 0x66FFFFFF)
            }
        }
        canvas.drawText(value, CX, baseline, text)
        if (stale && !ambient) {
            val half = text.measureText(value) / 2f + 4f
            resetPaint().apply { color = STALE; strokeWidth = 2f }
            canvas.drawLine(CX - half, 229f, CX + half, 229f, p)
        }
    }

    private fun drawDeltaLine(canvas: Canvas, now: Long, arrowColour: Int, textColour: Int, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val arrow = if (bg.timeStamp == 0L) "" else "${bg.slopeArrow}︎"
        val line = KusiFaceLogic.deltaLine(bg.delta, KusiFaceLogic.bgAgeMinutes(bg.timeStamp, now))
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.LEFT
        text.typeface = tf600
        text.textSize = 28f
        val arrowW = if (arrow.isEmpty()) 0f else text.measureText(arrow)
        text.typeface = tf500
        text.textSize = 22f
        val lineW = text.measureText(line)
        val gap = if (arrow.isEmpty()) 0f else 10f
        var x = CX - (arrowW + gap + lineW) / 2f
        if (arrow.isNotEmpty()) {
            text.typeface = tf600
            text.textSize = 28f
            text.color = arrowColour
            canvas.drawText(arrow, x, baselineFor(291f), text)
            x += arrowW + gap
        }
        text.typeface = tf500
        text.textSize = 22f
        text.color = textColour
        canvas.drawText(line, x, baselineFor(291f), text)
    }

    private fun drawIob(canvas: Canvas, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val r = arcRect(207f)
        val fraction = KusiFaceLogic.iobFraction(KusiFaceLogic.parseIob(st.iobSum))
        resetPaint(aa).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            strokeWidth = if (ambient) 1f else 5f
            color = if (ambient) Color.rgb(0x32, 0x32, 0x32) else IOB_TRACK
        }
        canvas.drawArc(r, -50f, 100f, false, p)
        if (fraction > 0f) {
            val sweep = -100f * fraction
            if (ambient) {
                p.strokeWidth = 2f
                p.color = Color.rgb(0x8C, 0x8C, 0x8C)
                canvas.drawArc(r, 50f, sweep, false, p)
            } else {
                p.color = CYAN
                p.maskFilter = blur4
                canvas.drawArc(r, 50f, sweep, false, p)
                p.maskFilter = null
                canvas.drawArc(r, 50f, sweep, false, p)
            }
        }
        drawText(canvas, getString(R.string.activity_IOB), 401f, 211f, 13f, tf600, if (ambient) Color.rgb(0x6E, 0x6E, 0x6E) else IOB_LABEL, aa)
        drawText(canvas, st.iobSum, 401f, 233f, 22f, tf500, if (ambient) Color.rgb(0xBE, 0xBE, 0xBE) else IOB_VALUE, aa)
    }

    private fun drawLoopPill(canvas: Canvas, now: Long, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val age = KusiFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        val state = KusiFaceLogic.loopState(age)
        val label = age?.let { "$it'" } ?: "--"
        rect.set(CX - 37f, 375f - 14f, CX + 37f, 375f + 14f)
        if (ambient) {
            resetPaint(aa).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(0x6E, 0x6E, 0x6E) }
            canvas.drawRoundRect(rect, 14f, 14f, p)
            p.color = Color.rgb(0xC8, 0xC8, 0xC8)
            canvas.drawCircle(205f, 375f, 4f, p)
            drawText(canvas, label, 233f, 375f, 17f, tf600, Color.rgb(0xC8, 0xC8, 0xC8), aa)
            return
        }
        val (fill, outline, dot) = when (state) {
            LoopState.OK    -> Triple(0xFF12281E.toInt(), 0xFF285A40.toInt(), GREEN)
            LoopState.WARN  -> Triple(0xFF2A2210.toInt(), 0xFF5A4A20.toInt(), AMBER)
            LoopState.STALE -> Triple(0xFF2E1218.toInt(), 0xFF6A2632.toInt(), RED)
        }
        resetPaint().apply { color = fill }
        canvas.drawRoundRect(rect, 14f, 14f, p)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = outline }
        canvas.drawRoundRect(rect, 14f, 14f, p)
        resetPaint().apply { color = dot; setShadowLayer(4f, 0f, 0f, dot) }
        canvas.drawCircle(205f, 375f, 5f, p)
        val textColour = when (state) {
            LoopState.OK    -> 0xFFD2F5E1.toInt()
            LoopState.WARN  -> 0xFFFFE6AA.toInt()
            LoopState.STALE -> 0xFFFFD2D8.toInt()
        }
        drawText(canvas, if (state == LoopState.STALE && age == null) "--" else label, 233f, 375f, 17f, tf600, textColour)
    }

    private fun drawBolusButton(canvas: Canvas, armed: Boolean) {
        val amount = String.format(java.util.Locale.US, "+%.1f", bolusAmount())
        resetPaint().apply {
            color = if (armed) 0xFF3C2A0A.toInt() else 0xFF141628.toInt()
            setShadowLayer(if (armed) 9f else 5f, 0f, 0f, if (armed) 0xAAFFC85A.toInt() else 0x66788CFF)
        }
        canvas.drawCircle(BTN_X, BTN_Y, BTN_R, p)
        resetPaint().apply { color = 0x16FFFFFF; maskFilter = blur3 }
        canvas.drawOval(29f, 197f, 65f, 222f, p)
        resetPaint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = if (armed) 0xFFFFC85A.toInt() else INDIGO }
        canvas.drawCircle(BTN_X, BTN_Y, BTN_R, p)
        drawText(canvas, amount, BTN_X, 220f, 15f, tf700, if (armed) 0xFFFFE6AA.toInt() else 0xFFF0F0FF.toInt())
        if (armed) drawText(canvas, getString(R.string.kusi_bolus_again), BTN_X, 237f, 10f, tf600, 0xFFFFC85A.toInt())
        else drawText(canvas, getString(R.string.kusi_bolus_sub), BTN_X, 237f, 11f, tf600, 0xFF96A0DC.toInt())
    }

    // ---------------------------------------------------------------- text helpers

    private fun baselineFor(centreY: Float): Float {
        val fm = text.fontMetrics
        return centreY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, aa: Boolean = true) {
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf
        text.textSize = size
        text.color = colour
        canvas.drawText(s, cx, baselineFor(cy), text)
    }

    private fun timeString(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern(if (is24HourFormat) "HH:mm" else "h:mm"))

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TAP_UP) return
        val left = (faceRect.width() - 450f * scale) / 2f
        val top = (faceRect.height() - 450f * scale) / 2f
        val dx = (x - left) / scale
        val dy = (y - top) / scale

        val enabled = buttonShown && bolusButtonEnabled()
        val inside = BolusTapGesture.isInside(dx, dy, BTN_X, BTN_Y, BTN_HIT_R)
        when (gesture.onTap(inside, enabled)) {
            BolusTapGesture.Decision.ARM    -> {
                vibrate()
                handler.removeCallbacks(disarmRunnable)
                handler.postDelayed(disarmRunnable, gesture.windowMs + 50)
                invalidate()
                return
            }

            BolusTapGesture.Decision.SEND   -> {
                handler.removeCallbacks(disarmRunnable)
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "KusiWatchface: double-tap bolus request $amount U")
                // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                invalidate()
                return
            }

            BolusTapGesture.Decision.DISARM -> {
                handler.removeCallbacks(disarmRunnable)
                invalidate()
            }

            BolusTapGesture.Decision.IGNORE -> Unit
        }
        if (inside && enabled) return

        // Double-tap on the hero BG opens the main menu, like the other AAPS faces.
        if (dx in 110f..340f && dy in 170f..270f) {
            if (bgTapTime != 0L && eventTime - bgTapTime < 800) {
                if (eventTime - lastMenuOpenTime > 2000) {
                    lastMenuOpenTime = eventTime
                    startActivity(Intent(this, MainMenuActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                }
                bgTapTime = 0
            } else bgTapTime = eventTime
        }
    }

    private fun vibrate() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "KusiWatchface vibrate failed", e)
        }
    }

    companion object {

        private const val TAP_UP = 2
        private const val CX = 225f
        private const val CY = 225f
        private const val RING_R = 133.5f
        private const val BTN_X = 50f
        private const val BTN_Y = 225f
        private const val BTN_R = 30f
        private const val BTN_HIT_R = 38f

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val INDIGO = 0xFF788CFF.toInt()
        private const val TRACK = 0xFF1E2228.toInt()
        private const val BAND = 0xFF1C3E30.toInt()
        private const val FILL_START = 0xFF286EBE.toInt()
        private const val TAIL = 0xFFAAB4C3.toInt()
        private const val TIME = 0xFFEBEBF0.toInt()
        private const val DELTA = 0xFFB4BAC4.toInt()
        private const val STALE = 0xFF6E7480.toInt()
        private const val SECONDARY = 0xFF6E7480.toInt()
        private const val IOB_TRACK = 0xFF1A1C24.toInt()
        private const val IOB_LABEL = 0xFF5A606E.toInt()
        private const val IOB_VALUE = 0xFFD7F0FF.toInt()
        private const val AMB_GREY = 0xFFAAAAAA.toInt()
    }
}
