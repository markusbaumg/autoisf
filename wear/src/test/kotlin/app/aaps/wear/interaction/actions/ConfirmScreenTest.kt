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
        assertThat(rows[0].value).isEqualTo("≈\u00A050 von 45\u00A0g")
        assertThat(rows[2].value).isEqualTo("≈\u00A0−9,6\u00A0mmol/L")
        assertThat(rows[2].quiet).isTrue()
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
    }

    @Test fun `warning details never break inside a number`() {
        val d = ConfirmDetails(kind = ConfirmDetails.KIND_BOLUS, insulin = 1.0, unitsMgdl = false, arrow = "↓")
        assertThat(screen.warningDetail(d, ConfirmWarning(ConfirmWarning.W_BG_FALLING, value = -12.0))).isEqualTo("−0,7\u00A0/\u00A05\u00A0min")
        assertThat(screen.warningShort(d, ConfirmWarning(ConfirmWarning.W_BG_LOW, value = 65.0))).isEqualTo("3,6\u00A0↓")
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
    }
}
