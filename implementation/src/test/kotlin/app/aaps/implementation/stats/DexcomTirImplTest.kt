package app.aaps.implementation.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DexcomTirImplTest {

    private val time = 1_700_000_000_000L

    @Test
    fun `no readings give no HbA1c`() {
        assertThat(DexcomTirImpl().hbA1cPercent()).isNull()
        assertThat(DexcomTirImpl().meanMgdl()).isNull()
    }

    @Test
    fun `error readings do not count`() {
        val tir = DexcomTirImpl()
        tir.add(time, 20.0)
        assertThat(tir.meanMgdl()).isNull()
        assertThat(tir.hbA1cPercent()).isNull()
    }

    @Test
    fun `HbA1c is (mean + 46,7) div 28,7 with one decimal`() {
        val tir = DexcomTirImpl()
        // mean 128 mg/dL -> (128 + 46.7) / 28.7 = 6.087 -> 6.1
        tir.add(time, 100.0)
        tir.add(time, 156.0)
        tir.add(time, 20.0) // error, ignored
        assertThat(tir.meanMgdl()).isEqualTo(128.0)
        assertThat(tir.hbA1cPercent()).isEqualTo(6.1)
    }

    @Test
    fun `HbA1c on an exact value`() {
        val tir = DexcomTirImpl()
        // (125.5 + 46.7) / 28.7 = 6.0 exactly
        tir.add(time, 125.5)
        assertThat(tir.hbA1cPercent()).isEqualTo(6.0)
    }
}
