package app.aaps.wear.watchfaces.glass

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateFormat
import android.widget.Toast
import androidx.annotation.VisibleForTesting
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
import app.aaps.wear.BuildConfig
import app.aaps.wear.R
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.BallState
import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Level
import app.aaps.wear.watchfaces.common.FaceState.LoopHealth
import app.aaps.wear.watchfaces.common.FaceState.LoopMode
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.AmbTone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.OutlineStyle
import app.aaps.wear.watchfaces.common.FaceState.PhoneLink
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Pills
import app.aaps.wear.watchfaces.common.FaceState.Range
import app.aaps.wear.watchfaces.common.FaceState.Sentinel
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Tone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend
import app.aaps.wear.watchfaces.common.BolusRequestGuard
import app.aaps.wear.watchfaces.common.FaceState
import app.aaps.wear.watchfaces.common.LoopRunTime
import app.aaps.wear.watchfaces.common.BolusRequestGuard.Reason
import app.aaps.wear.watchfaces.common.BolusRequestGuard.TapResult
import app.aaps.wear.watchfaces.common.BolusRequestPrefs
import app.aaps.wear.watchfaces.common.BolusTapGesture
import app.aaps.wear.watchfaces.common.PhoneReachability
import app.aaps.wear.watchfaces.halo.HaloFaceLogic
import app.aaps.wear.watchfaces.utils.SimpleUi
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "AAPS Glass": frosted glass cards over a night gradient; an aurora behind the hero card whose hue is the
 * BG state (green in range, amber high, red low, grey stale). Spec: designs/glass_spec.md, states and colours
 * from the family design system designsystem/states.md (status slot W1-W10, ball state machine).
 *
 * Drawn on a 450x450 design grid scaled to the screen. Gradient, aurora and card chrome are baked into six
 * software bitmaps (in, high, high+, low, low+, stale; blur works there, not on the hardware canvas) off the
 * render thread whenever the screen size or the pill layout changes; every frame only blits the one for the
 * current range and draws text, the sparkline and the optional bolus ball from state recomputed on a data
 * change or a minute tick ([updateState]), never per frame.
 *
 * The armed countdown redraws at 30 fps for at most the 800 ms window. The base [WatchFace] (androidx
 * CanvasRenderer) has no partial invalidation, so the whole face is redrawn, but those frames only blit and
 * draw cached state.
 *
 * Optional bolus ball (left): a double-tap sends only a bolus REQUEST (ActionBolusPreCheck); the phone runs
 * its checks and the watch shows the standard confirmation. Every rule (default off, never in ambient, Wear
 * control on, phone reachable with fresh data, max bolus, 800 ms window, 5 s cooldown, 30 s pending, dedupe,
 * fresh reachability answer before SEND) lives in the shared [BolusRequestGuard] with the shared setting
 * ([BolusRequestPrefs], also used by Halo and Orbit); Glass only draws the ball ([GlassFaceLogic.ballState]).
 *
 * Not yet covered (catalogue states / prefs, spec glass_spec.md): multi-patient datasets 1/2 (§10, multi.*,
 * patient name), key_show_seconds, key_show_week_number, key_chart_time_frame (fixed 3 h, no 1,5 s
 * timeframe caption), key_show_rig_battery run, key_show_detailed_iob run, graph tap (MainMenu) and
 * double-tap time-frame change, time-zone tap, loop pill tap, value cross-fades, ball.delivering/delivered,
 * scaling rules below s 0.95 (pill labels hidden) and the square/flat-tyre adaptations of §11.
 */
@SuppressLint("Deprecated")
class GlassWatchface : WatchFace() {

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

    private var data: ComplicationData = ComplicationData()

    // ---------------------------------------------------------------- bolus request (shared rule set)

    // Holds the phone reachability (UNKNOWN until the first answer: no warning, but no bolus either).
    private val guard = BolusRequestGuard(clock = { System.currentTimeMillis() }, store = BolusRequestPrefs.lastSentStore { sp })
    private val gesture get() = guard.gesture
    private var flashUntil = 0L
    private var bgTapTime = 0L
    private var lastMenuOpenTime = 0L
    private val armedTick = object : Runnable {
        override fun run() {
            if (gesture.expireIfDue() || !gesture.isArmed) {
                dirty = true
                invalidate()
                return
            }
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }
    private val redraw = Runnable { dirty = true; invalidate() }

    // ---------------------------------------------------------------- derived state (rebuilt on change only)

    private var dirty = true
    private var stateMinute = -1L
    private var scale = 1f
    private var pixelSize = 0

    private var bgAge = BgAge.NONE
    private var stale = true
    private var strike = false
    private var range = Range.STALE
    private var rangeColour = STALE_COL
    private var bgText = "---"
    private var bgColour = TXT2
    private var bgSize = 84f
    private var bgHalfWidth = 0f
    private var sentinel: Sentinel? = null
    private var caption = FaceState.Caption.NONE
    private var captionText = ""
    private var hero = GlassFaceLogic.heroLayout(showChip = true, metaEmpty = false)
    private var outline = GlassFaceLogic.ambientOutline(Range.STALE, lowBit = false)
    private var trend = Trend.NONE
    private var showChip = true
    private var deltaText = "--"
    private var showDelta = true
    private var ageText = "--"
    private var showAge = true
    private var ageLevel = Level.ALERT
    private var metaX = 225f
    private var metaSize = META_SIZE
    private var metaDeltaW = 0f
    private var metaSepW = 0f
    private var link = PhoneLink.OK
    private var owner: FaceState.Warning? = null
    private var pills = Pills.BOTH
    private var predictionsOn = true
    private var firstStart = true

    private var loopHealth = LoopHealth.NEVER
    private var loopMode = LoopMode.UNKNOWN
    private var loopText = "--"
    private var loopTextAmbient = "--"
    private var loopLabel = "LOOP"
    private var loopLabelShown = true
    private var pillValueSize = PILL_VALUE_SIZE
    private var pillUnitSize = PILL_UNIT_SIZE
    private var showKh = true
    private var khText = "--"
    private var khUnit = ""
    private var khColour = TXT3
    private var iobUnit = ""
    /** Pill row (loop, IOB, KH): widths (0 = not drawn) and left edges, centred as a group (UX round 2). */
    private val pillW = FloatArray(3)
    private var pillX = FloatArray(3)
    private var loopValueColour = TXT3
    private var loopMuted = false
    private var iobText = "--"
    private var iobColour = TXT3

    private var footer: List<GlassFaceLogic.Run> = emptyList()
    private var footerAmbient: List<GlassFaceLogic.Run> = emptyList()
    private val footerX = FloatArray(12)
    private val footerAmbientX = FloatArray(12)
    private var footerSize = GlassFaceLogic.FOOTER_SIZE
    private var footerAmbientSize = GlassFaceLogic.FOOTER_SIZE

    private var ball = BallState.HIDDEN
    private var bolusCheck = BolusRequestGuard.Check(BolusRequestGuard.State.HIDDEN, Reason.PREF_OFF)
    private var amountText = "+0,5"
    private var timeText = ""
    private var amPmText: String? = null
    private var timeX = 225f
    private var amPmX = 0f
    private var showDate = true
    private var timeY = TIME_Y
    private var dateText = ""
    private var dateKey = ""
    private var burnX = 0f
    private var burnY = 0f
    private var lowY = 0f
    private var highY = 0f
    /** Glass polish round 1: a band edge is drawn only where it lies inside the plot. */
    private var lowEdge = false
    private var highEdge = false
    /** Glass polish round 4: a visible value is below the low line (hypo y scale), the zone under the low edge is tinted red. */
    private var lowZone = false
    private var lowMgdl = GlassFaceLogic.DEFAULT_LOW_MGDL
    private var highMgdl = GlassFaceLogic.DEFAULT_HIGH_MGDL
    private var metaIsWarning = false

    private val history = Path()
    private var hasHistory = false
    private var curX = 0f
    private var curY = 0f
    private var hasCurrent = false
    /** Forecast polyline (now dot first, then the forecast slots) and the dot centres placed along it. */
    private val predXY = FloatArray(2 * (GlassFaceLogic.SPARK_FORECAST + 1))
    private val predDotXY = FloatArray(2 * 24)
    private var predDotCount = 0
    private val segX = FloatArray(GlassFaceLogic.SPARK_HISTORY + 2)
    private val segY = FloatArray(GlassFaceLogic.SPARK_HISTORY + 2)
    private val segV = DoubleArray(GlassFaceLogic.SPARK_HISTORY + 2)
    /**
     * Glass polish round 6: older history above the plot's top (clamped) as (x0, x1, peak mg/dL) triples; drawn faded in
     * the colour of its REAL value with an up-chevron, never as a solid plateau that reads as data.
     */
    private val offRuns = FloatArray(3 * 12)
    private var offCount = 0
    private val offTmp = FloatArray(3 * 12)
    private val chevron = Path()
    private val offDots = DashPathEffect(floatArrayOf(0.1f, 5f), 0f)
    private var predCount = 0
    private var sparkX0 = GlassFaceLogic.SPARK_X0
    private var sparkForecast = true
    private var sparkEndX = GlassFaceLogic.SPARK_X1
    private var nowX = GlassFaceLogic.nowX()
    private var shaderKey = ""

    // ---------------------------------------------------------------- paints, created once

    private val tf200 = Typeface.create(Typeface.DEFAULT, 200, false)
    private val tf300 = Typeface.create(Typeface.DEFAULT, 300, false)
    private val tf400 = Typeface.create(Typeface.DEFAULT, 400, false)
    private val tf500 = Typeface.create(Typeface.DEFAULT, 500, false)
    private val tf600 = Typeface.create(Typeface.DEFAULT, 600, false)
    private val tf700 = Typeface.create(Typeface.DEFAULT, 700, false)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    // Time digits: tabular figures, configured once (setText() resets the shared paint).
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; fontFeatureSettings = "tnum" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Erases the band edges under the trace (Glass polish round 3: an edge breaks around the curve). */
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    /** Band edges dashed (Glass polish round 3): a reference pattern that never fuses with a solid trace into a border. */
    private val edgeDash = DashPathEffect(floatArrayOf(EDGE_DASH_ON, EDGE_DASH_OFF), 0f)
    private val dash65 = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    private val rect = RectF()
    private val historyShaders = arrayOfNulls<Shader>(Range.entries.size)

    // Baked bitmaps: the six backgrounds (one per range: in, high, high+, low, low+, stale), the ball variants.
    // Backgrounds are baked off the render thread for (size, pill layout) and swapped in as one set.
    private var backgrounds: Array<Bitmap?> = arrayOfNulls(Range.entries.size)
    private var bakedKey = ""
    private var bakingKey = ""
    private val fallbackPaint = Paint().apply {
        color = Color.BLACK
    }
    private val ballBitmaps = arrayOfNulls<Bitmap>(2) // idle, plain (disabled); armed is a flat fill

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        BolusRequestPrefs.migrate(sp) // per-face settings of earlier iterations -> the shared pair
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.GLASS.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())
        simpleUi.onCreate { dirty = true; invalidate() }

        watchfaceScope.launch {
            complicationDataRepository.complicationData.collect { onData(it) }
        }
        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { onSettingsChanged() }
        disposable += rxBus
            .toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { onSettingsChanged() }

