package com.fragmind.camnode.feature.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.AppLockRepository
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GateState {
    /** Nothing is shown until the lock setting is read, so real content never flashes. */
    data object Loading : GateState
    data object Open : GateState
    data class Locked(val disguise: AppDisguise) : GateState
}

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val repository: AppLockRepository,
    private val session: AppLockSession,
    disguiser: LauncherDisguiser,
) : ViewModel() {
    init {
        session.observeProcess()
        // Heals a launcher entry left behind if the process died before a switch was applied.
        viewModelScope.launch {
            repository.config.first().let { if (it.enabled) disguiser.apply(it.disguise, it.iconStyle) else disguiser.apply(AppDisguise.NONE) }
        }
    }

    val state: StateFlow<GateState> = combine(repository.config, session.unlocked) { config, unlocked ->
        if (!config.enabled || unlocked) GateState.Open else GateState.Locked(config.disguise)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GateState.Loading)

    private val _lastCheck = MutableStateFlow<PinCheck?>(null)
    /** Only the plain PIN pad shows this; disguises stay silent on a wrong PIN. */
    val lastCheck: StateFlow<PinCheck?> = _lastCheck.asStateFlow()

    /** Called with a 4-digit candidate from any disguise; unlocks on the right PIN. */
    fun tryPin(pin: String, onDenied: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.verify(pin)
            _lastCheck.value = result
            if (result == PinCheck.Granted) session.unlock() else onDenied()
        }
    }
}
