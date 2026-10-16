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
import app.aaps.wear.comm.DataLayerListenerServiceWear
import app.aaps.wear.data.ComplicationData
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.BallState
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.BgAge
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Level
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.LoopHealth
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.LoopMode
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.AmbTone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.OutlineStyle
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.PhoneLink
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.PhoneReach
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Pills
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Range
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Refusal
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Sentinel
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Tone
import app.aaps.wear.watchfaces.glass.GlassFaceLogic.Trend
import app.aaps.wear.watchfaces.kusi.BolusTapGesture
import app.aaps.wear.watchfaces.utils.SimpleUi
import app.aaps.wear.watchfaces.utils.WatchFace
import app.aaps.wear.watchfaces.utils.WatchFaceTime
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.cos
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
 * its checks and the watch shows the standard confirmation. Hardened rules: default off, never in ambient,
 * Wear control on, phone reachable with fresh data, amount <= the phone's max bolus, 800 ms window, 5 s
 * cooldown, 30 s pending, duplicate tap events ignored ([GlassFaceLogic.refusal], [GlassFaceLogic.ballState]).
 * The phone must have answered "reachable": refused while the first lookup is pending or failed, looked up
 * again when the ball arms, and SEND needs a "reachable" answer at most 10 s old.
 *
 * Integrator note: the rule set (BOLUS_WINDOW_MS/COOLDOWN_MS/PENDING_MS, refusal, ballState) and the
 * key_kusi_bolus_* keys are Glass's copy until Halo lands; then switch to the shared halo BolusTapGesture /
 * BolusRequestGuard and the halo_bolus_* keys (also in watch_face_configuration_glass.xml) and delete the copy.
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
    private var phoneReach = PhoneReach.UNKNOWN // no warning until the first answer, but no bolus either
    private var reachAnsweredAt = 0L

    // ---------------------------------------------------------------- gesture (hardened rule set)

    private val gesture = BolusTapGesture(clock = { System.currentTimeMillis() }, windowMs = GlassFaceLogic.BOLUS_WINDOW_MS, cooldownMs = GlassFaceLogic.BOLUS_COOLDOWN_MS)
    private var armedAt = 0L
    private var sentAt = 0L
    private var flashUntil = 0L
    private var lastTapEventTime = Long.MIN_VALUE
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
    private var lowCaption = ""
    private var hero = GlassFaceLogic.heroLayout(showChip = true, metaEmpty = false)
    private var outline = GlassFaceLogic.ambientOutline(Range.STALE, lowBit = false)
    private var trend = Trend.NONE
    private var showChip = true
    private var deltaText = "--"
    private var showDelta = true
    private var ageText = "--"
    private var showAge = true
    private var ageLevel = Level.ALERT
    private var metaX = 312f
    private var metaSize = 18f
    private var metaDeltaW = 0f
    private var metaSepW = 0f
    private var link = PhoneLink.OK
    private var owner: GlassFaceLogic.Warning? = null
    private var pills = Pills.BOTH
    private var predictionsOn = true
    private var firstStart = true

    private var loopHealth = LoopHealth.NEVER
    private var loopMode = LoopMode.UNKNOWN
    private var loopText = "--"
    private var loopTextAmbient = "--"
    private var loopLabel = "LOOP"
    private var loopLabelShown = true
    private var loopValueColour = TXT3
    private var loopDotFilled = false
    private var iobText = "--"
    private var iobColour = TXT3
    private var iobLabelShown = true

    private var footer: List<GlassFaceLogic.Run> = emptyList()
    private var footerAmbient: List<GlassFaceLogic.Run> = emptyList()
    private val footerX = FloatArray(12)
    private val footerAmbientX = FloatArray(12)
    private var footerSize = 18f
    private var footerAmbientSize = 18f

    private var ball = BallState.HIDDEN
    private var refusal = Refusal.NONE
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
        shader = LinearGradient(0f, 0f, 0f, 450f, intArrayOf(BG_TOP, BG_MID, BG_BOT), floatArrayOf(0f, 248f / 450f, 1f), Shader.TileMode.CLAMP)
    }
    private val ballBitmaps = arrayOfNulls<Bitmap>(3) // idle, armed, plain (disabled)

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        sp.putInt(R.string.key_last_selected_watchface, SelectedWatchFace.GLASS.ordinal)
        rxBus.send(EventUpdateSelectedWatchface())
        simpleUi.onCreate { dirty = true; invalidate() }

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

        refreshPhoneReachable()
        rxBus.send(EventWearToMobile(ActionResendData("GlassWatchface::onCreate")))
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
                burnX = GlassFaceLogic.burnInOffset(Math.random())
                burnY = GlassFaceLogic.burnInOffset(Math.random())
            }
        }
    }

    /**
     * CapabilityClient FILTER_REACHABLE (contract gap G7: no "phone connected" flag); answers asynchronously.
     * A failed lookup counts as UNREACHABLE. Every answer is time-stamped so SEND can demand a recent one.
     */
    private fun refreshPhoneReachable() {
        try {
            Wearable.getCapabilityClient(this)
                .getCapability(DataLayerListenerServiceWear.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info -> onReachAnswer(if (info.nodes.isNotEmpty()) PhoneReach.REACHABLE else PhoneReach.UNREACHABLE) }
                .addOnFailureListener { e ->
                    aapsLogger.error(LTag.WEAR, "GlassWatchface capability lookup failed", e)
                    onReachAnswer(PhoneReach.UNREACHABLE)
                }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "GlassWatchface capability check failed", e)
            onReachAnswer(PhoneReach.UNREACHABLE)
        }
    }

    private fun onReachAnswer(reach: PhoneReach) {
        reachAnsweredAt = System.currentTimeMillis()
        if (reach != phoneReach) {
            phoneReach = reach
            if (reach != PhoneReach.REACHABLE && gesture.isArmed) disarm()
            dirty = true
            invalidate()
        }
    }

    // ---------------------------------------------------------------- preferences

    private fun bolusPref(): Boolean = sp.getBoolean(R.string.key_kusi_bolus_double_tap, false)
    private fun bolusAmountRaw(): String = sp.getString(R.string.key_kusi_bolus_amount, "0.5")
    private fun bolusAmount(): Double = BolusTapGesture.parseAmount(bolusAmountRaw())
    private fun maxBolus(): Double = sp.getDouble(R.string.key_treatments_safety_max_bolus, 0.0)

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
        timePaint.textSize = 42f
        val timeW = timePaint.measureText(timeText)
        setText(tf500, 17f)
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
        firstStart = GlassFaceLogic.firstStart(bg.timeStamp, st.openApsStatus, st.iobSum)
        link = GlassFaceLogic.phoneLink(phoneReach, data.lastUpdateTimestamp, now)
        val age = GlassFaceLogic.ageMinutes(bg.timeStamp, now)
        bgAge = GlassFaceLogic.bgAge(age)
        stale = GlassFaceLogic.isStale(bgAge)
        strike = GlassFaceLogic.strikeThrough(bgAge)
        trend = GlassFaceLogic.trend(bg.slopeArrow, bg.deltaMgdl)
        range = GlassFaceLogic.range(bg.sgv, bg.low, bg.high, bgAge, GlassFaceLogic.fastFall(trend, bg.deltaMgdl))
        rangeColour = colourOf(range)
        sentinel = GlassFaceLogic.sentinel(bg.sgv, bgAge)
        lowCaption = if (sentinel == Sentinel.LOW) getString(R.string.glass_low_caption) else ""
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

        // hero: chip on the right, or removed with BG and meta centred (trend.hidden_pref)
        showChip = sp.getBoolean(R.string.key_show_direction, true)
        hero = GlassFaceLogic.heroLayout(showChip, metaEmpty = !showDelta && !showAge)
        bgText = GlassFaceLogic.bgText(bg.sgvString, age, sentinel)
        bgColour = when {
            stale                        -> TXT2
            range == Range.URGENT_LOW    -> LOW_TEXT_COL
            else                         -> Color.WHITE
        }
        setText(tf600, GlassFaceLogic.BG_SIZE)
        bgSize = GlassFaceLogic.bgFitSize({ size -> text.textSize = size; text.measureText(bgText) }, hero.bgMaxWidth)
        text.textSize = bgSize
        bgHalfWidth = text.measureText(bgText) / 2f
        if (stale) trend = Trend.NONE
        layoutMeta()

        // loop / IOB
        val loopAge = GlassFaceLogic.loopAgeMinutes(st.openApsStatus, now)
        val run = GlassFaceLogic.loopRun(st.externalStatus)
        val noProfile = GlassFaceLogic.noProfile(run, st.iobSum, firstStart)
        val (states, listTs) = loopStates()
        loopMode = GlassFaceLogic.loopMode(states, listTs, data.lastUpdateTimestamp)
        loopHealth = if (firstStart) LoopHealth.NEVER else GlassFaceLogic.loopHealth(loopAge, run, loopMode)
        when (loopHealth) {
            LoopHealth.OPEN  -> { loopText = getString(R.string.glass_loop_open); loopValueColour = TXT2 }
            LoopHealth.OFF   -> { loopText = getString(R.string.glass_loop_off_short); loopValueColour = WARN_COL }
            LoopHealth.NEVER -> { loopText = "--"; loopValueColour = TXT3 }
            else             -> {
                loopText = GlassFaceLogic.loopText(loopAge, old, lowBit = false)
                loopValueColour = when (loopHealth) {
                    LoopHealth.WARN    -> WARN_COL
                    LoopHealth.NEUTRAL -> TXT2
                    else               -> TXT1 // OK, and BAD: the footer carries the alert (rule 3.3)
                }
            }
        }
        loopTextAmbient = if (loopHealth == LoopHealth.BAD && !loopText.endsWith("!")) "$loopText!" else loopText
        loopLabel = if (loopMode == LoopMode.LGS && loopHealth in setOf(LoopHealth.OK, LoopHealth.WARN, LoopHealth.BAD)) "LGS" else "LOOP"
        iobText = if (firstStart || noProfile) "--" else GlassFaceLogic.display(st.iobSum)
        iobColour = toneColour(if (iobText == "--") Tone.TXT3 else GlassFaceLogic.iobTone(st.iobSum))
        pills = GlassFaceLogic.pills(sp.getBoolean(R.string.key_show_iob, true), pumpControl = IS_PUMPCONTROL)
        loopX0 = if (pills == Pills.LOOP_ONLY) CENTRE_PILL_X0 else LOOP_X0
        iobX0 = if (pills == Pills.IOB_ONLY) CENTRE_PILL_X0 else IOB_X0
        setText(tf600, 20f)
        val loopRight = loopX0 + 33f + text.measureText(loopTextAmbient)
        val iobRight = iobX0 + 16f + text.measureText(iobText)
        setText(tf600, 12f)
        text.letterSpacing = 0.05f
        loopLabelShown = GlassFaceLogic.loopLabelAllowed(loopHealth) && loopRight + 6f <= loopX0 + PILL_W - 14f - text.measureText(loopLabel)
        iobLabelShown = iobRight + 6f <= iobX0 + PILL_W - 14f - text.measureText("IOB")

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
            showCob = sp.getBoolean(R.string.key_show_cob, true),
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
        buildSparkline(bg.timeStamp, bg.sgv)

        // ball
        val amount = bolusAmount()
        refusal = GlassFaceLogic.refusal(link, bgAge, amount, maxBolus(), phoneReach)
        updateBall(now)
        amountText = GlassFaceLogic.bolusAmountText(amount, locale)
    }

    private fun amountAllowed(): Boolean {
        val raw = bolusAmountRaw().trim().replace(',', '.').toDoubleOrNull() ?: return true // unset: default 0.5
        return kotlin.math.abs(BolusTapGesture.parseAmount(raw.toString()) - raw) < 1e-6
    }

    private fun updateBall(now: Long) {
        ball = GlassFaceLogic.ballState(
            prefOn = bolusPref(), ambient = isAmbient, headless = false,
            wearControl = preferences.get(BooleanKey.WearControl), amountAllowed = amountAllowed(), firstStart = firstStart,
            refusal = refusal, armed = gesture.isArmed, sentAt = sentAt, now = now
        )
        if (ball != BallState.ARMED && gesture.isArmed) disarm()
    }

    private fun layoutMeta() {
        setText(tf500, 18f)
        metaDeltaW = if (showDelta) text.measureText(deltaText) else 0f
        metaSepW = if (showDelta && showAge) text.measureText(META_SEP) else 0f
        var total = metaDeltaW + metaSepW + if (showAge) text.measureText(ageText) else 0f
        metaSize = GlassFaceLogic.fitSize(18f, total, if (showChip) META_MAX_WIDTH else META_MAX_WIDTH_NO_CHIP)
        val k = metaSize / 18f
        metaDeltaW *= k
        metaSepW *= k
        total *= k
        metaWidth = total
        // centred on the chip (the right edge stays inside the card), or on the card without the chip
        metaX = if (showChip) min(hero.metaCentreX - total / 2f, META_RIGHT - total) else hero.metaCentreX - total / 2f
    }

    private var metaWidth = 0f
    private var loopX0 = LOOP_X0
    private var iobX0 = IOB_X0

    private var lastFooterSize = 18f

    /** Footer runs at their x positions: shrunk to fit 257 px, 16 px floor, then the owner text ellipsised (§4.8). */
    private fun layoutFooter(input: GlassFaceLogic.FooterInput, xs: FloatArray): List<GlassFaceLogic.Run> {
        setText(tf500, GlassFaceLogic.FOOTER_SIZE)
        val measure18: (String) -> Float = { text.measureText(it) }
        val fit = GlassFaceLogic.fitFooter(GlassFaceLogic.footerRuns(input, slotWords, FOOTER_SEP, measure18), FOOTER_SEP, measure18)
        val runs = fit.runs
        lastFooterSize = fit.size
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
        history.reset()
        hasCurrent = false
        var lastTs = Long.MIN_VALUE
        var started = false
        var prevTs = Long.MIN_VALUE
        for (e in data.graphData.entries.sortedBy { it.timeStamp }) {
            if (e.sgv <= 0.0 || e.timeStamp > anchor) continue
            val slot = GlassFaceLogic.slotOf(e.timeStamp, anchor) ?: continue
            val x = GlassFaceLogic.sparkX(slot)
            val y = GlassFaceLogic.sparkY(e.sgv)
            // graph.gaps: break the line, never interpolate
            if (!started || e.timeStamp - prevTs > GlassFaceLogic.GAP_MS) history.moveTo(x, y) else history.lineTo(x, y)
            started = true
            prevTs = e.timeStamp
            curX = x; curY = y; lastTs = e.timeStamp
        }
        if (bgTs > 0L && bgMgdl > 0.0 && bgTs > lastTs) {
            GlassFaceLogic.slotOf(bgTs, anchor)?.let { slot ->
                val x = GlassFaceLogic.sparkX(slot)
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
        val preds = data.treatmentData.predictions
        if (preds.isEmpty() || !predictionsOn) return
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
        else if (ball == BallState.SENT && now - sentAt >= GlassFaceLogic.BOLUS_PENDING_MS) updateBall(now)
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

    /** Time (tabular digits, own paint), 12 h suffix, and the date unless key_show_date is off (time then at y=74). */
    private fun drawTime(canvas: Canvas, tf: Typeface, colour: Int, suffixColour: Int, dateColour: Int, aa: Boolean) {
        timePaint.typeface = tf
        timePaint.textSize = 42f
        timePaint.isAntiAlias = aa
        timePaint.color = colour
        val fm = timePaint.fontMetrics
        val y = timeY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(timeText, timeX, y, timePaint)
        amPmText?.let {
            setText(tf500, 17f, aa)
            text.color = suffixColour
            canvas.drawText(it, amPmX, y, text)
        }
        if (showDate) drawCentred(canvas, dateText, CX, DATE_Y, tf500, 18f, dateColour, aa)
    }

    private fun drawInteractive(canvas: Canvas, now: Long) {
        drawTime(canvas, tf400, TXT1, TXT2, TXT2, aa = true)

        // hero BG
        setText(tf600, bgSize, align = Paint.Align.CENTER)
        text.color = bgColour
        if (!stale) text.setShadowLayer(12f, 0f, 0f, withAlpha(rangeColour, 0x96))
        canvas.drawText(bgText, hero.bgX, baseline(hero.bgY), text)
        if (strike && bgText != "---") {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE; line.strokeWidth = 3f; line.color = TXT2
            canvas.drawLine(hero.bgX - bgHalfWidth - 4f, hero.bgY + 2f, hero.bgX + bgHalfWidth + 4f, hero.bgY + 2f, line)
        }
        if (sentinel == Sentinel.LOW) drawCentred(canvas, lowCaption, hero.bgX, CAPTION_Y, tf600, 13f, LOW_COL)

        // trend chip
        if (showChip) {
            fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(rangeColour, 40)
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, fill)
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 2f; stroke.color = withAlpha(rangeColour, 210)
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, stroke)
            drawArrow(canvas, rangeColour, aa = true, maxWidth = 3.4f)
        }

        // meta line + phone-off glyph (W10)
        drawMeta(canvas, DELTA_COL, TXT3, when (ageLevel) {
            Level.NORMAL -> TXT2
            Level.WARN   -> WARN_COL
            Level.ALERT  -> ALERT_COL
        }, aa = true)
        if (link == PhoneLink.LOST_FRESH) {
            if (showChip) drawPhoneOff(canvas, CHIP_X, PHONE_OFF_Y, TXT2)
            else drawPhoneOff(canvas, min(hero.metaCentreX + metaWidth / 2f + 16f, HERO_X1 - 20f), hero.metaY, TXT2)
        }

        // sparkline
        fill.reset(); fill.isAntiAlias = true; fill.color = withAlpha(rangeColour, 24)
        rect.set(GlassFaceLogic.SPARK_X0, highY, GlassFaceLogic.SPARK_X1, lowY)
        canvas.drawRoundRect(rect, 8f, 8f, fill)
        drawSparkGuides(canvas, withAlpha(Color.WHITE, 46), withAlpha(Color.WHITE, 60), aa = true)
        if (hasHistory) {
            line.reset(); line.isAntiAlias = true; line.style = Paint.Style.STROKE
            line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND; line.strokeWidth = 2.5f
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

        // pills
        if (pills != Pills.IOB_ONLY) drawLoopDot(canvas, ambient = false, lowBit = false)
        drawPillTexts(canvas, loopValueColour, LABEL_COL, loopText, aa = true, iobValueColour = iobColour)

        // footer = status slot
        drawFooter(canvas, footer, footerX, footerSize, ambient = false, aa = true)

        // bolus ball
        if (ball != BallState.HIDDEN) drawBall(canvas, now)
    }

    /** Face-drawn trend glyph (no font arrows): a shaft with one (normal) or two (fast) chevrons. */
    private fun drawArrow(canvas: Canvas, colour: Int, aa: Boolean, maxWidth: Float) {
        if (trend == Trend.NONE) return
        val triple = trend == Trend.UP3 || trend == Trend.DOWN3
        line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND
        line.strokeWidth = min(if (triple) 3.4f else 2.6f, maxWidth)
        line.color = colour
        val cy = hero.chipY
        canvas.save()
        canvas.rotate(-trend.angle, CHIP_X, cy)
        canvas.drawLine(CHIP_X - ARROW_L, cy, CHIP_X + ARROW_L, cy, line)
        drawChevron(canvas, CHIP_X + ARROW_L, cy)
        if (trend.double) drawChevron(canvas, CHIP_X + ARROW_L - 7f, cy)
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas, tipX: Float, cy: Float) {
        canvas.drawLine(tipX, cy, tipX - HEAD_DX, cy - HEAD_DY, line)
        canvas.drawLine(tipX, cy, tipX - HEAD_DX, cy + HEAD_DY, line)
    }

    private fun drawPhoneOff(canvas: Canvas, cx: Float, cy: Float, colour: Int) {
        stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1.3f; stroke.color = colour
        rect.set(cx - 4f, cy - 7f, cx + 4f, cy + 7f)
        canvas.drawRoundRect(rect, 1.6f, 1.6f, stroke)
        canvas.drawLine(cx - 7f, cy + 8f, cx + 7f, cy - 8f, stroke)
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

    /**
     * Loop dot (states.md loop.*): OK/WARN filled with glow; BAD hollow red (it yields to the slot text);
     * NEVER/NEUTRAL hollow grey; OPEN hollow green; OFF hollow amber with a pause bar.
     * Ambient: no hue, thin a2 ring, or a 2 px a1 ring for WARN/BAD/OFF; low-bit 1 px white.
     */
    private fun drawLoopDot(canvas: Canvas, ambient: Boolean, lowBit: Boolean) {
        val cx = loopX0 + 20f
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
            canvas.drawCircle(cx, cy, 5f, fill)
            fill.clearShadowLayer()
        } else canvas.drawCircle(cx, cy, 5f, stroke)
        if (loopHealth == LoopHealth.OFF) {
            stroke.strokeWidth = 1.4f
            canvas.drawLine(cx - 1.6f, cy - 2.5f, cx - 1.6f, cy + 2.5f, stroke)
            canvas.drawLine(cx + 1.6f, cy - 2.5f, cx + 1.6f, cy + 2.5f, stroke)
        }
    }

    private fun drawPillTexts(canvas: Canvas, loopValueColour: Int, labelColour: Int, loopValue: String, aa: Boolean, iobValueColour: Int, showLabels: Boolean = true) {
        val loopOn = pills != Pills.IOB_ONLY
        val iobOn = pills != Pills.LOOP_ONLY
        setText(tf600, 20f, aa)
        val y = baseline(PILL_Y)
        if (loopOn) {
            text.color = loopValueColour
            canvas.drawText(loopValue, loopX0 + 33f, y, text)
        }
        if (iobOn) {
            text.color = iobValueColour
            canvas.drawText(iobText, iobX0 + 16f, y, text)
        }
        if (!showLabels) return
        setText(tf600, 12f, aa, Paint.Align.RIGHT)
        text.letterSpacing = 0.05f
        text.color = labelColour
        val ly = baseline(PILL_Y + 1f)
        if (loopOn && loopLabelShown) canvas.drawText(loopLabel, loopX0 + PILL_W - 14f, ly, text)
        if (iobOn && iobLabelShown) canvas.drawText("IOB", iobX0 + PILL_W - 14f, ly, text)
    }

    private fun drawFooter(canvas: Canvas, runs: List<GlassFaceLogic.Run>, xs: FloatArray, size: Float, ambient: Boolean, aa: Boolean) {
        setText(tf500, size, aa)
        val y = baseline(FOOTER_Y)
        val lowBit = ambient && lowBitAmbient
        runs.forEachIndexed { i, run ->
            if (i >= xs.size) return@forEachIndexed
            if (i > 0) {
                text.color = if (lowBit) Color.WHITE else if (ambient) A_TXT3 else TXT3
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

    private fun drawBall(canvas: Canvas, now: Long) {
        val armed = ball == BallState.ARMED
        val variant = when (ball) {
            BallState.ARMED    -> 1
            BallState.DISABLED -> 2
            else               -> 0
        }
        val bmp = ballBitmap(variant)
        bitmapPaint.alpha = when (ball) {
            BallState.DISABLED -> 102
            BallState.SENT     -> 140
            else               -> 255
        }
        canvas.save()
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(bmp, (GlassFaceLogic.BALL_X - BALL_BOX) * scale, (GlassFaceLogic.BALL_Y - BALL_BOX) * scale, bitmapPaint)
        canvas.restore()
        bitmapPaint.alpha = 255
        if (armed) {
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2f; stroke.color = withAlpha(Color.WHITE, 60)
            canvas.drawCircle(GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y, RING_R, stroke)
            stroke.strokeWidth = 3f; stroke.color = Color.WHITE; stroke.strokeCap = Paint.Cap.ROUND
            rect.set(GlassFaceLogic.BALL_X - RING_R, GlassFaceLogic.BALL_Y - RING_R, GlassFaceLogic.BALL_X + RING_R, GlassFaceLogic.BALL_Y + RING_R)
            canvas.drawArc(rect, -90f, 360f * GlassFaceLogic.armProgress(armedAt, now, gesture.windowMs), false, stroke)
        }
        if (now < flashUntil) {
            stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 3f; stroke.color = Color.WHITE
            canvas.drawCircle(GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y, GlassFaceLogic.BALL_R, stroke)
        }
        val disabled = ball == BallState.DISABLED
        val textColour = if (disabled) TXT3 else if (ball == BallState.SENT) withAlpha(Color.WHITE, 191) else Color.WHITE
        drawCentred(canvas, amountText, GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y - 3f, tf700, 18f, textColour)
        if (ball == BallState.SENT) drawCentred(canvas, "…", GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y + 14f, tf700, 14f, BALL_UNIT_COL)
        else drawCentred(canvas, "U", GlassFaceLogic.BALL_X, GlassFaceLogic.BALL_Y + 13f, tf600, 12f, if (disabled) TXT3 else BALL_UNIT_COL)
    }

    private fun drawAmbient(canvas: Canvas) {
        val lowBit = lowBitAmbient
        val burnIn = burnInProtection
        val aa = !lowBit
        fun c(colour: Int) = if (lowBit) Color.WHITE else colour

        drawTime(canvas, tf200, c(A_TXT1), c(A_TXT2), c(A_TXT2), aa)

        // hero card outline: the range as a shape (§9; colour is never the only carrier)
        drawAmbientOutline(canvas, lowBit)
        // BG digits: weight 400 (urgent low 600); outlined 1,5 px under burn-in protection (no filled area > 4x4 px)
        setText(if (outline.boldBg) tf600 else tf400, bgSize, aa, Paint.Align.CENTER)
        text.color = c(A_TXT1)
        if (burnIn) {
            text.style = Paint.Style.STROKE
            text.strokeWidth = 1.5f
        }
        canvas.drawText(bgText, hero.bgX, baseline(hero.bgY), text)
        if (strike && bgText != "---") {
            line.reset(); line.isAntiAlias = aa; line.style = Paint.Style.STROKE; line.strokeWidth = 2f; line.color = c(A_TXT1)
            canvas.drawLine(hero.bgX - bgHalfWidth - 4f, hero.bgY + 2f, hero.bgX + bgHalfWidth + 4f, hero.bgY + 2f, line)
        }
        if (sentinel == Sentinel.LOW) drawCentred(canvas, lowCaption, hero.bgX, CAPTION_Y, tf600, 13f, c(A_TXT1), aa)
        if (showChip) {
            stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1f; stroke.color = c(A_TXT3)
            canvas.drawCircle(CHIP_X, hero.chipY, CHIP_R, stroke)
            drawArrow(canvas, c(A_TXT1), aa, maxWidth = 2f)
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

        // pills
        if (!lowBit) {
            stroke.reset(); stroke.isAntiAlias = aa; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1f; stroke.color = A_LINE
            if (pills != Pills.IOB_ONLY) {
                rect.set(loopX0, PILL_Y0, loopX0 + PILL_W, PILL_Y1)
                canvas.drawRoundRect(rect, PILL_R, PILL_R, stroke)
            }
            if (pills != Pills.LOOP_ONLY) {
                rect.set(iobX0, PILL_Y0, iobX0 + PILL_W, PILL_Y1)
                canvas.drawRoundRect(rect, PILL_R, PILL_R, stroke)
            }
        }
        if (pills != Pills.IOB_ONLY) drawLoopDot(canvas, ambient = true, lowBit = lowBit)
        drawPillTexts(canvas, c(A_TXT1), c(A_TXT3), if (lowBit || loopHealth == LoopHealth.BAD) loopTextAmbient else loopText, aa, c(A_TXT1), showLabels = !lowBit)

        drawFooter(canvas, footerAmbient, footerAmbientX, footerAmbientSize, ambient = true, aa = aa)
    }

    /** Hero card outline in ambient by range: solid, dashed 6/5, or double (5 px apart); white with AA off in low-bit. */
    private fun drawAmbientOutline(canvas: Canvas, lowBit: Boolean) {
        val o = outline
        if (o.style == OutlineStyle.NONE) return
        stroke.reset(); stroke.isAntiAlias = !lowBit; stroke.style = Paint.Style.STROKE
        stroke.color = if (lowBit) Color.WHITE else when (o.tone) {
            AmbTone.LINE -> A_LINE
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

    private fun historyShader(r: Range): Shader =
        historyShaders[r.ordinal] ?: LinearGradient(
            GlassFaceLogic.SPARK_X0, 0f, GlassFaceLogic.nowX, 0f, if (r == Range.STALE) STALE_COL else HIST_START, colourOf(r), Shader.TileMode.CLAMP
        ).also { historyShaders[r.ordinal] = it }

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
        val p = pills
        val key = "$size/$p"
        if (key == bakedKey || key == bakingKey) return
        bakingKey = key
        watchfaceScope.launch {
            val set: Array<Bitmap?> = try {
                withContext(Dispatchers.Default) { Array(Range.entries.size) { bakeBackground(Range.entries[it], size, p) } }
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

    /** Gradient, aurora (range blob + core, violet, blue), dial rim and the glass chrome of the hero card and pills. */
    private fun bakeBackground(r: Range, size: Int, pills: Pills): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 450f, size / 450f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 450f, intArrayOf(BG_TOP, BG_MID, BG_BOT), floatArrayOf(0f, 248f / 450f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 450f, 450f, p)
        p.shader = null
        val colour = colourOf(r)
        // emphasis step (very high, urgent low): aurora alpha +20 %
        val k = if (r == Range.VERY_HIGH || r == Range.URGENT_LOW) 1.2f else 1f
        blob(c, p, 130f, 100f, 320f, 250f, withAlpha(colour, (150 * k).toInt()), 40f)
        blob(c, p, 150f, 120f, 260f, 220f, withAlpha(colour, (120 * k).toInt()), 26f)
        blob(c, p, 240f, 220f, 410f, 340f, withAlpha(0xFF5A46C8.toInt(), 85), 44f)
        blob(c, p, 40f, 230f, 180f, 330f, withAlpha(0xFF2A5AC8.toInt(), 60), 44f)
        p.maskFilter = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, 22)
        c.drawCircle(CX, CX, 225f - 3f, p)
        glass(c, p, HERO_X0, HERO_Y0, HERO_X1, HERO_Y1, HERO_R, 35, 11, 60, 100)
        if (pills != Pills.IOB_ONLY) {
            val x0 = if (pills == Pills.LOOP_ONLY) CENTRE_PILL_X0 else LOOP_X0
            glass(c, p, x0, PILL_Y0, x0 + PILL_W, PILL_Y1, PILL_R, 29, 9, 55, 95)
        }
        if (pills != Pills.LOOP_ONLY) {
            val x0 = if (pills == Pills.IOB_ONLY) CENTRE_PILL_X0 else IOB_X0
            glass(c, p, x0, PILL_Y0, x0 + PILL_W, PILL_Y1, PILL_R, 29, 9, 55, 95)
        }
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
     * [variant] 0 idle (halo + highlight), 1 armed (brighter, white halo), 2 plain (disabled: no glow, no highlight).
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
        if (variant != 2) {
            p.color = if (variant == 1) withAlpha(Color.WHITE, 70) else withAlpha(BALL_COL, 100)
            p.maskFilter = BlurMaskFilter(7f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(cx, cx, 32f, p)
            p.maskFilter = null
        }
        val lo = if (variant == 1) 0xFF3A40C8.toInt() else 0xFF2C3290.toInt()
        val hi = if (variant == 1) 0xFF8C93FF.toInt() else 0xFFA3ACFF.toInt()
        p.shader = RadialGradient(cx - 4f, cx - 8f, r + 8f, hi, lo, Shader.TileMode.CLAMP)
        p.alpha = 235
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.alpha = 255
        if (variant != 2) {
            p.color = withAlpha(Color.WHITE, 70)
            p.maskFilter = BlurMaskFilter(3f * 1.73f, BlurMaskFilter.Blur.NORMAL)
            c.drawOval(cx - 17f, cx - 26f, cx + 5f, cx - 13f, p)
            p.maskFilter = null
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = withAlpha(Color.WHITE, 150)
        c.drawCircle(cx, cx, r, p)
        ballBitmaps[variant] = bmp
        return bmp
    }

    // ---------------------------------------------------------------- taps

    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        if (tapType != TAP_UP || isAmbient) return // tap.ambient: no hit testing at all
        if (eventTime == lastTapEventTime) return // duplicate delivery of the same tap
        lastTapEventTime = eventTime
        val size = min(faceRect.width(), faceRect.height())
        val s = size / 450f
        if (s <= 0f) return
        val dx = (x - (faceRect.width() - size) / 2f) / s
        val dy = (y - (faceRect.height() - size) / 2f) / s

        val now = System.currentTimeMillis()
        updateState(now) // fresh verdict (phone link, data age, max bolus) at the moment of the tap
        val inside = ball != BallState.HIDDEN && GlassFaceLogic.ballHit(dx, dy)
        if (inside && ball == BallState.SENT) {
            toast(R.string.glass_bolus_pending)
            return
        }
        if (inside && ball == BallState.DISABLED) {
            disarm()
            toast(
                when (refusal) {
                    Refusal.NO_PHONE   -> R.string.glass_bolus_refused_no_phone
                    Refusal.STALE_DATA -> R.string.glass_bolus_refused_stale
                    else               -> R.string.glass_bolus_refused_max
                }
            )
            invalidate()
            return
        }
        val enabled = ball == BallState.IDLE || ball == BallState.ARMED
        when (gesture.onTap(inside, enabled)) {
            BolusTapGesture.Decision.ARM    -> {
                refreshPhoneReachable() // fresh answer for the SEND gate, well inside the window
                armedAt = now
                ball = BallState.ARMED
                vibrate()
                handler.removeCallbacks(armedTick)
                handler.post(armedTick)
                return
            }

            BolusTapGesture.Decision.SEND   -> {
                handler.removeCallbacks(armedTick)
                if (!GlassFaceLogic.reachConfirmed(phoneReach, reachAnsweredAt, now)) {
                    // no "reachable" answer in the last 10 s: never send on the minute-tick verdict
                    disarm()
                    refreshPhoneReachable()
                    toast(R.string.glass_bolus_refused_no_phone)
                    dirty = true
                    invalidate()
                    return
                }
                val amount = bolusAmount()
                aapsLogger.info(LTag.WEAR, "GlassWatchface: double-tap bolus request $amount U")
                // Request only, exactly as BolusActivity: the phone checks it and the watch shows the confirmation.
                rxBus.send(EventWearToMobile(EventData.ActionBolusPreCheck(amount, 0)))
                vibrate()
                sentAt = now
                flashUntil = now + FLASH_MS
                ball = BallState.SENT
                handler.postDelayed(redraw, FLASH_MS + 10)
                handler.postDelayed(redraw, GlassFaceLogic.BOLUS_PENDING_MS + 10)
                invalidate()
                return
            }

            BolusTapGesture.Decision.DISARM -> {
                handler.removeCallbacks(armedTick)
                dirty = true
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
        private const val TIME_Y = 58f
        private const val TIME_Y_NO_DATE = 74f
        private const val AM_PM_GAP = 4f
        private const val DATE_Y = 92f
        private const val HERO_X0 = 92f
        private const val HERO_Y0 = 110f
        private const val HERO_X1 = 358f
        private const val HERO_Y1 = 240f
        private const val HERO_R = 30f
        private const val CAPTION_Y = 224f
        private const val CHIP_X = 312f
        private const val CHIP_R = 22f
        private const val ARROW_L = 10f
        private val HEAD_DX = (8.0 * cos(Math.toRadians(42.0))).toFloat()
        private val HEAD_DY = (8.0 * sin(Math.toRadians(42.0))).toFloat()
        private const val META_RIGHT = 350f
        private const val META_MAX_WIDTH = 85f
        private const val META_MAX_WIDTH_NO_CHIP = 230f
        private const val META_SEP = " · "
        private const val PHONE_OFF_Y = 222f
        private const val PILL_Y0 = 324f
        private const val PILL_Y1 = 366f
        private const val PILL_Y = 345f
        private const val PILL_R = 21f
        private const val LOOP_X0 = 106f
        private const val IOB_X0 = 231f
        private const val PILL_W = 113f
        private const val CENTRE_PILL_X0 = 169f

        /** Flavour effects (flavor.client / flavor.pumpcontrol): no predictions; pumpcontrol has no loop pill. */
        private val IS_PUMPCONTROL = BuildConfig.FLAVOR == "pumpcontrol"
        private val IS_CLIENT = BuildConfig.FLAVOR.startsWith("aapsclient")
        private const val FOOTER_Y = 390f
        private const val FOOTER_SEP = "  ·  "
        private const val BALL_BOX = 46f
        private const val RING_R = 34f

        // Family colour tokens (designsystem/states.md §3)
        private val BG_TOP = 0xFF141A33.toInt()
        private val BG_MID = 0xFF0B0F22.toInt()
        private val BG_BOT = 0xFF04050B.toInt()
        private val IN_COL = 0xFF3DDC97.toInt()
        private val HIGH_COL = 0xFFFFB547.toInt()
        private val LOW_COL = 0xFFFF5A6E.toInt()
        private val LOW_TEXT_COL = 0xFFFFD6DC.toInt()
        private val STALE_COL = 0xFF8A93A8.toInt()
        private val TXT1 = 0xFFEEF1F7.toInt()
        private val TXT2 = 0xFF9AA6C4.toInt()
        private val TXT3 = 0xFF6F7A99.toInt()
        private val LABEL_COL = 0xFF8E99B8.toInt()
        private val DELTA_COL = 0xFFC8D2E6.toInt()
        private val WARN_COL = 0xFFFFB547.toInt()
        private val ALERT_COL = 0xFFFF5A6E.toInt()
        private val INS_COL = 0xFF5AC8FA.toInt()
        private val TBR_COL = 0xFF3DDBD9.toInt()
        private val TT_COL = 0xFFB388FF.toInt()
        private val LOOP_OK = 0xFF40D68C.toInt()
        private val PRED_COL = 0xFFAA82FF.toInt()
        private val HIST_START = 0xFF5AAAFF.toInt()
        private val BALL_COL = 0xFF7888FF.toInt()
        private val BALL_UNIT_COL = 0xFFE3E6FF.toInt()
        private val A_TXT1 = 0xFFE0E0E0.toInt()
        private val A_TXT2 = 0xFF9A9A9A.toInt()
        private val A_TXT3 = 0xFF6E6E6E.toInt()
        private val A_LINE = 0xFF3C3C3C.toInt()
        private val A_HIST = 0xFFB4B4B4.toInt()
    }
}
