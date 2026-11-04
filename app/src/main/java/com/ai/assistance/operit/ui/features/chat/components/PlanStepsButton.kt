package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus
import com.ai.assistance.operit.data.stats.SubagentProgress

/**
 * Plan-steps affordance for the chat header, placed immediately left of the token gauge.
 *
 * Collapsed it shows a checklist icon and a completed/total counter, mirroring the collapsed row of
 * ZCode's todo block. Expanded it lists every step. The in-progress step uses a static arrow rather
 * than an animated indicator so "currently working on" never reads as a loading spinner.
 */
@Composable
fun PlanStepsButton(
    steps: List<PlanStep>,
    sessionId: String?,
    modifier: Modifier = Modifier,
    subagents: List<SubagentProgress> = emptyList(),
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val visibleSteps = visibleHeaderPlanSteps(steps)
    val completedCount = visibleSteps.count { it.status == PlanStepStatus.COMPLETED }
    val visibleAgents = visibleHeaderSubagents(subagents)
    // Completed plans remain persisted, but neither they nor finished agent rounds keep the entry alive.
    if (visibleSteps.isEmpty() && visibleAgents.isEmpty()) return
    val runningAgents = visibleAgents.count { it.status == "running" || it.status == "retrying" }
    val activeStep =
        visibleSteps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }
            ?: visibleSteps.firstOrNull { it.status != PlanStepStatus.COMPLETED }

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Checklist,
                contentDescription = stringResource(R.string.plan_steps_title),
                tint =
                    when {
                        runningAgents > 0 -> MaterialTheme.colorScheme.primary
                        visibleSteps.isEmpty() ->
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        activeStep != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                modifier = Modifier.size(18.dp)
            )
            if (visibleSteps.isNotEmpty()) {
                Text(
                    text = "$completedCount/${visibleSteps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (runningAgents > 0) {
                Text(
                    text = "+$runningAgents",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
                .heightIn(max = 360.dp).background(MaterialTheme.colorScheme.surface)
        ) {
            if (visibleSteps.isEmpty()) {
                Text(
                    text = stringResource(R.string.plan_steps_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            } else {
                val firstActive =
                    visibleSteps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }
                        ?: visibleSteps.firstOrNull { it.status != PlanStepStatus.COMPLETED }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text =
                            stringResource(
                                R.string.plan_steps_progress,
                                completedCount,
                                visibleSteps.size
                            ),
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (firstActive != null) {
                        Text(
                            text =
                                stringResource(
                                    R.string.plan_steps_current,
                                    firstActive.content
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                HorizontalDivider()
                Column {
                    visibleSteps.forEach { PlanStepRow(it) }
                }
            }
            if (visibleAgents.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.plan_subagents_title),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
                visibleAgents.asReversed().forEach { SubagentProgressRow(it) }
            }
        }
    }
}

@Composable
private fun SubagentProgressRow(agent: SubagentProgress) {
    val statusText = stringResource(
        when (agent.status) {
            "running" -> R.string.plan_agent_running
            "retrying" -> R.string.plan_agent_retrying
            "completed" -> R.string.plan_agent_completed
            "failed" -> R.string.plan_agent_failed
            "timed_out" -> R.string.plan_agent_timed_out
            "cancelled" -> R.string.plan_agent_cancelled
            else -> error("Unknown subagent status: ${agent.status}")
        }
    )
    val statusColor = when (agent.status) {
        "running", "retrying" -> MaterialTheme.colorScheme.primary
        "completed" -> MaterialTheme.colorScheme.tertiary
        "failed", "timed_out" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = agent.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${agent.agentType} · $statusText",
            style = MaterialTheme.typography.labelSmall,
            color = statusColor
        )
        Text(
            text = stringResource(R.string.plan_agent_tool_progress,
                agent.tools.count { it.isTerminal },
                agent.tools.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (agent.status == "retrying") agent.lastError?.let { reason ->
            Text(text = reason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
        agent.tools.takeLast(3).forEach { tool ->
            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (tool.status == "error") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanStepRow(step: PlanStep) {
    val icon =
        when (step.status) {
            PlanStepStatus.COMPLETED -> Icons.Default.CheckCircle
            PlanStepStatus.IN_PROGRESS -> Icons.AutoMirrored.Filled.ArrowRight
            PlanStepStatus.PENDING -> Icons.Default.RadioButtonUnchecked
        }
    val tint =
        when (step.status) {
            PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
            PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
            PlanStepStatus.PENDING ->
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
    val textColor =
        when (step.status) {
            PlanStepStatus.COMPLETED ->
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
            PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = step.content,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    textDecoration =
                        if (step.status == PlanStepStatus.COMPLETED) TextDecoration.LineThrough
                        else null
                ),
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}