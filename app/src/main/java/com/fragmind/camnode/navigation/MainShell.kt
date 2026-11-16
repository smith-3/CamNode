package com.fragmind.camnode.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.R
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.Permission
import com.fragmind.camnode.core.model.AccessRole
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fragmind.camnode.feature.alerts.AlertsRoute
import com.fragmind.camnode.feature.household.HouseholdRoute
import com.fragmind.camnode.feature.node.NodeRoute
import com.fragmind.camnode.feature.viewer.ViewerRoute
import com.fragmind.camnode.feature.viewer.ViewerViewModel

enum class MainTab {
    Cameras, Alerts, Broadcast, Household;

    companion object {
        /**
         * Watching and alerts need permission to view; everyone can stream and see the household.
         * A camera-only phone opens straight on Broadcast.
         */
        fun forRole(role: AccessRole): List<MainTab> =
            if (role.can(Permission.VIEW_STREAMS)) entries else listOf(Broadcast, Household)
    }
}

/**
 * Top-level destinations of a phone that belongs to a household. A bottom bar in
 * portrait; a side rail when the screen is wide, so landscape keeps its height for video.
 */
@Composable
fun MainShell(
    onOpenCamera: (NodeId) -> Unit,
    onInvite: () -> Unit,
    onAppLock: () -> Unit,
    onLeft: () -> Unit,
    viewerViewModel: ViewerViewModel,
) {
    // Wait for the role so a camera never flashes the viewer tabs.
    val role = viewerViewModel.access.collectAsStateWithLifecycle().value ?: return
    val tabs = MainTab.forRole(role)
    var chosen by rememberSaveable { mutableStateOf(MainTab.Cameras) }
    val tab = chosen.takeIf { it in tabs } ?: tabs.first()
    val content: @Composable () -> Unit = {
        when (tab) {
            MainTab.Cameras -> ViewerRoute(onOpenCamera = onOpenCamera, onInvite = onInvite, viewModel = viewerViewModel)
            MainTab.Alerts -> AlertsRoute(onOpenCamera = onOpenCamera)
            MainTab.Broadcast -> NodeRoute()
            MainTab.Household -> HouseholdRoute(onInvite = onInvite, onAppLock = onAppLock, onLeft = onLeft)
        }
    }
    BoxWithConstraints {
        if (maxWidth > maxHeight && maxWidth >= WIDE_LAYOUT) {
            Row {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Box(Modifier.weight(1f))
                    tabs.forEach { entry ->
                        NavigationRailItem(
                            selected = tab == entry,
                            onClick = { chosen = entry },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    Box(Modifier.weight(1f))
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        tabs.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { chosen = entry },
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(entry.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
            }
        }
    }
}

private val WIDE_LAYOUT = 600.dp

private val MainTab.label: String
    @Composable get() = stringResource(
        when (this) {
            MainTab.Cameras -> R.string.tab_cameras
            MainTab.Alerts -> R.string.tab_alerts
            MainTab.Broadcast -> R.string.tab_broadcast
            MainTab.Household -> R.string.tab_household
        },
    )

private val MainTab.icon: ImageVector
    @Composable get() = when (this) {
        MainTab.Cameras -> CamNodeIcons.Grid
        MainTab.Alerts -> CamNodeIcons.Notifications
        MainTab.Broadcast -> CamNodeIcons.Videocam
        MainTab.Household -> CamNodeIcons.Group
    }
