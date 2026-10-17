package app.aaps.wear.interaction.actions

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmDetails
import app.aaps.core.interfaces.rx.weardata.EventData.ConfirmWarning
import app.aaps.wear.R
import app.aaps.wear.widgets.SimplePageIndicator
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * OFFLINE RENDER HARNESS for the watch confirm screen (AcceptActivity pages, filled by the real ConfirmScreen and the
 * real layouts), test source set only. Writes `<out>/confirm/<state>.png` (page 1: what, warnings, effect, timing),
 * `<state>_ok.png` (page 2: confirm button) and for long pages `<state>_end.png` (page 1 scrolled to its end).
 * 450x450 round xhdpi (Galaxy Watch5 Pro), German unless the state says otherwise.
 *
 * Runs only on request (excluded otherwise, see wear/build.gradle.kts):
 *   ./gradlew :wear:testFullDebugUnitTest --tests '*WatchfaceRenderHarness*' -PwatchfaceRenderOut=/abs/out/dir
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-rDE-w225dp-h225dp-round-xhdpi")
class ConfirmWatchfaceRenderHarness {

    private val outDir: File? = System.getProperty("watchface.render.out")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private class State(
        val id: String,
        val details: ConfirmDetails?,
        val title: String = "BESTÄTIGEN",
        val message: String = "",
        val error: Boolean = false,
        val lang: String = "de"
    )

    private val now = System.currentTimeMillis()

    /** Calm 0.5 U bolus: 6.4 mmol/L flat, ISF 2.5 mmol/L, IC 13, IOB 1.2, Omnipod (40 s/U), Lyumjev-like peak 55'. */
    private val bolus = ConfirmDetails(
        kind = ConfirmDetails.KIND_BOLUS, insulin = 0.5, carbs = 0, unitsMgdl = false,
        bgMgdl = 115.0, bgAgeMin = 2, deltaMgdl = 0.0, arrow = "→",
        bgEffectMgdl = -22.5, carbsCovered = 6.5, iobNow = 1.2, iobAfter = 1.7,
        deliverySeconds = 20, insulinPeakMin = 55, lastBolusMinAgo = 42, lastBolusAmount = 1.5
    )

