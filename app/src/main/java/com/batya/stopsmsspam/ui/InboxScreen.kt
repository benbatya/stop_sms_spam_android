package com.batya.stopsmsspam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
    onMarkRead: (SpamSender) -> Unit,
    onDelete: (SpamSender) -> Unit,
    onMarkAllUnsubscribedRead: () -> Unit,
    onBlock: (SpamSender) -> Unit,
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

        val unsubscribedCount = state.senders.count { it.isUnsubscribed }
        if (unsubscribedCount > 0) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            countOf(unsubscribedCount, "sender") +
                                " already unsubscribed. Their newer messages are usually just " +
                                "the confirmation - clear them rather than replying again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onMarkAllUnsubscribedRead) {
                            Text("Mark all as read")
                        }
                    }
                }
            }
        }

        items(state.senders, key = { it.normalizedAddress }) { sender ->
            SenderRow(
                sender = sender,
                keyword = state.keywordFor(sender),
                selected = sender.normalizedAddress in state.selected,
                onToggle = { onToggle(sender) },
                onMarkRead = { onMarkRead(sender) },
                onDelete = { onDelete(sender) },
                onBlock = { onBlock(sender) },
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
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
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
            // Selectable whether or not a reply is due: an already-opted-out thread is still
            // something the user wants dealt with, it just gets cleared instead of texted.
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

                if (sender.isUnsubscribed) {
                    UnsubscribedRow(sender, onMarkRead, onDelete, onBlock)
                } else {
                    KeywordChip(keyword, sender.keyword.confidence)
                }
            }
        }
    }
}

/**
 * What a sender that has already been told to stop offers instead of a reply.
 *
 * The two states are shown apart on purpose. A confirmed opt-out is finished business; one the
 * sender never acknowledged is not, and collapsing them would tell the user a sender is done
 * with them when nothing supports that.
 */
@Composable
private fun UnsubscribedRow(
    sender: SpamSender,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    val status = sender.optedOut ?: return
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val sentOn = dates.format(Date(status.sentAtMillis))

    val label = when {
        sender.ignoredOptOut ->
            "STOP IGNORED - confirmed unsubscribed, then texted again " +
                dates.format(Date(sender.optOutViolatedAt!!))
        status.isConfirmed ->
            "Unsubscribed - they confirmed on ${dates.format(Date(status.confirmedAtMillis!!))}"
        else -> "\"${status.keyword}\" sent $sentOn - no confirmation yet"
    }

    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = when {
            sender.ignoredOptOut -> AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            status.isConfirmed -> AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            else -> AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    if (sender.ignoredOptOut) {
        Text(
            "This sender agreed to stop and then messaged you anyway. Replying again will not " +
                "help - it already ignored its own opt-out.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        "Select to clear this thread - no reply will be sent.",
        style = MaterialTheme.typography.labelSmall,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sender.ignoredOptOut) {
            TextButton(onClick = onBlock) { Text("Block number") }
        }
        TextButton(onClick = onMarkRead) { Text("Mark read") }
        TextButton(onClick = onDelete) { Text("Delete") }
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
