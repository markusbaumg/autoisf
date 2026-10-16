package app.aaps.wear.watchfaces.glass

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Level
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Range
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.RunKind
import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.utils.WatchFace
import app.aaps.wear.watchfaces.utils.WatchFaceTime
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
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.math.min

/**
 * "AAPS Glass": frosted glass cards over a night gradient; an aurora behind the hero card whose hue is the
 * BG state (teal in range, amber high, red low, grey stale). Spec: designs/glass_spec.md.
 *
 * Drawn on a 450x450 design grid scaled to the screen. The gradient, aurora and card chrome are baked once
 * per range into a software bitmap (blur works there, not on the hardware canvas); every frame only blits
 * that bitmap and draws text, the sparkline and the optional bolus ball from state that is recomputed on a
 * data change or a minute tick ([updateState]), never per frame.
 *
 * Optional bolus ball (left): a double-tap sends only a bolus REQUEST (ActionBolusPreCheck); the phone runs
 * its checks and the watch shows the standard confirmation. Hardened rules in [GlassFaceLogic.bolusAllowed];
 * gesture window 800 ms, cooldown 5 s, duplicate tap events ignored.
 */
@SuppressLint("Deprecated")
class GlassWatchface : WatchFace() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository

    private val disposable = CompositeDisposable()
    private val watchfaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var data: ComplicationData = ComplicationData()

    // ---------------------------------------------------------------- gesture (hardened rule set)

    private val gesture = BolusTapGesture(clock = { System.currentTimeMillis() }, windowMs = GlassFaceLogic.BOLUS_WINDOW_MS, cooldownMs = GlassFaceLogic.BOLUS_COOLDOWN_MS)
    private var armedAt = 0L
    private var flashUntil = 0L
    private var lastTapEventTime = Long.MIN_VALUE
    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L
    private val armedTick = object : Runnable {
        override fun run() {
            if (gesture.expireIfDue() || !gesture.isArmed) {
                invalidate()
                return
            }
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }
    private val flashEnd = Runnable { invalidate() }

    // ---------------------------------------------------------------- derived state (rebuilt on change only)

    private var dirty = true
    private var stateMinute = -1L
    private var scale = 1f
    private var pixelSize = 0

    private var stale = true
    private var hasBg = false
    private var range = Range.STALE
    private var rangeColour = STALE_COL
    private var bgText = "---"
    private var bgSize = 84f
    private var bgHalfWidth = 0f
    private var arrowText = ""
    private var deltaText = "--"
    private var ageText = "--"
    private var ageLevel = Level.ALERT
    private var metaX = 312f
    private var metaSize = 18f
    private var metaDeltaW = 0f
    private var metaSepW = 0f
    private var loopText = "--"
    private var loopTextLowBit = "--"
    private var loopLevel: Level? = null
    private var loopLabel = true
    private var iobText = "--"
    private var iobLabel = true
    private var footer: List<GlassFaceLogic.Run> = emptyList()
    private var footerX = FloatArray(12)
    private var footerSize = 18f
    private var disconnected = true
    private var ballShown = false
    private var amountText = "+0.5"
    private var timeText = ""
    private var dateText = ""
    private var dateKey = -1
    private var burnX = 0f
    private var burnY = 0f
    private var lowY = 0f
    private var highY = 0f

    private val history = Path()
    private var hasHistory = false
    private var curX = 0f
    private var curY = 0f
    private var hasCurrent = false
    private val predXY = FloatArray(2 * 12)
    private var predCount = 0

    // ---------------------------------------------------------------- paints, created once

    private val tf200 = Typeface.create(Typeface.DEFAULT, 200, false)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dash35 = DashPathEffect(floatArrayOf(3f, 5f), 0f)
    private val dash24 = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    private val rect = RectF()
    private val historyShaders = arrayOfNulls<Shader>(Range.entries.size)

    // Baked bitmaps: one background per range, the ball idle/armed.
    private val backgrounds = arrayOfNulls<Bitmap>(Range.entries.size)
    private var ballIdle: Bitmap? = null
    private var ballArmed: Bitmap? = null

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.GLASS.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())

        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect {
                data = it
                dirty = true
                invalidate()
            }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { onSettingsChanged() }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { onSettingsChanged() }

        rxBus.send(EventWearToMobile(ActionResendData("GlassWatchface::onCreate")))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disposable.clear()
        watchfaceScope.cancel()
        recycleBitmaps()
        super.onDestroy()
    }

    private fun onSettingsChanged() {
        dirty = true
        if (!bolusAllowed()) disarm()
        invalidate()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        disarm()
        burnX = 0f
        burnY = 0f
        dirty = true
    }

    override fun onTimeChanged(oldTime: WatchFaceTime, newTime: WatchFaceTime) {
        if (newTime.hasMinuteChanged(oldTime)) {
            dirty = true
            if (isAmbient && burnInProtection) {
                burnX = GlassFaceLogic.burnInOffset(Math.random())
                burnY = GlassFaceLogic.burnInOffset(Math.random())
            }
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusPref(): Boolean = sp.getBoolean(R.string.key_kusi_bolus_double_tap, false)
    private fun bolusAmount(): Double = BolusTapGesture.parseAmount(sp.getString(R.string.key_kusi_bolus_amount, "0.5"))
    private fun maxBolus(): Double = sp.getDouble(R.string.key_treatments_safety_max_bolus, 0.0)

    private fun bolusAllowed(now: Long = System.currentTimeMillis()): Boolean =
        GlassFaceLogic.bolusAllowed(
            prefOn = bolusPref(),
            wearControl = preferences.get(BooleanKey.WearControl),
            ambient = isAmbient,
            disconnected = GlassFaceLogic.isDisconnected(data.lastUpdateTimestamp, now),
            amount = bolusAmount(),
            maxBolus = maxBolus()
        )

    private fun disarm() {
        gesture.reset()
        handler.removeCallbacks(armedTick)
    }

    // ---------------------------------------------------------------- state

    private fun colourOf(r: Range) = when (r) {
        Range.IN    -> IN_COL
        Range.HIGH  -> HIGH_COL
        Range.LOW   -> LOW_COL
        Range.STALE -> STALE_COL
    }

    /** Recompute everything that is drawn from the data and the clock. Runs on a data change or a minute tick. */
    private fun updateState(now: Long) {
        dirty = false
        stateMinute = now / GlassFaceLogic.MINUTE_MS
        val bg = data.bgData
        val st = data.statusData
        val old = getString(R.string.old)

        // time / date from the renderer's time
        timeText = GlassFaceLogic.timeText(latestTime.hour, latestTime.minute, is24HourFormat)
        val key = latestTime.year * 400 + latestTime.month * 32 + latestTime.monthDay
        if (key != dateKey && latestTime.year > 0) {
            dateKey = key
            dateText = GlassFaceLogic.dateText(LocalDate.of(latestTime.year, latestTime.month + 1, latestTime.monthDay), Locale.GERMANY)
        }

        // BG
        stale = GlassFaceLogic.isStale(bg.timeStamp, now)
        hasBg = bg.timeStamp != 0L
        range = GlassFaceLogic.range(bg.sgv, bg.low, bg.high, bg.sgvLevel, stale)
        rangeColour = colourOf(range)
        bgText = if (bg.timeStamp == 0L) "---" else bg.sgvString
        bgSize = GlassFaceLogic.bgTextSize(bgText)
        setText(tf600, bgSize)
        bgSize = GlassFaceLogic.fitSize(bgSize, text.measureText(bgText), BG_MAX_WIDTH)
        text.textSize = bgSize
        bgHalfWidth = text.measureText(bgText) / 2f
        arrowText = GlassFaceLogic.arrow(bg.slopeArrow, stale)
        val age = GlassFaceLogic.ageMinutes(bg.timeStamp, now)
        ageText = GlassFaceLogic.minutesText(age, old)
        ageLevel = GlassFaceLogic.ageLevel(age)
        deltaText = GlassFaceLogic.deltaText(bg.delta)
        layoutMeta()

        // loop / IOB
        val loopAge = GlassFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        loopLevel = GlassFaceLogic.loopLevel(loopAge, GlassFaceLogic.loopDisabled(st.externalStatus))
        loopText = GlassFaceLogic.loopText(loopAge, old, lowBit = false)
        loopTextLowBit = GlassFaceLogic.loopText(loopAge, old, lowBit = true)
        loopLabel = GlassFaceLogic.loopLabelVisible(loopAge)
        iobText = GlassFaceLogic.iobText(st.iobSum)
        setText(tf600, 20f)
        val iobRight = IOB_X0 + 16f + text.measureText(iobText)
        setText(tf600, 12f)
        text.letterSpacing = 0.05f
        iobLabel = iobRight + 6f <= IOB_X1 - 14f - text.measureText("IOB")

        // footer
        disconnected = GlassFaceLogic.isDisconnected(data.lastUpdateTimestamp, now)
        layoutFooter(st, now, old)

        // sparkline
        val low = bg.low.takeIf { it > 0 } ?: GlassFaceLogic.DEFAULT_LOW_MGDL
        val high = bg.high.takeIf { it > low } ?: GlassFaceLogic.DEFAULT_HIGH_MGDL
        lowY = GlassFaceLogic.sparkY(low)
        highY = GlassFaceLogic.sparkY(high)
        buildSparkline(bg.timeStamp, bg.sgv, now)

        // ball
        ballShown = bolusAllowed(now)
        amountText = GlassFaceLogic.bolusAmountText(bolusAmount())
        if (!ballShown) disarm()
    }

    private fun layoutMeta() {
        metaSize = 18f
        setText(tf500, metaSize)
        var total = text.measureText(ageText)
        if (!stale) total += text.measureText(deltaText) + text.measureText(META_SEP)
        metaSize = GlassFaceLogic.fitSize(18f, total, META_MAX_WIDTH)
        text.textSize = metaSize
        metaDeltaW = if (stale) 0f else text.measureText(deltaText)
        metaSepW = if (stale) 0f else text.measureText(META_SEP)
        total = metaDeltaW + metaSepW + text.measureText(ageText)
        // centred on the chip, but the right edge stays inside the card
        metaX = min(CHIP_X - total / 2f, META_RIGHT - total)
    }

    private fun layoutFooter(st: EventData.Status, now: Long, old: String) {
        setText(tf500, 18f)
        val words = GlassFaceLogic.FooterWords(
            phoneBattery = { getString(R.string.glass_phone_battery, it) },
            disconnected = { getString(R.string.glass_phone_disconnected, it) },
            cob = { getString(R.string.glass_cob, it) },
            reservoir = { getString(R.string.glass_reservoir, it) },
            oldWord = old,
            waiting = getString(R.string.glass_waiting)
        )
        val input = GlassFaceLogic.FooterInput(
            tempTarget = st.tempTarget, tempTargetLevel = st.tempTargetLevel, cob = st.cob, currentBasal = st.currentBasal,
            profilePercent = null, // not part of EventData.Status
            phoneBattery = st.battery, reservoirString = st.reservoirString, reservoirLevel = st.reservoirLevel,
            lastDataTimeStamp = data.lastUpdateTimestamp, now = now
        )
        footer = GlassFaceLogic.footerRuns(input, words, FOOTER_SEP, { text.measureText(it) })
        val width = GlassFaceLogic.lineWidth(footer, FOOTER_SEP, { text.measureText(it) })
        footerSize = GlassFaceLogic.fitSize(18f, width, GlassFaceLogic.FOOTER_MAX_WIDTH)
        text.textSize = footerSize
        val sep = text.measureText(FOOTER_SEP)
        var x = CX - min(width, GlassFaceLogic.FOOTER_MAX_WIDTH) / 2f
        footer.forEachIndexed { i, run ->
            if (i > 0) x += sep
            footerX[i] = x
            x += text.measureText(run.text)
        }
    }

    private fun buildSparkline(bgTs: Long, bgMgdl: Double, now: Long) {
        val anchor = GlassFaceLogic.sparkAnchor(bgTs, now)
        history.reset()
        hasHistory = false
        hasCurrent = false
        var lastTs = Long.MIN_VALUE
        var started = false
        for (e in data.graphData.entries.sortedBy { it.timeStamp }) {
            if (e.sgv <= 0.0 || e.timeStamp > anchor) continue
            val slot = GlassFaceLogic.slotOf(e.timeStamp, anchor) ?: continue
            val x = GlassFaceLogic.sparkX(slot)
            val y = GlassFaceLogic.sparkY(e.sgv)
            if (!started) history.moveTo(x, y) else history.lineTo(x, y)
            started = true
            curX = x; curY = y; lastTs = e.timeStamp
        }
        if (bgTs > 0L && bgMgdl > 0.0 && bgTs > lastTs) {
            GlassFaceLogic.slotOf(bgTs, anchor)?.let { slot ->
                val x = GlassFaceLogic.sparkX(slot)
                val y = GlassFaceLogic.sparkY(bgMgdl)
                if (!started) history.moveTo(x, y) else history.lineTo(x, y)
                started = true
                curX = x; curY = y
            }
        }
        hasHistory = started
        hasCurrent = started

        // prediction: the main (longest) series, sampled at the 12 future slots
        predCount = 0
        val preds = data.treatmentData.predictions
        if (preds.isEmpty() || stale) return
        val main = preds.groupBy { it.color }.maxByOrNull { it.value.size }?.value ?: return
        for (k in 1..12) {
            val t = anchor + k * GlassFaceLogic.SLOT_MS
            val p = main.minByOrNull { kotlin.math.abs(it.timeStamp - t) } ?: continue
            if (kotlin.math.abs(p.timeStamp - t) > GlassFaceLogic.SLOT_MS / 2 || p.sgv <= 0.0) continue
            predXY[2 * predCount] = GlassFaceLogic.sparkX((GlassFaceLogic.SPARK_HISTORY - 1 + k).toFloat())
            predXY[2 * predCount + 1] = GlassFaceLogic.sparkY(p.sgv)
            predCount++
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width
        val h = canvas.height
        val size = min(w, h)
        if (size != pixelSize) {
            recycleBitmaps()
            pixelSize = size
        }
        scale = size / 450f
        val now = System.currentTimeMillis()
        if (dirty || now / GlassFaceLogic.MINUTE_MS != stateMinute) updateState(now)

        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate((w - size) / 2f, (h - size) / 2f)
        try {
            if (isAmbient) {
                canvas.scale(scale, scale)
                canvas.translate(burnX, burnY)
                drawAmbient(canvas)
            } else {
                canvas.drawBitmap(background(range), 0f, 0f, bitmapPaint)
                canvas.scale(scale, scale)
                drawInteractive(canvas, now)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "GlassWatchface draw failed", e)
        }
        canvas.restore()
    }

    private fun setText(tf: Typeface, size: Float, aa: Boolean = true, align: Paint.Align = Paint.Align.LEFT) {
        text.reset()
        text.isAntiAlias = aa
        text.typeface = tf
        text.textSize = size
        text.textAlign = align
    }

    private fun drawCentred(canvas: Canvas, s: String, cx: Float, cy: Float, tf: Typeface, size: Float, colour: Int, aa: Boolean = true) {
        setText(tf, size, aa, Paint.Align.CENTER)
        text.color = colour
        canvas.drawText(s, cx, baseline(cy), text)
    }

    private fun baseline(centreY: Float): Float {
        val fm = text.fontMetrics
        return centreY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawInteractive(canvas: Canvas, now: Long) {
        // time + date
        setText(tf400, 42f, align = Paint.Align.CENTER)
        text.fontFeatureSettings = "tnum"
        text.color = TXT1
        canvas.drawText(timeText, CX, baseline(TIME_Y), text)
        drawCentred(canvas, dateText, CX, DATE_Y, tf500, 18f, TXT2)

        // hero BG
        setText(tf600, bgSize, align = Paint.Align.CENTER)
        if (stale) text.color = TXT2
        else {
            text.color = Color.WHITE
            text.setShadowLayer(12f, 0f, 0f, withAlpha(rangeColour, 0x96))
        }
        canvas.drawText(bgText, BG_X, baseline(BG_Y), text)
        if (stale && hasBg) {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = 3f; line.color = TXT2
            canvas.drawLine(BG_X - bgHalfWidth - 4f, BG_Y + 2f, BG_X + bgHalfWidth + 4f, BG_Y + 2f, line)
        }

        // trend chip
        fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(rangeColour, 40)
        canvas.drawCircle(CHIP_X, CHIP_Y, CHIP_R, fill)
        stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = withAlpha(rangeColour, 210)
        canvas.drawCircle(CHIP_X, CHIP_Y, CHIP_R, stroke)
        if (arrowText.isNotEmpty()) drawCentred(canvas, arrowText, CHIP_X, CHIP_Y + 1f, tf400, 30f, rangeColour)

        // meta line
        drawMeta(canvas, DELTA_COL, TXT3, when (ageLevel) {
            Level.NORMAL -> TXT2
            Level.WARN   -> WARN_COL
            Level.ALERT  -> ALERT_COL
        }, aa = true)

        // sparkline
        fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(rangeColour, 24)
        rect.set(GlassFaceLogic.SPARK_X0, highY, GlassFaceLogic.SPARK_X1, lowY)
        canvas.drawRoundRect(rect, 8f, 8f, fill)
        drawSparkGuides(canvas, withAlpha(Color.WHITE, 46), withAlpha(Color.WHITE, 60), aa = true)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 2.5f
            line.shader = historyShader(range)
            line.setShadowLayer(4f, 0f, 0f, withAlpha(rangeColour, 180))
            canvas.drawPath(history, line)
            line.shader = null
            line.clearShadowLayer()
        }
        if (predCount > 0) {
            fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(PRED_COL, 210)
            for (i in 0 until predCount) canvas.drawCircle(predXY[2 * i], predXY[2 * i + 1], 1.6f, fill)
        }
        if (hasCurrent) {
            fill.reset(); fill.isAntiAlias = true; fill.color = rangeColour
            if (!stale) fill.setShadowLayer(5f, 0f, 0f, withAlpha(rangeColour, 220))
            canvas.drawCircle(curX, curY, 5f, fill)
            fill.clearShadowLayer()
            fill.color = if (stale) TXT3 else Color.WHITE
            canvas.drawCircle(curX, curY, 3f, fill)
        }

        // pills
        val loopColour = levelColour(loopLevel, LOOP_OK, LOOP_WARN, LOOP_BAD)
        fill.reset(); fill.isAntiAlias = true; fill.color = loopColour
        fill.setShadowLayer(4f, 0f, 0f, withAlpha(loopColour, 190))
        canvas.drawCircle(LOOP_X0 + 20f, PILL_Y, 5f, fill)
        fill.clearShadowLayer()
        drawPillTexts(canvas, if (loopLevel == Level.ALERT) LOOP_BAD else TXT1, LABEL_COL, loopText, aa = true)

        // footer
        drawFooter(canvas, ambient = false, aa = true)

        // bolus ball
        if (ballShown) drawBall(canvas, now)
    }

    private fun drawMeta(canvas: Canvas, deltaColour: Int, sepColour: Int, ageColour: Int, aa: Boolean) {
        setText(tf500, metaSize, aa)
        val y = baseline(META_Y)
        var x = metaX
        if (!stale) {
            text.color = deltaColour
            canvas.drawText(deltaText, x, y, text)
            x += metaDeltaW
            text.color = sepColour
            canvas.drawText(META_SEP, x, y, text)
            x += metaSepW
        }
        text.color = ageColour
        canvas.drawText(ageText, x, y, text)
    }

    private fun drawSparkGuides(canvas: Canvas, bandColour: Int, nowColour: Int, aa: Boolean) {
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 1f
        line.color = bandColour
        line.pathEffect = dash35
        canvas.drawLine(GlassFaceLogic.SPARK_X0, lowY, GlassFaceLogic.SPARK_X1, lowY, line)
        canvas.drawLine(GlassFaceLogic.SPARK_X0, highY, GlassFaceLogic.SPARK_X1, highY, line)
        line.color = nowColour
        line.pathEffect = dash24
        val nx = GlassFaceLogic.nowX
        canvas.drawLine(nx, GlassFaceLogic.SPARK_Y0, nx, GlassFaceLogic.SPARK_Y1, line)
        line.pathEffect = null
    }

    private fun drawPillTexts(canvas: Canvas, loopValueColour: Int, labelColour: Int, loopValue: String, aa: Boolean, valueColour: Int = TXT1) {
        setText(tf600, 20f, aa)
        val y = baseline(PILL_Y)
        text.color = loopValueColour
        canvas.drawText(loopValue, LOOP_X0 + 33f, y, text)
        text.color = valueColour
        canvas.drawText(iobText, IOB_X0 + 16f, y, text)
        setText(tf600, 12f, aa, Paint.Align.RIGHT)
        text.letterSpacing = 0.05f
        text.color = labelColour
        val ly = baseline(PILL_Y + 1f)
        if (loopLabel) canvas.drawText("LOOP", LOOP_X1 - 14f, ly, text)
        if (iobLabel) canvas.drawText("IOB", IOB_X1 - 14f, ly, text)
    }

    private fun drawFooter(canvas: Canvas, ambient: Boolean, aa: Boolean) {
        setText(tf500, footerSize, aa)
        val y = baseline(FOOTER_Y)
        val lowBit = ambient && lowBitAmbient
        footer.forEachIndexed { i, run ->
            if (i > 0) {
                text.color = if (lowBit) Color.WHITE else if (ambient) A_TXT3 else TXT3
                canvas.drawText(FOOTER_SEP, footerX[i - 1] + text.measureText(footer[i - 1].text), y, text)
            }
            text.color = when {
                lowBit                                   -> Color.WHITE
                ambient && run.level != Level.NORMAL     -> A_TXT1
                ambient                                  -> A_TXT2
                run.kind == RunKind.TT                   -> TT_COL
                run.level == Level.ALERT                 -> ALERT_COL
                run.level == Level.WARN                  -> WARN_COL
                else                                     -> TXT2
            }
            canvas.drawText(run.text, footerX[i], y, text)
        }
    }

    private fun drawBall(canvas: Canvas, now: Long) {
        val armed = gesture.isArmed
        val bmp = ballBitmap(armed)
        canvas.save()
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(bmp, (GlassFaceLogic.BALL_X - BALL_BOX) * scale, (GlassFaceLogic.BALL_Y - BALL_BOX) * scale, bitmapPaint)
        canvas.restore()
        if (armed) {
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2f; stroke.color = withAlpha(AMBER_BALL, 50)
            canvas.drawCircle(GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y, RING_R, stroke)
            stroke.strokeWidth = 3f; stroke.color = AMBER_BALL; stroke.strokeCap = Paint.Cap.ROUND
            rect.set(GlassFaceLogic.BALL_X - RING_R, GlassFaceLogic.BALL_Y - RING_R, GlassFaceLogic.BALL_X + RING_R, GlassFaceLogic.BALL_Y + RING_R)
            canvas.drawArc(rect, -90f, 360f * GlassFaceLogic.armProgress(armedAt, now, gesture.windowMs), false, stroke)
        }
        if (now < flashUntil) {
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = Color.WHITE
            canvas.drawCircle(GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y, GlassFaceLogic.BALL_R, stroke)
        }
        drawCentred(canvas, amountText, GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y - 3f, tf700, 18f, Color.WHITE)
        drawCentred(canvas, "U", GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y + 13f, tf600, 12f, if (armed) 0xFFFFF0D0.toInt() else 0xFFD7E6FF.toInt())
    }

    private fun drawAmbient(canvas: Canvas) {
        val lowBit = lowBitAmbient
        val aa = !lowBit
        fun c(colour: Int) = if (lowBit) Color.WHITE else colour

        setText(tf200, 42f, aa, Paint.Align.CENTER)
        text.fontFeatureSettings = "tnum"
        text.color = c(A_TXT1)
        canvas.drawText(timeText, CX, baseline(TIME_Y), text)
        drawCentred(canvas, dateText, CX, DATE_Y, tf500, 18f, c(A_TXT2), aa)

        // hero card outline
        stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1f; stroke.color = c(A_LINE)
        rect.set(HERO_X0, HERO_Y0, HERO_X1, HERO_Y1)
        canvas.drawRoundRect(rect, HERO_R, HERO_R, stroke)
        drawCentred(canvas, bgText, BG_X, BG_Y, tf300, bgSize, c(A_TXT1), aa)
        if (stale && hasBg) {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 2f; line.color = c(A_TXT1)
            canvas.drawLine(BG_X - bgHalfWidth - 4f, BG_Y + 2f, BG_X + bgHalfWidth + 4f, BG_Y + 2f, line)
        }
        stroke.color = c(A_TXT3)
        canvas.drawCircle(CHIP_X, CHIP_Y, CHIP_R, stroke)
        if (arrowText.isNotEmpty()) drawCentred(canvas, arrowText, CHIP_X, CHIP_Y + 1f, tf400, 30f, c(A_TXT1), aa)
        drawMeta(canvas, c(A_TXT2), c(A_TXT3), c(if (stale || ageLevel != Level.NORMAL) A_TXT1 else A_TXT2), aa)

        // sparkline: no band, no prediction; dashes omitted in low-bit
        if (!lowBit) drawSparkGuides(canvas, A_LINE, A_LINE, aa)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 1.5f; line.color = c(A_HIST)
            canvas.drawPath(history, line)
        }
        if (hasCurrent) {
            fill.reset(); fill.isAntiAlias = aa; fill.color = Color.BLACK
            canvas.drawCircle(curX, curY, 3f, fill)
            stroke.color = c(A_TXT1)
            canvas.drawCircle(curX, curY, 3f, stroke)
        }

        // pills
        stroke.color = c(A_LINE)
        rect.set(LOOP_X0, PILL_Y0, LOOP_X1, PILL_Y1)
        canvas.drawRoundRect(rect, PILL_R, PILL_R, stroke)
        rect.set(IOB_X0, PILL_Y0, IOB_X1, PILL_Y1)
        canvas.drawRoundRect(rect, PILL_R, PILL_R, stroke)
        stroke.strokeWidth = if (lowBit) 1f else 2f
        stroke.color = if (lowBit) Color.WHITE else if (loopLevel == null) A_TXT3 else levelColour(loopLevel, LOOP_OK, LOOP_WARN, LOOP_BAD)
        canvas.drawCircle(LOOP_X0 + 20f, PILL_Y, 5f, stroke)
        drawPillTexts(canvas, c(A_TXT1), c(A_TXT3), if (lowBit) loopTextLowBit else loopText, aa, c(A_TXT1))

        drawFooter(canvas, ambient = true, aa = aa)
    }

    private fun levelColour(level: Level?, ok: Int, warn: Int, bad: Int) = when (level) {
        null         -> TXT3
        Level.NORMAL -> ok
        Level.WARN   -> warn
        Level.ALERT  -> bad
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun historyShader(r: Range): Shader =
        historyShaders[r.ordinal] ?: LinearGradient(
            GlassFaceLogic.SPARK_X0, 0f, GlassFaceLogic.nowX, 0f, HIST_START, colourOf(r), Shader.TileMode.CLAMP
        ).also { historyShaders[r.ordinal] = it }

    // ---------------------------------------------------------------- baked bitmaps (software canvas: blur works)

    private fun recycleBitmaps() {
        for (i in backgrounds.indices) {
            backgrounds[i]?.recycle(); backgrounds[i] = null
        }
        ballIdle?.recycle(); ballIdle = null
        ballArmed?.recycle(); ballArmed = null
    }

    /** Gradient, aurora (range blob + core, violet, blue), dial rim and the glass chrome of the hero card and pills. */
    private fun background(r: Range): Bitmap {
        backgrounds[r.ordinal]?.let { return it }
        // Only the current range stays cached; a change of range (rare) bakes the next one.
        for (i in backgrounds.indices) if (i != r.ordinal) {
            backgrounds[i]?.recycle(); backgrounds[i] = null
        }
        val size = pixelSize.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 450f, size / 450f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 450f, intArrayOf(BG_TOP, BG_MID, BG_BOT), floatArrayOf(0f, 248f / 450f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 450f, 450f, p)
        p.shader = null
        val colour = colourOf(r)
        blob(c, p, 130f, 100f, 320f, 250f, withAlpha(colour, 150), 40f)
        blob(c, p, 150f, 120f, 260f, 220f, withAlpha(colour, 120), 26f)
        blob(c, p, 240f, 220f, 410f, 340f, withAlpha(0xFF5A46C8.toInt(), 85), 44f)
        blob(c, p, 40f, 230f, 180f, 330f, withAlpha(0xFF2A5AC8.toInt(), 60), 44f)
        p.maskFilter = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, 22)
        c.drawCircle(CX, CX, 225f - 3f, p)
        glass(c, p, HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, 35, 11, 60, 100)
        glass(c, p, LOOP_X0, PILL_Y0, LOOP_X1, PILL_Y1, PILL_R, 29, 9, 55, 95)
        glass(c, p, IOB_X0, PILL_Y0, IOB_X1, PILL_Y1, PILL_R, 29, 9, 55, 95)
        backgrounds[r.ordinal] = bmp
        return bmp
    }

    /** Pre-blurred ellipse. [sigma] is the Gaussian sigma of the mockup; BlurMaskFilter takes a radius (~1.73 sigma). */
    private fun blob(c: Canvas, p: Paint, l: Float, t: Float, r: Float, b: Float, colour: Int, sigma: Float) {
        p.reset()
        p.isAntiAlias = true
        p.color = colour
        p.maskFilter = BlurMaskFilter(sigma * 1.73f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(l, t, r, b, p)
    }

    private fun glass(c: Canvas, p: Paint, l: Float, t: Float, r: Float, b: Float, radius: Float, topAlpha: Int, bottomAlpha: Int, edge: Int, hairline: Int) {
        p.reset()
        p.isAntiAlias = true
        p.shader = LinearGradient(0f, t, 0f, b, withAlpha(Color.WHITE, topAlpha), withAlpha(Color.WHITE, bottomAlpha), Shader.TileMode.CLAMP)
        c.drawRoundRect(l, t, r, b, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, edge)
        c.drawRoundRect(l, t, r, b, radius, radius, p)
        p.color = withAlpha(Color.WHITE, hairline)
        c.drawLine(l + radius, t + 1f, r - radius, t + 1f, p)
    }

    /** Sphere, halo, specular highlight and rim of the ball, centred in a (2*BALL_BOX)² design box. */
    private fun ballBitmap(armed: Boolean): Bitmap {
        (if (armed) ballArmed else ballIdle)?.let { return it }
        val px = (2 * BALL_BOX * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val cx = BALL_BOX
        val r = GlassFaceLogic.BALL_R
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (armed) withAlpha(AMBER_BALL, 120) else withAlpha(0xFF5A96FF.toInt(), 100)
        p.maskFilter = BlurMaskFilter(7f * 1.73f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cx, 32f, p)
        p.maskFilter = null
        val lo = if (armed) 0xFF8A5A10.toInt() else 0xFF28468F.toInt()
        val hi = if (armed) 0xFFFFC864.toInt() else 0xFF7AAFFF.toInt()
        p.shader = RadialGradient(cx - 4f, cx - 8f, r + 8f, hi, lo, Shader.TileMode.CLAMP)
        p.alpha = 235
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.alpha = 255
        p.color = withAlpha(Color.WHITE, 75)
        p.maskFilter = BlurMaskFilter(3f * 1.73f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(cx - 17f, cx - 26f, cx + 5f, cx - 13f, p)
        p.maskFilter = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, 150)
        c.drawCircle(cx, cx, r, p)
        if (armed) ballArmed = bmp else ballIdle = bmp
        return bmp
    }

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TAP_UP || isAmbient) return
        if (eventTime == lastTapEventTime) return // duplicate delivery of the same tap
        lastTapEventTime = eventTime
        val size = min(faceRect.width(), faceRect.height())
        val s = size / 450f
        if (s <= 0f) return
        val dx = (x - (faceRect.width() - size) / 2f) / s
        val dy = (y - (faceRect.height() - size) / 2f) / s

        val now = System.currentTimeMillis()
        val enabled = ballShown && bolusAllowed(now)
        val inside = GlassFaceLogic.ballHit(dx, dy)
        when (gesture.onTap(inside, enabled)) {
            BolusTapGesture.Decision.ARM    -> {
                armedAt = now
                vibrate()
                handler.removeCallbacks(armedTick)
                handler.post(armedTick)
                return
            }

            BolusTapGesture.Decision.SEND   -> {
                handler.removeCallbacks(armedTick)
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "GlassWatchface: double-tap bolus request $amount U")
                // Request only, exactly as BolusActivity: the phone checks it and the watch shows the confirmation.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                vibrate()
                flashUntil = now + FLASH_MS
                handler.postDelayed(flashEnd, FLASH_MS + 10)
                invalidate()
                return
            }

            BolusTapGesture.Decision.DISARM -> {
                handler.removeCallbacks(armedTick)
                invalidate()
            }

            BolusTapGesture.Decision.IGNORE -> Unit
        }
        if (inside && enabled) return

        // Double-tap on the hero card opens the main menu, like the other AAPS faces.
        if (dx in HERO_X0..HERO_X1 && dy in HERO_Y0..HERO_Y1) {
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
            aapsLogger.error(LTag.WEAR, "GlassWatchface vibrate failed", e)
        }
    }

    companion object {

        private const val TAP_UP = 2

        private const val FRAME_MS = 33L
        private const val FLASH_MS = 150L

        private const val CX = 225f
        private const val TIME_Y = 58f
        private const val DATE_Y = 92f
        private const val HERO_X0 = 92f
        private const val HERO_Y0 = 110f
        private const val HERO_X1 = 358f
        private const val HERO_Y1 = 240f
        private const val HERO_R = 30f
        private const val BG_X = 184f
        private const val BG_Y = 176f
        private const val BG_MAX_WIDTH = 176f
        private const val CHIP_X = 312f
        private const val CHIP_Y = 152f
        private const val CHIP_R = 22f
        private const val META_Y = 200f
        private const val META_RIGHT = 350f
        private const val META_MAX_WIDTH = 84f
        private const val META_SEP = " · "
        private const val PILL_Y0 = 324f
        private const val PILL_Y1 = 366f
        private const val PILL_Y = 345f
        private const val PILL_R = 21f
        private const val LOOP_X0 = 106f
        private const val LOOP_X1 = 219f
        private const val IOB_X0 = 231f
        private const val IOB_X1 = 344f
        private const val FOOTER_Y = 390f
        private const val FOOTER_SEP = "  ·  "
        private const val BALL_BOX = 46f
        private const val RING_R = 34f

        private val BG_TOP = 0xFF141A33.toInt()
        private val BG_MID = 0xFF0B0F22.toInt()
        private val BG_BOT = 0xFF04050B.toInt()
        private val IN_COL = 0xFF78E6BE.toInt()
        private val HIGH_COL = 0xFFFFB840.toInt()
        private val LOW_COL = 0xFFFF5C5C.toInt()
        private val STALE_COL = 0xFF8A93A8.toInt()
        private val TXT1 = 0xFFEEF1F7.toInt()
        private val TXT2 = 0xFF9AA6C4.toInt()
        private val TXT3 = 0xFF6F7A99.toInt()
        private val LABEL_COL = 0xFF8E99B8.toInt()
        private val DELTA_COL = 0xFFC8D2E6.toInt()
        private val WARN_COL = 0xFFFFB840.toInt()
        private val ALERT_COL = 0xFFFF5C5C.toInt()
        private val TT_COL = 0xFFE8D28A.toInt()
        private val LOOP_OK = 0xFF40D68C.toInt()
        private val LOOP_WARN = 0xFFFFB840.toInt()
        private val LOOP_BAD = 0xFFFF5C5C.toInt()
        private val PRED_COL = 0xFFAA82FF.toInt()
        private val HIST_START = 0xFF5AAAFF.toInt()
        private val AMBER_BALL = 0xFFFFB840.toInt()
        private val A_TXT1 = 0xFFE0E0E0.toInt()
        private val A_TXT2 = 0xFF9A9A9A.toInt()
        private val A_TXT3 = 0xFF6E6E6E.toInt()
        private val A_LINE = 0xFF3C3C3C.toInt()
        private val A_HIST = 0xFFB4B4B4.toInt()
    }
}
