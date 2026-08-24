package com.batya.stopsmsspam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
    onRequestContacts: () -> Unit,
    onSetHideBlocked: (Boolean) -> Unit,
    onToggle: (SpamSender) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkRead: (SpamSender) -> Unit,
    onDelete: (SpamSender) -> Unit,
    onMarkAllUnsubscribedRead: () -> Unit,
    onBlock: (SpamSender) -> Unit,
    onSetDelete: (SpamSender, Boolean) -> Unit,
    onSetBlock: (SpamSender, Boolean) -> Unit,
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
                TextButton(onClick = onSelectAll) { Text("Select all") }
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
        }

        // Shown whenever the filter is off, not only when it hid something: with it off and the
        // count at zero the line is the only way to discover the toggle exists at all.
        if (state.hiddenBlockedCount > 0 || !state.settings.hideBlockedSenders) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.settings.hideBlockedSenders) {
                            countOf(state.hiddenBlockedCount, "sender") +
                                " hidden - already blocked"
                        } else {
                            "Showing senders you have already blocked"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onSetHideBlocked(!state.settings.hideBlockedSenders) },
                    ) {
                        Text(if (state.settings.hideBlockedSenders) "Show them" else "Hide them")
                    }
                }
            }
        }

        val skippedBySelectAll = state.senders.count { !it.includedInSelectAll }
        if (skippedBySelectAll > 0) {
            item {
                Text(
                    "\"Select all\" skips " + countOf(skippedBySelectAll, "sender") +
                        " that never offered an opt-out - replying only confirms the number is " +
                        "live. Select those by hand if you want them.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (!state.contactFilterActive) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Contacts access is off, so people you know cannot be filtered " +
                                "out - anyone in this list could be one of them. Check each " +
                                "sender before selecting, and remember the batch deletes.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRequestContacts) { Text("Grant contacts access") }
                    }
                }
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
                deletes = state.deletesMessages(sender),
                blocks = state.blocksNumber(sender),
                onSetDelete = { onSetDelete(sender, it) },
                onSetBlock = { onSetBlock(sender, it) },
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
    deletes: Boolean,
    blocks: Boolean,
    onSetDelete: (Boolean) -> Unit,
    onSetBlock: (Boolean) -> Unit,
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sender.displayAddress, style = MaterialTheme.typography.titleSmall)
                        if (sender.hasMms) MmsBadge(sender.isAllMms)
                    }
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

                // Keyed on canReply, not on isUnsubscribed: a sender that was sent an opt-out
                // and never answered is still one the batch will clear rather than text, and
                // showing it a "Reply STOP" chip would promise something that will not happen.
                if (sender.canReply) {
                    KeywordChip(keyword, sender.keyword.confidence)
                } else {
                    UnsubscribedRow(sender, onMarkRead, onDelete, onBlock)
                }

                // Always shown, disabled until the sender is selected. Hiding them meant the
                // row changed height on every selection and, more to the point, that the
                // defaults - notably a pre-ticked "block" on a sender that ignored its opt-out -
                // were invisible until you had already committed to acting on it.
                DispositionControls(
                    enabled = selected,
                    deletes = deletes,
                    blocks = blocks,
                    onSetDelete = onSetDelete,
                    onSetBlock = onSetBlock,
                )
            }
        }
    }
}

/**
 * Marks a sender whose unread messages are not plain SMS.
 *
 * Worth distinguishing because an MMS row does not behave like the SMS ones around it: the
 * preview is whatever text the message carried, so a picture-only blast shows up near-empty and
 * keyword detection has less to work with. The reply still goes out as an ordinary SMS.
 *
 * Outlined rather than filled: the row's own background changes on selection, and a tonal badge
 * would disappear into the selected colour.
 */
@Composable
private fun MmsBadge(allMms: Boolean) {
    Text(
        if (allMms) "MMS" else "SMS + MMS",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Per-sender choice of what happens to the thread afterwards.
 *
 * Deleting is the default, because being rid of these is the point of the app; the toggle is
 * there for the thread worth keeping. Blocking defaults on only for a sender that acknowledged
 * an opt-out and messaged anyway, and is spelled out rather than silent - it is system-wide and
 * outlives this app, so it should never happen without the user seeing it.
 */
@Composable
private fun DispositionControls(
    enabled: Boolean,
    deletes: Boolean,
    blocks: Boolean,
    onSetDelete: (Boolean) -> Unit,
    onSetBlock: (Boolean) -> Unit,
) {
    // These sit inside the row's own clickable card, so any tap they do not handle reaches it
    // and toggles the selection - which, while enabled, would hide nothing but would undo the
    // very selection being configured. The container swallows what the rows below do not.
    //
    // Disabled, the swallow is deliberately dropped: a tap should then do what tapping anywhere
    // else on the card does, select the sender, which is also what makes these usable.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.noRippleClickable { } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Labels state the action, not the current setting: the checkbox already carries that,
        // and a label that flips between "Delete" and "Keep" makes the pair read as two
        // different questions. Unticked "Delete" means the thread is kept and marked read - the
        // Review summary spells that consequence out.
        DispositionToggle(
            enabled = enabled,
            checked = deletes,
            label = "Delete",
            onToggle = onSetDelete,
        )
        DispositionToggle(
            enabled = enabled,
            checked = blocks,
            label = "Block",
            onToggle = onSetBlock,
            color = if (blocks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One disposition checkbox and its label, clickable as a unit.
 *
 * Making the whole row the hit target does two things at once: a tap on the words does what the
 * user plainly meant instead of nothing, and it consumes the event that would otherwise fall
 * through to the card and deselect the sender.
 */
@Composable
private fun DispositionToggle(
    enabled: Boolean,
    checked: Boolean,
    label: String,
    onToggle: (Boolean) -> Unit,
    color: Color = Color.Unspecified,
) {
    Row(
        // Wraps its content rather than filling: the two toggles sit side by side, so each must
        // claim only its own width or the first would swallow the row.
        modifier = Modifier
            .then(if (enabled) Modifier.noRippleClickable { onToggle(!checked) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            // Dimmed rather than hidden: the state still reads, it just is not actionable yet.
            color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

/**
 * Clickable without a ripple. These live inside a card that already shows its own press
 * feedback; a second ripple within it would read as a separate button.
 */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
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
 * opt-out probably will not honour one, and replying tells them the number is live. Note this is
 * the absence of a signal, not a judgement about the sender - see `SpamSender.hasOptOutLanguage`.
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
