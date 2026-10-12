package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.stats.SubagentProgress
import com.ai.assistance.operit.ui.features.chat.components.part.CanvasExpandableHeaderRow
import com.ai.assistance.operit.ui.features.chat.components.part.ExpandableHeaderTitleStart
import kotlin.math.PI
import kotlin.math.cos

/** Live-round projection; terminal rounds are released by the store, not pinned to the stream tail. */
@Composable
internal fun SubagentConversationProgress(
    agents: List<SubagentProgress>,
    chatId: String?,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return
    // Tool updates must not reopen a manually collapsed group; chat/round changes start a new group.
    var expanded by remember(chatId, agents.first().agentId) { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(300),
        label = "subagentDisclosureRotation",
    )
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        CanvasExpandableHeaderRow(
            title = "${agents.size} · ${stringResource(R.string.plan_subagents_title)}",
            semanticDescription = stringResource(
                if (expanded) R.string.common_collapse else R.string.common_expand,
            ),
            expanded = expanded,
            titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            rotationDegrees = rotation,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = ExpandableHeaderTitleStart, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                agents.forEach { agent ->
                    key(agent.agentId) { SubagentConversationRow(agent) }
                }
            }
        }
    }
}

@Composable
private fun SubagentConversationRow(agent: SubagentProgress) {
    val status = SubagentConversationStatus.fromStatus(agent.status)
    val statusText = stringResource(when (status) {
        SubagentConversationStatus.RUNNING -> R.string.plan_agent_running
        SubagentConversationStatus.RETRYING -> R.string.plan_agent_retrying
        SubagentConversationStatus.COMPLETED -> R.string.plan_agent_completed
        SubagentConversationStatus.FAILED -> R.string.plan_agent_failed
        SubagentConversationStatus.TIMED_OUT -> R.string.plan_agent_timed_out
        SubagentConversationStatus.CANCELLED -> R.string.plan_agent_cancelled
    })
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubagentStatusIndicator(status, statusText)
            Text(agent.description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.padding(start = 22.dp)) {
            Text(
                text = stringResource(R.string.plan_agent_tool_count, agent.toolCallCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status == SubagentConversationStatus.RETRYING) {
                // A disconnected child remains live and resumable, but must not look like a frozen tool.
                Text(
                    text = "$statusText · ${stringResource(R.string.plan_agent_attempt, agent.attempt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status == SubagentConversationStatus.RUNNING) {
                agent.tools.lastOrNull { !it.isTerminal }?.let { tool ->
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Match the plan row's 14dp slot; retries remain animated and never look successful. */
@Composable
private fun SubagentStatusIndicator(status: SubagentConversationStatus, description: String) {
    when (status) {
        SubagentConversationStatus.RUNNING, SubagentConversationStatus.RETRYING -> {
            SubagentLoadingDots(MaterialTheme.colorScheme.primary, description)
        }
        SubagentConversationStatus.COMPLETED -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        SubagentConversationStatus.FAILED, SubagentConversationStatus.TIMED_OUT -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        SubagentConversationStatus.CANCELLED -> Icon(
            imageVector = Icons.Default.Cancel,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SubagentLoadingDots(color: Color, description: String) {
    val transition = rememberInfiniteTransition(label = "subagentLoadingDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "subagentDotPhase",
    )
    Canvas(Modifier.size(14.dp).semantics { contentDescription = description }) {
        // 2.5dp dots pulse in place instead of bouncing or changing the row's height.
        repeat(3) { index ->
            val wave = (0.5 - 0.5 * cos(2.0 * PI * (phase.toDouble() - index / 3.0))).toFloat()
            drawCircle(
                color = color.copy(alpha = color.alpha * (0.3f + 0.7f * wave)),
                radius = 1.25.dp.toPx(),
                center = Offset((2.25f + index * 4.75f).dp.toPx(), size.height / 2f),
            )
        }
    }
}
