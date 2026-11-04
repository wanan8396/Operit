package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.stats.RequestPerformanceAverage
import com.ai.assistance.operit.data.stats.RequestPerformanceStore
import java.util.Locale
import kotlinx.coroutines.delay

private data class UsageSnapshot(
    val window: Long,
    val input: Long,
    val output: Long,
    val average: RequestPerformanceAverage,
)

/** Anchored to the header ring. Only an open popup samples the latest values, once per second. */
@Composable
fun ContextDiagnosticsDialog(
    chatId: String?,
    currentWindowSize: Long,
    inputTokens: Long,
    outputTokens: Long,
    onDismiss: () -> Unit,
) {
    val latestCounts by rememberUpdatedState(Triple(currentWindowSize, inputTokens, outputTokens))
    fun snapshot(): UsageSnapshot {
        val counts = latestCounts
        return UsageSnapshot(
            counts.first, counts.second, counts.third,
            chatId?.let { RequestPerformanceStore.averageSnapshot(it) } ?: RequestPerformanceAverage(),
        )
    }
    var usage by remember(chatId) { mutableStateOf(snapshot()) }
    LaunchedEffect(chatId) {
        while (true) {
            delay(1_000L)
            usage = snapshot()
        }
    }
    val unrecorded = stringResource(R.string.context_diag_unknown_count)
    val speed = usage.average.tokensPerSecond?.let { String.format(Locale.getDefault(), "%.1f", it) }
    val latency = usage.average.firstTokenLatencyMs?.let { String.format(Locale.getDefault(), "%.2f", it / 1000.0) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(min = 180.dp, max = 260.dp).padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(R.string.context_diag_usage_overview),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
            Text(stringResource(R.string.context_window, usage.window), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.input_tokens, usage.input), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.output_tokens, usage.output), style = MaterialTheme.typography.bodySmall)
            Text(
                text = stringResource(R.string.total_tokens, usage.input + usage.output),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            Text(stringResource(R.string.usage_token_rate, speed ?: unrecorded), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.usage_first_token_latency, latency ?: unrecorded), style = MaterialTheme.typography.bodySmall)
        }
    }
}
