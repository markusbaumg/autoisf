package app.aaps.ui.widget

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.widget.WidgetLogic.BasalState
import app.aaps.ui.widget.WidgetLogic.BgRange
import app.aaps.ui.widget.WidgetLogic.Freshness
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * Pure (Android free) logic of the lock-screen widget "AAPS Sperrbildschirm" ([LockWidget]), unit tested in LockWidgetLogicTest.
 * Display only: nothing here feeds the loop, dosing or limits. It maps the read-only state to a pill, the hero row and up
 * to three text lines of coloured runs. Thresholds come from [WidgetLogic] (3,9 / 10,0 / 12′ / 30′), so the lock screen,
 * the home widget and the watch never disagree.
 */
object LockWidgetLogic {

    // ---------------------------------------------------------------- size tiers

    enum class Tier { S, M, L }

    /** Per tier: hero (dp), arrow box (dp), meta / line 3 / line 4 (sp), pill padding (dp) and gap to the digits (dp) */
    data class TierSpec(
        val heroDp: Float, val arrowDp: Float, val metaSp: Float, val line3Sp: Float, val line4Sp: Float,
        val pillPadH: Float, val pillPadV: Float, val pillGap: Float
    )

    fun spec(tier: Tier): TierSpec = when (tier) {
        Tier.L -> TierSpec(68f, 36f, 18f, 16f, 15f, 9f, 4f, 10f)
        Tier.M -> TierSpec(56f, 30f, 16f, 15f, 15f, 9f, 3f, 10f)
        Tier.S -> TierSpec(40f, 22f, 14f, 14f, 14f, 7f, 2f, 8f)
    }

    /** L: w >= 220 and h >= 170 dp (LockStar default 359 x 274); M: h 96..169 or w 170..219; S otherwise */
    fun tier(widthDp: Float, heightDp: Float): Tier = when {
        widthDp >= 220f && heightDp >= 170f                       -> Tier.L
        (heightDp >= 96f && heightDp < 170f) || (widthDp >= 170f && widthDp < 220f) -> Tier.M
        else                                                      -> Tier.S
    }

    const val PILL_SP = 13f
    const val PILL_URGENT_SP = 15f

    /** Ring of the urgent pill: drawn this far outside the solid pill (part of the view, so padding grows by it) */
    const val URGENT_RING_DP = 3f

    /** The hero is clamped at this font scale, the secondary texts and pills at [SECONDARY_MAX_FONT_SCALE] */
    const val HERO_MAX_FONT_SCALE = 1.15f
    const val SECONDARY_MAX_FONT_SCALE = 1.3f

    fun heroSizeDp(tier: Tier, fontScale: Float): Float = spec(tier).heroDp * clampScale(fontScale, HERO_MAX_FONT_SCALE)
    fun secondarySizeDp(sp: Float, fontScale: Float): Float = sp * clampScale(fontScale, SECONDARY_MAX_FONT_SCALE)
    private fun clampScale(fontScale: Float, max: Float) = (fontScale.takeIf { it > 0f } ?: 1f).coerceAtMost(max)

    // ---------------------------------------------------------------- palettes

    enum class PaletteKind { LIGHT, INK }

    data class Palette(
        val kind: PaletteKind,
        val inRange: Int, val high: Int, val low: Int, val lowFill: Int, val ins: Int, val tt: Int,
        val txt1: Int, val txt2: Int, val txt3: Int, val noData: Int, val white: Int
    )

    private fun c(rgb: Long): Int = rgb.toInt()

    /** Light type on a dark / mid-tone wallpaper (with text shadow and scrim) */
    val LIGHT = Palette(
        PaletteKind.LIGHT,
        inRange = c(0xFF3DDC97), high = c(0xFFFFB547), low = c(0xFFFF7A88), lowFill = c(0xFFFF5A6E), ins = c(0xFF5AC8FA), tt = c(0xFFB388FF),
        txt1 = c(0xFFF4F6FA), txt2 = c(0xFFDCE2EE), txt3 = c(0xFFB4BED5), noData = c(0x8CDCE2EE), white = c(0xFFFFFFFF)
    )

