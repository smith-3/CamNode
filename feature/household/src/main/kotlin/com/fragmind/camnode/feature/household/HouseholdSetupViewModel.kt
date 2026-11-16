package com.fragmind.camnode.feature.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.DirectorySync
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.model.InviteCodec
import com.fragmind.camnode.core.model.InviteParseResult
import com.fragmind.camnode.core.model.InviteProblem
import androidx.annotation.StringRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SetupState {
    data object Editing : SetupState
    data object Working : SetupState
    data class Failed(@StringRes val message: Int) : SetupState
    data object Done : SetupState
}

@HiltViewModel
class HouseholdSetupViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val directorySync: DirectorySync,
) : ViewModel() {
    private val _state = MutableStateFlow<SetupState>(SetupState.Editing)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun create(householdName: String, deviceName: String) {
        if (householdName.isBlank() || deviceName.isBlank()) {
            _state.value = SetupState.Failed(R.string.household_error_names)
            return
        }
        launchWork {
            households.create(householdName, deviceName)
            SetupState.Done
        }
    }

    /** Joins, then tries the invite's seeds once; later syncs keep retrying them. */
    fun join(link: String, deviceName: String) {
        if (deviceName.isBlank()) {
            _state.value = SetupState.Failed(R.string.household_error_device_name)
            return
        }
        when (val parsed = InviteCodec.decode(link)) {
            is InviteParseResult.Invalid -> _state.value = SetupState.Failed(parsed.problem.messageRes())
            is InviteParseResult.Valid -> launchWork {
                households.join(parsed.invite, deviceName)
                directorySync.sync()
                SetupState.Done
            }
        }
    }

    private fun InviteProblem.messageRes(): Int = when (this) {
        InviteProblem.NOT_A_LINK -> R.string.household_error_not_a_link
        InviteProblem.NOT_CAMNODE -> R.string.household_error_not_camnode
        InviteProblem.UNSUPPORTED_VERSION -> R.string.household_error_version
        InviteProblem.INCOMPLETE -> R.string.household_error_incomplete
    }

    fun dismissError() {
        if (_state.value is SetupState.Failed) _state.value = SetupState.Editing
    }

    private fun launchWork(block: suspend () -> SetupState) {
        if (_state.value == SetupState.Working) return
        _state.value = SetupState.Working
        viewModelScope.launch { _state.value = block() }
    }
}
