package app.aaps.wear.watchfaces.orbit

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.wear.watchface.TapType
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
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ball
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Freshness
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ring
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
import java.util.Locale
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "AAPS Orbit": BG is the sun in the centre, status values are planets on one orbit ring that breaks for the
 * time (12 o'clock) and the date (6 o'clock). A minute moon moves along the ring (the only motion).
 * Colour shows state only. Spec: designs/orbit_spec.md.
 *
 * Drawing: a 450 x 450 design grid scaled to the screen. Everything derived from data (strings, colours, the
 * sparkline path, the moon) is rebuilt only when the data, the minute, the mode or a preference changes; a frame
 * itself allocates nothing. Glows use setShadowLayer (hardware canvas) or bitmaps pre-rendered on a software
 * canvas (BlurMaskFilter does not work on the hardware canvas).
 *
 * Optional bolus button at 9 o'clock (watch preference, default OFF): double tap within 800 ms sends only a bolus
 * REQUEST (ActionBolusPreCheck); the phone checks it and the watch shows the standard confirmation.
 */
@SuppressLint("Deprecated")
class OrbitWatchface : WatchFace() {

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

    // ---------------------------------------------------------------- bolus

    private val clock: () -> Long = { System.currentTimeMillis() }
    private val gesture = BolusTapGesture(clock, windowMs = OrbitBolusGuard.WINDOW_MS, cooldownMs = OrbitBolusGuard.COOLDOWN_MS)
    private val guard = OrbitBolusGuard(clock)
    private var armedAt = 0L
    private var bolusCheck = OrbitBolusGuard.Check(OrbitBolusGuard.State.HIDDEN, OrbitBolusGuard.Reason.PREF_OFF)
    private var bolusAmount = BolusTapGesture.DEFAULT_AMOUNT
    private var bolusAmountText = ""
    private val countdownTick = object : Runnable {
        override fun run() {
            if (gesture.isArmed) {
                invalidate()
                handler.postDelayed(this, 100L)
            } else {
                gesture.expireIfDue()
                invalidate()
            }
        }
    }

    // ---------------------------------------------------------------- derived state (rebuilt on change only)

    private var labels = OrbitFaceLogic.Labels()
    private var builtFor: ComplicationData? = null
    private var builtMinuteKey = -1L
    private var builtAmbient = false
    private var built24h = false
    private var dirty = true

    private var scale = 1f
    private var timeText = ""
    private var dateText = ""
    private var datePrefix = ""
    private var warnText: String? = null
    private var dateSize = 18f
    private var gapT = 10f
    private var gapD = 10f
    private var phoneLost = false
    private var phoneText = ""
    private var freshness = Freshness.NONE
    private var rangeColour = GREEN
    private var heroText = "---"
    private var arrowText = ""
    private var deltaRow = OrbitFaceLogic.DeltaRow("", "", false)
    private var pillText = ""
    private var ambientOldText = ""
    private val balls = arrayOfNulls<Ball>(5)
    private val ballSize = FloatArray(5)
    private var moon = OrbitFaceLogic.Moon(MoonKind.HIDDEN, 0f)
    private var shiftX = 0f
    private var shiftY = 0f

    // sparkline
    private val sparkPath = Path()
    private val caretX = FloatArray(MAX_POINTS)
    private val caretDir = IntArray(MAX_POINTS)
    private var caretCount = 0
    private var hasNowDot = false
    private var nowX = 0f
    private var nowY = 0f
    private val predX = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private val predY = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private var predCount = 0
    private var lowY = 0f
    private var highY = 0f

