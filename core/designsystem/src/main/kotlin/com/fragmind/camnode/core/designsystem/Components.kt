package com.fragmind.camnode.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class StatusTone { Live, Online, Warning, Offline, Neutral }

@Composable
private fun StatusTone.color(): Color = with(CamNodeTheme.status) {
    when (this@color) {
        StatusTone.Live -> live
        StatusTone.Online -> online
        StatusTone.Warning -> warning
        StatusTone.Offline -> offline
        StatusTone.Neutral -> neutral
    }
}

/** Dot plus optional label. The dot pulses while [tone] is [StatusTone.Live]. */
@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier, onVideo: Boolean = false, compact: Boolean = false) {
    val color = tone.color()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (tone == StatusTone.Live) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val background = if (onVideo) Color.Black.copy(alpha = 0.55f) else color.copy(alpha = 0.14f)
    Row(
        modifier = modifier
            .then(if (compact) Modifier.semantics { contentDescription = text } else Modifier)
            .background(background, CircleShape)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).alpha(pulse).background(color, CircleShape))
        if (!compact) Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (onVideo) Color.White else color)
    }
}

/** Small icon + value, used for battery, viewers, network and quality. */
@Composable
fun MetricChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, onVideo: Boolean = false) {
    val contentColor = when {
        tint != Color.Unspecified -> tint
        onVideo -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = if (onVideo) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier = modifier
            .background(background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

@Composable
fun CamNodeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Rounded tile holding an icon, the visual anchor of rows and headers. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 44) {
    Box(
        modifier = modifier.size(size.dp).background(tint.copy(alpha = 0.14f), MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** One line of a settings or info list: icon, title, optional subtitle, trailing slot. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, tint = iconTint, size = 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, size = 72)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

enum class ButtonEmphasis { Primary, Secondary, Danger }

@Composable
fun CamNodeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasis: ButtonEmphasis = ButtonEmphasis.Primary,
    enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
    val shape = MaterialTheme.shapes.medium
    val sized = modifier.fillMaxWidth().height(56.dp)
    when (emphasis) {
        ButtonEmphasis.Primary -> Button(onClick, sized, enabled = enabled, shape = shape) { content() }
        ButtonEmphasis.Danger -> Button(
            onClick, sized, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { content() }
        ButtonEmphasis.Secondary -> OutlinedButton(onClick, sized, enabled = enabled, shape = shape) { content() }
    }
}

/** Inline notice for warnings and errors that need the user's attention. */
@Composable
fun NoticeCard(icon: ImageVector, title: String, body: String, tone: StatusTone, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    val color = tone.color()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color.copy(alpha = 0.10f)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action?.invoke()
            }
        }
    }
}
