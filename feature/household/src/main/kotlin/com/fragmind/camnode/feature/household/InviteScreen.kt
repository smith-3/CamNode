package com.fragmind.camnode.feature.household

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fragmind.camnode.core.data.HouseholdRepository
import com.fragmind.camnode.core.designsystem.ButtonEmphasis
import com.fragmind.camnode.core.designsystem.CamNodeButton
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.designsystem.NoticeCard
import com.fragmind.camnode.core.designsystem.StatusTone
import com.fragmind.camnode.core.model.InviteCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class InviteUiState(val householdName: String, val link: String, val seeds: Int, val qr: ImageBitmap)

@HiltViewModel
class InviteViewModel @Inject constructor(households: HouseholdRepository) : ViewModel() {
    val uiState: StateFlow<InviteUiState?> = households.session
        .filterNotNull()
        .map { it.invite() }
        .distinctUntilChanged()
        .map { invite ->
            val link = InviteCodec.encode(invite)
            InviteUiState(invite.household.name, link, invite.seeds.size, QrCodes.render(link, QR_SIZE_PX).asImageBitmap())
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val QR_SIZE_PX = 720
    }
}

@Composable
fun InviteRoute(onBack: () -> Unit, viewModel: InviteViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FlowScaffold(stringResource(R.string.household_invite), onBack) {
        val invite = state
        if (invite == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            return@FlowScaffold
        }
        Text(
            stringResource(R.string.household_invite_instructions),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Color.White)
                .padding(20.dp),
        ) {
            Image(invite.qr, contentDescription = stringResource(R.string.household_invite_qr, invite.householdName), modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Text(invite.householdName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (invite.seeds == 0) {
            NoticeCard(
                CamNodeIcons.Videocam,
                stringResource(R.string.household_invite_no_cameras_title),
                stringResource(R.string.household_invite_no_cameras_body),
                StatusTone.Neutral,
            )
        }
        NoticeCard(
            CamNodeIcons.Lock,
            stringResource(R.string.household_invite_key_title),
            stringResource(R.string.household_invite_key_body),
            StatusTone.Warning,
        )
        CamNodeButton(
            stringResource(R.string.household_invite_share),
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite.link)
                context.startActivity(Intent.createChooser(send, resources.getString(R.string.household_invite_share_title)))
            },
            icon = CamNodeIcons.Share,
            emphasis = ButtonEmphasis.Secondary,
        )
    }
}
