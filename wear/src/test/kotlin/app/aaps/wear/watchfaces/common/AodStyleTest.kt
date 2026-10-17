package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class AodStyleTest {

    private fun spWith(value: String?): SP = mock<SP>().also { sp ->
        doAnswer { if (it.getArgument<String>(0) == AodStyle.KEY && value != null) value else it.getArgument<String?>(1) }
            .whenever(sp).getStringOrNull(anyString(), anyOrNull())
    }

    @Test fun defaultIsOutline() {
        assertThat(AodStyle.DEFAULT).isEqualTo(AodStyle.OUTLINE)
        assertThat(AodStyle.read(spWith(null))).isEqualTo(AodStyle.OUTLINE)
    }

    @Test fun readsEveryStyle() {
        assertThat(AodStyle.read(spWith("outline"))).isEqualTo(AodStyle.OUTLINE)
        assertThat(AodStyle.read(spWith("minimal"))).isEqualTo(AodStyle.MINIMAL)
        assertThat(AodStyle.read(spWith("glance"))).isEqualTo(AodStyle.GLANCE)
        assertThat(AodStyle.read(spWith(" glance "))).isEqualTo(AodStyle.GLANCE)
    }

    @Test fun unknownValuesFallBackToOutline() {
        for (v in listOf("", "Minimal", "MINIMAL", "bold", "1")) assertThat(AodStyle.fromPref(v)).isEqualTo(AodStyle.OUTLINE)
    }

    @Test fun notSettableFromACustomWatchface() {
        assertThat(CwfMetadataKey.entries.map { it.key }).doesNotContain(AodStyle.KEY)
    }

    @Test fun keyAndValuesMatchResources() {
        val xml = File("src/main/res/values/strings_face_aod.xml").readText()
        assertThat(xml).contains("name=\"key_face_aod_style\" translatable=\"false\">${AodStyle.KEY}<")
        val values = xml.substringAfter("name=\"face_aod_style_values\"").substringBefore("</string-array>")
        val items = Regex("<item>([^<]*)</item>").findAll(values).map { it.groupValues[1] }.toList()
        assertThat(items).containsExactlyElementsIn(AodStyle.entries.map { it.prefValue }).inOrder()
        val de = File("src/main/res/values-de/strings_face_aod.xml").readText()
        assertThat(de).contains(">Always-On-Stil<")
        assertThat(de).contains(">Umriss<")
        assertThat(de).contains(">Übersicht<")
    }

    @Test fun allThreeFacesOfferTheSharedStyleDefaultOutline() {
        for (face in listOf("halo", "glass", "orbit")) {
            val xml = File("src/main/res/xml/watch_face_configuration_$face.xml").readText()
            val block = xml.substringBefore("android:key=\"@string/key_face_aod_style\"").substringAfterLast("<ListPreference")
            assertThat(block).contains("android:defaultValue=\"${AodStyle.DEFAULT.prefValue}\"")
            assertThat(block).contains("android:entryValues=\"@array/face_aod_style_values\"")
        }
    }
}
