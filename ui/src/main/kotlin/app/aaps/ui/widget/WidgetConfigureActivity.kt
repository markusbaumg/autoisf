package app.aaps.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.R
import app.aaps.ui.databinding.WidgetConfigureBinding
import dagger.android.DaggerActivity
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The configuration screen for the [Widget] AppWidget.
 */
class WidgetConfigureActivity : DaggerActivity() {

    @Inject lateinit var preferences: Preferences

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var binding: WidgetConfigureBinding

    public override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Set the result to CANCELED.  This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        binding = WidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Write the prefix to the SharedPreferences object for this widget
                preferences.put(IntComposedKey.WidgetOpacity, appWidgetId, value = progress)
                Widget.updateWidget(this@WidgetConfigureActivity, "WidgetConfigure")
            }
        })

        binding.closeLayout.close.setOnClickListener {
            // Make sure we pass back the original appWidgetId
            val resultValue = Intent()
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }

        binding.useBlack.setOnCheckedChangeListener { _, value ->
            preferences.put(BooleanComposedKey.WidgetUseBlack, appWidgetId, value = value)
            Widget.updateWidget(this@WidgetConfigureActivity, "WidgetConfigure")
        }

        // Quick-bolus amounts, per widget, in steps of 0.05 U (constraints are applied again at confirmation)
        listOf(
            Triple(binding.bolus1Seek, binding.bolus1Value, IntComposedKey.WidgetBolusAmount1),
            Triple(binding.bolus2Seek, binding.bolus2Value, IntComposedKey.WidgetBolusAmount2)
        ).forEach { (seek, label, key) ->
            seek.max = ((WidgetLogic.MAX_AMOUNT - WidgetLogic.MIN_AMOUNT) / AMOUNT_STEP).roundToInt()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val amount = WidgetLogic.clampAmount(WidgetLogic.MIN_AMOUNT + progress * AMOUNT_STEP)
                    label.text = WidgetLogic.amountText(amount) + " " + getString(R.string.widget_unit_insulin)
                    if (!fromUser || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                    preferences.put(key, appWidgetId, value = WidgetLogic.unitsToCentiUnits(amount))
                    Widget.updateWidget(this@WidgetConfigureActivity, "WidgetConfigure")
                }
            })
        }

        // Find the widget id from the intent.
        appWidgetId = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        binding.seekBar.progress = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        binding.useBlack.isChecked = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)
        binding.bolus1Seek.progress = amountToProgress(preferences.get(IntComposedKey.WidgetBolusAmount1, appWidgetId))
        binding.bolus2Seek.progress = amountToProgress(preferences.get(IntComposedKey.WidgetBolusAmount2, appWidgetId))
        // onProgressChanged is not called for progress 0
        binding.bolus1Value.text = WidgetLogic.amountText(WidgetLogic.centiUnitsToUnits(preferences.get(IntComposedKey.WidgetBolusAmount1, appWidgetId))) + " " + getString(R.string.widget_unit_insulin)
        binding.bolus2Value.text = WidgetLogic.amountText(WidgetLogic.centiUnitsToUnits(preferences.get(IntComposedKey.WidgetBolusAmount2, appWidgetId))) + " " + getString(R.string.widget_unit_insulin)
    }

    private fun amountToProgress(centiUnits: Int): Int =
        ((WidgetLogic.clampAmount(WidgetLogic.centiUnitsToUnits(centiUnits)) - WidgetLogic.MIN_AMOUNT) / AMOUNT_STEP).roundToInt()

    companion object {

        private const val AMOUNT_STEP = 0.05
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.seekBar.setOnSeekBarChangeListener(null)
        binding.closeLayout.close.setOnClickListener(null)
        binding.useBlack.setOnCheckedChangeListener(null)
        binding.bolus1Seek.setOnSeekBarChangeListener(null)
        binding.bolus2Seek.setOnSeekBarChangeListener(null)
    }
}