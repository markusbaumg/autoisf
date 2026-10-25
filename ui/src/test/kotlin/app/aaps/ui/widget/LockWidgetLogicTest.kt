package app.aaps.ui.widget

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.ui.widget.LockWidgetLogic.Arrow
import app.aaps.ui.widget.LockWidgetLogic.Input
import app.aaps.ui.widget.LockWidgetLogic.INK
import app.aaps.ui.widget.LockWidgetLogic.LIGHT
import app.aaps.ui.widget.LockWidgetLogic.Model
import app.aaps.ui.widget.LockWidgetLogic.PaletteKind
import app.aaps.ui.widget.LockWidgetLogic.Pill
import app.aaps.ui.widget.LockWidgetLogic.Tier
import app.aaps.ui.widget.LockWidgetLogic.Weight
import app.aaps.ui.widget.LockWidgetLogic.text
import app.aaps.ui.widget.WidgetLogic.BasalState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LockWidgetLogicTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val p = LIGHT

    private fun mmol(v: Double) = v * Constants.MMOLL_TO_MGDL

    /** State 1: in range 6,4 →, now, IOB 1,20 U, TIR 94 %, loop ran 2′ ago */
    private val inRange = Input(
        now = now, bgMgdl = mmol(6.4), bgTimestamp = now - 20_000L, arrow = TrendArrow.FLAT, mmol = true,
        iob = 1.2, cob = 0.0, aps = true, lastLoopRun = now - 2 * min, tir24h = 94, since = "17:16"
    )
    private val high = inRange.copy(bgMgdl = mmol(13.2), bgTimestamp = now - 2 * min, arrow = TrendArrow.FORTY_FIVE_UP, iob = 3.46,
                                    basal = BasalState.High(2.1, 210), tir24h = 88)
    private val low = inRange.copy(bgMgdl = mmol(3.4), arrow = TrendArrow.FORTY_FIVE_DOWN, iob = 0.4, basal = BasalState.Zero(25))
    private val stale = inRange.copy(bgMgdl = mmol(11.8), bgTimestamp = now - 23 * min, iob = 2.85, lastLoopRun = now - 4 * min)
    private val noData = inRange.copy(bgMgdl = mmol(3.6), bgTimestamp = now - 41 * min, iob = 0.85, lastLoopRun = now - 41 * min)

    private fun build(i: Input, tier: Tier = Tier.L, pal: LockWidgetLogic.Palette = p) = LockWidgetLogic.build(i, tier, pal)
    /** Text with the no-break spaces shown as plain spaces (see `no-break spaces` for where they are) */
    private fun List<LockWidgetLogic.Run>?.str() = this?.text()?.sp()
    private fun String.sp() = replace(LockWidgetLogic.NBSP, ' ')

    @Test
    fun `tier choice`() {
        assertThat(LockWidgetLogic.tier(359f, 274f)).isEqualTo(Tier.L)
        assertThat(LockWidgetLogic.tier(220f, 170f)).isEqualTo(Tier.L)
        assertThat(LockWidgetLogic.tier(219f, 274f)).isEqualTo(Tier.M)
        assertThat(LockWidgetLogic.tier(359f, 169f)).isEqualTo(Tier.M)
        assertThat(LockWidgetLogic.tier(250f, 96f)).isEqualTo(Tier.M)
        assertThat(LockWidgetLogic.tier(180f, 80f)).isEqualTo(Tier.M)
        assertThat(LockWidgetLogic.tier(169f, 95f)).isEqualTo(Tier.S)
        assertThat(LockWidgetLogic.tier(120f, 56f)).isEqualTo(Tier.S)
        assertThat(LockWidgetLogic.tier(150f, 300f)).isEqualTo(Tier.S)
    }

    @Test
    fun `font scale clamps`() {
        assertThat(LockWidgetLogic.heroSizeDp(Tier.L, 1f)).isEqualTo(68f)
        assertThat(LockWidgetLogic.heroSizeDp(Tier.L, 1.3f)).isWithin(0.001f).of(68f * 1.15f)
        assertThat(LockWidgetLogic.heroSizeDp(Tier.S, 0.9f)).isWithin(0.001f).of(36f)
        assertThat(LockWidgetLogic.secondarySizeDp(18f, 2f)).isWithin(0.001f).of(18f * 1.3f)
        assertThat(LockWidgetLogic.secondarySizeDp(18f, 0f)).isEqualTo(18f)
    }

    @Test
    fun `state 1 in range`() {
        val m = build(inRange)
        assertThat(m.pill).isNull()
        assertThat(m.bg).isEqualTo("6,4")
        assertThat(m.bgColor).isEqualTo(p.inRange)
        assertThat(m.arrow).isEqualTo(Arrow.FLAT)
        assertThat(m.meta.str()).isEqualTo("jetzt  ·  IOB 1,20 U")
        assertThat(m.meta!![0].color).isEqualTo(p.txt2)
        assertThat(m.meta!![0].weight).isEqualTo(Weight.REGULAR)
        assertThat(m.line3).isNull()
        assertThat(m.line4.str()).isEqualTo("Im Ziel 24 h  94 %")
    }

    @Test
    fun `state 2 high`() {
        val m = build(high)
        assertThat(m.pill).isEqualTo(Pill.HOCH)
        assertThat(m.bg).isEqualTo("13,2")
        assertThat(m.bgColor).isEqualTo(p.high)
        assertThat(m.arrow).isEqualTo(Arrow.UP45)
        assertThat(m.meta.str()).isEqualTo("vor 2′  ·  IOB 3,46 U")
        assertThat(m.line3.str()).isEqualTo("Basal 2,10 U/h")
        assertThat(m.line3!![1].color).isEqualTo(p.txt1)
        assertThat(m.line4.str()).isEqualTo("Im Ziel 24 h  88 %")
        assertThat(LockWidgetLogic.pillTextColor(Pill.HOCH, p)).isEqualTo(p.high)
        // HOCH is dropped in S
        assertThat(build(high, Tier.S).pill).isNull()
    }

    @Test
    fun `state 3 low with zero basal is calm (F18)`() {
        val m = build(low)
        assertThat(m.pill).isEqualTo(Pill.TIEF)
        assertThat(m.bgColor).isEqualTo(p.low)
        assertThat(m.arrow).isEqualTo(Arrow.DOWN45)
        assertThat(m.meta.str()).isEqualTo("jetzt  ·  IOB 0,40 U")
        assertThat(m.line3.str()).isEqualTo("Basal 0 U/h · Pause seit 25′")
        assertThat(m.line3!!.map { it.color }).containsExactly(p.txt2, p.txt2)
        assertThat(m.line4).isNull() // no TIR during a low
        assertThat(LockWidgetLogic.pillTextColor(Pill.TIEF, p)).isEqualTo(p.white)
        // TIEF stays in S
        assertThat(build(low, Tier.S).pill).isEqualTo(Pill.TIEF)
    }

    @Test
    fun `zero basal outside a low is amber`() {
        val m = build(inRange.copy(basal = BasalState.Zero(25)))
        assertThat(m.line3.str()).isEqualTo("Basal 0 U/h seit 25′")
        assertThat(m.line3!![1].color).isEqualTo(p.high)
        assertThat(build(high.copy(basal = BasalState.Zero(240, capped = true))).line3.str()).isEqualTo("Basal 0 U/h seit > 4 h")
    }

    @Test
    fun `quiet basal, smb and paused states give no basal line`() {
        assertThat(build(inRange.copy(basal = BasalState.Quiet(0.4, 80, true))).line3).isNull()
        assertThat(build(inRange.copy(basal = BasalState.Smb(0.3, 1))).line3).isNull()
        assertThat(build(inRange.copy(basal = BasalState.High(2.0, 200), aps = false)).line3).isNull()
    }

    @Test
    fun `urgent low`() {
        assertThat(build(low.copy(bgMgdl = mmol(2.9))).pill).isEqualTo(Pill.TIEF_URGENT)
        assertThat(build(low.copy(arrow = TrendArrow.DOUBLE_DOWN)).pill).isEqualTo(Pill.TIEF_URGENT)
        assertThat(build(low.copy(arrow = TrendArrow.DOUBLE_DOWN)).arrow).isEqualTo(Arrow.DOWN2)
        assertThat(build(low.copy(arrow = TrendArrow.SINGLE_DOWN)).pill).isEqualTo(Pill.TIEF)
        // falling fast in range is not a low
        assertThat(build(inRange.copy(arrow = TrendArrow.DOUBLE_DOWN)).pill).isNull()
        assertThat(build(low.copy(bgMgdl = mmol(2.9)), Tier.S).pill).isEqualTo(Pill.TIEF_URGENT)
    }

    @Test
    fun `state 4 stale beats range`() {
        val m = build(stale)
        assertThat(m.pill).isEqualTo(Pill.ALT)
        assertThat(m.bg).isEqualTo("11,8")
        assertThat(m.bgColor).isEqualTo(LockWidgetLogic.withAlpha(p.high, 0x99))
        assertThat(m.arrow).isNull()
        assertThat(m.meta.str()).isEqualTo("vor 23′  ·  zuletzt hoch")
        assertThat(m.meta!![0].color).isEqualTo(p.low)
        assertThat(m.meta!![0].weight).isEqualTo(Weight.MEDIUM)
        assertThat(m.line3.str()).isEqualTo("IOB 2,85 U")
        assertThat(m.line3!![0].color).isEqualTo(p.txt2) // not blue: not part of a live reading
        assertThat(m.line4).isNull()
        // from 12′, also a last low: no fresh range colour, no TIEF pill
        val staleLow = build(stale.copy(bgMgdl = mmol(3.4), bgTimestamp = now - 12 * min))
        assertThat(staleLow.pill).isEqualTo(Pill.ALT)
        assertThat(staleLow.bgColor).isEqualTo(LockWidgetLogic.withAlpha(p.low, 0x99))
        assertThat(staleLow.meta.str()).isEqualTo("vor 12′  ·  zuletzt tief")
        assertThat(build(stale.copy(bgMgdl = mmol(6.0))).meta.str()).isEqualTo("vor 23′  ·  zuletzt im Ziel")
        // 11′ is still fresh (amber medium age)
        val fresh11 = build(stale.copy(bgTimestamp = now - 11 * min))
        assertThat(fresh11.pill).isEqualTo(Pill.HOCH)
        assertThat(fresh11.meta!![0].text.sp()).isEqualTo("vor 11′")
        assertThat(fresh11.meta!![0].color).isEqualTo(p.high)
        assertThat(fresh11.meta!![0].weight).isEqualTo(Weight.MEDIUM)
    }

    @Test
    fun `fresh age turns amber from 6 minutes`() {
        val five = build(inRange.copy(bgTimestamp = now - 5 * min - 59_000L)).meta!![0]
        assertThat(five.text.sp()).isEqualTo("vor 5′")
        assertThat(five.color).isEqualTo(p.txt2)
        val six = build(inRange.copy(bgTimestamp = now - 6 * min)).meta!![0]
        assertThat(six.color).isEqualTo(p.high)
        assertThat(six.weight).isEqualTo(Weight.MEDIUM)
    }

    @Test
    fun `state 5 no data`() {
        val m = build(noData)
        assertThat(m.pill).isEqualTo(Pill.NO_DATA)
        assertThat(m.bg).isEqualTo(LockWidgetLogic.NO_DATA_HERO)
        assertThat(m.bgColor).isEqualTo(p.noData)
        assertThat(m.arrow).isNull()
        assertThat(m.meta.str()).isEqualTo("seit 17:16  ·  Loop steht")
        assertThat(m.meta!![0].color).isEqualTo(p.txt1)
        assertThat(m.line3.str()).isEqualTo("zuletzt tief 3,6")
        assertThat(m.line3!![1].color).isEqualTo(p.low)
        assertThat(m.line4.str()).isEqualTo("IOB 0,85 U")
        // M: IOB appended to line 3
        val mm = build(noData, Tier.M)
        assertThat(mm.line3.str()).isEqualTo("zuletzt tief 3,6  ·  IOB 0,85 U")
        assertThat(mm.line4).isNull()
        // loop fine: no loop word; last high in TXT1
        val hi = build(noData.copy(bgMgdl = mmol(11.8), lastLoopRun = now - min))
        assertThat(hi.meta.str()).isEqualTo("seit 17:16")
        assertThat(hi.line3.str()).isEqualTo("zuletzt hoch 11,8")
        assertThat(hi.line3!![1].color).isEqualTo(p.txt1)
    }

    @Test
    fun `state 6 never any reading`() {
        val m = build(inRange.copy(bgMgdl = null, bgTimestamp = null, since = null, iob = 0.0))
        assertThat(m.pill).isEqualTo(Pill.NO_DATA)
        assertThat(m.meta).isNull()
        assertThat(m.line3.str()).isEqualTo("IOB 0,00 U")
        assertThat(m.line4).isNull()
        val s = build(inRange.copy(bgMgdl = null, bgTimestamp = null, since = null), Tier.S)
        assertThat(s.pill).isEqualTo(Pill.NO_DATA)
        assertThat(s.meta).isNull()
    }

    @Test
    fun `loop cue thresholds F12`() {
        assertThat(build(inRange.copy(lastLoopRun = now - 14 * min)).line3).isNull()
        val late = build(inRange.copy(lastLoopRun = now - 18 * min)).line3!!
        assertThat(late.str()).isEqualTo("Loop seit 18′")
        assertThat(late[0].color).isEqualTo(p.high)
        val stalled = build(inRange.copy(lastLoopRun = now - 41 * min)).line3!!
        assertThat(stalled.str()).isEqualTo("Loop steht seit 41′")
        assertThat(stalled[0].color).isEqualTo(p.low)
        val off = build(inRange.copy(loopOff = true)).line3!!
        assertThat(off.str()).isEqualTo("Loop aus")
        assertThat(off[0].color).isEqualTo(p.high)
        // unknown last run or no APS build: no cue
        assertThat(build(inRange.copy(lastLoopRun = null)).line3).isNull()
        assertThat(build(inRange.copy(aps = false, lastLoopRun = now - 60 * min, loopOff = true)).line3).isNull()
    }

    @Test
    fun `line 3 priority loop, temp target, basal, carbs`() {
        val all = high.copy(lastLoopRun = now - 20 * min, ttMgdl = mmol(7.0), ttMinutesLeft = 35, cob = 24.0)
        assertThat(build(all).line3.str()).isEqualTo("Loop seit 20′")
        val tt = build(all.copy(lastLoopRun = now - min)).line3!!
        assertThat(tt.str()).isEqualTo("Temp-Ziel 7,0 · noch 35′")
        assertThat(tt[0].color).isEqualTo(p.tt)
        assertThat(build(all.copy(lastLoopRun = now - min, ttMgdl = null)).line3.str()).isEqualTo("Basal 2,10 U/h")
        assertThat(build(all.copy(lastLoopRun = now - min, ttMgdl = null, basal = BasalState.None)).line3.str()).isEqualTo("KH 24 g")
        assertThat(build(all.copy(lastLoopRun = now - min, ttMgdl = null, basal = BasalState.None, cob = 4.9)).line3).isNull()
        assertThat(build(all.copy(lastLoopRun = now - min, ttMinutesLeft = null)).line3.str()).isEqualTo("Temp-Ziel 7,0")
    }

    @Test
    fun `stale shows the loop only when off or stalled`() {
        assertThat(build(stale.copy(lastLoopRun = now - 20 * min)).line3.str()).isEqualTo("IOB 2,85 U")
        val stalled = build(stale.copy(lastLoopRun = now - 35 * min))
        assertThat(stalled.line3.str()).isEqualTo("Loop steht seit 35′")
        assertThat(stalled.line4.str()).isEqualTo("IOB 2,85 U")
        val off = build(stale.copy(loopOff = true))
        assertThat(off.line3.str()).isEqualTo("Loop aus")
        assertThat(off.line4.str()).isEqualTo("IOB 2,85 U")
    }

    @Test
    fun `S tier one line texts`() {
        val s1 = build(inRange, Tier.S)
        assertThat(s1.meta.str()).isEqualTo("jetzt  ·  IOB 1,20 U")
        assertThat(s1.line3).isNull()
        assertThat(s1.line4).isNull()
        assertThat(build(inRange.copy(lastLoopRun = now - 20 * min), Tier.S).meta.str()).isEqualTo("Loop seit 20′")
        assertThat(build(stale, Tier.S).meta.str()).isEqualTo("vor 23′  ·  zuletzt hoch")
        assertThat(build(noData, Tier.S).meta.str()).isEqualTo("seit 17:16  ·  zuletzt tief 3,6")
        assertThat(build(stale, Tier.S).pill).isEqualTo(Pill.ALT)
        assertThat(build(noData, Tier.S).pill).isEqualTo(Pill.NO_DATA)
    }

    @Test
    fun `TIR line only in L, only fresh and not low`() {
        assertThat(build(inRange, Tier.M).line4).isNull()
        assertThat(build(inRange.copy(tir24h = null)).line4).isNull()
        assertThat(build(high).line4.str()).isEqualTo("Im Ziel 24 h  88 %")
    }

    private val allStates = listOf(inRange, high, low, low.copy(bgMgdl = mmol(2.8)), stale, stale.copy(bgMgdl = mmol(3.2)), noData,
                                   noData.copy(bgMgdl = mmol(8.0)), inRange.copy(bgMgdl = null, bgTimestamp = null))
    private val loopVariants = listOf<(Input) -> Input>(
        { it }, { it.copy(lastLoopRun = now - 20 * min) }, { it.copy(lastLoopRun = now - 45 * min) }, { it.copy(loopOff = true) }
    )

    private fun everyModel(): List<Pair<String, Model>> =
        allStates.flatMap { s -> loopVariants.map { it(s) } }.flatMap { s ->
            Tier.entries.flatMap { t -> listOf(LIGHT, INK).map { pal -> "$t ${pal.kind} $s" to LockWidgetLogic.build(s, t, pal) } }
        }

    @Test
    fun `IOB is labelled wherever it is shown`() {
        for ((name, m) in everyModel()) {
            for (line in listOfNotNull(m.meta, m.line3, m.line4)) {
                val i = line.indexOfFirst { it.text.sp().endsWith(" U") && !it.text.contains("U/h") }
                if (i >= 0) assertWithMessage(name).that(line[i - 1].text.sp()).isEqualTo("IOB ")
            }
        }
        // the IOB is shown in every L / M state
        for (s in allStates) for (t in listOf(Tier.L, Tier.M))
            assertThat(LockWidgetLogic.build(s, t, p).plain(LockWidgetLogic.Texts()).sp()).contains("IOB ")
        // F13: blue label, value TXT1 when live
        val live = build(inRange).meta!!
        assertThat(live[2].color).isEqualTo(p.ins)
        assertThat(live[3].color).isEqualTo(p.txt1)
    }

    @Test
    fun `red budget at most 2 groups`() {
        for ((name, m) in everyModel()) {
            val pal = if (name.contains("INK")) INK else LIGHT
            assertWithMessage(name).that(LockWidgetLogic.redGroups(m, pal)).isAtMost(2)
        }
        // normal never uses red
        assertThat(LockWidgetLogic.redGroups(build(inRange), p)).isEqualTo(0)
    }

    @Test
    fun `every non normal state has a word`() {
        assertThat(build(high).pill).isNotNull()
        assertThat(build(low).pill).isNotNull()
        assertThat(build(stale).pill).isNotNull()
        assertThat(build(noData).pill).isNotNull()
    }

    @Test
    fun `ink palette uses ink tokens`() {
        val m = build(low, pal = INK)
        assertThat(m.bgColor).isEqualTo(INK.low)
        assertThat(build(stale, pal = INK).bgColor).isEqualTo(LockWidgetLogic.withAlpha(INK.high, 0x99))
        assertThat(build(noData, pal = INK).bgColor).isEqualTo(0x8C3A4354.toInt())
    }

    @Test
    fun `palette fallback`() {
        val dark = LockWidgetLogic.HINT_SUPPORTS_DARK_TEXT
        assertThat(LockWidgetLogic.paletteKind(null, null)).isEqualTo(PaletteKind.LIGHT)
        assertThat(LockWidgetLogic.paletteKind(dark, null)).isEqualTo(PaletteKind.INK)
        assertThat(LockWidgetLogic.paletteKind(0, dark)).isEqualTo(PaletteKind.LIGHT) // the lock wallpaper wins
        assertThat(LockWidgetLogic.paletteKind(null, dark)).isEqualTo(PaletteKind.INK)
        assertThat(LockWidgetLogic.paletteKind(null, 4)).isEqualTo(PaletteKind.LIGHT)
        assertThat(LockWidgetLogic.palette(PaletteKind.INK)).isEqualTo(INK)
    }

    @Test
    fun `scrim strength from wallpaper luminance`() {
        assertThat(LockWidgetLogic.scrimAlpha(0xFF1A2238.toInt())).isEqualTo(LockWidgetLogic.SCRIM_ALPHA_DARK)
        assertThat(LockWidgetLogic.scrimAlpha(0xFF9A9A9A.toInt())).isEqualTo(LockWidgetLogic.SCRIM_ALPHA_MID)
        assertThat(LockWidgetLogic.scrimAlpha(null)).isEqualTo(LockWidgetLogic.SCRIM_ALPHA_MID)
        assertThat(LockWidgetLogic.luminance(0xFFFFFFFF.toInt())).isWithin(1e-9).of(1.0)
        assertThat(LockWidgetLogic.luminance(0xFF000000.toInt())).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `since days`() {
        val zone = ZoneId.of("Europe/Zurich")
        fun t(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        assertThat(LockWidgetLogic.sinceDays(t(25, 17, 16), t(25, 17, 57), zone)).isEqualTo(0)
        assertThat(LockWidgetLogic.sinceDays(t(24, 23, 40), t(25, 0, 20), zone)).isEqualTo(1)
        assertThat(LockWidgetLogic.sinceDays(t(23, 23, 40), t(25, 0, 20), zone)).isEqualTo(2)
    }

    @Test
    fun `arrow mapping`() {
        assertThat(LockWidgetLogic.arrow(TrendArrow.DOUBLE_UP)).isEqualTo(Arrow.UP2)
        assertThat(LockWidgetLogic.arrow(TrendArrow.SINGLE_DOWN)).isEqualTo(Arrow.DOWN)
        assertThat(LockWidgetLogic.arrow(TrendArrow.TRIPLE_UP)).isNull()
        assertThat(LockWidgetLogic.arrow(TrendArrow.NONE)).isNull()
        assertThat(LockWidgetLogic.arrow(null)).isNull()
        assertThat(build(inRange.copy(arrow = null)).arrow).isNull()
    }

    @Test
    fun `mg per dl user`() {
        val m = build(inRange.copy(mmol = false, bgMgdl = 117.0, ttMgdl = 126.0, ttMinutesLeft = 70))
        assertThat(m.bg).isEqualTo("117")
        assertThat(m.line3.str()).isEqualTo("Temp-Ziel 126 · noch 1 h 10")
    }

    // ------------------------------------------------------------ boundaries

    @Test
    fun `loop cue exact boundaries`() {
        assertThat(build(inRange.copy(lastLoopRun = now - 15 * min)).line3.str()).isEqualTo("Loop seit 15′")
        assertThat(build(inRange.copy(lastLoopRun = now - 15 * min)).line3!![0].color).isEqualTo(p.high)
        assertThat(build(inRange.copy(lastLoopRun = now - 15 * min + 1)).line3).isNull()
        assertThat(build(inRange.copy(lastLoopRun = now - 29 * min - 59_000L)).line3!![0].color).isEqualTo(p.high)
        val stalled = build(inRange.copy(lastLoopRun = now - 30 * min)).line3!!
        assertThat(stalled.str()).isEqualTo("Loop steht seit 30′")
        assertThat(stalled[0].color).isEqualTo(p.low)
    }

    @Test
    fun `stale and no data boundary at 30 minutes`() {
        assertThat(build(stale.copy(bgTimestamp = now - 30 * min + 1_000L)).pill).isEqualTo(Pill.ALT)
        assertThat(build(stale.copy(bgTimestamp = now - 30 * min)).pill).isEqualTo(Pill.NO_DATA)
        assertThat(build(stale.copy(bgTimestamp = now - 12 * min + 1_000L)).pill).isEqualTo(Pill.HOCH)
        assertThat(build(stale.copy(bgTimestamp = now - 12 * min)).pill).isEqualTo(Pill.ALT)
    }

    @Test
    fun `high boundary at 10,0 and 180 mg per dl`() {
        assertThat(build(inRange.copy(bgMgdl = 180.0)).pill).isNull()
        assertThat(build(inRange.copy(bgMgdl = 180.0)).bgColor).isEqualTo(p.inRange)
        assertThat(build(inRange.copy(bgMgdl = 181.0)).pill).isEqualTo(Pill.HOCH)
        assertThat(build(inRange.copy(bgMgdl = 181.0, mmol = false)).bg).isEqualTo("181")
    }

    @Test
    fun `urgent boundary at 3,0 and 54 mg per dl, triple down`() {
        assertThat(build(low.copy(bgMgdl = 54.0)).pill).isEqualTo(Pill.TIEF)
        assertThat(build(low.copy(bgMgdl = 53.0)).pill).isEqualTo(Pill.TIEF_URGENT)
        assertThat(build(low.copy(arrow = TrendArrow.TRIPLE_DOWN)).pill).isEqualTo(Pill.TIEF_URGENT)
        assertThat(build(inRange.copy(arrow = TrendArrow.TRIPLE_DOWN)).pill).isNull()
    }

    @Test
    fun `mg per dl stale and no data`() {
        val st = build(stale.copy(mmol = false, bgMgdl = 65.0))
        assertThat(st.bg).isEqualTo("65")
        assertThat(st.meta.str()).isEqualTo("vor 23′  ·  zuletzt tief")
        val nd = build(noData.copy(mmol = false, bgMgdl = 65.0))
        assertThat(nd.line3.str()).isEqualTo("zuletzt tief 65")
        assertThat(build(noData.copy(mmol = false, bgMgdl = 65.0), Tier.S).meta.str()).isEqualTo("seit 17:16  ·  zuletzt tief 65")
    }

    @Test
    fun `reading from the future is stale without an age`() {
        val m = build(stale.copy(bgTimestamp = now + 2 * min))
        assertThat(m.pill).isEqualTo(Pill.ALT)
        assertThat(m.meta.str()).isEqualTo("zuletzt hoch")
        assertThat(m.meta.str()).doesNotContain("vor")
        // within the tolerance it is still fresh
        assertThat(build(inRange.copy(bgTimestamp = now + 30_000L)).pill).isNull()
    }

    @Test
    fun `loop off derivation`() {
        assertThat(LockWidgetLogic.loopOff(true, RM.Mode.CLOSED_LOOP, false)).isFalse()
        assertThat(LockWidgetLogic.loopOff(true, RM.Mode.OPEN_LOOP, false)).isFalse()
        assertThat(LockWidgetLogic.loopOff(true, RM.Mode.CLOSED_LOOP_LGS, false)).isFalse()
        assertThat(LockWidgetLogic.loopOff(true, RM.Mode.DISABLED_LOOP, false)).isTrue()
        for (m in listOf(RM.Mode.SUSPENDED_BY_USER, RM.Mode.SUSPENDED_BY_PUMP, RM.Mode.DISCONNECTED_PUMP, RM.Mode.SUSPENDED_BY_DST, RM.Mode.SUPER_BOLUS))
            assertWithMessage(m.name).that(LockWidgetLogic.loopOff(true, m, false)).isTrue()
        assertThat(LockWidgetLogic.loopOff(true, RM.Mode.CLOSED_LOOP, true)).isTrue() // pump suspended, record not yet written
        assertThat(LockWidgetLogic.loopOff(true, null, false)).isFalse()
        assertThat(LockWidgetLogic.loopOff(false, RM.Mode.DISABLED_LOOP, true)).isFalse()
    }

    @Test
    fun `suspend minutes left like LoopPlugin`() {
        assertThat(LockWidgetLogic.suspendMinutesLeft(RM.Mode.CLOSED_LOOP, now - 10 * min, 30 * min, now)).isEqualTo(0)
        assertThat(LockWidgetLogic.suspendMinutesLeft(RM.Mode.SUSPENDED_BY_USER, now - 10 * min, 30 * min, now)).isEqualTo(20)
        assertThat(LockWidgetLogic.suspendMinutesLeft(RM.Mode.SUSPENDED_BY_USER, now - 30 * min, 30 * min, now)).isEqualTo(0)
        assertThat(LockWidgetLogic.suspendMinutesLeft(RM.Mode.SUSPENDED_BY_PUMP, now, 0, now)).isEqualTo(Int.MAX_VALUE)
    }

    // ------------------------------------------------------------ line breaking

    /** The texts a line may break into: split at plain spaces only (U+00A0 does not break) */
    private fun words(runs: List<LockWidgetLogic.Run>?) = runs!!.text().split(' ').filter { it.isNotEmpty() }.map { it.sp() }

    @Test
    fun `no-break spaces keep values with their unit`() {
        assertThat(words(build(high).meta)).containsExactly("vor 2′", "·  IOB 3,46 U").inOrder()
        assertThat(words(build(inRange.copy(lastLoopRun = now - 65 * min)).line3)).containsExactly("Loop", "steht", "seit 1 h 05").inOrder()
        assertThat(words(build(noData, Tier.S).meta)).containsExactly("seit 17:16", "·  zuletzt tief 3,6").inOrder()
        assertThat(words(build(inRange.copy(basal = BasalState.Zero(25))).line3)).containsExactly("Basal 0 U/h", "seit 25′").inOrder()
        assertThat(words(build(low).line3)).containsExactly("Basal 0 U/h", "·", "Pause", "seit 25′").inOrder()
        assertThat(words(build(inRange).line4)).containsExactly("Im Ziel 24 h  94 %")
        assertThat(words(build(all3()).line3)).containsExactly("Temp-Ziel 7,0", "·", "noch 1 h 10").inOrder()
        assertThat(LockWidgetLogic.glue("%1\$s ago")).isEqualTo("%1\$s\u00A0ago")
        assertThat(LockWidgetLogic.glue("Temp target %1\$s · %2\$s left")).isEqualTo("Temp target\u00A0%1\$s ·\u00A0%2\$s\u00A0left")
    }

    private fun all3() = inRange.copy(ttMgdl = mmol(7.0), ttMinutesLeft = 70)

    // ------------------------------------------------------------ fitting

    @Test
    fun `fit keeps L at the LockStar default`() {
        for (s in allStates) {
            val (tier, _) = LockWidgetLogic.fit(s, 359f, 274f, 1f, p)
            assertWithMessage("$s").that(tier).isEqualTo(Tier.L)
        }
        // even at the largest font scale with the widest hero row
        assertThat(LockWidgetLogic.fit(low.copy(bgMgdl = mmol(2.8)), 359f, 274f, 1.3f, p).first).isEqualTo(Tier.L)
    }

    @Test
    fun `fit steps down when a line would fall off the bottom`() {
        // M from 96 dp tall, but a loop warning on line 3 does not fit there: S puts it into its one line
        val late = inRange.copy(lastLoopRun = now - 41 * min)
        val (tier, m) = LockWidgetLogic.fit(late, 300f, 100f, 1f, p)
        assertThat(tier).isEqualTo(Tier.S)
        assertThat(m.meta.str()).isEqualTo("Loop steht seit 41′")
        // without line 3, M fits at 100 dp
        assertThat(LockWidgetLogic.fit(inRange, 300f, 100f, 1f, p).first).isEqualTo(Tier.M)
        // L with TIR at 170 dp and font scale 1.3: line 4 would be cut, so M
        assertThat(LockWidgetLogic.fit(inRange.copy(cob = 24.0), 359f, 170f, 1.3f, p).first).isEqualTo(Tier.M)
        assertThat(LockWidgetLogic.fit(inRange.copy(cob = 24.0), 359f, 274f, 1.3f, p).first).isEqualTo(Tier.L)
    }

    @Test
    fun `fit moves the pill into the meta line at the narrowest size`() {
        val urgent = low.copy(bgMgdl = mmol(2.8))
        val (tier, m) = LockWidgetLogic.fit(urgent, 160f, 80f, 1.3f, p)
        assertThat(tier).isEqualTo(Tier.S)
        assertThat(m.pill).isNull()
        assertThat(m.pillInMeta).isTrue()
        assertThat(m.bg).isEqualTo("2,8")
        assertThat(m.meta.str()).isEqualTo("TIEF  ·  jetzt  ·  IOB 0,40 U")
        assertThat(m.meta!![0].color).isEqualTo(p.low)
        assertThat(LockWidgetLogic.heroRowWidthDp(m, Tier.S, 1.3f)).isAtMost(160f)
        assertThat(LockWidgetLogic.redGroups(m, p)).isAtMost(2)
    }

    @Test
    fun `the hero row always fits in every allowed size`() {
        val widths = listOf(160f, 170f, 200f, 219f, 220f, 250f, 300f, 359f)
        val heights = listOf(72f, 96f, 120f, 150f, 169f, 170f, 200f, 274f)
        val states = allStates.flatMap { s -> loopVariants.map { it(s) } } + low.copy(bgMgdl = mmol(2.8), arrow = TrendArrow.DOUBLE_DOWN) +
            high.copy(bgMgdl = mmol(22.4)) + high.copy(bgMgdl = 404.0, mmol = false)
        for (s in states) for (w in widths) for (h in heights) for (fs in listOf(0.85f, 1f, 1.3f, 2f)) {
            val (tier, m) = LockWidgetLogic.fit(s, w, h, fs, p)
            assertWithMessage("$w x $h @ $fs $tier $s").that(LockWidgetLogic.heroRowWidthDp(m, tier, fs)).isAtMost(w)
            assertWithMessage("red $w x $h @ $fs $tier $s").that(LockWidgetLogic.redGroups(m, p)).isAtMost(2)
        }
    }

    @Test
    fun `width estimate errs wide`() {
        // Roboto: digits 0.56 em, comma 0.2 em; "12,4" at 64 dp is about 120 dp
        assertThat(LockWidgetLogic.textWidthDp("12,4", 64f)).isAtLeast(120f)
        assertThat(LockWidgetLogic.textWidthDp("TIEF", 13f, bold = true, letterSpacing = 0.06)).isAtLeast(30f)
    }
}