    private fun states(): List<State> = listOf(
        State("bolus_0_5", bolus),
        State(
            "bolus_carbs", bolus.copy(
                insulin = 1.5, carbs = 20, bgEffectMgdl = -67.5, carbsCovered = 19.5, iobAfter = 2.7, cobNow = 12.0, cobAfter = 32.0, deliverySeconds = 60
            )
        ),
        State(
            "warn_low_falling", bolus.copy(
                bgMgdl = 65.0, deltaMgdl = -8.0, arrow = "↘",
                warnings = listOf(
                    ConfirmWarning(ConfirmWarning.W_BG_LOW, ConfirmWarning.LEVEL_DANGER, 65.0),
                    ConfirmWarning(ConfirmWarning.W_BG_FALLING, ConfirmWarning.LEVEL_DANGER, -8.0)
                )
            )
        ),
        State(
            "warn_stale", bolus.copy(
                bgAgeMin = 18, warnings = listOf(ConfirmWarning(ConfirmWarning.W_BG_STALE, ConfirmWarning.LEVEL_CAUTION, 18.0))
            )
        ),
        State(
            "warn_recent_bolus", bolus.copy(
                lastBolusMinAgo = 6, lastBolusAmount = 1.0,
                warnings = listOf(ConfirmWarning(ConfirmWarning.W_RECENT_BOLUS, ConfirmWarning.LEVEL_CAUTION, 6.0, 1.0))
            )
        ),
        State(
            "warn_many", bolus.copy(
                insulin = 4.5, bgEffectMgdl = -202.5, carbsCovered = 58.5, iobAfter = 5.7, deliverySeconds = 180, bgMgdl = 65.0, deltaMgdl = -12.0,
                arrow = "↓", lastBolusMinAgo = 9, lastBolusAmount = 1.0,
                warnings = listOf(
                    ConfirmWarning(ConfirmWarning.W_BG_LOW, ConfirmWarning.LEVEL_DANGER, 65.0),
                    ConfirmWarning(ConfirmWarning.W_BG_FALLING, ConfirmWarning.LEVEL_DANGER, -12.0),
                    ConfirmWarning(ConfirmWarning.W_RECENT_BOLUS, ConfirmWarning.LEVEL_CAUTION, 9.0, 1.0),
                    ConfirmWarning(ConfirmWarning.W_NEAR_MAX_BOLUS, ConfirmWarning.LEVEL_CAUTION, 5.0),
                    ConfirmWarning(ConfirmWarning.W_CONSTRAINT, ConfirmWarning.LEVEL_CAUTION, 6.0, null)
                )
            )
        ),
        State("mgdl_bolus", bolus.copy(unitsMgdl = true, insulin = 1.25, bgEffectMgdl = -56.25, carbsCovered = 16.25, iobAfter = 2.45, deliverySeconds = 50)),
        State(
            "carbs", ConfirmDetails(
                kind = ConfirmDetails.KIND_CARBS, carbs = 20, unitsMgdl = false, bgMgdl = 115.0, bgAgeMin = 2,
                bgEffectMgdl = 69.2, insulinForCarbs = 1.54, cobNow = 12.0, cobAfter = 32.0, lastBolusMinAgo = 42, lastBolusAmount = 1.5
            )
        ),
        State(
            "ecarbs", ConfirmDetails(
                kind = ConfirmDetails.KIND_ECARBS, carbs = 30, unitsMgdl = false, bgEffectMgdl = 103.8, insulinForCarbs = 2.31,
                carbsStart = now + 30 * 60_000L, carbsDurationH = 3
            )
        ),
        State(
            "wizard_long", bolus.copy(
                kind = ConfirmDetails.KIND_WIZARD, label = "Frühstück gross", insulin = 3.85, carbs = 45, bgEffectMgdl = -173.25, carbsCovered = 50.05,
                iobAfter = 5.05, cobNow = 0.0, cobAfter = 45.0, deliverySeconds = 154,
                note = "+20g/2h(+30m)\nKH: 3,46U\nBZ: 0,52U\nIOB: -0,35U\nTrend: 0,10U\nZiel: 5,5 mmol/L\nKorrektur 90%"
            )
        ),
        State("fill", ConfirmDetails(kind = ConfirmDetails.KIND_FILL, insulin = 0.7, deliverySeconds = 28)),
        State("legacy", null, message = "Bolus: 0.5U\nKohlenhydrate: 0g"),
        State("error", null, title = "Fehler", message = "Pumpe nicht verfügbar", error = true),
        State("en_bolus_carbs", bolus.copy(carbs = 10, cobNow = 12.0, cobAfter = 22.0), title = "CONFIRM", lang = "en"),
    )

    @Test
    fun renderConfirmScreens() {
        val out = outDir
        assumeTrue("set -PwatchfaceRenderOut=<dir> to render", out != null)
        val dir = File(out!!, "confirm").apply { mkdirs() }
        val clipped = ArrayList<String>()
        for (s in states()) {
            val (page1, scrollRange) = render(s, page = 0, scrollToEnd = false)
            write(page1, File(dir, "${s.id}.png"))
            if (scrollRange > 0) write(render(s, page = 0, scrollToEnd = true).first, File(dir, "${s.id}_end.png"))
            val (page2, overflow) = render(s, page = 1, scrollToEnd = false)
            write(page2, File(dir, "${s.id}_ok.png"))
            if (overflow > 0) clipped += "${s.id}_ok: $overflow px too tall"
            clipped += textProblems.map { "${s.id}: $it" }
        }
        assertTrue("confirm page does not fit:\n" + clipped.joinToString("\n"), clipped.isEmpty())
    }

