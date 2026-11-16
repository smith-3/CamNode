package com.fragmind.camnode.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.fragmind.camnode.AppViewModel
import com.fragmind.camnode.LaunchState
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.feature.applock.AppLockSettingsRoute
import com.fragmind.camnode.feature.household.CreateHouseholdRoute
import com.fragmind.camnode.feature.household.InviteRoute
import com.fragmind.camnode.feature.household.JoinHouseholdRoute
import com.fragmind.camnode.feature.household.WelcomeScreen
import com.fragmind.camnode.feature.viewer.CameraDetailRoute
import com.fragmind.camnode.feature.viewer.ViewerViewModel
import kotlinx.serialization.Serializable

@Serializable data object Welcome : NavKey
@Serializable data object CreateHousehold : NavKey
@Serializable data class JoinHousehold(val inviteLink: String? = null) : NavKey
@Serializable data object Main : NavKey
@Serializable data class CameraDetail(val cameraId: String) : NavKey
@Serializable data object Invite : NavKey
@Serializable data object AppLockSettings : NavKey

@Composable
fun CamNodeNavHost(
    incomingInvite: String?,
    onInviteConsumed: () -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val launch by appViewModel.launchState.collectAsStateWithLifecycle()
    when (val state = launch) {
        LaunchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LaunchState.NeedsHousehold, LaunchState.Ready -> CamNodeNavDisplay(
            start = if (state == LaunchState.Ready) Main else Welcome,
            incomingInvite = incomingInvite,
            onInviteConsumed = onInviteConsumed,
        )
    }
}

@Composable
private fun CamNodeNavDisplay(
    start: NavKey,
    incomingInvite: String?,
    onInviteConsumed: () -> Unit,
) {
    val backStack = rememberNavBackStack(start)
    val viewerViewModel: ViewerViewModel = hiltViewModel()
    LifecycleStartEffect(viewerViewModel) {
        viewerViewModel.setForeground(true)
        onStopOrDispose { viewerViewModel.setForeground(false) }
    }
    LaunchedEffect(incomingInvite) {
        if (incomingInvite != null) {
            backStack.add(JoinHousehold(incomingInvite))
            onInviteConsumed()
        }
    }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Welcome> {
                WelcomeScreen(onCreate = { backStack.add(CreateHousehold) }, onJoin = { backStack.add(JoinHousehold()) })
            }
            entry<CreateHousehold> {
                CreateHouseholdRoute(onBack = back, onDone = { backStack.resetTo(Main) })
            }
            entry<JoinHousehold> { key ->
                JoinHouseholdRoute(prefilledLink = key.inviteLink, onBack = back, onDone = { backStack.resetTo(Main) })
            }
            entry<Main> {
                MainShell(
                    viewerViewModel = viewerViewModel,
                    onOpenCamera = { id -> backStack.add(CameraDetail(id.value)) },
                    onInvite = { backStack.add(Invite) },
                    onAppLock = { backStack.add(AppLockSettings) },
                    onLeft = { backStack.resetTo(Welcome) },
                )
            }
            entry<CameraDetail> { key -> CameraDetailRoute(cameraId = NodeId(key.cameraId), onBack = back, viewModel = viewerViewModel) }
            entry<Invite> { InviteRoute(onBack = back) }
            entry<AppLockSettings> { AppLockSettingsRoute(onBack = back) }
        },
    )
}

private fun NavBackStack<NavKey>.resetTo(key: NavKey) {
    clear()
    add(key)
}
