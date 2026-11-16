package com.fragmind.camnode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.HouseholdRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface LaunchState {
    data object Loading : LaunchState
    data object NeedsHousehold : LaunchState
    data object Ready : LaunchState
}

@HiltViewModel
class AppViewModel @Inject constructor(households: HouseholdRepository) : ViewModel() {
    val launchState: StateFlow<LaunchState> = households.session
        .map { session -> if (session != null) LaunchState.Ready else LaunchState.NeedsHousehold }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LaunchState.Loading)
}
