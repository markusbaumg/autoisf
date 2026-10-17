package app.aaps.wear.interaction.actions

import android.app.Application
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmDetails
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmWarning
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class ConfirmScreenTest {

    private val screen get() = ConfirmScreen(RuntimeEnvironment.getApplication())

    @Test fun `insulin is shown exactly, never rounded away`() {
        assertThat(screen.insulin(0.5)).isEqualTo("0,5")
        assertThat(screen.insulin(0.05)).isEqualTo("0,05")
        assertThat(screen.insulin(1.25)).isEqualTo("1,25")
        assertThat(screen.insulin(0.025)).isEqualTo("0,025")
        assertThat(screen.insulin(3.0)).isEqualTo("3,0")
        assertThat(screen.headline(ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 0.35))).isEqualTo("0,35\u00A0U")
    }

    @Test fun `glucose changes carry sign and unit in both units`() {
        assertThat(screen.glucoseDelta(-22.5, unitsMgdl = false)).isEqualTo("−1,2\u00A0mmol/L")
        assertThat(screen.glucoseDelta(56.25, unitsMgdl = true)).isEqualTo("+56\u00A0mg/dL")
        assertThat(screen.glucoseDelta(-0.1, unitsMgdl = true)).isEqualTo("0\u00A0mg/dL")
    }

    @Test fun `carbs headline and second line`() {
        assertThat(screen.headline(ConfirmDetails(kind = ConfirmDetails.KIND_CARBS, carbs = 20))).isEqualTo("20\u00A0g")
        assertThat(screen.secondLine(ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 1.0, carbs = 20))).isEqualTo("+ 20\u00A0g KH")
        assertThat(screen.secondLine(ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 1.0, carbs = -5))).isEqualTo("− 5\u00A0g KH")
        assertThat(screen.secondLine(ConfirmDetails(kind = ConfirmDetails.KIND_CARBS, carbs = 20))).isNull()
    }

    @Test fun `every warning has a word, unknown codes too`() {
        assertThat(screen.warningWord(ConfirmWarning(ConfirmWarning.W_BG_LOW))).isEqualTo("TIEF")
        assertThat(screen.warningWord(ConfirmWarning(ConfirmWarning.W_BG_FALLING, value = -12.0))).isEqualTo("FÄLLT SCHNELL")
        assertThat(screen.warningWord(ConfirmWarning("something_new"))).isEqualTo("HINWEIS")
    }

    @Test fun `details travel as an intent extra and bad extras fall back to the plain message`() {
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 0.5, warnings = listOf(ConfirmWarning(ConfirmWarning.W_NO_BG)))
        assertThat(ConfirmScreen.decode(ConfirmScreen.encode(d))).isEqualTo(d)
        assertThat(ConfirmScreen.decode(null)).isNull()
        assertThat(ConfirmScreen.decode("{not json")).isNull()
        assertThat(ConfirmScreen.encode(null)).isNull()
    }

    @Test fun `details are shown only when they name exactly what the confirm sends`() {
        val bolus = ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 2.0, carbs = 10)
        assertThat(ConfirmScreen.matchingDetails(bolus, EventData.ActionBolusConfirmed(2.0, 10, 7L))).isEqualTo(bolus)
        // a different amount or carbs: the phone's own text instead
        assertThat(ConfirmScreen.matchingDetails(bolus, EventData.ActionBolusConfirmed(5.0, 10, 7L))).isNull()
        assertThat(ConfirmScreen.matchingDetails(bolus, EventData.ActionBolusConfirmed(2.0, 20, 7L))).isNull()
        assertThat(ConfirmScreen.matchingDetails(bolus.copy(insulin = 2.0000001), EventData.ActionBolusConfirmed(2.0, 10, 7L))).isNotNull()
        // carbs only come as a bolus command with 0 U
        val carbs = ConfirmDetails(kind = ConfirmDetails.KIND_CARBS, carbs = 20)
        assertThat(ConfirmScreen.matchingDetails(carbs, EventData.ActionBolusConfirmed(0.0, 20, 7L))).isEqualTo(carbs)
        // the kind must match the command
        val fill = ConfirmDetails(kind = ConfirmDetails.KIND_FILL, insulin = 0.7)
        assertThat(ConfirmScreen.matchingDetails(fill, EventData.ActionFillConfirmed(0.7, 3L))).isEqualTo(fill)
        assertThat(ConfirmScreen.matchingDetails(fill, EventData.ActionBolusConfirmed(0.7, 0, 3L))).isNull()
        assertThat(ConfirmScreen.matchingDetails(fill, EventData.ActionFillConfirmed(1.0, 3L))).isNull()
        val ecarbs = ConfirmDetails(kind = ConfirmDetails.KIND_ECARBS, carbs = 30)
        assertThat(ConfirmScreen.matchingDetails(ecarbs, EventData.ActionECarbsConfirmed(30, 0L, 3))).isEqualTo(ecarbs)
        assertThat(ConfirmScreen.matchingDetails(ecarbs, EventData.ActionECarbsConfirmed(40, 0L, 3))).isNull()
        val wizard = ConfirmDetails(kind = ConfirmDetails.KIND_WIZARD, insulin = 3.85, carbs = 45)
        assertThat(ConfirmScreen.matchingDetails(wizard, EventData.ActionWizardConfirmed(1L))).isEqualTo(wizard)
        assertThat(ConfirmScreen.matchingDetails(bolus, EventData.ActionWizardConfirmed(1L))).isNull()
        assertThat(ConfirmScreen.matchingDetails(bolus, null)).isNull()
        assertThat(ConfirmScreen.matchingDetails(null, EventData.ActionBolusConfirmed(2.0, 10, 7L))).isNull()
    }

    @Test fun `with carbs the covered carbs come first and the insulin-only drop last`() {
        val d = ConfirmDetails(
            kind = ConfirmDetails.KIND_BOLUS, insulin = 3.85, carbs = 45, unitsMgdl = false,
            bgEffectMgdl = -173.25, carbsCovered = 50.05, iobNow = 1.2, iobAfter = 5.05, cobNow = 0.0, cobAfter = 45.0
        )
        val rows = screen.effectRows(d)
        assertThat(rows.map { it.label }).containsExactly("Deckt KH", "IOB", "Insulin allein", "COB").inOrder()
        // more than the carbs: not "≈ 50 von 45 g", which reads like an error
        assertThat(rows[0].value).isEqualTo("≈\u00A050\u00A0g für 45\u00A0g")
        assertThat(rows[0].emphasis).isEqualTo(ConfirmScreen.Emphasis.PRIMARY)
        assertThat(rows[1].emphasis).isEqualTo(ConfirmScreen.Emphasis.SECONDARY)
        assertThat(rows[2].value).isEqualTo("≈\u00A0−9,6\u00A0mmol/L")
        assertThat(rows[2].quiet).isTrue()
        // up to the carbs: "von"
        assertThat(screen.effectRows(d.copy(carbsCovered = 39.0))[0].value).isEqualTo("≈\u00A039 von 45\u00A0g")
    }

    @Test fun `the wizard's covered carbs are never compared with its carbs`() {
        // the wizard's dose nets correction, IOB, COB, trend and %: "≈ 29 von 45 g" would read as too little and invite a top-up
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_WIZARD, insulin = 2.25, carbs = 45, unitsMgdl = false, carbsCovered = 29.25, bgEffectMgdl = -101.25)
        assertThat(screen.effectRows(d)[0].label to screen.effectRows(d)[0].value).isEqualTo("Deckt KH" to "≈\u00A029,2\u00A0g")
        assertThat(screen.effectRows(d.copy(carbsCovered = 50.05))[0].value).isEqualTo("≈\u00A050\u00A0g")
    }

    @Test fun `durations are estimates without false precision`() {
        assertThat(screen.duration(20)).isEqualTo("≈\u00A020\u00A0s")
        assertThat(screen.duration(23)).isEqualTo("≈\u00A025\u00A0s")
        assertThat(screen.duration(2)).isEqualTo("≈\u00A05\u00A0s")
        assertThat(screen.duration(60)).isEqualTo("≈\u00A01\u00A0min")
        assertThat(screen.duration(154)).isEqualTo("≈\u00A02½\u00A0min")
        assertThat(screen.duration(180)).isEqualTo("≈\u00A03\u00A0min")
    }

    @Test fun `the quick wizard's carb delay and extended carbs are named next to the carbs`() {
        val d = ConfirmDetails(
            kind = ConfirmDetails.KIND_WIZARD, insulin = 3.85, carbs = 45, carbsDelayMin = 15, carbsAlarm = true,
            eCarbs = 20, eCarbsDurationH = 2, eCarbsOffsetMin = 30
        )
        assertThat(screen.carbsTiming(d)).isEqualTo("in\u00A015\u00A0min · mit Alarm")
        assertThat(screen.eCarbsLines(d)).isEqualTo("+ 20\u00A0g verzögert" to "über 2\u00A0Std, ab in\u00A030\u00A0min")
        assertThat(screen.confirmCarbsLine(d)).isEqualTo("+ 45\u00A0g KH · in\u00A015\u00A0min\n+ 20\u00A0g verzögert")
        assertThat(screen.eCarbsLines(d.copy(eCarbsOffsetMin = 0))!!.second).isEqualTo("über 2\u00A0Std, ab jetzt")
        val plain = d.copy(carbsDelayMin = null, carbsAlarm = false, eCarbs = null)
        assertThat(screen.carbsTiming(plain)).isNull()
        assertThat(screen.eCarbsLines(plain)).isNull()
        assertThat(screen.confirmCarbsLine(plain)).isEqualTo("+ 45\u00A0g KH")
    }

    @Test fun `low and falling are one chip, counted once`() {
        val d = ConfirmDetails(
            kind = ConfirmDetails.KIND_BOLUS, insulin = 1.0, unitsMgdl = false, arrow = "↘",
            warnings = listOf(
                ConfirmWarning(ConfirmWarning.W_BG_LOW, ConfirmWarning.LEVEL_DANGER, 65.0),
                ConfirmWarning(ConfirmWarning.W_BG_FALLING, ConfirmWarning.LEVEL_CAUTION, -7.0),
                ConfirmWarning(ConfirmWarning.W_RECENT_BOLUS, ConfirmWarning.LEVEL_CAUTION, 6.0, 1.0)
            )
        )
        val shown = screen.shownWarnings(d)
        assertThat(shown.map { it.code }).containsExactly(ConfirmScreen.W_LOW_FALLING, ConfirmWarning.W_RECENT_BOLUS).inOrder()
        assertThat(shown[0].level).isEqualTo(ConfirmWarning.LEVEL_DANGER)
        assertThat(screen.warningWord(shown[0])).isEqualTo("TIEF · FÄLLT")
        assertThat(screen.warningDetail(d, shown[0])).isEqualTo("BZ\u00A03,6\u00A0mmol/L\n−0,4\u00A0mmol/L in\u00A05\u00A0min")
        assertThat(screen.warningWord(shown[1])).isEqualTo("NACH BOLUS")
        assertThat(screen.warningDetail(d, shown[1])).isEqualTo("1,0\u00A0U · vor\u00A06\u00A0min")
        // one of them alone stays as it is
        assertThat(screen.shownWarnings(d.copy(warnings = d.warnings.drop(1))).map { it.code })
            .containsExactly(ConfirmWarning.W_BG_FALLING, ConfirmWarning.W_RECENT_BOLUS).inOrder()
    }

    @Test fun `the watch wizard's confirm page shows the details only for exactly its result`() {
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_WIZARD, insulin = 3.25, carbs = 45, warnings = listOf(ConfirmWarning(ConfirmWarning.W_NO_BG)))
        assertThat(ConfirmScreen.wizardDetails(d, 3.25, 45)).isEqualTo(d)
        // another amount, other carbs, another kind or none: only the result's own numbers, no warnings from elsewhere
        val plain = ConfirmDetails(kind = ConfirmDetails.KIND_WIZARD, insulin = 3.25, carbs = 45)
        assertThat(ConfirmScreen.wizardDetails(d.copy(insulin = 3.3), 3.25, 45)).isEqualTo(plain)
        assertThat(ConfirmScreen.wizardDetails(d.copy(carbs = 40), 3.25, 45)).isEqualTo(plain)
        assertThat(ConfirmScreen.wizardDetails(d.copy(kind = ConfirmDetails.KIND_BOLUS), 3.25, 45)).isEqualTo(plain)
        assertThat(ConfirmScreen.wizardDetails(null, 3.25, 45)).isEqualTo(plain)
    }

    @Test fun `every estimate carries the approx sign, the plain sums do not`() {
        val d = ConfirmDetails(
            kind = ConfirmDetails.KIND_BOLUS, insulin = 0.5, unitsMgdl = false, bgEffectMgdl = -22.5, carbsCovered = 6.5, iobNow = 1.2, iobAfter = 1.7,
            deliverySeconds = 20, insulinPeakMin = 55, lastBolusMinAgo = 42, lastBolusAmount = 1.5, isfMgdl = 45.0, ic = 13.0
        )
        assertThat(screen.effectRows(d).map { it.label to it.value }).containsExactly(
            "Senkt BZ" to "≈\u00A0−1,2\u00A0mmol/L", "IOB" to "1,20 → 1,70\u00A0U", "Deckt KH" to "≈\u00A06,5\u00A0g"
        ).inOrder()
        assertThat(screen.timingRows(d).map { it.label to it.value }).containsExactly(
            "Abgabe dauert" to "≈\u00A020\u00A0s", "Wirkgipfel in" to "≈\u00A055\u00A0min", "Letzter Bolus vor" to "42\u00A0min · 1,5\u00A0U"
        ).inOrder()
        assertThat(screen.sourceLine(d)).isEqualTo("Profil: ISF\u00A02,5 · IC\u00A013")
    }

    @Test fun `carbs alone raise BG and never show an insulin need`() {
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_CARBS, carbs = 20, unitsMgdl = false, bgEffectMgdl = 69.2, cobNow = 12.0, cobAfter = 32.0)
        assertThat(screen.effectRows(d).map { it.label }).containsExactly("Hebt BZ", "COB").inOrder()
        // extended carbs raise BG over their duration
        val e = ConfirmDetails(kind = ConfirmDetails.KIND_ECARBS, carbs = 30, unitsMgdl = false, bgEffectMgdl = 103.8, carbsDurationH = 3)
        assertThat(screen.effectRows(e).single().label).isEqualTo("Hebt BZ über 3\u00A0Std")
    }

    @Test fun `warning details never break inside a number`() {
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 1.0, unitsMgdl = false, arrow = "↓")
        // the change carries its unit (never read as U); it may wrap only before "in"
        assertThat(screen.warningDetail(d, ConfirmWarning(ConfirmWarning.W_BG_FALLING, value = -12.0))).isEqualTo("−0,7\u00A0mmol/L in\u00A05\u00A0min")
        assertThat(screen.warningDetail(d.copy(unitsMgdl = true), ConfirmWarning(ConfirmWarning.W_BG_FALLING, value = -12.0))).isEqualTo("−12\u00A0mg/dL in\u00A05\u00A0min")
        // details start with a capital
        assertThat(screen.warningDetail(d, ConfirmWarning(ConfirmWarning.W_BG_STALE, value = 18.0))).isEqualTo("Letzter\u00A0BZ vor\u00A018\u00A0min")
        assertThat(screen.warningDetail(d, ConfirmWarning(ConfirmWarning.W_CONSTRAINT, value = 6.0))).isEqualTo("Angefragt 6,0\u00A0U")
    }

    @Test fun `the calculation splits into label and value with typographic numbers`() {
        assertThat(ConfirmScreen.noteSplit("IOB: -0,35U")).isEqualTo("IOB" to "-0,35U")
        assertThat(ConfirmScreen.noteSplit("Berechnung (IC: 13,0, ISF: 2,5)")).isNull()
        assertThat(ConfirmScreen.noteSplit("Prozent: 4,28U x 90% ≈ 3,85U")).isEqualTo("Prozent" to "4,28U x 90% ≈ 3,85U")
        assertThat(ConfirmScreen.noteSplit("Korrektur 90%")).isNull()
        assertThat(ConfirmScreen.prettyNumbers("-0,35U")).isEqualTo("−0,35\u00A0U")
        assertThat(ConfirmScreen.prettyNumbers("+20g/2h(+30m)")).isEqualTo("+20\u00A0g/2\u00A0h(+30\u00A0m)")
        assertThat(ConfirmScreen.prettyNumbers("5,5 mmol/L")).isEqualTo("5,5 mmol/L")
        assertThat(ConfirmScreen.prettyNumbers("4,28U x 90%")).isEqualTo("4,28\u00A0U × 90%")
        // two values apart, and the dose after the % is exact: "→", not "≈"
        assertThat(ConfirmScreen.noteValue("0g 0,00U")).isEqualTo("0\u00A0g · 0,00\u00A0U")
        assertThat(ConfirmScreen.noteValue("4,28U x 90% ≈ 3,85U")).isEqualTo("4,28\u00A0U × 90% → 3,85\u00A0U")
    }
}
