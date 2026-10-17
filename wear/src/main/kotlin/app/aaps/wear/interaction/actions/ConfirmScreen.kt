package app.aaps.wear.interaction.actions

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmDetails
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmWarning
import app.aaps.wear.R
import kotlinx.serialization.json.Json
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fills the two pages of the watch confirm screen (AcceptActivity): page 1 tells what is confirmed (the amount large),
 * warnings, the estimated effect and the timing; page 2 holds the confirm button.
 *
 * DISPLAY ONLY. Everything shown comes from the phone's ConfirmAction (title, message and the optional
 * [ConfirmDetails]); nothing here decides what is delivered: the confirm button sends the phone's returnCommand
 * unchanged (AcceptActivity). Without details (an older phone, status and error screens) the phone's message text is
 * shown as before, only restyled.
 *
 * The amount is printed exactly (up to 3 decimals, never rounded to fewer than it has); estimates always carry "≈".
 */
class ConfirmScreen(private val context: Context) {

    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    // ------------------------------------------------------------------ page 1

    fun bindInfoPage(page: View, title: String, message: String, details: ConfirmDetails?, isError: Boolean) {
        val kind = page.findViewById<TextView>(R.id.confirm_kind)
        val amount = page.findViewById<TextView>(R.id.confirm_amount)
        val amount2 = page.findViewById<TextView>(R.id.confirm_amount2)
        val warnings = page.findViewById<LinearLayout>(R.id.confirm_warnings)
        val sections = page.findViewById<LinearLayout>(R.id.confirm_sections)
        val messageView = page.findViewById<TextView>(R.id.message)
        val hint = page.findViewById<TextView>(R.id.confirm_swipe_hint)

        if (details == null || details.kind == ConfirmDetails.KIND_OTHER) {
            // legacy / status / error: the phone's title and text
            kind.text = title.uppercase(locale)
            kind.setTextColor(color(if (isError) R.color.confirm_danger else R.color.confirm_muted))
            amount.visibility = View.GONE
            amount2.visibility = View.GONE
            warnings.visibility = View.GONE
            sections.visibility = View.GONE
            messageView.visibility = View.VISIBLE
            messageView.text = message
            messageView.gravity = if (message.length <= 90) Gravity.CENTER_HORIZONTAL else Gravity.START
            hint.visibility = if (isError) View.GONE else View.VISIBLE
            return
        }

        messageView.visibility = View.GONE
        // "RECHNER" and, below it, the quick wizard's own name as the user wrote it
        kind.text = SpannableStringBuilder(kindLabel(details).uppercase(locale)).apply {
            details.label.trim().takeIf { details.kind == ConfirmDetails.KIND_WIZARD && it.isNotEmpty() }?.let {
                append("\n")
                val start = length
                append(it)
                setSpan(ForegroundColorSpan(color(R.color.confirm_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(1.15f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        kind.letterSpacing = 0.1f
        kind.setTextColor(color(R.color.confirm_muted))
        amount.text = headline(details)
        val second = secondLine(details)
        amount2.visibility = if (second == null) View.GONE else View.VISIBLE
        amount2.text = second

        warnings.removeAllViews()
        warnings.visibility = if (details.warnings.isEmpty()) View.GONE else View.VISIBLE
        details.warnings.forEachIndexed { index, w -> warnings.addView(warningChip(details, w, first = index == 0)) }

        sections.removeAllViews()
        effectRows(details).takeIf { it.isNotEmpty() }?.let { sections.addView(card(context.getString(R.string.confirm_section_effect), it)) }
        timingRows(details).takeIf { it.isNotEmpty() }?.let { sections.addView(card(context.getString(R.string.confirm_section_timing), it)) }
        details.note.trim().takeIf { it.isNotEmpty() }?.let { sections.addView(noteCard(it)) }
        sections.visibility = if (sections.childCount > 0) View.VISIBLE else View.GONE
        hint.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ page 2

    fun bindConfirmPage(page: View, details: ConfirmDetails?, isError: Boolean) {
        val kind = page.findViewById<TextView>(R.id.confirm_ok_kind)
        val amount = page.findViewById<TextView>(R.id.confirm_ok_amount)
        val hint = page.findViewById<TextView>(R.id.confirm_ok_hint)
        val warning = page.findViewById<TextView>(R.id.confirm_ok_warning)
        val d = details?.takeIf { it.kind != ConfirmDetails.KIND_OTHER }
        if (isError) {
            // an error screen only gets closed: neutral button, no green
            page.findViewById<ImageView>(R.id.confirmbutton)?.let { button ->
                button.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.confirm_card))
                    setStroke(dp(3), color(R.color.confirm_muted))
                }
                button.setColorFilter(color(R.color.confirm_text_dim))
            }
        }
        if (d == null) {
            kind.visibility = View.GONE
            amount.visibility = View.GONE
            warning.visibility = View.GONE
            hint.text = context.getString(if (isError) R.string.confirm_tap_close else R.string.confirm_tap_confirm)
            return
        }
        // page 2 has room for one more line only: the carbs next to insulin win over the kind (page 1 shows both)
        val second = secondLine(d)
        kind.visibility = if (second == null) View.VISIBLE else View.GONE
        kind.text = kindLabel(d)
        amount.visibility = View.VISIBLE
        amount.text = second?.let { second ->
            SpannableStringBuilder(headline(d)).apply {
                append("\n")
                val start = length
                append(second)
                setSpan(RelativeSizeSpan(0.7f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color(R.color.confirm_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } ?: headline(d)
        hint.text = context.getString(
            when {
                isError                 -> R.string.confirm_tap_close
                d.insulin > 0.0         -> R.string.confirm_tap_deliver
                d.carbs != 0            -> R.string.confirm_tap_enter
                else                    -> R.string.confirm_tap_confirm
            }
        )
        val top = d.warnings.firstOrNull()
        if (top == null) warning.visibility = View.GONE
        else {
            warning.visibility = View.VISIBLE
            val more = d.warnings.size - 1
            warning.text = warningWord(top) + if (more > 0) " · " + context.getString(R.string.confirm_warnings_more, more) else ""
            warning.setTextColor(color(levelColor(top.level)))
        }
    }

    // ------------------------------------------------------------------ content

    /** What is confirmed ("Bolus", "Rechner", ...); the quick wizard's name is shown separately. */
    fun kindLabel(d: ConfirmDetails): String = when (d.kind) {
        ConfirmDetails.KIND_BOLUS  -> context.getString(if (d.carbs != 0) R.string.confirm_kind_bolus_carbs else R.string.confirm_kind_bolus)
        ConfirmDetails.KIND_CARBS  -> context.getString(R.string.confirm_kind_carbs)
        ConfirmDetails.KIND_WIZARD -> context.getString(R.string.confirm_kind_wizard)
        ConfirmDetails.KIND_ECARBS -> context.getString(R.string.confirm_kind_ecarbs)
        ConfirmDetails.KIND_FILL   -> context.getString(R.string.confirm_kind_fill)
        else                       -> ""
    }

    /** The big amount: insulin when there is any (or a fill), otherwise the carbs. */
    fun headline(d: ConfirmDetails): String =
        if (d.insulin > 0.0 || d.kind == ConfirmDetails.KIND_FILL) context.getString(R.string.confirm_insulin, insulin(d.insulin))
        else context.getString(R.string.confirm_grams, signed(d.carbs))

    /** Carbs next to insulin ("+ 20 g KH"), null when the headline already is the carbs or there are none. */
    fun secondLine(d: ConfirmDetails): String? =
        if ((d.insulin > 0.0 || d.kind == ConfirmDetails.KIND_FILL) && d.carbs != 0)
            (if (d.carbs > 0) "+ " else "− ") + context.getString(R.string.confirm_plus_carbs, abs(d.carbs).toString())
        else null

    private class Row(val label: String, val value: String, val sub: String? = null)

    private fun effectRows(d: ConfirmDetails): List<Row> {
        val rows = ArrayList<Row>()
        // with carbs in the same treatment the insulin's drop alone is not where BG goes: say so
        val bgLabel = if (d.insulin > 0.0 && d.carbs > 0) R.string.confirm_row_bg_without_carbs else R.string.confirm_row_bg
        // the effect card's header carries the "≈" for all its rows
        d.bgEffectMgdl?.let { rows += Row(context.getString(bgLabel), glucoseDelta(it, d.unitsMgdl)) }
        val iobNow = d.iobNow
        val iobAfter = d.iobAfter
        if (iobNow != null && iobAfter != null)
            rows += Row(context.getString(R.string.confirm_row_iob), context.getString(R.string.confirm_change, iob(iobNow), iob(iobAfter)) + " U")
        d.carbsCovered?.let {
            val v = if (d.carbs > 0) context.getString(R.string.confirm_covers_of, grams(it), d.carbs.toString())
            else context.getString(R.string.confirm_grams, grams(it))
            rows += Row(context.getString(R.string.confirm_row_covers), v)
        }
        d.insulinForCarbs?.let {
            rows += Row(context.getString(R.string.confirm_row_needs), context.getString(R.string.confirm_insulin, iob(it)))
        }
        val cobNow = d.cobNow
        val cobAfter = d.cobAfter
        if (cobNow != null && cobAfter != null)
            rows += Row(context.getString(R.string.confirm_row_cob), context.getString(R.string.confirm_change, grams(cobNow), grams(cobAfter)) + " g")
        return rows
    }

    private fun timingRows(d: ConfirmDetails): List<Row> {
        val rows = ArrayList<Row>()
        d.deliverySeconds?.let { rows += Row(context.getString(R.string.confirm_row_delivery), duration(it)) }
        d.insulinPeakMin?.let { rows += Row(context.getString(R.string.confirm_row_peak), context.getString(R.string.confirm_in_minutes, it)) }
        d.carbsStart?.let { rows += Row(context.getString(R.string.confirm_row_start), time(it)) }
        d.carbsDurationH?.takeIf { it > 0 }?.let { rows += Row(context.getString(R.string.confirm_row_duration), context.getString(R.string.confirm_hours, it)) }
        d.lastBolusMinAgo?.let { ago ->
            val agoText = minutesAgo(ago)
            rows += d.lastBolusAmount?.let { Row(context.getString(R.string.confirm_row_last_bolus), context.getString(R.string.confirm_insulin, insulin(it)), agoText) }
                ?: Row(context.getString(R.string.confirm_row_last_bolus), agoText)
        }
        return rows
    }

    /** The warning's word, e.g. "TIEF" (always a word, never colour alone). */
    fun warningWord(w: ConfirmWarning): String = context.getString(
        when (w.code) {
            ConfirmWarning.W_BG_LOW         -> R.string.confirm_w_low
            ConfirmWarning.W_BG_FALLING     -> if ((w.value ?: 0.0) <= -FALLING_FAST_MGDL) R.string.confirm_w_falling_fast else R.string.confirm_w_falling
            ConfirmWarning.W_BG_STALE       -> R.string.confirm_w_stale
            ConfirmWarning.W_NO_BG          -> R.string.confirm_w_no_bg
            ConfirmWarning.W_RECENT_BOLUS   -> R.string.confirm_w_recent_bolus
            ConfirmWarning.W_NEAR_MAX_BOLUS -> R.string.confirm_w_near_max
            ConfirmWarning.W_CONSTRAINT     -> R.string.confirm_w_constraint
            else                            -> R.string.confirm_w_other
        }
    )

    /** The warning's detail line, e.g. "BZ 3,6 mmol/L", or null. */
    fun warningDetail(d: ConfirmDetails, w: ConfirmWarning): String? = when (w.code) {
        ConfirmWarning.W_BG_LOW         -> w.value?.let { context.getString(R.string.confirm_row_bg) + " " + glucose(it, d.unitsMgdl) + unitSuffix(d.unitsMgdl) + arrowSuffix(d) }
        ConfirmWarning.W_BG_FALLING     -> w.value?.let { context.getString(R.string.confirm_per_5min, glucoseDelta(it, d.unitsMgdl, withUnit = false)) }
        ConfirmWarning.W_BG_STALE       -> w.value?.let { context.getString(R.string.confirm_w_stale_detail, it.roundToInt()) }
        ConfirmWarning.W_NO_BG          -> context.getString(R.string.confirm_w_no_bg_detail)
        ConfirmWarning.W_RECENT_BOLUS   -> w.value?.let { ago ->
            listOfNotNull(w.value2?.let { context.getString(R.string.confirm_insulin, insulin(it)) }, minutesAgo(ago.roundToInt())).joinToString(" · ")
        }

        ConfirmWarning.W_NEAR_MAX_BOLUS -> w.value?.let { context.getString(R.string.confirm_w_near_max_detail, insulin(it)) }
        ConfirmWarning.W_CONSTRAINT     -> listOfNotNull(
            w.value?.let { context.getString(R.string.confirm_insulin, insulin(it)) },
            w.value2?.let { context.getString(R.string.confirm_grams, it.roundToInt().toString()) }
        ).takeIf { it.isNotEmpty() }?.let { context.getString(R.string.confirm_w_constraint_detail, it.joinToString(" + ")) }

        else                            -> null
    }

    // ------------------------------------------------------------------ views

    private fun warningChip(d: ConfirmDetails, w: ConfirmWarning, first: Boolean): View {
        val c = color(levelColor(w.level))
        val text = SpannableStringBuilder().apply {
            val word = warningWord(w)
            append(word)
            setSpan(ForegroundColorSpan(c), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            warningDetail(d, w)?.let {
                append("  ")
                val start = length
                append(it)
                setSpan(RelativeSizeSpan(0.92f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return TextView(context).apply {
            this.text = text
            setTextColor(color(R.color.confirm_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
            setPadding(dp(10), dp(5), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(if (w.level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger_tint else R.color.confirm_caution_tint))
                setStroke(dp(1.5f), c)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (first) 0 else dp(5)
            }
        }
    }

    private fun card(header: String, rows: List<Row>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.confirm_card)
        setPadding(dp(8), dp(8), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(sectionHeader(header))
        rows.forEach { addView(row(it)) }
    }

    private fun noteCard(note: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.confirm_card)
        setPadding(dp(12), dp(7), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        addView(sectionHeader(context.getString(R.string.confirm_section_calc)))
        addView(TextView(context).apply {
            text = note
            setTextColor(color(R.color.confirm_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.08f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        })
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(locale)
        setTextColor(color(R.color.confirm_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.1f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(1) }
    }

    /** One centred line "label  value" (round screen: centred lines fit the circle best), the sub line below it. */
    private fun row(r: Row): View = TextView(context).apply {
        text = SpannableStringBuilder().apply {
            append(r.label)
            setSpan(ForegroundColorSpan(color(R.color.confirm_muted)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.88f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.NORMAL), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            append(r.value)
            r.sub?.let {
                append("\n")
                val start = length
                append(it)
                setSpan(ForegroundColorSpan(color(R.color.confirm_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        setTextColor(color(R.color.confirm_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    // ------------------------------------------------------------------ formatting

    private fun fmt(pattern: String, v: Double): String = DecimalFormat(pattern, symbols).format(v)

    /** Insulin exactly as offered: at least one, at most three decimals ("0,5", "1,25", "0,025"). */
    fun insulin(v: Double): String = fmt("0.0##", v)

    private fun iob(v: Double): String = fmt("0.00", v)
    private fun grams(v: Double): String = fmt("0.#", v)
    private fun signed(v: Int): String = if (v < 0) "−" + abs(v) else v.toString()

    fun glucose(mgdl: Double, unitsMgdl: Boolean): String = if (unitsMgdl) fmt("0", mgdl) else fmt("0.0", mgdl * MGDL_TO_MMOL)

    /** A signed glucose change with its unit: "−1,2 mmol/L", "+32 mg/dL". */
    fun glucoseDelta(mgdl: Double, unitsMgdl: Boolean, withUnit: Boolean = true): String {
        val magnitude = glucose(abs(mgdl), unitsMgdl)
        val zero = magnitude.all { it == '0' || it == symbols.decimalSeparator }
        val sign = if (zero) "" else if (mgdl < 0) "−" else "+"
        return sign + magnitude + if (withUnit) unitSuffix(unitsMgdl) else ""
    }

    private fun unitSuffix(unitsMgdl: Boolean) = if (unitsMgdl) "\u00A0mg/dL" else "\u00A0mmol/L"
    private fun arrowSuffix(d: ConfirmDetails) = if (d.arrow.isBlank() || d.arrow == "??") "" else " " + d.arrow

    private fun duration(seconds: Int): String =
        if (seconds < 60) context.getString(R.string.confirm_seconds, seconds)
        else if (seconds % 60 == 0) context.getString(R.string.confirm_approx, "${seconds / 60} min")
        else context.getString(R.string.confirm_min_seconds, seconds / 60, seconds % 60)

    private fun minutesAgo(min: Int): String = context.getString(R.string.confirm_minutes_ago, min)

    private fun time(ts: Long): String =
        SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale).format(Date(ts))

    @ColorRes private fun levelColor(level: Int) = if (level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger else R.color.confirm_caution
    private fun color(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).roundToInt()

    companion object {

        /** Same factor as the phone (Constants.MGDL_TO_MMOLL). */
        const val MGDL_TO_MMOL = 0.0555
        /** Same as FaceState.FAST_DELTA_MGDL. */
        const val FALLING_FAST_MGDL = 10.0

        /** Intent extra carrying [ConfirmDetails] as JSON. */
        const val KEY_DETAILS = "confirm_details"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(details: ConfirmDetails?): String? = details?.let { json.encodeToString(ConfirmDetails.serializer(), it) }

        /** The details from the intent extra, or null (absent or unreadable: the plain message is shown). */
        fun decode(s: String?): ConfirmDetails? = if (s.isNullOrBlank()) null else try {
            json.decodeFromString(ConfirmDetails.serializer(), s)
        } catch (_: Exception) {
            null
        }
    }
}
