package app.aaps.implementation.logging

import ch.qos.logback.core.PropertyDefinerBase
import java.io.File
import java.io.FileOutputStream

/**
 * The path of the active log file, for logback.xml (`<define>`): [fileName] in [directory] if this
 * process can append to it, else the first of `<name>_1<ext>` .. `<name>_9<ext>` it can append to.
 *
 * The logs live in shared storage (Documents/aapsLogs). There a file belongs to the app that created
 * it, and Android drops that ownership when the app's data is cleared or the app is uninstalled. The
 * new process can then neither open the old AndroidAPS.log for writing nor rename or delete it, and
 * logback's file appender fails to start without any sign outside its own status list: no file log
 * until someone renames the file by hand. With this the log goes on in a file the app can write to.
 * The names still start with "AndroidAPS" and end with ".log", so the log export picks them up, and
 * the hourly rollover archives them as before.
 */
class WritableLogFileDefiner : PropertyDefinerBase() {

    /** Set by logback from `<directory>`. */
    var directory: String? = null

    /** Set by logback from `<fileName>`. */
    var fileName: String = DEFAULT_FILE_NAME

    override fun getPropertyValue(): String {
        val dir = directory ?: return fileName
        return firstWritable(File(dir), fileName).path
    }

    companion object {

        const val DEFAULT_FILE_NAME = "AndroidAPS.log"
        const val ALTERNATIVES = 9

        fun firstWritable(dir: File, fileName: String): File {
            dir.mkdirs()
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val extension = if (dot > 0) fileName.substring(dot) else ""
            val candidates = listOf(fileName) + (1..ALTERNATIVES).map { "${base}_$it$extension" }
            // Nothing writable at all (no storage access): keep the usual name, as before
            return candidates.map { File(dir, it) }.firstOrNull { canAppend(it) } ?: File(dir, fileName)
        }

        /** What logback's file appender does: open for append, creating the file if missing. */
        private fun canAppend(file: File): Boolean =
            try {
                FileOutputStream(file, true).close()
                true
            } catch (_: Exception) {
                false
            }
    }
}
