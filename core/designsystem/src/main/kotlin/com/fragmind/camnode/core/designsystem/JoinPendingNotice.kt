package com.fragmind.camnode.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Shown on a phone that joined [householdName] but has not reached any of its phones yet.
 * Without it the join looked like a new, empty household with the same name.
 */
@Composable
fun JoinPendingNotice(householdName: String, modifier: Modifier = Modifier) {
    NoticeCard(
        CamNodeIcons.CloudOff,
        stringResource(R.string.join_pending_title, householdName),
        stringResource(R.string.join_pending_body),
        StatusTone.Warning,
        modifier,
    )
}
