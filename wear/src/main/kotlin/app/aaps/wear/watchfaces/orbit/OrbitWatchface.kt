package app.aaps.wear.watchfaces.orbit

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
import app.aaps.wear.watchfaces.common.AodStyle
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
import kotlin.math.sqrt

/**
 * "AAPS Orbit": BG is the sun in the centre, status values are lit discs threaded on one orbit ring that uses the
 * full dial and breaks only for the system status icon at 12 o'clock (Orbit discs round 4: the minute moon is gone,
 * the time is digital; empty 3 / 9 o'clock stations show a small socket so the ring stays balanced).
 * Colour shows state only. Geometry: designs/orbit_spec.md; states: designsystem/states.md.
 *
 * Drawing: a 450 x 450 design grid scaled to the screen. Everything derived from data (strings, colours, the
 * sparkline path, the status slot) is rebuilt only when the data, the minute, the mode or a preference
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
    private var showAgo = true
    private var showDirection = true
    private var detailedDelta = false
    private var avgDelta = false
    private var showPredictions = true
    private var timeFrameH = OrbitFaceLogic.DEFAULT_TIME_FRAME_H
    /** The shared always-on style (face_aod_style), mapped to Orbit's own look by [OrbitAod.look]. */
    private var aodStyle = AodStyle.DEFAULT
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
    /** Date line parts (Glass/Orbit UX round 1: an active temp target joins the date at 6 o'clock in violet). */
    private var lineDate = ""
    private var lineTt = ""
    private var lineDateW = 0f
    private var lineSepW = 0f
    private var lineW = 0f
    private var dateSize = DATE_SIZE
    /** The ring's one break, for the system status icon at 12 o'clock (half angle, degrees). */
    private var gapT = 10f
    private var bgAge = BgAge.NONE
    private var range = Range.STALE
    private var rangeColour = STALE_C
    private var heroColour = STALE_C
    private var heroText = "---"
    private var heroSize = OrbitFaceLogic.HERO_SIZE
    private var heroX = OrbitFaceLogic.CX
    private var heroW = 0f
    private var trend = Trend.NONE
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
    private val ballLabelSize = FloatArray(5) { OrbitFaceLogic.BALL_LABEL_SIZE }
    private val ballLabelTrack = FloatArray(5) { LABEL_TRACKING }
    /** The label drawn in each disc (the short token when the full word does not fit at the floor). */
    private val ballLabelText = arrayOfNulls<String>(5)

    /** Offline render harness only: the date line text drawn by the last frame ("" when none was drawn). */
    /** Offline render harness only: draws this look in ambient instead of the one the setting picks (AOD exploration). */
    @VisibleForTesting internal var aodLookOverride: OrbitAod.Look? = null
    /** Offline render harness only: the always-on model of the last ambient rebuild. */
    @VisibleForTesting internal var aodModel: OrbitAod.Model? = null
    private val aodPainter = OrbitAod.Painter()
    @VisibleForTesting internal var drawnDateText = ""
    /** Offline render harness only: the glyph box (design px) of the date line drawn by the last frame. */
    @VisibleForTesting internal val drawnDateBox = RectF()
    private val textBounds = android.graphics.Rect()
    private var shiftX = 0f
    private var shiftY = 0f
    private var sparkWindowMs = OrbitFaceLogic.SPARK_HISTORY_MS

    // sparkline
    private val sparkPath = Path()
    /** Orbit discs round 3: no band, trace or dot without a reading in the window (no ghost box under "Warte auf AAPS"). */
    private var hasSpark = false
    /** Orbit discs round 3: the data is old (W2 / W3): the value discs drop to the stale grey, so old numbers never look live. */
    private var discsStale = false
    private var hasNowDot = false
    private var nowX = 0f
    private var nowY = 0f
    private val predX = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private val predY = FloatArray(OrbitFaceLogic.PREDICTION_DOTS)
    private var predCount = 0
    private var lowY = 0f
    // Orbit discs round 4: the target range as two hairlines, faded at both ends (the filled band read as a smudge box)
    private val bandLine = bandFade(withAlpha(Color.WHITE, BAND_LINE_ALPHA))
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
    /**
     * Orbit discs round 3 (review: the 2 px hairline was the last flat element): the orbit is lit like the discs,
     * bright on the upper-left arc and fading toward the lower right.
     */
    private val trackShader: Shader = LinearGradient(
        OrbitFaceLogic.CX - 0.75f * OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY - 0.75f * OrbitFaceLogic.ORBIT_R,
        OrbitFaceLogic.CX + 0.75f * OrbitFaceLogic.ORBIT_R, OrbitFaceLogic.CY + 0.75f * OrbitFaceLogic.ORBIT_R,
        TRACK_LIT, TRACK_DARK, Shader.TileMode.CLAMP
    )

    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)
    private val ballX = FloatArray(5) { OrbitFaceLogic.pointX(OrbitFaceLogic.SAT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    private val ballY = FloatArray(5) { OrbitFaceLogic.pointY(OrbitFaceLogic.SAT_R, OrbitFaceLogic.BALL_DEGS[it]) }
    // one lit-disc shader (and a glow for the hot disc) per satellite, rebuilt only when its look changes
    private val discShader = arrayOfNulls<Shader>(5)
    private val glowShader = arrayOfNulls<Shader>(5)
    private val discKey = IntArray(5)
    private val bolusX = OrbitFaceLogic.BOLUS_X
    private val bolusY = OrbitFaceLogic.BOLUS_Y
    private val outlinePath = Path()
    private val outlineSegment = Path()
    private var outlineMeasure = PathMeasure()
    private var outlineLength = 0f
    private val warnIcon = Path()

    // pre-rendered bitmaps (software canvas), rebuilt when the scale changes
    private var cacheScale = 0f
    private val corona = arrayOfNulls<Bitmap>(3)
    private val coronaDst = RectF()
    private var bolusIdle: Bitmap? = null
    private var bolusArmed: Bitmap? = null
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
        aodPainter.agoFormat = getString(R.string.orbit_aod_age_ago).replace("%1\$s", "%s")
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
        showAgo = sp.getBoolean(R.string.key_show_ago, true)
        showDirection = sp.getBoolean(R.string.key_show_direction, true)
        detailedDelta = sp.getBoolean(R.string.key_show_detailed_delta, false)
        avgDelta = sp.getBoolean(R.string.key_show_avg_delta, true)
        showPredictions = sp.getBoolean(R.string.key_prediction_lines, true)
        aodStyle = AodStyle.read(sp)
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
        // Orbit discs round 2: the time sits inside the orbit; the ring opens only for the system status icon
        gapT = OrbitFaceLogic.TIME_GAP_DEG

        val locale = Locale.getDefault()
        dateText = if (!showDate) "" else try {
            val pattern = if (locale.language == "de") null else DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
            if (isHeadless) OrbitFaceLogic.dateString(SAMPLE_YEAR, SAMPLE_MONTH, SAMPLE_DAY, locale, pattern, if (showWeek) labels.weekFormat else null)
            else OrbitFaceLogic.dateString(t.year, t.month + 1, t.monthDay, locale, pattern, if (showWeek) labels.weekFormat else null)
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
        val delta = OrbitFaceLogic.deltaSource(bg.delta, bg.deltaDetailed, bg.avgDelta, bg.avgDeltaDetailed, detailedDelta, avgDelta)
        deltaRow = OrbitFaceLogic.deltaRow(delta, ageMin, labels, showAgo)

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
        discsStale = slot?.rank == 2 || slot?.rank == 3
        phoneGlyph = slot?.tone == Tone.GLYPH
        if (phoneGlyph) slot = null
        slotInDeltaRow = firstStart || OrbitFaceLogic.slotReplacesDelta(slot) || (deltaRow.isEmpty && !phoneGlyph)
        if (slot == null) slotInDeltaRow = false

        // balls
        val temps = d.treatmentData.temps
        val starts = LongArray(temps.size) { temps[it].startTime }
        val ends = LongArray(temps.size) { temps[it].endTime }
        // Final pass: no profile uses the same dim "--" skeleton as the first start (states.md conn.no_profile:
        // IOB/COB/basal "--", loop indicator grey), as Halo and Glass do; hidden satellites read as a broken layout
        val skeleton = firstStart || (noProfile && !loopOff)
        if (skeleton) {
            balls[OrbitFaceLogic.IDX_LOOP] = Ball("--", "LOOP", Ring.DIM, dashed = true)
            balls[OrbitFaceLogic.IDX_IOB] = OrbitFaceLogic.placeholderBall("IOB")
            balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.placeholderBall(OrbitFaceLogic.BASAL_LABEL)
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
            // Orbit discs round 2: an active TT is its own violet disc at 3 o'clock again (each disc one status)
            balls[OrbitFaceLogic.IDX_TT] = if (showTt) OrbitFaceLogic.ttBall(st.tempTarget, st.tempTargetLevel) else null
        }
        layoutDateLine(null)
        if (skeleton) {
            if (!showLoop) balls[OrbitFaceLogic.IDX_LOOP] = null
            if (!showIob) balls[OrbitFaceLogic.IDX_IOB] = null
            if (!showBasal) balls[OrbitFaceLogic.IDX_BASAL] = null
            if (!showCob) balls[OrbitFaceLogic.IDX_COB] = null
        }
        // Glass/Orbit UX round 5: an unknown satellite ("--", e.g. no profile) is not drawn at all outside the first
        // start (the pill names the cause); the dashed never-looped LOOP stays
        if (!skeleton) for (i in balls.indices) {
            val b = balls[i] ?: continue
            if (b.value == "--" && !b.dashed && !b.pause) balls[i] = null
        }
        for (i in balls.indices) {
            val b = balls[i]
            ballShown[i] = b != null
            if (b == null) continue
            // Orbit discs round 1: one typography in every disc, each text kept DISC_TEXT_PAD inside the disc's chord
            setText(OrbitFaceLogic.BALL_VALUE_SIZE, tf600)
            // a paused loop draws the pause glyph before "AUS" (its width counts at the value size)
            ballSize[i] = OrbitFaceLogic.ballValueSize(text.measureText(b.value) + (if (b.pause) PAUSE_W + PAUSE_GAP else 0f), floorPx(OrbitFaceLogic.MIN_TEXT_PX))
            // the label gives way first (never below the floor): the full word tracked, untracked, then the short token
            // ("BAS"), tracked again when that fits
            val ls = floorPx(OrbitFaceLogic.BALL_LABEL_SIZE)
            val maxW = OrbitFaceLogic.labelMaxWidth(ls)
            var label = b.label
            var track = LABEL_TRACKING
            if (labelWidth(label, ls, track) > maxW) track = 0f
            if (labelWidth(label, ls, track) > maxW) {
                label = OrbitFaceLogic.shortLabel(label)
                track = if (labelWidth(label, ls, LABEL_TRACKING) <= maxW) LABEL_TRACKING else 0f
            }
            ballLabelText[i] = label
            ballLabelSize[i] = ls
            ballLabelTrack[i] = track
        }

        refreshBolusCheck() // the hero keeps clear of a shown bolus button and TT disc
        layoutHero()
        if (bolusCheck.state == State.SENT) {
            handler.removeCallbacks(pendingEnd)
            handler.postDelayed(pendingEnd, guard.pendingLeftMs() + 50L)
        }
        captionShown = caption != FaceState.Caption.NONE && heroText != "---"
        val slotOwnsDeltaRow = slot != null && slotInDeltaRow
        captionInDelta = captionShown && !slotOwnsDeltaRow && !firstStart
        // Glass/Orbit UX round 2: the LOOP satellite owns a loop warning (no second "Loop 23′" pill)
        val loopPillFolded = OrbitFaceLogic.foldLoopPill(slot, ballShown[OrbitFaceLogic.IDX_LOOP], isAmbient)
        loopMuted = OrbitFaceLogic.muteLoopRing(range, slot, loopPillFolded)
        if (loopPillFolded) slot = null
        // ... and a remaining W4-W9 warning takes the chart's slot (Orbit discs round 4: the date never moves)
        pillRowShown = (slot != null && !slotInDeltaRow) || (captionShown && !captionInDelta)
        shiftX = OrbitFaceLogic.burnInShift(minuteKey, 0).toFloat()
        shiftY = OrbitFaceLogic.burnInShift(minuteKey, 1).toFloat()
        buildSparkline(d, now)
        if (isAmbient) buildAod(d, now, delta)
    }

    /** AOD round 2: the always-on model (BG, arrow, time and at most one line of words); see [OrbitAod]. */
    private fun buildAod(d: ComplicationData, now: Long, delta: String) {
        val look = aodLookOverride ?: OrbitAod.look(aodStyle)
        val spark = if (look == OrbitAod.Look.BG_SPARK) {
            val pts = d.graphData.entries.toMutableList()
            if (d.bgData.sgv > 0) pts.add(d.bgData)
            OrbitAod.sparkPoints(LongArray(pts.size) { pts[it].timeStamp }, DoubleArray(pts.size) { pts[it].sgv }, now)
        } else FloatArray(0)
        val ampm = if (amPmText.isEmpty()) "" else " $amPmText"
        aodModel = OrbitAod.model(heroText, range, bgAge, trend, timeText + ampm, caption, slot, deltaRow, delta, spark)
        aodPainter.lowWord = labels.sentinelLow
        aodPainter.highWord = labels.sentinelHigh
    }

    private fun labelWidth(label: String, size: Float, tracking: Float): Float {
        setText(size, tf500)
        text.letterSpacing = tracking
        return text.measureText(label)
    }

    /** "Sa 17. Okt", "Sa 17. Okt · TT 5,5" or "TT 5,0-6,0" (OrbitFaceLogic.dateLine), fitted to the chord at 6 o'clock. */
    private fun layoutDateLine(tt: String?) {
        setText(DATE_SIZE, tf500)
        val (datePart, ttPart) = OrbitFaceLogic.dateLine(dateText, tt, DATE_SIZE, DATE_MIN_SIZE, OrbitFaceLogic.DATE_LINE_MAX_WIDTH) { text.measureText(it) }
        lineDate = datePart
        lineTt = ttPart
        if (datePart.isEmpty() && ttPart.isEmpty()) {
            lineW = 0f
            return
        }
        val dw = text.measureText(datePart)
        setText(DATE_SIZE, tf600)
        val sep = if (datePart.isNotEmpty() && ttPart.isNotEmpty()) OrbitFaceLogic.dateSeparatorWidth(DATE_SIZE) else 0f
        val full = dw + sep + text.measureText(ttPart)
        dateSize = max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(full, DATE_SIZE, OrbitFaceLogic.DATE_LINE_MAX_WIDTH))
        lineDateW = dw * dateSize / DATE_SIZE
        lineSepW = sep * dateSize / DATE_SIZE
        lineW = full * dateSize / DATE_SIZE
    }

    /**
     * Hero group (spec §4.2/§4.3): BG + 14 + a 40 px glyph must fit 252; the size steps down by 4 (minimum 60).
     * The group is centred at x 225; without a glyph the digits re-centre. Builds the vector trend glyph.
     */
    private fun layoutHero() {
        setText(OrbitFaceLogic.HERO_REF, tf600)
        text.letterSpacing = HERO_TRACKING
        val w108 = text.measureText(heroText)
        // Orbit discs round 3: the digits stand alone, centred (the arrow leads the delta row, as on Halo)
        var maxGroup = OrbitFaceLogic.HERO_GROUP_MAX
        if (bolusCheck.state != State.HIDDEN) maxGroup = min(maxGroup, OrbitFaceLogic.HERO_GROUP_MAX_BOLUS)
        if (balls[OrbitFaceLogic.IDX_TT] != null) maxGroup = min(maxGroup, OrbitFaceLogic.HERO_GROUP_MAX_TT)
        heroSize = OrbitFaceLogic.heroSize(w108, false, maxGroup)
        heroW = w108 * heroSize / OrbitFaceLogic.HERO_REF
        heroX = OrbitFaceLogic.CX
        // the trend glyph, centred on (0, 0) in a [DELTA_ARROW_W] box; translated to the delta row when drawn
        trendSize = OrbitFaceLogic.DELTA_ARROW_W
        trendPath.rewind()
        if (trend == Trend.NONE) return
        val rad = Math.toRadians(trend.deg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val shaft = 0.86f * trendSize
        // a double arrow is two arrows side by side (as Halo), not stacked chevrons
        val heads = trend.heads.coerceAtLeast(1)
        val spread = if (heads > 1) 0.2f * trendSize else 0f
        val headLen = if (heads > 1) 0.26f * trendSize else 0.34f * trendSize
        for (k in 0 until heads) {
            val off = if (heads > 1) (k - (heads - 1) / 2f) * 2f * spread else 0f
            val ox = -dy * off
            val oy = dx * off
            val tipX = ox + dx * shaft / 2f
            val tipY = oy + dy * shaft / 2f
            trendPath.moveTo(ox - dx * shaft / 2f, oy - dy * shaft / 2f)
            trendPath.lineTo(tipX, tipY)
            for (sign in HEAD_SIGNS) {
                val a = rad + Math.PI + sign * Math.toRadians(40.0)
                trendPath.moveTo(tipX, tipY)
                trendPath.lineTo(tipX + sin(a).toFloat() * headLen, tipY - cos(a).toFloat() * headLen)
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
        hasSpark = points.isNotEmpty()
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
        // Glass/Orbit UX round 5: a disc (the family's sphere), the outline a circle from 12 o'clock clockwise
        val bh = OrbitFaceLogic.BOLUS_HALF - 1f
        outlinePath.rewind()
        rect.set(bolusX - bh, bolusY - bh, bolusX + bh, bolusY + bh)
        outlinePath.arcTo(rect, -90f, 359.9f, true)
        outlineMeasure = PathMeasure(outlinePath, false)
        outlineLength = outlineMeasure.length

        // warning triangle of the alert pill, 14 x 12, centred on (0, 0); translated when drawn
        warnIcon.rewind()
        warnIcon.moveTo(0f, -6f)
        warnIcon.lineTo(7f, 6f)
        warnIcon.lineTo(-7f, 6f)
        warnIcon.close()

    }

    private fun ensureCaches(s: Float) {
        if (s == cacheScale && corona[0] != null) return
        cacheScale = s
        // corona: ellipse 240 x 200 at (225, 215), 60 px blur. Baked at the emphasis alpha (34 + 20 %),
        // drawn at CORONA_NORMAL_ALPHA for the normal step.
        val cw = 240f + 2 * CORONA_BLUR
        val ch = 200f + 2 * CORONA_BLUR
        coronaDst.set(225f - cw / 2f, 208f - ch / 2f, 225f + cw / 2f, 208f + ch / 2f)
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
        // Glass/Orbit UX round 6: no baked alarm shells any more (a status dot and the value's hue carry the alarm)
        // bolus button shells
        val bs = OrbitFaceLogic.BOLUS_HALF * 2f + 2 * BOLUS_PAD
        bolusIdle?.recycle()
        // Glass/Orbit UX round 3: the idle button is the family's filled indigo sphere (Glass, Halo) with white text,
        // so it never reads as a fifth outlined data satellite
        // Orbit discs round 3 (review: the glass ball read as a fifth status disc): a solid, unlit indigo button with a
        // soft indigo glow; the white ring is drawn on top (drawBolus). Only the pressable object is filled flat.
        bolusIdle = softBitmap(bs, bs, s) { c ->
            val m = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF
            val r = OrbitFaceLogic.BOLUS_HALF
            fill.reset(); fill.isAntiAlias = true
            fill.color = withAlpha(BOLUS_FILL, 80)
            fill.maskFilter = BlurMaskFilter(7f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(m, m, r + 1f, fill)
            fill.maskFilter = null
            fill.color = BOLUS_FILL
            c.drawCircle(m, m, r, fill)
        }
        bolusArmed?.recycle()
        bolusArmed = softBitmap(bs, bs, s) { c ->
            val a = BOLUS_PAD
            val b = BOLUS_PAD + OrbitFaceLogic.BOLUS_HALF * 2f
            fill.reset(); fill.isAntiAlias = true
            val m = (a + b) / 2f
            fill.color = withAlpha(ARMED_FILL, 90)
            fill.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(m, m, m - a, fill)
            fill.maskFilter = null
            fill.color = ARMED_FILL
            c.drawCircle(m, m, m - a, fill)
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
            drawnDateText = ""
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

        // 2. orbit ring (Orbit discs round 4: unbroken behind the discs and the button, which sit on it; 3.5 px, lit
        // from the upper left), and a socket at an empty 3 / 9 o'clock station
        drawTrack(canvas, OrbitFaceLogic.TRACK_W, Color.WHITE, true, shader = trackShader)
        val ballOn = bolusCheck.state != State.HIDDEN
        val ttOn = balls[OrbitFaceLogic.IDX_TT] != null
        if (ballOn != ttOn) drawSocket(canvas, if (ballOn) OrbitFaceLogic.DEG_TT else OrbitFaceLogic.DEG_BOLUS)

        // 3. sparkline. Orbit discs round 4 (review: the filled band read as a smudge box in the critical states): the
        // target range as two faded hairlines, a soft fill that clings to the trace (gone 20 px under it), the now dot in
        // the range colour; no target lines and no fill for old data; nothing without data, and hidden while a W4-W9
        // warning holds the chart's slot
        if (hasSpark && !pillRowShown) {
            if (!old) {
                setStroke(1f, Color.WHITE, true)
                stroke.shader = bandLine
                canvas.drawLine(OrbitFaceLogic.SPARK_X0, highY, OrbitFaceLogic.SPARK_X1, highY, stroke)
                canvas.drawLine(OrbitFaceLogic.SPARK_X0, lowY, OrbitFaceLogic.SPARK_X1, lowY, stroke)
                stroke.shader = null
                // the soft fill clings to the trace: AREA_STEPS nested bands hanging from the line (each d px deep, the
                // trace stroked d px wide d/2 px lower), so the fill fades from about AREA_ALPHA to 0 within AREA_FADE px
                // of the line in every column (no box, no edge at the plot floor, no seams between the bands)
                for (k in 1..AREA_STEPS) {
                    val d = k * AREA_FADE / AREA_STEPS
                    setStroke(d, withAlpha(rangeColour, AREA_ALPHA / AREA_STEPS + 1), true)
                    stroke.strokeJoin = Paint.Join.ROUND
                    canvas.save()
                    canvas.translate(0f, d / 2f)
                    canvas.drawPath(sparkPath, stroke)
                    canvas.restore()
                }
            }
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
                    setFill(Color.BLACK, true)
                    canvas.drawCircle(nowX, nowY, 6f, fill)
                    setFill(rangeColour, true)
                    canvas.drawCircle(nowX, nowY, 4f, fill)
                }
            }
        }

        // 4. hero BG + arrow
        drawHero(canvas, heroColour, glow = !old, ambient = false)
        if (captionUntil != 0L && System.currentTimeMillis() < captionUntil) {
            // graph.timeframe_changed: "5 Std" top-left of the sparkline box
            setText(floorPx(OrbitFaceLogic.MIN_TEXT_PX), tf600)
            text.textAlign = Paint.Align.LEFT
            text.color = TXT2
            canvas.drawText(captionText, OrbitFaceLogic.SPARK_X0, sparkTop - 4f, text)
        }

        // 5. delta row; the pill row under it (TIEF/HOCH, status slot) has its own line, never over the delta
        val s = slot
        if (slotInDeltaRow && s != null) drawWarning(canvas, s, OrbitFaceLogic.CX, OrbitFaceLogic.SLOT_IN_DELTA_Y, ambient = false, deltaWarnMax())
        else if (!firstStart) drawDeltaRow(canvas, DELTA_C, if (deltaRow.ageAmber) AMBER else DELTA_C, true, ambient = false, arrowColour = rangeColour)
        drawPillRow(canvas, if (s != null && !slotInDeltaRow) s else null, ambient = false)

        // 6. discs
        for (i in balls.indices) drawBall(canvas, i)

        // 7. bolus button
        if (bolusCheck.state != State.HIDDEN) drawBolus(canvas)

        // 8. time, date (+ TT)
        drawTime(canvas, TIME_C, tf600, true)
        drawDateLine(canvas, DATE_C, VIOLET, true)
    }

    /**
     * Orbit discs round 4 (review: with only the TT disc or only the bolus button, the 3 / 9 o'clock pair was lopsided):
     * the empty station is a small socket on the ring (a dark dot with a ring-grey rim), so the orbit keeps its six
     * stations and its balance. Never a value, never tappable.
     */
    private fun drawSocket(canvas: Canvas, deg: Float) {
        val x = OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, deg)
        val y = OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, deg)
        setFill(Color.BLACK, true)
        canvas.drawCircle(x, y, SOCKET_R + 2f, fill)
        setFill(SOCKET_FILL, true)
        canvas.drawCircle(x, y, SOCKET_R, fill)
        setStroke(1.5f, SOCKET_RIM, true)
        canvas.drawCircle(x, y, SOCKET_R, stroke)
    }

    /**
     * Always-on (AOD round 2, owner feedback on the outline AOD: "too hard to read with the fine, thin lines, and it's
     * too much information"): bold, dimmed digits with the trend arrow, the time in most looks, and one line of words
     * only when something needs attention. No ring, discs, chart, date or bolus button. Drawn by [OrbitAod.Painter].
     */
    private fun drawAmbient(canvas: Canvas) {
        val m = aodModel ?: return
        aodPainter.draw(canvas, m, aodLookOverride ?: OrbitAod.look(aodStyle), lowBitAmbient, burnInProtection)
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

    private fun drawTrack(canvas: Canvas, width: Float, colour: Int, aa: Boolean, effect: DashPathEffect? = null, shader: Shader? = null) {
        setStroke(width, colour, aa)
        stroke.pathEffect = effect
        stroke.shader = shader
        // canvas angles start at 3 o'clock: clockwise-from-12 minus 90; one arc from the end of the 12 o'clock break
        canvas.drawArc(trackRect, gapT - 90f, 360f - 2f * gapT, false, stroke)
        stroke.pathEffect = null
        stroke.shader = null
    }

    /** The date (and an active TT in [ttColour]) centred on the ring at 6 o'clock. */
    private fun drawDateLine(canvas: Canvas, colour: Int, ttColour: Int, aa: Boolean) {
        if (lineW <= 0f) return
        // Orbit discs round 4: always at 6 o'clock inside the unbroken ring (a W4-W9 warning takes the chart's slot)
        val y = OrbitFaceLogic.DATE_Y
        val x0 = OrbitFaceLogic.CX - lineW / 2f
        setText(dateSize, tf500, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        val by = baseline(y)
        drawnDateText = lineDate
        // the glyph box of the whole line (the harness checks its clearance to the ring)
        val all = if (lineTt.isEmpty()) lineDate else "$lineDate · $lineTt"
        text.getTextBounds(all, 0, all.length, textBounds)
        drawnDateBox.set(x0, by + textBounds.top, x0 + lineW, by + textBounds.bottom)
        if (lineTt.isEmpty()) {
            if (lineDate.isNotEmpty()) canvas.drawText(lineDate, x0, by, text)
            return
        }
        // with a TT the date ends exactly at its measured end (right-aligned), so the separator gap never closes
        text.textAlign = Paint.Align.RIGHT
        if (lineDate.isNotEmpty()) canvas.drawText(lineDate, x0 + lineDateW, by, text)
        if (lineSepW > 0f) {
            // the separator is its own run, centred in its gap (never glued to the TT)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("·", x0 + lineDateW + lineSepW / 2f, by, text)
        }
        setText(dateSize, tf600, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = ttColour
        canvas.drawText(lineTt, x0 + lineDateW + lineSepW, by, text)
        drawnDateText = if (lineDate.isEmpty()) lineTt else "$lineDate · $lineTt"
    }

    private fun drawHero(canvas: Canvas, colour: Int, glow: Boolean, ambient: Boolean) {
        val aa = !(ambient && lowBitAmbient)
        val dashes = heroText == "---"
        val urgent = range == Range.URGENT_LOW
        // ambient: Light, urgent low one weight step up (Regular)
        // Glass/Orbit UX round 6: weight 600 (as Glass; 700 read black and sporty) with -0.02 em tracking
        setText(heroSize, if (ambient) (if (urgent) tf400 else tf300) else tf600, aa)
        text.letterSpacing = HERO_TRACKING
        text.color = if (dashes && !ambient) TXT2 else colour
        // Glass/Orbit UX round 5: an old reading in AOD is a thin AMB2 outline, never brighter than a live one
        val staleAmbient = ambient && OrbitFaceLogic.isOld(bgAge) && !dashes
        if (staleAmbient) {
            text.color = amb(AMB2)
            text.style = Paint.Style.STROKE
            text.strokeWidth = 1.5f
        } else if (ambient) {
            // Orbit discs round 4 (design system: ambient = outlines only, as Halo): outlined digits (black inside) in every
            // ambient mode, 2 px, 3 px for urgent low
            text.style = Paint.Style.STROKE
            text.strokeWidth = if (urgent) 3f else 2f
        }
        // UX round 3: a tighter, fainter glow (12 px at 110/150 made the digits look blurry and neon)
        if (glow) text.setShadowLayer(7f, 0f, 0f, withAlpha(rangeColour, if (OrbitFaceLogic.emphasis(range)) 110 else 50))
        canvas.drawText(heroText, heroX, baseline(HERO_Y), text)
        text.clearShadowLayer()
        if (OrbitFaceLogic.isStruck(bgAge) && !dashes) {
            setStroke(if (!ambient) 3f else if (lowBitAmbient) 2f else 1.5f, if (staleAmbient) amb(AMB2) else colour, aa)
            canvas.drawLine(heroX - heroW / 2f - 6f, HERO_Y + 4f, heroX + heroW / 2f + 6f, HERO_Y + 4f, stroke)
        }
    }

    /**
     * Trend arrow, delta · age, the phone-off glyph and (UX round 3) the TIEF/HOCH caption after them, centred as one
     * group. Orbit discs round 3: the arrow leads the row (as Halo), in the range colour.
     */
    private fun drawDeltaRow(canvas: Canvas, colour: Int, ageColour: Int, aa: Boolean, ambient: Boolean, arrowColour: Int) {
        val arrow = trend != Trend.NONE
        val textShown = !deltaRow.isEmpty || phoneGlyph
        if (!textShown && !captionInDelta && !arrow) return
        val word = if (caption == FaceState.Caption.HIGH_WORD) labels.sentinelHigh else labels.sentinelLow
        val capW = if (captionInDelta) captionWidth(word, ambient) else 0f
        val capGap = if (captionInDelta && (textShown || arrow)) OrbitFaceLogic.CAPTION_GAP else 0f
        val ds = OrbitFaceLogic.DELTA_SIZE
        setText(ds, if (ambient) tf400 else tf500, aa)
        val wl = if (deltaRow.isEmpty) 0f else text.measureText(deltaRow.deltaPart)
        val wa = if (deltaRow.isEmpty) 0f else text.measureText(deltaRow.agePart)
        val glyphW = if (phoneGlyph) PHONE_GLYPH_W else 0f
        val arrowW = if (arrow) OrbitFaceLogic.DELTA_ARROW_W + (if (textShown) OrbitFaceLogic.DELTA_ARROW_GAP else 0f) else 0f
        val size = max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(wl + wa + glyphW, ds, OrbitFaceLogic.DELTA_ROW_MAX_WIDTH - capW - capGap - arrowW))
        text.textSize = size
        val k = size / ds
        text.textAlign = Paint.Align.LEFT
        val textW = arrowW + (wl + wa) * k + glyphW
        val x0 = OrbitFaceLogic.CX - (textW + capGap + capW) / 2f
        val y = baseline(DELTA_ROW_Y)
        if (arrow) {
            setStroke(if (!ambient) DELTA_ARROW_STROKE else if (burnInProtection) 2f else 2.5f, arrowColour, aa)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.save()
            canvas.translate(x0 + OrbitFaceLogic.DELTA_ARROW_W / 2f, DELTA_ROW_Y - 1f)
            canvas.drawPath(trendPath, stroke)
            canvas.restore()
            setText(size, if (ambient) tf400 else tf500, aa)
            text.textAlign = Paint.Align.LEFT
        }
        val tx = x0 + arrowW
        if (!deltaRow.isEmpty) {
            text.color = colour
            canvas.drawText(deltaRow.deltaPart, tx, y, text)
            text.color = ageColour
            canvas.drawText(deltaRow.agePart, tx + wl * k, y, text)
        }
        if (phoneGlyph) drawPhoneOff(canvas, tx + (wl + wa) * k + PHONE_GLYPH_W / 2f + 2f, DELTA_ROW_Y, if (ambient) colour else AMBER, aa)
        if (captionInDelta) drawCaptionPill(canvas, word, x0 + textW + capGap + capW / 2f, DELTA_ROW_Y, OrbitFaceLogic.CAPTION_H, capW, ambient)
    }

    /**
     * Phone-off glyph (conn.phone_lost_fresh): a phone outline crossed by a slash. Orbit discs round 3: 24 px tall and
     * amber (16 px grey did not read as "no phone"). Orbit discs round 4: 28 px tall with a speaker slot, so it reads as
     * a phone, not a crossed-out box.
     */
    private fun drawPhoneOff(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean) {
        setStroke(2.4f, colour, aa)
        rect.set(x - 8.5f, y - 14f, x + 8.5f, y + 14f)
        canvas.drawRoundRect(rect, 3f, 3f, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x - 3f, y - 10f, x + 3f, y - 10f, stroke)
        stroke.strokeWidth = 2.6f
        canvas.drawLine(x - 13f, y + 13f, x + 13f, y - 13f, stroke)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, colour: Int, aa: Boolean, hollow: Boolean, k: Float = 1f) {
        // Glass/Orbit UX round 5: the bars at the value's cap height (19 px at 28 px), readable in AOD
        if (hollow) setStroke(1.5f, colour, aa) else setFill(colour, aa)
        for (ox in PAUSE_OFFSETS) {
            rect.set(x + (ox - 2.5f) * k, y - 9.5f * k, x + (ox + 2.5f) * k, y + 9.5f * k)
            if (hollow) canvas.drawRect(rect, stroke) else canvas.drawRoundRect(rect, 1f, 1f, fill)
        }
    }

    /**
     * A disc value centred on ([x], [cy]). Orbit discs round 3: a paused loop draws the pause glyph in [glyphColour]
     * before its value ("|| AUS"), the pair centred as one.
     */
    private fun drawDiscValue(canvas: Canvas, b: Ball, x: Float, cy: Float, size: Float, tf: Typeface, colour: Int, glyphColour: Int, aa: Boolean, hollowPause: Boolean) {
        if (!b.pause) {
            drawText(canvas, b.value, x, cy, size, tf, colour, aa)
            return
        }
        val k = size / OrbitFaceLogic.BALL_VALUE_SIZE
        setText(size, tf, aa)
        val tw = text.measureText(b.value)
        val gw = (PAUSE_W + PAUSE_GAP) * k
        val x0 = x - (gw + tw) / 2f
        drawPause(canvas, x0 + PAUSE_W * k / 2f, cy, glyphColour, aa, hollowPause, k)
        setText(size, tf, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        canvas.drawText(b.value, x0 + gw, baseline(cy), text)
    }

    /**
     * Orbit discs round 4: the W4-W9 warning (and a TIEF / HOCH word that cannot sit on the delta row) in the chart's
     * slot ([OrbitFaceLogic.ROW_Y]; the chart is hidden then), side by side and centred as a group, clear of the KH /
     * basal discs. The date stays at 6 o'clock.
     */
    private fun drawPillRow(canvas: Canvas, s: Slot?, ambient: Boolean) {
        val cap = captionShown && !captionInDelta
        if (!cap && s == null) return
        val word = if (caption == FaceState.Caption.HIGH_WORD) labels.sentinelHigh else labels.sentinelLow
        val capW = if (cap) captionWidth(word, ambient) else 0f
        val gap = if (cap && s != null) PILL_GAP else 0f
        val y = OrbitFaceLogic.ROW_Y
        // the budget from the words' real glyph box ("Reservoir 18 U" has no descender: it may run wider than "Tel 8 %"
        // with a "g" would), the caption's 32 px pill box when only a caption is shown
        var above = OrbitFaceLogic.CAPTION_H / 2f
        var below = OrbitFaceLogic.CAPTION_H / 2f
        if (s != null) {
            setText(floorPx(OrbitFaceLogic.WARN_TEXT), if (ambient) tf500 else tf600)
            text.getTextBounds(s.text, 0, s.text.length, textBounds)
            val base = baseline(y) - y
            above = max(if (cap) above else 0f, -(base + textBounds.top) + 1f)
            below = max(if (cap) below else 0f, base + textBounds.bottom + 1f)
        }
        val maxW = OrbitFaceLogic.textLineMaxWidth(y, above, below) - capW - gap
        val slotW = if (s != null) layoutWarning(s, ambient, maxW) else 0f
        val x1 = OrbitFaceLogic.CX - (capW + gap + slotW) / 2f
        if (cap) drawCaptionPill(canvas, word, x1 + capW / 2f, y, OrbitFaceLogic.CAPTION_H, capW, ambient)
        if (s != null) drawWarning(canvas, s, x1 + capW + gap + slotW / 2f, y, ambient, maxW)
    }

    /** Width budget of a W1-W3 warning on the delta row (it passes above the lower discs, between the 3 / 9 o'clock stations). */
    private fun deltaWarnMax(): Float =
        OrbitFaceLogic.textLineMaxWidth(OrbitFaceLogic.SLOT_IN_DELTA_Y, OrbitFaceLogic.WARN_ABOVE, OrbitFaceLogic.WARN_BELOW, cap = DELTA_WARN_MAX_WIDTH)

    // layout of the last measured warning (size and whether the triangle fits), no allocation
    private var warnSize = OrbitFaceLogic.WARN_TEXT
    private var warnIconShown = false

    /**
     * Fits [s] into [maxW]: the words at [OrbitFaceLogic.WARN_TEXT] with the alert triangle in front when both fit; the
     * triangle gives way first (the words name the problem; colour is never the only cue), the size only when even the
     * words alone cannot fit (never below the 18 px floor). Returns the drawn width.
     */
    private fun layoutWarning(s: Slot, ambient: Boolean, maxW: Float): Float {
        val size = floorPx(OrbitFaceLogic.WARN_TEXT)
        setText(size, if (ambient) tf500 else tf600, !(ambient && lowBitAmbient))
        val tw = text.measureText(s.text)
        val wantIcon = s.tone == Tone.ALERT && !ambient
        warnIconShown = wantIcon && tw + OrbitFaceLogic.WARN_ICON_W <= maxW
        val iconW = if (warnIconShown) OrbitFaceLogic.WARN_ICON_W else 0f
        warnSize = max(floorPx(OrbitFaceLogic.MIN_TEXT_PX), OrbitFaceLogic.fitSize(tw, size, maxW - iconW))
        return iconW + tw * warnSize / size
    }

    /**
     * Status warning (states.md §4.2). Orbit discs round 4 (review: the pills' 13-14 px caps were too small for a safety
     * message): coloured words at 24 px semibold, frameless as on Halo: red for an alert (with the triangle), amber for
     * a warning, a light grey for information. Ambient: the words in the ambient grey.
     */
    private fun drawWarning(canvas: Canvas, s: Slot, cx: Float, cy: Float, ambient: Boolean, maxW: Float) {
        val aa = !(ambient && lowBitAmbient)
        val w = layoutWarning(s, ambient, maxW)
        val fg = when {
            ambient              -> amb(AMB1)
            s.tone == Tone.ALERT -> RED
            s.tone == Tone.WARN  -> AMBER
            else                 -> INFO_TXT
        }
        val x0 = cx - w / 2f
        if (warnIconShown) {
            canvas.save()
            canvas.translate(x0 + 9f, cy - 1f)
            canvas.scale(WARN_ICON_SCALE, WARN_ICON_SCALE)
            setStroke(1.8f, fg, true)
            stroke.strokeJoin = Paint.Join.ROUND
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(warnIcon, stroke)
            canvas.drawLine(0f, -1.5f, 0f, 2f, stroke)
            canvas.drawPoint(0f, 4.2f, stroke)
            canvas.restore()
        }
        setText(warnSize, if (ambient) tf500 else tf600, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = fg
        canvas.drawText(s.text, x0 + (if (warnIconShown) OrbitFaceLogic.WARN_ICON_W else 0f), baseline(cy), text)
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

    /**
     * The disc's rim colour (null = no family, a dim placeholder). Orbit discs round 3 (review: two rims popped, two
     * sank): every family rim at a similar lightness (L* about 70-80): IOB a lighter blue, KH a warm peach, basal (and a
     * TBR, whose "TBR" / "%" says it) a cool white that is clearly live (never the stale grey), TT an orchid violet
     * apart from the indigo bolus button. Old data (W2 / W3) turns the value discs stale grey.
     */
    private fun discFamily(i: Int, b: Ball, muted: Boolean): Int? = when {
        muted                       -> RED
        b.ring == Ring.DIM          -> null
        discsStale                  -> STALE_C
        b.ring == Ring.GREEN        -> GREEN
        b.ring == Ring.CYAN         -> IOB_C
        b.ring == Ring.TEAL         -> BASAL_C
        b.ring == Ring.VIOLET       -> TT_C
        b.ring == Ring.RED          -> RED
        b.ring == Ring.AMBER        -> AMBER
        i == OrbitFaceLogic.IDX_COB -> KH_C
        else                        -> BASAL_C
    }

    /** The saturated hue the body is tinted with (the rim may be lighter): one body recipe for every family. */
    private fun bodyHue(rim: Int): Int = when (rim) {
        IOB_C   -> IOB_BODY
        KH_C    -> KH_BODY
        BASAL_C -> BASAL_BODY
        TT_C    -> TT_BODY
        STALE_C -> DISC_SLATE
        else    -> rim
    }

    /** [c] mixed toward the dark disc base by [t] (0 = base, 1 = c). */
    private fun discMix(c: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (DISC_BASE shr shift) and 0xFF
            val b = (c shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * The LOOP disc is "hot" when the loop is late (amber 6-14 min, red from 15 min) and not muted by the red budget:
     * a stronger glow, a 3 px rim and the age in the warning colour (word and colour agree, as Halo's red "Loop 23′").
     */
    private fun discHot(i: Int, b: Ball, muted: Boolean): Boolean =
        i == OrbitFaceLogic.IDX_LOOP && !muted && !b.dashed && !b.pause && (b.ring == Ring.RED || b.ring == Ring.AMBER)

    /**
     * Orbit discs round 2/3 (owner: "too flat ... the discs are not actually visible"): each satellite is a lit sphere
     * riding on the orbit, readable at a glance:
     * - a faint glow in its family colour (stronger on the hot disc) and a soft drop shadow;
     * - one body recipe for every family: the saturated family hue at the same tint, lit from the upper left, with a
     *   soft specular highlight, a lower-right inner shade and an upper-left rim light;
     * - a dark contact ring just outside the rim (separates the disc from the orbit line and the glow);
     * - a 2 px rim in the family colour (3 px on the hot disc: a shape cue, not colour alone);
     * - the value 28 px semibold near-white, the label 18 px in a light neutral.
     * A muted late loop keeps a dashed red rim, loop open / never a dashed neutral rim, a placeholder a dim slate body.
     * Every shader is per disc position and rebuilt only when that disc's look changes (no per-frame allocation).
     */
    private fun drawBall(canvas: Canvas, i: Int) {
        val b = balls[i] ?: return
        val x = ballX[i]
        val y = ballY[i]
        val r = OrbitFaceLogic.BALL_R
        val muted = i == OrbitFaceLogic.IDX_LOOP && loopMuted && b.ring == Ring.RED
        val family = discFamily(i, b, muted)
        val dim = family == null || b.dimValue
        val stale = family == STALE_C
        val hot = discHot(i, b, muted) && !stale
        val hue = family ?: DISC_SLATE
        // Orbit discs round 4 (review: the amber-on-brown loop-off disc looked like the KH disc): a paused loop is the one
        // SOLID amber disc on the face, with "|| AUS" over "LOOP" in near-black and the strongest glow
        val pausedAmber = b.pause && !muted && family == AMBER
        // Orbit polish round 4 (safety): a stopped loop (>= 15 min) is the loudest disc on the face, never quieter than a
        // paused one: a SOLID lit red sphere with near-black "⚠ 23′" / "LOOP" and the strongest glow. WARN (6-14 min)
        // stays an amber rim: green rim < amber rim < solid amber (off) < solid red (stopped).
        val badRed = hot && hue == RED
        val key = hue xor (if (dim) 0x55000000 else 0) xor (if (hot) 0x2A000000 else 0) xor (if (pausedAmber) 0x3C000000 else 0) xor
            (if (muted) 0x11000000 else 0) xor (if (badRed) 0x66000000 else 0)
        if (discShader[i] == null || discKey[i] != key) {
            discKey[i] = key
            // a muted late loop (the red budget is spent on the pill) keeps only its dashed red rim: a slate body, no glow
            discShader[i] = if (pausedAmber) solidAmberBody(x, y, r)
            else if (badRed) solidRedBody(x, y, r)
            else discBody(x, y, r, if (muted) DISC_SLATE else bodyHue(hue), discStrength(dim, hot, muted, stale))
            val glowA = when {
                badRed                -> DISC_BAD_GLOW_ALPHA
                hot || pausedAmber    -> DISC_HOT_GLOW_ALPHA
                dim || muted || stale -> 0
                else                  -> DISC_GLOW_ALPHA
            }
            glowShader[i] = if (glowA == 0) null else discGlow(x, y, r, hue, glowA)
        }
        drawDiscBase(canvas, x, y, r, glowShader[i], discShader[i])
        // rim
        when {
            muted          -> {
                setStroke(2f, RED, true); stroke.pathEffect = ringDash
            }

            b.dashed       -> {
                setStroke(1.8f, if (b.ring == Ring.DIM) DIM_RING else NEUTRAL_RING, true); stroke.pathEffect = ringDash
            }

            badRed         -> setStroke(DISC_RIM, BAD_RIM, true)
            hot            -> setStroke(DISC_HOT_RIM, hue, true)
            pausedAmber    -> setStroke(DISC_RIM, PAUSED_RIM, true)
            family == null -> setStroke(1.5f, DIM_RING, true)
            else           -> setStroke(DISC_RIM, withAlpha(hue, if (dim || stale) 150 else 255), true)
        }
        val rimR = r - stroke.strokeWidth / 2f
        canvas.drawCircle(x, y, rimR, stroke)
        stroke.pathEffect = null
        val valueColour = when {
            badRed      -> BADGE_TXT
            pausedAmber -> PAUSED_TXT
            hot   -> if (hue == RED) HOT_RED_TXT else HOT_AMBER_TXT
            stale -> TXT2
            dim   -> DIM_TXT
            else  -> VAL_TXT
        }
        val labelColour = if (badRed) BADGE_TXT else if (pausedAmber) PAUSED_TXT else if (family == null || b.ring == Ring.DIM || stale) DIM_TXT else DISC_LABEL
        val vy = if (b.label.isEmpty()) y else y - BALL_VALUE_DY
        if (!(badRed && drawBadValue(canvas, b.value, x, vy, ballSize[i])))
            drawDiscValue(canvas, b, x, vy, ballSize[i], tf600, valueColour, if (pausedAmber) PAUSED_TXT else VAL_TXT, true, hollowPause = false)
        val label = ballLabelText[i] ?: b.label
        if (label.isNotEmpty()) drawText(canvas, label, x, y + BALL_LABEL_DY, ballLabelSize[i], tf500, labelColour, true, ballLabelTrack[i])
    }

    /** Body strength (1 = the standard lit sphere, the same for every family). */
    private fun discStrength(dim: Boolean, hot: Boolean, muted: Boolean, stale: Boolean): Float = when {
        hot              -> DISC_HOT_K
        muted || stale   -> 0.7f
        dim              -> 0.55f
        else             -> 1f
    }

    /** The lit body: light from the upper left, the hue at [DISC_LIT] x [k] there fading to [DISC_DARK] at the rim. */
    private fun discBody(x: Float, y: Float, r: Float, hue: Int, k: Float): Shader = RadialGradient(
        x - 0.34f * r, y - 0.42f * r, 1.42f * r,
        intArrayOf(discMix(hue, DISC_LIT * k), discMix(hue, DISC_MID * k), discMix(hue, DISC_DARK * k), discMix(hue, DISC_EDGE * k)),
        floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP
    )

    /** The paused loop's solid amber body, lit from the upper left (a sphere, not a flat sticker). */
    private fun solidAmberBody(x: Float, y: Float, r: Float): Shader = RadialGradient(
        x - 0.34f * r, y - 0.42f * r, 1.42f * r,
        intArrayOf(AMBER_LIT, AMBER, AMBER_DEEP), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )

    /** The stopped loop's solid red body, lit from the upper left (as [solidAmberBody]). */
    private fun solidRedBody(x: Float, y: Float, r: Float): Shader = RadialGradient(
        x - 0.34f * r, y - 0.42f * r, 1.42f * r,
        intArrayOf(RED_LIT, RED, RED_DEEP), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
    )

    /**
     * The stopped loop's value with a small alert triangle in front ("⚠ 23′"), near-black on the solid red disc, when
     * both fit the value's chord at its size; returns false (the plain value is drawn) when they do not.
     */
    private fun drawBadValue(canvas: Canvas, value: String, x: Float, cy: Float, fullSize: Float): Boolean {
        setText(fullSize, tf600)
        val tw0 = text.measureText(value)
        val iw = BAD_ICON_W
        // the value may give up a few px for the triangle, never below BAD_VALUE_MIN (then the plain value)
        var size = fullSize
        repeat(3) { size = OrbitFaceLogic.fitSize(tw0, fullSize, OrbitFaceLogic.valueMaxWidth(size) - iw).coerceAtMost(fullSize) }
        if (size < BAD_VALUE_MIN) return false
        val tw = tw0 * size / fullSize
        val x0 = x - (tw + iw) / 2f
        canvas.save()
        canvas.translate(x0 + BAD_ICON_SCALE * 7f, cy - 0.5f)
        canvas.scale(BAD_ICON_SCALE, BAD_ICON_SCALE)
        setStroke(1.9f, BADGE_TXT, true)
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(warnIcon, stroke)
        canvas.drawLine(0f, -1.5f, 0f, 2f, stroke)
        canvas.drawPoint(0f, 4.2f, stroke)
        canvas.restore()
        setText(size, tf600)
        text.textAlign = Paint.Align.LEFT
        text.color = BADGE_TXT
        canvas.drawText(value, x0 + iw, baseline(cy), text)
        return true
    }

    /** Glow in the state colour from the rim outwards ([DISC_GLOW] px). */
    private fun discGlow(x: Float, y: Float, r: Float, hue: Int, alpha: Int): Shader = RadialGradient(
        x, y, r + DISC_GLOW,
        intArrayOf(withAlpha(hue, alpha), withAlpha(hue, alpha), withAlpha(hue, alpha / 4), withAlpha(hue, 0)),
        floatArrayOf(0f, (r - 1f) / (r + DISC_GLOW), (r + DISC_GLOW * 0.4f) / (r + DISC_GLOW), 1f), Shader.TileMode.CLAMP
    )

    /**
     * Glow, drop shadow, body, specular highlight, lower inner shade and the upper-left rim light of a sphere of radius
     * [r] at ([x], [y]) (shared by the status discs and the bolus button).
     */
    private fun drawDiscBase(canvas: Canvas, x: Float, y: Float, r: Float, glow: Shader?, body: Shader?) {
        glow?.let {
            setFill(Color.WHITE, true)
            fill.shader = it
            canvas.drawCircle(x, y, r + DISC_GLOW, fill)
        }
        // drop shadow: a soft dark disc 5 px below (gradient, no blur filter: works on the hardware canvas)
        setFill(Color.WHITE, true)
        fill.shader = shadowShader(r)
        canvas.save()
        canvas.translate(x, y + DISC_SHADOW_DY)
        canvas.drawCircle(0f, 0f, r + DISC_SHADOW_SOFT, fill)
        canvas.restore()
        if (body != null) {
            fill.shader = body
            canvas.drawCircle(x, y, r, fill)
        }
        // specular highlight (upper left, inside the disc)
        fill.shader = specShader(r)
        canvas.save()
        canvas.translate(x - 0.36f * r, y - 0.5f * r)
        canvas.drawCircle(0f, 0f, 0.42f * r, fill)
        canvas.restore()
        fill.shader = null
        // lower-right inner shade and the upper-left rim light
        rect.set(x - r + 2.5f, y - r + 2.5f, x + r - 2.5f, y + r - 2.5f)
        setStroke(3f, withAlpha(Color.BLACK, 100), true)
        canvas.drawArc(rect, 0f, 150f, false, stroke)
        rect.set(x - r + 2f, y - r + 2f, x + r - 2f, y + r - 2f)
        setStroke(1.4f, withAlpha(Color.WHITE, DISC_RIM_LIGHT_ALPHA), true)
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(rect, 190f, 90f, false, stroke)
        // Orbit discs round 3: a dark contact ring just outside the rim (the drop shadow alone vanished on black)
        setStroke(2f, withAlpha(Color.BLACK, 170), true)
        canvas.drawCircle(x, y, r + 1f, stroke)
    }

    /** Drop shadow and specular shaders, centred on (0, 0) (the canvas is translated), one per radius. */
    private var shadowR = 0f
    private var shadowSh: Shader? = null
    private var specR = 0f
    private var specSh: Shader? = null
    private var shadowR2 = 0f
    private var shadowSh2: Shader? = null
    private var specR2 = 0f
    private var specSh2: Shader? = null

    private fun shadowShader(r: Float): Shader {
        if (r == shadowR) shadowSh?.let { return it }
        if (r == shadowR2) shadowSh2?.let { return it }
        val outer = r + DISC_SHADOW_SOFT
        val sh = RadialGradient(
            0f, 0f, outer, intArrayOf(withAlpha(Color.BLACK, 230), withAlpha(Color.BLACK, 230), withAlpha(Color.BLACK, 0)),
            floatArrayOf(0f, (r - 4f) / outer, 1f), Shader.TileMode.CLAMP
        )
        if (shadowSh == null) { shadowR = r; shadowSh = sh } else { shadowR2 = r; shadowSh2 = sh }
        return sh
    }

    private fun specShader(r: Float): Shader {
        if (r == specR) specSh?.let { return it }
        if (r == specR2) specSh2?.let { return it }
        val sh = RadialGradient(
            0f, 0f, 0.42f * r, intArrayOf(withAlpha(Color.WHITE, DISC_SPEC_ALPHA), withAlpha(Color.WHITE, DISC_SPEC_ALPHA / 3), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        if (specSh == null) { specR = r; specSh = sh } else { specR2 = r; specSh2 = sh }
        return sh
    }

    /**
     * Orbit discs round 3 (review: the glass ball at 9 o'clock read as a fifth status disc): the bolus button is the one
     * flat, solid, pressable object on the face: a solid indigo disc (r 40, one step above the r 39 discs) with a white
     * 2.5 px ring, no glass light, and the amount in 26 px bold. States by SHAPE: armed = brighter fill, the countdown
     * ring running out and a tick; sent = faded with a spinner arc and "…"; disabled = no fill, dashed outline and a
     * strike. Taps, the guard and the confirm semantics are unchanged.
     */
    private fun drawBolus(canvas: Canvas) {
        val state = bolusCheck.state
        val armed = gesture.isArmed && state == State.IDLE
        val r = OrbitFaceLogic.BOLUS_HALF
        val half = r + BOLUS_PAD
        bolusDst.set(bolusX - half, bolusY - half, bolusX + half, bolusY + half)
        val alpha = if (state == State.SENT) SENT_ALPHA else 255
        if (state != State.DISABLED) {
            // the same soft drop shadow as the status discs (the button sits on the dial)
            setFill(Color.WHITE, true)
            fill.shader = shadowShader(r)
            canvas.save()
            canvas.translate(bolusX, bolusY + DISC_SHADOW_DY)
            canvas.drawCircle(0f, 0f, r + DISC_SHADOW_SOFT, fill)
            canvas.restore()
            fill.shader = null
            bmpPaint.alpha = alpha
            (if (armed) bolusArmed else bolusIdle)?.let { canvas.drawBitmap(it, null, bolusDst, bmpPaint) }
            bmpPaint.alpha = 255
        } else {
            setFill(DISABLED_FILL, true)
            canvas.drawCircle(bolusX, bolusY, r, fill)
        }
        val amountY = bolusY + BOLUS_AMOUNT_DY
        val subY = bolusY + BOLUS_SUB_DY
        when {
            armed                -> {
                // Orbit discs round 4: the countdown is a 4 px white ring on a dark track (readable on the bright indigo)
                setStroke(ARMED_RING_W, withAlpha(Color.BLACK, 110), true)
                canvas.drawPath(outlinePath, stroke)
                val remaining = (1f - (System.currentTimeMillis() - guard.armedAt).toFloat() / BolusTapGesture.WINDOW_MS).coerceIn(0f, 1f)
                outlineSegment.rewind()
                if (outlineMeasure.getSegment(0f, outlineLength * remaining, outlineSegment, true)) {
                    stroke.color = Color.WHITE
                    stroke.strokeCap = Paint.Cap.ROUND
                    canvas.drawPath(outlineSegment, stroke)
                }
                drawBolusAmount(canvas, amountY, Color.WHITE)
                // Orbit polish round 4 (review: "+0,5" over "2×" read like a double dose): always the "tap again" word,
                // bold, fitted to the button's chord; "2×" only on a button under 50 px across (never on Orbit's 80 px)
                val word = armedWord()
                drawText(canvas, word, bolusX, bolusY + ARMED_WORD_DY, armedWordSize, tf700, Color.WHITE, true, ARMED_WORD_TRACK)
            }

            state == State.SENT  -> {
                // "waiting for the phone": a bright quarter arc on a faint ring, as Halo's pending ball
                val rr = r - 1.5f
                rect.set(bolusX - rr, bolusY - rr, bolusX + rr, bolusY + rr)
                setStroke(1.5f, withAlpha(Color.WHITE, 70), true)
                canvas.drawCircle(bolusX, bolusY, rr, stroke)
                setStroke(3f, Color.WHITE, true)
                stroke.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(rect, -90f, 90f, false, stroke)
                drawBolusAmount(canvas, amountY, Color.WHITE)
                drawText(canvas, "…", bolusX, subY, 22f, tf700, BOLUS_TEXT, true)
            }

            state == State.DISABLED -> {
                // disabled by SHAPE, not only by alpha: dashed outline and a faint slash UNDER the text
                setStroke(2f, withAlpha(INDIGO, 150), true)
                stroke.pathEffect = disabledDash
                canvas.drawPath(outlinePath, stroke)
                stroke.pathEffect = null
                setStroke(2.5f, TXT3, true)
                stroke.strokeCap = Paint.Cap.ROUND
                val k = r * 0.55f // inside the disc
                canvas.drawLine(bolusX - k, bolusY + k, bolusX + k, bolusY - k, stroke)
                drawBolusAmount(canvas, amountY, TXT3)
                drawText(canvas, "U", bolusX, subY, BOLUS_SUB_SIZE, tf600, TXT3, true)
            }

            else                 -> {
                // idle: the white ring marks the one pressable object (Orbit discs round 4: quieter, so arming is a visible change)
                setStroke(BOLUS_RING_W, withAlpha(Color.WHITE, IDLE_RING_ALPHA), true)
                canvas.drawCircle(bolusX, bolusY, r - BOLUS_RING_W / 2f, stroke)
                drawBolusAmount(canvas, amountY, Color.WHITE)
                drawText(canvas, "U", bolusX, subY, BOLUS_SUB_SIZE, tf600, BOLUS_TEXT, true)
            }
        }
        if (flashUntil != 0L && System.currentTimeMillis() < flashUntil) {
            // request sent: white rim flash for SEND_FLASH_MS
            setStroke(3f, Color.WHITE, true)
            canvas.drawPath(outlinePath, stroke)
        }
    }

    private var armedWordSize = ARMED_WORD_SIZE

    /**
     * "nochmal" / "again" in bold, [ARMED_WORD_SIZE] shrunk to fit [BOLUS_AMOUNT_MAX_W], never below [ARMED_WORD_MIN].
     * "2×" only when the button is under [ARMED_TWICE_BELOW] px across (as Halo's small ball); Orbit's is 80 px.
     */
    private fun armedWord(): String {
        if (2f * OrbitFaceLogic.BOLUS_HALF < ARMED_TWICE_BELOW) {
            armedWordSize = ARMED_TWICE_SIZE
            return ARMED_TWICE
        }
        setText(ARMED_WORD_SIZE, tf700)
        text.letterSpacing = ARMED_WORD_TRACK
        val w = text.measureText(labels.again)
        // the chord inside the 4 px countdown ring (r 39) with 2 px air, at the word's baseline (its edge nearest the rim)
        val inner = OrbitFaceLogic.BOLUS_HALF - 1f - ARMED_RING_W / 2f - 2f
        var size = ARMED_WORD_SIZE
        repeat(3) {
            val edge = ARMED_WORD_DY + OrbitFaceLogic.CAP_HALF_EM * size
            val chord = 2f * sqrt(max(0f, inner * inner - edge * edge))
            size = OrbitFaceLogic.fitSize(w, ARMED_WORD_SIZE, min(BOLUS_AMOUNT_MAX_W, chord)).coerceIn(ARMED_WORD_MIN, ARMED_WORD_SIZE)
        }
        armedWordSize = size
        return labels.again
    }

    /** The bolus amount, 26 px bold, shrunk only to the button's chord (a long "+10,5" stays inside the ring). */
    private fun drawBolusAmount(canvas: Canvas, cy: Float, colour: Int) {
        setText(BOLUS_AMOUNT_SIZE, tf700)
        val size = OrbitFaceLogic.fitSize(text.measureText(bolusAmountText), BOLUS_AMOUNT_SIZE, BOLUS_AMOUNT_MAX_W)
        drawText(canvas, bolusAmountText, bolusX, cy, size, tf700, colour, true)
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

    /** Horizontal fade of the target band over its first and last [BAND_FADE] px (Glass/Orbit UX round 4). */
    private fun bandFade(colour: Int): Shader {
        val x0 = OrbitFaceLogic.SPARK_X0
        val x1 = OrbitFaceLogic.SPARK_X1
        val f = BAND_FADE / (x1 - x0)
        val clear = colour and 0x00FFFFFF
        return LinearGradient(x0, 0f, x1, 0f, intArrayOf(clear, colour, colour, clear), floatArrayOf(0f, f, 1f - f, 1f), Shader.TileMode.CLAMP)
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
        // Orbit discs round 3: no chart (and no chart tap) while a W4-W9 pill holds the bottom and the date stacks there
        if (!pillRowShown && dx in GRAPH_X0..GRAPH_X1 && dy in GRAPH_Y0..GRAPH_Y1) {
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
        private const val DATE_SIZE = 26f // Orbit discs round 3: 22 -> 26 (an 18 px cap, Halo's date)
        private const val DATE_MIN_SIZE = 22f
        private const val AMPM_SIZE = 18f
        private const val CAPTION_TEXT = 24f // Orbit discs round 3: 22 -> 24, the word carries the alarm
        private const val CAPTION_PAD = 12f
        private const val PILL_GAP = 8f
        /** Orbit discs round 4: a W1-W3 warning on the delta row may run wider than the delta (it clears the 3 / 9 o'clock stations). */
        private const val DELTA_WARN_MAX_WIDTH = 280f
        private const val WARN_ICON_SCALE = 1.25f
        private const val INFO_TXT = 0xFFC8CFDB.toInt()
        private const val BALL_VALUE_DY = OrbitFaceLogic.BALL_VALUE_DY
        private const val BALL_LABEL_DY = OrbitFaceLogic.BALL_LABEL_DY
        /** Orbit discs round 4: the lit ring, #B8C0CF at the upper left to #6B7385 at the lower right (outdoor contrast). */
        private const val TRACK_LIT = 0xFFB8C0CF.toInt()
        private const val TRACK_DARK = 0xFF6B7385.toInt()
        private const val BAND_FADE = 28f // Orbit discs round 4: 40 -> 28 on the hairlines. Before: Glass/Orbit UX round 6: 24 -> 40
        private const val BAND_LINE_ALPHA = 45
        private const val AREA_ALPHA = 64
        private const val AREA_FADE = 20f
        private const val AREA_STEPS = 10
        private const val BOLUS_SUB_SIZE = OrbitFaceLogic.MIN_TEXT_PX
        /** Orbit discs round 3: the amount 20 -> 26 px bold (the one dosing number on the face), the "U" / tick under it. */
        private const val BOLUS_AMOUNT_SIZE = 26f
        private const val BOLUS_AMOUNT_MAX_W = 64f
        private const val BOLUS_AMOUNT_DY = -7f
        private const val BOLUS_SUB_DY = 17f
        private const val BOLUS_RING_W = 2f // Orbit discs round 4: 2.5 -> 2 px at 63 % white
        private const val IDLE_RING_ALPHA = 160
        private const val ARMED_RING_W = 4f
        private const val ARMED_WORD_SIZE = 17f
        private const val ARMED_WORD_MIN = 15f
        private const val ARMED_WORD_DY = 12f // 5 px under the amount's baseline, where the chord is widest
        private const val ARMED_WORD_TRACK = -0.02f
        private const val ARMED_TWICE_BELOW = 50f
        private const val ARMED_TWICE_SIZE = 22f
        private const val ARMED_TWICE = "2×"
        /** Pause glyph width (two 5 px bars 11 px apart) and its gap to "AUS", at the 28 px value size. */
        private const val PAUSE_W = 16f
        private const val PAUSE_GAP = 5f
        private const val PHONE_GLYPH_W = 32f
        private const val DELTA_ARROW_STROKE = 4.5f
        private val HEAD_SIGNS = intArrayOf(-1, 1)
        private const val AMPM_GAP = 3f
        private const val BOLUS_PAD = 16f
        private const val CAPTION_MS = 1_500L

        /** Graph tap zone (spec §9), design px. */
        private const val GRAPH_X0 = OrbitFaceLogic.SPARK_X0
        private const val GRAPH_X1 = OrbitFaceLogic.SPARK_X1
        private const val GRAPH_Y0 = 296f // Glass/Orbit UX round 6: follows the lower chart
        private const val GRAPH_Y1 = 372f
        private val PAUSE_OFFSETS = floatArrayOf(-5.5f, 5.5f)

        /** Bitmap margin around the corona ellipse, and the blur radius (a 60 px Gaussian of the mockup). */
        private const val CORONA_BLUR = 120f
        private const val CORONA_BLUR_RADIUS = 100f
        private const val CORONA_EMPHASIS_ALPHA = 36   // UX round 3: fainter (was 41), the BG read neon
        private const val CORONA_NORMAL_ALPHA = 150    // Glass/Orbit UX round 3: 200 -> 150, the high haze read neon
        private const val LABEL_TRACKING = 0.05f // Orbit discs round 3: 0.04 -> 0.05. Glass/Orbit UX round 2: tracked small labels (LOOP, IOB, KH)
        private const val HERO_TRACKING = -0.02f // Glass/Orbit UX round 6: closes the "6 , 4" comma gap
        private const val DISABLED_FILL = 0xFF12152A.toInt() // disabled bolus: a dark disc, no sphere
        private const val SENT_ALPHA = 140             // 55 %
        private const val DEFAULT_LOW_MGDL = 70.0
        private const val DEFAULT_HIGH_MGDL = 180.0

        private const val GREEN = 0xFF3DDC97.toInt()
        private const val TXT1 = 0xFFEEF1F7.toInt()
        private const val AMB1 = 0xFFE0E0E0.toInt()
        private const val AMB2 = 0xFF9A9A9A.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5A6E.toInt()
        private const val LOW_TXT = 0xFFFFD6DC.toInt()
        private const val STALE_C = 0xFF8A93A8.toInt()
        private const val VIOLET = 0xFFB388FF.toInt()
        // UX round 4: neutral rings darker (5A6B85 -> 3A4250) so the hero stays dominant; dim one step darker still
        // Orbit discs round 1: a dark base (L* about 5) with a faint family tint (about 11 % at the upper left), so the
        // discs never outshine the BG; the hot disc's tint is about 2.2 x stronger
        // Orbit discs round 2: lit spheres (the round 1 tint of about 11 % read flat and vanished on the watch)
        private const val DISC_BASE = 0xFF07090E.toInt()
        private const val DISC_SLATE = 0xFF5A6478.toInt() // placeholder / no-family body
        // Orbit discs round 3: one body recipe for every family (the round 2 KH / basal bodies were muddy or stale-grey),
        // the light / shade contrast about 15 % lower (a sphere, not a gloss button)
        private const val DISC_LIT = 0.48f
        private const val DISC_MID = 0.32f
        private const val DISC_DARK = 0.18f
        private const val DISC_EDGE = 0.12f
        private const val DISC_HOT_K = 0.85f // the hot disc's warning-coloured value needs a darker body
        private const val DISC_RIM = 2f
        private const val DISC_HOT_RIM = 3f
        private const val DISC_GLOW = 10f
        private const val DISC_GLOW_ALPHA = 55
        private const val DISC_HOT_GLOW_ALPHA = 150
        private const val DISC_BAD_GLOW_ALPHA = 175 // a stopped loop glows at least as strongly as a paused one
        private const val DISC_SHADOW_DY = 5f
        private const val DISC_SHADOW_SOFT = 8f
        private const val DISC_SPEC_ALPHA = 68
        private const val DISC_RIM_LIGHT_ALPHA = 105
        /** Family rims (L* about 70-80) and the saturated hues their bodies are tinted with. */
        private const val IOB_C = 0xFF5BB8E8.toInt()
        private const val IOB_BODY = 0xFF3A9CE0.toInt()
        /**
         * Orbit discs round 4 (review: amber belongs to warnings only): KH is a low-chroma sand rim on a warm graphite body
         * (the U/h recipe, warm instead of cool), so it never reads as a warning next to an amber BG or a paused loop.
         */
        private const val KH_C = 0xFFE2CBAE.toInt()
        private const val KH_BODY = 0xFF9A8A76.toInt()
        private const val BASAL_C = 0xFFC9D3E6.toInt()
        private const val BASAL_BODY = 0xFF6A80B0.toInt()
        private const val TT_C = 0xFFD08CF0.toInt()
        private const val TT_BODY = 0xFFA050D8.toInt()
        /** The late loop's age in its warning colour, light enough on the hot body (word and colour agree). */
        /** Orbit discs round 4 (review: red on dark red had the lowest contrast): near-white, the rim and glow carry the colour. */
        private const val HOT_RED_TXT = 0xFFFFE3E7.toInt()
        private const val HOT_AMBER_TXT = 0xFFFFF0D6.toInt()
        /** The paused loop's solid amber sphere and its near-black words. */
        private const val AMBER_LIT = 0xFFFFD58C.toInt()
        private const val AMBER_DEEP = 0xFFD98A1E.toInt()
        private const val PAUSED_RIM = 0xFFFFE2A8.toInt()
        private const val PAUSED_TXT = 0xFF1A1206.toInt()
        private const val RED_LIT = 0xFFFF9AA6.toInt()
        private const val RED_DEEP = 0xFFD8364C.toInt()
        private const val BAD_RIM = 0xFFFFC2CA.toInt()
        private const val BAD_ICON_SCALE = 1.05f
        private const val BAD_ICON_W = 18f // 14.7 px triangle + gap
        private const val BAD_VALUE_MIN = 24f
        /** Idle bolus button: a solid indigo (white 4.6:1 or more on it). */
        private const val BOLUS_FILL = 0xFF4752D6.toInt() // Orbit discs round 4: 4F5FF0 -> 4752D6, the BG stays the hero
        /** Orbit discs round 4: the socket of an empty 3 / 9 o'clock station. */
        private const val SOCKET_R = 5f
        private const val SOCKET_FILL = 0xFF1A1F2A.toInt()
        private const val SOCKET_RIM = 0xFF8C95A8.toInt()
        /** Disc labels: a light neutral, 4.5:1 or more on every disc fill (about 9:1 on the hot red one). */
        private const val DISC_LABEL = 0xFFB8C0D0.toInt()
        /** Ambient disc outline (1 px, interactive-free grey; dropped under burn-in protection). */
        private val DIAG = kotlin.math.sqrt(0.5).toFloat()
        private const val NEUTRAL_RING = 0xFF3A4250.toInt()
        private const val DIM_RING = 0xFF2C323D.toInt()
        private const val DIM_TXT = 0xFF7D8796.toInt()
        private const val TXT2 = 0xFF9AA6C4.toInt()
        private const val TXT3 = 0xFF6F7A99.toInt()
        private const val WARN_TXT = 0xFFFFE2B0.toInt()
        /** Indigo sphere of the idle bolus button (the Glass ball gradient). */
        private const val VAL_TXT = 0xFFEEF2F7.toInt()
        private const val TIME_C = 0xFFE6ECF4.toInt()
        /** Orbit discs round 4: a neutral light grey (TXT2 sat next to the stale token #8A93A8). */
        private const val DATE_C = 0xFFB0B8C8.toInt()
        private const val DELTA_C = 0xFFD5DCE8.toInt() // UX round 2: near txt1, the second reading after the BG
        private const val INDIGO = 0xFF7888FF.toInt()
        private const val BOLUS_TEXT = 0xFFE8EBFF.toInt()
        // Glass/Orbit UX round 1: a deeper indigo (white 7:1), the armed button no longer dominates the face
        // Glass/Orbit UX round 3: the family's armed indigo #5B63F5 (Glass), idle and armed differ by fill AND ring
        // Orbit discs round 3: armed is a brighter indigo than the solid idle button (plus the countdown and the tick)
        private const val ARMED_FILL = 0xFF5560E0.toInt() // Orbit polish round 4: white text 5.1:1 (was 3.2:1 on #7484FF)
        private const val NOW_HOLLOW = 0xFFC8C8C8.toInt()
        private const val PILL_ALERT = 0xFF3A1018.toInt()
        private const val PILL_WARN = 0xFF33240C.toInt()
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
