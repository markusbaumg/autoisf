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
    private var loopDotFilled = false
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

    private val history = Path()
    private var hasHistory = false
    private var curX = 0f
    private var curY = 0f
    private var hasCurrent = false
    private val predXY = FloatArray(2 * 12)
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
    private val dash35 = DashPathEffect(floatArrayOf(3f, 5f), 0f)
    private val dash24 = DashPathEffect(floatArrayOf(2f, 4f), 0f)
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
        outline = GlassFaceLogic.ambientOutline(range, lowBitAmbient)

        // meta line (delta · age); empty for age.none / first start
        val (deltaOn, ageOn) = GlassFaceLogic.metaVisible(
            bgAge, prefShowAgo = sp.getBoolean(R.string.key_show_ago, true), prefShowDelta = sp.getBoolean(R.string.key_show_delta, true)
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
        bgColour = when (GlassFaceLogic.digitTone(range, stale)) {
            GlassFaceLogic.DigitTone.STALE -> STALE_DIGIT_COL // UX round 4: brighter than TXT2 on the grey card
            GlassFaceLogic.DigitTone.LOW   -> LOW_COL
            GlassFaceLogic.DigitTone.WHITE -> Color.WHITE
        }
        setText(tf600, GlassFaceLogic.BG_SIZE)
        bgSize = GlassFaceLogic.bgFitSize({ size -> text.textSize = size; bgWidth() }, hero.bgMaxWidth)
        text.textSize = bgSize
        bgHalfWidth = bgWidth() / 2f
        if (stale) trend = Trend.NONE
        layoutMeta()

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
                    else               -> TXT1 // OK, and BAD: the footer carries the alert (rule 3.3)
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
        loopDotFilled = GlassFaceLogic.loopDotFilled(loopHealth, owner)
        val footerInput = GlassFaceLogic.FooterInput(
            warnings = warnings,
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
            showBasal = sp.getBoolean(R.string.key_show_temp_basal, true),
            showBgi = sp.getBoolean(R.string.key_show_bgi, false),
            showReservoir = sp.getBoolean(R.string.key_show_reservoir_level, true),
            showPhoneBattery = sp.getBoolean(R.string.key_show_uploader_battery, true)
        )
        footer = if (firstStart && warnings.isEmpty()) emptyList() else layoutFooter(footerInput, footerX)
        footerSize = lastFooterSize
        footerAmbient = layoutFooter(footerInput.copy(ambient = true), footerAmbientX)
        footerAmbientSize = lastFooterSize

        // sparkline
        val low = GlassFaceLogic.lowLine(bg.low)
        val high = GlassFaceLogic.highLine(bg.low, bg.high)
        lowY = GlassFaceLogic.sparkY(low)
        highY = GlassFaceLogic.sparkY(high)
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
        // UX round 4: the TIEF/HOCH pill sits right-aligned in the chip column, the delta line centres in the space
        // left of it (never under the comma of the larger digits)
        val capW = if (captionText.isNotEmpty()) captionPillWidth() else 0f
        captionX = HERO_X1 - HERO_PAD - capW / 2f
        val left = HERO_X0 + HERO_PAD
        val right = if (capW > 0f) HERO_X1 - HERO_PAD - capW - CAPTION_GAP else HERO_X1 - HERO_PAD
        metaSize = maxOf(META_MIN_SIZE, GlassFaceLogic.fitSize(META_SIZE, total, right - left))
        val k = metaSize / META_SIZE
        metaDeltaW *= k
        metaSepW *= k
        total *= k
        metaWidth = total
        val centre = if (capW > 0f) (left + right) / 2f else hero.metaCentreX
        metaX = centre - total / 2f
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
        val loopLabelW = when {
            !loopLabelShown    -> 0f
            loopLabel == "LGS" -> text.measureText(loopLabel)
            else               -> LOOP_GLYPH_W
        }
        pillW[0] = if (pills == Pills.IOB_ONLY) 0f
        else 2f * PILL_PAD + LOOP_DOT_W + (if (loopValueW > 0f) PILL_INNER_GAP + loopValueW else 0f) + (if (loopLabelW > 0f) PILL_INNER_GAP + loopLabelW else 0f)
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
        history.reset()
        hasCurrent = false
        var lastTs = Long.MIN_VALUE
        var started = false
        var prevTs = Long.MIN_VALUE
        for (e in data.graphData.entries.sortedBy { it.timeStamp }) {
            if (e.sgv <= 0.0 || e.timeStamp > anchor) continue
            val slot = GlassFaceLogic.slotOf(e.timeStamp, anchor) ?: continue
            val x = sx(slot)
            val y = GlassFaceLogic.sparkY(e.sgv)
            // graph.gaps: break the line, never interpolate
            if (!started || e.timeStamp - prevTs > GlassFaceLogic.GAP_MS) history.moveTo(x, y) else history.lineTo(x, y)
            started = true
            prevTs = e.timeStamp
            curX = x; curY = y; lastTs = e.timeStamp
        }
        if (bgTs > 0L && bgMgdl > 0.0 && bgTs > lastTs) {
            GlassFaceLogic.slotOf(bgTs, anchor)?.let { slot ->
                val x = sx(slot)
                val y = GlassFaceLogic.sparkY(bgMgdl)
                if (!started || bgTs - prevTs > GlassFaceLogic.GAP_MS) history.moveTo(x, y) else history.lineTo(x, y)
                started = true
                curX = x; curY = y
            }
        }
        hasHistory = started
        hasCurrent = started

        // prediction: the main (longest) series, sampled at the 12 future slots
        predCount = 0
        if (!sparkForecast) return
        val main = preds.groupBy { it.color }.maxByOrNull { it.value.size }?.value ?: return
        for (k in 1..12) {
            val t = anchor + k * GlassFaceLogic.SLOT_MS
            val p = main.minByOrNull { kotlin.math.abs(it.timeStamp - t) } ?: continue
            if (kotlin.math.abs(p.timeStamp - t) > GlassFaceLogic.SLOT_MS / 2 || p.sgv <= 0.0) continue
            predXY[2 * predCount] = sx((GlassFaceLogic.SPARK_HISTORY - 1 + k).toFloat())
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

    /** Time (tabular digits, own paint), 12 h suffix, and the date unless key_show_date is off (time then at y=74). */
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
        if (!stale && !GlassFaceLogic.isLow(range)) text.setShadowLayer(10f, 0f, 0f, withAlpha(rangeColour, 0x70))
        drawBg(canvas, hero.bgX, hero.bgY)
        if (strike && bgText != "---") {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = 3f; line.color = STALE_DIGIT_COL
            canvas.drawLine(hero.bgX - bgHalfWidth - 4f, hero.bgY + 2f, hero.bgX + bgHalfWidth + 4f, hero.bgY + 2f, line)
        }
        drawCaption(canvas, ambient = false, lowBit = false)

        // trend chip (a struck reading has none: its age sits under the digits, in alert)
        if (showChip) {
            fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(rangeColour, 40)
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = withAlpha(rangeColour, 210)
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, stroke)
            drawArrow(canvas, rangeColour, aa = true, maxWidth = 4.2f)
        }

        // meta line + phone-off glyph (W10); UX round 3: a normal age in the delta colour (TXT2 was 4.2:1 on the glass)
        // UX round 4: the "·" in TXT2 (TXT3 vanished in sunlight and "-0,6 2'" read as one number)
        drawMeta(canvas, DELTA_COL, TXT2, when (ageLevel) {
            Level.NORMAL -> DELTA_COL
            Level.WARN   -> WARN_COL
            Level.ALERT  -> ALERT_COL
        }, aa = true)
        if (link == PhoneLink.LOST_FRESH) drawPhoneOff(canvas, min(metaX + metaWidth + 20f, HERO_X1 - 20f), hero.metaY, TXT2)

        // sparkline (UX round 4: no band fill, the two dashed range lines frame it)
        drawSparkGuides(canvas, withAlpha(Color.WHITE, 46), withAlpha(Color.WHITE, 60), aa = true)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 3f
            line.shader = historyShader(range)
            if (!stale) line.setShadowLayer(4f, 0f, 0f, withAlpha(rangeColour, 180))
            canvas.drawPath(history, line)
            line.shader = null
            line.clearShadowLayer()
        }
        if (predCount > 0) {
            fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(PRED_COL, 210)
            for (i in 0 until predCount) canvas.drawCircle(predXY[2 * i], predXY[2 * i + 1], 1.6f, fill)
        }
        if (hasCurrent) {
            fill.reset(); fill.isAntiAlias = true
            if (stale) {
                fill.color = TXT3
                canvas.drawCircle(curX, curY, 4f, fill)
            } else {
                fill.color = rangeColour
                fill.setShadowLayer(7f, 0f, 0f, withAlpha(rangeColour, 220))
                canvas.drawCircle(curX, curY, 5f, fill)
                fill.clearShadowLayer()
                fill.color = Color.WHITE
                canvas.drawCircle(curX, curY, 3f, fill)
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
                // urgent low by SHAPE (UX round 3): a double outline, 2 px outside and 1 px inside, burn-in safe
                stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = wordColour
                canvas.drawRoundRect(rect, CAPTION_H / 2f, CAPTION_H / 2f, stroke)
                stroke.strokeWidth = 1f
                rect.inset(3.5f, 3.5f)
                canvas.drawRoundRect(rect, CAPTION_H / 2f - 3.5f, CAPTION_H / 2f - 3.5f, stroke)
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
        line.strokeWidth = min(if (triple) 4.2f else 3.4f, maxWidth)
        line.color = colour
        val cy = hero.chipY
        canvas.save()
        canvas.rotate(-trend.angle, CHIP_X, cy)
        canvas.drawLine(CHIP_X - ARROW_L, cy, CHIP_X + ARROW_L, cy, line)
        drawChevron(canvas, CHIP_X + ARROW_L, cy)
        if (trend.double) drawChevron(canvas, CHIP_X + ARROW_L - 9f, cy)
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas, tipX: Float, cy: Float) {
        canvas.drawLine(tipX, cy, tipX - HEAD_DX, cy - HEAD_DY, line)
        canvas.drawLine(tipX, cy, tipX - HEAD_DX, cy + HEAD_DY, line)
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

    /** Target band dashes and, only when a forecast follows it, the dotted now line (without, now is the plot's edge). */
    private fun drawSparkGuides(canvas: Canvas, bandColour: Int, nowColour: Int, aa: Boolean) {
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 1f
        line.color = bandColour
        line.pathEffect = dash35
        canvas.drawLine(sparkX0, lowY, sparkEndX, lowY, line)
        canvas.drawLine(sparkX0, highY, sparkEndX, highY, line)
        if (sparkForecast) {
            line.color = nowColour
            line.pathEffect = dash24
            canvas.drawLine(nowX, GlassFaceLogic.SPARK_Y0, nowX, GlassFaceLogic.SPARK_Y1, line)
        }
        line.pathEffect = null
    }

    /**
     * Loop dot (states.md loop.*): OK/WARN filled with glow; BAD hollow red (it yields to the slot text);
     * NEVER/NEUTRAL hollow grey; OPEN hollow green; OFF hollow amber with a pause bar.
     * Ambient: no hue, thin a2 ring, or a 2 px a1 ring for WARN/BAD/OFF; low-bit 1 px white.
     */
    private fun drawLoopDot(canvas: Canvas, ambient: Boolean, lowBit: Boolean) {
        val cx = pillX[0] + PILL_PAD + LOOP_DOT_W / 2f
        val cy = PILL_Y
        val strong = loopHealth == LoopHealth.WARN || loopHealth == LoopHealth.BAD || loopHealth == LoopHealth.OFF
        val colour: Int
        val filled: Boolean
        if (ambient) {
            colour = if (lowBit) Color.WHITE else if (strong) A_TXT1 else A_TXT2
            filled = false
            stroke.reset(); stroke.isAntiAlias = !lowBit; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = if (lowBit || !strong) 1f else 2f
        } else {
            colour = when (loopHealth) {
                LoopHealth.OK, LoopHealth.OPEN       -> LOOP_OK
                LoopHealth.WARN, LoopHealth.OFF      -> WARN_COL
                LoopHealth.BAD                       -> ALERT_COL
                LoopHealth.NEVER, LoopHealth.NEUTRAL -> STALE_COL
            }
            filled = loopDotFilled
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f
        }
        stroke.color = colour
        if (filled) {
            fill.reset(); fill.isAntiAlias = true; fill.color = colour
            fill.setShadowLayer(4f, 0f, 0f, withAlpha(colour, 190))
            canvas.drawCircle(cx, cy, 6f, fill)
            fill.clearShadowLayer()
        } else canvas.drawCircle(cx, cy, 6f, stroke)
        if (loopHealth == LoopHealth.OFF) {
            stroke.strokeWidth = 1.4f
            canvas.drawLine(cx - 1.6f, cy - 2.5f, cx - 1.6f, cy + 2.5f, stroke)
            canvas.drawLine(cx + 1.6f, cy - 2.5f, cx + 1.6f, cy + 2.5f, stroke)
        }
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
            drawLoopDot(canvas, ambient, lowBit)
            var x = pillX[0] + PILL_PAD + LOOP_DOT_W + PILL_INNER_GAP
            setText(tf600, pillValueSize, aa)
            text.color = if (lowBit) Color.WHITE else if (ambient) A_TXT1 else loopValueColour
            val v = if (ambient) loopTextAmbient else loopText
            canvas.drawText(v, x, baseline(y), text)
            x += text.measureText(v) + PILL_INNER_GAP
            if (loopLabelShown) {
                if (loopLabel == "LGS") {
                    setText(tf600, pillUnitSize, aa)
                    text.color = unitColour
                    canvas.drawText(loopLabel, x, baseline(y), text)
                } else drawLoopGlyph(canvas, x + LOOP_GLYPH_W / 2f, y, unitColour, aa)
            }
        }
        if (pillW[1] > 0f) drawValueUnit(canvas, pillX[1], iobText, iobUnit, if (lowBit) Color.WHITE else if (ambient) A_TXT1 else iobColour, unitColour, aa)
        if (pillW[2] > 0f) drawValueUnit(canvas, pillX[2], khText, khUnit, if (lowBit) Color.WHITE else if (ambient) A_TXT1 else khColour, unitColour, aa)
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

    /** The family's loop glyph (Halo): a 300 degree arc open at the top right with a filled head, 14 px. */
    private fun drawLoopGlyph(canvas: Canvas, cx: Float, cy: Float, colour: Int, aa: Boolean) {
        val r = LOOP_GLYPH_W / 2f - 1f
        stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f
        stroke.strokeCap = Paint.Cap.ROUND; stroke.color = colour
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -30f, 300f, false, stroke)
        val hx = cx + r * cos(Math.toRadians(-60.0)).toFloat()
        val hy = cy + r * sin(Math.toRadians(-60.0)).toFloat()
        val h = 4f
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
            stroke.color = TXT3; stroke.strokeCap = Paint.Cap.ROUND
            val k = r * 0.7071f
            canvas.drawLine(bx - k, by + k, bx + k, by - k, stroke)
            // knock-out under the amount, so it stays legible over the slash
            setText(tf700, BALL_AMOUNT_SIZE, true, Paint.Align.CENTER)
            text.style = Paint.Style.STROKE; text.strokeWidth = 4f; text.color = Color.BLACK
            canvas.drawText(amountText, bx, baseline(by - 4f), text)
            text.style = Paint.Style.FILL
        }
        val textColour = if (disabled) TXT3 else if (ball == BallState.SENT) withAlpha(Color.WHITE, 191) else Color.WHITE
        drawCentred(canvas, amountText, bx, by - 4f, tf700, BALL_AMOUNT_SIZE, textColour)
        when {
            armed                   -> {
                setText(tf600, BALL_SUB_SIZE, true, Paint.Align.CENTER)
                text.textSize = GlassFaceLogic.fitSize(BALL_SUB_SIZE, text.measureText(againText), 2f * r - 6f)
                text.color = Color.WHITE
                canvas.drawText(againText, bx, baseline(by + 14f), text)
            }

            ball == BallState.SENT  -> drawCentred(canvas, "…", bx, by + 14f, tf700, BALL_SUB_SIZE, withAlpha(BALL_UNIT_COL, 191))
            else                    -> drawCentred(canvas, "U", bx, by + 14f, tf600, BALL_SUB_SIZE, if (disabled) TXT3 else BALL_UNIT_COL)
        }
    }

    private val againText by lazy { getString(R.string.halo_bolus_again) }

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
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, stroke)
            drawArrow(canvas, c(A_TXT1), aa, maxWidth = 2.5f)
        }
        drawMeta(canvas, c(A_TXT2), c(A_TXT3), c(if (ageLevel != Level.NORMAL) A_TXT1 else A_TXT2), aa)

        // sparkline: no band, no prediction; dashes and now line omitted in low-bit
        if (!lowBit) drawSparkGuides(canvas, A_LINE, A_LINE, aa)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 1.5f; line.color = c(A_HIST)
            canvas.drawPath(history, line)
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

    private fun historyShader(r: Range): Shader {
        val key = "$sparkX0/$nowX"
        if (key != shaderKey) {
            historyShaders.fill(null)
            shaderKey = key
        }
        return historyShaders[r.ordinal] ?: LinearGradient(
            sparkX0, 0f, nowX, 0f, if (r == Range.STALE) STALE_COL else HIST_START, colourOf(r), Shader.TileMode.CLAMP
        ).also { historyShaders[r.ordinal] = it }
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
            blob(c, p, 110f, 112f, 340f, 284f, withAlpha(colour, (110 * k * s).toInt()), 40f)
            blob(c, p, 140f, 136f, 270f, 248f, withAlpha(colour, (60 * k * s).toInt()), 26f)
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
        else glass(c, p, HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, 35, 11, 60, 100)
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
        p.shader = RadialGradient(cx - 4f, cx - 8f, r + 8f, 0xFFA3ACFF.toInt(), 0xFF2C3290.toInt(), Shader.TileMode.CLAMP)
        p.alpha = 235
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.alpha = 255
        if (variant == 0) {
            p.color = withAlpha(Color.WHITE, 70)
            p.maskFilter = BlurMaskFilter(3f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawOval(cx - 16f, cx - 24f, cx + 5f, cx - 12f, p)
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
        private const val TIME_Y = 66f
        private const val TIME_Y_NO_DATE = 80f
        private const val TIME_SIZE = 40f
        private const val AM_PM_GAP = 4f
        /** 12 h suffix: at the 18 px text floor (was 17). */
        private const val AM_PM_SIZE = 18f
        private const val DATE_Y = 97f
        private const val DATE_SIZE = 24f
        /** Physical text floor (Halo's [app.aaps.wear.watchfaces.halo.HaloLayout.MIN_TEXT_PX]): 384 px screens keep it. */
        private const val MIN_TEXT_PX = 18f
        private const val HERO_X0 = GlassFaceLogic.HERO_X0
        private const val HERO_Y0 = GlassFaceLogic.HERO_Y0
        private const val HERO_X1 = GlassFaceLogic.HERO_X1
        private const val HERO_Y1 = GlassFaceLogic.HERO_Y1
        private const val HERO_R = 28f
        private const val HERO_PAD = 12f
        private const val CAPTION_SIZE = 24f
        private const val CAPTION_H = 30f
        private const val CAPTION_PAD = 10f
        private const val CAPTION_GAP = 12f
        // UX round 4: chip r 18 -> 24 and a 30 px arrow with a 3.4 px stroke (the double arrow reads at arm's length)
        private const val CHIP_X = GlassFaceLogic.CHIP_X
        private const val CHIP_R = GlassFaceLogic.CHIP_R
        private const val ARROW_L = 15f
        private val HEAD_DX = (10.0 * cos(Math.toRadians(42.0))).toFloat()
        private val HEAD_DY = (10.0 * sin(Math.toRadians(42.0))).toFloat()
        private const val META_SIZE = 28f
        private const val META_MIN_SIZE = 24f
        private const val META_SEP = " · "
        // UX round 4: borderless pills (text 26 px, units 20 px), a dot in the 22 px gap
        private const val PILL_Y = 364f
        private const val PILL_PAD = 4f
        private const val PILL_GAP = 22f
        private const val PILL_INNER_GAP = 7f
        private const val UNIT_GAP = 4f
        private const val PILL_VALUE_SIZE = 26f
        private const val PILL_VALUE_MIN = 18f
        private const val PILL_VALUE_PX_MIN = 22f
        private const val PILL_UNIT_SIZE = 20f
        private const val PILL_UNIT_PX_MIN = MIN_TEXT_PX
        private const val LOOP_DOT_W = 12f
        private const val LOOP_GLYPH_W = 16f
        private const val BALL_AMOUNT_SIZE = 19f
        private const val BALL_SUB_SIZE = MIN_TEXT_PX

        /** Flavour effects (flavor.client / flavor.pumpcontrol): no predictions; pumpcontrol has no loop pill. */
        private val IS_PUMPCONTROL = BuildConfig.FLAVOR == "pumpcontrol"
        private val IS_CLIENT = BuildConfig.FLAVOR.startsWith("aapsclient")
        private const val FOOTER_Y = 406f
        private const val FOOTER_SEP = "  ·  "
        private const val BALL_BOX = 44f
        private const val RING_R = 32f

        // Family colour tokens (designsystem/states.md §3)
        private val BG_CENTRE = 0xFF0D101C.toInt()
        private val BG_MID = 0xFF06070D.toInt()
        private val IN_COL = 0xFF3DDC97.toInt()
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
        private val PRED_COL = 0xFFAA82FF.toInt()
        private val HIST_START = 0xFF5AAAFF.toInt()
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
