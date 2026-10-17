package app.aaps.wear.interaction.actions

import android.app.Application
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
}