        refreshPhoneReachable()
        rxBus.send(EventWearToMobile(ActionResendData("GlassWatchface::onCreate")))
    }

    private fun onData(d: ComplicationData) {
        data = d
        dirty = true
        invalidate()
    }

    /**
     * Offline render harness only (wear/src/test); never called on the watch. [d] as received data, without
     * injection or side effects (the injected fields must be set before). Returns the bolus guard (phone answer, taps).
     */
    @VisibleForTesting
    internal fun prepareOffline(d: ComplicationData): BolusRequestGuard {
        onData(d)
        return guard
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disposable.clear()
        watchfaceScope.cancel()
        simpleUi.onDestroy()
        recycleBitmaps()
        super.onDestroy()
    }

    private fun onSettingsChanged() {
        simpleUi.updatePreferences()
        dateKey = "" // locale or date pref may have changed
        dirty = true
        invalidate()
    }

    override fun onWatchModeChanged(watchMode: WatchMode) {
        // mode.transition: ball hidden, armed cleared; ages recomputed before the next frame
        disarm()
        burnX = 0f
        burnY = 0f
        dirty = true
    }

    override fun onTimeChanged(oldTime: WatchFaceTime, newTime: WatchFaceTime) {
        if (newTime.hasMinuteChanged(oldTime)) {
            dirty = true
            refreshPhoneReachable()
            if (isAmbient && burnInProtection) {
                val key = (if (latestEpochMillis > 0L) latestEpochMillis else System.currentTimeMillis()) / FaceState.MINUTE_MS
                burnX = FaceState.burnInShift(key, 0).toFloat()
                burnY = FaceState.burnInShift(key, 1).toFloat()
            }
        }
    }

    /** CapabilityClient FILTER_REACHABLE (contract gap G7: no "phone connected" flag); answers asynchronously. */
    private fun refreshPhoneReachable() {
        PhoneReachability.refresh(this, guard, aapsLogger, "GlassWatchface") {
            if (guard.phoneReach != app.aaps.wear.watchfaces.common.PhoneReach.REACHABLE) disarm()
            dirty = true
            invalidate()
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusAmount(): Double = BolusRequestPrefs.amount(sp)

    private fun bolusInput() = BolusRequestGuard.Input(
        prefOn = BolusRequestPrefs.enabled(sp),
        ambient = isAmbient,
        wearControl = preferences.get(BooleanKey.WearControl),
        amount = bolusAmount(),
        maxBolus = BolusRequestPrefs.maxBolus(sp, getString(R.string.key_treatments_safety_max_bolus)),
        lastPhoneMessage = data.lastUpdateTimestamp,
        bgTimeStamp = data.bgData.timeStamp,
        headless = isHeadless || !isLiveRender,
        firstStart = firstStart
    )

    private fun disarm() {
        gesture.reset()
        handler.removeCallbacks(armedTick)
    }

    // ---------------------------------------------------------------- state

    private fun colourOf(r: Range) = when (r) {
        Range.IN                       -> IN_COL
        Range.HIGH, Range.VERY_HIGH    -> HIGH_COL
        Range.LOW, Range.URGENT_LOW    -> LOW_COL
        Range.STALE                    -> STALE_COL
    }

    private fun toneColour(t: Tone) = when (t) {
        Tone.TXT1  -> TXT1
        Tone.TXT2  -> TXT2
        Tone.TXT3  -> TXT3
        Tone.INS   -> INS_COL
        Tone.TT    -> TT_COL
        Tone.TBR   -> TBR_COL
        Tone.WARN  -> WARN_COL
        Tone.ALERT -> ALERT_COL
    }

    private val ageWords by lazy {
        GlassFaceLogic.AgeWords(
            hours = { getString(R.string.glass_hours, it.toInt()) },
            days = { getString(R.string.glass_days, it.toInt()) }
        )
    }

    private val slotWords by lazy {
        GlassFaceLogic.SlotWords(
            waiting = getString(R.string.glass_waiting),
            phoneLost = { getString(R.string.glass_phone_disconnected, it) },
            noData = { getString(R.string.glass_no_data, it) },
            noProfile = getString(R.string.glass_no_profile),
            loopOff = getString(R.string.glass_loop_off),
            loopAge = { getString(R.string.glass_loop_age, it) },
            reservoir = { getString(R.string.glass_reservoir, it) },
            phoneBattery = { getString(R.string.glass_phone_battery, it) },
            cob = { getString(R.string.glass_cob, it) },
            bgi = { "BGI $it" },
            age = ageWords
        )
    }

    private fun loopStates(): Pair<Set<String>, Long> = try {
        val list = EventData.deserialize(sp.getString(R.string.key_loop_states_data, "")) as? EventData.LoopStatesList
        if (list == null) emptySet<String>() to 0L else list.states.map { it.state.name }.toSet() to list.timeStamp
    } catch (_: Exception) {
        emptySet<String>() to 0L
    }

    private fun formatDate(): String {
        val date = LocalDate.of(latestTime.year, latestTime.month + 1, latestTime.monthDay)
        val locale = Locale.getDefault()
        return if (locale.language == Locale.GERMAN.language) GlassFaceLogic.dateText(date, Locale.GERMANY)
        else date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale))
    }

    /** Recompute everything that is drawn from the data and the clock. Runs on a data change or a minute tick. */
    private fun updateState(now: Long) {
        dirty = false
        stateMinute = now / GlassFaceLogic.MINUTE_MS
        val bg = data.bgData
        val st = data.statusData
        val old = getString(R.string.old)

        // time / date from the renderer's time (12 h: "2:07" + "PM" 17 px, the composite centred)
        timeText = GlassFaceLogic.timeText(latestTime.hour, latestTime.minute, is24HourFormat)
        amPmText = GlassFaceLogic.amPm(latestTime.hour, is24HourFormat)
        timePaint.typeface = tf400
        timePaint.textSize = TIME_SIZE
        val timeW = timePaint.measureText(timeText)
        setText(tf500, AM_PM_SIZE)
        val amPmW = amPmText?.let { AM_PM_GAP + text.measureText(it) } ?: 0f
        timeX = CX - (timeW + amPmW) / 2f
        amPmX = timeX + timeW + AM_PM_GAP
        showDate = sp.getBoolean(R.string.key_show_date, true)
        timeY = if (showDate) TIME_Y else TIME_Y_NO_DATE
        val locale = Locale.getDefault()
        val key = "${latestTime.year}-${latestTime.month}-${latestTime.monthDay}-$locale"
        if (key != dateKey && latestTime.year > 0) {
            dateKey = key
            dateText = formatDate()
        }

        // signals
        firstStart = GlassFaceLogic.firstStart(bg.timeStamp, LoopRunTime.of(st), st.iobSum)
        link = GlassFaceLogic.phoneLink(guard.phoneReach, data.lastUpdateTimestamp, now)
        val age = GlassFaceLogic.ageMinutes(bg.timeStamp, now)
        bgAge = GlassFaceLogic.bgAge(age)
        stale = GlassFaceLogic.isStale(bgAge)
        strike = GlassFaceLogic.strikeThrough(bgAge)
        trend = GlassFaceLogic.trend(bg.slopeArrow, bg.deltaMgdl)
        // the phone's sgvLevel decides low / high (same as Halo and Orbit), FaceState adds urgent low / very high
        range = FaceState.range(bg.sgv, bg.sgvLevel, bgAge, GlassFaceLogic.fastFall(trend, bg.deltaMgdl))
        rangeColour = colourOf(range)
        sentinel = GlassFaceLogic.sentinel(bg.sgv, bgAge)
        caption = FaceState.caption(range)
        captionText = when (caption) {
            FaceState.Caption.LOW_WORD, FaceState.Caption.LOW_BADGE -> getString(R.string.face_low)
            FaceState.Caption.HIGH_WORD                             -> getString(R.string.face_high)
            FaceState.Caption.NONE                                  -> ""
        }
        outline = GlassFaceLogic.ambientOutline(range, lowBitAmbient, burnInProtection)

        // meta line (delta · age); empty for age.none / first start
        val (deltaOn, ageOn) = GlassFaceLogic.metaVisible(
            bgAge, prefShowAgo = sp.getBoolean(R.string.key_show_ago, true)
        )
        showDelta = deltaOn
        showAge = ageOn
        ageText = GlassFaceLogic.ageText(age, ageWords)
        ageLevel = GlassFaceLogic.ageLevel(age)
        deltaText = GlassFaceLogic.deltaText(
            GlassFaceLogic.pickDelta(
                bg.delta, bg.avgDelta, bg.deltaDetailed, bg.avgDeltaDetailed,
                detailed = sp.getBoolean(R.string.key_show_detailed_delta, false), avg = sp.getBoolean(R.string.key_show_avg_delta, true)
            )
        )

        // hero: chip on the right, or removed with BG and meta centred (trend.hidden_pref; UX round 3: also for a
        // struck reading and "---", whose age is centred under the digits as on Halo)
        bgText = GlassFaceLogic.bgText(bg.sgvString, age, sentinel)
        showChip = GlassFaceLogic.chipShown(sp.getBoolean(R.string.key_show_direction, true), strike, bgText)
        hero = GlassFaceLogic.heroLayout(showChip, metaEmpty = !showDelta && !showAge && captionText.isEmpty())
        // UX round 3 (spec F1): low AND urgent low digits are red; a white 3,4 next to a white 6,4 hid the low in sunlight
        bgColour = when (GlassFaceLogic.digitTone(range, stale, bgAge == BgAge.VERY_STALE)) {
            GlassFaceLogic.DigitTone.VERY_STALE -> withAlpha(STALE_COL, 153) // Glass/Orbit UX round 6: a step quieter from 30'
            GlassFaceLogic.DigitTone.STALE -> STALE_DIGIT_COL // UX round 4: brighter than TXT2 on the grey card
            GlassFaceLogic.DigitTone.LOW   -> LOW_COL
            GlassFaceLogic.DigitTone.HIGH  -> HIGH_COL        // Glass/Orbit UX round 2: high is amber, not white
            GlassFaceLogic.DigitTone.IN    -> IN_DIGIT_COL
        }
        setText(tf600, GlassFaceLogic.BG_SIZE)
        bgSize = GlassFaceLogic.bgFitSize({ size -> text.textSize = size; bgWidth() }, hero.bgMaxWidth)
        text.textSize = bgSize
        bgHalfWidth = bgWidth() / 2f
        if (stale) trend = Trend.NONE

        // loop / IOB
        val loopAge = GlassFaceLogic.loopAgeMinutes(LoopRunTime.of(st), now)
        val run = GlassFaceLogic.loopRun(st.externalStatus, st.iobSum)
        val noProfile = GlassFaceLogic.noProfile(run, st.iobSum, firstStart)
        val (states, listTs) = loopStates()
        loopMode = GlassFaceLogic.loopMode(states, listTs, data.lastUpdateTimestamp)
        loopHealth = if (firstStart) LoopHealth.NEVER else GlassFaceLogic.loopHealth(loopAge, run, loopMode, realRun = st.lastLoopRunAt > 0L)
        when (loopHealth) {
            LoopHealth.OPEN  -> { loopText = getString(R.string.glass_loop_open); loopValueColour = TXT2 }
            LoopHealth.OFF   -> { loopText = getString(R.string.glass_loop_off_short); loopValueColour = WARN_COL }
            LoopHealth.NEVER -> { loopText = "--"; loopValueColour = TXT3 }
            else             -> {
                loopText = GlassFaceLogic.loopText(loopAge, old)
                loopValueColour = when (loopHealth) {
                    LoopHealth.WARN    -> WARN_COL
                    LoopHealth.NEUTRAL -> TXT2
                    LoopHealth.BAD     -> ALERT_COL // Glass/Orbit UX round 3: the glyph and its age say it once (no footer line)
                    else               -> TXT1
                }
            }
        }
        loopTextAmbient = loopText // no bare "!" (UX round 1): the 2 px ring of the dot and the footer carry a bad loop
        loopLabel = if (loopMode == LoopMode.LGS && loopHealth in setOf(LoopHealth.OK, LoopHealth.WARN, LoopHealth.BAD)) "LGS" else "LOOP"
        // UX round 4: the loop age always shows (stale included): it matters most exactly then
        val (iobV, iobU) = HaloFaceLogic.valueUnit(if (firstStart || noProfile) "" else st.iobSum, "U")
        iobText = iobV
        iobUnit = iobU
        iobColour = toneColour(if (iobText == "--") Tone.TXT3 else GlassFaceLogic.iobTone(st.iobSum))
        val (khV, khU) = HaloFaceLogic.valueUnit(if (firstStart || noProfile) "" else st.cob, "g")
        khText = khV
        khUnit = khU
        khColour = if (khText == "--" || GlassFaceLogic.parseNumber(khText)?.let { it <= 0.0 } == true) TXT3 else KH_COL
        showKh = sp.getBoolean(R.string.key_show_cob, true)
        pills = GlassFaceLogic.pills(sp.getBoolean(R.string.key_show_iob, true), pumpControl = IS_PUMPCONTROL)
        // loop off: "aus" with the pause dot says it; the loop glyph would read as "running"
        loopLabelShown = GlassFaceLogic.loopLabelAllowed(loopHealth) && loopHealth != LoopHealth.OFF

        // status slot / footer
        val warnings = GlassFaceLogic.warnings(
            GlassFaceLogic.SlotInput(
                firstStart = firstStart, link = link, bgAge = bgAge, range = range, noProfile = noProfile, loop = loopHealth,
                reservoirString = st.reservoirString, reservoirLevel = st.reservoirLevel, phoneBattery = st.battery, phoneBatteryLevel = st.batteryLevel
            )
        )
        owner = warnings.firstOrNull()
        loopMuted = GlassFaceLogic.loopGlyphMuted(owner)
        if (loopMuted) loopValueColour = TXT2
        val footerInput = GlassFaceLogic.FooterInput(
            warnings = GlassFaceLogic.footerWarnings(warnings, ambient = false),
            dataAgeMinutes = GlassFaceLogic.ageMinutes(data.lastUpdateTimestamp, now),
            bgAgeMinutes = age,
            loopAgeMinutes = loopAge,
            tempTarget = st.tempTarget, tempTargetLevel = st.tempTargetLevel,
            cob = if (noProfile) "" else st.cob,
            currentBasal = if (noProfile) "" else st.currentBasal,
            bgi = st.bgi,
            reservoirString = st.reservoirString, reservoirLevel = st.reservoirLevel,
            phoneBattery = st.battery, phoneBatteryLevel = st.batteryLevel,
            showTt = sp.getBoolean(R.string.key_show_temp_target, true),
            showCob = false, // UX round 2: KH has its own pill
            showBasal = GlassFaceLogic.basalInFooter(sp.getBoolean(R.string.key_show_temp_basal, true), if (noProfile) "" else st.currentBasal),
            showBgi = sp.getBoolean(R.string.key_show_bgi, false),
            // Glass/Orbit UX round 3 (five tiers, as Halo): a healthy reservoir or phone battery is no longer an always-on
            // footer run ("Tel 78 %" took the basal's place); low and critical levels still come as W7-W9 warnings
            showReservoir = false,
            showPhoneBattery = false
        )
        footer = if (firstStart && warnings.isEmpty()) emptyList() else layoutFooter(footerInput, footerX)
        footerSize = lastFooterSize
        footerAmbient = layoutFooter(footerInput.copy(ambient = true, warnings = GlassFaceLogic.footerWarnings(warnings, ambient = true)), footerAmbientX)
        footerAmbientSize = lastFooterSize

        // Glass/Orbit UX round 2 (as Halo): a W2/W3 owner is said ONCE, in the card's META row under the struck digits
        // ("Keine Daten · 17′"); the footer keeps its normal line and the loop glyph greys
        metaIsWarning = false
        GlassFaceLogic.metaWarning(owner)?.let { w ->
            GlassFaceLogic.warningText(w, footerInput.copy(warnings = warnings), slotWords)?.let { run ->
                setText(tf500, META_SIZE)
                val budget = (HERO_X1 - HERO_X0 - 2f * HERO_PAD) * META_SIZE / META_MIN_SIZE
                ageText = GlassFaceLogic.ellipsizeKeepTail(run.text, budget) { text.measureText(it) }
                ageLevel = Level.ALERT
                showDelta = false
                showAge = true
                metaIsWarning = true
                hero = GlassFaceLogic.heroLayout(showChip, metaEmpty = false)
            }
        }
        hero = GlassFaceLogic.heroGroup(hero, showChip, 2f * bgHalfWidth)
        layoutMeta()

        // sparkline (the band lines are placed in buildSparkline, on the adaptive scale)
        lowMgdl = GlassFaceLogic.lowLine(bg.low)
        highMgdl = GlassFaceLogic.highLine(bg.low, bg.high)
        predictionsOn = GlassFaceLogic.showPredictions(
            sp.getBoolean(R.string.key_prediction_lines, true), stale, loopMode, client = IS_CLIENT, pumpControl = IS_PUMPCONTROL
        )

        // ball (before the sparkline: the plot starts right of a drawn ball)
        val amount = bolusAmount()
        updateBall()
        if (!amount.isNaN()) amountText = GlassFaceLogic.bolusAmountText(amount, locale)
        layoutPills() // after the ball: with the ball shown the row moves right of its hit area (UX round 4)
        buildSparkline(bg.timeStamp, bg.sgv)
    }

    private fun updateBall() {
        bolusCheck = guard.check(bolusInput())
        ball = GlassFaceLogic.ballState(bolusCheck, gesture.isArmed)
        if (ball != BallState.ARMED && gesture.isArmed) disarm()
    }

    /**
     * Delta · age under the digits at full card width (UX round 1: 18 -> 28 px, the second most important reading;
     * UX round 2: never below 24 px). The TIEF/HOCH pill follows the line (UX round 2: off the chip column, so the
     * digits get the width).
     */
    private fun layoutMeta() {
        setText(tf500, META_SIZE)
        metaDeltaW = if (showDelta) text.measureText(deltaText) else 0f
        metaSepW = if (showDelta && showAge) text.measureText(META_SEP) else 0f
        var total = metaDeltaW + metaSepW + if (showAge) text.measureText(ageText) else 0f
        // Glass/Orbit UX round 1: the delta line and the TIEF/HOCH pill are ONE group centred on the dial (with the
        // pill right-aligned the line jumped sideways exactly when the BG went low or very high)
        val capW = if (captionText.isNotEmpty()) captionPillWidth() else 0f
        val capGap = if (capW > 0f && total > 0f) CAPTION_GAP else 0f
        val avail = HERO_X1 - HERO_X0 - 2f * HERO_PAD - capW - capGap
        metaSize = maxOf(META_MIN_SIZE, GlassFaceLogic.fitSize(META_SIZE, total, avail))
        val k = metaSize / META_SIZE
        metaDeltaW *= k
        metaSepW *= k
        total *= k
        metaWidth = total
        metaX = hero.metaCentreX - (total + capGap + capW) / 2f
        captionX = metaX + total + capGap + capW / 2f
    }

    private var captionX = 0f

    private fun captionPillWidth(): Float {
        setText(tf700, CAPTION_SIZE)
        text.letterSpacing = 0.04f
        val w = text.measureText(captionText) + 2f * CAPTION_PAD
        text.letterSpacing = 0f
        return w
    }

    private var metaWidth = 0f

    /**
     * Pill row (UX round 2): loop (dot, age, loop glyph), IOB ("1,25 U") and KH ("12 g"), each sized to its content
     * and centred as a group; the unit replaces the old 15 px small-caps labels. Values 24 px (22 px physical
     * minimum on small screens), stepped down to 18 px only if the row would exceed [GlassFaceLogic.PILL_ROW_MAX_WIDTH].
     */
    private fun layoutPills() {
        pillUnitSize = minPx(PILL_UNIT_SIZE, PILL_UNIT_PX_MIN)
        val (centre, maxWidth) = GlassFaceLogic.pillRowFrame(ball != BallState.HIDDEN)
        var size = minPx(PILL_VALUE_SIZE, PILL_VALUE_PX_MIN)
        while (true) {
            measurePills(size)
            val total = pillW.sum() + PILL_GAP * (pillW.count { it > 0f } - 1).coerceAtLeast(0)
            if (total <= maxWidth || size <= PILL_VALUE_MIN) break
            size -= 1f
        }
        pillValueSize = size
        pillX = GlassFaceLogic.pillRow(pillW, PILL_GAP, centre)
    }

    private fun measurePills(valueSize: Float) {
        setText(tf600, valueSize)
        val loopValueW = text.measureText(loopText)
        val iobValueW = text.measureText(iobText)
        val khValueW = text.measureText(khText)
        setText(tf600, pillUnitSize)
        // Glass/Orbit UX round 2: "⟳ 2′" as on Halo: the state-tinted loop glyph first (it replaces the separate dot),
        // then the age; only LGS adds its word after the age
        val loopLabelW = if (loopLabel == "LGS" && loopLabelShown) text.measureText(loopLabel) else 0f
        pillW[0] = if (pills == Pills.IOB_ONLY) 0f
        else 2f * PILL_PAD + LOOP_GLYPH_W + (if (loopValueW > 0f) PILL_INNER_GAP + loopValueW else 0f) + (if (loopLabelW > 0f) PILL_INNER_GAP + loopLabelW else 0f)
        pillW[1] = if (pills == Pills.LOOP_ONLY) 0f
        else 2f * PILL_PAD + iobValueW + if (iobUnit.isNotEmpty()) UNIT_GAP + text.measureText(iobUnit) else 0f
        pillW[2] = if (!showKh) 0f
        else 2f * PILL_PAD + khValueW + if (khUnit.isNotEmpty()) UNIT_GAP + text.measureText(khUnit) else 0f
    }

    /** Text sizes never go below [physical] px on the real screen (round_384 and smaller scale the 450 grid down). */
    private fun minPx(size: Float, physical: Float): Float = max(size, physical / max(scale, 0.01f))

    private var lastFooterSize = GlassFaceLogic.FOOTER_SIZE

    /** Footer runs at their x positions: shrunk to fit 257 px, 16 px floor, then the owner text ellipsised (§4.8). */
    private fun layoutFooter(input: GlassFaceLogic.FooterInput, xs: FloatArray): List<GlassFaceLogic.Run> {
        setText(tf500, GlassFaceLogic.FOOTER_SIZE)
        val measure18: (String) -> Float = { text.measureText(it) }
        val fit = GlassFaceLogic.fitFooter(GlassFaceLogic.footerRuns(input, slotWords, FOOTER_SEP, measure18), FOOTER_SEP, measure18)
        val runs = fit.runs
        lastFooterSize = minPx(fit.size, MIN_TEXT_PX)
        text.textSize = lastFooterSize
        val width = GlassFaceLogic.lineWidth(runs, FOOTER_SEP) { text.measureText(it) }
        val sep = text.measureText(FOOTER_SEP)
        var x = CX - width / 2f
        runs.forEachIndexed { i, run ->
            if (i >= xs.size) return@forEachIndexed
            if (i > 0) x += sep
            xs[i] = x
            x += text.measureText(run.text)
        }
        return runs
    }

    private fun buildSparkline(bgTs: Long, bgMgdl: Double) {
        val now = System.currentTimeMillis()
        val anchor = GlassFaceLogic.sparkAnchor(bgTs, now)
        // frame: right of a drawn ball; ends at now unless a forecast is drawn
        val preds = data.treatmentData.predictions
        sparkX0 = if (ball != BallState.HIDDEN) GlassFaceLogic.SPARK_X0_BALL else GlassFaceLogic.SPARK_X0
        sparkForecast = predictionsOn && preds.isNotEmpty()
        nowX = GlassFaceLogic.nowX(sparkX0, sparkForecast)
        sparkEndX = if (sparkForecast) GlassFaceLogic.SPARK_X1 else nowX
        fun sx(slot: Float) = GlassFaceLogic.sparkX(slot, sparkX0, sparkForecast)
        // linear range over what is visible (history, current reading, forecast) and the target band. Glass polish round 6:
        // the current reading, the last 30 min and the forecast are never clamped (keepMax); only older history may be
        var vMin = Double.NaN
        var vMax = Double.NaN
        var keepMax = Double.NaN
        var nowV = Double.NaN
        var nowTs = Long.MIN_VALUE
        val keepFrom = anchor - GlassFaceLogic.SPARK_KEEP_MS
        fun see(v: Double, keep: Boolean) {
            if (v <= 0.0) return
            vMin = if (vMin.isNaN()) v else min(vMin, v)
            vMax = if (vMax.isNaN()) v else max(vMax, v)
            if (keep) keepMax = if (keepMax.isNaN()) v else max(keepMax, v)
        }
        for (e in data.graphData.entries) if (e.timeStamp <= anchor && GlassFaceLogic.slotOf(e.timeStamp, anchor) != null) {
            see(e.sgv, e.timeStamp >= keepFrom)
            if (e.sgv > 0.0 && e.timeStamp > nowTs) { nowTs = e.timeStamp; nowV = e.sgv }
        }
        if (bgTs > 0L && bgMgdl > 0.0 && GlassFaceLogic.slotOf(bgTs, anchor) != null) {
            see(bgMgdl, true)
            if (bgTs >= nowTs) { nowTs = bgTs; nowV = bgMgdl }
        }
        if (sparkForecast) for (p in preds) if (GlassFaceLogic.slotOf(p.timeStamp, anchor) != null) see(p.sgv, true)
        val (lo, hi) = GlassFaceLogic.sparkRange(vMin, vMax, lowMgdl, highMgdl, nowV, keepMax)
        fun sy(v: Double) = GlassFaceLogic.sparkY(v, lo, hi)
        // band edges on the same scale (inside the plot by construction; the clamp and the check stay as a guard)
        val lowRaw = GlassFaceLogic.sparkYRaw(lowMgdl, lo, hi)
        val highRaw = GlassFaceLogic.sparkYRaw(highMgdl, lo, hi)
        lowEdge = GlassFaceLogic.bandEdgeVisible(lowRaw)
        highEdge = GlassFaceLogic.bandEdgeVisible(highRaw)
        lowY = lowRaw.coerceIn(GlassFaceLogic.SPARK_Y0, GlassFaceLogic.SPARK_Y1)
        highY = highRaw.coerceIn(GlassFaceLogic.SPARK_Y0, GlassFaceLogic.SPARK_Y1)
        lowZone = lowEdge && !vMin.isNaN() && vMin < lowMgdl
        history.reset()
        hasCurrent = false
        offCount = 0
        // graph.gaps: a gap longer than GAP_MS breaks the line, never interpolated; each run is drawn as a smooth curve
        var n = 0
        fun flush() {
            if (n == 0) return
            val k = GlassFaceLogic.offScaleRuns(segX, segV, n, hi, offTmp)
            for (i in 0 until k) if (3 * offCount + 2 < offRuns.size) {
                offRuns[3 * offCount] = offTmp[3 * i]; offRuns[3 * offCount + 1] = offTmp[3 * i + 1]; offRuns[3 * offCount + 2] = offTmp[3 * i + 2]
                offCount++
            }
            history.moveTo(segX[0], segY[0])
            if (n == 1) history.lineTo(segX[0] + 0.01f, segY[0])
            else {
                // Glass polish round 1: a quadratic curve through the midpoints (the 5' steps read as a calm curve, not
                // a jagged polyline); it passes through the first and the last (current) point exactly
                for (i in 1 until n - 1) history.quadTo(segX[i], segY[i], (segX[i] + segX[i + 1]) / 2f, (segY[i] + segY[i + 1]) / 2f)
                history.lineTo(segX[n - 1], segY[n - 1])
            }
            n = 0
        }
        fun add(x: Float, y: Float, v: Double) {
            if (n == segX.size) {
                // a run longer than the buffer (1' data): flush and carry the last point over, so the curve stays continuous
                val lx = segX[n - 1]; val ly = segY[n - 1]; val lv = segV[n - 1]
                flush()
                segX[0] = lx; segY[0] = ly; segV[0] = lv; n = 1
            }
            segX[n] = x; segY[n] = y; segV[n] = v; n++
        }
        var lastTs = Long.MIN_VALUE
        var started = false
        var prevTs = Long.MIN_VALUE
        for (e in data.graphData.entries.sortedBy { it.timeStamp }) {
            if (e.sgv <= 0.0 || e.timeStamp > anchor) continue
            val slot = GlassFaceLogic.slotOf(e.timeStamp, anchor) ?: continue
            val x = sx(slot)
            val y = sy(e.sgv)
            if (started && e.timeStamp - prevTs > GlassFaceLogic.GAP_MS) flush()
            add(x, y, e.sgv)
            started = true
            prevTs = e.timeStamp
            curX = x; curY = y; lastTs = e.timeStamp
        }
        if (bgTs > 0L && bgMgdl > 0.0 && bgTs > lastTs) {
            GlassFaceLogic.slotOf(bgTs, anchor)?.let { slot ->
                val x = sx(slot)
                val y = sy(bgMgdl)
                if (started && bgTs - prevTs > GlassFaceLogic.GAP_MS) flush()
                add(x, y, bgMgdl)
                started = true
                curX = x; curY = y
            }
        }
        flush()
        hasHistory = started
        hasCurrent = started

        // prediction: the main (longest) series, sampled at the SPARK_FORECAST future slots
        predCount = 0
        predDotCount = 0
        if (!sparkForecast) return
        val main = preds.groupBy { it.color }.maxByOrNull { it.value.size }?.value ?: return
        if (hasCurrent) {
            predXY[0] = curX; predXY[1] = curY; predCount = 1
        }
        for (k in 1..GlassFaceLogic.SPARK_FORECAST) {
            val t = anchor + k * GlassFaceLogic.SLOT_MS
            val p = main.minByOrNull { kotlin.math.abs(it.timeStamp - t) } ?: continue
            if (kotlin.math.abs(p.timeStamp - t) > GlassFaceLogic.SLOT_MS / 2 || p.sgv <= 0.0) continue
            predXY[2 * predCount] = sx((GlassFaceLogic.SPARK_HISTORY - 1 + k).toFloat())
            predXY[2 * predCount + 1] = sy(p.sgv)
            predCount++
        }
        // Glass polish round 1: one faint dotted curve on from the now dot (12 loose purple dots read as noise);
        // round 2: at the history's time scale, neutral grey; round 3: 45 min, larger dots placed along the path from
        // [PRED_START] px after the now dot (the dash effect started under the dot and the specks read as an ellipsis)
        predDotCount = if (predCount > 1)
            GlassFaceLogic.forecastDots(predXY, predCount, if (hasCurrent) PRED_START else 0f, PRED_DOT_STEP, predDotXY)
        else 0
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = canvas.width
        val h = canvas.height
        val size = min(w, h)
        if (size != pixelSize) {
            recycleBallBitmaps()
            pixelSize = size
        }
        scale = size / 450f
        if (simpleUi.isEnabled(currentWatchMode)) { // sys.simplified: the shared SimpleUi replaces Glass
            simpleUi.onDraw(canvas, data.bgData)
            return
        }
        val now = System.currentTimeMillis()
        if (dirty || now / GlassFaceLogic.MINUTE_MS != stateMinute) updateState(now)
        else if (ball == BallState.SENT && !guard.isPending) updateBall()
        ensureBackgrounds()

        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate((w - size) / 2f, (h - size) / 2f)
        try {
            if (isAmbient) {
                canvas.scale(scale, scale)
                canvas.translate(burnX, burnY)
                drawAmbient(canvas)
            } else {
                val bmp = backgrounds[range.ordinal]
                if (bmp != null) canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
                canvas.scale(scale, scale)
                if (bmp == null) canvas.drawRect(0f, 0f, 450f, 450f, fallbackPaint) // until the bake lands (first frames only)
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

    /** Width of [bgText] in [text] with the tight decimal separator (UX round 3, [GlassFaceLogic.splitDecimal]). */
    private fun bgWidth(): Float {
        val parts = GlassFaceLogic.splitDecimal(bgText) ?: return text.measureText(bgText)
        return text.measureText(parts.first) + text.measureText(parts.second) + text.measureText(parts.third) -
            2f * GlassFaceLogic.SEPARATOR_KERN * text.textSize
    }

    /** [bgText] centred on [cx] with the tight separator; [text] is set up (any style), its alignment is changed. */
    private fun drawBg(canvas: Canvas, cx: Float, cy: Float) {
        text.textAlign = Paint.Align.LEFT
        val y = baseline(cy)
        var x = cx - bgWidth() / 2f
        val parts = GlassFaceLogic.splitDecimal(bgText)
        if (parts == null) {
            canvas.drawText(bgText, x, y, text)
            return
        }
        val kern = GlassFaceLogic.SEPARATOR_KERN * text.textSize
        canvas.drawText(parts.first, x, y, text)
        x += text.measureText(parts.first) - kern
        canvas.drawText(parts.second, x, y, text)
        x += text.measureText(parts.second) - kern
        canvas.drawText(parts.third, x, y, text)
    }

    /** Time (tabular digits, own paint), 12 h suffix, and the date unless key_show_date is off (time then at y=88). */
    private fun drawTime(canvas: Canvas, tf: Typeface, colour: Int, suffixColour: Int, dateColour: Int, aa: Boolean) {
        timePaint.typeface = tf
        timePaint.textSize = TIME_SIZE
        timePaint.isAntiAlias = aa
        timePaint.color = colour
        val fm = timePaint.fontMetrics
        val y = timeY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(timeText, timeX, y, timePaint)
        amPmText?.let {
            setText(tf500, AM_PM_SIZE, aa)
            text.color = suffixColour
            canvas.drawText(it, amPmX, y, text)
        }
        if (showDate) drawCentred(canvas, dateText, CX, DATE_Y, tf500, minPx(DATE_SIZE, MIN_TEXT_PX), dateColour, aa)
    }

    private fun drawInteractive(canvas: Canvas, now: Long) {
        drawTime(canvas, tf400, TXT1, TXT2, TXT2, aa = true)

        // hero BG
        setText(tf600, bgSize, align = Paint.Align.CENTER)
        text.color = bgColour
        // UX round 4: no glow behind red digits (it lowered their contrast on the low card)
        // Glass/Orbit UX round 2: the glow 30 % tighter and fainter (10 px / 0x70 softened the digit edges at arm's length)
        if (!stale && !GlassFaceLogic.isLow(range)) text.setShadowLayer(7f, 0f, 0f, withAlpha(rangeColour, 0x4E))
        drawBg(canvas, hero.bgX, hero.bgY)
        if (strike && bgText != "---") {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = 3f; line.color = bgColour
            canvas.drawLine(hero.bgX - bgHalfWidth - 4f, hero.bgY + 2f, hero.bgX + bgHalfWidth + 4f, hero.bgY + 2f, line)
        }
        drawCaption(canvas, ambient = false, lowBit = false)

        // trend chip (a struck reading has none: its age sits under the digits, in alert)
        if (showChip) {
            // Glass/Orbit UX round 2: a filled disc and a 4 px arrow with the weight of the bold digits.
            // Glass/Orbit UX round 3: the 18 % disc read muddy; a fainter 12 % disc with a crisp 1 px rim at 30 % of the
            // state colour (as the ambient chip, which looked cleaner)
            // Glass/Orbit UX round 6: a dark disc (black under a 16 % tint) with a 1.5 px rim at 45 %, so the arrow stands
            // out (the tinted 12 % disc made a dim orange-on-orange chip); the arrow is lifted 25 % toward white
            fill.reset(); fill.isAntiAlias = true; fill.color = CHIP_BASE
            canvas.drawCircle(hero.chipX, hero.chipY, CHIP_R, fill)
            fill.color = withAlpha(rangeColour, 40)
            canvas.drawCircle(hero.chipX, hero.chipY, CHIP_R, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1.5f
            stroke.color = withAlpha(rangeColour, 115)
            canvas.drawCircle(hero.chipX, hero.chipY, CHIP_R - 0.75f, stroke)
            drawArrow(canvas, lift(rangeColour, 0.25f), aa = true, maxWidth = 4.6f)
        }

        // meta line + phone-off glyph (W10); UX round 3: a normal age in the delta colour (TXT2 was 4.2:1 on the glass)
        // UX round 4: the "·" in TXT2 (TXT3 vanished in sunlight and "-0,6 2'" read as one number)
        drawMeta(canvas, DELTA_COL, TXT2, when (ageLevel) {
            Level.NORMAL -> DELTA_COL
            Level.WARN   -> WARN_COL
            Level.ALERT  -> ALERT_COL
        }, aa = true)
        if (link == PhoneLink.LOST_FRESH) drawPhoneOff(canvas, min(metaX + metaWidth + 20f, HERO_X1 - 20f), hero.metaY, TXT2)

        // sparkline (Glass polish round 1/2): the target band as a faint green tint with a readable low edge and a
        // fainter high edge (the reference; ONE look in every state, a band that turned grey out of range read as an
        // empty rail), a smooth trace coloured by value zone that fades in toward now, a clear now dot and a faint
        // dotted forecast. No now line: the dot marks now.
        // no trace (no data yet): no reference band either, an empty band read as a progress bar
        // Glass polish round 3: on a stale reading the reference is grey too (a green band hinted "in range")
        val bandCol = if (stale) STALE_COL else IN_COL
        if (hasHistory && lowY - highY > 0.5f) {
            fill.reset(); fill.isAntiAlias = true
            fill.color = Color.WHITE
            fill.shader = bandShader(withAlpha(bandCol, BAND_ALPHA))
            rect.set(sparkX0, highY, sparkEndX, lowY)
            canvas.drawRoundRect(rect, BAND_CORNER, BAND_CORNER, fill)
            fill.shader = null
        }
        // Glass polish round 4: at a hypo the zone under the low edge is a faint red tint (the depth reads as "in the
        // red zone", in words the hero and the state line still say it); never on a stale reading
        if (hasHistory && lowZone && !stale) {
            fill.reset(); fill.isAntiAlias = true
            fill.color = Color.WHITE
            fill.shader = bandFade(withAlpha(LOW_COL, LOW_ZONE_ALPHA))
            rect.set(sparkX0, lowY, sparkEndX, GlassFaceLogic.SPARK_Y1)
            canvas.drawRoundRect(rect, BAND_CORNER, BAND_CORNER, fill)
            fill.shader = null
        }
        if (hasHistory) drawSparkGuides(
            canvas, withAlpha(bandCol, if (stale) BAND_LOW_EDGE_ALPHA_STALE else BAND_LOW_EDGE_ALPHA),
            withAlpha(bandCol, if (stale) BAND_HIGH_EDGE_ALPHA_STALE else BAND_HIGH_EDGE_ALPHA), aa = true, fade = true, gap = TRACE_W + 2f * TRACE_GAP
        )
        if (predDotCount > 0) {
            fill.reset(); fill.isAntiAlias = true
            fill.color = withAlpha(PRED_COL, PRED_ALPHA)
            for (i in 0 until predDotCount) canvas.drawCircle(predDotXY[2 * i], predDotXY[2 * i + 1], PRED_DOT / 2f, fill)
        }
        if (hasHistory) {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = TRACE_W
            line.shader = historyShader(range)
            if (!stale) line.setShadowLayer(4f, 0f, 0f, withAlpha(rangeColour, 110))
            drawTrace(canvas, line)
            line.shader = null
            line.clearShadowLayer()
            // Glass polish round 6: clamped older history faded in the colour of its real value, with an up-chevron
            drawOffScale(canvas, aa = true, width = TRACE_W) { peak ->
                withAlpha(if (stale) STALE_COL else if (peak > highMgdl) HIGH_COL else IN_COL, OFF_SCALE_ALPHA)
            }
        }
        if (hasCurrent) {
            fill.reset(); fill.isAntiAlias = true
            if (stale) {
                fill.color = TXT3
                canvas.drawCircle(curX, curY, 4.5f, fill)
            } else {
                // a dark ring parts the dot from the trace, then the range colour with a soft glow and a white core
                fill.color = Color.BLACK
                canvas.drawCircle(curX, curY, NOW_R + 2f, fill)
                fill.color = rangeColour
                // Glass polish round 3: a tighter glow under the low edge (the dot sits low there, near the pill row)
                fill.setShadowLayer(if (lowEdge && curY > lowY) 5f else 7f, 0f, 0f, withAlpha(rangeColour, 200))
                canvas.drawCircle(curX, curY, NOW_R, fill)
                fill.clearShadowLayer()
                fill.color = Color.WHITE
                canvas.drawCircle(curX, curY, NOW_R - 2.5f, fill)
            }
        }

        // pills (UX round 4: borderless, separated by dots like the footer: one rhythm for the lower stack)
        drawPillSeparators(canvas, TXT2, aa = true) // rework: TXT3 dots were too faint to separate the values
        drawPillContents(canvas, ambient = false, lowBit = false)

        // footer = status slot
        drawFooter(canvas, footer, footerX, footerSize, ambient = false, aa = true)

        // bolus ball
        if (ball != BallState.HIDDEN) drawBall(canvas, now)
    }

    /**
     * Caption so colour is never the only carrier, as a pill inside the card (UX round 2: 24 px bold, on the delta line
     * after the delta, clear of the comma and of the chip column). "TIEF" (low:
     * tinted pill), "TIEF" in a filled red pill (urgent low; ambient: outlined, burn-in safe), "HOCH" (very high).
     */
    private fun drawCaption(canvas: Canvas, ambient: Boolean, lowBit: Boolean) {
        if (caption == FaceState.Caption.NONE) return
        val aa = !lowBit
        val w = captionPillWidth()
        val x = captionX
        val y = hero.metaY
        val hue = if (caption == FaceState.Caption.HIGH_WORD) HIGH_COL else LOW_COL
        val wordColour = when {
            lowBit                                    -> Color.WHITE
            ambient                                   -> A_TXT1
            caption == FaceState.Caption.LOW_BADGE    -> BADGE_TEXT_COL // UX round 3: near-black on red (white was 3.0:1)
            caption == FaceState.Caption.HIGH_WORD    -> HIGH_COL
            else                                      -> LOW_TEXT_COL
        }
        rect.set(x - w / 2f, y - CAPTION_H / 2f, x + w / 2f, y + CAPTION_H / 2f)
        if (ambient) {
            if (caption == FaceState.Caption.LOW_BADGE) {
                // urgent low by SHAPE (UX round 3): a double outline, 2 px outside and 1 px inside, burn-in safe.
                // Glass polish round 6: at the former 30 px height, the inner outline needs the room around the word
                val hh = CAPTION_H_AMBIENT_BADGE / 2f
                rect.set(x - w / 2f, y - hh, x + w / 2f, y + hh)
                stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = wordColour
                canvas.drawRoundRect(rect, hh, hh, stroke)
                stroke.strokeWidth = 1f
                rect.inset(3.5f, 3.5f)
                canvas.drawRoundRect(rect, hh - 3.5f, hh - 3.5f, stroke)
            }
        } else if (caption == FaceState.Caption.LOW_BADGE) {
            fill.reset(); fill.isAntiAlias = true; fill.color = LOW_COL
            canvas.drawRoundRect(rect, CAPTION_H / 2f, CAPTION_H / 2f, fill)
        } else {
            // UX round 3: a neutral dark pill with a hue outline and hue text (hue on hue-tinted glass was 3.5:1)
            fill.reset(); fill.isAntiAlias = true; fill.color = CAPTION_FILL
            canvas.drawRoundRect(rect, CAPTION_H / 2f, CAPTION_H / 2f, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1.5f; stroke.color = withAlpha(hue, 220)
            canvas.drawRoundRect(rect, CAPTION_H / 2f, CAPTION_H / 2f, stroke)
        }
        setText(tf700, CAPTION_SIZE, aa, Paint.Align.CENTER)
        text.letterSpacing = 0.04f
        text.color = wordColour
        canvas.drawText(captionText, x, baseline(y), text)
        text.letterSpacing = 0f
    }

    /** Face-drawn trend glyph (no font arrows): a shaft with one (normal) or two (fast) chevrons. */
    private fun drawArrow(canvas: Canvas, colour: Int, aa: Boolean, maxWidth: Float) {
        if (trend == Trend.NONE) return
        val triple = trend == Trend.UP3 || trend == Trend.DOWN3
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND
        line.strokeWidth = min(if (triple) 4.6f else 4.2f, maxWidth)
        line.color = colour
        val cy = hero.chipY
        val cx = hero.chipX
        canvas.save()
        canvas.rotate(-trend.angle, cx, cy)
        if (trend.double) {
            // Glass/Orbit UX round 6 (safety): two parallel arrows side by side, as Orbit and Halo; stacked chevrons 9 px
            // apart merged into one arrow with a stray bar
            line.strokeWidth = min(line.strokeWidth, 3.6f)
            for (off in DOUBLE_OFFSETS) {
                canvas.drawLine(cx - ARROW_L + 2f, cy + off, cx + ARROW_L, cy + off, line)
                drawChevron(canvas, cx + ARROW_L, cy + off, DOUBLE_HEAD_DX, DOUBLE_HEAD_DY)
            }
        } else {
            canvas.drawLine(cx - ARROW_L, cy, cx + ARROW_L, cy, line)
            drawChevron(canvas, cx + ARROW_L, cy, HEAD_DX, HEAD_DY)
        }
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas, tipX: Float, cy: Float, hdx: Float, hdy: Float) {
        canvas.drawLine(tipX, cy, tipX - hdx, cy - hdy, line)
        canvas.drawLine(tipX, cy, tipX - hdx, cy + hdy, line)
    }

    /** [colour] moved [f] of the way toward white (alpha kept). */
    private fun lift(colour: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return (colour and 0xFF000000.toInt()) or (ch(Color.red(colour)) shl 16) or (ch(Color.green(colour)) shl 8) or ch(Color.blue(colour))
    }

    private fun drawPhoneOff(canvas: Canvas, cx: Float, cy: Float, colour: Int) {
        stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1.8f; stroke.color = colour
        rect.set(cx - 5.5f, cy - 9.5f, cx + 5.5f, cy + 9.5f)
        canvas.drawRoundRect(rect, 2f, 2f, stroke)
        canvas.drawLine(cx - 9f, cy + 10.5f, cx + 9f, cy - 10.5f, stroke)
    }

    private fun drawMeta(canvas: Canvas, deltaColour: Int, sepColour: Int, ageColour: Int, aa: Boolean) {
        setText(tf500, metaSize, aa)
        val y = baseline(hero.metaY)
        var x = metaX
        if (showDelta) {
            text.color = deltaColour
            canvas.drawText(deltaText, x, y, text)
            x += metaDeltaW
            if (showAge) {
                text.color = sepColour
                canvas.drawText(META_SEP, x, y, text)
                x += metaSepW
            }
        }
        if (showAge) {
            text.color = ageColour
            canvas.drawText(ageText, x, y, text)
        }
    }

    /** Horizontal fade of the target band (Glass/Orbit UX round 4): [colour] fading in and out over [BAND_FADE] px. */
    private val bandFades = HashMap<String, Shader>()

    private fun bandFade(colour: Int): Shader = bandFades.getOrPut("$sparkX0/$sparkEndX/$colour") {
        if (bandFades.size > 8) bandFades.clear()
        val w = (sparkEndX - sparkX0).coerceAtLeast(2f * BAND_FADE + 1f)
        val f = BAND_FADE / w
        val clear = colour and 0x00FFFFFF
        LinearGradient(
            sparkX0, 0f, sparkX0 + w, 0f, intArrayOf(clear, colour, colour, clear), floatArrayOf(0f, f, 1f - f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Band tint (Glass polish round 1): faded in and out horizontally ([bandFade]) and, where the band runs past the
     * plot, also vertically over [BAND_FADE_V] px, so a band wider than the plot is a soft glow, not a slab.
     */
    private fun bandShader(colour: Int): Shader = bandFades.getOrPut("v/$sparkX0/$sparkEndX/$highY/$lowY/$highEdge/$lowEdge/$colour") {
        if (bandFades.size > 8) bandFades.clear()
        val clear = colour and 0x00FFFFFF
        val h = (lowY - highY).coerceAtLeast(1f)
        val ft = if (highEdge) 0f else (BAND_FADE_V / h).coerceAtMost(0.45f)
        val fb = if (lowEdge) 0f else (BAND_FADE_V / h).coerceAtMost(0.45f)
        val vertical = LinearGradient(
            0f, highY, 0f, lowY, intArrayOf(if (highEdge) colour else clear, colour, colour, if (lowEdge) colour else clear),
            floatArrayOf(0f, ft, 1f - fb, 1f), Shader.TileMode.CLAMP
        )
        ComposeShader(vertical, bandFade(Color.WHITE), PorterDuff.Mode.DST_IN)
    }

    /** Glass polish round 6: the history trace less the clamped older runs ([offRuns]), which [drawOffScale] draws. */
    private fun drawTrace(canvas: Canvas, paint: Paint) {
        if (offCount == 0) {
            canvas.drawPath(history, paint)
            return
        }
        val save = canvas.save()
        for (i in 0 until offCount)
            canvas.clipOutRect(offRuns[3 * i], GlassFaceLogic.SPARK_Y0 - OFF_CLIP, offRuns[3 * i + 1], GlassFaceLogic.SPARK_Y1 + OFF_CLIP)
        canvas.drawPath(history, paint)
        canvas.restoreToCount(save)
    }

    /**
     * Glass polish round 6 (strict panel): older history above the plot is clamped to its top edge; a solid plateau
     * there read as data ("flat at 7 mmol/l"). It is drawn at [colourOf] (the colour of its REAL peak, faded) and
     * marked with a small up-chevron above the plateau: "off the scale, higher than shown".
     */
    private fun drawOffScale(canvas: Canvas, aa: Boolean, width: Float, trace: Boolean = true, colourOf: (Float) -> Int) {
        val top = GlassFaceLogic.SPARK_Y0 + GlassFaceLogic.SPARK_INSET
        for (i in 0 until offCount) {
            val x0 = offRuns[3 * i]
            val x1 = offRuns[3 * i + 1]
            val colour = colourOf(offRuns[3 * i + 2])
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = width
            line.color = colour
            if (trace) {
                // dotted: a faint solid plateau still read as a flat stretch of data
                line.pathEffect = offDots
                val save = canvas.save()
                canvas.clipRect(x0 + OFF_DOT_INSET, GlassFaceLogic.SPARK_Y0 - OFF_CLIP, x1 - OFF_DOT_INSET, GlassFaceLogic.SPARK_Y1 + OFF_CLIP)
                canvas.drawPath(history, line)
                canvas.restoreToCount(save)
                line.pathEffect = null
            }
            if (x1 - x0 < OFF_CHEVRON_MIN_RUN) continue
            // the chevron a step stronger than the faded line, so it reads as a mark, centred on the clamped run
            line.color = if (Color.alpha(colour) < 255) withAlpha(colour, OFF_CHEVRON_ALPHA) else colour
            line.strokeWidth = 1.5f
            val cx = (x0 + x1) / 2f
            val y = top - OFF_CHEVRON_GAP
            chevron.rewind()
            chevron.moveTo(cx - OFF_CHEVRON_W, y)
            chevron.lineTo(cx, y - OFF_CHEVRON_H)
            chevron.lineTo(cx + OFF_CHEVRON_W, y)
            canvas.drawPath(chevron, line)
        }
    }

    /**
     * Target band edges (1 px, only those inside the plot); [fade] fades them in and out at the plot's ends. No now line.
     * Glass polish round 2: the low edge (the safety-relevant one) in [lowColour], the high edge in [highColour].
     * Glass polish round 3: the edges break around the trace ([gap] px wide, erased in a layer bounded to the plot), so
     * a curve running near an edge reads as its own line, not as a thick border of the band; the edges are dashed
     * ([edgeDash]) for the same reason: a solid trace beside a dashed reference reads as two things.
     */
    private fun drawSparkGuides(canvas: Canvas, lowColour: Int, highColour: Int, aa: Boolean, fade: Boolean, gap: Float) {
        if (!lowEdge && !highEdge) return
        val layer = canvas.saveLayer(sparkX0 - 2f, GlassFaceLogic.SPARK_Y0, sparkEndX + 2f, GlassFaceLogic.SPARK_Y1, null)
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 1f
        for ((show, y, colour) in listOf(Triple(lowEdge, lowY, lowColour), Triple(highEdge, highY, highColour))) {
            if (!show || Color.alpha(colour) == 0) continue
            line.color = if (fade) Color.WHITE else colour
            line.shader = if (fade) bandFade(colour) else null
            line.pathEffect = edgeDash
            canvas.drawLine(sparkX0, y, sparkEndX, y, line)
        }
        line.shader = null
        line.pathEffect = null
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = gap
        line.color = Color.BLACK; line.xfermode = eraseMode
        canvas.drawPath(history, line)
        line.xfermode = null
        canvas.restoreToCount(layer)
    }

    /**
     * Loop glyph (states.md loop.*; Glass/Orbit UX round 2 replaces the dot): the family's loop arrow tinted by state:
     * OK / open green, WARN amber, BAD red, never / no profile / W2-W3 owner grey; loop off a pause sign in amber.
     * Ambient: no hue, 2 px a2, or a 2.6 px a1 glyph for WARN/BAD/OFF; low-bit 2 px white without AA.
     */
    private fun drawLoopState(canvas: Canvas, cx: Float, cy: Float, ambient: Boolean, lowBit: Boolean) {
        val strong = loopHealth == LoopHealth.WARN || loopHealth == LoopHealth.BAD || loopHealth == LoopHealth.OFF
        val colour = when {
            lowBit  -> Color.WHITE
            ambient -> if (strong) A_TXT1 else A_TXT2
            loopMuted -> STALE_COL
            else    -> when (loopHealth) {
                LoopHealth.OK, LoopHealth.OPEN       -> LOOP_OK
                LoopHealth.WARN, LoopHealth.OFF      -> WARN_COL
                LoopHealth.BAD                       -> ALERT_COL
                LoopHealth.NEVER, LoopHealth.NEUTRAL -> STALE_COL
            }
        }
        val width = if (ambient && !lowBit && strong) 2.6f else LOOP_GLYPH_STROKE
        if (loopHealth == LoopHealth.OFF) {
            fill.reset(); fill.isAntiAlias = !lowBit; fill.color = colour
            for (ox in floatArrayOf(-4f, 4f)) {
                rect.set(cx + ox - 2f, cy - 7.5f, cx + ox + 2f, cy + 7.5f)
                canvas.drawRoundRect(rect, 1f, 1f, fill)
            }
        } else drawLoopGlyph(canvas, cx, cy, colour, !lowBit, width)
    }

    /** A small dot centred in each gap between two drawn pills (the pills have no chrome since UX round 4). */
    private fun drawPillSeparators(canvas: Canvas, colour: Int, aa: Boolean) {
        fill.reset(); fill.isAntiAlias = aa; fill.color = colour
        var prevEnd = Float.NaN
        for (i in 0..2) {
            if (pillW[i] <= 0f) continue
            if (!prevEnd.isNaN()) canvas.drawCircle((prevEnd + pillX[i]) / 2f, PILL_Y, 2f, fill)
            prevEnd = pillX[i] + pillW[i]
        }
    }

    /**
     * Pill contents: loop dot + age + loop glyph ("LGS" in LGS mode, nothing for an open loop), "1,25 U", "12 g".
     * Units stay in every mode, low-bit included (white, no AA), so a bare "1,25" never sits next to the BG.
     */
    private fun drawPillContents(canvas: Canvas, ambient: Boolean, lowBit: Boolean) {
        val aa = !lowBit
        val unitColour = if (lowBit) Color.WHITE else if (ambient) A_TXT2 else LABEL_COL
        val y = PILL_Y
        if (pillW[0] > 0f) {
            drawLoopState(canvas, pillX[0] + PILL_PAD + LOOP_GLYPH_W / 2f, y, ambient, lowBit)
            var x = pillX[0] + PILL_PAD + LOOP_GLYPH_W + PILL_INNER_GAP
            setText(tf600, pillValueSize, aa)
            text.color = if (lowBit) Color.WHITE else if (ambient) A_TXT1 else loopValueColour
            val v = if (ambient) loopTextAmbient else loopText
            canvas.drawText(v, x, baseline(y), text)
            x += text.measureText(v) + PILL_INNER_GAP
            if (loopLabelShown && loopLabel == "LGS") {
                setText(tf600, pillUnitSize, aa)
                text.color = unitColour
                canvas.drawText(loopLabel, x, baseline(y), text)
            }
        }
        // Glass/Orbit UX round 2: the unit in its value's hue at 72 % (a grey unit read as noise beside the value)
        fun unitOf(c: Int) = if (lowBit || ambient) unitColour else withAlpha(c, 184)
        if (pillW[1] > 0f) drawValueUnit(canvas, pillX[1], iobText, iobUnit, if (lowBit) Color.WHITE else if (ambient) A_TXT1 else iobColour, unitOf(iobColour), aa)
        if (pillW[2] > 0f) drawValueUnit(canvas, pillX[2], khText, khUnit, if (lowBit) Color.WHITE else if (ambient) A_TXT1 else khColour, unitOf(khColour), aa)
    }

    private fun drawValueUnit(canvas: Canvas, x0: Float, value: String, unit: String, valueColour: Int, unitColour: Int, aa: Boolean) {
        setText(tf600, pillValueSize, aa)
        text.color = valueColour
        val y = baseline(PILL_Y)
        canvas.drawText(value, x0 + PILL_PAD, y, text)
        if (unit.isEmpty()) return
        val x = x0 + PILL_PAD + text.measureText(value) + UNIT_GAP
        setText(tf500, pillUnitSize, aa)
        text.color = unitColour
        canvas.drawText(unit, x, y, text)
    }

    /** The family's loop glyph (Halo): a 300 degree arc open at the top right with a filled head, 20 px (UX round 2: 16). */
    private fun drawLoopGlyph(canvas: Canvas, cx: Float, cy: Float, colour: Int, aa: Boolean, width: Float) {
        val r = LOOP_GLYPH_W / 2f - 1f
        stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND; stroke.color = colour
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -30f, 300f, false, stroke)
        val hx = cx + r * cos(Math.toRadians(-60.0)).toFloat()
        val hy = cy + r * sin(Math.toRadians(-60.0)).toFloat()
        val h = 5f
        glyphPath.reset()
        glyphPath.moveTo(hx - h, hy - h * 0.15f)
        glyphPath.lineTo(hx + h * 0.35f, hy + h * 0.15f)
        glyphPath.lineTo(hx - h * 0.25f, hy + h * 1.05f)
        glyphPath.close()
        stroke.style = Paint.Style.FILL_AND_STROKE
        stroke.strokeWidth = 0.8f
        canvas.drawPath(glyphPath, stroke)
    }

    private val glyphPath = Path()

    private fun drawFooter(canvas: Canvas, runs: List<GlassFaceLogic.Run>, xs: FloatArray, size: Float, ambient: Boolean, aa: Boolean) {
        setText(tf500, size, aa) // layoutFooter already applied the physical floor
        val y = baseline(FOOTER_Y)
        val lowBit = ambient && lowBitAmbient
        runs.forEachIndexed { i, run ->
            if (i >= xs.size) return@forEachIndexed
            if (i > 0) {
                text.color = if (lowBit) Color.WHITE else if (ambient) A_TXT2 else TXT2
                canvas.drawText(FOOTER_SEP, xs[i - 1] + text.measureText(runs[i - 1].text), y, text)
            }
            text.color = when {
                lowBit                                                 -> Color.WHITE
                ambient && run.kind == GlassFaceLogic.RunKind.WARNING  -> A_TXT1
                ambient                                                -> A_TXT2
                else                                                   -> toneColour(run.tone)
            }
            canvas.drawText(run.text, xs[i], y, text)
        }
    }

    /**
     * Ball (states.md §4.6; UX round 1 makes the states distinct by more than one glyph):
     * idle = indigo sphere, "+0,5" / "U"; armed = solid #5B63F5 fill, white countdown ring, "nochmal";
     * sent = the idle sphere at 55 % with "…" (no white rim: only the 150 ms send flash has one);
     * disabled = plain sphere at 40 % with a dashed rim and a slash (by shape, not only by alpha).
     */
    private fun drawBall(canvas: Canvas, now: Long) {
        val bx = GlassFaceLogic.BALL_X
        val by = GlassFaceLogic.BALL_Y
        val r = GlassFaceLogic.BALL_R
        val armed = ball == BallState.ARMED
        val disabled = ball == BallState.DISABLED
        if (armed) {
            fill.reset(); fill.isAntiAlias = true; fill.color = BALL_ARMED_COL
            canvas.drawCircle(bx, by, r, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2f; stroke.color = withAlpha(Color.WHITE, 60)
            canvas.drawCircle(bx, by, RING_R, stroke)
            stroke.strokeWidth = 3f; stroke.color = Color.WHITE; stroke.strokeCap = Paint.Cap.ROUND
            rect.set(bx - RING_R, by - RING_R, bx + RING_R, by + RING_R)
            canvas.drawArc(rect, -90f, 360f * GlassFaceLogic.armProgress(guard.armedAt, now, BolusTapGesture.WINDOW_MS), false, stroke)
        } else {
            val bmp = ballBitmap(if (disabled) 1 else 0)
            bitmapPaint.alpha = when (ball) {
                BallState.DISABLED -> 102
                BallState.SENT     -> 140
                else               -> 255
            }
            canvas.save()
            canvas.scale(1f / scale, 1f / scale)
            canvas.drawBitmap(bmp, (bx - BALL_BOX) * scale, (by - BALL_BOX) * scale, bitmapPaint)
            canvas.restore()
            bitmapPaint.alpha = 255
        }
        if (now < flashUntil) {
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 3f; stroke.color = Color.WHITE
            canvas.drawCircle(bx, by, r, stroke)
        }
        if (disabled) {
            // disabled by SHAPE, not only by alpha: dashed rim and a diagonal slash (a tap shows the reason)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f
            stroke.color = withAlpha(BALL_RIM_COL, 140)
            val dash = (2.0 * Math.PI * r / 20.0).toFloat()
            stroke.pathEffect = DashPathEffect(floatArrayOf(dash * 0.55f, dash * 0.45f), 0f)
            canvas.drawCircle(bx, by, r, stroke)
            stroke.pathEffect = null
            // Glass/Orbit UX round 6: no slash through the digits (it read as "broken"); the dashed rim and the dim
            // sphere say "disabled", the amount stays readable in LABEL_COL
        }
        val textColour = if (disabled) LABEL_COL else if (ball == BallState.SENT) withAlpha(Color.WHITE, 191) else Color.WHITE
        // Glass/Orbit UX round 6 (sunlight): a 1 px dark shadow under the amount on the sphere
        setText(tf700, BALL_AMOUNT_SIZE, true, Paint.Align.CENTER)
        text.color = textColour
        if (!armed) text.setShadowLayer(1.5f, 0f, 1f, BALL_TEXT_SHADOW)
        canvas.drawText(amountText, bx, baseline(by - 4f), text)
        text.clearShadowLayer()
        when {
            armed                   -> {
                // Glass/Orbit UX round 1: a confirm tick ("tap again to send") instead of "nochmal", which did not fit
                // the ball at the 18 px floor and touched the countdown ring
                stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2.6f
                stroke.color = Color.WHITE; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
                canvas.drawLine(bx - 8f, by + 12f, bx - 2.5f, by + 17f, stroke)
                canvas.drawLine(bx - 2.5f, by + 17f, bx + 8f, by + 7f, stroke)
            }

            ball == BallState.SENT  -> drawCentred(canvas, "…", bx, by + 14f, tf700, BALL_SUB_SIZE, withAlpha(BALL_UNIT_COL, 191))
            else                    -> drawCentred(canvas, "U", bx, by + 14f, tf600, BALL_SUB_SIZE, if (disabled) LABEL_COL else BALL_UNIT_COL)
        }
    }

    private fun drawAmbient(canvas: Canvas) {
        val lowBit = lowBitAmbient
        val burnIn = burnInProtection
        val aa = !lowBit
        fun c(colour: Int) = if (lowBit) Color.WHITE else colour

        // UX round 4: the time at regular weight (the 200 hairline read weaker than the date under it)
        drawTime(canvas, tf400, c(A_TXT1), c(A_TXT2), c(A_TXT2), aa)

        // hero card outline: the range as a shape (§9; colour is never the only carrier)
        drawAmbientOutline(canvas, lowBit)
        // BG digits: filled weight 400 (urgent low 600); outlined 2 px under burn-in protection (no filled area > 4x4 px)
        setText(if (outline.boldBg) tf600 else tf400, bgSize, aa, Paint.Align.CENTER)
        text.color = c(A_TXT1)
        if (burnIn) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = 2f
        }
        drawBg(canvas, hero.bgX, hero.bgY)
        if (strike && bgText != "---") {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 2f; line.color = c(A_TXT1)
            canvas.drawLine(hero.bgX - bgHalfWidth - 4f, hero.bgY + 2f, hero.bgX + bgHalfWidth + 4f, hero.bgY + 2f, line)
        }
        drawCaption(canvas, ambient = true, lowBit = lowBit)
        if (showChip) {
            stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1f; stroke.color = c(A_TXT3)
            canvas.drawCircle(hero.chipX, hero.chipY, CHIP_R, stroke)
            drawArrow(canvas, c(A_TXT1), aa, maxWidth = 2.5f)
        }
        drawMeta(canvas, c(A_TXT2), c(A_TXT3), c(if (ageLevel != Level.NORMAL) A_TXT1 else A_TXT2), aa)

        // sparkline: no band fill, no prediction; only the low edge as a 1 px line (omitted in low-bit). Glass polish
        // round 2: with both edges always inside the plot, two full-width hairlines read as a rail in ambient
        if (!lowBit && hasHistory) drawSparkGuides(canvas, A_LINE, Color.TRANSPARENT, aa, fade = false, gap = 1.5f + 2f * TRACE_GAP)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 1.5f; line.color = c(A_HIST)
            drawTrace(canvas, line)
            // Glass polish round 6: clamped older history as a dim line (low-bit: only the chevron, no grey exists)
            drawOffScale(canvas, aa = aa, width = 1.5f, trace = !lowBit) { if (lowBit) Color.WHITE else A_LINE }
        }
        if (hasCurrent) {
            fill.reset(); fill.isAntiAlias = aa; fill.color = Color.BLACK
            canvas.drawCircle(curX, curY, 3f, fill)
            stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1f; stroke.color = c(A_TXT1)
            canvas.drawCircle(curX, curY, 3f, stroke)
        }

        // pills: borderless as in interactive
        drawPillSeparators(canvas, c(A_TXT2), aa)
        drawPillContents(canvas, ambient = true, lowBit = lowBit)

        drawFooter(canvas, footerAmbient, footerAmbientX, footerAmbientSize, ambient = true, aa = aa)
    }

    /** Hero card outline in ambient by range: solid, dashed 6/5, or double (5 px apart); white with AA off in low-bit. */
    private fun drawAmbientOutline(canvas: Canvas, lowBit: Boolean) {
        val o = outline
        if (o.style == OutlineStyle.NONE) return
        stroke.reset(); stroke.isAntiAlias = !lowBit; stroke.style = Paint.Style.STROKE
        stroke.color = if (lowBit) Color.WHITE else when (o.tone) {
            AmbTone.LINE -> A_LINE
            AmbTone.A3   -> A_TXT3
            AmbTone.A2   -> A_TXT2
            AmbTone.A1   -> A_TXT1
        }
        stroke.strokeWidth = o.width
        if (o.style == OutlineStyle.DASHED) stroke.pathEffect = dash65
        rect.set(HERO_X0, HERO_Y0, HERO_X1, HERO_Y1)
        canvas.drawRoundRect(rect, HERO_R, HERO_R, stroke)
        if (o.style == OutlineStyle.DOUBLE) {
            val g = GlassFaceLogic.DOUBLE_OUTLINE_GAP
            stroke.strokeWidth = o.innerWidth
            rect.set(HERO_X0 + g, HERO_Y0 + g, HERO_X1 - g, HERO_Y1 - g)
            canvas.drawRoundRect(rect, HERO_R - g, HERO_R - g, stroke)
        }
        stroke.pathEffect = null
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /**
     * Glass/Orbit UX round 3 (colour semantics): the trace is coloured by the VALUE zone of each point, the hues of the
     * BG state (amber above the high line, green in the band, red below the low line; blue is the insulin colour and
     * is never used), and the history fades in from 45 % to full strength at now instead of shifting hue. Stale: grey.
     * A vertical zone gradient with hard stops at the band lines, masked by a horizontal alpha ramp.
     */
    private fun historyShader(r: Range): Shader {
        val key = "$sparkX0/$nowX/$highY/$lowY"
        if (key != shaderKey) {
            historyShaders.fill(null)
            shaderKey = key
        }
        return historyShaders[r.ordinal] ?: run {
            val y0 = GlassFaceLogic.SPARK_Y0
            val y1 = GlassFaceLogic.SPARK_Y1
            val zones = if (r == Range.STALE) LinearGradient(0f, y0, 0f, y1, STALE_COL, STALE_COL, Shader.TileMode.CLAMP)
            else {
                val fh = ((highY - y0) / (y1 - y0)).coerceIn(0f, 1f)
                val fl = ((lowY - y0) / (y1 - y0)).coerceIn(fh, 1f)
                LinearGradient(
                    0f, y0, 0f, y1, intArrayOf(HIGH_COL, HIGH_COL, IN_COL, IN_COL, LOW_COL, LOW_COL),
                    floatArrayOf(0f, fh, fh, fl, fl, 1f), Shader.TileMode.CLAMP
                )
            }
            // Glass/Orbit UX round 6: older history at 40-60 %, only the last 15 min at full strength, so the eye goes
            // to the end of the trace (an old orange segment pulled it away from a falling urgent low)
            val f15 = ((GlassFaceLogic.SPARK_HISTORY - 4f) / (GlassFaceLogic.SPARK_HISTORY - 1f)).coerceIn(0f, 1f)
            val fade = LinearGradient(
                sparkX0, 0f, nowX, 0f, intArrayOf(withAlpha(Color.WHITE, 100), withAlpha(Color.WHITE, 150), Color.WHITE, Color.WHITE),
                floatArrayOf(0f, (f15 - 0.04f).coerceAtLeast(0f), f15, 1f), Shader.TileMode.CLAMP
            )
            ComposeShader(zones, fade, PorterDuff.Mode.DST_IN)
        }.also { historyShaders[r.ordinal] = it }
    }

    // ---------------------------------------------------------------- baked bitmaps (software canvas: blur works)

    private fun recycleBitmaps() {
        recycleBackgrounds(backgrounds)
        bakedKey = ""
        bakingKey = ""
        recycleBallBitmaps()
    }

    private fun recycleBackgrounds(set: Array<Bitmap?>) {
        for (i in set.indices) {
            set[i]?.recycle(); set[i] = null
        }
    }

    private fun recycleBallBitmaps() {
        for (i in ballBitmaps.indices) {
            ballBitmaps[i]?.recycle(); ballBitmaps[i] = null
        }
    }

    /**
     * Bake all six backgrounds (in, high, high+, low, low+, stale) for the current size and pill layout on a
     * background thread, then swap the whole set in on the main thread. onDraw only blits; until the first
     * set lands it draws the plain night gradient.
     */
    private fun ensureBackgrounds() {
        val size = pixelSize
        if (size <= 0) return
        val key = "$size"
        if (key == bakedKey || key == bakingKey) return
        bakingKey = key
        watchfaceScope.launch {
            val set: Array<Bitmap?> = try {
                withContext(Dispatchers.Default) { Array(Range.entries.size) { bakeBackground(Range.entries[it], size) } }
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "GlassWatchface background bake failed", e)
                if (bakingKey == key) bakingKey = ""
                return@launch
            }
            if (bakingKey != key) { // superseded by a newer size or layout
                recycleBackgrounds(set)
                return@launch
            }
            val old = backgrounds
            backgrounds = set
            bakedKey = key
            bakingKey = ""
            recycleBackgrounds(old)
            invalidate()
        }
    }

    /**
     * UX round 2: a near-black field with a faint vignette (AMOLED: almost nothing lit outside the card), the range
     * bloom only inside the hero card (clipped), the dial rim and the card's glass chrome. The pills are drawn live.
     */
    private fun bakeBackground(r: Range, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 450f, size / 450f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(CX, 200f, 250f, intArrayOf(BG_CENTRE, BG_MID, Color.BLACK), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 450f, 450f, p)
        p.shader = null
        val colour = colourOf(r)
        // emphasis step (very high, urgent low): bloom alpha +20 %
        val k = if (r == Range.VERY_HIGH || r == Range.URGENT_LOW) 1.2f else 1f
        c.save()
        val card = Path().apply { addRoundRect(HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, HERO_R, Path.Direction.CW) }
        c.clipPath(card)
        if (r == Range.LOW || r == Range.URGENT_LOW) {
            // UX round 4 (P0 contrast): red digits on a red bloom measured 2.0-2.6:1. The bloom under the digits stays
            // at or below 20 % and the hue moves to a glow along the card rim (digits now about 3.5:1 or better).
            // Glass rework (P0, measured on the renders): the digits must reach 4.5:1 on every pixel around them, so
            // the interior bloom is gone and the rim glow is a thin band that stays outside the 16 px digit margin.
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 6f
            p.color = withAlpha(colour, (150 * k).toInt())
            p.maskFilter = BlurMaskFilter(5f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, HERO_R, p)
            p.maskFilter = null
        } else {
            // stale: a quieter grey bloom so the struck digits keep their contrast
            val s = if (r == Range.STALE) 0.6f else 1f
            // Glass/Orbit UX round 2: the bloom 30 % fainter, so the glass behind the digit strokes stays dark (7:1)
            blob(c, p, 110f, 108f, 340f, 280f, withAlpha(colour, (77 * k * s).toInt()), 40f)
            blob(c, p, 140f, 132f, 270f, 244f, withAlpha(colour, (42 * k * s).toInt()), 26f)
        }
        c.restore()
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, 22)
        c.drawCircle(CX, CX, 225f - 3f, p)
        // low cards: a darker pane (less white lift behind the red digits)
        if (r == Range.LOW || r == Range.URGENT_LOW) glass(c, p, HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, 16, 6, 60, 90)
        // Glass/Orbit UX round 3: the neutral frost 30 % fainter (it read grey-green and dull next to Halo's black);
        // the state bloom carries the tint
        else glass(c, p, HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, 24, 8, 60, 100)
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
        // Glass/Orbit UX round 4: the full-width highlight read as a phone widget. It is inset by the corner radius at
        // each end, half as bright, and fades out towards its ends like light on a curved glass edge.
        val hl = withAlpha(Color.WHITE, hairline / 2)
        p.color = Color.WHITE
        p.shader = LinearGradient(
            l + radius, 0f, r - radius, 0f, intArrayOf(hl and 0x00FFFFFF, hl, hl, hl and 0x00FFFFFF), floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawLine(l + radius, t + 1f, r - radius, t + 1f, p)
        p.shader = null
    }

    /**
     * Indigo sphere of the ball (never a range/warn/alert hue), centred in a (2*BALL_BOX)² design box.
     * [variant] 0 idle (halo + highlight), 1 plain (disabled: no glow, no highlight). The rim is light indigo, not
     * white: a white rim is reserved for armed (countdown) and the send flash.
     */
    private fun ballBitmap(variant: Int): Bitmap {
        ballBitmaps[variant]?.let { return it }
        val px = (2 * BALL_BOX * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val cx = BALL_BOX
        val r = GlassFaceLogic.BALL_R
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (variant == 0) {
            p.color = withAlpha(BALL_COL, 100)
            p.maskFilter = BlurMaskFilter(7f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(cx, cx, r + 2f, p)
            p.maskFilter = null
        }
        // Glass/Orbit UX round 6 (contrast): the light indigo only as a top-left highlight, the body a deeper indigo, so
        // the white amount keeps 4.5:1 or more (the old linear ramp put it on a 3.6:1 mid-tone)
        p.shader = RadialGradient(
            cx - 10f, cx - 14f, r + 12f, intArrayOf(0xFFA3ACFF.toInt(), 0xFF5A63D2.toInt(), 0xFF3E46B8.toInt(), 0xFF2C3290.toInt()),
            floatArrayOf(0f, 0.22f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        p.alpha = 235
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.alpha = 255
        if (variant == 0) {
            p.color = withAlpha(Color.WHITE, 70)
            p.maskFilter = BlurMaskFilter(3f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawOval(cx - 18f, cx - 27f, cx + 3f, cx - 15f, p)
            p.maskFilter = null
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(BALL_UNIT_COL, 110)
        c.drawCircle(cx, cx, r, p)
        ballBitmaps[variant] = bmp
        return bmp
    }

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TAP_UP || isAmbient) return // tap.ambient: no hit testing at all
        val size = min(faceRect.width(), faceRect.height())
        val s = size / 450f
        if (s <= 0f) return
        val dx = (x - (faceRect.width() - size) / 2f) / s
        val dy = (y - (faceRect.height() - size) / 2f) / s

        val now = System.currentTimeMillis()
        updateState(now) // fresh state (phone link, data age) at the moment of the tap
        // The guard re-checks every rule itself and drops a duplicate delivery of the same tap.
        // 24 dp radius whatever the screen (the family's 48 dp target), never less than the drawn ball's hit ring
        val hitR = maxOf(GlassFaceLogic.BALL_HIT_R, 24f * resources.displayMetrics.density / s)
        val inside = ball != BallState.HIDDEN && GlassFaceLogic.ballHit(dx, dy, r = hitR)
        val outcome = guard.onTap(eventTime, inside, bolusInput())
        when (outcome.result) {
            TapResult.DUPLICATE -> return
            TapResult.ARM       -> {
                refreshPhoneReachable() // fresh answer for the SEND gate, well inside the window
                ball = BallState.ARMED
                vibrate()
                handler.removeCallbacks(armedTick)
                handler.post(armedTick)
                return
            }

            TapResult.SEND      -> {
                handler.removeCallbacks(armedTick)
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "GlassWatchface: double-tap bolus request $amount U")
                // Request only, exactly as BolusActivity: the phone checks it and the watch shows the confirmation.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                vibrate()
                flashUntil = now + FLASH_MS
                ball = BallState.SENT
                handler.postDelayed(redraw, FLASH_MS + 10)
                handler.postDelayed(redraw, BolusRequestGuard.PENDING_MS + 10)
                invalidate()
                return
            }

            TapResult.REFUSED   -> {
                disarm()
                if (outcome.reason == Reason.NO_PHONE) refreshPhoneReachable()
                when (GlassFaceLogic.refusal(outcome.reason)) {
                    GlassFaceLogic.Refusal.PENDING         -> R.string.glass_bolus_pending
                    GlassFaceLogic.Refusal.NO_PHONE        -> R.string.glass_bolus_refused_no_phone
                    GlassFaceLogic.Refusal.STALE_DATA      -> R.string.glass_bolus_refused_stale
                    GlassFaceLogic.Refusal.OVER_MAX_BOLUS  -> R.string.glass_bolus_refused_max
                    GlassFaceLogic.Refusal.NO_MAX_BOLUS    -> R.string.glass_bolus_refused_max_unknown
                    GlassFaceLogic.Refusal.NO_WEAR_CONTROL -> R.string.glass_bolus_refused_wear_control
                    null                                   -> null
                }?.let { toast(it) }
                dirty = true
                invalidate()
                return
            }

            TapResult.DISARM    -> {
                handler.removeCallbacks(armedTick)
                dirty = true
                invalidate()
            }

            TapResult.IGNORE    -> Unit
        }
        if (outcome.onButton) return

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

    private fun toast(res: Int) {
        try {
            Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "GlassWatchface toast failed", e)
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
        // UX round 2 geometry (450 grid): card ±150 px with 12 px inner padding, BG 128 px, delta line 28 px under it
        // with the TIEF/HOCH pill, sparkline 44 px with the ball docked left, three content-sized pills, footer 22 px.
        // UX round 3: the time and date start below y 60 (the Watch5 Pro status icon spans y 23-49), the date is 24 px,
        // the card is y 120..274, the sparkline 50 px, the pills y 344..380 and the footer 26 px at y 406.
        // UX round 4: time and date 4-6 px further apart and 10 px or more above the card (y 116)
        // Glass live round 1: on the watch the Samsung status icon (charging, y 23..49 plus its dark disc) sat on the
        // time's top edge; the time is 38 px (Halo's cap height) centred at y 77 (glyphs y 63..91), the date at 108
        // Glass polish round 1: 5 px lower (glyphs y 68..96, 13 px under the icon's dark disc), the date 4 px; the card's
        // top follows (y 132)
        private const val TIME_Y = 82f
        private const val TIME_Y_NO_DATE = 95f
        private const val TIME_SIZE = 38f
        private const val AM_PM_GAP = 4f
        /** 12 h suffix: at the 18 px text floor (was 17). */
        private const val AM_PM_SIZE = 18f
        // Glass/Orbit UX round 3: 22 px in TXT2 (Halo's rhythm) and 3 px further from the time: one calm top block
        private const val DATE_Y = 112f
        private const val DATE_SIZE = 22f
        /** Physical text floor (Halo's [app.aaps.wear.watchfaces.halo.HaloLayout.MIN_TEXT_PX]): 384 px screens keep it. */
        private const val MIN_TEXT_PX = 18f
        private const val HERO_X0 = GlassFaceLogic.HERO_X0
        private const val HERO_Y0 = GlassFaceLogic.HERO_Y0
        private const val HERO_X1 = GlassFaceLogic.HERO_X1
        private const val HERO_Y1 = GlassFaceLogic.HERO_Y1
        private const val HERO_R = 28f
        private const val HERO_PAD = 12f
        // Glass polish round 1: the delta row and the TIEF/HOCH pill a step smaller with the 112 px digits
        // Glass polish round 3: back to 24 / 30, the word that stands in for colour does not shrink in a layout pass
        private const val CAPTION_SIZE = 24f
        private const val CAPTION_H = 26f // Glass polish round 6: 30 -> 26 (the word stays 24 px), 12 px to the card's bottom
        private const val CAPTION_H_AMBIENT_BADGE = 30f
        private const val CAPTION_PAD = 10f
        private const val CAPTION_GAP = 12f
        // UX round 4: chip r 18 -> 24 and a 30 px arrow with a 3.4 px stroke (the double arrow reads at arm's length)
        private const val CHIP_X = GlassFaceLogic.CHIP_X
        private const val CHIP_R = GlassFaceLogic.CHIP_R
        private const val ARROW_L = 15f // Glass polish round 1: 17 -> 15, a 30 px arrow in the r 25 chip
        private val DOUBLE_OFFSETS = floatArrayOf(-7f, 7f)
        private val DOUBLE_HEAD_DX = (6.5 * cos(Math.toRadians(42.0))).toFloat()
        private val DOUBLE_HEAD_DY = (6.5 * sin(Math.toRadians(42.0))).toFloat()
        private val CHIP_BASE = 0xD9000000.toInt()
        private val BALL_TEXT_SHADOW = 0x99000000.toInt()
        private val HEAD_DX = (9.0 * cos(Math.toRadians(42.0))).toFloat()
        private val HEAD_DY = (9.0 * sin(Math.toRadians(42.0))).toFloat()
        private const val META_SIZE = 26f
        private const val META_MIN_SIZE = 22f
        private const val META_SEP = " · "
        // UX round 4: borderless pills (text 26 px, units 20 px), a dot in the 22 px gap
        private const val PILL_Y = GlassFaceLogic.PILL_Y // Glass/Orbit UX round 3: 12 px lower, the chart gets air
        private const val PILL_PAD = 4f
        private const val PILL_GAP = 22f
        private const val PILL_INNER_GAP = 7f
        private const val UNIT_GAP = 2f // Glass/Orbit UX round 2: 4 -> 2, the unit sits with its value
        private const val PILL_VALUE_SIZE = 26f
        private const val PILL_VALUE_MIN = 18f
        private const val PILL_VALUE_PX_MIN = 22f
        private const val PILL_UNIT_SIZE = 20f
        private const val PILL_UNIT_PX_MIN = MIN_TEXT_PX
        /** Loop glyph (Glass/Orbit UX round 2: the dot is gone, the glyph leads, 20 px tall: above the 18 px floor). */
        private const val LOOP_GLYPH_W = 20f
        private const val LOOP_GLYPH_STROKE = 2.4f
        private const val BALL_AMOUNT_SIZE = 19f
        private const val BALL_SUB_SIZE = MIN_TEXT_PX

        /** Flavour effects (flavor.client / flavor.pumpcontrol): no predictions; pumpcontrol has no loop pill. */
        private val IS_PUMPCONTROL = BuildConfig.FLAVOR == "pumpcontrol"
        private val IS_CLIENT = BuildConfig.FLAVOR.startsWith("aapsclient")
        private const val FOOTER_Y = GlassFaceLogic.FOOTER_Y
        private const val FOOTER_SEP = "  ·  "
        private const val BALL_BOX = 48f // Glass/Orbit UX round 6: 44 -> 48 for the r 31 ball and its halo
        private const val RING_R = 35f // Glass/Orbit UX round 6: 32 -> 35, 4 px outside the r 31 ball

        // Family colour tokens (designsystem/states.md §3)
        private val BG_CENTRE = 0xFF0D101C.toInt()
        private val BG_MID = 0xFF06070D.toInt()
        private val IN_COL = 0xFF3DDC97.toInt()
        /**
         * Sparkline target band (Glass polish round 2): ONE look in every state (the grey out-of-range strip with two
         * grey hairlines read as an empty rail): an [IN_COL] tint, a readable low edge and a fainter high edge.
         */
        private const val BAND_CORNER = 4f
        private const val BAND_FADE = 20f
        private const val BAND_FADE_V = 16f
        private const val BAND_ALPHA = 20
        /** Glass polish round 4: the red tint under the low edge at a hypo ([lowZone]). */
        private const val LOW_ZONE_ALPHA = 28
        private const val BAND_LOW_EDGE_ALPHA = 130
        private const val BAND_HIGH_EDGE_ALPHA = 70
        /** Glass polish round 3: on a stale reading the band is drawn in [STALE_COL] (green hinted "in range"). */
        private const val BAND_LOW_EDGE_ALPHA_STALE = 110
        private const val BAND_HIGH_EDGE_ALPHA_STALE = 60
        private const val TRACE_W = 3f
        /** Glass polish round 3: the band edges break [TRACE_GAP] px each side of the trace. */
        private const val TRACE_GAP = 1.5f
        private const val EDGE_DASH_ON = 4f
        private const val EDGE_DASH_OFF = 3f
        private const val NOW_R = 5.5f
        // Glass polish round 3: 2,4 px specks at 150 read as an ellipsis; 3,2 px dots every 6,5 px at 190, from 9 px
        // right of the now dot's centre (clear of its dark ring and glow)
        private const val PRED_DOT = 3.2f
        private const val PRED_DOT_STEP = 6.5f
        private const val PRED_START = 9f
        private const val PRED_ALPHA = 190
        /** Glass polish round 6: clamped older history (off the plot's top) at about 28 %, its chevron at about 60 %. */
        private const val OFF_SCALE_ALPHA = 72
        private const val OFF_CHEVRON_ALPHA = 150
        private const val OFF_CHEVRON_MIN_RUN = 8f
        private const val OFF_CHEVRON_W = 3.5f
        private const val OFF_CHEVRON_H = 3f
        private const val OFF_CHEVRON_GAP = 3f
        private const val OFF_CLIP = 12f
        private const val OFF_DOT_INSET = 3f
        /** In-range digits (Glass/Orbit UX round 2): a pale mint, the family's green at Glass's calmer strength. */
        private val IN_DIGIT_COL = 0xFFCFF5E4.toInt()
        private val HIGH_COL = 0xFFFFB547.toInt()
        private val LOW_COL = 0xFFFF5A6E.toInt()
        private val LOW_TEXT_COL = 0xFFFFD6DC.toInt()
        private val BADGE_TEXT_COL = 0xFF1A0508.toInt()
        private val CAPTION_FILL = 0xE00B0D14.toInt()
        private val STALE_COL = 0xFF8A93A8.toInt()
        private val STALE_DIGIT_COL = 0xFFC2C9DA.toInt()
        private val TXT1 = 0xFFEEF1F7.toInt()
        private val TXT2 = 0xFF9AA6C4.toInt()
        private val TXT3 = 0xFF6F7A99.toInt()
        private val LABEL_COL = 0xFFAAB4CF.toInt()
        private val DELTA_COL = 0xFFC8D2E6.toInt()
        private val WARN_COL = 0xFFFFB547.toInt()
        private val ALERT_COL = 0xFFFF5A6E.toInt()
        private val INS_COL = 0xFF5AC8FA.toInt()
        private val KH_COL = 0xFFFFE0B2.toInt()
        private val TBR_COL = 0xFF3DDBD9.toInt()
        private val TT_COL = 0xFFB388FF.toInt()
        private val LOOP_OK = 0xFF40D68C.toInt()
        /**
         * Forecast token (Glass polish round 2/3): the stale grey at [PRED_ALPHA] in [PRED_DOT] px dots, neutral so it is never
         * read as a BG zone (the AAPS purple stood out against the design system and drew the eye); predictions are
         * never drawn on a stale reading, so it does not meet the grey stale trace.
         */
        private val PRED_COL = 0xFF8A93A8.toInt()
        private val BALL_COL = 0xFF7888FF.toInt()
        private val BALL_ARMED_COL = 0xFF5B63F5.toInt()
        private val BALL_UNIT_COL = 0xFFE3E6FF.toInt()
        private val BALL_RIM_COL = 0xFF7888FF.toInt()
        private val A_TXT1 = 0xFFE0E0E0.toInt()
        private val A_TXT2 = 0xFF9A9A9A.toInt()
        private val A_TXT3 = 0xFF6E6E6E.toInt()
        private val A_LINE = 0xFF3C3C3C.toInt()
        private val A_HIST = 0xFFB4B4B4.toInt()
    }
}
