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
import android.text.format.DateFormat
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
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.State
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.BolusTapGesture
import app.aaps.wear.watchfaces.common.PhoneReach
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Ball
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.BgAge
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.MoonKind
import app.aaps.wear.watchfaces.orbit.OrbitFaceLogic.Range
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
    private var dateSize = 18f
    private var gapT = 10f
    private var gapD = 10f
    private var bgAge = BgAge.NONE
    private var range = Range.STALE
    private var rangeColour = STALE_C
    private var heroColour = STALE_C
    private var heroText = "---"
    private var heroSize = 108f
    private var heroX = OrbitFaceLogic.CX
    private var heroW = 0f
    private var trend = Trend.NONE
    private var trendX = 0f
    private var trendSize = 48f
    private var sentinelLow = false
    private var loopMuted = false
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

    // ambient range tracks (spec §6): dashed 6/6 for HIGH, doubled r 166 + r 171 for LOW
    private val ambDash = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    private val trackRectInner = RectF(OrbitFaceLogic.CX - 166f, OrbitFaceLogic.CY - 166f, OrbitFaceLogic.CX + 166f, OrbitFaceLogic.CY + 166f)
    private val trackRectOuter = RectF(OrbitFaceLogic.CX - 171f, OrbitFaceLogic.CY - 171f, OrbitFaceLogic.CX + 171f, OrbitFaceLogic.CY + 171f)
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
            sentinelLow = getString(R.string.orbit_sentinel_low),
            weekFormat = getString(R.string.orbit_week).replace("%1\$d", "%d"),
            pumpOnly = getString(R.string.orbit_pump_only),
            reservoirFormat = getString(R.string.orbit_reservoir).replace("%1\$s", "%s"),
            phoneBatteryFormat = getString(R.string.orbit_phone_battery).replace("%1\$s", "%s")
        )
        buildShapes()
        readPrefs()
        simpleUi.onCreate(::invalidate)

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
            firstStart = OrbitFaceLogic.firstStart(d.bgData.timeStamp, d.statusData.openApsStatus, d.statusData.iobSum)
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
        setText(30f, tf600)
        val timeW = text.measureText(timeText)
        setText(floorPx(12f), tf500)
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
        if (dateText.isEmpty()) {
            // pref.date off: the track closes at 6 o'clock
            gapD = 0f
        } else {
            setText(18f, tf500)
            val dateWidth = text.measureText(dateText)
            dateSize = OrbitFaceLogic.fitSize(dateWidth, 18f, OrbitFaceLogic.DATE_LINE_MAX_WIDTH)
            gapD = OrbitFaceLogic.gapHalfDeg(dateWidth * dateSize / 18f)
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
        // spec v2 §4.2: digits txt1; the range lives in the corona, glyph, glow and sparkline
        heroColour = when (range) {
            Range.STALE      -> TXT2
            Range.URGENT_LOW -> LOW_TXT
            else             -> TXT1
        }
        heroText = OrbitFaceLogic.heroText(bg.sgvString, bg.sgv, ageMin)
        trend = if (OrbitFaceLogic.isOld(bgAge) || !showDirection) Trend.NONE else OrbitFaceLogic.trend(bg.slopeArrow, bg.deltaMgdl)
        layoutHero()
        val delta = OrbitFaceLogic.deltaSource(bg.delta, bg.deltaDetailed, bg.avgDelta, bg.avgDeltaDetailed, detailedDelta, avgDelta)
        deltaRow = OrbitFaceLogic.deltaRow(delta, ageMin, labels, showDelta, showAgo)

        // status
        firstStart = OrbitFaceLogic.firstStart(bg.timeStamp, st.openApsStatus, st.iobSum)
        val noProfile = OrbitFaceLogic.noProfile(st.externalStatus, st.iobSum)
        val loopOff = OrbitFaceLogic.loopOff(st.externalStatus, st.iobSum)
        val loopAge = OrbitFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        val dataAgeMs = if (d.lastUpdateTimestamp > 0L) now - d.lastUpdateTimestamp else Long.MAX_VALUE
        // the display treats an unknown link as reachable (no "Telefon getrennt" before the first answer);
        // the bolus guard does not (DISABLED until then)
        val link = OrbitFaceLogic.phoneLink(isHeadless || guard.phoneReach != PhoneReach.UNREACHABLE, dataAgeMs)
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
            balls[OrbitFaceLogic.IDX_BASAL] = OrbitFaceLogic.placeholderBall("BASAL")
            balls[OrbitFaceLogic.IDX_COB] = OrbitFaceLogic.placeholderBall(labels.cob)
            balls[OrbitFaceLogic.IDX_TT] = null
        } else {
            val mode = if (isHeadless) OrbitFaceLogic.LoopMode.UNKNOWN
            else OrbitFaceLogic.loopMode(loopStateNames, loopStatesTimeStamp, d.lastUpdateTimestamp)
            balls[OrbitFaceLogic.IDX_LOOP] =
                if (OrbitFaceLogic.loopBallShown(showLoop, OrbitFaceLogic.loopHealth(loopAge)))
                    OrbitFaceLogic.loopBall(st.openApsStatus, st.externalStatus, st.iobSum, now, labels, mode)
                else null
            balls[OrbitFaceLogic.IDX_IOB] = if (showIob) OrbitFaceLogic.iobBall(st.iobSum) else null
            balls[OrbitFaceLogic.IDX_BASAL] = if (showBasal) OrbitFaceLogic.basalBall(st.currentBasal, OrbitFaceLogic.tbrRunning(starts, ends, now)) else null
            balls[OrbitFaceLogic.IDX_COB] = if (showCob) OrbitFaceLogic.cobBall(st.cob, labels) else null
            balls[OrbitFaceLogic.IDX_TT] = if (showTt) OrbitFaceLogic.ttBall(st.tempTarget, st.tempTargetLevel) else null
        }
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
            setText(22f, tf600)
            ballSize[i] = OrbitFaceLogic.ballValueSize(text.measureText(b.value))
        }

        refreshBolusCheck()
        if (bolusCheck.state == State.SENT) {
            handler.removeCallbacks(pendingEnd)
            handler.postDelayed(pendingEnd, guard.pendingLeftMs() + 50L)
        }
        moon = OrbitFaceLogic.moon(minute, gapT, if (dateText.isEmpty()) -2f else gapD, bolusCheck.state != State.HIDDEN, ballShown)
        shiftX = OrbitFaceLogic.burnInShift(minuteKey, 0).toFloat()
        shiftY = OrbitFaceLogic.burnInShift(minuteKey, 1).toFloat()

        val pillUnder = slot != null && !slotInDeltaRow
        sparkTop = if (pillUnder) OrbitFaceLogic.SPARK_Y_TOP_PILL else OrbitFaceLogic.SPARK_Y_TOP
        sparkBottom = if (pillUnder) OrbitFaceLogic.SPARK_Y_BOTTOM_PILL else OrbitFaceLogic.SPARK_Y_BOTTOM
        buildSparkline(d, now)
    }

    /**
     * Hero group (spec §4.2/§4.3): BG + 14 + a 40 px glyph must fit 252; the size steps down by 4 (minimum 60).
     * The group is centred at x 225; without a glyph the digits re-centre. Builds the vector trend glyph.
     */
    private fun layoutHero() {
        setText(108f, tf700)
        val w108 = text.measureText(heroText)
        val glyph = trend != Trend.NONE
        heroSize = OrbitFaceLogic.heroSize(w108, glyph)
        heroW = w108 * heroSize / 108f
        heroX = OrbitFaceLogic.CX - OrbitFaceLogic.heroGroupWidth(w108, heroSize, glyph) / 2f + heroW / 2f
        trendSize = 48f * heroSize / 108f
        trendX = heroX + heroW / 2f + OrbitFaceLogic.HERO_GLYPH_GAP + OrbitFaceLogic.HERO_GLYPH_W * heroSize / 108f / 2f
        trendPath.rewind()
        if (trend == Trend.NONE) return
        val rad = Math.toRadians(trend.deg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val shaft = 0.62f * trendSize
        val tipX = trendX + dx * shaft / 2f
        val tipY = HERO_Y + dy * shaft / 2f
        trendPath.moveTo(trendX - dx * shaft / 2f, HERO_Y - dy * shaft / 2f)
        trendPath.lineTo(tipX, tipY)
        for (k in 0 until trend.heads) {
            val hx = tipX - dx * 0.24f * trendSize * k
            val hy = tipY - dy * 0.24f * trendSize * k
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
        caretCount = 0
        predCount = 0
        val low = bg.low.takeIf { it > 0 } ?: DEFAULT_LOW_MGDL
        val high = bg.high.takeIf { it > 0 } ?: DEFAULT_HIGH_MGDL
        lowY = OrbitFaceLogic.sparkY(low, sparkTop, sparkBottom)
        highY = OrbitFaceLogic.sparkY(high, sparkTop, sparkBottom)

        sparkWindowMs = timeFrameH * 60 * 60_000L
        val from = now - sparkWindowMs
        val points = d.graphData.entries.asSequence()
            .filter { it.timeStamp in from..now && it.sgv > 0 }
            .sortedBy { it.timeStamp }
            .toMutableList()
        if (bg.timeStamp in from..now && bg.sgv > 0 && (points.isEmpty() || points.last().timeStamp < bg.timeStamp)) points.add(bg)
        var prevT = Long.MIN_VALUE
        for (p in points) {
            val x = OrbitFaceLogic.sparkX(p.timeStamp, now, sparkWindowMs)
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
            nowX = OrbitFaceLogic.sparkX(bg.timeStamp, now, sparkWindowMs)
            nowY = OrbitFaceLogic.sparkY(bg.sgv, sparkTop, sparkBottom)
        }
        if (hasNowDot && showPredictions && !OrbitFaceLogic.isOld(bgAge)) {
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

        // 2. orbit track with the time and date gaps
        drawTrack(canvas, trackRect, 1.5f, TRACK, true)

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
        if (captionUntil != 0L && System.currentTimeMillis() < captionUntil) {
            // graph.timeframe_changed: "5 Std" top-left of the sparkline box
            setText(floorPx(13f), tf600)
            text.textAlign = Paint.Align.LEFT
            text.color = TXT2
            canvas.drawText(captionText, OrbitFaceLogic.SPARK_X0, sparkTop - 4f, text)
        }

        // 5. delta row, status slot
        val s = slot
        if (slotInDeltaRow && s != null) drawPill(canvas, s, 251f, 30f, 19f, ambient = false)
        else if (!firstStart) drawDeltaRow(canvas, DELTA_C, if (deltaRow.ageAmber) AMBER else DELTA_C, true, ambient = false)
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
        drawTime(canvas, TIME_C, tf600, true, outlined = false)
        if (dateText.isNotEmpty())
            drawText(canvas, dateText, OrbitFaceLogic.CX, OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f), dateSize, tf500, DATE_C, true)

        // 9. minute moon
        if (moon.kind == MoonKind.DOT) {
            setFill(MOON_C, true)
            canvas.drawCircle(OrbitFaceLogic.pointX(OrbitFaceLogic.ORBIT_R, moon.deg), OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, moon.deg), 4.5f, fill)
        }
    }

    /**
     * Ambient (spec §6): greys only (white in low-bit, AA off), no corona/glow/moon/bolus/targets/predictions.
     * The range is carried by the track's shape, so colour is never the only signal: solid ambLine for in range /
     * stale (dropped in low-bit), dashed 6/6 amb2 for HIGH, doubled r 166 + r 171 amb2 for LOW. Burn-in: outlined
     * digits and time, hollow dots/bars, strokes <= 2 px (3 for an urgent-low BG), per-minute [-4, 4] shift.
     */
    private fun drawAmbient(canvas: Canvas) {
        val aa = !lowBitAmbient
        val burnIn = burnInProtection
        when (range) {
            Range.HIGH, Range.VERY_HIGH -> {
                drawTrack(canvas, trackRect, 1f, amb(AMB2), aa, ambDash)
            }

            Range.LOW, Range.URGENT_LOW -> {
                drawTrack(canvas, trackRectInner, 1f, amb(AMB2), aa)
                drawTrack(canvas, trackRectOuter, 1f, amb(AMB2), aa)
            }

            else                        -> if (!lowBitAmbient) drawTrack(canvas, trackRect, 1f, AMB_LINE, aa)
        }

        // sparkline: history only
        setStroke(1.5f, amb(AMB3), aa)
        stroke.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(sparkPath, stroke)
        drawCarets(canvas, amb(AMB3), aa)
        if (hasNowDot) {
            setStroke(1.2f, amb(NOW_HOLLOW), aa)
            canvas.drawCircle(nowX, nowY, 4f, stroke)
        }

        drawHero(canvas, amb(AMB1), amb(AMB1), glow = false, ambient = true)
        val s = slot
        when {
            // W1..W3 (incl. the stale "Keine Daten · 17′") own slot A as an outline pill
            s != null && slotInDeltaRow -> drawPill(canvas, s, 251f, 30f, 18f, ambient = true)
            !firstStart                 -> drawDeltaRow(canvas, amb(AMB2), amb(if (deltaRow.ageAmber) AMB1 else AMB2), aa, ambient = true)
        }
        if (s != null && !slotInDeltaRow) drawPill(canvas, s, OrbitFaceLogic.SLOT_Y, 26f, 16f, ambient = true)

        // LOOP, IOB and TT as outlines; COB and BASAL are dropped
        for (i in balls.indices) {
            if (i == OrbitFaceLogic.IDX_COB || i == OrbitFaceLogic.IDX_BASAL) continue
            val b = balls[i] ?: continue
            val x = ballX[i]
            val y = ballY[i]
            setFill(Color.BLACK, aa)
            canvas.drawCircle(x, y, OrbitFaceLogic.BALL_R, fill)
            setStroke(1.2f, amb(AMB3), aa)
            if (b.dashed) stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, OrbitFaceLogic.BALL_R, stroke)
            stroke.pathEffect = null
            if (b.pause) drawPause(canvas, x, y - 7f, amb(AMB1), aa, hollow = true)
            else {
                val size = ballSize[i]
                drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - 7f, size, tf400, amb(AMB1), aa)
            }
            if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + 15f, floorPx(14f), tf600, amb(AMB2), aa, LABEL_TRACKING)
        }

        drawTime(canvas, amb(AMB1), tf400, aa, outlined = burnIn)
        if (dateText.isNotEmpty())
            drawText(canvas, dateText, OrbitFaceLogic.CX, OrbitFaceLogic.pointY(OrbitFaceLogic.ORBIT_R, 180f), dateSize, tf500, amb(AMB2), aa)
    }

    /** Time with the optional AM/PM suffix (locale.en_12h). [outlined]: burn-in, stroke only. */
    private fun drawTime(canvas: Canvas, colour: Int, tf: Typeface, aa: Boolean, outlined: Boolean) {
        setText(30f, tf, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = colour
        if (outlined) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = 1.5f
        }
        val y = baseline(57f)
        canvas.drawText(timeText, timeX, y, text)
        if (amPmText.isEmpty()) return
        setText(floorPx(12f), tf500, aa)
        text.textAlign = Paint.Align.LEFT
        text.color = if (isAmbient) amb(AMB2) else TXT2
        canvas.drawText(amPmText, amPmX, y, text)
    }

    private fun drawTrack(canvas: Canvas, oval: RectF, width: Float, colour: Int, aa: Boolean, effect: DashPathEffect? = null) {
        setStroke(width, colour, aa)
        stroke.pathEffect = effect
        // canvas angles start at 3 o'clock: clockwise-from-12 minus 90
        canvas.drawArc(oval, gapT - 90f, 180f - gapD - gapT, false, stroke)
        canvas.drawArc(oval, 180f + gapD - 90f, 180f - gapD - gapT, false, stroke)
        stroke.pathEffect = null
    }

    private fun drawCarets(canvas: Canvas, colour: Int, aa: Boolean) {
        if (caretCount == 0) return
        setFill(colour, aa)
        // burn-in: no filled shapes, the carets are outlines
        if (isAmbient && burnInProtection) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 1f
        }
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
        val urgent = range == Range.URGENT_LOW
        // ambient: Light, urgent low one weight step up (Regular)
        setText(heroSize, if (ambient) (if (urgent) tf400 else tf300) else tf700, aa)
        text.color = if (dashes && !ambient) TXT2 else colour
        if (ambient && burnInProtection) {
            // burn-in: outlined digits (black inside), 2 px, 3 px for urgent low
            text.style = Paint.Style.STROKE
            text.strokeWidth = if (urgent) 3f else 2f
        }
        if (glow) text.setShadowLayer(12f, 0f, 0f, withAlpha(rangeColour, if (OrbitFaceLogic.emphasis(range)) 150 else 120))
        canvas.drawText(heroText, heroX, baseline(HERO_Y), text)
        text.clearShadowLayer()
        var right = heroX + heroW / 2f
        if (trend != Trend.NONE) {
            setStroke(if (ambient) 2f else 4.6f * trendSize / 48f, arrowColour, aa)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(trendPath, stroke)
            right = trendX + trendSize * 0.31f
        }
        if (sentinelLow && !dashes) {
            // sentinel low: "TIEF" caption top-right of the digits
            setText(floorPx(14f), tf700, aa)
            text.textAlign = Paint.Align.LEFT
            text.color = if (ambient) amb(AMB1) else LOW_TXT
            canvas.drawText(labels.sentinelLow, heroX + heroW / 2f + 6f, baseline(HERO_Y - 30f), text)
        }
        if (OrbitFaceLogic.isStruck(bgAge) && !dashes) {
            setStroke(if (!ambient) 3f else if (lowBitAmbient) 2f else 1.5f, colour, aa)
            canvas.drawLine(heroX - heroW / 2f - 6f, HERO_Y + 4f, max(right, heroX + heroW / 2f + 6f), HERO_Y + 4f, stroke)
        }
    }

    private fun drawDeltaRow(canvas: Canvas, colour: Int, ageColour: Int, aa: Boolean, ambient: Boolean) {
        if (deltaRow.isEmpty && !phoneGlyph) return
        setText(21f, if (ambient) tf400 else tf500, aa)
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
        val tw = min(text.measureText(s.text), OrbitFaceLogic.SLOT_MAX_WIDTH)
        val k = OrbitFaceLogic.fitSize(text.measureText(s.text), textSize, OrbitFaceLogic.SLOT_MAX_WIDTH) / textSize
        val half = (iconW + tw) / 2f + 14f
        rect.set(OrbitFaceLogic.CX - half, cy - height / 2f, OrbitFaceLogic.CX + half, cy + height / 2f)
        setFill(bg, aa)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        setStroke(if (ambient) 1f else 1.5f, line, aa)
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
        if (b.dashed) {
            setFill(BALL_FILL, true)
            canvas.drawCircle(x, y, r, fill)
            // loop.never: dim 2 px; loop.open: loop.ok 1.6 px
            setStroke(if (b.ring == Ring.DIM) 2f else 1.6f, if (b.ring == Ring.DIM) DIM_RING else LOOP_OK, true)
            stroke.pathEffect = ringDash
            canvas.drawCircle(x, y, r - 1f, stroke)
            stroke.pathEffect = null
        } else {
            val shell = shells[b.ring.ordinal]
            if (shell != null) {
                val half = r + SHELL_PAD
                shellDst.set(x - half, y - half, x + half, y + half)
                if (muted) {
                    // red budget: the ring at alpha 110 over an opaque shell
                    setFill(BALL_FILL, true)
                    canvas.drawCircle(x, y, r, fill)
                    bmpPaint.alpha = MUTED_ALPHA
                }
                canvas.drawBitmap(shell, null, shellDst, bmpPaint)
                bmpPaint.alpha = 255
            }
        }
        if (b.arc > 0f) {
            setStroke(3f, CYAN, true)
            stroke.strokeCap = Paint.Cap.ROUND
            rect.set(x - r + 1f, y - r + 1f, x + r - 1f, y + r - 1f)
            canvas.drawArc(rect, -90f, 360f * b.arc, false, stroke)
        }
        val dim = b.ring == Ring.DIM || b.dimValue
        val labelColour = when {
            b.ring == Ring.DIM     -> DIM_TXT
            b.ring == Ring.NEUTRAL -> LBL_NEUTRAL
            muted                  -> withAlpha(ringColour(b.ring), MUTED_ALPHA)
            else                   -> withAlpha(ringColour(b.ring), 235)
        }
        if (b.pause) drawPause(canvas, x, y - 7f, AMBER, true, hollow = false)
        else {
            val size = ballSize[i]
            val tf = if (size < 22f) tf700 else tf600
            drawText(canvas, b.value, x, if (b.label.isEmpty()) y else y - 7f, size, tf, if (dim) DIM_TXT else VAL_TXT, true)
        }
        if (b.label.isNotEmpty()) drawText(canvas, b.label, x, y + 15f, floorPx(14f), tf600, labelColour, true, LABEL_TRACKING)
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

    /** Text sizes never go below 11 physical px (spec §1). */
    private fun floorPx(size: Float): Float = max(size, 11f / max(scale, 0.01f))

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
        val inside = bolusCheck.state != State.HIDDEN && BolusTapGesture.isInside(dx, dy, bolusX, bolusY, OrbitFaceLogic.BOLUS_HIT_R)
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
        if (dx in 155f..295f && dy in 30f..85f) {
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

        private const val MAX_POINTS = 96
        private const val HERO_Y = 191f
        private val HEAD_SIGNS = intArrayOf(-1, 1)
        private const val AMPM_GAP = 3f
        private const val BOLUS_CORNER = 16f
        private const val BOLUS_PAD = 16f
        private const val MUTED_ALPHA = 110
        private const val CAPTION_MS = 1_500L

        /** Graph tap zone (spec §9), design px. */
        private const val GRAPH_X0 = 143f
        private const val GRAPH_X1 = 299f
        private const val GRAPH_Y0 = 268f
        private const val GRAPH_Y1 = 340f
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
        private const val LOOP_OK = 0xFF40D68C.toInt()
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