    // ---------------------------------------------------------------- paints and shapes, created once

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fm = Paint.FontMetrics()
    private val rect = RectF()
    private val trackRect = RectF(OrbitFaceLogic.CX - OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY - OrbitFaceLogic.ORBIT_R,
                                  OrbitFaceLogic.CX + OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY + OrbitFaceLogic.ORBIT_R)
    private val dash = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)
    private val ballX = FloatArray(5) { OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    private val ballY = FloatArray(5) { OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    private val bolusX = OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.DEG_BOLUS)
    private val bolusY = OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.DEG_BOLUS)
    private val dropPath = Path()
    private val outlinePath = Path()
    private val outlineSegment = Path()
    private var outlineMeasure = PathMeasure()
    private var outlineLength = 0f
    private val caretPath = Path()
    private val warnIcon = Path()

    // pre-rendered bitmaps (software canvas), rebuilt when the scale changes
    private var cacheScale = 0f
    private val corona = arrayOfNulls<Bitmap>(3)
    private val coronaDst = RectF()
    private val shells = arrayOfNulls<Bitmap>(Ring.entries.size)
    private var bolusIdle: Bitmap? = null
    private var bolusArmed: Bitmap? = null
    private val shellDst = RectF()
    private val bolusDst = RectF()

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.ORBIT.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())
        labels = OrbitFaceLogic.Labels(
            profile = getString(R.string.orbit_profile),
            again = getString(R.string.orbit_bolus_again),
            oldFormat = getString(R.string.orbit_old_format),
            noData = getString(R.string.orbit_no_data),
            phoneLost = getString(R.string.orbit_phone_lost),
            loopOff = getString(R.string.orbit_loop_off),
            loopSuspended = getString(R.string.orbit_loop_suspended),
            loopDisconnected = getString(R.string.orbit_loop_disconnected),
            loopOpen = getString(R.string.orbit_loop_open),
            phoneBatteryFormat = getString(R.string.orbit_warn_phone_battery),
            reservoirFormat = getString(R.string.orbit_warn_reservoir),
            pumpBattery = getString(R.string.orbit_warn_pump_battery)
        )
        buildShapes()

        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect {
                data = it
                invalidate()
            }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { prefsChanged() }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { prefsChanged() }

        rxBus.send(EventWearToMobile(ActionResendData("OrbitWatchface::onCreate")))
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownTick)
        disposable.clear()
        watchfaceScope.cancel()
        for (i in corona.indices) corona[i]?.recycle()
        for (i in shells.indices) shells[i]?.recycle()
        bolusIdle?.recycle()
        bolusArmed?.recycle()
        super.onDestroy()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        if (watchMode != WatchMode.INTERACTIVE) disarm()
        dirty = true
    }

    private fun prefsChanged() {
        dirty = true
        refreshBolusCheck()
        if (bolusCheck.state != OrbitBolusGuard.State.READY) disarm()
        invalidate()
    }

    private fun disarm() {
        gesture.reset()
        handler.removeCallbacks(countdownTick)
    }

    // ---------------------------------------------------------------- preferences

    private fun refreshBolusCheck() {
        val amount = BolusTapGesture.parseAmount(sp.getString(R.string.key_orbit_bolus_amount, "0.5"))
        if (amount != bolusAmount || bolusAmountText.isEmpty()) {
            bolusAmount = amount
            bolusAmountText = String.format(Locale.US, "%.1f U", amount)
        }
        bolusCheck = guard.check(
            prefOn = sp.getBoolean(R.string.key_orbit_bolus_double_tap, false),
            ambient = isAmbient,
            wearControl = preferences.get(BooleanKey.WearControl),
            amount = bolusAmount,
            maxBolus = sp.getDouble(R.string.key_treatments_safety_max_bolus, OrbitBolusGuard.DEFAULT_MAX_BOLUS),
            lastPhoneMessage = data.lastUpdateTimestamp
        )
    }

    // ---------------------------------------------------------------- derived state

    private fun minuteKey(): Long {
        val t = latestTime
        return ((t.year * 13L + t.month) * 32L + t.monthDay) * 1440L + t.hour * 60L + t.minute
    }

    private fun rebuildIfNeeded() {
        val d = data
        val key = minuteKey()
        if (!dirty && d === builtFor && key == builtMinuteKey && isAmbient == builtAmbient && is24HourFormat == built24h) return
        dirty = false
        builtFor = d
        builtMinuteKey = key
        builtAmbient = isAmbient
        built24h = is24HourFormat
        rebuild(d, System.currentTimeMillis())
    }

    private fun rebuild(d: ComplicationData, now: Long) {
        val t = latestTime
        val bg = d.bgData
        val st = d.statusData

        timeText = OrbitFaceLogic.timeString(t.hour, t.minute, is24HourFormat)
        dateText = try {
            OrbitFaceLogic.dateString(t.year, t.month + 1, t.monthDay)
        } catch (_: Exception) {
            ""
        }
        warnText = OrbitFaceLogic.warning(st.reservoir, st.battery, pumpBatteryLow = false, labels = labels)
        datePrefix = if (warnText != null) dateText + OrbitFaceLogic.DATE_SEPARATOR else dateText
        setText(18f, tf500)
        val dateWidth = text.measureText(datePrefix) + (warnText?.let { text.measureText(it) } ?: 0f)
        dateSize = OrbitFaceLogic.fitSize(dateWidth, 18f, OrbitFaceLogic.DATE_LINE_MAX_WIDTH)
        gapD = OrbitFaceLogic.gapHalfDeg(dateWidth * dateSize / 18f)
        setText(30f, tf600)
        gapT = OrbitFaceLogic.gapHalfDeg(text.measureText(timeText))

        phoneLost = d.lastUpdateTimestamp <= 0L || now - d.lastUpdateTimestamp > OrbitFaceLogic.PHONE_LOST_MS
        phoneText = labels.phoneLost

        val age = OrbitFaceLogic.bgAgeMinutes(bg.timeStamp, now)
        freshness = OrbitFaceLogic.freshness(age)
        rangeColour = when (OrbitFaceLogic.range(bg.sgvLevel)) {
            Range.IN   -> GREEN
            Range.HIGH -> AMBER
            Range.LOW  -> RED
        }
        heroText = if (bg.timeStamp > 0L) bg.sgvString else "---"
        arrowText = if (bg.timeStamp > 0L && bg.slopeArrow.isNotBlank() && bg.slopeArrow != "--") bg.slopeArrow + "︎" else ""
        deltaRow = OrbitFaceLogic.deltaRow(bg.delta, age)
        pillText = OrbitFaceLogic.oldPillText(age, labels)
        ambientOldText = OrbitFaceLogic.oldAmbientText(age, labels)

        val temps = d.treatmentData.temps
        val starts = LongArray(temps.size) { temps[it].startTime }
        val ends = LongArray(temps.size) { temps[it].endTime }
        balls[OrbitFaceLogic.IDX_LOOP] = OrbitFaceLogic.loopBall(st.openApsStatus, st.externalStatus, now, labels)
        balls[OrbitFaceLogic.IDX_IOB] = OrbitFaceLogic.iobBall(st.iobSum)
        balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.basalBall(st.currentBasal, OrbitFaceLogic.tbrRunning(starts, ends, now))
        balls[OrbitFaceLogic.IDX_COB] = OrbitFaceLogic.cobBall(st.cob)
        balls[OrbitFaceLogic.IDX_TT] = OrbitFaceLogic.ttBall(st.tempTarget, st.tempTargetLevel, labels)
        for (i in balls.indices) {
            val b = balls[i] ?: continue
            if (b.word) {
                setText(17f, tf700)
                ballSize[i] = OrbitFaceLogic.fitSize(text.measureText(b.value), 17f, OrbitFaceLogic.BALL_VALUE_HARD_MAX)
            } else {
                setText(22f, tf600)
                ballSize[i] = OrbitFaceLogic.ballValueSize(text.measureText(b.value))
            }
        }

        refreshBolusCheck()
        moon = OrbitFaceLogic.moon(t.minute, gapT, gapD, bolusCheck.state != OrbitBolusGuard.State.HIDDEN)
        shiftX = OrbitFaceLogic.burnInShiftX(t.minute)
        shiftY = OrbitFaceLogic.burnInShiftY(t.minute)

        buildSparkline(d, now)
    }

    private fun buildSparkline(d: ComplicationData, now: Long) {
        val bg = d.bgData
        sparkPath.rewind()
        caretCount = 0
        predCount = 0
        val low = bg.low.takeIf { it > 0 } ?: DEFAULT_LOW_MGDL
        val high = bg.high.takeIf { it > 0 } ?: DEFAULT_HIGH_MGDL
        lowY = OrbitFaceLogic.sparkY(low)
        highY = OrbitFaceLogic.sparkY(high)

        val from = now - OrbitFaceLogic.SPARK_HISTORY_MS
        val points = d.graphData.entries.asSequence()
            .filter { it.timeStamp in from..now && it.sgv > 0 }
            .sortedBy { it.timeStamp }
            .toMutableList()
        if (bg.timeStamp in from..now && bg.sgv > 0 && (points.isEmpty() || points.last().timeStamp < bg.timeStamp)) points.add(bg)
        var first = true
        for (p in points) {
            val x = OrbitFaceLogic.sparkX(p.timeStamp, now)
            val y = OrbitFaceLogic.sparkY(p.sgv)
            if (first) sparkPath.moveTo(x, y) else sparkPath.lineTo(x, y)
            first = false
            val c = OrbitFaceLogic.sparkClamp(p.sgv)
            if (c != 0 && caretCount < MAX_POINTS) {
                caretX[caretCount] = x
                caretDir[caretCount] = c
                caretCount++
            }
        }
        hasNowDot = bg.timeStamp in from..now && bg.sgv > 0
        if (hasNowDot) {
            nowX = OrbitFaceLogic.sparkX(bg.timeStamp, now)
            nowY = OrbitFaceLogic.sparkY(bg.sgv)
        }
        if (hasNowDot && !OrbitFaceLogic.isOld(freshness)) {
            val preds = d.treatmentData.predictions
            for (k in 1..OrbitFaceLogic.PREDICTION_DOTS) {
                val target = bg.timeStamp + k * OrbitFaceLogic.PREDICTION_STEP_MS
                var best: EventData.SingleBg? = null
                var bestD = Long.MAX_VALUE
                for (p in preds) {
                    val dist = kotlin.math.abs(p.timeStamp - target)
                    if (dist < bestD && p.sgv > 0) {
                        bestD = dist
                        best = p
                    }
                }
                if (best != null && bestD <= OrbitFaceLogic.PREDICTION_STEP_MS / 2) {
                    predX[predCount] = OrbitFaceLogic.predictionX(k)
                    predY[predCount] = OrbitFaceLogic.sparkY(best.sgv)
                    predCount++
                }
            }
        }
    }

    // ---------------------------------------------------------------- shapes and bitmaps

    private fun buildShapes() {
        // drop glyph, 15 px tall, centred on (bolusX, bolusY - 12)
        val h = 15f
        val gx = bolusX
        val gy = bolusY - 12f
        val r = h * 0.36f
        val top = gy - h / 2f
        val cy = gy + h / 2f - r
        val dist = cy - top
        val s = r / dist
        val beta = Math.toDegrees(asin(s.toDouble())).toFloat()
        val tx = r * sqrt(1f - s * s)
        dropPath.rewind()
        dropPath.moveTo(gx, top)
        dropPath.lineTo(gx + tx, cy - r * s)
        rect.set(gx - r, cy - r, gx + r, cy + r)
        dropPath.arcTo(rect, -beta, 180f + 2f * beta, false)
        dropPath.close()

        // bolus outline, clockwise from top-centre (countdown)
        val bh = OrbitFaceLogic.BOLUS_HALF + 0.5f
        val cr = BOLUS_CORNER
        val x = bolusX
        val y = bolusY
        outlinePath.rewind()
        outlinePath.moveTo(x, y - bh)
        outlinePath.lineTo(x + bh - cr, y - bh)
        rect.set(x + bh - 2 * cr, y - bh, x + bh, y - bh + 2 * cr); outlinePath.arcTo(rect, -90f, 90f, false)
        outlinePath.lineTo(x + bh, y + bh - cr)
        rect.set(x + bh - 2 * cr, y + bh - 2 * cr, x + bh, y + bh); outlinePath.arcTo(rect, 0f, 90f, false)
        outlinePath.lineTo(x - bh + cr, y + bh)
        rect.set(x - bh, y + bh - 2 * cr, x - bh + 2 * cr, y + bh); outlinePath.arcTo(rect, 90f, 90f, false)
        outlinePath.lineTo(x - bh, y - bh + cr)
        rect.set(x - bh, y - bh, x - bh + 2 * cr, y - bh + 2 * cr); outlinePath.arcTo(rect, 180f, 90f, false)
        outlinePath.lineTo(x, y - bh)
        outlineMeasure = PathMeasure(outlinePath, false)
        outlineLength = outlineMeasure.length

        // warning triangle of the stale pill, 14 x 12, centred on (0, 0); translated when drawn
        warnIcon.rewind()
        warnIcon.moveTo(0f, -6f)
        warnIcon.lineTo(7f, 6f)
        warnIcon.lineTo(-7f, 6f)
        warnIcon.close()

        // up caret (▲) 5 px, centred on (0, 0); mirrored for ▼
        caretPath.rewind()
        caretPath.moveTo(0f, -2.5f)
        caretPath.lineTo(2.9f, 2.5f)
        caretPath.lineTo(-2.9f, 2.5f)
        caretPath.close()
    }

    private fun ensureCaches(s: Float) {
        if (s == cacheScale && corona[0] != null) return
        cacheScale = s
        // corona: ellipse 240 x 200 at (225, 215), alpha 34, 60 px blur
        val cw = 240f + 2 * CORONA_BLUR
        val ch = 200f + 2 * CORONA_BLUR
        coronaDst.set(225f - cw / 2f, 215f - ch / 2f, 225f + cw / 2f, 215f + ch / 2f)
        val colours = intArrayOf(GREEN, AMBER, RED)
        for (i in 0..2) {
            corona[i]?.recycle()
            // blurred anyway: half resolution is enough
            corona[i] = softBitmap(cw, ch, s * 0.5f) { c ->
                fill.reset(); fill.isAntiAlias = true
                fill.color = withAlpha(colours[i], 34)
                fill.maskFilter = BlurMaskFilter(CORONA_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)
                c.drawOval(CORONA_BLUR, CORONA_BLUR, CORONA_BLUR + 240f, CORONA_BLUR + 200f, fill)
                fill.maskFilter = null
            }
        }
        // ball shells per ring colour
        val side = (OrbitFaceLogic.BALL_R + SHELL_PAD) * 2f
        for (ring in Ring.entries) {
            shells[ring.ordinal]?.recycle()
            shells[ring.ordinal] = softBitmap(side, side, s) { c ->
                val m = side / 2f
                val r = OrbitFaceLogic.BALL_R
                fill.reset(); fill.isAntiAlias = true; fill.color = BALL_FILL
                c.drawCircle(m, m, r, fill)
                if (ring != Ring.NEUTRAL && ring != Ring.DIM) {
                    fill.color = withAlpha(ringColour(ring), 26)
                    fill.maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
                    c.drawOval(m - r * 0.7f, m - r * 0.92f, m + r * 0.7f, m - r * 0.1f, fill)
                    fill.maskFilter = null
                }
                stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
                stroke.strokeWidth = 2f; stroke.color = ringColour(ring)
                c.drawCircle(m, m, r - 1f, stroke)
            }
        }
        // bolus button shells
        val bs = OrbitFaceLogic.BOLUS_HALF * 2f + 2 * BOLUS_PAD
        bolusIdle?.recycle()
        bolusIdle = softBitmap(bs, bs, s) { c ->
            val a = BOLUS_PAD
            val b = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF * 2f
            fill.reset(); fill.isAntiAlias = true; fill.color = BALL_FILL
            c.drawRoundRect(a, a, b, b, BOLUS_CORNER, BOLUS_CORNER, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2f; stroke.color = withAlpha(INDIGO, 180)
            c.drawRoundRect(a + 1f, a + 1f, b - 1f, b - 1f, BOLUS_CORNER - 1f, BOLUS_CORNER - 1f, stroke)
        }
        bolusArmed?.recycle()
        bolusArmed = softBitmap(bs, bs, s) { c ->
            val a = BOLUS_PAD
            val b = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF * 2f
            fill.reset(); fill.isAntiAlias = true
            fill.color = withAlpha(ARMED_FILL, 150)
            fill.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(a, a, b, b, BOLUS_CORNER, BOLUS_CORNER, fill)
            fill.maskFilter = null
            fill.color = ARMED_FILL
            c.drawRoundRect(a, a, b, b, BOLUS_CORNER, BOLUS_CORNER, fill)
        }
    }

    private inline fun softBitmap(w: Float, h: Float, s: Float, draw: (Canvas) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        draw(c)
        return bmp
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        scale = min(w, h) / 450f
        canvas.drawColor(Color.BLACK)
        try {
            rebuildIfNeeded()
            canvas.save()
            canvas.translate((w - 450f * scale) / 2f, (h - 450f * scale) / 2f)
            canvas.scale(scale, scale)
            if (isAmbient) {
                canvas.translate(shiftX, shiftY)
                drawAmbient(canvas)
            } else {
                ensureCaches(scale)
                drawInteractive(canvas)
            }
            canvas.restore()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "OrbitWatchface draw failed", e)
        }
    }

    private fun drawInteractive(canvas: Canvas) {
        val old = OrbitFaceLogic.isOld(freshness)
        val heroColour = if (old) DIM_TXT else rangeColour

        // 1. corona
        if (!old) {
            val bmp = corona[when (rangeColour) { AMBER -> 1; RED -> 2; else -> 0 }]
            if (bmp != null) canvas.drawBitmap(bmp, null, coronaDst, bmpPaint)
        }

        // 2. orbit track with the time and date gaps
        drawTrack(canvas, 1.5f, TRACK, true)

        // 3. sparkline
        setStroke(1f, withAlpha(GREEN, 120), true)
        stroke.pathEffect = dash
        canvas.drawLine(OrbitFaceLogic.SPARK_X0, highY, OrbitFaceLogic.SPARK_X1, highY, stroke)
        canvas.drawLine(OrbitFaceLogic.SPARK_X0, lowY, OrbitFaceLogic.SPARK_X1, lowY, stroke)
        stroke.pathEffect = null
        setStroke(2.5f, heroColour, true)
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeCap = Paint.Cap.ROUND
        if (!old) stroke.setShadowLayer(4f, 0f, 0f, withAlpha(heroColour, 150))
        canvas.drawPath(sparkPath, stroke)
        stroke.clearShadowLayer()
        drawCarets(canvas, heroColour, true)
        setFill(withAlpha(VIOLET, 220), true)
        for (i in 0 until predCount) canvas.drawCircle(predX[i], predY[i], 2.2f, fill)
        if (hasNowDot) {
            if (old) {
                setStroke(1.2f, NOW_HOLLOW, true)
                canvas.drawCircle(nowX, nowY, 4f, stroke)
            } else {
                setFill(Color.WHITE, true)
                canvas.drawCircle(nowX, nowY, 4f, fill)
            }
        }

        // 4. hero BG + arrow
        drawHero(canvas, heroColour, glow = !old, ambient = false)

        // 5. delta row or stale pill
        if (old) drawPill(canvas) else drawDeltaRow(canvas, DELTA_C, if (deltaRow.ageAmber) AMBER else DELTA_C, true)

        // 6. balls (and the moon rim light)
        for (i in balls.indices) drawBall(canvas, i)
        if (moon.kind == MoonKind.RIM && moon.ball >= 0) {
            setStroke(1.6f, withAlpha(Color.WHITE, 220), true)
            val r = OrbitFaceLogic.BALL_R + 1f
            val bx = ballX[moon.ball]
            val by = ballY[moon.ball]
            rect.set(bx - r, by - r, bx + r, by + r)
            canvas.drawArc(rect, moon.rimDeg - 90f - 22f, 44f, false, stroke)
        }

        // 7. bolus button
        if (bolusCheck.state != OrbitBolusGuard.State.HIDDEN) drawBolus(canvas)

        // 8. time, phone, date
        drawText(canvas, timeText, OrbitFaceLogic.CX, 57f, 30f, tf600, TIME_C, true)
        if (phoneLost) drawPhoneLost(canvas, RED, true)
        drawDateLine(canvas, DATE_C, AMBER, true)

        // 9. minute moon
        if (moon.kind == MoonKind.DOT) {
            setFill(MOON_C, true)
            canvas.drawCircle(OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, moon.deg), OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, moon.deg), 4.5f, fill)
        }
    }

    private fun drawAmbient(canvas: Canvas) {
        val aa = !lowBitAmbient
        val old = OrbitFaceLogic.isOld(freshness)
        drawTrack(canvas, 1f, amb(0xFF4A4A4A.toInt()), aa)

        // sparkline: history only
        setStroke(1.5f, amb(0xFF7A7A7A.toInt()), aa)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(sparkPath, stroke)
        drawCarets(canvas, amb(0xFF7A7A7A.toInt()), aa)
        if (hasNowDot) {
            setStroke(1.2f, amb(NOW_HOLLOW), aa)
            canvas.drawCircle(nowX, nowY, 4f, stroke)
        }

        drawHero(canvas, amb(0xFFD6D6D6.toInt()), glow = false, ambient = true)
        if (old) drawText(canvas, ambientOldText, OrbitFaceLogic.CX, 251f, 19f, tf600, amb(0xFFBDBDBD.toInt()), aa)
        else drawDeltaRow(canvas, amb(0xFF9A9A9A.toInt()), amb(0xFF9A9A9A.toInt()), aa)

        for (i in balls.indices) {
            val b = balls[i] ?: continue
            setFill(Color.BLACK, aa)
            canvas.drawCircle(ballX[i], ballY[i], OrbitFaceLogic.BALL_R, fill)
            setStroke(1.2f, amb(0xFF6E6E6E.toInt()), aa)
            canvas.drawCircle(ballX[i], ballY[i], OrbitFaceLogic.BALL_R, stroke)
            val size = ballSize[i]
            drawText(canvas, b.value, ballX[i], ballY[i] - 7f, size, if (size >= 22f) tf400 else tf500, amb(0xFFC8C8C8.toInt()), aa)
            drawText(canvas, b.label, ballX[i], ballY[i] + 15f, 14f, tf600, amb(0xFF9A9A9A.toInt()), aa, LABEL_TRACKING)
        }

        drawText(canvas, timeText, OrbitFaceLogic.CX, 57f, 30f, tf600, amb(0xFFBDBDBD.toInt()), aa)
        if (phoneLost) drawPhoneLost(canvas, amb(0xFFBDBDBD.toInt()), aa)
        drawDateLine(canvas, amb(0xFF8E8E8E.toInt()), amb(0xFFBDBDBD.toInt()), aa)
    }

    private fun drawTrack(canvas: Canvas, width: Float, colour: Int, aa: Boolean) {
        setStroke(width, colour, aa)
        // canvas angles start at 3 o'clock: clockwise-from-12 minus 90
        canvas.drawArc(trackRect, gapT - 90f, 180f - gapD - gapT, false, stroke)
        canvas.drawArc(trackRect, 180f + gapD - 90f, 180f - gapD - gapT, false, stroke)
    }

    private fun drawCarets(canvas: Canvas, colour: Int, aa: Boolean) {
        if (caretCount == 0) return
        setFill(colour, aa)
        for (i in 0 until caretCount) {
            canvas.save()
            if (caretDir[i] > 0) canvas.translate(caretX[i], OrbitFaceLogic.SPARK_Y_TOP - 4f)
            else {
                canvas.translate(caretX[i], OrbitFaceLogic.SPARK_Y_BOTTOM + 4f)
                canvas.scale(1f, -1f)
            }
            canvas.drawPath(caretPath, fill)
            canvas.restore()
        }
    }

    private fun drawHero(canvas: Canvas, colour: Int, glow: Boolean, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        setText(108f, if (ambient) tf300 else tf700, aa)
        val w = text.measureText(heroText)
        val size = OrbitFaceLogic.fitSize(w, 108f, OrbitFaceLogic.HERO_MAX_WIDTH)
        text.textSize = size
        val heroW = w * size / 108f
        text.color = colour
        if (glow) text.setShadowLayer(12f, 0f, 0f, withAlpha(colour, 120))
        canvas.drawText(heroText, HERO_X, baseline(191f), text)
        text.clearShadowLayer()
        val right = HERO_X + heroW / 2f
        var arrowRight = right
        if (arrowText.isNotEmpty()) {
            setText(44f, if (ambient) tf400 else tf600, aa)
            text.color = colour
            val ax = right + 24f
            canvas.drawText(arrowText, ax, baseline(193f), text)
            arrowRight = ax + text.measureText(arrowText) / 2f
        }
        if (freshness == Freshness.STRUCK) {
            setStroke(if (ambient) 1.5f else 3f, colour, aa)
            canvas.drawLine(HERO_X - heroW / 2f - 6f, 195f, arrowRight, 195f, stroke)
        }
    }

    private fun drawDeltaRow(canvas: Canvas, colour: Int, ageColour: Int, aa: Boolean) {
        setText(21f, tf500, aa)
        val wl = text.measureText(deltaRow.deltaPart)
        val wa = text.measureText(deltaRow.agePart)
        val size = OrbitFaceLogic.fitSize(wl + wa, 21f, OrbitFaceLogic.DELTA_ROW_MAX_WIDTH)
        text.textSize = size
        val k = size / 21f
        text.textAlign = Paint.Align.LEFT
        val x0 = OrbitFaceLogic.CX - (wl + wa) * k / 2f
        val y = baseline(251f)
        text.color = colour
        canvas.drawText(deltaRow.deltaPart, x0, y, text)
        text.color = ageColour
        canvas.drawText(deltaRow.agePart, x0 + wl * k, y, text)
    }

    private fun drawPill(canvas: Canvas) {
        setText(19f, tf600)
        val iconW = 14f + 6f
        val tw = text.measureText(pillText)
        val half = (iconW + tw) / 2f + 14f
        rect.set(OrbitFaceLogic.CX - half, 251f - 15f, OrbitFaceLogic.CX + half, 251f + 15f)
        setFill(PILL_FILL, true)
        canvas.drawRoundRect(rect, 15f, 15f, fill)
        setStroke(1.5f, RED, true)
        canvas.drawRoundRect(rect, 15f, 15f, stroke)
        // icon
        val ix = OrbitFaceLogic.CX - (iconW + tw) / 2f + 7f
        canvas.save()
        canvas.translate(ix, 250f)
        setStroke(1.6f, PILL_TEXT, true)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(warnIcon, stroke)
        canvas.drawLine(0f, -1.5f, 0f, 2f, stroke)
        canvas.drawPoint(0f, 4.2f, stroke)
        canvas.restore()
        setText(19f, tf600)
        text.textAlign = Paint.Align.LEFT
        text.color = PILL_TEXT
        canvas.drawText(pillText, ix + 7f + 6f, baseline(251f), text)
    }

    private fun drawBall(canvas: Canvas, i: Int) {
        val b = balls[i] ?: return
        val x = ballX[i]
        val y = ballY[i]
        val shell = shells[b.ring.ordinal]
        if (shell != null) {
            val half = OrbitFaceLogic.BALL_R + SHELL_PAD
            shellDst.set(x - half, y - half, x + half, y + half)
            canvas.drawBitmap(shell, null, shellDst, bmpPaint)
        }
        val dim = b.ring == Ring.DIM
        val size = ballSize[i]
        val tf = if (b.word || size < 22f) tf700 else tf600
        drawText(canvas, b.value, x, y - 7f, size, tf, if (dim) DIM_TXT else VAL_TXT, true)
        val labelColour = when (b.ring) {
            Ring.DIM     -> DIM_TXT
            Ring.NEUTRAL -> LBL_NEUTRAL
            else         -> withAlpha(ringColour(b.ring), 235)
        }
        drawText(canvas, b.label, x, y + 15f, 14f, tf600, labelColour, true, LABEL_TRACKING)
    }

    private fun drawBolus(canvas: Canvas) {
        val armed = gesture.isArmed && bolusCheck.state == OrbitBolusGuard.State.READY
        val half = OrbitFaceLogic.BOLUS_HALF + BOLUS_PAD
        bolusDst.set(bolusX - half, bolusY - half, bolusX + half, bolusY + half)
        bmpPaint.alpha = if (bolusCheck.state == OrbitBolusGuard.State.BLOCKED) BLOCKED_ALPHA else 255
        (if (armed) bolusArmed else bolusIdle)?.let { canvas.drawBitmap(it, null, bolusDst, bmpPaint) }
        bmpPaint.alpha = 255
        if (armed) {
            setStroke(3.5f, withAlpha(Color.WHITE, 60), true)
            canvas.drawPath(outlinePath, stroke)
            val remaining = (1f - (System.currentTimeMillis() - armedAt).toFloat() / OrbitBolusGuard.WINDOW_MS).coerceIn(0f, 1f)
            outlineSegment.rewind()
            if (outlineMeasure.getSegment(0f, outlineLength * remaining, outlineSegment, true)) {
                stroke.color = Color.WHITE
                canvas.drawPath(outlineSegment, stroke)
            }
            drawText(canvas, bolusAmountText, bolusX, bolusY - 8f, 20f, tf700, Color.WHITE, true)
            drawText(canvas, labels.again, bolusX, bolusY + 13f, 14f, tf600, Color.WHITE, true)
        } else {
            val blocked = bolusCheck.state == OrbitBolusGuard.State.BLOCKED
            setFill(withAlpha(INDIGO, if (blocked) BLOCKED_ALPHA else 230), true)
            canvas.drawPath(dropPath, fill)
            drawText(canvas, bolusAmountText, bolusX, bolusY + 11f, 19f, tf700, if (blocked) withAlpha(BOLUS_TEXT, BLOCKED_ALPHA) else BOLUS_TEXT, true)
        }
    }

    private fun drawPhoneLost(canvas: Canvas, colour: Int, aa: Boolean) {
        setText(15f, tf600, aa)
        val tw = text.measureText(phoneText)
        val xs = OrbitFaceLogic.CX - (tw + 14f) / 2f
        val y = 87f
        val k = 4.5f
        setStroke(2f, colour, aa)
        canvas.drawLine(xs, y - k, xs + 2 * k, y + k, stroke)
        canvas.drawLine(xs, y + k, xs + 2 * k, y - k, stroke)
        setText(15f, tf600, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        canvas.drawText(phoneText, xs + 14f, baseline(y), text)
    }

    private fun drawDateLine(canvas: Canvas, colour: Int, warnColour: Int, aa: Boolean) {
        val y = OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f)
        val warn = warnText
        if (warn == null) {
            drawText(canvas, dateText, OrbitFaceLogic.CX, y, dateSize, tf500, colour, aa)
            return
        }
        setText(dateSize, tf500, aa)
        text.textAlign = Paint.Align.LEFT
        val w1 = text.measureText(datePrefix)
        val x0 = OrbitFaceLogic.CX - (w1 + text.measureText(warn)) / 2f
        val by = baseline(y)
        text.color = colour
        canvas.drawText(datePrefix, x0, by, text)
        text.color = warnColour
        canvas.drawText(warn, x0 + w1, by, text)
    }

    // ---------------------------------------------------------------- paint helpers (no allocation)

    private fun setFill(colour: Int, aa: Boolean) {
        fill.reset()
        fill.isAntiAlias = aa
        fill.style = Paint.Style.FILL
        fill.color = colour
    }

    private fun setStroke(width: Float, colour: Int, aa: Boolean) {
        stroke.reset()
        stroke.isAntiAlias = aa
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = width
        stroke.color = colour
    }

    private fun setText(size: Float, tf: Typeface, aa: Boolean = true) {
        text.reset()
        text.isAntiAlias = aa
        text.textAlign = Paint.Align.CENTER
        text.typeface = tf
        text.textSize = size
    }

    private fun baseline(centreY: Float): Float {
        text.getFontMetrics(fm)
        return centreY - (fm.ascent + fm.descent) / 2f
    }

    private fun drawText(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, tf: Typeface, colour: Int, aa: Boolean, tracking: Float = 0f) {
        setText(size, tf, aa)
        text.letterSpacing = tracking
        text.color = colour
        canvas.drawText(s, cx, baseline(cy), text)
    }

    /** Ambient grey, or white in low-bit ambient. */
    private fun amb(colour: Int): Int = if (lowBitAmbient) Color.WHITE else colour

    private fun ringColour(ring: Ring): Int = when (ring) {
        Ring.NEUTRAL -> NEUTRAL_RING
        Ring.DIM     -> DIM_RING
        Ring.GREEN   -> GREEN
        Ring.AMBER   -> AMBER
        Ring.RED     -> RED
        Ring.CYAN    -> CYAN
        Ring.TEAL    -> TEAL
        Ring.VIOLET  -> VIOLET
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TapType.UP) return
        if (!guard.acceptTap(eventTime)) return
        val left = (faceRect.width() - 450f * scale) / 2f
        val top = (faceRect.height() - 450f * scale) / 2f
        val dx = (x - left) / scale
        val dy = (y - top) / scale

        refreshBolusCheck()
        val state = bolusCheck.state
        val inside = state != OrbitBolusGuard.State.HIDDEN && BolusTapGesture.isInside(dx, dy, bolusX, bolusY, BOLUS_HIT_R)
        val enabled = !isAmbient && state == OrbitBolusGuard.State.READY
        if (inside && state == OrbitBolusGuard.State.BLOCKED) {
            if (gesture.reset()) handler.removeCallbacks(countdownTick)
            invalidate()
            return
        }
        when (gesture.onTap(inside, enabled)) {
            BolusTapGesture.Decision.ARM    -> {
                armedAt = System.currentTimeMillis()
                vibrate()
                handler.removeCallbacks(countdownTick)
                handler.postDelayed(countdownTick, 100L)
                invalidate()
                return
            }

            BolusTapGesture.Decision.SEND   -> {
                handler.removeCallbacks(countdownTick)
                if (guard.mayRequest()) {
                    guard.recordRequest()
                    aapsLogger.info(LTag.WEAR, "OrbitWatchface: double-tap bolus request $bolusAmount U")
                    // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                    rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(bolusAmount, 0)))
                }
                invalidate()
                return
            }

            BolusTapGesture.Decision.DISARM -> {
                handler.removeCallbacks(countdownTick)
                invalidate()
            }

            BolusTapGesture.Decision.IGNORE -> Unit
        }
        if (inside && enabled) return

        // Double-tap on the hero BG opens the main menu, like the other AAPS faces.
        if (dx in 110f..340f && dy in 140f..240f) {
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

    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L

    private fun vibrate() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "OrbitWatchface vibrate failed", e)
        }
    }

    companion object {

        private const val MAX_POINTS = 64
        private const val HERO_X = 209f
        private const val BOLUS_CORNER = 16f
        private const val BOLUS_PAD = 16f
        private const val BOLUS_HIT_R = 40f
        private const val SHELL_PAD = 6f
        /** Bitmap margin around the corona ellipse, and the blur radius (a 60 px Gaussian of the mockup). */
        private const val CORONA_BLUR = 120f
        private const val CORONA_BLUR_RADIUS = 100f
        private const val LABEL_TRACKING = 0.04f
        private const val BLOCKED_ALPHA = 90
        private const val DEFAULT_LOW_MGDL = 70.0
        private const val DEFAULT_HIGH_MGDL = 180.0

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val TEAL = 0xFF3DDBD9.toInt()
        private const val VIOLET = 0xFFB388FF.toInt()
        private const val NEUTRAL_RING = 0xFF5A6B85.toInt()
        private const val LBL_NEUTRAL = 0xFF9AA6B8.toInt()
        private const val DIM_RING = 0xFF3A4250.toInt()
        private const val DIM_TXT = 0xFF7D8796.toInt()
        private const val BALL_FILL = 0xFF0E131B.toInt()
        private const val VAL_TXT = 0xFFEEF2F7.toInt()
        private const val TRACK = 0xFF2A3446.toInt()
        private const val TIME_C = 0xFFE6ECF4.toInt()
        private const val DATE_C = 0xFFA9B4C4.toInt()
        private const val DELTA_C = 0xFFAAB6C6.toInt()
        private const val INDIGO = 0xFF7888FF.toInt()
        private const val BOLUS_TEXT = 0xFFE8EBFF.toInt()
        private const val ARMED_FILL = 0xFF5B63F5.toInt()
        private const val MOON_C = 0xFFF2F6FC.toInt()
        private const val NOW_HOLLOW = 0xFFC8C8C8.toInt()
        private const val PILL_FILL = 0xFF3A1018.toInt()
        private const val PILL_TEXT = 0xFFFFD6DC.toInt()
    }
}
