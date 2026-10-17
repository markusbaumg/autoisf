package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.sharedPreferences.SP
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class BolusRequestPrefsTest {

    private val map = HashMap<String, Any?>()
    private lateinit var sp: SP

    @BeforeEach fun setUp() {
        map.clear()
        sp = mock()
        doAnswer { map.containsKey(it.getArgument<String>(0)) }.whenever(sp).contains(anyString())
        doAnswer { map.remove(it.getArgument<String>(0)); Unit }.whenever(sp).remove(anyString())
        doAnswer { map[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1) }.whenever(sp).getBoolean(anyString(), anyBoolean())
        doAnswer { map[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1) }.whenever(sp).getStringOrNull(anyString(), anyOrNull())
        doAnswer { map[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1) }.whenever(sp).getLong(anyString(), anyLong())
        doAnswer { map[it.getArgument<String>(0)] as? Double ?: it.getArgument<Double>(1) }.whenever(sp).getDouble(anyString(), anyDouble())
        doAnswer { map[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); Unit }.whenever(sp).putBoolean(anyString(), anyBoolean())
        doAnswer { map[it.getArgument<String>(0)] = it.getArgument<String>(1); Unit }.whenever(sp).putString(anyString(), anyString())
        doAnswer { map[it.getArgument<String>(0)] = it.getArgument<Long>(1); Unit }.whenever(sp).putLong(anyString(), anyLong())
    }

    @Test fun defaultsOffAndHalfUnit() {
        assertThat(BolusRequestPrefs.enabled(sp)).isFalse()
        assertThat(BolusRequestPrefs.amount(sp)).isEqualTo(0.5)
        map[BolusRequestPrefs.KEY_AMOUNT] = "1.0"
        assertThat(BolusRequestPrefs.amount(sp)).isEqualTo(1.0)
        map[BolusRequestPrefs.KEY_AMOUNT] = "2.0"
        assertThat(BolusRequestPrefs.amount(sp).isNaN()).isTrue() // not allowed: the button stays hidden
    }

    @Test fun unknownMaxBolusIsNaN() {
        assertThat(BolusRequestPrefs.maxBolus(sp, "max").isNaN()).isTrue()
        map["max"] = 2.5
        assertThat(BolusRequestPrefs.maxBolus(sp, "max")).isEqualTo(2.5)
    }

    @Test fun nothingToMigrate() {
        BolusRequestPrefs.migrate(sp)
        assertThat(map).isEmpty()
    }

    @Test fun migratesHaloSetting() {
        map["halo_bolus_double_tap"] = true
        map["halo_bolus_amount"] = "1.0"
        map["halo_bolus_last_sent_at"] = 1234L
        BolusRequestPrefs.migrate(sp)
        assertThat(map).containsExactly(
            BolusRequestPrefs.KEY_ENABLED, true,
            BolusRequestPrefs.KEY_AMOUNT, "1.0",
            BolusRequestPrefs.KEY_LAST_SENT_AT, 1234L
        )
    }

    @Test fun migratesKusiAndOrbitWithoutRaisingAnything() {
        map["kusi_bolus_double_tap"] = true
        map["kusi_bolus_amount"] = "1.0"
        map["orbit_bolus_double_tap"] = true
        map["orbit_bolus_amount"] = "0.5"
        map["halo_bolus_double_tap"] = false
        map["halo_bolus_amount"] = "1.0"
        BolusRequestPrefs.migrate(sp)
        // on (an old switch was on), smallest amount of the faces that had it on
        assertThat(map).containsExactly(BolusRequestPrefs.KEY_ENABLED, true, BolusRequestPrefs.KEY_AMOUNT, "0.5")
    }

    @Test fun migrationKeepsOffAndInvalidAmountsOut() {
        map["orbit_bolus_double_tap"] = false
        map["orbit_bolus_amount"] = "3"
        BolusRequestPrefs.migrate(sp)
        assertThat(map).containsExactly(BolusRequestPrefs.KEY_ENABLED, false)
    }

    @Test fun existingSharedValuesWinAndMigrationIsIdempotent() {
        map[BolusRequestPrefs.KEY_ENABLED] = false
        map[BolusRequestPrefs.KEY_AMOUNT] = "0.5"
        map["halo_bolus_double_tap"] = true
        map["halo_bolus_amount"] = "1.0"
        BolusRequestPrefs.migrate(sp)
        BolusRequestPrefs.migrate(sp)
        assertThat(map).containsExactly(BolusRequestPrefs.KEY_ENABLED, false, BolusRequestPrefs.KEY_AMOUNT, "0.5")
    }

    @Test fun lastSentStoreUsesTheSharedKey() {
        val store = BolusRequestPrefs.lastSentStore { sp }
        assertThat(store.lastSentAt).isEqualTo(0L)
        store.lastSentAt = 42L
        assertThat(map[BolusRequestPrefs.KEY_LAST_SENT_AT]).isEqualTo(42L)
    }

    @Test fun notSettableFromACustomWatchface() {
        val keys = CwfMetadataKey.entries.map { it.key }
        assertThat(keys).containsNoneOf(BolusRequestPrefs.KEY_ENABLED, BolusRequestPrefs.KEY_AMOUNT)
        assertThat(keys.none { it.contains("bolus", ignoreCase = true) }).isTrue()
    }

    @Test fun keyConstantsMatchResources() {
        val xml = File("src/main/res/values/strings_face_bolus.xml").readText()
        assertThat(xml).contains("name=\"key_face_bolus_request_enabled\" translatable=\"false\">${BolusRequestPrefs.KEY_ENABLED}<")
        assertThat(xml).contains("name=\"key_face_bolus_request_amount\" translatable=\"false\">${BolusRequestPrefs.KEY_AMOUNT}<")
    }

    @Test fun allThreeFacesUseTheSharedSwitchDefaultOff() {
        for (face in listOf("halo", "glass", "orbit")) {
            val xml = File("src/main/res/xml/watch_face_configuration_$face.xml").readText()
            val block = xml.substringBefore("android:key=\"@string/key_face_bolus_request_enabled\"").substringAfterLast("<CheckBoxPreference")
            assertThat(block).contains("android:defaultValue=\"false\"")
            assertThat(xml).contains("android:key=\"@string/key_face_bolus_request_amount\"")
            assertThat(xml).doesNotContain("halo_bolus")
            assertThat(xml).doesNotContain("orbit_bolus")
        }
    }

    @Test fun modeDefaultsToFixedAmount() {
        assertThat(BolusRequestPrefs.mode(sp)).isEqualTo(BolusRequestPrefs.Mode.FIXED)
        map[BolusRequestPrefs.KEY_MODE] = "picker"
        assertThat(BolusRequestPrefs.mode(sp)).isEqualTo(BolusRequestPrefs.Mode.PICKER)
        map[BolusRequestPrefs.KEY_MODE] = "something"
        assertThat(BolusRequestPrefs.mode(sp)).isEqualTo(BolusRequestPrefs.Mode.FIXED)
        map[BolusRequestPrefs.KEY_MODE] = "fixed"
        assertThat(BolusRequestPrefs.mode(sp)).isEqualTo(BolusRequestPrefs.Mode.FIXED)
    }

    @Test fun oneAndAHalfOffByDefault() {
        assertThat(BolusRequestPrefs.allow15(sp)).isFalse()
        assertThat(BolusRequestPrefs.pickerAmounts(sp)).containsExactly(0.5, 1.0).inOrder()
        map[BolusRequestPrefs.KEY_ALLOW_1_5] = true
        assertThat(BolusRequestPrefs.pickerAmounts(sp)).containsExactly(0.5, 1.0, 1.5).inOrder()
        // the fixed ball's amount setting is unchanged: 1.5 is not one of its values
        map[BolusRequestPrefs.KEY_AMOUNT] = "1.5"
        assertThat(BolusRequestPrefs.amount(sp).isNaN()).isTrue()
    }

    @Test fun pickerKeysNotSettableFromACustomWatchface() {
        val keys = CwfMetadataKey.entries.map { it.key }
        assertThat(keys).containsNoneOf(BolusRequestPrefs.KEY_MODE, BolusRequestPrefs.KEY_ALLOW_1_5)
    }

    @Test fun pickerKeyConstantsMatchResources() {
        val xml = File("src/main/res/values/strings_face_bolus.xml").readText()
        assertThat(xml).contains("name=\"key_face_bolus_request_mode\" translatable=\"false\">${BolusRequestPrefs.KEY_MODE}<")
        assertThat(xml).contains("name=\"key_face_bolus_request_allow_1_5\" translatable=\"false\">${BolusRequestPrefs.KEY_ALLOW_1_5}<")
        assertThat(xml).contains("<item>${BolusRequestPrefs.MODE_FIXED}</item>")
        assertThat(xml).contains("<item>${BolusRequestPrefs.MODE_PICKER}</item>")
    }

    @Test fun orbitOffersTheModeAndTheSwitchWithSafeDefaults() = assertPickerSettings("orbit")

    @Test fun glassOffersTheModeAndTheSwitchWithSafeDefaults() = assertPickerSettings("glass")

    private fun assertPickerSettings(face: String) {
        val xml = File("src/main/res/xml/watch_face_configuration_$face.xml").readText()
        // the shared master switch stays first
        assertThat(xml.indexOf("key_face_bolus_request_enabled\"")).isLessThan(xml.indexOf("key_face_bolus_request_mode\""))
        val mode = xml.substringBefore("android:key=\"@string/key_face_bolus_request_mode\"").substringAfterLast("<ListPreference")
        assertThat(mode).contains("android:defaultValue=\"fixed\"")
        assertThat(mode).contains("android:dependency=\"@string/key_face_bolus_request_enabled\"")
        val allow = xml.substringBefore("android:key=\"@string/key_face_bolus_request_allow_1_5\"").substringAfterLast("<CheckBoxPreference")
        assertThat(allow).contains("android:defaultValue=\"false\"")
    }

    @Test fun haloHasNoPickerSettingsYet() {
        for (face in listOf("halo")) {
            val other = File("src/main/res/xml/watch_face_configuration_$face.xml").readText()
            assertThat(other).doesNotContain("key_face_bolus_request_mode")
            assertThat(other).doesNotContain("key_face_bolus_request_allow_1_5")
        }
    }
}
