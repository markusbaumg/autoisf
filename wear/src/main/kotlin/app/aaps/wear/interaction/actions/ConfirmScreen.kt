package app.aaps.wear.interaction.actions

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
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
 * Fills the two pages of the watch confirm screen (AcceptActivity): page 1 tells what is confirmed (the amount large),
 * warnings, the estimated effect and the timing; page 2 holds the confirm button.
 *
 * DISPLAY ONLY. Everything shown comes from the phone's ConfirmAction (title, message and the optional
 * [ConfirmDetails]); nothing here decides what is delivered: the confirm button sends the phone's returnCommand
 * unchanged (AcceptActivity). Without details (an older phone, status and error screens, or details whose amount does
 * not match the returnCommand, see [matchingDetails]) the phone's message text is shown as before, only restyled.
 *
 * The amount is printed exactly (up to 3 decimals, never rounded to fewer than it has); estimates always carry "≈".
 */
class ConfirmScreen(private val context: Context) {

    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val symbols = DecimalFormatSymbols.getInstance(locale)

    // ------------------------------------------------------------------ page 1

    fun bindInfoPage(page: View, title: String, message: String, details: ConfirmDetails?, isError: Boolean) {
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
            messageView.gravity = if (message.length <= 90) Gravity.CENTER_HORIZONTAL else Gravity.START
            hint.visibility = if (isError) View.GONE else View.VISIBLE
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
        val second = secondLine(details)
        amount2.visibility = if (second == null) View.GONE else View.VISIBLE
        amount2.text = second

        warnings.removeAllViews()
        warnings.visibility = if (details.warnings.isEmpty()) View.GONE else View.VISIBLE
        details.warnings.forEachIndexed { index, w -> warnings.addView(warningChip(details, w, first = index == 0)) }

        sections.removeAllViews()
        effectRows(details).takeIf { it.isNotEmpty() }?.let {
            sections.addView(card(context.getString(R.string.confirm_section_effect), it, sourceLine(details)))
        }
        timingRows(details).takeIf { it.isNotEmpty() }?.let { sections.addView(card(context.getString(R.string.confirm_section_timing), it, null)) }
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
        val button = page.findViewById<ImageView>(R.id.confirmbutton)
        val d = details?.takeIf { it.kind != ConfirmDetails.KIND_OTHER }
        if (isError) {
            // an error screen only gets closed: neutral ring and a cross, no green tick
            button.background = ring(R.color.confirm_muted, R.color.confirm_card)
            button.setImageResource(R.drawable.confirm_ic_close)
            button.setColorFilter(color(R.color.confirm_text_dim))
        }
        if (d == null) {
            kind.visibility = View.GONE
            amount.visibility = View.GONE
            warning.visibility = View.GONE
            hint.text = context.getString(if (isError) R.string.confirm_tap_close else R.string.confirm_tap_confirm)
            return
        }
        // page 2 has room for one more line only: the carbs next to insulin, or the top warning, win over the kind
        // (page 1 shows all of them; the hint below the button still says what the tap does)
        val second = secondLine(d)
        val top = d.warnings.firstOrNull()
        kind.visibility = if (second == null && top == null) View.VISIBLE else View.GONE
        kind.text = kindLabel(d).uppercase(locale)
        fitText(kind, dp(110), minSp = 13f, bold = false)
        amount.visibility = View.VISIBLE
        amount.text = second?.let { second ->
            SpannableStringBuilder(headline(d)).apply {
                append("\n")
                val start = length
                append(second)
                setSpan(RelativeSizeSpan(0.6f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color(R.color.confirm_text_dim)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } ?: headline(d)
        hint.text = context.getString(
            when {
                isError         -> R.string.confirm_tap_close
                d.insulin > 0.0 -> R.string.confirm_tap_deliver
                d.carbs != 0    -> R.string.confirm_tap_enter
                else            -> R.string.confirm_tap_confirm
            }
        )
        if (top == null) warning.visibility = View.GONE
        else {
            // the top warning, at the moment of decision, as the same chip as on page 1; display only, never blocks
            warning.visibility = View.VISIBLE
            val c = color(levelColor(top.level))
            warning.text = SpannableStringBuilder().apply {
                val word = warningWord(top)
                append(word)
                setSpan(ForegroundColorSpan(c), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                warningShort(d, top)?.let { short ->
                    append(" ")
                    append(short)
                    // the trend arrow of a low, large and in the warning's colour
                    if (top.code == ConfirmWarning.W_BG_LOW && arrowSuffix(d).isNotEmpty()) {
                        setSpan(ForegroundColorSpan(c), length - d.arrow.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(1.15f), length - d.arrow.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                val more = d.warnings.size - 1
                if (more > 0) append(" · " + context.getString(R.string.confirm_warnings_more, more))
            }
            warning.background = chipBackground(top.level, radiusDp = 13f)
            fitText(warning, dp(170) - warning.paddingLeft - warning.paddingRight - dp(4), minSp = 14f, bold = true)
            // with the chip the button is a little smaller, so that hint and button stay inside the circle
            (button.layoutParams as LinearLayout.LayoutParams).apply { width = dp(58); height = dp(58); topMargin = dp(8); bottomMargin = dp(4) }
            button.setPadding(dp(13), dp(13), dp(13), dp(13))
            amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 31f)
            if (!isError) button.background = ring(levelColor(top.level), if (top.level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger_tint else R.color.confirm_caution_tint)
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

    /** One row of a card: a muted label above its value; [quiet] values are secondary (dimmer). */
    class Row(val label: String, val value: String, val quiet: Boolean = false)

    /**
     * The effect card. With carbs in the same treatment the carbs covered come first and the insulin's drop alone
     * ("Insulin allein") last and quieter: it is not where BG goes. Every estimate carries "≈"; IOB and COB now → after
     * are plain sums and do not.
     */
    fun effectRows(d: ConfirmDetails): List<Row> {
        val rows = ArrayList<Row>()
        val dosing = d.insulin > 0.0 && d.kind != ConfirmDetails.KIND_FILL
        val withCarbs = dosing && d.carbs > 0
        val bg = d.bgEffectMgdl?.let { approx(glucoseDelta(it, d.unitsMgdl)) }
        val covers = d.carbsCovered?.let {
            if (d.carbs > 0) context.getString(R.string.confirm_covers_of, grams(it), d.carbs.toString())
            else approx(context.getString(R.string.confirm_grams, grams(it)))
        }
        val iob = d.iobNow?.let { now -> d.iobAfter?.let { after -> context.getString(R.string.confirm_change, iob(now), iob(after)) + "\u00A0U" } }
        val cob = d.cobNow?.let { now -> d.cobAfter?.let { after -> context.getString(R.string.confirm_change, grams(now), grams(after)) + "\u00A0g" } }
        when {
            withCarbs -> {
                covers?.let { rows += Row(context.getString(R.string.confirm_row_covers), it) }
                iob?.let { rows += Row(context.getString(R.string.confirm_row_iob), it) }
                bg?.let { rows += Row(context.getString(R.string.confirm_row_bg_insulin_only), it, quiet = true) }
            }

            dosing    -> {
                bg?.let { rows += Row(context.getString(R.string.confirm_row_bg_down), it) }
                iob?.let { rows += Row(context.getString(R.string.confirm_row_iob), it) }
                covers?.let { rows += Row(context.getString(R.string.confirm_row_covers), it) }
            }

            else      -> bg?.let { rows += Row(context.getString(if ((d.bgEffectMgdl ?: 0.0) < 0) R.string.confirm_row_bg_down else R.string.confirm_row_bg_up), it) }
        }
        cob?.let { rows += Row(context.getString(R.string.confirm_row_cob), it) }
        return rows
    }

    /** Where the estimate comes from: "Profil: ISF 2,5 · IC 13" (in the user's units), or null. */
    fun sourceLine(d: ConfirmDetails): String? {
        val parts = listOfNotNull(
            d.isfMgdl?.takeIf { it > 0.0 }?.let { "ISF\u00A0" + glucose(it, d.unitsMgdl) },
            d.ic?.takeIf { it > 0.0 }?.let { "IC\u00A0" + grams(it) }
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

    /** The warning's detail line, e.g. "BZ 3,6 mmol/L", or null. Never breaks inside: spaces are non-breaking. */
    fun warningDetail(d: ConfirmDetails, w: ConfirmWarning): String? = when (w.code) {
        ConfirmWarning.W_BG_LOW         -> w.value?.let { context.getString(R.string.confirm_row_bg) + "\u00A0" + glucose(it, d.unitsMgdl) + unitSuffix(d.unitsMgdl) }
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

    /** The key number of a warning for the one-line chip on page 2 ("3,6 ↘", "−0,7", "18 min"), or null. */
    fun warningShort(d: ConfirmDetails, w: ConfirmWarning): String? = when (w.code) {
        ConfirmWarning.W_BG_LOW       -> w.value?.let { glucose(it, d.unitsMgdl) + arrowSuffix(d) }
        ConfirmWarning.W_BG_FALLING   -> w.value?.let { glucoseDelta(it, d.unitsMgdl, withUnit = false) }
        ConfirmWarning.W_BG_STALE     -> w.value?.let { context.getString(R.string.confirm_last_bolus_min, it.roundToInt()) }
        else                          -> null // "KURZ NACH BOLUS" is long enough by itself; page 1 has the detail
    }

    // ------------------------------------------------------------------ views

    /**
     * Every chip has the same form: the word (bold, coloured, 18sp) on line 1, its detail (15sp) on line 2. A long word
     * ("KURZ NACH BOLUS") gets a little smaller rather than wrapping; the detail never wraps (non-breaking spaces).
     */
    private fun warningChip(d: ConfirmDetails, w: ConfirmWarning, first: Boolean): View {
        val c = color(levelColor(w.level))
        val word = warningWord(w)
        val wordPaint = TextPaint().apply {
            typeface = Typeface.create(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD)
            isAntiAlias = true
        }
        val textWidth = cardTextWidthPx() + dp(2 * CARD_PADDING_DP) - dp(2 * CHIP_PADDING_DP) - dp(6)
        var wordSp = 18f
        while (wordSp > 15f) {
            wordPaint.textSize = sp(wordSp)
            if (wordPaint.measureText(word) <= textWidth) break
            wordSp -= 0.5f
        }
        val text = SpannableStringBuilder().apply {
            append(word)
            setSpan(ForegroundColorSpan(c), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(wordSp / 15f), 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            warningDetail(d, w)?.let {
                append("\n")
                append(it)
                // the trend arrow of a low, large and in the warning's colour
                if (w.code == ConfirmWarning.W_BG_LOW && arrowSuffix(d).isNotEmpty()) {
                    append("\u00A0")
                    val a = length
                    append(d.arrow)
                    setSpan(ForegroundColorSpan(c), a, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(1.2f), a, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return TextView(context).apply {
            this.text = text
            tag = TAG_CHIP
            setTextColor(color(R.color.confirm_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            })
        }
    }

    /**
     * The wizard's calculation as a two-column grid (label right, value left, one gutter in the middle) where a line is
     * "label: value"; other lines centred. Numbers get typographic minus signs and a space before their unit.
     */
    private fun noteCard(note: String): View = cardLayout().apply {
        addView(sectionHeader(context.getString(R.string.confirm_section_calc)))
        note.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.all { c -> c == '_' } }.forEach { line ->
            val split = noteSplit(line)
            fun centred(s: String, @ColorRes c: Int, top: Int) = noteText(s, Gravity.CENTER_HORIZONTAL, c).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
            }
            if (split == null) addView(centred(prettyNumbers(line), R.color.confirm_text_dim, 3))
            else if (split.second.length > NOTE_COLUMN_CHARS) {
                // a long value ("4,28 U × 90 % ≈ 3,85 U") on its own line below its label, as the card rows
                addView(centred(split.first, R.color.confirm_muted, 5))
                addView(centred(prettyNumbers(split.second), R.color.confirm_text_dim, 0).also { fitText(it, cardTextWidthPx() - dp(4), minSp = 13f, bold = false) })
            } else addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
                    addView(noteText(split.first, Gravity.END, R.color.confirm_muted).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
                    })
                    addView(noteText(prettyNumbers(split.second), Gravity.START, R.color.confirm_text_dim).apply {
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(TextView(context).apply {
            text = r.value
            tag = TAG_VALUE
            setTextColor(color(if (r.quiet) R.color.confirm_text_dim else R.color.confirm_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (r.quiet) 17f else 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
            fitText(this, cardTextWidthPx(), minSp = 15f, bold = false)
        })
    }

    /** Width (px) for text inside a card: the screen minus the page's 32dp sides and the card's padding. */
    private fun cardTextWidthPx(): Int = context.resources.displayMetrics.widthPixels - dp(2 * 32) - dp(2 * CARD_PADDING_DP)

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
            for (i in text.indices) if (text[i] == '\n') { add(text.subSequence(start, i)); start = i + 1 }
            add(text.subSequence(start, text.length))
        }
        var sp = tv.textSize / context.resources.displayMetrics.scaledDensity
        while (sp > minSp) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
            if (lines.all { Layout.getDesiredWidth(it, paint) <= widthPx }) break
            sp -= 0.5f
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
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

    private fun unitSuffix(unitsMgdl: Boolean) = if (unitsMgdl) "\u00A0mg/dL" else "\u00A0mmol/L"
    private fun arrowSuffix(d: ConfirmDetails) = if (d.arrow.isBlank() || d.arrow == "??") "" else "\u00A0" + d.arrow

    private fun duration(seconds: Int): String =
        if (seconds < 60) context.getString(R.string.confirm_seconds, seconds)
        else if (seconds % 60 == 0) context.getString(R.string.confirm_minutes, seconds / 60)
        else context.getString(R.string.confirm_min_seconds, seconds / 60, seconds % 60)

    private fun minutesAgo(min: Int): String = context.getString(R.string.confirm_minutes_ago, min)

    private fun time(ts: Long): String =
        SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale).format(Date(ts))

    @ColorRes private fun levelColor(level: Int) = if (level >= ConfirmWarning.LEVEL_DANGER) R.color.confirm_danger else R.color.confirm_caution
    private fun color(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).roundToInt()
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    companion object {

        /** Same factor as the phone (Constants.MGDL_TO_MMOLL). */
        const val MGDL_TO_MMOL = 0.0555
        /** Same as FaceState.FAST_DELTA_MGDL. */
        const val FALLING_FAST_MGDL = 10.0

        /** Intent extra carrying [ConfirmDetails] as JSON. */
        const val KEY_DETAILS = "confirm_details"

        /** View tags, for the render harness: a warning chip, a card row's value. */
        const val TAG_CHIP = "confirm_chip"
        const val TAG_VALUE = "confirm_value"

        private const val CARD_PADDING_DP = 10
        private const val CHIP_PADDING_DP = 8

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

        /** "label: value" of a calculation line, or null for a line to show whole ("Calc (IC: 13, ISF: 2,5)"). */
        fun noteSplit(line: String): Pair<String, String>? {
            val i = line.indexOf(':')
            if (i <= 0 || i >= line.length - 1) return null
            val label = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            if (label.isEmpty() || value.isEmpty() || label.contains('(') || label.length > NOTE_COLUMN_CHARS) return null
            return label to value
        }

        /** Typographic numbers in the phone's calculation text: "-0,35U" -> "−0,35 U", "20g" -> "20 g", " x " -> " × ". */
        fun prettyNumbers(s: String): String =
            s.replace(Regex("(?<![\\w])-(?=\\d)"), "−").replace(Regex("(\\d)(U|g|h|m)(?![\\w])"), "$1\u00A0$2").replace(" x ", " × ")
    }
}
