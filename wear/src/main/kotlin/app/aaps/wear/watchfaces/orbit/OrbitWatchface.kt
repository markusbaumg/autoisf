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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.text.format.DateFormat
import android.os.Vibrator
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.wear.watchface.TapType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventUpdateSelectedWatchface
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.ActionResendData
import app.aaps.core.interfaces.rx.weardata.EventData.LoopStatesList
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.wear.R
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.LoopRunTime
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.BolusTapGesture
import app.aaps.wear.watchfaces.common.PhoneReach
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ball
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ring
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Slot
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Tone
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Trend
import app.aaps.wear.watchfaces.utils.SimpleUi
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
 * REQUEST (ActionBolusPreCheck); the phone checks it and the watch shows the standard confirmation. Every rule and
 * the setting are shared with Halo and Glass ([BolusRequestGuard], [BolusRequestPrefs]); Orbit only draws the button.
 *
 * Not yet covered (deferred, see the commit message): multi-patient balls (datasets 1/2, key_include_external,
 * multi.*), key_show_seconds, the optional LOOP-ball tap (ActionLoopStatusDetailed), ball.delivering/delivered
 * (BolusProgress), flat-tyre insets and locale.rtl.
 */
@SuppressLint("Deprecated")
class OrbitWatchface : WatchFace() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var complicationDataRepository: ComplicationDataRepository
    @Inject lateinit var simpleUi: SimpleUi

    private val disposable = CompositeDisposable()
    private val watchfaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var data: ComplicationData = ComplicationData()

    // ---------------------------------------------------------------- bolus (shared rule set)

    /**
     * Also holds the phone reachability (CapabilityClient FILTER_REACHABLE, refreshed once per minute and on every
     * bolus-button tap): UNKNOWN until the first answer, the button stays DISABLED until then.
     */
    private val guard = BolusRequestGuard(clock = { System.currentTimeMillis() }, store = BolusRequestPrefs.lastSentStore { sp })
    private val gesture get() = guard.gesture
    private var bolusCheck = BolusRequestGuard.Check(State.HIDDEN, Reason.PREF_OFF)
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
    private var flashUntil = 0L
    private val redraw = Runnable { invalidate() }

    // ---------------------------------------------------------------- display preferences (read in prefsChanged)

    private var showIob = true
    private var showCob = true
    private var showBasal = true
    private var showTt = true
    private var showLoop = true
    private var showDate = true
    private var showWeek = false
    private var showDelta = true
    private var showAgo = true
    private var showDirection = true
    private var detailedDelta = false
    private var avgDelta = false
    private var showPredictions = true
    private var timeFrameH = OrbitFaceLogic.DEFAULT_TIME_FRAME_H
    private var loopStatesRaw = ""
    private var loopStateNames: Set<String> = emptySet()
    private var loopStatesTimeStamp = 0L

    // graph tap zone: single tap opens the menu (after the double-tap window), double tap cycles the time frame
    private var graphTapTime = 0L
    private val graphSingleTap = Runnable {
        graphTapTime = 0L
        openMenu(System.currentTimeMillis())
    }
    private var captionText = ""
    private var captionUntil = 0L

    // ---------------------------------------------------------------- derived state (rebuilt on change only)

    private var labels = OrbitFaceLogic.Labels()
    private var builtFor: ComplicationData? = null
    private var builtMinuteKey = -1L
    private var builtAmbient = false
    private var built24h = false
    private var builtReachable = PhoneReach.UNKNOWN
    private var dirty = true

    private var scale = 1f
    private var timeText = ""
    private var amPmText = ""
    private var timeX = 0f
    private var amPmX = 0f
    private var dateText = ""
    /** "Sa 17." beside a W4-W9 pill at 6 o'clock (Glass/Orbit UX round 3: the date never disappears). */
    private var shortDateText = ""
    /** Date line parts (Glass/Orbit UX round 1: an active temp target joins the date at 6 o'clock in violet). */
    private var lineDate = ""
    private var lineTt = ""
    private var lineDateW = 0f
    private var lineW = 0f
    private var dateSize = DATE_SIZE
    /** Track segments between the breaks (time, date, satellites, bolus), degrees clockwise from 12. */
    private val segStart = FloatArray(MAX_SEGMENTS)
    private val segSweep = FloatArray(MAX_SEGMENTS)
    private var segCount = 0
    private val gapA = FloatArray(MAX_SEGMENTS)
    private val gapB = FloatArray(MAX_SEGMENTS)
    private var gapT = 10f
    private var gapD = 10f
    private var bgAge = BgAge.NONE
    private var range = Range.STALE
    private var rangeColour = STALE_C
    private var heroColour = STALE_C
    private var heroText = "---"
    private var heroSize = OrbitFaceLogic.HERO_SIZE
    private var heroX = OrbitFaceLogic.CX
    private var heroW = 0f
    private var trend = Trend.NONE
    private var trendX = 0f
    private var trendSize = 48f
    private var sentinelLow = false
    private var caption = FaceState.Caption.NONE
    private var captionShown = false
    /** UX round 3: the TIEF/HOCH caption follows the delta on the delta row (the pill row keeps it only as a fallback). */
    private var captionInDelta = false
    private var loopMuted = false
    private val disabledDash = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    private val captionBox = RectF()
    private val trendPath = Path()
    private var deltaRow = OrbitFaceLogic.DeltaRow("", "", false)
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
    private var sparkWindowMs = OrbitFaceLogic.SPARK_HISTORY_MS

    // sparkline
    private val sparkPath = Path()
    private var hasNowDot = false
    private var nowX = 0f
    private var nowY = 0f
    private val predX = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private val predY = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private var predCount = 0
    private var lowY = 0f
    private var highY = 0f
    private val sparkTop = OrbitFaceLogic.SPARK_Y_TOP
    private val sparkBottom = OrbitFaceLogic.SPARK_Y_BOTTOM
    /** A W4-W9 pill (or a caption off the delta row) replaces the date line at 6 o'clock (Glass/Orbit UX round 2). */
    private var pillRowShown = false

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
    private val ringDash = DashPathEffect(floatArrayOf(5f, 4f), 0f)

    // ambient range tracks (spec §6): dashed 6/6 1 px for HIGH; UX round 2: LOW is one 2 px ring with long dashes
    // (the doubled ring read heavy next to the satellites)
    // UX round 3: high = fine dots, low = long dashes (6/6 and 22/6 looked alike at arm's length)
    private val ambDash = DashPathEffect(floatArrayOf(2f, 7f), 0f)
    private val ambLowDash = DashPathEffect(floatArrayOf(22f, 6f), 0f)
    private val ambUrgentDash = DashPathEffect(floatArrayOf(48f, 4f), 0f)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)
    private val ballX = FloatArray(5) { OrbitFaceLogic.pointX(OrbitFaceLogic.SAT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    private val ballY = FloatArray(5) { OrbitFaceLogic.pointY(OrbitFaceLogic.SAT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    private val bolusX = OrbitFaceLogic.pointX(OrbitFaceLogic.SAT_R, OrbitFaceLogic.DEG_BOLUS)
    private val bolusY = OrbitFaceLogic.pointY(OrbitFaceLogic.SAT_R, OrbitFaceLogic.DEG_BOLUS)
    private val outlinePath = Path()
    private val outlineSegment = Path()
    private var outlineMeasure = PathMeasure()
    private var outlineLength = 0f
    private val tickPath = Path()
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
        initDrawing()
        simpleUi.onCreate(::invalidate)

        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect { onData(it) }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { prefsChanged() }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { prefsChanged() }
    }

    /** Labels, shapes and preferences (onCreate, after injection). */
    private fun initDrawing() {
        labels = OrbitFaceLogic.Labels(
            again = getString(R.string.orbit_bolus_again),
            cob = getString(R.string.orbit_cob),
            minutesFormat = getString(R.string.orbit_minutes).replace("%1\$d", "%d"),
            hoursFormat = getString(R.string.orbit_hours).replace("%1\$d", "%d"),
            daysFormat = getString(R.string.orbit_days).replace("%1\$d", "%d"),
            waiting = getString(R.string.orbit_waiting),
            phoneLostFormat = getString(R.string.orbit_phone_lost).replace("%1\$s", "%s"),
            noDataFormat = getString(R.string.orbit_no_data).replace("%1\$s", "%s"),
            noProfile = getString(R.string.orbit_no_profile),
            loopOff = getString(R.string.orbit_loop_off),
            loopAgeFormat = getString(R.string.orbit_loop_age).replace("%1\$s", "%s"),
            loopOffShort = getString(R.string.orbit_loop_off_short),
            loopOpen = getString(R.string.orbit_loop_open),
            loopLgs = getString(R.string.orbit_loop_lgs),
            sentinelLow = getString(R.string.face_low),
            sentinelHigh = getString(R.string.face_high),
            weekFormat = getString(R.string.orbit_week).replace("%1\$d", "%d"),
            reservoirFormat = getString(R.string.orbit_reservoir).replace("%1\$s", "%s"),
            phoneBatteryFormat = getString(R.string.orbit_phone_battery).replace("%1\$s", "%s")
        )
        buildShapes()
        readPrefs()
    }

    private fun onData(d: ComplicationData) {
        data = d
        invalidate()
    }

    /**
     * Offline render harness only (wear/src/test); never called on the watch. onCreate's drawing state without
     * injection or side effects (the injected fields must be set before), then [d] as received data.
     * Returns the bolus guard (phone answer, taps).
     */
    @VisibleForTesting
    internal fun prepareOffline(d: ComplicationData): BolusRequestGuard {
        initDrawing()
        onData(d)
        return guard
    }

    /** sys.preview: the headless (picker / editor) instance renders the fixed sample and starts no services. */
    override fun onWatchStateKnown() {
        dirty = true
        if (isHeadless) return
        BolusRequestPrefs.migrate(sp) // per-face settings of earlier iterations -> the shared pair
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.ORBIT.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())
        refreshPhoneReachable()
        rxBus.send(EventWearToMobile(ActionResendData("OrbitWatchface::onCreate")))
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownTick)
        handler.removeCallbacks(pendingEnd)
        handler.removeCallbacks(redraw)
        handler.removeCallbacks(graphSingleTap)
        simpleUi.onDestroy()
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
        if (watchMode != WatchMode.INTERACTIVE) {
            disarm()
            // captions and pending graph taps are dropped
            captionUntil = 0L
            graphTapTime = 0L
            handler.removeCallbacks(graphSingleTap)
        }
        dirty = true
    }

    private fun prefsChanged() {
        dirty = true
        readPrefs()
        simpleUi.updatePreferences()
        refreshBolusCheck()
        if (bolusCheck.state != State.IDLE) disarm()
        invalidate()
    }

    private fun disarm() {
        gesture.reset()
        handler.removeCallbacks(countdownTick)
    }

    /** Asynchronous; the answer redraws only when it changed. */
    private fun refreshPhoneReachable() {
        if (isHeadless) return
        PhoneReachability.refresh(this, guard, aapsLogger, "OrbitWatchface") { invalidate() }
    }

    // ---------------------------------------------------------------- preferences

    /** display_preferences toggles and the chart time frame (pref.all_on / pref.minimal). */
    private fun readPrefs() {
        showIob = sp.getBoolean(R.string.key_show_iob, true)
        showCob = sp.getBoolean(R.string.key_show_cob, true)
        showBasal = sp.getBoolean(R.string.key_show_temp_basal, true)
        showTt = sp.getBoolean(R.string.key_show_temp_target, true)
        showLoop = sp.getBoolean(R.string.key_show_external_status, true)
        showDate = sp.getBoolean(R.string.key_show_date, true)
        showWeek = sp.getBoolean(R.string.key_show_week_number, false)
        showDelta = sp.getBoolean(R.string.key_show_delta, true)
        showAgo = sp.getBoolean(R.string.key_show_ago, true)
        showDirection = sp.getBoolean(R.string.key_show_direction, true)
        detailedDelta = sp.getBoolean(R.string.key_show_detailed_delta, false)
        avgDelta = sp.getBoolean(R.string.key_show_avg_delta, true)
        showPredictions = sp.getBoolean(R.string.key_prediction_lines, true)
        timeFrameH = (sp.getString(R.string.key_chart_time_frame, OrbitFaceLogic.DEFAULT_TIME_FRAME_H.toString()).toIntOrNull()
            ?: OrbitFaceLogic.DEFAULT_TIME_FRAME_H).coerceIn(1, 5)
    }

    /** loopMode input: the offered states of the stored LoopStatesList, deserialised only when the string changed. */
    private fun refreshLoopStates() {
        val raw = sp.getString(R.string.key_loop_states_data, "")
        if (raw == loopStatesRaw) return
        loopStatesRaw = raw
        val list = try {
            if (raw.isEmpty()) null else EventData.deserialize(raw) as? LoopStatesList
        } catch (_: Exception) {
            null
        }
        loopStateNames = list?.states?.map { it.state.name }?.toSet() ?: emptySet()
        loopStatesTimeStamp = list?.timeStamp ?: 0L
    }

    private fun refreshBolusCheck() {
        if (isHeadless) {
            bolusCheck = BolusRequestGuard.Check(State.HIDDEN, Reason.HEADLESS)
            return
        }
        val amount = BolusRequestPrefs.amount(sp)
        if (!amount.isNaN() && (amount != bolusAmount || bolusAmountText.isEmpty())) {
            bolusAmount = amount
            bolusAmountText = OrbitFaceLogic.bolusAmountText(amount, Locale.getDefault())
        }
        bolusCheck = guard.check(bolusInput())
    }

    private fun bolusInput(): BolusRequestGuard.Input {
        val d = data
        return BolusRequestGuard.Input(
            prefOn = BolusRequestPrefs.enabled(sp),
            ambient = isAmbient,
            wearControl = preferences.get(BooleanKey.WearControl),
            amount = BolusRequestPrefs.amount(sp),
            maxBolus = BolusRequestPrefs.maxBolus(sp, getString(R.string.key_treatments_safety_max_bolus)),
            lastPhoneMessage = d.lastUpdateTimestamp,
            bgTimeStamp = d.bgData.timeStamp,
            headless = isHeadless || !isLiveRender,
            firstStart = OrbitFaceLogic.firstStart(d.bgData.timeStamp, LoopRunTime.of(d.statusData), d.statusData.iobSum)
        )
    }

    // ---------------------------------------------------------------- derived state

    private fun minuteKey(): Long {
        val t = latestTime
        return ((t.year * 13L + t.month) * 32L + t.monthDay) * 1440L + t.hour * 60L + t.minute
    }

    /** The renderer's time (one time source for the clock and every age); the fixed sample time when headless. */
    private fun renderNow(): Long = when {
        isHeadless             -> SAMPLE_NOW
        latestEpochMillis > 0L -> latestEpochMillis
        else                   -> System.currentTimeMillis()
    }

    private fun rebuildIfNeeded() {
        val d = if (isHeadless) sampleData else data
        val key = minuteKey()
        if (!dirty && d === builtFor && key == builtMinuteKey && isAmbient == builtAmbient && is24HourFormat == built24h &&
            guard.phoneReach == builtReachable
        ) return
        if (key != builtMinuteKey) refreshPhoneReachable()
        refreshLoopStates()
        dirty = false
        builtFor = d
        builtMinuteKey = key
        builtAmbient = isAmbient
        built24h = is24HourFormat
        builtReachable = guard.phoneReach
        rebuild(d, renderNow(), key)
    }

    private fun rebuild(d: ComplicationData, now: Long, minuteKey: Long) {
        val t = latestTime
        val bg = d.bgData
        val st = d.statusData
        val hour = if (isHeadless) SAMPLE_HOUR else t.hour
        val minute = if (isHeadless) SAMPLE_MINUTE else t.minute

        // time (+ AM/PM in 12 h) and date in the watch locale
        timeText = OrbitFaceLogic.timeString(hour, minute, is24HourFormat)
        amPmText = OrbitFaceLogic.amPm(hour, is24HourFormat)
        setText(OrbitFaceLogic.TIME_SIZE, tf600)
        val timeW = text.measureText(timeText)
        setText(floorPx(AMPM_SIZE), tf500)
        val amW = if (amPmText.isEmpty()) 0f else AMPM_GAP + text.measureText(amPmText)
        timeX = OrbitFaceLogic.CX - (timeW + amW) / 2f
        amPmX = timeX + timeW + AMPM_GAP
        gapT = OrbitFaceLogic.gapHalfDeg(timeW + amW)

        val locale = Locale.getDefault()
        dateText = if (!showDate) "" else try {
            val pattern = if (locale.language == "de") null else DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
            if (isHeadless) OrbitFaceLogic.dateString(SAMPLE_YEAR, SAMPLE_MONTH, SAMPLE_DAY, locale, pattern, if (showWeek) labels.weekFormat else null)
            else OrbitFaceLogic.dateString(t.year, t.month + 1, t.monthDay, locale, pattern, if (showWeek) labels.weekFormat else null)
        } catch (_: Exception) {
            ""
        }

        shortDateText = if (!showDate) "" else try {
            if (isHeadless) OrbitFaceLogic.shortDate(SAMPLE_YEAR, SAMPLE_MONTH, SAMPLE_DAY, locale)
            else OrbitFaceLogic.shortDate(t.year, t.month + 1, t.monthDay, locale)
        } catch (_: Exception) {
            ""
        }

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
        sentinelLow = OrbitFaceLogic.sentinelLow(bg.sgv, ageMin)
        if (sentinelLow) range = Range.URGENT_LOW
        caption = FaceState.caption(range)
        // UX round 2: the digits carry the range colour at Halo's strength (green, amber, red; urgent low never pale)
        heroColour = when (range) {
            Range.STALE -> TXT2
            else        -> rangeColour
        }
        heroText = OrbitFaceLogic.heroText(bg.sgvString, bg.sgv, ageMin)
        trend = if (OrbitFaceLogic.isOld(bgAge) || !showDirection) Trend.NONE else OrbitFaceLogic.trend(bg.slopeArrow, bg.deltaMgdl)
        layoutHero()
        val delta = OrbitFaceLogic.deltaSource(bg.delta, bg.deltaDetailed, bg.avgDelta, bg.avgDeltaDetailed, detailedDelta, avgDelta)
        deltaRow = OrbitFaceLogic.deltaRow(delta, ageMin, labels, showDelta, showAgo)

        // status
        firstStart = OrbitFaceLogic.firstStart(bg.timeStamp, LoopRunTime.of(st), st.iobSum)
        val noProfile = OrbitFaceLogic.noProfile(st.externalStatus, st.iobSum)
        val loopOff = OrbitFaceLogic.loopOff(st.externalStatus, st.iobSum)
        val loopAge = OrbitFaceLogic.loopAgeMinutes(LoopRunTime.of(st), now)
        val realRun = st.lastLoopRunAt > 0L
        val dataAgeMs = if (d.lastUpdateTimestamp > 0L) now - d.lastUpdateTimestamp else Long.MAX_VALUE
        // the display treats an unknown link as reachable (no "Telefon getrennt" before the first answer);
        // the bolus guard does not (DISABLED until then)
        val link = OrbitFaceLogic.phoneLink(isHeadless || guard.phoneReach != PhoneReach.UNREACHABLE, dataAgeMs)
        val dataAgeMin = if (d.lastUpdateTimestamp > 0L) maxOf(0L, dataAgeMs / 60_000L) else null
        slot = OrbitFaceLogic.statusSlot(
            OrbitFaceLogic.SlotInput(
                firstStart = firstStart, link = link, dataAgeMinutes = dataAgeMin, bgAgeMinutes = ageMin, bgAge = bgAge,
                range = range, noProfile = noProfile, loopOff = loopOff,
                loopBad = OrbitFaceLogic.loopHealth(loopAge, realRun) == OrbitFaceLogic.LoopHealth.BAD,
                loopAgeMinutes = loopAge, reservoirLevel = st.reservoirLevel, reservoirString = st.reservoirString,
                batteryLevel = st.batteryLevel, battery = st.battery
            ),
            ambient = isAmbient, labels = labels
        )
        phoneGlyph = slot?.tone == Tone.GLYPH
        if (phoneGlyph) slot = null
        slotInDeltaRow = firstStart || OrbitFaceLogic.slotReplacesDelta(slot) || (deltaRow.isEmpty && !phoneGlyph)
        if (slot == null) slotInDeltaRow = false
        loopMuted = OrbitFaceLogic.muteLoopRing(range, slot)

        // balls
        val temps = d.treatmentData.temps
        val starts = LongArray(temps.size) { temps[it].startTime }
        val ends = LongArray(temps.size) { temps[it].endTime }
        if (firstStart) {
            balls[OrbitFaceLogic.IDX_LOOP] = Ball("--", "LOOP", Ring.DIM, dashed = true)
            balls[OrbitFaceLogic.IDX_IOB] = OrbitFaceLogic.placeholderBall("IOB")
            balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.placeholderBall("U/h")
            balls[OrbitFaceLogic.IDX_COB] = OrbitFaceLogic.placeholderBall(labels.cob)
            balls[OrbitFaceLogic.IDX_TT] = null
        } else {
            val mode = if (isHeadless) FaceState.LoopMode.UNKNOWN
            else OrbitFaceLogic.loopMode(loopStateNames, loopStatesTimeStamp, d.lastUpdateTimestamp)
            balls[OrbitFaceLogic.IDX_LOOP] =
                if (OrbitFaceLogic.loopBallShown(showLoop, OrbitFaceLogic.loopHealth(loopAge, realRun)))
                    OrbitFaceLogic.loopBall(LoopRunTime.of(st), st.externalStatus, st.iobSum, now, labels, mode, realRun)
                else null
            balls[OrbitFaceLogic.IDX_IOB] = if (showIob) OrbitFaceLogic.iobBall(st.iobSum) else null
            balls[OrbitFaceLogic.IDX_BASAL] = if (showBasal) OrbitFaceLogic.basalBall(st.currentBasal, OrbitFaceLogic.tbrRunning(starts, ends, now)) else null
            balls[OrbitFaceLogic.IDX_COB] = if (showCob) OrbitFaceLogic.cobBall(st.cob, labels) else null
            // Glass/Orbit UX round 1: the TT joins the date line (a 5th satellite made the ring lopsided)
            balls[OrbitFaceLogic.IDX_TT] = null
        }
        val tt = if (!firstStart && showTt) OrbitFaceLogic.ttBall(st.tempTarget, st.tempTargetLevel)?.value else null
        layoutDateLine(tt)
        if (firstStart) {
            if (!showLoop) balls[OrbitFaceLogic.IDX_LOOP] = null
            if (!showIob) balls[OrbitFaceLogic.IDX_IOB] = null
            if (!showBasal) balls[OrbitFaceLogic.IDX_BASAL] = null
            if (!showCob) balls[OrbitFaceLogic.IDX_COB] = null
        }
        for (i in balls.indices) {
            val b = balls[i]
            ballShown[i] = b != null
            if (b == null) continue
            setText(OrbitFaceLogic.BALL_VALUE_SIZE, tf600)
            ballSize[i] = OrbitFaceLogic.ballValueSize(text.measureText(b.value))
        }

        refreshBolusCheck()
        if (bolusCheck.state == State.SENT) {
            handler.removeCallbacks(pendingEnd)
            handler.postDelayed(pendingEnd, guard.pendingLeftMs() + 50L)
        }
        captionShown = caption != FaceState.Caption.NONE && heroText != "---"
        val slotOwnsDeltaRow = slot != null && slotInDeltaRow
        captionInDelta = captionShown && !slotOwnsDeltaRow && !firstStart
        // Glass/Orbit UX round 2: the LOOP satellite owns a loop warning (no second "Loop 23′" pill)
        if (OrbitFaceLogic.foldLoopPill(slot, ballShown[OrbitFaceLogic.IDX_LOOP], isAmbient)) slot = null
        // ... and a remaining W4-W9 pill takes the date's place at 6 o'clock: the chart never reflows
        val pillRowW = pillRowWidth(if (slot != null && !slotInDeltaRow) slot else null, isAmbient)
        pillRowShown = pillRowW > 0f
        if (pillRowShown) {
            lineW = 0f
            gapD = OrbitFaceLogic.gapHalfDeg(pillRowW)
        }
        moon = OrbitFaceLogic.moon(minute, gapT, if (dateText.isEmpty() && !pillRowShown) -2f else gapD, bolusCheck.state != State.HIDDEN, ballShown)
        shiftX = OrbitFaceLogic.burnInShift(minuteKey, 0).toFloat()
        shiftY = OrbitFaceLogic.burnInShift(minuteKey, 1).toFloat()
        buildSparkline(d, now)
        buildTrackSegments()
    }

    /** "Sa 17. Okt", "Sa 17. Okt · TT 5,5" or "TT 5,0-6,0" (OrbitFaceLogic.dateLine), fitted to the chord at 6 o'clock. */
    private fun layoutDateLine(tt: String?) {
        setText(DATE_SIZE, tf500)
        val (datePart, ttPart) = OrbitFaceLogic.dateLine(dateText, tt, DATE_SIZE, DATE_MIN_SIZE, OrbitFaceLogic.DATE_LINE_MAX_WIDTH) { text.measureText(it) }
        lineDate = datePart
        lineTt = ttPart
        if (datePart.isEmpty() && ttPart.isEmpty()) {
            // pref.date off: the track closes at 6 o'clock
            gapD = 0f
            lineW = 0f
            return
        }
        val dw = text.measureText(datePart)
        setText(DATE_SIZE, tf600)
        val full = dw + text.measureText(ttPart)
        dateSize = max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(full, DATE_SIZE, OrbitFaceLogic.DATE_LINE_MAX_WIDTH))
        lineDateW = dw * dateSize / DATE_SIZE
        lineW = full * dateSize / DATE_SIZE
        gapD = OrbitFaceLogic.gapHalfDeg(lineW)
    }

    /**
     * Glass/Orbit UX round 1: the orbit breaks around the time, the date and every drawn satellite (and the bolus
     * button), so the satellites sit ON the ring. Ambient draws all four satellite values (UX round 3), no bolus.
     */
    private fun buildTrackSegments() {
        var n = 0
        if (gapD > 0f) {
            gapA[n] = 180f - gapD; gapB[n] = 180f + gapD; n++
        }
        for (i in balls.indices) {
            val b = balls[i] ?: continue
            if (isAmbient && b.value == "--" && !b.pause) continue
            val deg = OrbitFaceLogic.BALL_DEGS[i]
            gapA[n] = deg - OrbitFaceLogic.BALL_GAP_DEG; gapB[n] = deg + OrbitFaceLogic.BALL_GAP_DEG; n++
        }
        if (!isAmbient && bolusCheck.state != State.HIDDEN) {
            gapA[n] = OrbitFaceLogic.DEG_BOLUS - OrbitFaceLogic.BOLUS_GAP_DEG; gapB[n] = OrbitFaceLogic.DEG_BOLUS + OrbitFaceLogic.BOLUS_GAP_DEG; n++
        }
        // insertion sort by start (at most 7 gaps, no allocation)
        for (i in 1 until n) {
            val a = gapA[i]; val b = gapB[i]
            var j = i - 1
            while (j >= 0 && gapA[j] > a) { gapA[j + 1] = gapA[j]; gapB[j + 1] = gapB[j]; j-- }
            gapA[j + 1] = a; gapB[j + 1] = b
        }
        // the time gap is always there: segments run from gapT to 360 - gapT around the other gaps
        segCount = 0
        var from = gapT
        val end = 360f - gapT
        for (i in 0 until n) {
            if (gapA[i] > from) {
                segStart[segCount] = from; segSweep[segCount] = min(gapA[i], end) - from; segCount++
            }
            from = max(from, gapB[i])
        }
        if (end > from) {
            segStart[segCount] = from; segSweep[segCount] = end - from; segCount++
        }
    }

    /**
     * Hero group (spec §4.2/§4.3): BG + 14 + a 40 px glyph must fit 252; the size steps down by 4 (minimum 60).
     * The group is centred at x 225; without a glyph the digits re-centre. Builds the vector trend glyph.
     */
    private fun layoutHero() {
        setText(OrbitFaceLogic.HERO_REF, tf700)
        val w108 = text.measureText(heroText)
        val glyph = trend != Trend.NONE
        heroSize = OrbitFaceLogic.heroSize(w108, glyph)
        heroW = w108 * heroSize / OrbitFaceLogic.HERO_REF
        heroX = OrbitFaceLogic.CX - OrbitFaceLogic.heroGroupWidth(w108, heroSize, glyph) / 2f + heroW / 2f
        // Glass/Orbit UX round 3: the arrow box is floored (36 px) and set 0.12 em off the digits
        val glyphW = OrbitFaceLogic.heroGlyphW(heroSize)
        trendSize = glyphW * 48f / 44f
        trendX = heroX + heroW / 2f + OrbitFaceLogic.heroGlyphGap(heroSize) + glyphW / 2f
        trendPath.rewind()
        if (trend == Trend.NONE) return
        val rad = Math.toRadians(trend.deg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val shaft = 0.78f * trendSize
        val tipX = trendX + dx * shaft / 2f
        val tipY = HERO_Y + dy * shaft / 2f
        trendPath.moveTo(trendX - dx * shaft / 2f, HERO_Y - dy * shaft / 2f)
        trendPath.lineTo(tipX, tipY)
        for (k in 0 until trend.heads) {
            val hx = tipX - dx * 0.26f * trendSize * k
            val hy = tipY - dy * 0.26f * trendSize * k
            for (sign in HEAD_SIGNS) {
                val a = rad + Math.PI + sign * Math.toRadians(42.0)
                trendPath.moveTo(hx, hy)
                trendPath.lineTo(hx + sin(a).toFloat() * 0.30f * trendSize, hy - cos(a).toFloat() * 0.30f * trendSize)
            }
        }
    }

    private fun buildSparkline(d: ComplicationData, now: Long) {
        val bg = d.bgData
        sparkPath.rewind()
        predCount = 0
        val low = bg.low.takeIf { it > 0 } ?: DEFAULT_LOW_MGDL
        val high = bg.high.takeIf { it > 0 } ?: DEFAULT_HIGH_MGDL

        sparkWindowMs = timeFrameH * 60 * 60_000L
        val from = now - sparkWindowMs
        val points = d.graphData.entries.asSequence()
            .filter { it.timeStamp in from..now && it.sgv > 0 }
            .sortedBy { it.timeStamp }
            .toMutableList()
        if (bg.timeStamp in from..now && bg.sgv > 0 && (points.isEmpty() || points.last().timeStamp < bg.timeStamp)) points.add(bg)
        val predOn = bg.timeStamp in from..now && bg.sgv > 0 && showPredictions && !OrbitFaceLogic.isOld(bgAge)
        // Glass/Orbit UX round 3: the adaptive log range of Glass over everything drawn (history and forecast)
        var vMin = Double.NaN
        var vMax = Double.NaN
        fun see(v: Double) {
            if (v <= 0.0) return
            vMin = if (vMin.isNaN()) v else min(vMin, v)
            vMax = if (vMax.isNaN()) v else max(vMax, v)
        }
        for (p in points) see(p.sgv)
        if (predOn) {
            val last = bg.timeStamp + OrbitFaceLogic.PREDICTION_DOTS * OrbitFaceLogic.PREDICTION_STEP_MS + OrbitFaceLogic.PREDICTION_STEP_MS / 2
            for (p in d.treatmentData.predictions) if (p.timeStamp in bg.timeStamp..last) see(p.sgv)
        }
        val (lo, hi) = OrbitFaceLogic.sparkRange(vMin, vMax)
        fun sy(v: Double) = OrbitFaceLogic.sparkY(v, lo, hi, sparkTop, sparkBottom)
        lowY = sy(low)
        highY = sy(high)
        var prevT = Long.MIN_VALUE
        for (p in points) {
            val x = OrbitFaceLogic.sparkX(p.timeStamp, now, sparkWindowMs)
            val y = sy(p.sgv)
            val brk = prevT == Long.MIN_VALUE || OrbitFaceLogic.sparkBreak(prevT, p.timeStamp)
            if (brk) sparkPath.moveTo(x, y) else sparkPath.lineTo(x, y)
            prevT = p.timeStamp
        }
        hasNowDot = bg.timeStamp in from..now && bg.sgv > 0
        if (hasNowDot) {
            nowX = OrbitFaceLogic.sparkX(bg.timeStamp, now, sparkWindowMs)
            nowY = sy(bg.sgv)
        }
        if (predOn) {
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
                    predY[predCount] = sy(best.sgv)
                    predCount++
                }
            }
        }
    }

    // ---------------------------------------------------------------- shapes and bitmaps

    private fun buildShapes() {
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

        // confirm tick of the armed bolus button, 16 x 10 px centred on (0, 0) (Glass/Orbit UX round 1: replaces the
        // "nochmal" word, which did not fit the button at the 18 px floor)
        tickPath.rewind()
        tickPath.moveTo(-8f, 0f)
        tickPath.lineTo(-2.5f, 5f)
        tickPath.lineTo(8f, -5f)
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
            // rendered at the real size, never upscaled (spec §1)
            corona[i] = softBitmap(cw, ch, s) { c ->
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
                // Glass/Orbit UX round 3: hairline rings on true black (four filled discs read as a complication grid):
                // the satellites and the ring are one orbit system; only an alarm (amber, red) keeps a 2 px coloured rim
                fill.reset(); fill.isAntiAlias = true; fill.color = Color.BLACK
                c.drawCircle(m, m, r, fill)
                stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
                val alarm = ring != Ring.NEUTRAL && ring != Ring.DIM
                stroke.strokeWidth = if (alarm) 2f else 1f
                stroke.color = if (alarm) ringColour(ring) else SAT_RIM
                c.drawCircle(m, m, r - stroke.strokeWidth / 2f, stroke)
            }
        }
        // bolus button shells
        val bs = OrbitFaceLogic.BOLUS_HALF * 2f + 2 * BOLUS_PAD
        bolusIdle?.recycle()
        // Glass/Orbit UX round 3: the idle button is the family's filled indigo sphere (Glass, Halo) with white text,
        // so it never reads as a fifth outlined data satellite
        bolusIdle = softBitmap(bs, bs, s) { c ->
            val m = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF
            val r = OrbitFaceLogic.BOLUS_HALF
            fill.reset(); fill.isAntiAlias = true
            fill.color = withAlpha(INDIGO, 90)
            fill.maskFilter = BlurMaskFilter(6f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(m, m, r + 1f, fill)
            fill.maskFilter = null
            fill.color = Color.WHITE
            fill.shader = RadialGradient(m - 4f, m - 8f, r + 8f, SPHERE_LIGHT, SPHERE_DARK, Shader.TileMode.CLAMP)
            c.drawCircle(m, m, r, fill)
            fill.shader = null
            fill.color = withAlpha(Color.WHITE, 60)
            fill.maskFilter = BlurMaskFilter(3f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawOval(m - 15f, m - 22f, m + 4f, m - 11f, fill)
            fill.maskFilter = null
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1f; stroke.color = withAlpha(BOLUS_TEXT, 110)
            c.drawCircle(m, m, r - 0.5f, stroke)
        }
        bolusArmed?.recycle()
        bolusArmed = softBitmap(bs, bs, s) { c ->
            val a = BOLUS_PAD
            val b = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF * 2f
            fill.reset(); fill.isAntiAlias = true
            fill.color = withAlpha(ARMED_FILL, 90)
            fill.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
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
            // sys.simplified (charging / simplify_ui): the shared SimpleUi, no Orbit variant
            if (!isHeadless && simpleUi.isEnabled(currentWatchMode)) {
                simpleUi.setAntiAlias(currentWatchMode)
                simpleUi.onDraw(canvas, data.bgData)
                return
            }
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

        // 2. orbit track with the time, date and satellite breaks
        drawTrack(canvas, 1.5f, TRACK, true)

        // 3. sparkline (Glass/Orbit UX round 3): no box, the trace floats inside the orbit; the target range is a 10 %
        // tint only (green while in range, neutral otherwise), on the adaptive scale
        val inBand = range == Range.IN
        setFill(if (inBand) withAlpha(GREEN, 26) else withAlpha(Color.WHITE, 9), true) // out of range: barely there, no grey box
        rect.set(OrbitFaceLogic.SPARK_X0, highY, OrbitFaceLogic.SPARK_X1, lowY)
        canvas.drawRect(rect, fill)
        setStroke(2.5f, rangeColour, true)
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeCap = Paint.Cap.ROUND
        if (!old) stroke.setShadowLayer(4f, 0f, 0f, withAlpha(rangeColour, 150))
        canvas.drawPath(sparkPath, stroke)
        stroke.clearShadowLayer()
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
        if (captionUntil != 0L && System.currentTimeMillis() < captionUntil) {
            // graph.timeframe_changed: "5 Std" top-left of the sparkline box
            setText(floorPx(OrbitFaceLogic.MIN_TEXT_PX), tf600)
            text.textAlign = Paint.Align.LEFT
            text.color = TXT2
            canvas.drawText(captionText, OrbitFaceLogic.SPARK_X0, sparkTop - 4f, text)
        }

        // 5. delta row; the pill row under it (TIEF/HOCH, status slot) has its own line, never over the delta
        val s = slot
        if (slotInDeltaRow && s != null) drawPill(canvas, s, OrbitFaceLogic.CX, OrbitFaceLogic.SLOT_IN_DELTA_Y, OrbitFaceLogic.SLOT_H, SLOT_TEXT, ambient = false, OrbitFaceLogic.SLOT_IN_DELTA_MAX_WIDTH)
        else if (!firstStart) drawDeltaRow(canvas, DELTA_C, if (deltaRow.ageAmber) AMBER else DELTA_C, true, ambient = false)
        drawPillRow(canvas, if (s != null && !slotInDeltaRow) s else null, ambient = false)

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

        // 8. time, date (+ TT)
        drawTime(canvas, TIME_C, tf600, true)
        drawDateLine(canvas, DATE_C, VIOLET, true)

        // 9. minute moon
        if (moon.kind == MoonKind.DOT) {
            setFill(MOON_C, true)
            canvas.drawCircle(OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, moon.deg), OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, moon.deg), 4.5f, fill)
        }
    }

    /**
     * Ambient (spec §6): greys only (white in low-bit, AA off), no corona/glow/moon/bolus/targets/predictions.
     * The range is carried by the track's shape, so colour is never the only signal: solid ambLine for in range /
     * stale (dropped in low-bit), fine 2/7 dots 1 px amb2 for HIGH, one 2 px ring with long dashes for LOW, one solid
     * 2 px amb1 ring for URGENT LOW. Burn-in: outlined digits, a thin solid time, satellites as values only (no rings), hollow dots/bars,
     * strokes <= 2 px (3 for an urgent-low BG), per-minute [-4, 4] shift.
     */
    private fun drawAmbient(canvas: Canvas) {
        val aa = !lowBitAmbient
        val burnIn = burnInProtection
        when (range) {
            Range.HIGH, Range.VERY_HIGH -> {
                drawTrack(canvas, 1f, amb(AMB2), aa, ambDash)
            }

            // Glass/Orbit UX round 3 (burn-in): no static full ring for hours: urgent low keeps its 2 px weight in long
            // dashes (still unlike low's 22/6 rhythm), in range / stale draws no ring at all
            Range.URGENT_LOW            -> if (burnIn) drawTrack(canvas, 2f, amb(AMB2), aa, ambUrgentDash) else drawTrack(canvas, 2f, amb(AMB2), aa)

            // UX round 3 (safety): low and urgent low differ by SHAPE, not by grey level (both are white in low-bit):
            // low = one 2 px ring of long dashes, urgent low = one solid 2 px ring (plus the double-outlined TIEF badge)
            Range.LOW                   -> drawTrack(canvas, 2f, amb(AMB2), aa, ambLowDash)
            else                        -> if (!lowBitAmbient && !burnIn) drawTrack(canvas, 1f, AMB_LINE, aa)
        }

        // sparkline: history only
        setStroke(1.5f, amb(AMB3), aa)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(sparkPath, stroke)
        if (hasNowDot) {
            setStroke(1.2f, amb(NOW_HOLLOW), aa)
            canvas.drawCircle(nowX, nowY, 4f, stroke)
        }

        drawHero(canvas, amb(AMB1), amb(AMB1), glow = false, ambient = true)
        val s = slot
        when {
            // W1..W3 (incl. the stale "Keine Daten · 17′") own slot A as an outline pill
            s != null && slotInDeltaRow -> drawPill(canvas, s, OrbitFaceLogic.CX, OrbitFaceLogic.SLOT_IN_DELTA_Y, OrbitFaceLogic.SLOT_H, SLOT_TEXT, ambient = true, OrbitFaceLogic.SLOT_IN_DELTA_MAX_WIDTH)
            !firstStart                 -> drawDeltaRow(canvas, amb(AMB2), amb(if (deltaRow.ageAmber) AMB1 else AMB2), aa, ambient = true)
        }
        drawPillRow(canvas, if (s != null && !slotInDeltaRow) s else null, ambient = true)

        // every satellite as value + label only (UX round 4: no disc outlines, the orbit ring is the one circle).
        // Glass/Orbit UX round 3: all four (LOOP and IOB alone left two lopsided numbers at 10 and 2 o'clock)
        for (i in balls.indices) {
            val b = balls[i] ?: continue
            val x = ballX[i]
            val y = ballY[i]
            setFill(Color.BLACK, aa)
            canvas.drawCircle(x, y, OrbitFaceLogic.BALL_R, fill)
            if (b.value == "--" && !b.pause) continue
            if (b.pause) drawPause(canvas, x, y - BALL_VALUE_DY, amb(AMB1), aa, hollow = true)
            else {
                val size = ballSize[i]
                drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - BALL_VALUE_DY, size, tf400, amb(AMB1), aa)
            }
            if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + BALL_LABEL_DY, floorPx(OrbitFaceLogic.BALL_LABEL_SIZE), tf600, amb(AMB2), aa, LABEL_TRACKING)
        }

        // burn-in: the small time solid in a thin weight (an outlined 23 px time smeared into a double stroke)
        drawTime(canvas, amb(AMB1), if (burnIn) tf300 else tf400, aa)
        drawDateLine(canvas, amb(AMB2), amb(AMB2), aa)
    }

    /** Time with the optional AM/PM suffix (locale.en_12h), below the system status icon (UX round 3). */
    private fun drawTime(canvas: Canvas, colour: Int, tf: Typeface, aa: Boolean) {
        setText(OrbitFaceLogic.TIME_SIZE, tf, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        val y = baseline(OrbitFaceLogic.TIME_Y)
        canvas.drawText(timeText, timeX, y, text)
        if (amPmText.isEmpty()) return
        setText(floorPx(AMPM_SIZE), tf500, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = if (isAmbient) amb(AMB2) else TXT2
        canvas.drawText(amPmText, amPmX, y, text)
    }

    private fun drawTrack(canvas: Canvas, width: Float, colour: Int, aa: Boolean, effect: DashPathEffect? = null) {
        setStroke(width, colour, aa)
        stroke.pathEffect = effect
        // canvas angles start at 3 o'clock: clockwise-from-12 minus 90
        for (i in 0 until segCount) canvas.drawArc(trackRect, segStart[i] - 90f, segSweep[i], false, stroke)
        stroke.pathEffect = null
    }

    /** The date (and an active TT in [ttColour]) centred on the ring at 6 o'clock. */
    private fun drawDateLine(canvas: Canvas, colour: Int, ttColour: Int, aa: Boolean) {
        if (lineW <= 0f) return
        val y = OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f)
        val x0 = OrbitFaceLogic.CX - lineW / 2f
        setText(dateSize, tf500, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        val by = baseline(y)
        if (lineDate.isNotEmpty()) canvas.drawText(lineDate, x0, by, text)
        if (lineTt.isEmpty()) return
        setText(dateSize, tf600, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = ttColour
        canvas.drawText(lineTt, x0 + lineDateW, by, text)
    }

    private fun drawHero(canvas: Canvas, colour: Int, arrowColour: Int, glow: Boolean, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val dashes = heroText == "---"
        val urgent = range == Range.URGENT_LOW
        // ambient: Light, urgent low one weight step up (Regular)
        setText(heroSize, if (ambient) (if (urgent) tf400 else tf300) else tf700, aa)
        text.color = if (dashes && !ambient) TXT2 else colour
        if (ambient && burnInProtection) {
            // burn-in: outlined digits (black inside), 2 px, 3 px for urgent low
            text.style = Paint.Style.STROKE
            text.strokeWidth = if (urgent) 3f else 2f
        }
        // UX round 3: a tighter, fainter glow (12 px at 110/150 made the digits look blurry and neon)
        if (glow) text.setShadowLayer(7f, 0f, 0f, withAlpha(rangeColour, if (OrbitFaceLogic.emphasis(range)) 110 else 80))
        canvas.drawText(heroText, heroX, baseline(HERO_Y), text)
        text.clearShadowLayer()
        var right = heroX + heroW / 2f
        if (trend != Trend.NONE) {
            // Glass/Orbit UX round 1: 5 px (3 px ambient, 2 px with burn-in protection), the trend reads next to the digits
            // Glass/Orbit UX round 2: 5 -> 9.5 px, the arrow carries the weight of the bold digits (half their stroke)
            // Glass/Orbit UX round 3: never under 7 px interactive, so a stepped-down hero keeps a readable arrow
            setStroke(if (!ambient) max(ARROW_STROKE * heroSize / OrbitFaceLogic.HERO_SIZE, ARROW_STROKE_MIN) else if (burnInProtection) 2f else 3f, arrowColour, aa)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(trendPath, stroke)
            right = trendX + trendSize * 0.39f
        }
        if (OrbitFaceLogic.isStruck(bgAge) && !dashes) {
            setStroke(if (!ambient) 3f else if (lowBitAmbient) 2f else 1.5f, colour, aa)
            canvas.drawLine(heroX - heroW / 2f - 6f, HERO_Y + 4f, max(right, heroX + heroW / 2f + 6f), HERO_Y + 4f, stroke)
        }
    }

    /** Delta · age, the phone-off glyph and (UX round 3) the TIEF/HOCH caption after them, centred as one group. */
    private fun drawDeltaRow(canvas: Canvas, colour: Int, ageColour: Int, aa: Boolean, ambient: Boolean) {
        val textShown = !deltaRow.isEmpty || phoneGlyph
        if (!textShown && !captionInDelta) return
        val word = if (caption == FaceState.Caption.HIGH_WORD) labels.sentinelHigh else labels.sentinelLow
        val capW = if (captionInDelta) captionWidth(word, ambient) else 0f
        val capGap = if (captionInDelta && textShown) OrbitFaceLogic.CAPTION_GAP else 0f
        val ds = OrbitFaceLogic.DELTA_SIZE
        setText(ds, if (ambient) tf400 else tf500, aa)
        val wl = if (deltaRow.isEmpty) 0f else text.measureText(deltaRow.deltaPart)
        val wa = if (deltaRow.isEmpty) 0f else text.measureText(deltaRow.agePart)
        val glyphW = if (phoneGlyph) 26f else 0f
        val size = max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(wl + wa + glyphW, ds, OrbitFaceLogic.DELTA_ROW_MAX_WIDTH - capW - capGap))
        text.textSize = size
        val k = size / ds
        text.textAlign = Paint.Align.LEFT
        val textW = (wl + wa) * k + glyphW
        val x0 = OrbitFaceLogic.CX - (textW + capGap + capW) / 2f
        val y = baseline(DELTA_ROW_Y)
        if (!deltaRow.isEmpty) {
            text.color = colour
            canvas.drawText(deltaRow.deltaPart, x0, y, text)
            text.color = ageColour
            canvas.drawText(deltaRow.agePart, x0 + wl * k, y, text)
        }
        if (phoneGlyph) drawPhoneOff(canvas, x0 + (wl + wa) * k + 6f + 10f, DELTA_ROW_Y, colour, aa)
        if (captionInDelta) drawCaptionPill(canvas, word, x0 + textW + capGap + capW / 2f, DELTA_ROW_Y, OrbitFaceLogic.CAPTION_H, capW, ambient)
    }

    /** Phone-off glyph (conn.phone_lost_fresh): a phone outline crossed by a slash, 20 px tall (UX round 2: 14 was unreadable). */
    private fun drawPhoneOff(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean) {
        setStroke(1.8f, colour, aa)
        rect.set(x - 6f, y - 10f, x + 6f, y + 10f)
        canvas.drawRoundRect(rect, 2f, 2f, stroke)
        stroke.strokeWidth = 2f
        canvas.drawLine(x - 10f, y + 10f, x + 10f, y - 10f, stroke)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean, hollow: Boolean) {
        if (hollow) setStroke(1.2f, colour, aa) else setFill(colour, aa)
        for (ox in PAUSE_OFFSETS) {
            rect.set(x + ox - 2f, y - 7f, x + ox + 2f, y + 7f)
            if (hollow) canvas.drawRect(rect, stroke) else canvas.drawRoundRect(rect, 1f, 1f, fill)
        }
    }

    /**
     * The pill row under the delta (UX round 1): the TIEF/HOCH word as a pill (moved out of the hero, where it was cramped
     * next to the glyph) and the status slot pill W4-W9, side by side and centred as a group.
     */
    /** Total width of the pill row for [s] (0 when nothing is drawn); the date gap at 6 o'clock is cut to it. */
    /**
     * Glass/Orbit UX round 3 (time/date in every state): a W4-W9 pill keeps the short date ("Sa 17.", 18 px) to its left,
     * centred with it as one group; the pill text shrinks to what is left (never below the floor).
     */
    private fun shortDateWidth(s: Slot?, ambient: Boolean): Float {
        if (s == null || shortDateText.isEmpty()) return 0f
        setText(floorPx(SHORT_DATE_SIZE), if (ambient) tf400 else tf500, !(ambient && lowBitAmbient))
        return text.measureText(shortDateText)
    }

    private fun pillRowWidth(s: Slot?, ambient: Boolean): Float {
        val cap = captionShown && !captionInDelta
        if (!cap && s == null) return 0f
        val word = if (caption == FaceState.Caption.HIGH_WORD) labels.sentinelHigh else labels.sentinelLow
        val capW = if (cap) captionWidth(word, ambient) else 0f
        val gap = if (cap && s != null) PILL_GAP else 0f
        val dateW = shortDateWidth(s, ambient)
        val dateGap = if (dateW > 0f) PILL_GAP else 0f
        // next to the date the pill is set 4 px tighter on each side (the row's ends stay 14 px off the bezel)
        val pad = if (dateW > 0f) PILL_TEXT_PAD_TIGHT else PILL_TEXT_PAD
        val slotMax = min(OrbitFaceLogic.SLOT_MAX_WIDTH, OrbitFaceLogic.PILL_ROW_MAX_WIDTH - dateW - dateGap - (if (dateW > 0f) DATE_ROW_EDGE else 0f) - capW - gap - 2f * pad - (if (s?.tone == Tone.ALERT && !ambient) 20f else 0f))
        val slotW = if (s != null) 2f * pillHalf(s, SLOT_TEXT, ambient, slotMax, pad) else 0f
        return dateW + dateGap + capW + gap + slotW
    }

    private fun drawPillRow(canvas: Canvas, s: Slot?, ambient: Boolean) {
        val cap = captionShown && !captionInDelta
        if (!cap && s == null) return
        val word = if (caption == FaceState.Caption.HIGH_WORD) labels.sentinelHigh else labels.sentinelLow
        val capW = if (cap) captionWidth(word, ambient) else 0f
        val gap = if (cap && s != null) PILL_GAP else 0f
        val dateW = shortDateWidth(s, ambient)
        val dateGap = if (dateW > 0f) PILL_GAP else 0f
        // next to the date the pill is set 4 px tighter on each side (the row's ends stay 14 px off the bezel)
        val pad = if (dateW > 0f) PILL_TEXT_PAD_TIGHT else PILL_TEXT_PAD
        // the row sits on the ring at 6 o'clock (the date's place): the slot text shrinks to what the date and caption leave
        val slotMax = min(OrbitFaceLogic.SLOT_MAX_WIDTH, OrbitFaceLogic.PILL_ROW_MAX_WIDTH - dateW - dateGap - (if (dateW > 0f) DATE_ROW_EDGE else 0f) - capW - gap - 2f * pad - (if (s?.tone == Tone.ALERT && !ambient) 20f else 0f))
        val slotW = if (s != null) 2f * pillHalf(s, SLOT_TEXT, ambient, slotMax, pad) else 0f
        val x0 = OrbitFaceLogic.CX - (dateW + dateGap + capW + gap + slotW) / 2f
        if (dateW > 0f) {
            val aa = !(ambient && lowBitAmbient)
            setText(floorPx(SHORT_DATE_SIZE), if (ambient) tf400 else tf500, aa)
            text.textAlign = Paint.Align.LEFT
            text.color = if (ambient) amb(AMB2) else TXT2
            canvas.drawText(shortDateText, x0, baseline(OrbitFaceLogic.SLOT_Y), text)
        }
        val x1 = x0 + dateW + dateGap
        if (cap) drawCaptionPill(canvas, word, x1 + capW / 2f, OrbitFaceLogic.SLOT_Y, OrbitFaceLogic.SLOT_H, capW, ambient)
        if (s != null) drawPill(canvas, s, x1 + capW + gap + slotW / 2f, OrbitFaceLogic.SLOT_Y, OrbitFaceLogic.SLOT_H, SLOT_TEXT, ambient, slotMax, pad)
    }

    private fun captionWidth(word: String, ambient: Boolean): Float {
        setText(CAPTION_TEXT, tf700, !(ambient && lowBitAmbient))
        text.letterSpacing = 0.04f
        return text.measureText(word) + 2f * CAPTION_PAD
    }

    /**
     * "TIEF" (low: tinted), "TIEF" filled red with near-black text (urgent low; UX round 3: white was 3.0:1), "HOCH"
     * (very high: tinted). Ambient (UX round 3, by shape): urgent low a double outline (2 px + 1 px, burn-in safe),
     * low and high the plain word.
     */
    private fun drawCaptionPill(canvas: Canvas, word: String, cx: Float, cy: Float, h: Float, w: Float, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val badge = caption == FaceState.Caption.LOW_BADGE
        val high = caption == FaceState.Caption.HIGH_WORD
        captionBox.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        if (ambient) {
            if (badge) {
                setStroke(2f, amb(AMB1), aa)
                canvas.drawRoundRect(captionBox, h / 2f, h / 2f, stroke)
                captionBox.inset(3.5f, 3.5f)
                stroke.strokeWidth = 1f
                canvas.drawRoundRect(captionBox, h / 2f - 3.5f, h / 2f - 3.5f, stroke)
            }
        } else {
            setFill(if (badge) RED else if (high) PILL_WARN else PILL_ALERT, true)
            canvas.drawRoundRect(captionBox, h / 2f, h / 2f, fill)
            if (!badge) {
                setStroke(1.5f, if (high) AMBER else RED, true)
                canvas.drawRoundRect(captionBox, h / 2f, h / 2f, stroke)
            }
        }
        drawText(
            canvas, word, cx, cy, CAPTION_TEXT, tf700, when {
                ambient -> amb(AMB1)
                badge   -> BADGE_TXT
                high    -> WARN_TXT
                else    -> LOW_TXT
            }, aa, 0.04f
        )
    }

    private fun pillHalf(s: Slot, textSize: Float, ambient: Boolean, maxText: Float = OrbitFaceLogic.SLOT_MAX_WIDTH, pad: Float = PILL_TEXT_PAD): Float {
        setText(textSize, if (ambient) tf500 else tf600, !(ambient && lowBitAmbient))
        val iconW = if (s.tone == Tone.ALERT && !ambient) 14f + 6f else 0f
        return (iconW + pillTextWidth(s.text, textSize, maxText)) / 2f + pad
    }

    /** Pill text size: shrunk to [maxText], never below the 18 px floor (the strings are short enough there). */
    private fun pillTextSize(s: String, textSize: Float, maxText: Float): Float =
        max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(text.measureText(s), textSize, maxText))

    /** Text width at [pillTextSize] (the paint is set to [textSize]). */
    private fun pillTextWidth(s: String, textSize: Float, maxText: Float): Float =
        text.measureText(s) * pillTextSize(s, textSize, maxText) / textSize

    /** Status slot pill (states.md §4.2). Interactive: tinted fill + outline; ambient: outline only. */
    private fun drawPill(canvas: Canvas, s: Slot, cx: Float, cy: Float, height: Float, textSize: Float, ambient: Boolean, maxText: Float = OrbitFaceLogic.SLOT_MAX_WIDTH, pad: Float = PILL_TEXT_PAD) {
        val aa = !(ambient && lowBitAmbient)
        val bg = when {
            ambient              -> Color.BLACK
            s.tone == Tone.ALERT -> PILL_ALERT
            s.tone == Tone.WARN  -> PILL_WARN
            else                 -> PILL_INFO
        }
        val line = when {
            ambient              -> amb(AMB2)
            s.tone == Tone.ALERT -> RED
            s.tone == Tone.WARN  -> AMBER
            else                 -> NEUTRAL_RING
        }
        val fg = when {
            ambient              -> amb(AMB1)
            s.tone == Tone.ALERT -> LOW_TXT
            s.tone == Tone.WARN  -> WARN_TXT
            else                 -> TXT2
        }
        val tf = if (ambient) tf500 else tf600
        setText(textSize, tf, aa)
        val icon = s.tone == Tone.ALERT && !ambient
        val iconW = if (icon) 14f + 6f else 0f
        val tw = pillTextWidth(s.text, textSize, maxText)
        val k = pillTextSize(s.text, textSize, maxText) / textSize
        val half = (iconW + tw) / 2f + pad
        rect.set(cx - half, cy - height / 2f, cx + half, cy + height / 2f)
        setFill(bg, aa)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        setStroke(if (ambient) 1f else 1.5f, line, aa)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, stroke)
        val x0 = cx - (iconW + tw) / 2f
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
        setText(textSize * k, tf, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = fg
        canvas.drawText(s.text, x0 + iconW, baseline(cy), text)
    }

    private fun drawBall(canvas: Canvas, i: Int) {
        val b = balls[i] ?: return
        val x = ballX[i]
        val y = ballY[i]
        val r = OrbitFaceLogic.BALL_R
        val muted = i == OrbitFaceLogic.IDX_LOOP && loopMuted && b.ring == Ring.RED
        // UX round 3: an unknown value ("--": first start, no profile) draws "--" over its label again (an empty disc
        // read as a rendering fault)
        if (muted) {
            // red budget without losing contrast: the loop state is carried by SHAPE (a dashed red ring), the age stays white
            setFill(Color.BLACK, true)
            canvas.drawCircle(x, y, r, fill)
            setStroke(2f, RED, true)
            stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, r - 1f, stroke)
            stroke.pathEffect = null
        } else if (b.dashed) {
            setFill(Color.BLACK, true)
            canvas.drawCircle(x, y, r, fill)
            // loop.never: dim 2 px; loop.open: neutral 1.6 px (UX round 3: rings are neutral unless they alarm)
            setStroke(if (b.ring == Ring.DIM) 2f else 1.6f, if (b.ring == Ring.DIM) DIM_RING else NEUTRAL_RING, true)
            stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, r - 1f, stroke)
            stroke.pathEffect = null
        } else {
            // UX round 3: one ring style; only an alarm (amber, red) keeps a coloured ring, the value carries the hue
            val shellRing = when (b.ring) {
                Ring.AMBER, Ring.RED, Ring.DIM -> b.ring
                else                           -> Ring.NEUTRAL
            }
            val shell = shells[shellRing.ordinal]
            if (shell != null) {
                val half = r + SHELL_PAD
                shellDst.set(x - half, y - half, x + half, y + half)
                canvas.drawBitmap(shell, null, shellDst, bmpPaint)
            }
        }
        val dim = b.ring == Ring.DIM || b.dimValue
        // UX round 3: labels neutral; the value carries the hue (loop green, IOB cyan, TBR teal, TT violet)
        val labelColour = if (b.ring == Ring.DIM) DIM_TXT else LBL_NEUTRAL
        val valueColour = when {
            dim                   -> DIM_TXT
            b.ring == Ring.GREEN  -> GREEN
            b.ring == Ring.CYAN   -> CYAN
            b.ring == Ring.TEAL   -> TEAL
            b.ring == Ring.VIOLET -> VIOLET
            else                  -> VAL_TXT
        }
        if (b.pause) drawPause(canvas, x, y - BALL_VALUE_DY, AMBER, true, hollow = false)
        else {
            val size = ballSize[i]
            val tf = if (size < OrbitFaceLogic.BALL_VALUE_SIZE) tf700 else tf600
            drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - BALL_VALUE_DY, size, tf, valueColour, true)
        }
        if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + BALL_LABEL_DY, floorPx(OrbitFaceLogic.BALL_LABEL_SIZE), tf600, labelColour, true, LABEL_TRACKING)
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
            val remaining = (1f - (System.currentTimeMillis() - guard.armedAt).toFloat() / BolusTapGesture.WINDOW_MS).coerceIn(0f, 1f)
            outlineSegment.rewind()
            if (outlineMeasure.getSegment(0f, outlineLength * remaining, outlineSegment, true)) {
                stroke.color = Color.WHITE
                canvas.drawPath(outlineSegment, stroke)
            }
            drawText(canvas, bolusAmountText, bolusX, bolusY - 7f, 20f, tf700, Color.WHITE, true)
            // confirm tick: "tap again to send" (the countdown rim runs out around it)
            setStroke(2.6f, Color.WHITE, true)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.save()
            canvas.translate(bolusX, bolusY + 13f)
            canvas.drawPath(tickPath, stroke)
            canvas.restore()
        } else if (state == State.SENT) {
            drawText(canvas, bolusAmountText, bolusX, bolusY - 7f, 19f, tf700, withAlpha(BOLUS_TEXT, alpha), true)
            drawText(canvas, "…", bolusX, bolusY + 13f, 18f, tf700, withAlpha(BOLUS_TEXT, alpha), true)
        } else {
            val disabled = state == State.DISABLED
            if (disabled) {
                // disabled by SHAPE, not only by alpha: dashed outline and (UX round 3) a faint slash UNDER the text, no
                // black text halo (the knock-out box read as a rendering glitch at wrist distance)
                setStroke(2f, withAlpha(INDIGO, 150), true)
                stroke.pathEffect = disabledDash
                canvas.drawPath(outlinePath, stroke)
                stroke.pathEffect = null
                setStroke(2f, withAlpha(TXT3, 102), true)
                stroke.strokeCap = Paint.Cap.ROUND
                val k = OrbitFaceLogic.BOLUS_HALF * 0.62f // inside the round button
                canvas.drawLine(bolusX - k, bolusY + k, bolusX + k, bolusY - k, stroke)
            }
            drawText(canvas, bolusAmountText, bolusX, bolusY - 7f, 19f, tf700, if (disabled) TXT3 else Color.WHITE, true)
            drawText(canvas, "U", bolusX, bolusY + 13f, BOLUS_SUB_SIZE, tf600, if (disabled) TXT3 else BOLUS_TEXT, true)
        }
        if (flashUntil != 0L && System.currentTimeMillis() < flashUntil) {
            // request sent: white rim flash for SEND_FLASH_MS
            setStroke(3f, Color.WHITE, true)
            canvas.drawPath(outlinePath, stroke)
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

    /** Text sizes never go below the family floor of 18 physical px (Glass/Orbit UX round 1: was 11). */
    private fun floorPx(size: Float): Float = max(size, OrbitFaceLogic.MIN_TEXT_PX / max(scale, 0.01f))

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
        if (tapType != TapType.UP || isAmbient || isHeadless) return
        val left = (faceRect.width() - 450f * scale) / 2f
        val top = (faceRect.height() - 450f * scale) / 2f
        val dx = (x - left) / scale
        val dy = (y - top) / scale

        refreshBolusCheck()
        // 24 dp radius whatever the screen (the family's 48 dp target)
        val hitR = max(OrbitFaceLogic.BOLUS_HIT_R, 24f * resources.displayMetrics.density / max(scale, 0.01f))
        val inside = bolusCheck.state != State.HIDDEN && BolusTapGesture.isInside(dx, dy, bolusX, bolusY, hitR)
        // The guard re-checks every rule and drops a duplicate delivery of the same tap event.
        val outcome = guard.onTap(eventTime, inside, bolusInput())
        if (outcome.result == TapResult.DUPLICATE) return
        // a tap on the button re-checks the phone link, so a lost phone shows up without waiting for the minute
        // (on ARM this is also the fresh answer that SEND needs)
        if (inside) refreshPhoneReachable()
        when (outcome.result) {
            TapResult.REFUSED   -> {
                handler.removeCallbacks(countdownTick)
                toast(
                    when (outcome.reason) {
                        Reason.PENDING        -> R.string.orbit_bolus_pending
                        Reason.STALE_DATA     -> R.string.orbit_bolus_refused_stale
                        Reason.OVER_MAX_BOLUS -> R.string.orbit_bolus_refused_max
                        Reason.NO_MAX_BOLUS   -> R.string.orbit_bolus_refused_max_unknown
                        else                  -> R.string.orbit_bolus_refused_phone
                    }
                )
                dirty = true
                invalidate()
                return
            }

            TapResult.ARM       -> {
                vibrate()
                handler.removeCallbacks(countdownTick)
                handler.postDelayed(countdownTick, 100L)
                invalidate()
                return
            }

            TapResult.SEND      -> {
                handler.removeCallbacks(countdownTick)
                aapsLogger.info(LTag.WEAR, "OrbitWatchface: double-tap bolus request $bolusAmount U")
                // Exactly as BolusActivity: the phone checks and the watch shows the confirmation screen.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(bolusAmount, 0)))
                dirty = true
                flashUntil = System.currentTimeMillis() + OrbitFaceLogic.BOLUS_SEND_FLASH_MS
                handler.removeCallbacks(redraw)
                handler.postDelayed(redraw, OrbitFaceLogic.BOLUS_SEND_FLASH_MS + 10L)
                invalidate()
                return
            }

            TapResult.DISARM    -> {
                handler.removeCallbacks(countdownTick)
                invalidate()
            }

            TapResult.IGNORE, TapResult.DUPLICATE -> Unit
        }
        if (outcome.onButton) return

        // tap.menu: a single tap on the time opens the main menu
        if (dx in 155f..295f && dy in 50f..100f) {
            openMenu(eventTime)
            return
        }
        // tap.graph: single tap opens the menu once the double-tap window passed; a double tap within the window
        // cycles the chart time frame (1..5 h) and shows a caption for 1,5 s
        if (dx in GRAPH_X0..GRAPH_X1 && dy in GRAPH_Y0..GRAPH_Y1) {
            if (graphTapTime != 0L && eventTime - graphTapTime < OrbitFaceLogic.GRAPH_DOUBLE_TAP_MS) {
                handler.removeCallbacks(graphSingleTap)
                graphTapTime = 0L
                cycleTimeFrame()
            } else {
                graphTapTime = eventTime
                handler.removeCallbacks(graphSingleTap)
                handler.postDelayed(graphSingleTap, OrbitFaceLogic.GRAPH_DOUBLE_TAP_MS)
            }
        }
    }

    private fun cycleTimeFrame() {
        val next = OrbitFaceLogic.nextTimeFrame(timeFrameH)
        sp.putString(R.string.key_chart_time_frame, next.toString())
        timeFrameH = next
        captionText = String.format(Locale.ROOT, labels.hoursFormat, next)
        captionUntil = System.currentTimeMillis() + CAPTION_MS
        dirty = true
        handler.removeCallbacks(redraw)
        handler.postDelayed(redraw, CAPTION_MS + 10L)
        invalidate()
    }

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

        private const val HERO_Y = OrbitFaceLogic.HERO_Y
        /** Delta row centre: low enough that the German comma's tail of the 120 px digits stays clear of it. */
        private const val DELTA_ROW_Y = OrbitFaceLogic.DELTA_ROW_Y
        private const val DATE_SIZE = 22f // Glass/Orbit UX round 3: Halo's rhythm, 22 px in TXT2
        private const val DATE_MIN_SIZE = 20f
        private const val AMPM_SIZE = 18f
        private const val MAX_SEGMENTS = 8
        private const val SLOT_TEXT = OrbitFaceLogic.SLOT_TEXT
        private const val CAPTION_TEXT = 22f
        private const val CAPTION_PAD = 12f
        private const val PILL_GAP = 8f
        private const val SHORT_DATE_SIZE = 18f
        /** The short date's text corner sits nearer the bezel than a pill's rounded end: 12 px of the row budget kept back. */
        private const val DATE_ROW_EDGE = 12f
        private const val PILL_TEXT_PAD = 14f
        private const val PILL_TEXT_PAD_TIGHT = 10f
        private const val BALL_VALUE_DY = 8f
        private const val BALL_LABEL_DY = 16f
        private const val BOLUS_SUB_SIZE = OrbitFaceLogic.MIN_TEXT_PX
        private val HEAD_SIGNS = intArrayOf(-1, 1)
        private const val AMPM_GAP = 3f
        /** Corner = half side: the bolus button is a round ball like Halo's and Glass' (UX round 1). */
        private const val BOLUS_CORNER = OrbitFaceLogic.BOLUS_HALF
        private const val BOLUS_PAD = 16f
        private const val CAPTION_MS = 1_500L

        /** Graph tap zone (spec §9), design px. */
        private const val GRAPH_X0 = OrbitFaceLogic.SPARK_X0
        private const val GRAPH_X1 = OrbitFaceLogic.SPARK_X1
        private const val GRAPH_Y0 = 290f
        private const val GRAPH_Y1 = 378f
        private const val SHELL_PAD = 6f
        private val PAUSE_OFFSETS = floatArrayOf(-4f, 4f)

        /** Bitmap margin around the corona ellipse, and the blur radius (a 60 px Gaussian of the mockup). */
        private const val CORONA_BLUR = 120f
        private const val CORONA_BLUR_RADIUS = 100f
        private const val CORONA_EMPHASIS_ALPHA = 36   // UX round 3: fainter (was 41), the BG read neon
        private const val CORONA_NORMAL_ALPHA = 150    // Glass/Orbit UX round 3: 200 -> 150, the high haze read neon
        private const val LABEL_TRACKING = 0.04f // Glass/Orbit UX round 2: tracked small labels (LOOP, IOB, KH)
        private const val ARROW_STROKE = 9.5f
        private const val ARROW_STROKE_MIN = 7f
        private const val DISABLED_ALPHA = 102         // 40 %
        private const val SENT_ALPHA = 140             // 55 %
        private const val DEFAULT_LOW_MGDL = 70.0
        private const val DEFAULT_HIGH_MGDL = 180.0

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val TXT1 = 0xFFEEF1F7.toInt()
        private const val AMB1 = 0xFFE0E0E0.toInt()
        private const val AMB2 = 0xFF9A9A9A.toInt()
        private const val AMB3 = 0xFF6E6E6E.toInt()
        private const val AMB_LINE = 0xFF3C3C3C.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val LOW_TXT = 0xFFFFD6DC.toInt()
        private const val STALE_C = 0xFF8A93A8.toInt()
        private const val CYAN = 0xFF5AC8FA.toInt()
        private const val TEAL = 0xFF3DDBD9.toInt()
        private const val VIOLET = 0xFFB388FF.toInt()
        // UX round 4: neutral rings darker (5A6B85 -> 3A4250) so the hero stays dominant; dim one step darker still
        private const val NEUTRAL_RING = 0xFF3A4250.toInt()
        private const val LBL_NEUTRAL = 0xFF9AA6B8.toInt()
        private const val DIM_RING = 0xFF2C323D.toInt()
        private const val DIM_TXT = 0xFF7D8796.toInt()
        private const val TXT2 = 0xFF9AA6C4.toInt()
        private const val TXT3 = 0xFF6F7A99.toInt()
        private const val WARN_TXT = 0xFFFFE2B0.toInt()
        /** Satellite hairline rim (Glass/Orbit UX round 3), a step brighter than the ring track. */
        private const val SAT_RIM = 0xFF465064.toInt()
        /** Indigo sphere of the idle bolus button (the Glass ball gradient). */
        private const val SPHERE_LIGHT = 0xFFA3ACFF.toInt()
        private const val SPHERE_DARK = 0xFF2C3290.toInt()
        private const val VAL_TXT = 0xFFEEF2F7.toInt()
        private const val TRACK = 0xFF2A3446.toInt()
        private const val TIME_C = 0xFFE6ECF4.toInt()
        private const val DATE_C = TXT2
        private const val DELTA_C = 0xFFD5DCE8.toInt() // UX round 2: near txt1, the second reading after the BG
        private const val INDIGO = 0xFF7888FF.toInt()
        private const val BOLUS_TEXT = 0xFFE8EBFF.toInt()
        // Glass/Orbit UX round 1: a deeper indigo (white 7:1), the armed button no longer dominates the face
        // Glass/Orbit UX round 3: the family's armed indigo #5B63F5 (Glass), idle and armed differ by fill AND ring
        private const val ARMED_FILL = 0xFF5B63F5.toInt()
        private const val MOON_C = 0xFFF2F6FC.toInt()
        private const val NOW_HOLLOW = 0xFFC8C8C8.toInt()
        private const val PILL_ALERT = 0xFF3A1018.toInt()
        private const val PILL_WARN = 0xFF33240C.toInt()
        private const val PILL_INFO = 0xFF141A25.toInt()
        private const val BADGE_TXT = 0xFF1A0508.toInt()

        // sys.preview sample (spec §10): "6,4" FLAT "+0,1" 1′, loop 2′, IOB 1,25, COB 0g, basal 0,85, 10:08 "Fr 17. Okt"
        private const val SAMPLE_YEAR = 2025
        private const val SAMPLE_MONTH = 10
        private const val SAMPLE_DAY = 17
        private const val SAMPLE_HOUR = 10
        private const val SAMPLE_MINUTE = 8
        private val SAMPLE_NOW: Long = java.time.LocalDateTime.of(SAMPLE_YEAR, SAMPLE_MONTH, SAMPLE_DAY, SAMPLE_HOUR, SAMPLE_MINUTE)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun sampleBg(t: Long, mgdl: Double, mmol: String) = EventData.SingleBg(
            dataset = 0, timeStamp = t, sgvString = mmol, glucoseUnits = "mmol", slopeArrow = "→", delta = "+0,1",
            deltaDetailed = "+0,08", avgDelta = "+0,1", avgDeltaDetailed = "+0,08", sgvLevel = 0L, sgv = mgdl,
            high = 180.0, low = 70.0, deltaMgdl = 1.0, avgDeltaMgdl = 1.0
        )

        private val sampleData: ComplicationData by lazy {
            val points = ArrayList<EventData.SingleBg>()
            for (k in 36 downTo 1) {
                val mgdl = 115.0 + 18.0 * kotlin.math.sin(k / 6.0) * k / 36.0
                points.add(sampleBg(SAMPLE_NOW - k * 5 * 60_000L - 60_000L, mgdl, ""))
            }
            ComplicationData(
                bgData = sampleBg(SAMPLE_NOW - 60_000L, 115.0, "6,4"),
                statusData = EventData.Status(
                    dataset = 0, externalStatus = "", iobSum = "1,25", iobDetail = "", cob = "0g", currentBasal = "0,85U/h",
                    battery = "80", rigBattery = "", openApsStatus = SAMPLE_NOW - 2 * 60_000L, bgi = "", batteryLevel = 1,
                    tempTarget = "--", tempTargetLevel = 0, reservoirString = "120U", reservoir = 120.0, reservoirLevel = 0
                ),
                graphData = EventData.GraphData(points),
                lastUpdateTimestamp = SAMPLE_NOW - 60_000L
            )
        }
    }
}
