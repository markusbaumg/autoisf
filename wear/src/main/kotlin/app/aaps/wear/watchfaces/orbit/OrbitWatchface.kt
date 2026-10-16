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
import android.widget.Toast
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
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.Reason
import app.aaps.wear.watchfaces.orbit.OrbitBolusGuard.State
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ball
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.BgAge
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ring
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import app.aaps.wear.watchfaces.utils.WatchFace
import app.aaps.wear.watchfaces.utils.WatchMode
import app.aaps.wear.watchfaces.utils.WatchfaceViewAdapter.Companion.SelectedWatchFace
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
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
 * Colour shows state only. Geometry: designs/orbit_spec.md; states: designsystem/states.md.
 *
 * Drawing: a 450 x 450 design grid scaled to the screen. Everything derived from data (strings, colours, the
 * sparkline path, the moon, the status slot) is rebuilt only when the data, the minute, the mode or a preference
 * changes; a frame itself allocates nothing. Glows use setShadowLayer (hardware canvas) or bitmaps pre-rendered on
 * a software canvas (BlurMaskFilter does not work on the hardware canvas).
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

    /** CapabilityClient FILTER_REACHABLE, refreshed once per minute. Unknown until the first answer: assume reachable. */
    @Volatile private var phoneReachable = true

    // ---------------------------------------------------------------- bolus

    private val clock: () -> Long = { System.currentTimeMillis() }
    private val gesture = BolusTapGesture(clock, windowMs = OrbitBolusGuard.WINDOW_MS, cooldownMs = OrbitBolusGuard.COOLDOWN_MS)
    private val guard = OrbitBolusGuard(clock)
    private var armedAt = 0L
    private var bolusCheck = OrbitBolusGuard.Check(State.HIDDEN, Reason.PREF_OFF)
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
    private val pendingEnd = Runnable {
        dirty = true
        invalidate()
    }

    // ---------------------------------------------------------------- derived state (rebuilt on change only)

    private var labels = OrbitFaceLogic.Labels()
    private var builtFor: ComplicationData? = null
    private var builtMinuteKey = -1L
    private var builtAmbient = false
    private var built24h = false
    private var builtReachable = true
    private var dirty = true

    private var scale = 1f
    private var timeText = ""
    private var dateText = ""
    private var dateSize = 18f
    private var gapT = 10f
    private var gapD = 10f
    private var bgAge = BgAge.NONE
    private var range = Range.STALE
    private var rangeColour = STALE_C
    private var heroColour = STALE_C
    private var heroText = "---"
    private var arrowText = ""
    private var deltaRow = OrbitFaceLogic.DeltaRow("", "", false)
    private var ambientOldText = ""
    private var slot: Slot? = null
    private var slotInDeltaRow = false
    private var phoneGlyph = false
    private var firstStart = false
    private val balls = arrayOfNulls<Ball>(5)
    private val ballShown = BooleanArray(5)
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
    private var sparkTop = OrbitFaceLogic.SPARK_Y_TOP
    private var sparkBottom = OrbitFaceLogic.SPARK_Y_BOTTOM

    // ---------------------------------------------------------------- paints and shapes, created once

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fm = Paint.FontMetrics()
    private val rect = RectF()
    private val trackRect = RectF(
        OrbitFaceLogic.CX - OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY - OrbitFaceLogic.ORBIT_R,
        OrbitFaceLogic.CX + OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY + OrbitFaceLogic.ORBIT_R
    )
    private val dash = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    private val ringDash = DashPathEffect(floatArrayOf(5f, 4f), 0f)
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
            again = getString(R.string.orbit_bolus_again),
            cob = getString(R.string.orbit_cob),
            minutesFormat = getString(R.string.orbit_minutes).replace("%1\$d", "%d"),
            hoursFormat = getString(R.string.orbit_hours).replace("%1\$d", "%d"),
            daysFormat = getString(R.string.orbit_days).replace("%1\$d", "%d"),
            old = getString(R.string.orbit_old),
            waiting = getString(R.string.orbit_waiting),
            phoneLostFormat = getString(R.string.orbit_phone_lost).replace("%1\$s", "%s"),
            noDataFormat = getString(R.string.orbit_no_data).replace("%1\$s", "%s"),
            noProfile = getString(R.string.orbit_no_profile),
            loopOff = getString(R.string.orbit_loop_off),
            loopAgeFormat = getString(R.string.orbit_loop_age).replace("%1\$s", "%s"),
            loopOffShort = getString(R.string.orbit_loop_off_short),
            loopOpen = getString(R.string.orbit_loop_open),
            pumpOnly = getString(R.string.orbit_pump_only),
            reservoirFormat = getString(R.string.orbit_reservoir).replace("%1\$s", "%s"),
            phoneBatteryFormat = getString(R.string.orbit_phone_battery).replace("%1\$s", "%s")
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

        refreshPhoneReachable()
        rxBus.send(EventWearToMobile(ActionResendData("OrbitWatchface::onCreate")))
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownTick)
        handler.removeCallbacks(pendingEnd)
        disposable.clear()
        watchfaceScope.cancel()
        for (i in corona.indices) corona[i]?.recycle()
        for (i in shells.indices) shells[i]?.recycle()
        bolusIdle?.recycle()
        bolusArmed?.recycle()
        super.onDestroy()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        // mode.transition: entering ambient clears the armed state; on exit the ages are recomputed before drawing
        if (watchMode != WatchMode.INTERACTIVE) disarm()
        dirty = true
    }

    private fun prefsChanged() {
        dirty = true
        refreshBolusCheck()
        if (bolusCheck.state != State.IDLE) disarm()
        invalidate()
    }

    private fun disarm() {
        gesture.reset()
        handler.removeCallbacks(countdownTick)
    }

    /** Asynchronous; the answer marks the state dirty only when it changed. */
    private fun refreshPhoneReachable() {
        try {
            Wearable.getCapabilityClient(this)
                .getCapability(DataLayerListenerServiceWear.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    val reachable = info.nodes.isNotEmpty()
                    if (reachable != phoneReachable) {
                        phoneReachable = reachable
                        invalidate()
                    }
                }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "OrbitWatchface capability query failed", e)
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun refreshBolusCheck() {
        val amount = BolusTapGesture.parseAmount(sp.getString(R.string.key_orbit_bolus_amount, "0.5"))
        if (amount != bolusAmount || bolusAmountText.isEmpty()) {
            bolusAmount = amount
            bolusAmountText = OrbitFaceLogic.bolusAmountText(amount, Locale.getDefault())
        }
        val d = data
        bolusCheck = guard.check(
            OrbitBolusGuard.Input(
                prefOn = sp.getBoolean(R.string.key_orbit_bolus_double_tap, false),
                ambient = isAmbient,
                wearControl = preferences.get(BooleanKey.WearControl),
                amount = bolusAmount,
                maxBolus = sp.getDouble(R.string.key_treatments_safety_max_bolus, OrbitBolusGuard.DEFAULT_MAX_BOLUS),
                phoneReachable = phoneReachable,
                lastPhoneMessage = d.lastUpdateTimestamp,
                bgTimeStamp = d.bgData.timeStamp
            )
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
        if (!dirty && d === builtFor && key == builtMinuteKey && isAmbient == builtAmbient && is24HourFormat == built24h &&
            phoneReachable == builtReachable
        ) return
        if (key != builtMinuteKey) refreshPhoneReachable()
        dirty = false
        builtFor = d
        builtMinuteKey = key
        builtAmbient = isAmbient
        built24h = is24HourFormat
        builtReachable = phoneReachable
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
        setText(18f, tf500)
        val dateWidth = text.measureText(dateText)
        dateSize = OrbitFaceLogic.fitSize(dateWidth, 18f, OrbitFaceLogic.DATE_LINE_MAX_WIDTH)
        gapD = OrbitFaceLogic.gapHalfDeg(dateWidth * dateSize / 18f)
        setText(30f, tf600)
        gapT = OrbitFaceLogic.gapHalfDeg(text.measureText(timeText))

        // BG
        val ageMin = OrbitFaceLogic.bgAgeMinutes(bg.timeStamp, now)
        bgAge = OrbitFaceLogic.bgAge(ageMin)
        range = OrbitFaceLogic.range(bg.sgv, bg.sgvLevel, bg.slopeArrow, bg.deltaMgdl, bgAge)
        rangeColour = when (range) {
            Range.IN                      -> GREEN
            Range.HIGH, Range.VERY_HIGH   -> AMBER
            Range.LOW, Range.URGENT_LOW   -> RED
            Range.STALE                   -> STALE_C
        }
        heroColour = when {
            range == Range.STALE      -> STALE_C
            range == Range.URGENT_LOW -> LOW_TXT
            else                      -> rangeColour
        }
        heroText = OrbitFaceLogic.heroText(bg.sgvString, bg.sgv, ageMin)
        arrowText = if (OrbitFaceLogic.isOld(bgAge)) "" else OrbitFaceLogic.arrowText(bg.slopeArrow, bg.deltaMgdl)
        deltaRow = OrbitFaceLogic.deltaRow(bg.delta, ageMin, labels)
        ambientOldText = OrbitFaceLogic.oldAmbientText(ageMin, labels)

        // status
        firstStart = OrbitFaceLogic.firstStart(bg.timeStamp, st.openApsStatus, st.iobSum)
        val noProfile = OrbitFaceLogic.noProfile(st.externalStatus, st.iobSum)
        val loopOff = OrbitFaceLogic.loopOff(st.externalStatus, st.iobSum)
        val loopAge = OrbitFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        val dataAgeMs = if (d.lastUpdateTimestamp > 0L) now - d.lastUpdateTimestamp else Long.MAX_VALUE
        val link = OrbitFaceLogic.phoneLink(phoneReachable, dataAgeMs)
        val dataAgeMin = if (d.lastUpdateTimestamp > 0L) maxOf(0L, dataAgeMs / 60_000L) else null
        slot = OrbitFaceLogic.statusSlot(
            OrbitFaceLogic.SlotInput(
                firstStart = firstStart, link = link, dataAgeMinutes = dataAgeMin, bgAgeMinutes = ageMin, bgAge = bgAge,
                range = range, noProfile = noProfile, loopOff = loopOff,
                loopBad = OrbitFaceLogic.loopHealth(loopAge) == OrbitFaceLogic.LoopHealth.BAD,
                loopAgeMinutes = loopAge, reservoirLevel = st.reservoirLevel, reservoirString = st.reservoirString,
                batteryLevel = st.batteryLevel, battery = st.battery
            ),
            ambient = isAmbient, labels = labels
        )
        phoneGlyph = slot?.tone == Tone.GLYPH
        if (phoneGlyph) slot = null
        slotInDeltaRow = firstStart || OrbitFaceLogic.slotReplacesDelta(slot)
        if (firstStart && slot == null) slotInDeltaRow = false

        // balls
        val temps = d.treatmentData.temps
        val starts = LongArray(temps.size) { temps[it].startTime }
        val ends = LongArray(temps.size) { temps[it].endTime }
        if (firstStart) {
            balls[OrbitFaceLogic.IDX_LOOP] = Ball("--", "LOOP", Ring.DIM, dashed = true)
            balls[OrbitFaceLogic.IDX_IOB] = OrbitFaceLogic.placeholderBall("IOB")
            balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.placeholderBall("BASAL")
            balls[OrbitFaceLogic.IDX_COB] = OrbitFaceLogic.placeholderBall(labels.cob)
            balls[OrbitFaceLogic.IDX_TT] = null
        } else {
            balls[OrbitFaceLogic.IDX_LOOP] = OrbitFaceLogic.loopBall(st.openApsStatus, st.externalStatus, st.iobSum, now, labels)
            balls[OrbitFaceLogic.IDX_IOB] = OrbitFaceLogic.iobBall(st.iobSum)
            balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.basalBall(st.currentBasal, OrbitFaceLogic.tbrRunning(starts, ends, now))
            balls[OrbitFaceLogic.IDX_COB] = OrbitFaceLogic.cobBall(st.cob, labels)
            balls[OrbitFaceLogic.IDX_TT] = OrbitFaceLogic.ttBall(st.tempTarget, st.tempTargetLevel)
        }
        for (i in balls.indices) {
            val b = balls[i]
            ballShown[i] = b != null
            if (b == null) continue
            setText(22f, tf600)
            ballSize[i] = OrbitFaceLogic.ballValueSize(text.measureText(b.value))
        }

        refreshBolusCheck()
        if (bolusCheck.state == State.SENT) {
            handler.removeCallbacks(pendingEnd)
            handler.postDelayed(pendingEnd, guard.pendingLeftMs() + 50L)
        }
        moon = OrbitFaceLogic.moon(t.minute, gapT, gapD, bolusCheck.state != State.HIDDEN, ballShown)
        shiftX = OrbitFaceLogic.burnInShiftX(t.minute)
        shiftY = OrbitFaceLogic.burnInShiftY(t.minute)

        val pillUnder = slot != null && !slotInDeltaRow
        sparkTop = if (pillUnder) OrbitFaceLogic.SPARK_Y_TOP_PILL else OrbitFaceLogic.SPARK_Y_TOP
        sparkBottom = if (pillUnder) OrbitFaceLogic.SPARK_Y_BOTTOM_PILL else OrbitFaceLogic.SPARK_Y_BOTTOM
        buildSparkline(d, now)
    }

    private fun buildSparkline(d: ComplicationData, now: Long) {
        val bg = d.bgData
        sparkPath.rewind()
        caretCount = 0
        predCount = 0
        val low = bg.low.takeIf { it > 0 } ?: DEFAULT_LOW_MGDL
        val high = bg.high.takeIf { it > 0 } ?: DEFAULT_HIGH_MGDL
        lowY = OrbitFaceLogic.sparkY(low, sparkTop, sparkBottom)
        highY = OrbitFaceLogic.sparkY(high, sparkTop, sparkBottom)

        val from = now - OrbitFaceLogic.SPARK_HISTORY_MS
        val points = d.graphData.entries.asSequence()
            .filter { it.timeStamp in from..now && it.sgv > 0 }
            .sortedBy { it.timeStamp }
            .toMutableList()
        if (bg.timeStamp in from..now && bg.sgv > 0 && (points.isEmpty() || points.last().timeStamp < bg.timeStamp)) points.add(bg)
        var prevT = Long.MIN_VALUE
        for (p in points) {
            val x = OrbitFaceLogic.sparkX(p.timeStamp, now)
            val y = OrbitFaceLogic.sparkY(p.sgv, sparkTop, sparkBottom)
            if (prevT == Long.MIN_VALUE || OrbitFaceLogic.sparkBreak(prevT, p.timeStamp)) sparkPath.moveTo(x, y) else sparkPath.lineTo(x, y)
            prevT = p.timeStamp
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
            nowY = OrbitFaceLogic.sparkY(bg.sgv, sparkTop, sparkBottom)
        }
        if (hasNowDot && !OrbitFaceLogic.isOld(bgAge)) {
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
                    predY[predCount] = OrbitFaceLogic.sparkY(best.sgv, sparkTop, sparkBottom)
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

        // warning triangle of the alert pill, 14 x 12, centred on (0, 0); translated when drawn
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
        // corona: ellipse 240 x 200 at (225, 215), 60 px blur. Baked at the emphasis alpha (34 + 20 %),
        // drawn at CORONA_NORMAL_ALPHA for the normal step.
        val cw = 240f + 2 * CORONA_BLUR
        val ch = 200f + 2 * CORONA_BLUR
        coronaDst.set(225f - cw / 2f, 215f - ch / 2f, 225f + cw / 2f, 215f + ch / 2f)
        val colours = intArrayOf(GREEN, AMBER, RED)
        for (i in 0..2) {
            corona[i]?.recycle()
            // blurred anyway: half resolution is enough
            corona[i] = softBitmap(cw, ch, s * 0.5f) { c ->
                fill.reset(); fill.isAntiAlias = true
                fill.color = withAlpha(colours[i], CORONA_EMPHASIS_ALPHA)
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
        val old = OrbitFaceLogic.isOld(bgAge)

        // 1. corona
        if (!old) {
            val bmp = corona[when (rangeColour) {
                AMBER -> 1
                RED   -> 2
                else  -> 0
            }]
            if (bmp != null) {
                bmpPaint.alpha = if (OrbitFaceLogic.emphasis(range)) 255 else CORONA_NORMAL_ALPHA
                canvas.drawBitmap(bmp, null, coronaDst, bmpPaint)
                bmpPaint.alpha = 255
            }
        }

        // 2. orbit track with the time and date gaps
        drawTrack(canvas, 1.5f, TRACK, true)

        // 3. sparkline
        setStroke(1f, withAlpha(GREEN, 120), true)
        stroke.pathEffect = dash
        canvas.drawLine(OrbitFaceLogic.SPARK_X0, highY, OrbitFaceLogic.SPARK_X1, highY, stroke)
        canvas.drawLine(OrbitFaceLogic.SPARK_X0, lowY, OrbitFaceLogic.SPARK_X1, lowY, stroke)
        stroke.pathEffect = null
        setStroke(2.5f, rangeColour, true)
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeCap = Paint.Cap.ROUND
        if (!old) stroke.setShadowLayer(4f, 0f, 0f, withAlpha(rangeColour, 150))
        canvas.drawPath(sparkPath, stroke)
        stroke.clearShadowLayer()
        drawCarets(canvas, rangeColour, true)
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
        drawHero(canvas, heroColour, rangeColour, glow = !old, ambient = false)

        // 5. delta row, status slot
        val s = slot
        if (slotInDeltaRow && s != null) drawPill(canvas, s, 251f, 30f, 19f, ambient = false)
        else if (!firstStart) drawDeltaRow(canvas, DELTA_C, if (deltaRow.ageAmber) AMBER else DELTA_C, true)
        if (s != null && !slotInDeltaRow) drawPill(canvas, s, OrbitFaceLogic.SLOT_Y, 26f, 16f, ambient = false)

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
        if (bolusCheck.state != State.HIDDEN) drawBolus(canvas)

        // 8. time, date
        drawText(canvas, timeText, OrbitFaceLogic.CX, 57f, 30f, tf600, TIME_C, true)
        drawText(canvas, dateText, OrbitFaceLogic.CX, OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f), dateSize, tf500, DATE_C, true)

        // 9. minute moon
        if (moon.kind == MoonKind.DOT) {
            setFill(MOON_C, true)
            canvas.drawCircle(OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, moon.deg), OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, moon.deg), 4.5f, fill)
        }
    }

    private fun drawAmbient(canvas: Canvas) {
        val aa = !lowBitAmbient
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

        drawHero(canvas, amb(0xFFD6D6D6.toInt()), amb(0xFFD6D6D6.toInt()), glow = false, ambient = true)
        val s = slot
        when {
            // the final spec's ambient stale row; the slot text would only repeat it
            s != null && s.rank == 3 -> drawText(canvas, ambientOldText, OrbitFaceLogic.CX, 251f, 19f, tf600, amb(0xFFBDBDBD.toInt()), aa)
            s != null && slotInDeltaRow -> drawPill(canvas, s, 251f, 30f, 19f, ambient = true)
            !firstStart              -> drawDeltaRow(canvas, amb(0xFF9A9A9A.toInt()), amb(0xFF9A9A9A.toInt()), aa)
        }
        if (s != null && !slotInDeltaRow) drawPill(canvas, s, OrbitFaceLogic.SLOT_Y, 26f, 16f, ambient = true)

        for (i in balls.indices) {
            val b = balls[i] ?: continue
            val x = ballX[i]
            val y = ballY[i]
            setFill(Color.BLACK, aa)
            canvas.drawCircle(x, y, OrbitFaceLogic.BALL_R, fill)
            setStroke(1.2f, amb(0xFF6E6E6E.toInt()), aa)
            if (b.dashed) stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, OrbitFaceLogic.BALL_R, stroke)
            stroke.pathEffect = null
            if (b.pause) drawPause(canvas, x, y - 7f, amb(0xFFC8C8C8.toInt()), aa, hollow = true)
            else {
                val size = ballSize[i]
                drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - 7f, size, if (size >= 22f) tf400 else tf500, amb(0xFFC8C8C8.toInt()), aa)
            }
            if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + 15f, 14f, tf600, amb(0xFF9A9A9A.toInt()), aa, LABEL_TRACKING)
        }

        drawText(canvas, timeText, OrbitFaceLogic.CX, 57f, 30f, tf600, amb(0xFFBDBDBD.toInt()), aa)
        drawText(canvas, dateText, OrbitFaceLogic.CX, OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f), dateSize, tf500, amb(0xFF8E8E8E.toInt()), aa)
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
            if (caretDir[i] > 0) canvas.translate(caretX[i], sparkTop - 4f)
            else {
                canvas.translate(caretX[i], sparkBottom + 4f)
                canvas.scale(1f, -1f)
            }
            canvas.drawPath(caretPath, fill)
            canvas.restore()
        }
    }

    private fun drawHero(canvas: Canvas, colour: Int, arrowColour: Int, glow: Boolean, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val dashes = heroText == "---"
        setText(108f, if (ambient) tf300 else tf700, aa)
        val w = text.measureText(heroText)
        val size = OrbitFaceLogic.fitSize(w, 108f, OrbitFaceLogic.HERO_MAX_WIDTH)
        text.textSize = size
        val heroW = w * size / 108f
        text.color = if (dashes && !ambient) STALE_C else colour
        if (glow) text.setShadowLayer(12f, 0f, 0f, withAlpha(rangeColour, 120))
        // without an arrow the digits sit on the optical centre
        val heroX = if (arrowText.isEmpty()) OrbitFaceLogic.CX else HERO_X
        canvas.drawText(heroText, heroX, baseline(191f), text)
        text.clearShadowLayer()
        val right = heroX + heroW / 2f
        var arrowRight = right
        if (arrowText.isNotEmpty()) {
            setText(44f, if (ambient) tf400 else tf600, aa)
            text.color = arrowColour
            val ax = right + 24f
            canvas.drawText(arrowText, ax, baseline(193f), text)
            arrowRight = ax + text.measureText(arrowText) / 2f
        }
        if (OrbitFaceLogic.isStruck(bgAge) && !dashes) {
            setStroke(if (!ambient) 3f else if (lowBitAmbient) 2f else 1.5f, colour, aa)
            canvas.drawLine(heroX - heroW / 2f - 6f, 195f, arrowRight, 195f, stroke)
        }
    }

    private fun drawDeltaRow(canvas: Canvas, colour: Int, ageColour: Int, aa: Boolean) {
        setText(21f, tf500, aa)
        val wl = text.measureText(deltaRow.deltaPart)
        val wa = text.measureText(deltaRow.agePart)
        val glyphW = if (phoneGlyph) 18f else 0f
        val size = OrbitFaceLogic.fitSize(wl + wa + glyphW, 21f, OrbitFaceLogic.DELTA_ROW_MAX_WIDTH)
        text.textSize = size
        val k = size / 21f
        text.textAlign = Paint.Align.LEFT
        val x0 = OrbitFaceLogic.CX - ((wl + wa) * k + glyphW) / 2f
        val y = baseline(251f)
        text.color = colour
        canvas.drawText(deltaRow.deltaPart, x0, y, text)
        text.color = ageColour
        canvas.drawText(deltaRow.agePart, x0 + wl * k, y, text)
        if (phoneGlyph) drawPhoneOff(canvas, x0 + (wl + wa) * k + 4f + 7f, 251f, colour, aa)
    }

    /** Phone-off glyph (conn.phone_lost_fresh): a phone outline crossed by a slash, 14 px tall. */
    private fun drawPhoneOff(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean) {
        setStroke(1.4f, colour, aa)
        rect.set(x - 4f, y - 7f, x + 4f, y + 7f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, stroke)
        stroke.strokeWidth = 1.6f
        canvas.drawLine(x - 7f, y + 7f, x + 7f, y - 7f, stroke)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean, hollow: Boolean) {
        if (hollow) setStroke(1.2f, colour, aa) else setFill(colour, aa)
        for (ox in PAUSE_OFFSETS) {
            rect.set(x + ox - 2f, y - 7f, x + ox + 2f, y + 7f)
            if (hollow) canvas.drawRect(rect, stroke) else canvas.drawRoundRect(rect, 1f, 1f, fill)
        }
    }

    /** Status slot pill (states.md §4.2). Interactive: tinted fill + outline; ambient: outline only. */
    private fun drawPill(canvas: Canvas, s: Slot, cy: Float, height: Float, textSize: Float, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val bg = when {
            ambient              -> Color.BLACK
            s.tone == Tone.ALERT -> PILL_ALERT
            s.tone == Tone.WARN  -> PILL_WARN
            else                 -> PILL_INFO
        }
        val line = when {
            ambient              -> amb(0xFF9A9A9A.toInt())
            s.tone == Tone.ALERT -> RED
            s.tone == Tone.WARN  -> AMBER
            else                 -> NEUTRAL_RING
        }
        val fg = when {
            ambient              -> amb(0xFFE0E0E0.toInt())
            s.tone == Tone.ALERT -> LOW_TXT
            s.tone == Tone.WARN  -> WARN_TXT
            else                 -> TXT2
        }
        setText(textSize, tf600, aa)
        val icon = s.tone == Tone.ALERT && !ambient
        val iconW = if (icon) 14f + 6f else 0f
        val tw = min(text.measureText(s.text), OrbitFaceLogic.SLOT_MAX_WIDTH)
        val k = OrbitFaceLogic.fitSize(text.measureText(s.text), textSize, OrbitFaceLogic.SLOT_MAX_WIDTH) / textSize
        val half = (iconW + tw) / 2f + 14f
        rect.set(OrbitFaceLogic.CX - half, cy - height / 2f, OrbitFaceLogic.CX + half, cy + height / 2f)
        setFill(bg, aa)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        setStroke(1.5f, line, aa)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, stroke)
        val x0 = OrbitFaceLogic.CX - (iconW + tw) / 2f
        if (icon) {
            canvas.save()
            canvas.translate(x0 + 7f, cy - 1f)
            setStroke(1.6f, fg, true)
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(warnIcon, stroke)
            canvas.drawLine(0f, -1.5f, 0f, 2f, stroke)
            canvas.drawPoint(0f, 4.2f, stroke)
            canvas.restore()
        }
        setText(textSize * k, tf600, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = fg
        canvas.drawText(s.text, x0 + iconW, baseline(cy), text)
    }

    private fun drawBall(canvas: Canvas, i: Int) {
        val b = balls[i] ?: return
        val x = ballX[i]
        val y = ballY[i]
        val r = OrbitFaceLogic.BALL_R
        if (b.dashed) {
            setFill(BALL_FILL, true)
            canvas.drawCircle(x, y, r, fill)
            setStroke(2f, DIM_RING, true)
            stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, r - 1f, stroke)
            stroke.pathEffect = null
        } else {
            val shell = shells[b.ring.ordinal]
            if (shell != null) {
                val half = r + SHELL_PAD
                shellDst.set(x - half, y - half, x + half, y + half)
                canvas.drawBitmap(shell, null, shellDst, bmpPaint)
            }
        }
        if (b.arc > 0f) {
            setStroke(3f, CYAN, true)
            stroke.strokeCap = Paint.Cap.ROUND
            rect.set(x - r + 1f, y - r + 1f, x + r - 1f, y + r - 1f)
            canvas.drawArc(rect, -90f, 360f * b.arc, false, stroke)
        }
        val dim = b.ring == Ring.DIM || b.dimValue
        val labelColour = when (b.ring) {
            Ring.DIM     -> DIM_TXT
            Ring.NEUTRAL -> LBL_NEUTRAL
            else         -> withAlpha(ringColour(b.ring), 235)
        }
        if (b.pause) drawPause(canvas, x, y - 7f, AMBER, true, hollow = false)
        else {
            val size = ballSize[i]
            val tf = if (size < 22f) tf700 else tf600
            drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - 7f, size, tf, if (dim) DIM_TXT else VAL_TXT, true)
        }
        if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + 15f, 14f, tf600, labelColour, true, LABEL_TRACKING)
    }

    private fun drawBolus(canvas: Canvas) {
        val state = bolusCheck.state
        val armed = gesture.isArmed && state == State.IDLE
        val half = OrbitFaceLogic.BOLUS_HALF + BOLUS_PAD
        bolusDst.set(bolusX - half, bolusY - half, bolusX + half, bolusY + half)
        val alpha = when (state) {
            State.DISABLED -> DISABLED_ALPHA
            State.SENT     -> SENT_ALPHA
            else           -> 255
        }
        bmpPaint.alpha = alpha
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
        } else if (state == State.SENT) {
            drawText(canvas, bolusAmountText, bolusX, bolusY - 6f, 19f, tf700, withAlpha(BOLUS_TEXT, alpha), true)
            drawText(canvas, "…", bolusX, bolusY + 12f, 18f, tf700, withAlpha(BOLUS_TEXT, alpha), true)
        } else {
            val disabled = state == State.DISABLED
            setFill(if (disabled) withAlpha(TXT3, 255) else withAlpha(INDIGO, 230), true)
            canvas.drawPath(dropPath, fill)
            drawText(canvas, bolusAmountText, bolusX, bolusY + 11f, 19f, tf700, if (disabled) TXT3 else BOLUS_TEXT, true)
        }
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
        // tap.ambient: no hit testing at all
        if (tapType != TapType.UP || isAmbient) return
        if (!guard.acceptTap(eventTime)) return
        val left = (faceRect.width() - 450f * scale) / 2f
        val top = (faceRect.height() - 450f * scale) / 2f
        val dx = (x - left) / scale
        val dy = (y - top) / scale

        refreshBolusCheck()
        val state = bolusCheck.state
        val inside = state != State.HIDDEN && BolusTapGesture.isInside(dx, dy, bolusX, bolusY, BOLUS_HIT_R)
        if (inside && (state == State.DISABLED || state == State.SENT)) {
            if (gesture.reset()) handler.removeCallbacks(countdownTick)
            toast(
                when (bolusCheck.reason) {
                    Reason.PENDING        -> R.string.orbit_bolus_pending
                    Reason.STALE_DATA     -> R.string.orbit_bolus_refused_stale
                    Reason.OVER_MAX_BOLUS -> R.string.orbit_bolus_refused_max
                    else                  -> R.string.orbit_bolus_refused_phone
                }
            )
            invalidate()
            return
        }
        val enabled = state == State.IDLE
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
                    dirty = true
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
        if (inside) return

        // tap.menu: a single tap on the time opens the main menu
        if (dx in 155f..295f && dy in 30f..85f) {
            openMenu(eventTime)
            return
        }
        // Double-tap on the hero BG opens the main menu as well, like the other AAPS faces.
        if (dx in 110f..340f && dy in 140f..240f) {
            if (bgTapTime != 0L && eventTime - bgTapTime < OrbitBolusGuard.WINDOW_MS) {
                openMenu(eventTime)
                bgTapTime = 0
            } else bgTapTime = eventTime
        }
    }

    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L

    private fun openMenu(eventTime: Long) {
        if (eventTime - lastMenuOpenTime <= 2000) return
        lastMenuOpenTime = eventTime
        startActivity(Intent(this, MainMenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun toast(res: Int) {
        try {
            Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "OrbitWatchface toast failed", e)
        }
    }

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
        private val PAUSE_OFFSETS = floatArrayOf(-4f, 4f)

        /** Bitmap margin around the corona ellipse, and the blur radius (a 60 px Gaussian of the mockup). */
        private const val CORONA_BLUR = 120f
        private const val CORONA_BLUR_RADIUS = 100f
        private const val CORONA_EMPHASIS_ALPHA = 41   // 34 + 20 %
        private const val CORONA_NORMAL_ALPHA = 212    // 34 / 41 of the baked alpha
        private const val LABEL_TRACKING = 0.04f
        private const val DISABLED_ALPHA = 102         // 40 %
        private const val SENT_ALPHA = 140             // 55 %
        private const val DEFAULT_LOW_MGDL = 70.0
        private const val DEFAULT_HIGH_MGDL = 180.0

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val LOW_TXT = 0xFFFFD6DC.toInt()
        private const val STALE_C = 0xFF8A93A8.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val TEAL = 0xFF3DDBD9.toInt()
        private const val VIOLET = 0xFFB388FF.toInt()
        private const val NEUTRAL_RING = 0xFF5A6B85.toInt()
        private const val LBL_NEUTRAL = 0xFF9AA6B8.toInt()
        private const val DIM_RING = 0xFF3A4250.toInt()
        private const val DIM_TXT = 0xFF7D8796.toInt()
        private const val TXT2 = 0xFF9AA6C4.toInt()
        private const val TXT3 = 0xFF6F7A99.toInt()
        private const val WARN_TXT = 0xFFFFE2B0.toInt()
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
        private const val PILL_ALERT = 0xFF3A1018.toInt()
        private const val PILL_WARN = 0xFF33240C.toInt()
        private const val PILL_INFO = 0xFF141A25.toInt()
    }
}
