package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey

enum class IntComposedKey(
    override val key: String,
    override val format: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntComposedNonPreferenceKey {

    WidgetOpacity("appwidget_", "%d", 25),
    // quick-bolus button amounts of the home-screen widget in 0.01 U, per widget id (own prefix: allMatchingInts uses startsWith)
    WidgetBolusAmount1("widget_bolus1_cu_", "%d", 50),
    WidgetBolusAmount2("widget_bolus2_cu_", "%d", 100),
}