    /** Ink on a bright wallpaper (no shadow, no scrim) */
    val INK = Palette(
        PaletteKind.INK,
        inRange = c(0xFF0B8F5A), high = c(0xFFB86400), low = c(0xFFD21F3C), lowFill = c(0xFFD21F3C), ins = c(0xFF0071B3), tt = c(0xFF6A3FC8),
        txt1 = c(0xFF14181F), txt2 = c(0xFF3A4354), txt3 = c(0xFF6A7385), noData = c(0x8C3A4354), white = c(0xFFFFFFFF)
    )

    fun palette(kind: PaletteKind): Palette = if (kind == PaletteKind.INK) INK else LIGHT

    /** android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT */
    const val HINT_SUPPORTS_DARK_TEXT = 1

    /**
     * Palette from the wallpaper colour hints: the lock wallpaper's ([lockHints], null when it has no own colours, i.e.
     * the lock screen shows the home wallpaper), else the home wallpaper's ([systemHints]), else light.
     */
    fun paletteKind(lockHints: Int?, systemHints: Int?): PaletteKind {
        val hints = lockHints ?: systemHints ?: return PaletteKind.LIGHT
        return if (hints and HINT_SUPPORTS_DARK_TEXT != 0) PaletteKind.INK else PaletteKind.LIGHT
    }

    /** setImageAlpha of the scrim on a dark wallpaper (about 0x29 effective with the 0x80 asset) and otherwise */
    const val SCRIM_ALPHA_DARK = 82
    const val SCRIM_ALPHA_MID = 255
    const val DARK_WALLPAPER_LUMINANCE = 0.25

    /** Scrim strength from the wallpaper's primary colour (ARGB); unknown -> the stronger one */
    fun scrimAlpha(primaryColor: Int?): Int =
        if (primaryColor != null && luminance(primaryColor) < DARK_WALLPAPER_LUMINANCE) SCRIM_ALPHA_DARK else SCRIM_ALPHA_MID

