package app.aaps.wear.watchfaces.common

import app.aaps.core.interfaces.sharedPreferences.SP

/**
 * Always-on (ambient) style of the Halo, Glass and Orbit faces: ONE shared, watch-only setting (face_aod_style).
 * Only the ambient drawing reads it; interactive drawing, taps and the bolus request do not depend on it, and the
 * bolus ball is never drawn in ambient whatever the style.
 *
 * - [OUTLINE] (default): the face's own layout as thin outlines, no fills (the original AOD concept).
 * - [MINIMAL]: only the essentials (BG, arrow, time, data age / alarm words), fewest lit pixels.
 * - [GLANCE]: a little more context (e.g. delta, IOB, loop age) still as thin strokes on black.
 *
 * Watch-only key: not part of CwfMetadataKey. Must equal key_face_aod_style and the entry values in
 * strings_face_aod.xml (unit test). An unknown stored value falls back to [OUTLINE].
 */
enum class AodStyle(val prefValue: String) {
    OUTLINE("outline"),
    MINIMAL("minimal"),
    GLANCE("glance");

    companion object {

        const val KEY = "face_aod_style"
        val DEFAULT = OUTLINE

        fun fromPref(value: String?): AodStyle = entries.firstOrNull { it.prefValue == value?.trim() } ?: DEFAULT

        fun read(sp: SP): AodStyle = fromPref(sp.getStringOrNull(KEY, null))
    }
}
