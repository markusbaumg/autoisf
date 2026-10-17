package app.aaps.wear.interaction.actions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.wear.activity.ConfirmationActivity
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import dagger.android.support.AndroidSupportInjection
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

class WizardConfirmFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBus

    private val disposable = CompositeDisposable()
    private var timestamp: Long = 0
    private var confirmationSent = false

    companion object {
        private const val ARG_TIMESTAMP = "timestamp"
        private const val ARG_TOTAL_INSULIN = "total_insulin"
        private const val ARG_CARBS = "carbs"
        private const val ARG_DETAILS = "details"

        /** [details]: the phone's display-only ConfirmDetails as JSON (ConfirmScreen.KEY_DETAILS), or null. */
        fun newInstance(timestamp: Long, totalInsulin: Double, carbs: Int, details: String? = null): WizardConfirmFragment {
            return WizardConfirmFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_TIMESTAMP, timestamp)
                    putDouble(ARG_TOTAL_INSULIN, totalInsulin)
                    putInt(ARG_CARBS, carbs)
                    putString(ARG_DETAILS, details)
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // the same confirm page as the other watch confirms (AcceptActivity): amount, carbs, top warning, button
        return inflater.inflate(R.layout.action_confirm_ok, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments ?: return

        timestamp = args.getLong(ARG_TIMESTAMP)
        val totalInsulin = args.getDouble(ARG_TOTAL_INSULIN)
        val carbs = args.getInt(ARG_CARBS)

        // display only: the amount shown is the result's own (details only when they name exactly it), the tap below
        // sends the same ActionWizardConfirmed(timestamp) as before
        try {
            val details = ConfirmScreen.wizardDetails(ConfirmScreen.decode(args.getString(ARG_DETAILS)), totalInsulin, carbs)
            ConfirmScreen(requireContext()).bindConfirmPage(view, details, isError = false)
        } catch (_: Exception) {
            // never lose the confirm over its looks
        }

        view.findViewById<ImageView>(R.id.confirmbutton).setOnClickListener { button ->
            if (confirmationSent) return@setOnClickListener
            confirmationSent = true
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            button.isClickable = false

            // Send confirmation to phone
            rxBus.send(EventWearToMobile(EventData.ActionWizardConfirmed(timestamp)))

            // Show success confirmation
            showConfirmation()
        }
    }

    private fun showConfirmation() {
        if (!isAdded || isDetached) return  // Safety check

        val intent = Intent(requireContext(), ConfirmationActivity::class.java).apply {
            putExtra(ConfirmationActivity.EXTRA_ANIMATION_TYPE, ConfirmationActivity.SUCCESS_ANIMATION)
            putExtra(ConfirmationActivity.EXTRA_MESSAGE, getString(R.string.wizard_confirmation_sent))
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }
}