    /** Returns the bitmap and, for page 1, its scroll range (px); for page 2 how far its content exceeds the screen. */
    private fun render(s: State, page: Int, scrollToEnd: Boolean): Pair<Bitmap, Int> {
        RuntimeEnvironment.setQualifiers("${if (s.lang == "de") "de-rDE" else "en-rUS"}-w225dp-h225dp-round-xhdpi")
        Locale.setDefault(if (s.lang == "de") Locale.GERMANY else Locale.US)
        val context: Context = RuntimeEnvironment.getApplication()
        val px = 450
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val screen = ConfirmScreen(context)
        val view = if (page == 0)
            LayoutInflater.from(context).inflate(R.layout.action_confirm_text, root, false).also { screen.bindInfoPage(it, s.title, s.message, s.details, s.error) }
        else
            LayoutInflater.from(context).inflate(R.layout.action_confirm_ok, root, false).also { screen.bindConfirmPage(it, s.details, s.error) }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // the pager's page dots (activity_pager.xml)
        root.addView(SimplePageIndicator(context).apply {
            setPageCount(2)
            setCurrentPage(page)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = (8 * context.resources.displayMetrics.density).toInt()
        })
        val spec = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, px, px)
        var extra = 0
        if (page == 0) {
            val scroll = view as NestedScrollView
            val child = scroll.getChildAt(0)
            extra = (child.height - scroll.height).coerceAtLeast(0)
            if (scrollToEnd) scroll.scrollTo(0, extra)
        } else {
            val content = (view as android.view.ViewGroup)
            val used = (0 until content.childCount).map { content.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
                .sumOf { it.measuredHeight + (it.layoutParams as android.view.ViewGroup.MarginLayoutParams).let { lp -> lp.topMargin + lp.bottomMargin } }
            extra = (used + content.paddingTop + content.paddingBottom - px).coerceAtLeast(0)
        }
        textProblems.clear()
        checkText(root, root, page, scrollToEnd, px / 2f)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        roundMask(bmp)
        return bmp to extra
    }

    /** Text that is cut: a line wider than its view (both pages) or, on page 2 (no scrolling), a line outside the circle. */
    private val textProblems = ArrayList<String>()

    private fun checkText(v: View, root: View, page: Int, scrolled: Boolean, r: Float) {
        if (v.visibility != View.VISIBLE) return
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) checkText(v.getChildAt(i), root, page, scrolled, r)
        if (v !is android.widget.TextView) return
        // page 2 does not scroll: all of it must sit inside the circle. Page 1 scrolls (lines pass the narrow top and
        // bottom of the circle on their way): its head (what, how much) must sit inside the circle unscrolled, and
        // every line must fit the circle's middle band.
        val head = v.id == R.id.confirm_kind || v.id == R.id.confirm_amount || v.id == R.id.confirm_amount2
        val roundCheck = page == 1 || (!scrolled && head)
        val layout = v.layout ?: return
        val loc = IntArray(2).also { pos -> var x = 0; var y = 0; var c: View? = v; while (c != null && c !== root) { x += c.left - c.scrollX; y += c.top - c.scrollY; c = c.parent as? View }; pos[0] = x; pos[1] = y }
        for (i in 0 until layout.lineCount) {
            val left = loc[0] + v.compoundPaddingLeft + layout.getLineLeft(i)
            val right = loc[0] + v.compoundPaddingLeft + layout.getLineRight(i)
            val avail = v.width - v.compoundPaddingLeft - v.compoundPaddingRight
            val line = v.text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString().trim()
            if (layout.getLineWidth(i) > avail + 1f || layout.getEllipsisCount(i) > 0) textProblems += "page ${page + 1} \"$line\" cut (width ${layout.getLineWidth(i)} > $avail, ellipsis ${layout.getEllipsisCount(i)})"
            if (layout.getLineWidth(i) > MAX_LINE_PX) textProblems += "page ${page + 1} \"$line\" wider than $MAX_LINE_PX px"
            if (!roundCheck) continue
            val top = loc[1] + v.extendedPaddingTop + layout.getLineTop(i) + 4f
            val bottom = loc[1] + v.extendedPaddingTop + layout.getLineBottom(i) - 4f
            val outside = listOf(left to top, right to top, left to bottom, right to bottom).any { (x, y) -> kotlin.math.hypot(x - r, y - r) > r - ROUND_MARGIN_PX }
            if (outside) textProblems += "page ${page + 1} \"$line\" outside the round display"
        }
    }

    private fun write(bmp: Bitmap, file: File) = FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun roundMask(bmp: Bitmap) {
        val c = Canvas(bmp)
        val r = bmp.width / 2f
        val path = Path().apply {
            addCircle(r, r, r, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        // a faint outline of the display, to judge the content against the round edge
        c.drawCircle(r, r, r - 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(40, 40, 48); strokeWidth = 2f })
    }


    companion object {

        /** Page 2 text must keep this distance (px, 450 grid) from the round edge. */
        const val ROUND_MARGIN_PX = 8f

        /** Widest line (px, 450 grid) that still fits the round display's middle band with margin. */
        const val MAX_LINE_PX = 360f
    }
}
