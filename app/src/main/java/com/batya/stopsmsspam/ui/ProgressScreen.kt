package com.batya.stopsmsspam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.batya.stopsmsspam.data.model.BatchProgress
import com.batya.stopsmsspam.data.model.SendStatus
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun ProgressScreen(progress: BatchProgress, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            progress.running && progress.dryRun -> "Dry run in progress"
                            progress.running -> "Sending opt-out replies"
                            else -> "Batch finished"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )

                    LinearProgressIndicator(
                        progress = {
                            if (progress.total == 0) 0f
                            else progress.completed.toFloat() / progress.total
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        buildString {
                            append("${progress.completed} of ${progress.total} - ")
                            append("${progress.sentCount} sent, ${progress.failedCount} failed")
                            if (progress.clearedCount > 0) {
                                append(", ${progress.clearedCount} cleared")
                            }
                            if (progress.unconfirmedCount > 0) {
                                append(", ${progress.unconfirmedCount} unconfirmed")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (progress.unconfirmedCount > 0) {
                        Text(
                            "Unconfirmed means Android never told us what happened - usually a " +
                                "short-code confirmation dialog still waiting for you. Those may " +
                                "still send, so they were left unread.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (progress.dryRun) {
                        Text(
                            "Dry run: no messages are leaving this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    progress.currentAddress?.let {
                        Text("Sending to $it…", style = MaterialTheme.typography.bodySmall)
                    }

                    progress.nextSendAtMillis?.let { Countdown(it) }
                }
            }
        }

        items(progress.outcomes.asReversed()) { outcome ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (outcome.status) {
                        SendStatus.FAILED, SendStatus.UNCONFIRMED ->
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(outcome.address, style = MaterialTheme.typography.bodyMedium)
                        outcome.detail?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        when (outcome.status) {
                            SendStatus.SENT -> "${outcome.keyword} ✓"
                            SendStatus.FAILED -> "failed"
                            SendStatus.UNCONFIRMED -> "unconfirmed"
                            SendStatus.CLEARED -> "cleared"
                            SendStatus.CANCELLED -> "not sent"
                            else -> outcome.status.name.lowercase()
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** Live countdown so a long gap between sends reads as deliberate rather than as a hang. */
@Composable
private fun Countdown(targetMillis: Long) {
    var remaining by remember(targetMillis) {
        mutableLongStateOf(max(0L, targetMillis - System.currentTimeMillis()))
    }
    LaunchedEffect(targetMillis) {
        while (remaining > 0) {
            delay(500)
            remaining = max(0L, targetMillis - System.currentTimeMillis())
        }
    }
    Text(
        "Next reply in ${(remaining / 1000)}s",
        style = MaterialTheme.typography.bodySmall,
    )
}