    /** WCAG relative luminance 0..1 of an (A)RGB colour, alpha ignored */
    fun luminance(rgb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((rgb shr 16) and 0xFF) + 0.7152 * ch((rgb shr 8) and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Stale digits: the last range colour at 60 % */
    const val STALE_ALPHA = 0x99

    // ---------------------------------------------------------------- model

    enum class Pill { TIEF, TIEF_URGENT, HOCH, ALT, NO_DATA }

    enum class Arrow { FLAT, UP45, UP, DOWN45, DOWN, UP2, DOWN2 }

    enum class Weight { REGULAR, MEDIUM }

    data class Run(val text: String, val color: Int, val weight: Weight = Weight.REGULAR)

    data class Model(
        val pill: Pill?,
        val bg: String,
        val bgColor: Int,
        val arrow: Arrow?,
        /** line 2: age · IOB (or the one S line) */
        val meta: List<Run>?,
        val line3: List<Run>?,
        val line4: List<Run>?,
        /** the pill word moved to the start of [meta] (too narrow for a pill), see [fit] */
        val pillInMeta: Boolean = false
    ) {

        fun plain(texts: Texts): String =
            listOfNotNull(
                pill?.let { pillText(it, texts) }, bg, meta?.text(), line3?.text(), line4?.text()
            ).joinToString(", ")
    }

    fun List<Run>.text(): String = joinToString("") { it.text }

    /** Localised texts (format strings use %1$s / %2$s, all arguments are already formatted strings) */
    data class Texts(
        val pillLow: String = "TIEF",
        val pillHigh: String = "HOCH",
        val pillStale: String = "ALT",
        val pillNoData: String = "KEINE DATEN",
        val now: String = "jetzt",
        val ago: String = "vor %1\$s",
        val iobLabel: String = "IOB",
        val insulin: String = "%1\$s U",
        val lastLow: String = "zuletzt tief",
        val lastHigh: String = "zuletzt hoch",
        val lastIn: String = "zuletzt im Ziel",
        val since: String = "seit %1\$s",
        val loopStalledShort: String = "Loop steht",
        val loopLate: String = "Loop seit %1\$s",
        val loopStalled: String = "Loop steht seit %1\$s",
        val loopOff: String = "Loop aus",
        val tempTarget: String = "Temp-Ziel %1\$s · noch %2\$s",
        val tempTargetNoEnd: String = "Temp-Ziel %1\$s",
        val basalLabel: String = "Basal",
        val basalZeroLow: String = "0 U/h · Pause seit %1\$s",
        val basalZero: String = "0 U/h seit %1\$s",
        val basalRate: String = "%1\$s U/h",
        val carbsLabel: String = "KH",
        val carbs: String = "%1\$s g",
        val tirLabel: String = "Im Ziel 24 h",
        val percent: String = "%1\$s %%"
    )

    /** No-break space: a too wide one-line text may only drop whole items (at [SEP]), never cut "1 h 05" or "1,25 U" */
    const val NBSP = '\u00A0'

    /** All spaces of [s] made non-breaking */
    fun nb(s: String): String = s.replace(' ', NBSP)

    /**
     * Glues the spaces next to a placeholder ("seit %1$s", "%1$s U", "%1$s %%") and in "0 U/h", so a value never loses
     * its unit or its "seit"; a space before " · " stays breakable.
     */
    fun glue(pattern: String): String = pattern
        .replace(Regex(" (?=%\\d\\\$s)"), NBSP.toString())
        .replace(Regex("(?<=%\\d\\\$s) (?!·)"), NBSP.toString())
        .replace(Regex("(?<=\\d) (?=U)"), NBSP.toString())

    private fun fmt(pattern: String, vararg args: String): String =
        String.format(java.util.Locale.ROOT, glue(pattern), *args.map { nb(it) }.toTypedArray())

    fun pillText(pill: Pill, t: Texts): String = when (pill) {
        Pill.TIEF, Pill.TIEF_URGENT -> t.pillLow
        Pill.HOCH                   -> t.pillHigh
        Pill.ALT                    -> t.pillStale
        Pill.NO_DATA                -> t.pillNoData
    }

    /** Text colour of a pill: white on the solid ones, the stroke colour on the outline ones */
    fun pillTextColor(pill: Pill, p: Palette): Int = when (pill) {
        Pill.TIEF, Pill.TIEF_URGENT, Pill.NO_DATA -> p.white
        Pill.HOCH                                 -> p.high
        Pill.ALT                                  -> p.txt2
    }

    /** Breakable only before the dot, so a dropped item never leaves a dangling "·" */
    const val SEP = "  ·\u00A0\u00A0"

    /** Urgent low: below 3,0 mmol/L, or falling fast (⇊) below 3,9 */
    const val URGENT_LOW_MGDL = 3.0 * Constants.MMOLL_TO_MGDL

    /** Carbs on board shown from this many grams (smaller amounts are noise) */
    const val COB_MIN_G = 5.0

    /** Loop cue (F12): amber from 15′, red ("steht") from 30′ */
    const val LOOP_LATE_MIN = 15L
    const val LOOP_STALLED_MIN = 30L

    /** The fresh age turns amber and medium from this many minutes (Orbit: after 5 min) */
    const val AGE_WARN_MIN = 6L

    data class Input(
        val now: Long,
        val bgMgdl: Double?,
        val bgTimestamp: Long?,
        /** trend arrow to show (WidgetLogic.trendArrow), null = unknown */
        val arrow: TrendArrow?,
        val mmol: Boolean,
        /** bolus IOB + basal IOB, each rounded as on the home widget */
        val iob: Double,
        val cob: Double?,
        /** APS build: only then a loop cue and a basal line */
        val aps: Boolean,
        /** loop disabled or suspended (by the user, pump, disconnect, DST, super bolus) */
        val loopOff: Boolean = false,
        /** time of the last loop run (loop.lastRun.lastAPSRun), null = unknown (no cue) */
        val lastLoopRun: Long? = null,
        val ttMgdl: Double? = null,
        val ttMinutesLeft: Int? = null,
        val basal: BasalState = BasalState.None,
        val tir24h: Int? = null,
        /** formatted time of the last reading for "seit 17:16" / "seit gestern 23:40", see [sinceDays] */
        val since: String? = null
    )

    fun arrow(trend: TrendArrow?): Arrow? = when (trend) {
        TrendArrow.DOUBLE_DOWN     -> Arrow.DOWN2
        TrendArrow.SINGLE_DOWN     -> Arrow.DOWN
        TrendArrow.FORTY_FIVE_DOWN -> Arrow.DOWN45
        TrendArrow.FLAT            -> Arrow.FLAT
        TrendArrow.FORTY_FIVE_UP   -> Arrow.UP45
        TrendArrow.SINGLE_UP       -> Arrow.UP
        TrendArrow.DOUBLE_UP       -> Arrow.UP2
        else                       -> null
    }

    /** Minutes since the last loop run, null = no cue possible (no APS build or no run known yet) */
    fun loopMinutes(i: Input): Long? = if (i.aps && i.lastLoopRun != null) WidgetLogic.ageMinutes(i.lastLoopRun, i.now) else null

    /** Loop cue item (F12), null while the loop is fine */
    fun loopRuns(i: Input, p: Palette, t: Texts): List<Run>? {
        if (!i.aps) return null
        if (i.loopOff) return listOf(Run(nb(t.loopOff), p.high, Weight.MEDIUM))
        val m = loopMinutes(i) ?: return null
        return when {
            m >= LOOP_STALLED_MIN -> listOf(Run(fmt(t.loopStalled, WidgetLogic.durationShort(m)), p.low, Weight.MEDIUM))
            m >= LOOP_LATE_MIN    -> listOf(Run(fmt(t.loopLate, WidgetLogic.durationShort(m)), p.high, Weight.MEDIUM))
            else                  -> null
        }
    }

    private fun loopStalled(i: Input): Boolean = i.aps && !i.loopOff && (loopMinutes(i) ?: 0L) >= LOOP_STALLED_MIN

    /** Whole local days between the reading and now: 0 = today ("seit 17:16"), 1 = yesterday ("seit gestern 23:40") */
    fun sinceDays(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        ).coerceAtLeast(0)

    /** Maps the state to what the widget shows. First match wins in every slot; see FINAL_SPEC (lock widget). */
    fun build(i: Input, tier: Tier, p: Palette, t: Texts = Texts()): Model {
        val freshness = if (i.bgMgdl == null) Freshness.NO_DATA else WidgetLogic.freshness(i.bgTimestamp, i.now)
        val last = i.bgMgdl?.let { WidgetLogic.lastRange(it) }
        val sep = Run(SEP, p.txt3)
        val iobValue = fmt(t.insulin, WidgetLogic.decimal(i.iob, 2))
        val iobLabel = nb(t.iobLabel) + NBSP
        val iobLive = listOf(Run(iobLabel, p.ins, Weight.MEDIUM), Run(iobValue, p.txt1, Weight.MEDIUM))
        val iobOld = listOf(Run(iobLabel, p.txt2), Run(iobValue, p.txt1, Weight.MEDIUM))
        val lastWord = nb(
            when (last) {
                BgRange.LOW  -> t.lastLow
                BgRange.HIGH -> t.lastHigh
                else         -> t.lastIn
            }
        )
        val loop = loopRuns(i, p, t)

        when (freshness) {
            Freshness.FRESH   -> {
                val bg = i.bgMgdl!!
                val range = WidgetLogic.bgRange(bg, i.bgTimestamp, i.now)
                val color = rangeColor(range, p)
                val pill = when {
                    range == BgRange.LOW && urgent(bg, i.arrow) -> Pill.TIEF_URGENT
                    range == BgRange.LOW                        -> Pill.TIEF
                    range == BgRange.HIGH && tier != Tier.S     -> Pill.HOCH
                    else                                        -> null
                }
                val age = WidgetLogic.ageMinutes(i.bgTimestamp!!, i.now)
                val ageRun = when {
                    age < 1            -> Run(t.now, p.txt2)
                    age < AGE_WARN_MIN -> Run(fmt(t.ago, WidgetLogic.durationShort(age)), p.txt2)
                    else               -> Run(fmt(t.ago, WidgetLogic.durationShort(age)), p.high, Weight.MEDIUM)
                }
                val meta = listOf(ageRun, sep) + iobLive
                val hero = WidgetLogic.glucose(bg, i.mmol)
                if (tier == Tier.S) return Model(pill, hero, color, arrow(i.arrow), loop ?: meta, null, null)
                val line3 = loop ?: tempTargetRuns(i, p, t) ?: basalRuns(i, range, p, t) ?: carbsRuns(i, p, t)
                val line4 = if (tier == Tier.L && range != BgRange.LOW && i.tir24h != null)
                    listOf(Run(nb(t.tirLabel) + NBSP + NBSP, p.txt2), Run(fmt(t.percent, i.tir24h.toString()), p.txt1))
                else null
                return Model(pill, hero, color, arrow(i.arrow), meta, line3, line4)
            }

            Freshness.STALE   -> {
                val bg = i.bgMgdl!!
                val age = WidgetLogic.ageMinutes(i.bgTimestamp!!, i.now)
                // a reading from the future (clock skew, bad CGM time) is stale too, but "vor 0′" would read as fresh
                val future = i.bgTimestamp - i.now > WidgetLogic.FUTURE_TOLERANCE_MS
                val meta =
                    if (future) listOf(Run(lastWord, p.txt1))
                    else listOf(Run(fmt(t.ago, WidgetLogic.durationShort(age)), p.low, Weight.MEDIUM), sep, Run(lastWord, p.txt1))
                val color = withAlpha(rangeColor(last!!, p), STALE_ALPHA)
                val hero = WidgetLogic.glucose(bg, i.mmol)
                if (tier == Tier.S) return Model(Pill.ALT, hero, color, null, meta, null, null)
                // The loop is named only when it is off or stalled: 15..29′ would only repeat the BG age
                val loopItem = if (i.aps && (i.loopOff || loopStalled(i))) loop else null
                return if (loopItem != null) Model(Pill.ALT, hero, color, null, meta, loopItem, iobOld)
                else Model(Pill.ALT, hero, color, null, meta, iobOld, null)
            }

            Freshness.NO_DATA -> {
                val stalled = loopStalled(i)
                val sinceRun = i.since?.let { Run(fmt(t.since, it), p.txt1, Weight.MEDIUM) }
                val lastRuns = if (i.bgMgdl != null && last != null)
                    listOf(
                        Run(lastWord + NBSP, p.txt2),
                        Run(WidgetLogic.glucose(i.bgMgdl, i.mmol), if (last == BgRange.LOW) p.low else p.txt1, Weight.MEDIUM)
                    )
                else null
                if (tier == Tier.S) {
                    val runs = listOfNotNull(sinceRun) + (if (sinceRun != null && lastRuns != null) listOf(sep) else emptyList()) + (lastRuns ?: emptyList())
                    return Model(Pill.NO_DATA, NO_DATA_HERO, p.noData, null, runs.ifEmpty { null }, null, null)
                }
                val metaRuns = listOfNotNull(sinceRun) +
                    if (stalled) (if (sinceRun != null) listOf(sep) else emptyList()) + Run(nb(t.loopStalledShort), p.txt1, Weight.MEDIUM) else emptyList()
                val meta = metaRuns.ifEmpty { null }
                return when {
                    lastRuns == null -> Model(Pill.NO_DATA, NO_DATA_HERO, p.noData, null, meta, iobOld, null)
                    tier == Tier.M   -> Model(Pill.NO_DATA, NO_DATA_HERO, p.noData, null, meta, lastRuns + sep + iobOld, null)
                    else             -> Model(Pill.NO_DATA, NO_DATA_HERO, p.noData, null, meta, lastRuns, iobOld)
                }
            }
        }
    }

    /** "– –" with a thin space */
    const val NO_DATA_HERO = "– –"

    fun urgent(bgMgdl: Double, trend: TrendArrow?): Boolean =
        bgMgdl < URGENT_LOW_MGDL || (bgMgdl < WidgetLogic.LOW_MGDL && (trend == TrendArrow.DOUBLE_DOWN || trend == TrendArrow.TRIPLE_DOWN))

    fun rangeColor(range: BgRange, p: Palette): Int = when (range) {
        BgRange.IN                  -> p.inRange
        BgRange.HIGH                -> p.high
        BgRange.LOW                 -> p.low
        BgRange.STALE, BgRange.NONE -> p.txt2
    }

    private fun tempTargetRuns(i: Input, p: Palette, t: Texts): List<Run>? {
        val tt = i.ttMgdl ?: return null
        val value = WidgetLogic.glucose(tt, i.mmol)
        val text = i.ttMinutesLeft?.let { fmt(t.tempTarget, value, WidgetLogic.durationShort(it.toLong())) } ?: fmt(t.tempTargetNoEnd, value)
        return listOf(Run(text, p.tt))
    }

    /** Unusual basal only: a zero temp (TXT2 during a fresh low, F18, otherwise amber) or a high temp. Display only. */
    private fun basalRuns(i: Input, range: BgRange, p: Palette, t: Texts): List<Run>? {
        if (!i.aps) return null
        val label = Run(nb(t.basalLabel) + NBSP, p.txt2)
        return when (val b = i.basal) {
            is BasalState.Zero -> {
                val since = WidgetLogic.durationShort(b.sinceMinutes, b.capped)
                if (range == BgRange.LOW) listOf(label, Run(fmt(t.basalZeroLow, since), p.txt2))
                else listOf(label, Run(fmt(t.basalZero, since), p.high))
            }

            is BasalState.High -> listOf(label, Run(fmt(t.basalRate, WidgetLogic.rateText(b.rate)), p.txt1))
            else               -> null
        }
    }

    private fun carbsRuns(i: Input, p: Palette, t: Texts): List<Run>? {
        val cob = i.cob ?: return null
        if (cob < COB_MIN_G) return null
        return listOf(Run(nb(t.carbsLabel) + NBSP, p.txt2), Run(fmt(t.carbs, WidgetLogic.decimal(cob, 0)), p.txt1))
    }

    /**
     * Red groups on screen (budget: at most 2). The hero row (pill + digits) is one group when its pill is solid red or
     * its digits are full red; every full-red text run counts on its own.
     */
    fun redGroups(m: Model, p: Palette): Int {
        val red = { r: Run -> r.color == p.low || r.color == p.lowFill }
        val movedPillRed = m.pillInMeta && m.meta?.firstOrNull()?.let(red) == true
        val row = if (m.pill == Pill.TIEF || m.pill == Pill.TIEF_URGENT || m.pill == Pill.NO_DATA || m.bgColor == p.low || movedPillRed) 1 else 0
        // a pill word moved into the meta line still belongs to the hero row's group
        val meta = if (m.pillInMeta) m.meta?.drop(1) else m.meta
        val runs = listOfNotNull(meta, m.line3, m.line4).flatten().count(red)
        return row + runs
    }

    // ---------------------------------------------------------------- loop state (read-only, no Loop getter)

    /** Loop off for the cue: disabled, any suspended mode, or the pump reports suspended. APS builds only. */
    fun loopOff(aps: Boolean, mode: RM.Mode?, pumpSuspended: Boolean): Boolean =
        aps && (mode == RM.Mode.DISABLED_LOOP || mode?.isSuspended() == true || pumpSuspended)

    /** Same formula as LoopPlugin.minutesToEndOfSuspend, but on a record read without the loop's pre-check (which can write) */
    fun suspendMinutesLeft(mode: RM.Mode, timestamp: Long, duration: Long, now: Long): Int = when {
        !mode.isSuspended() -> 0
        duration > 0        -> ((timestamp + duration - now) / 60_000L).toInt()
        else                -> Int.MAX_VALUE
    }

    // ---------------------------------------------------------------- fitting (no digit may ever be cut)

    /** Layout constants shared by all six layouts (widget_lock_{l,m,s}_{light,ink}.xml) */
    const val COLUMN_PAD_H_DP = 16f
    const val COLUMN_PAD_TOP_DP = 4f
    const val ARROW_MARGIN_DP = 6f
    const val META_MARGIN_DP = 2f
    const val LINE3_MARGIN_DP = 6f
    const val LINE4_MARGIN_DP = 4f
    const val PILL_LETTER_SPACING = 0.06

    /** Line height of Roboto without font padding (ascent + descent = 1.17 em), rounded up */
    const val LINE_HEIGHT_EM = 1.2

    /** Advance widths (em) of Roboto, rounded up so the estimate errs wide (a smaller tier, never a cut digit) */
    private fun em(c: Char, bold: Boolean): Double = when {
        c.isDigit()                                    -> 0.57
        c == ',' || c == '.' || c == ':' || c == '′'   -> 0.28
        c == ' ' || c == NBSP || c == '\u2009'         -> 0.27
        c == '·'                                       -> 0.30
        c == '–' || c == '>'                           -> 0.60
        c == '%'                                       -> 0.80
        c == 'I' || c == 'i' || c == 'l' || c == 'j' || c == 't' || c == 'f' || c == 'r' -> 0.36
        c == 'm' || c == 'M' || c == 'w' || c == 'W'   -> 0.90
        c.isUpperCase()                                -> if (bold) 0.72 else 0.68
        else                                           -> if (bold) 0.60 else 0.57
    }

    fun textWidthDp(text: String, sizeDp: Float, bold: Boolean = false, letterSpacing: Double = 0.0): Float =
        (text.sumOf { em(it, bold) + letterSpacing } * sizeDp).toFloat()

    private fun pillSizeDp(pill: Pill, fontScale: Float) =
        secondarySizeDp(if (pill == Pill.TIEF_URGENT) PILL_URGENT_SP else PILL_SP, fontScale)

    /** Width the hero row needs: column padding + pill (text, padding, ring, gap) + digits + arrow box */
    fun heroRowWidthDp(m: Model, tier: Tier, fontScale: Float, t: Texts = Texts()): Float {
        val s = spec(tier)
        var w = COLUMN_PAD_H_DP + textWidthDp(m.bg, heroSizeDp(tier, fontScale))
        if (m.arrow != null) w += ARROW_MARGIN_DP + s.arrowDp
        m.pill?.let { pill ->
            val ring = if (pill == Pill.TIEF_URGENT) URGENT_RING_DP else 0f
            w += textWidthDp(pillText(pill, t), pillSizeDp(pill, fontScale), bold = true, letterSpacing = PILL_LETTER_SPACING) +
                2 * (s.pillPadH + ring) + (s.pillGap - ring)
        }
        return w
    }

    fun lineWidthDp(runs: List<Run>, sp: Float, fontScale: Float): Float =
        COLUMN_PAD_H_DP + runs.sumOf { textWidthDp(it.text, secondarySizeDp(sp, fontScale), bold = it.weight == Weight.MEDIUM).toDouble() }.toFloat()

    /** Height the column needs: top padding + hero row + every shown line with its top margin */
    fun heightDp(m: Model, tier: Tier, fontScale: Float): Float {
        val s = spec(tier)
        fun line(sp: Float) = (secondarySizeDp(sp, fontScale) * LINE_HEIGHT_EM).toFloat()
        var h = COLUMN_PAD_TOP_DP + maxOf((heroSizeDp(tier, fontScale) * LINE_HEIGHT_EM).toFloat(), s.arrowDp)
        if (m.meta != null) h += META_MARGIN_DP + line(s.metaSp)
        if (m.line3 != null) h += LINE3_MARGIN_DP + line(s.line3Sp)
        if (m.line4 != null) h += LINE4_MARGIN_DP + line(s.line4Sp)
        return h
    }

    fun fits(m: Model, tier: Tier, widthDp: Float, heightDp: Float, fontScale: Float, t: Texts = Texts()): Boolean {
        val s = spec(tier)
        return heroRowWidthDp(m, tier, fontScale, t) <= widthDp &&
            heightDp(m, tier, fontScale) <= heightDp &&
            (m.meta == null || lineWidthDp(m.meta, s.metaSp, fontScale) <= widthDp) &&
            (m.line3 == null || lineWidthDp(m.line3, s.line3Sp, fontScale) <= widthDp) &&
            (m.line4 == null || lineWidthDp(m.line4, s.line4Sp, fontScale) <= widthDp)
    }

    /**
     * Tier and model for a widget of [widthDp] x [heightDp]: starts at [tier] and steps down L -> M -> S until everything
     * fits (a lower tier also puts the loop cue into its one line). If even S is too narrow for the pill, the pill word moves
     * to the start of the meta line ("TIEF  ·  jetzt …"), so the digits are never cut.
     */
    fun fit(i: Input, widthDp: Float, heightDp: Float, fontScale: Float, p: Palette, t: Texts = Texts()): Pair<Tier, Model> {
        val start = tier(widthDp, heightDp)
        for (tier in Tier.entries.filter { it.ordinal <= start.ordinal }.sortedByDescending { it.ordinal }) {
            val m = build(i, tier, p, t)
            if (fits(m, tier, widthDp, heightDp, fontScale, t)) return tier to m
        }
        val s = build(i, Tier.S, p, t)
        return Tier.S to (if (s.pill != null && heroRowWidthDp(s, Tier.S, fontScale, t) > widthDp) pillIntoMeta(s, p, t) else s)
    }

    fun pillIntoMeta(m: Model, p: Palette, t: Texts = Texts()): Model {
        val pill = m.pill ?: return m
        val color = when (pill) {
            Pill.TIEF, Pill.TIEF_URGENT -> p.low
            Pill.HOCH                   -> p.high
            Pill.ALT                    -> p.txt2
            Pill.NO_DATA                -> p.txt1
        }
        val word = Run(nb(pillText(pill, t)), color, Weight.MEDIUM)
        return m.copy(pill = null, meta = listOf(word) + (m.meta?.let { listOf(Run(SEP, p.txt3)) + it } ?: emptyList()), pillInMeta = true)
    }
}
