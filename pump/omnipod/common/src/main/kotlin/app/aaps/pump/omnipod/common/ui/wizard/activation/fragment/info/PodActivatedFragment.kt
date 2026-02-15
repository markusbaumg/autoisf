package app.aaps.pump.omnipod.common.ui.wizard.activation.fragment.info

import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.di.OmnipodPluginQualifier
import app.aaps.pump.omnipod.common.ui.wizard.activation.viewmodel.info.PodActivatedViewModel
import app.aaps.pump.omnipod.common.ui.wizard.common.fragment.InfoFragmentBase
import app.aaps.ui.dialogs.SiteRotationDialog
import javax.inject.Inject

class PodActivatedFragment : InfoFragmentBase() {

    @Inject
    @OmnipodPluginQualifier
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vm: PodActivatedViewModel by viewModels { viewModelFactory }
        this.viewModel = vm
    }

    @IdRes
    override fun getNextPageActionId(): Int? = null

    override fun getIndex(): Int = 5

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show SiteRotation dialog to record new pod location
        if (preferences.get(BooleanKey.SiteRotationManagePump)) {
            SiteRotationDialog().also { dialog ->
                dialog.arguments = Bundle().apply {
                    putInt("siteMode", UiInteraction.SiteMode.EDIT.ordinal)
                    putInt("siteType", TE.Type.CANNULA_CHANGE.ordinal)
                    putLong("time", System.currentTimeMillis())
                }
            }.show(childFragmentManager, "SiteRotationDialog")
        }
    }
}