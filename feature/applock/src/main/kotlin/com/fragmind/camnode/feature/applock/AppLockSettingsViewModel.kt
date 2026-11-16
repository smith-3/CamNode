package com.fragmind.camnode.feature.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.AppLockConfig
import com.fragmind.camnode.core.data.AppLockRepository
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(
    private val repository: AppLockRepository,
    private val session: AppLockSession,
    private val disguiser: LauncherDisguiser,
) : ViewModel() {
    val config: StateFlow<AppLockConfig?> = repository.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The PIN was just typed here, so the app stays open after turning the lock on. */
    fun enable(pin: String, disguise: AppDisguise, iconStyle: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val enabled = repository.enable(pin, disguise, iconStyle)
            if (enabled) {
                session.unlock()
                disguiser.apply(disguise, iconStyle)
            }
            onResult(enabled)
        }
    }

    fun changeDisguise(pin: String, disguise: AppDisguise, iconStyle: Int, onResult: (PinCheck) -> Unit) {
        viewModelScope.launch {
            val result = repository.changeDisguise(pin, disguise, iconStyle)
            if (result == PinCheck.Granted) disguiser.apply(disguise, iconStyle)
            onResult(result)
        }
    }

    fun disable(pin: String, onResult: (PinCheck) -> Unit) {
        viewModelScope.launch {
            val result = repository.disable(pin)
            if (result == PinCheck.Granted) disguiser.apply(AppDisguise.NONE)
            onResult(result)
        }
    }
}
