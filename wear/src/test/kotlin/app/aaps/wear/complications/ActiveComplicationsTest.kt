package app.aaps.wear.complications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActiveComplicationsTest {

    private val all: List<Class<out ModernBaseComplicationProviderService>> = listOf(
        SgvComplication::class.java,
        IobIconComplication::class.java,
        UploaderBatteryComplication::class.java
    )

    @Test
    fun `nothing bound requests no update`() {
        assertThat(ActiveComplications.boundOnly(all, emptySet())).isEmpty()
    }

    @Test
    fun `only bound data sources are updated, once each`() {
        var keys = emptySet<String>()
        keys = ActiveComplications.withActivated(keys, SgvComplication::class.java.name, 3)
        keys = ActiveComplications.withActivated(keys, SgvComplication::class.java.name, 7)
        keys = ActiveComplications.withActivated(keys, UploaderBatteryComplication::class.java.name, 4)
        assertThat(ActiveComplications.boundOnly(all, keys))
            .containsExactly(SgvComplication::class.java, UploaderBatteryComplication::class.java)
    }

    @Test
    fun `a data source stays bound until its last instance is deactivated`() {
        val sgv = SgvComplication::class.java.name
        var keys = ActiveComplications.withActivated(emptySet(), sgv, 3)
        keys = ActiveComplications.withActivated(keys, sgv, 7)
        keys = ActiveComplications.withDeactivated(keys, sgv, 3)
        assertThat(ActiveComplications.boundOnly(all, keys)).containsExactly(SgvComplication::class.java)
        keys = ActiveComplications.withDeactivated(keys, sgv, 7)
        assertThat(ActiveComplications.boundOnly(all, keys)).isEmpty()
    }

    @Test
    fun `repeated activation of the same instance is idempotent`() {
        val sgv = SgvComplication::class.java.name
        val once = ActiveComplications.withActivated(emptySet(), sgv, 3)
        assertThat(ActiveComplications.withActivated(once, sgv, 3)).isEqualTo(once)
    }

    @Test
    fun `negative instance ids keep the provider name intact`() {
        val keys = ActiveComplications.withActivated(emptySet(), IobIconComplication::class.java.name, -1)
        assertThat(ActiveComplications.providersOf(keys)).containsExactly(IobIconComplication::class.java.name)
    }
}
