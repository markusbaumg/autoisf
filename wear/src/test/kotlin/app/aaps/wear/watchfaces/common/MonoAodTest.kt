package app.aaps.wear.watchfaces.common

import app.aaps.wear.watchfaces.common.FaceState.BgAge
import app.aaps.wear.watchfaces.common.FaceState.Caption
import app.aaps.wear.watchfaces.common.FaceState.Range
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class MonoAodTest {

    private fun model(
        hero: String = "6,4", range: Range = Range.IN, age: BgAge = BgAge.FRESH, caption: Caption = FaceState.caption(range),
        warning: String? = null, ageText: String = "2′"
    ) = MonoAod.model(hero, range, age, caption, 90f, 1, warning, ageText, "vor %s")

    @Test fun inRangeIsTheBgAndArrowOnly() {
        val m = model()
        assertThat(m.grey).isFalse()
        assertThat(m.struck).isFalse()
        assertThat(m.arrowHeads).isEqualTo(1)
        assertThat(m.badge).isNull()
        assertThat(m.words).isNull()
        assertThat(m.lineShown).isFalse()
    }

    @Test fun lowsAndVeryHighSayItInWords() {
        assertThat(model(hero = "3,4", range = Range.LOW).badge).isEqualTo(MonoAod.Badge.LOW)
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW).badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(model(hero = "22,2", range = Range.VERY_HIGH).badge).isEqualTo(MonoAod.Badge.VERY_HIGH)
        // a plain high: the number says it
        assertThat(model(hero = "12,4", range = Range.HIGH).badge).isNull()
    }

    @Test fun oldDataIsGreyStruckWithoutArrowAndSaysHowOld() {
        val m = model(range = Range.STALE, age = BgAge.STALE, ageText = "17′")
        assertThat(m.grey).isTrue()
        assertThat(m.struck).isTrue()
        assertThat(m.arrowHeads).isEqualTo(0)
        assertThat(m.badge).isNull()
        assertThat(m.words).isEqualTo("vor 17′")
        // the face's own warning speaks for it when there is one
        assertThat(model(range = Range.STALE, age = BgAge.STALE, warning = "Keine Daten · 17′").words).isEqualTo("Keine Daten · 17′")
        // no badge on an old low
        assertThat(model(hero = "2,6", range = Range.URGENT_LOW, age = BgAge.STALE).badge).isNull()
    }

    @Test fun agingDataShowsItsAgeFreshDataDoesNot() {
        assertThat(model(age = BgAge.AGING, ageText = "8′").words).isEqualTo("vor 8′")
        assertThat(model(age = BgAge.FRESH, ageText = "2′").words).isNull()
    }

    @Test fun noReadingIsGreyDashesWithoutStrike() {
        val m = model(hero = FaceState.NO_BG, range = Range.STALE, age = BgAge.NONE, ageText = "--", warning = "Warte auf AAPS")
        assertThat(m.grey).isTrue()
        assertThat(m.struck).isFalse()
        assertThat(m.arrowHeads).isEqualTo(0)
        assertThat(m.words).isEqualTo("Warte auf AAPS")
        assertThat(model(hero = FaceState.NO_BG, range = Range.STALE, age = BgAge.NONE, ageText = "--").words).isNull()
    }

    @Test fun badgeAndWarningShareTheOneLine() {
        val m = model(hero = "2,6", range = Range.URGENT_LOW, warning = "Loop 23′")
        assertThat(m.badge).isEqualTo(MonoAod.Badge.URGENT_LOW)
        assertThat(m.words).isEqualTo("Loop 23′")
    }

    @Test fun batteryPercentFromLevelAndScale() {
        assertThat(MonoAod.batteryPercent(82, 100)).isEqualTo(82)
        assertThat(MonoAod.batteryPercent(41, 50)).isEqualTo(82)
        assertThat(MonoAod.batteryPercent(255, 255)).isEqualTo(100)
        assertThat(MonoAod.batteryPercent(0, 100)).isEqualTo(0)
        // unknown: nothing is drawn
        assertThat(MonoAod.batteryPercent(-1, 100)).isNull()
        assertThat(MonoAod.batteryPercent(50, 0)).isNull()
        assertThat(MonoAod.batteryPercent(50, -1)).isNull()
        // never above 100
        assertThat(MonoAod.batteryPercent(120, 100)).isEqualTo(100)
    }

    @Test fun batteryWordsPerLocale() {
        assertThat(MonoAod.batteryText(82)).isEqualTo("82 %")
        assertThat(MonoAod.batteryText(82, "%d%%")).isEqualTo("82%")
        assertThat(MonoAod.batteryText(5, "%d %%")).isEqualTo("5 %")
        assertThat(MonoAod.batteryText(100, "%d %%")).isEqualTo("100 %")
    }

    @Test fun lowBatteryIsEmphasisedAtFifteenOrLess() {
        assertThat(MonoAod.batteryLow(15)).isTrue()
        assertThat(MonoAod.batteryLow(3)).isTrue()
        assertThat(MonoAod.batteryLow(0)).isTrue()
        assertThat(MonoAod.batteryLow(16)).isFalse()
        assertThat(MonoAod.batteryLow(82)).isFalse()
    }

    @Test fun timeIn24And12Hours() {
        assertThat(MonoAod.timeText(7, 5, true)).isEqualTo("07:05")
        assertThat(MonoAod.timeText(22, 20, true)).isEqualTo("22:20")
        assertThat(MonoAod.timeText(0, 0, true)).isEqualTo("00:00")
        assertThat(MonoAod.timeText(22, 20, false)).isEqualTo("10:20")
        assertThat(MonoAod.timeText(0, 7, false)).isEqualTo("12:07")
        assertThat(MonoAod.timeText(12, 30, false)).isEqualTo("12:30")
    }

    @Test fun layoutKeepsSamsungZonesClear() {
        // centre lines plus half heights (the burn-in shift is at most 4 px)
        val shift = 4f
        assertThat(MonoAod.TIME_Y - MonoAod.TIME_SIZE / 2f - shift).isGreaterThan(MonoAod.SAMSUNG_TOP_Y1)
        assertThat(MonoAod.BATTERY_Y + MonoAod.BATTERY_SIZE / 2f + shift).isLessThan(MonoAod.SAMSUNG_BOTTOM_Y0)
        // order: time above BG above the line above the battery, the BG the largest
        assertThat(MonoAod.TIME_Y).isLessThan(MonoAod.HERO_Y)
        assertThat(MonoAod.HERO_Y).isLessThan(MonoAod.LINE_Y)
        assertThat(MonoAod.LINE_Y + MonoAod.BADGE_H / 2f).isLessThan(MonoAod.BATTERY_Y - MonoAod.BATTERY_SIZE / 2f)
        assertThat(MonoAod.HERO_SIZE).isGreaterThan(2f * MonoAod.TIME_SIZE)
        assertThat(MonoAod.TIME_SIZE).isGreaterThan(MonoAod.BATTERY_SIZE)
    }

    @Test fun paletteIsGreyOnly() {
        for (c in listOf(MonoAod.WHITE_D, MonoAod.GREY_BG, MonoAod.GREY_WORDS, MonoAod.GREY_TIME)) {
            assertThat(c ushr 24).isEqualTo(0xFF)
            assertThat(c shr 16 and 0xFF).isEqualTo(c shr 8 and 0xFF)
            assertThat(c shr 8 and 0xFF).isEqualTo(c and 0xFF)
        }
    }

    @Test fun theStyleSettingIsGone() {
        for (face in listOf("halo", "glass", "orbit")) {
            assertThat(File("src/main/res/xml/watch_face_configuration_$face.xml").readText()).doesNotContain("aod")
        }
        assertThat(File("src/main/res/values/strings_face_aod.xml").readText()).doesNotContain("face_aod_style")
    }
}
