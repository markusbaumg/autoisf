package app.aaps.implementation.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.joran.util.PropertySetter
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WritableLogFileDefinerTest {

    @TempDir lateinit var dir: File

    private fun definer(directory: File) = WritableLogFileDefiner().also {
        it.directory = directory.path
        it.fileName = "AndroidAPS.log"
    }

    @Test
    fun `the usual file when it can be written, created if missing`() {
        assertThat(definer(dir).propertyValue).isEqualTo(File(dir, "AndroidAPS.log").path)
        assertThat(File(dir, "AndroidAPS.log").exists()).isTrue()
    }

    @Test
    fun `an existing writable file is kept and not truncated`() {
        File(dir, "AndroidAPS.log").writeText("earlier lines")
        assertThat(definer(dir).propertyValue).isEqualTo(File(dir, "AndroidAPS.log").path)
        assertThat(File(dir, "AndroidAPS.log").readText()).isEqualTo("earlier lines")
    }

    @Test
    fun `a file that can not be written is passed over for the first alternative that can`() {
        File(dir, "AndroidAPS.log").apply { writeText("old"); setWritable(false) }
        File(dir, "AndroidAPS_1.log").apply { writeText("old"); setWritable(false) }

        assertThat(definer(dir).propertyValue).isEqualTo(File(dir, "AndroidAPS_2.log").path)
    }

    @Test
    fun `the directory is created`() {
        val sub = File(dir, "aapsLogs")
        assertThat(definer(sub).propertyValue).isEqualTo(File(sub, "AndroidAPS.log").path)
    }

    @Test
    fun `nothing writable keeps the usual name`() {
        dir.setWritable(false)
        try {
            assertThat(definer(File(dir, "missing")).propertyValue).isEqualTo(File(File(dir, "missing"), "AndroidAPS.log").path)
        } finally {
            dir.setWritable(true)
        }
    }

    /**
     * logback sets the nested elements of `<define>` with its PropertySetter: `<directory>` and
     * `<fileName>` in app/src/main/assets/logback.xml must reach this class.
     */
    @Test
    fun `logback can set directory and fileName as logback xml does`() {
        val xml = File("../app/src/main/assets/logback.xml").readText()
        assertThat(xml).contains("class=\"${WritableLogFileDefiner::class.java.name}\"")
        assertThat(xml).contains("<file>\${ACTIVE_LOG_FILE}</file>")

        val definer = WritableLogFileDefiner()
        val setter = PropertySetter(definer).also { it.context = LoggerContext() }
        setter.setProperty("directory", dir.path)
        setter.setProperty("fileName", "AndroidAPS.log")

        assertThat(definer.directory).isEqualTo(dir.path)
        assertThat(definer.propertyValue).isEqualTo(File(dir, "AndroidAPS.log").path)
    }
}
