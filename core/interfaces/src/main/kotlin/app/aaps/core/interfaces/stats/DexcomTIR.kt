package app.aaps.core.interfaces.stats

import android.content.Context
import android.widget.TableRow
import android.widget.TextView
import app.aaps.core.interfaces.profile.ProfileUtil

interface DexcomTIR {

    fun calculateSD(): Double

    /** Mean of the valid readings in mg/dL, null without readings. Read only. */
    fun meanMgdl(): Double?

    /** Estimated HbA1c in % as the statistics screen shows it: (mean mg/dL + 46.7) / 28.7, 1 decimal. null without readings. */
    fun hbA1cPercent(): Double?
    fun toHbA1cView(context: Context): TextView
    fun toSDView(context: Context, profileUtil: ProfileUtil): TextView
    fun toRangeHeaderView(context: Context, profileUtil: ProfileUtil): TextView
    fun toTableRowHeader(context: Context): TableRow
    fun toTableRow(context: Context): TableRow
}
