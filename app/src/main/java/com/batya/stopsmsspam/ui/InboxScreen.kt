package com.batya.stopsmsspam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batya.stopsmsspam.data.model.KeywordConfidence
import com.batya.stopsmsspam.data.model.SpamSender
import java.text.DateFormat
import java.util.Date

@Composable
fun InboxScreen(
    state: UiState,
    contentPadding: PaddingValues,
    onToggle: (SpamSender) -> Unit,
    onSelectAllWithOptOut: () -> Unit,
    onClearSelection: () -> Unit,
) {
    if (state.loading && state.senders.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    if (state.senders.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No unread messages", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing to opt out of right now. Pull the list again after the next batch arrives.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectAllWithOptOut) { Text("Select all with opt-out") }
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
        }

        items(state.senders, key = { it.normalizedAddress }) { sender ->
            SenderRow(
                sender = sender,
                keyword = state.keywordFor(sender),
                selected = sender.normalizedAddress in state.selected,
                onToggle = { onToggle(sender) },
            )
        }
    }
}

@Composable
private fun SenderRow(
    sender: SpamSender,
    keyword: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onToggle),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(
                Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(sender.displayAddress, style = MaterialTheme.typography.titleSmall)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(sender.latestDate)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (sender.messageCount > 1) {
                    Text(
                        "${sender.messageCount} unread messages - one reply covers all of them",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Text(
                    sender.latestBody,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                KeywordChip(keyword, sender.keyword.confidence)
            }
        }
    }
}

/**
 * The chip carries the warning that matters most: a sender whose message never mentioned an
 * opt-out probably will not honour one, and replying tells a scammer the number is live.
 */
@Composable
private fun KeywordChip(keyword: String, confidence: KeywordConfidence) {
    val (label, warning) = when (confidence) {
        KeywordConfidence.EXPLICIT -> "Reply \"$keyword\" (they asked for it)" to false
        KeywordConfidence.LIKELY -> "Reply \"$keyword\" (probable)" to false
        KeywordConfidence.ASSUMED -> "No opt-out offered - \"$keyword\" is a guess" to true
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = if (warning) {
            AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
