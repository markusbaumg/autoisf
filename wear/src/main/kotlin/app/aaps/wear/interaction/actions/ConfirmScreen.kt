package app.aaps.wear.interaction.actions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.rx.weardata.EventData
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
 * Fills the two pages of the watch confirm screen (AcceptActivity; page 2 also for the watch wizard's
 * WizardConfirmFragment): page 1 tells what is confirmed (the amount large), warnings, the estimated effect and the
 * timing; page 2 holds the confirm button.
 *
 * DISPLAY ONLY. Everything shown comes from the phone's ConfirmAction (title, message and the optional
 * [ConfirmDetails]); nothing here decides what is delivered: the confirm button sends the phone's returnCommand
 * unchanged (AcceptActivity). Without details (an older phone, status and error screens, or details whose amount does
 * not match the returnCommand, see [matchingDetails]) the phone's message text is shown as before, only restyled.
 *
 * The amount is printed exactly (up to 3 decimals, never rounded to fewer than it has) and never wrapped or cut;
 * estimates always carry "≈".
 */
class ConfirmScreen(private val context: Context) {

    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    // ------------------------------------------------------------------ page 1

    /**
     * @param hasAction false for a screen whose button only closes it (status screens without a returnCommand)
     */
    fun bindInfoPage(page: View, title: String, message: String, details: ConfirmDetails?, isError: Boolean, hasAction: Boolean = true) {
        val errorIcon = page.findViewById<TextView>(R.id.confirm_error_icon)
        val kind = page.findViewById<TextView>(R.id.confirm_kind)
        val label = page.findViewById<TextView>(R.id.confirm_label)
        val amount = page.findViewById<TextView>(R.id.confirm_amount)
        val amount2 = page.findViewById<TextView>(R.id.confirm_amount2)
        val warnings = page.findViewById<LinearLayout>(R.id.confirm_warnings)
        val sections = page.findViewById<LinearLayout>(R.id.confirm_sections)
        val messageView = page.findViewById<TextView>(R.id.message)
        val hint = page.findViewById<TextView>(R.id.confirm_swipe_hint)
        errorIcon.visibility = if (isError) View.VISIBLE else View.GONE
        hint.visibility = if (isError) View.GONE else View.VISIBLE
        hint.text = context.getString(if (hasAction) R.string.confirm_swipe_hint else R.string.confirm_swipe_close)

        if (details == null || details.kind == ConfirmDetails.KIND_OTHER) {
            // legacy / status / error: the phone's title and text
            kind.text = title.uppercase(locale)
            kind.setTextColor(color(if (isError) R.color.confirm_danger else R.color.confirm_muted))
            label.visibility = View.GONE
            amount.visibility = View.GONE
            amount2.visibility = View.GONE
            warnings.visibility = View.GONE
            sections.visibility = View.GONE
            messageView.visibility = View.VISIBLE
            messageView.text = message
            // centred: on the round screen a left-aligned line near the top or bottom runs into the edge
            messageView.gravity = Gravity.CENTER_HORIZONTAL
            return
        }

        messageView.visibility = View.GONE
        kind.text = kindLabel(details).uppercase(locale)
        kind.setTextColor(color(R.color.confirm_muted))
        // the quick wizard's own name, as the user wrote it (no caps, no tracking)
        val name = details.label.trim().takeIf { details.kind == ConfirmDetails.KIND_WIZARD && it.isNotEmpty() }
        label.visibility = if (name == null) View.GONE else View.VISIBLE
        label.text = name
        fitText(kind, dp(130), minSp = 13f, bold = false)
        amount.text = headline(details)
        amount.tag = TAG_AMOUNT
        amount.gravity = Gravity.CENTER_HORIZONTAL
        // the amount is never wrapped or cut: smaller rather than "12,3" without its unit (large doses, large fonts)
        fitText(amount, contentWidthPx() - dp(4), minSp = 30f, bold = false)
        val carbs = carbsBlock(details)
        amount2.visibility = if (carbs == null) View.GONE else View.VISIBLE
        amount2.text = carbs
        amount2.gravity = Gravity.CENTER_HORIZONTAL
        carbs?.let { fitText(amount2, contentWidthPx() - dp(4), minSp = 13f, bold = false) }

        warnings.removeAllViews()
        val shown = shownWarnings(details)
        warnings.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        // with several warnings their number comes first, so that the first view tells there are more below
        if (shown.size > 1) warnings.addView(warningsHeader(shown))
        shown.forEachIndexed { index, w -> warnings.addView(warningChip(details, w, first = index == 0)) }

        sections.removeAllViews()
        effectRows(details).takeIf { it.isNotEmpty() }?.let {
            sections.addView(card(context.getString(R.string.confirm_section_effect), it, sourceLine(details)))
        }
        timingRows(details).takeIf { it.isNotEmpty() }?.let { sections.addView(card(context.getString(R.string.confirm_section_timing), it, null)) }
        details.note.trim().takeIf { it.isNotEmpty() }?.let { sections.addView(noteCard(it)) }
        sections.visibility = if (sections.childCount > 0) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ page 2

    /**
     * @param hasAction false for a screen whose button only closes it (status screens without a returnCommand)
     */
    fun bindConfirmPage(page: View, details: ConfirmDetails?, isError: Boolean, hasAction: Boolean = true) {
        val kind = page.findViewById<TextView>(R.id.confirm_ok_kind)
        val amount = page.findViewById<TextView>(R.id.confirm_ok_amount)
        val amount2 = page.findViewById<TextView>(R.id.confirm_ok_amount2)
        val hint = page.findViewById<TextView>(R.id.confirm_ok_hint)
        val warning = page.findViewById<TextView>(R.id.confirm_ok_warning)
        val button = page.findViewById<ImageView>(R.id.confirmbutton)
        val d = details?.takeIf { it.kind != ConfirmDetails.KIND_OTHER }
        if (isError) {
            // an error screen only gets closed: neutral ring and a cross, no green tick
            button.background = ring(R.color.confirm_muted, R.color.confirm_card)
            button.setImageResource(R.drawable.confirm_ic_close)
            button.imageTintList = ColorStateList.valueOf(color(R.color.confirm_text_dim))
        } else if (!hasAction) {
            // a status screen's button only closes it: neutral, not the green of a treatment
            button.background = ring(R.color.confirm_muted, R.color.confirm_card)
            button.imageTintList = ColorStateList.valueOf(color(R.color.confirm_text))
        }
        if (d == null) {
            kind.visibility = View.GONE
            amount.visibility = View.GONE
            amount2.visibility = View.GONE
            warning.visibility = View.GONE
            hint.text = context.getString(if (isError || !hasAction) R.string.confirm_tap_close else R.string.confirm_tap_confirm)
            return
        }
        // page 2 has room for one more line only: the carbs next to insulin, or the top warning, win over the kind
        // (page 1 shows all of them; the hint below the button still says what the tap does)
        val second = confirmCarbsLine(d)
        val shown = shownWarnings(d)
        val top = shown.firstOrNull()
        kind.visibility = if (second == null && top == null) View.VISIBLE else View.GONE
        kind.text = kindLabel(d).uppercase(locale)
        fitText(kind, dp(110), minSp = 13f, bold = false)
        amount.visibility = View.VISIBLE
        amount.text = headline(d)
        amount.tag = TAG_AMOUNT
        amount2.visibility = if (second == null) View.GONE else View.VISIBLE
        amount2.text = second
        hint.text = context.getString(
            when {
                isError || !hasAction -> R.string.confirm_tap_close
                d.insulin > 0.0       -> R.string.confirm_tap_deliver
                d.carbs != 0          -> R.string.confirm_tap_enter
                else                  -> R.string.confirm_tap_confirm
            }
        )
        if (top == null) {
            warning.visibility = View.GONE
            // near the top of the circle the amount has less width than in the middle; page 2 does not scroll, so a
            // large font setting enlarges the amount only a little (it is large anyway)
            capTextPx(amount, dp(37))
            fitText(amount, dp(135), minSp = 22f, bold = false)
            // the hint is the lowest line, where the circle is narrow (large font settings)
            fitText(hint, dp(145), minSp = 12f, bold = false)
            // a large font setting: a smaller button keeps amount and hint inside the circle
            if (largeFont()) smallButton(button, dp(62))
        } else {
            // the top warning, at the moment of decision, as a chip like page 1's; display only, never blocks.
            // How many more there are is spelled out on its own line ("3 weitere Warnungen"): a bare "+3" next to a
            // BG value or under a dose would read as mmol/L or U.
            warning.visibility = View.VISIBLE
            warning.tag = TAG_CHIP
            warning.text = SpannableStringBuilder().apply {
                append(confirmChipText(d, top))
                val more = shown.size - 1
                if (more > 0) {
                    append("\n")
                    val start = length
                    append(if (more == 1) context.getString(R.string.confirm_warnings_one_more) else context.getString(R.string.confirm_warnings_more, more))
                    setSpan(ForegroundColorSpan(color(R.color.confirm_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(14f / 16f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(TypefaceSpan("sans-serif"), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            warning.setLineSpacing(dp(3).toFloat(), 1f)
            warning.background = chipBackground(top.level, radiusDp = 14f)
            fitText(warning, dp(190) - warning.paddingLeft - warning.paddingRight - dp(4), minSp = 13f, bold = true)
            // with the chip the button is a little smaller; when chip and carbs take more than one extra line the hint
            // goes too (the chip and the ring in its colour say what this moment is about, page 1 said "Weiter zum
            // Bestätigen"), so that everything stays inside the circle
            smallButton(button, dp(if (largeFont()) 50 else 54))
            val extraLines = (second?.lines()?.size ?: 0) + (if (shown.size > 1) 1 else 0)
            if (extraLines > 1 || largeFont()) hint.visibility = View.GONE
            else fitText(hint, dp(145), minSp = 12f, bold = false)
            amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            capTextPx(amount, dp(32))
            fitText(amount, dp(130), minSp = 22f, bold = false)
            if (!isError) {
                button.background = ring(levelColor(top.level), if (top.level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger_tint else R.color.confirm_caution_tint)
                // no green tick inside a red / amber ring: one signal, not two
                button.imageTintList = ColorStateList.valueOf(color(R.color.confirm_text))
            }
        }
        if (second != null) fitText(amount2, dp(180), minSp = 13f, bold = false)
        // two carbs lines (the quick wizard's extended carbs): a smaller button keeps the hint inside the circle
        if (top == null && second?.contains('\n') == true) smallButton(button, dp(60))
    }

    /** Samsung's "large" font settings (1.15 and up). */
    private fun largeFont(): Boolean = context.resources.configuration.fontScale >= 1.1f

    private fun capTextPx(tv: TextView, maxPx: Int) {
        if (tv.textSize > maxPx) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx.toFloat())
    }

    private fun smallButton(button: ImageView, size: Int) {
        (button.layoutParams as LinearLayout.LayoutParams).apply { width = size; height = size; topMargin = dp(7); bottomMargin = dp(4) }
        button.setPadding(size * 13 / 56, size * 13 / 56, size * 13 / 56, size * 13 / 56)
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

    /** When the quick wizard enters its carbs: "in 15 min · mit Alarm", or null when now. */
    fun carbsTiming(d: ConfirmDetails): String? = d.carbsDelayMin?.takeIf { it > 0 && d.carbs > 0 }?.let { min ->
        context.getString(R.string.confirm_carbs_in, min) + if (d.carbsAlarm) " · " + context.getString(R.string.confirm_carbs_alarm) else ""
    }

    /** The quick wizard's extended carbs: "+ 20 g verzögert" and "über 2 Std, ab in 30 min", or null. */
    fun eCarbsLines(d: ConfirmDetails): Pair<String, String?>? = d.eCarbs?.takeIf { it > 0 }?.let { g ->
        context.getString(R.string.confirm_ecarbs_line, g) to d.eCarbsDurationH?.let { h ->
            val offset = d.eCarbsOffsetMin ?: 0
            if (offset > 0) context.getString(R.string.confirm_ecarbs_when, h, offset) else context.getString(R.string.confirm_ecarbs_when_now, h)
        }
    }

    /**
     * Page 1's block under the amount: the carbs ("+ 45 g KH"), when they are entered ("in 15 min · mit Alarm") and
     * the extended carbs, so that the first view shows everything the confirm enters. Null when there is nothing.
     */
    private fun carbsBlock(d: ConfirmDetails): CharSequence? {
        val sb = SpannableStringBuilder()
        fun main(s: String) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(s)
        }

        fun sub(s: String) {
            if (sb.isNotEmpty()) sb.append("\n")
            val start = sb.length
            sb.append(s)
            sb.setSpan(RelativeSizeSpan(17f / 20f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpan("sans-serif"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        secondLine(d)?.let { main(it) }
        carbsTiming(d)?.let { sub(it) }
        eCarbsLines(d)?.let { (line, timing) -> main(line); timing?.let { sub(it) } }
        return if (sb.isEmpty()) null else sb
    }

    /** Page 2's line under the amount, short: "+ 45 g KH · in 15 min", and the extended carbs on a second line. */
    fun confirmCarbsLine(d: ConfirmDetails): String? {
        val carbs = secondLine(d)?.let { s -> d.carbsDelayMin?.takeIf { it > 0 && d.carbs > 0 }?.let { s + " · " + context.getString(R.string.confirm_carbs_in, it) } ?: s }
            ?: carbsTiming(d)?.takeIf { d.insulin <= 0.0 }?.let { context.getString(R.string.confirm_carbs_in, d.carbsDelayMin ?: 0) }
        val e = d.eCarbs?.takeIf { it > 0 }?.let { context.getString(R.string.confirm_ecarbs_short, it) }
        return listOfNotNull(carbs, e).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** How a row's value is set: the effect's headline, plain, secondary, quiet. */
    enum class Emphasis { PRIMARY, NORMAL, SECONDARY, QUIET }

    /** One row of a card: a muted label above its value. */
    class Row(val label: String, val value: String, val emphasis: Emphasis = Emphasis.NORMAL) {

        val quiet: Boolean get() = emphasis == Emphasis.QUIET
    }

    /**
     * The effect card. Its first row is its headline (larger): the BG change, or with carbs in the same treatment the
     * carbs covered; then IOB and the rest, smaller. The insulin's drop alone ("Insulin allein") comes last and quiet
     * when there are carbs: it is not where BG goes. Every estimate carries "≈"; IOB and COB now → after are plain sums
     * and do not.
     */
    fun effectRows(d: ConfirmDetails): List<Row> {
        val rows = ArrayList<Row>()
        val dosing = d.insulin > 0.0 && d.kind != ConfirmDetails.KIND_FILL
        val withCarbs = dosing && d.carbs > 0
        val bg = d.bgEffectMgdl?.let { approx(glucoseDelta(it, d.unitsMgdl)) }
        val covers = d.carbsCovered?.let { c ->
            when {
                !withCarbs                                -> approx(context.getString(R.string.confirm_grams, grams(c)))
                // the wizard's dose already nets correction, IOB, COB, trend and %: "≈ 29 von 45 g" would invite a top-up
                d.kind == ConfirmDetails.KIND_WIZARD      -> approx(context.getString(R.string.confirm_grams, grams(c)))
                c <= d.carbs * COVERS_OF_MAX + 0.5        -> context.getString(R.string.confirm_covers_of, grams(c), d.carbs.toString())
                // more than the carbs: "≈ 50 von 45 g" reads like an error
                else                                      -> context.getString(R.string.confirm_covers_for, grams(c), d.carbs.toString())
            }
        }
        val iob = d.iobNow?.let { now -> d.iobAfter?.let { after -> context.getString(R.string.confirm_change, iob(now), iob(after)) + " U" } }
        val cob = d.cobNow?.let { now -> d.cobAfter?.let { after -> context.getString(R.string.confirm_change, grams(now), grams(after)) + " g" } }
        when {
            withCarbs -> {
                covers?.let { rows += Row(context.getString(R.string.confirm_row_covers), it) }
                iob?.let { rows += Row(context.getString(R.string.confirm_row_iob), it) }
                bg?.let { rows += Row(context.getString(R.string.confirm_row_bg_insulin_only), it, Emphasis.QUIET) }
            }

            dosing    -> {
                bg?.let { rows += Row(context.getString(R.string.confirm_row_bg_down), it) }
                iob?.let { rows += Row(context.getString(R.string.confirm_row_iob), it) }
                covers?.let { rows += Row(context.getString(R.string.confirm_row_covers), it) }
            }

            else      -> bg?.let {
                val up = (d.bgEffectMgdl ?: 0.0) >= 0
                val over = d.carbsDurationH?.takeIf { up && d.kind == ConfirmDetails.KIND_ECARBS && it > 0 }
                rows += Row(
                    when {
                        over != null -> context.getString(R.string.confirm_row_bg_up_over, over)
                        up           -> context.getString(R.string.confirm_row_bg_up)
                        else         -> context.getString(R.string.confirm_row_bg_down)
                    }, it
                )
            }
        }
        cob?.let { rows += Row(context.getString(R.string.confirm_row_cob), it) }
        // the first row is the headline, the others secondary (a quiet row stays quiet)
        return rows.mapIndexed { i, r ->
            Row(r.label, r.value, if (r.emphasis == Emphasis.QUIET) Emphasis.QUIET else if (i == 0) Emphasis.PRIMARY else Emphasis.SECONDARY)
        }
    }

    /** Where the estimate comes from: "Profil: ISF 2,5 · IC 13" (in the user's units), or null. */
    fun sourceLine(d: ConfirmDetails): String? {
        val parts = listOfNotNull(
            d.isfMgdl?.takeIf { it > 0.0 }?.let { "ISF " + glucose(it, d.unitsMgdl) },
            d.ic?.takeIf { it > 0.0 }?.let { "IC " + grams(it) }
        )
        return if (parts.isEmpty()) null else context.getString(R.string.confirm_source, parts.joinToString(" · "))
    }

    fun timingRows(d: ConfirmDetails): List<Row> {
        val rows = ArrayList<Row>()
        d.deliverySeconds?.let { rows += Row(context.getString(R.string.confirm_row_delivery), duration(it)) }
        d.insulinPeakMin?.let { rows += Row(context.getString(R.string.confirm_row_peak), context.getString(R.string.confirm_minutes, it)) }
        d.carbsStart?.let { rows += Row(context.getString(R.string.confirm_row_start), time(it)) }
        d.carbsDurationH?.takeIf { it > 0 }?.let { rows += Row(context.getString(R.string.confirm_row_duration), context.getString(R.string.confirm_hours, it)) }
        d.lastBolusMinAgo?.let { ago ->
            val value = d.lastBolusAmount?.let { context.getString(R.string.confirm_last_bolus_value, ago, context.getString(R.string.confirm_insulin, insulin(it))) }
                ?: context.getString(R.string.confirm_last_bolus_min, ago)
            rows += Row(context.getString(R.string.confirm_row_last_bolus), value)
        }
        return rows
    }

    /**
     * The warnings as shown: BG low and falling together are one chip ("TIEF · FÄLLT") instead of two red ones. The
     * first is the most severe (the phone sends them sorted). Display only.
     */
    fun shownWarnings(d: ConfirmDetails): List<ConfirmWarning> {
        val low = d.warnings.firstOrNull { it.code == ConfirmWarning.W_BG_LOW }
        val falling = d.warnings.firstOrNull { it.code == ConfirmWarning.W_BG_FALLING }
        if (low == null || falling == null) return d.warnings
        val merged = ConfirmWarning(W_LOW_FALLING, maxOf(low.level, falling.level), low.value, falling.value)
        return d.warnings.mapNotNull { if (it === low) merged else if (it === falling) null else it }
    }

    /** The warning's word, e.g. "TIEF" (always a word, never colour alone). */
    fun warningWord(w: ConfirmWarning): String = context.getString(
        when (w.code) {
            ConfirmWarning.W_BG_LOW         -> R.string.confirm_w_low
            W_LOW_FALLING                   -> R.string.confirm_w_low_falling
            ConfirmWarning.W_BG_FALLING     -> if ((w.value ?: 0.0) <= -FALLING_FAST_MGDL) R.string.confirm_w_falling_fast else R.string.confirm_w_falling
            ConfirmWarning.W_BG_STALE       -> R.string.confirm_w_stale
            ConfirmWarning.W_NO_BG          -> R.string.confirm_w_no_bg
            ConfirmWarning.W_RECENT_BOLUS   -> R.string.confirm_w_recent_bolus
            ConfirmWarning.W_NEAR_MAX_BOLUS -> R.string.confirm_w_near_max
            ConfirmWarning.W_CONSTRAINT     -> R.string.confirm_w_constraint
            else                            -> R.string.confirm_w_other
        }
    )

    /**
     * The warning's detail, starting with a capital: "BZ 3,6 mmol/L", "−0,7 mmol/L in 5 min", or null. Numbers stay
     * with their unit (non-breaking spaces); where a detail may wrap it has a normal space ("−0,7 mmol/L" / "in 5 min").
     */
    fun warningDetail(d: ConfirmDetails, w: ConfirmWarning): String? = when (w.code) {
        ConfirmWarning.W_BG_LOW         -> w.value?.let { lowDetail(d, it) }
        W_LOW_FALLING                   -> listOfNotNull(w.value?.let { lowDetail(d, it) }, w.value2?.let { fallingDetail(d, it) }).joinToString("\n").ifEmpty { null }
        ConfirmWarning.W_BG_FALLING     -> w.value?.let { fallingDetail(d, it) }
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

    private fun lowDetail(d: ConfirmDetails, mgdl: Double) = context.getString(R.string.confirm_row_bg) + " " + glucose(mgdl, d.unitsMgdl) + unitSuffix(d.unitsMgdl)
    private fun fallingDetail(d: ConfirmDetails, mgdl: Double) = context.getString(R.string.confirm_per_5min, glucoseDelta(mgdl, d.unitsMgdl))

    /** Page 2's one-line chip: the word and its key number ("TIEF 3,6 ↘", "BOLUS VOR 6 min", "DATEN ALT 18 min"). */
    private fun confirmChipText(d: ConfirmDetails, w: ConfirmWarning): CharSequence {
        val c = color(levelColor(w.level))
        val (word, value) = when (w.code) {
            ConfirmWarning.W_BG_LOW, W_LOW_FALLING -> context.getString(R.string.confirm_w_low) to w.value?.let { glucose(it, d.unitsMgdl) }
            ConfirmWarning.W_BG_FALLING            -> warningWord(w) to w.value?.let { glucoseDelta(it, d.unitsMgdl, withUnit = false) }
            ConfirmWarning.W_BG_STALE              -> warningWord(w) to w.value?.let { context.getString(R.string.confirm_last_bolus_min, it.roundToInt()) }
            ConfirmWarning.W_RECENT_BOLUS          ->
                context.getString(R.string.confirm_w_recent_bolus_short) to w.value?.let { context.getString(R.string.confirm_w_recent_bolus_short_value, it.roundToInt()) }

            else                                   -> warningWord(w) to null
        }
        return SpannableStringBuilder().apply {
            append(word)
            setSpan(ForegroundColorSpan(c), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            value?.let { append(" "); append(it) }
            if ((w.code == ConfirmWarning.W_BG_LOW || w.code == W_LOW_FALLING) && value != null) appendArrow(this, d, c)
        }
    }

    /** The trend arrow, bold at cap height in [c] (TrendArrowSpan), after a non-breaking space; nothing when unknown. */
    private fun appendArrow(sb: SpannableStringBuilder, d: ConfirmDetails, c: Int) {
        if (d.arrow.isBlank() || d.arrow == "??") return
        sb.append(" ")
        val a = sb.length
        sb.append(d.arrow)
        sb.setSpan(TrendArrowSpan(d.arrow, c), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ------------------------------------------------------------------ views

    /** "4 WARNUNGEN" above the chips, in the colour of the most severe one. */
    private fun warningsHeader(shown: List<ConfirmWarning>): View = TextView(context).apply {
        text = context.getString(R.string.confirm_warnings_count, shown.size).uppercase(locale)
        setTextColor(color(levelColor(shown.maxOf { it.level })))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    }

    /**
     * Every chip has the same form: the word (bold, coloured, 18sp) on line 1, its detail (17sp) below. The detail
     * keeps numbers with their units and wraps only where it has a normal space.
     */
    private fun warningChip(d: ConfirmDetails, w: ConfirmWarning, first: Boolean): View {
        val c = color(levelColor(w.level))
        val word = warningWord(w)
        val text = SpannableStringBuilder().apply {
            append(word)
            setSpan(ForegroundColorSpan(c), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(CHIP_WORD_SP / CHIP_DETAIL_SP), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            warningDetail(d, w)?.let { detail ->
                append("\n")
                if (w.code == ConfirmWarning.W_BG_LOW || w.code == W_LOW_FALLING) {
                    // the trend arrow goes after the BG value, the detail's first line
                    val firstLine = detail.substringBefore('\n')
                    append(firstLine)
                    appendArrow(this, d, c)
                    if (detail.contains('\n')) append("\n" + detail.substringAfter('\n'))
                } else append(detail)
            }
        }
        return TextView(context).apply {
            this.text = text
            tag = TAG_CHIP
            setTextColor(color(R.color.confirm_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_DETAIL_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(dp(CHIP_PADDING_DP), dp(7), dp(CHIP_PADDING_DP), dp(8))
            background = chipBackground(w.level, radiusDp = 16f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (first) 0 else dp(6)
            }
        }
    }

    private fun chipBackground(level: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color(if (level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger_tint else R.color.confirm_caution_tint))
        setStroke(dp(1.5f), color(levelColor(level)))
    }

    private fun ring(@ColorRes stroke: Int, @ColorRes fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(fill))
        setStroke(dp(4), color(stroke))
    }

    private fun cardLayout(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.confirm_card)
        setPadding(dp(CARD_PADDING_DP), dp(8), dp(CARD_PADDING_DP), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun card(header: String, rows: List<Row>, footer: String?): View = cardLayout().apply {
        addView(sectionHeader(header))
        rows.forEachIndexed { i, r -> addView(row(r, first = i == 0)) }
        footer?.let {
            addView(TextView(context).apply {
                text = it
                setTextColor(color(R.color.confirm_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }
    }

    /**
     * The wizard's calculation as a two-column grid (label right, value left, one gutter in the middle) where a line is
     * "label: value"; other lines centred. Numbers get typographic minus signs and a space before their unit; the
     * dose the % leads to gets "→", not "≈" (the dose is exact).
     */
    private fun noteCard(note: String): View = cardLayout().apply {
        addView(sectionHeader(context.getString(R.string.confirm_section_calc)))
        note.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.all { c -> c == '_' } }.forEach { line ->
            val split = noteSplit(line)
            fun centred(s: String, @ColorRes c: Int, top: Int) = noteText(s, Gravity.CENTER_HORIZONTAL, c).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
            }
            // half the card for the value, as measured at the size it is drawn
            val column = (cardTextWidthPx() - dp(10)) / 2f * 0.97f
            val wide = split != null && noteText(noteValue(split.second), Gravity.START, R.color.confirm_text_dim).let { Layout.getDesiredWidth(it.text, it.paint) > column }
            if (split == null) addView(centred(noteValue(line), R.color.confirm_text_dim, 3))
            else if (wide || split.second.length > NOTE_COLUMN_CHARS) {
                // a long value ("4,28 U × 90 % → 3,85 U") on its own line below its label, as the card rows
                addView(centred(noteLabel(split.first), R.color.confirm_muted, 5))
                addView(centred(noteValue(split.second), R.color.confirm_text_dim, 0).also { fitText(it, cardTextWidthPx() - dp(4), minSp = 13f, bold = false) })
            } else addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
                    addView(noteText(noteLabel(split.first), Gravity.END, R.color.confirm_muted).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
                    })
                    addView(noteText(noteValue(split.second), Gravity.START, R.color.confirm_text_dim).apply {
                        tag = TAG_VALUE
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
                    })
                }
            )
        }
    }

    private fun noteText(s: String, g: Int, @ColorRes c: Int) = TextView(context).apply {
        text = s
        gravity = g
        setTextColor(color(c))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(locale)
        setTextColor(color(R.color.confirm_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
    }

    /**
     * One row: the muted label centred above its value. Label and value side by side do not fit the round screen
     * at a readable size (values like "1,20 → 1,70 U" alone take most of a card's width); stacked, every row shares
     * the one centre axis of the dial.
     */
    private fun row(r: Row, first: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (first) 2 else 8) }
        addView(TextView(context).apply {
            text = r.label
            setTextColor(color(R.color.confirm_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            fitText(this, cardTextWidthPx(), minSp = 14f, bold = false)
        })
        addView(TextView(context).apply {
            text = r.value
            tag = TAG_VALUE
            val (size, @ColorRes c) = when (r.emphasis) {
                Emphasis.PRIMARY   -> 24f to R.color.confirm_text
                Emphasis.NORMAL    -> 19f to R.color.confirm_text
                Emphasis.SECONDARY -> 17f to R.color.confirm_text_dim
                Emphasis.QUIET     -> 16f to R.color.confirm_text_dim
            }
            setTextColor(color(c))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (r.emphasis == Emphasis.PRIMARY) 3 else 2) }
            fitText(this, cardTextWidthPx(), minSp = 13f, bold = false)
        })
    }

    /** Width (px) of page 1's content column: the screen minus the page's 32dp sides. */
    private fun contentWidthPx(): Int = context.resources.displayMetrics.widthPixels - dp(2 * 32)

    /** Width (px) for text inside a card: the content column minus the card's padding. */
    private fun cardTextWidthPx(): Int = contentWidthPx() - dp(2 * CARD_PADDING_DP)

    /**
     * Shrinks [tv]'s text size (down to [minSp]) until each of its lines fits [widthPx]. Measures every line at the
     * full size (and bold when [bold]), which over-estimates smaller or regular spans: it errs on the side of fitting.
     */
    private fun fitText(tv: TextView, widthPx: Int, minSp: Float, bold: Boolean) {
        // the text's own spans (bold word, smaller detail) are measured as drawn; [bold] adds a margin for plain text
        val paint = TextPaint(tv.paint).apply { if (bold) typeface = Typeface.create(tv.typeface, Typeface.BOLD) }
        val text = tv.text
        val lines = ArrayList<CharSequence>().apply {
            var start = 0
            for (i in text.indices) if (text[i] == '\n') {
                add(text.subSequence(start, i)); start = i + 1
            }
            add(text.subSequence(start, text.length))
        }
        // in px throughout: sizes in sp follow the user's font scale non-linearly (Android 14), so px -> sp is not exact
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, tv.resources.displayMetrics)
        var px = tv.textSize
        while (px > minPx) {
            paint.textSize = px
            // 3 % to spare: a laid out line can come out a little wider than measured alone (spans of other lines)
            if (lines.all { Layout.getDesiredWidth(it, paint) <= widthPx * 0.97f }) break
            px = maxOf(minPx, px - 1f)
        }
        if (px != tv.textSize) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }

    // ------------------------------------------------------------------ formatting

    private fun fmt(pattern: String, v: Double): String = DecimalFormat(pattern, symbols).format(v)

    /** Insulin exactly as offered: at least one, at most three decimals ("0,5", "1,25", "0,025"). */
    fun insulin(v: Double): String = fmt("0.0##", v)

    private fun iob(v: Double): String = fmt("0.00", v)
    private fun grams(v: Double): String = fmt("0.#", v)
    private fun signed(v: Int): String = if (v < 0) "−" + abs(v) else v.toString()
    private fun approx(s: String): String = context.getString(R.string.confirm_approx, s)

    fun glucose(mgdl: Double, unitsMgdl: Boolean): String = if (unitsMgdl) fmt("0", mgdl) else fmt("0.0", mgdl * MGDL_TO_MMOL)

    /** A signed glucose change with its unit: "−1,2 mmol/L", "+32 mg/dL". */
    fun glucoseDelta(mgdl: Double, unitsMgdl: Boolean, withUnit: Boolean = true): String {
        val magnitude = glucose(abs(mgdl), unitsMgdl)
        val zero = magnitude.all { it == '0' || it == symbols.decimalSeparator }
        val sign = if (zero) "" else if (mgdl < 0) "−" else "+"
        return sign + magnitude + if (withUnit) unitSuffix(unitsMgdl) else ""
    }

    private fun unitSuffix(unitsMgdl: Boolean) = if (unitsMgdl) " mg/dL" else " mmol/L"

    /** An estimated duration without false precision: below a minute to 5 s, above to half minutes ("≈ 2½ min"). */
    fun duration(seconds: Int): String =
        if (seconds < 58) context.getString(R.string.confirm_seconds, ((seconds / 5.0).roundToInt() * 5).coerceAtLeast(5))
        else {
            val halves = (seconds / 30.0).roundToInt().coerceAtLeast(2)
            if (halves % 2 == 0) context.getString(R.string.confirm_minutes, halves / 2) else context.getString(R.string.confirm_minutes_half, halves / 2)
        }

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

        /** View tags, for the render harness: a warning chip, a card row's value, the amount. */
        const val TAG_CHIP = "confirm_chip"
        const val TAG_VALUE = "confirm_value"
        const val TAG_AMOUNT = "confirm_amount"

        /** Watch-only code of the chip that shows BG low and falling together (value = BG, value2 = delta). */
        const val W_LOW_FALLING = "bg_low_falling"

        private const val CARD_PADDING_DP = 10
        private const val CHIP_PADDING_DP = 7
        private const val CHIP_WORD_SP = 18f
        private const val CHIP_DETAIL_SP = 17f

        /** Up to this share of the carbs, a covered amount is shown as "≈ x von y g". */
        private const val COVERS_OF_MAX = 1.05

        private const val EPS = 1e-6

        /** A calculation label or value longer than this does not fit its half of the two-column grid. */
        private const val NOTE_COLUMN_CHARS = 14

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(details: ConfirmDetails?): String? = details?.let { json.encodeToString(ConfirmDetails.serializer(), it) }

        /** The details from the intent extra, or null (absent or unreadable: the plain message is shown). */
        fun decode(s: String?): ConfirmDetails? = if (s.isNullOrBlank()) null else try {
            json.decodeFromString(ConfirmDetails.serializer(), s)
        } catch (_: Exception) {
            null
        }

        /**
         * [details] only when they describe the same treatment as [returnCommand], the command the confirm button sends:
         * the same kind and, where the command carries it, exactly the same insulin and carbs. Otherwise null, and the
         * phone's own message (which names the amount) is shown instead. The large amount on the watch can then never
         * differ from what the confirm delivers.
         */
        fun matchingDetails(details: ConfirmDetails?, returnCommand: EventData?): ConfirmDetails? {
            details ?: return null
            if (details.kind == ConfirmDetails.KIND_OTHER) return details
            val matches = when (returnCommand) {
                is EventData.ActionBolusConfirmed  ->
                    (details.kind == ConfirmDetails.KIND_BOLUS || details.kind == ConfirmDetails.KIND_CARBS) &&
                        abs(details.insulin - returnCommand.insulin) <= EPS && details.carbs == returnCommand.carbs

                is EventData.ActionFillConfirmed   -> details.kind == ConfirmDetails.KIND_FILL && abs(details.insulin - returnCommand.insulin) <= EPS && details.carbs == 0
                is EventData.ActionECarbsConfirmed -> details.kind == ConfirmDetails.KIND_ECARBS && details.carbs == returnCommand.carbs && details.insulin == 0.0
                // the wizard's command names its calculation, not an amount: the phone checks that amount itself
                is EventData.ActionWizardConfirmed -> details.kind == ConfirmDetails.KIND_WIZARD
                else                               -> false
            }
            return if (matches) details else null
        }

        /**
         * The watch wizard's confirm page (WizardConfirmFragment): [details] only when they are the wizard's and name
         * exactly the insulin and carbs of the result it confirms; otherwise just those numbers, without warnings.
         */
        fun wizardDetails(details: ConfirmDetails?, totalInsulin: Double, carbs: Int): ConfirmDetails =
            details?.takeIf { it.kind == ConfirmDetails.KIND_WIZARD && abs(it.insulin - totalInsulin) <= EPS && it.carbs == carbs }
                ?: ConfirmDetails(kind = ConfirmDetails.KIND_WIZARD, insulin = totalInsulin, carbs = carbs)

        /** "label: value" of a calculation line, or null for a line to show whole ("Calc (IC: 13, ISF: 2,5)"). */
        fun noteSplit(line: String): Pair<String, String>? {
            val i = line.indexOf(':')
            if (i <= 0 || i >= line.length - 1) return null
            val label = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            if (label.isEmpty() || value.isEmpty() || label.contains('(') || label.length > NOTE_COLUMN_CHARS) return null
            return label to value
        }

        /** A calculation label short enough for its half of the grid ("Kohlenhydrate" -> "KH"). */
        fun noteLabel(label: String): String = when (label) {
            "Kohlenhydrate" -> "KH"
            else            -> label
        }


        /** Typographic numbers in the phone's calculation text: "-0,35U" -> "−0,35 U", "20g" -> "20 g", " x " -> " × ". */
        fun prettyNumbers(s: String): String =
            s.replace(Regex("(?<![\\w])-(?=\\d)"), "−").replace(Regex("(\\d)(U|g|h|m)(?![\\w])"), "$1 $2").replace(" x ", " × ")

        /**
         * A calculation value as shown: typographic numbers, two values apart ("0 g 0,00 U" -> "0 g · 0,00 U") and "→"
         * for the dose the percentage leads to (it is exact, not an estimate).
         */
        fun noteValue(s: String): String =
            prettyNumbers(s).replace(Regex("(\\d g) (?=[−\\d])"), "$1 · ").replace(" ≈ ", " → ")
    }
}
