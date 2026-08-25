package com.batya.stopsmsspam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    onSetHideHandled: (Boolean) -> Unit,
    onToggle: (SpamSender) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkRead: (SpamSender) -> Unit,
    onDelete: (SpamSender) -> Unit,
    onMarkAllUnsubscribedRead: () -> Unit,
    onBlock: (SpamSender) -> Unit,
    onSetDelete: (SpamSender, Boolean) -> Unit,
    onSetBlock: (SpamSender, Boolean) -> Unit,
    onSetSendReply: (SpamSender, Boolean) -> Unit,
    onKeywordChange: (SpamSender, String) -> Unit,
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

    // Held as an address rather than the sender itself: a refresh mid-dialog replaces every
    // SpamSender instance, and a captured copy would go on showing a thread that has since been
    // dealt with. Looked up each recomposition, so a sender that disappears takes its dialog
    // with it.
    var detailAddress by remember { mutableStateOf<String?>(null) }
    val detail = detailAddress?.let { key -> state.senders.firstOrNull { it.normalizedAddress == key } }

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
        if (state.hiddenHandledCount > 0 || !state.settings.hideHandledSenders) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.settings.hideHandledSenders) {
                            countOf(state.hiddenHandledCount, "sender") +
                                " hidden - blocked, or archived in your messaging app"
                        } else {
                            "Showing senders your messaging app already dealt with"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onSetHideHandled(!state.settings.hideHandledSenders) },
                    ) {
                        Text(if (state.settings.hideHandledSenders) "Show them" else "Hide them")
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
                handled = state.handledAddresses[sender.normalizedAddress],
                selected = sender.normalizedAddress in state.selected,
                onToggle = { onToggle(sender) },
                onOpenDetails = { detailAddress = sender.normalizedAddress },
                onMarkRead = { onMarkRead(sender) },
                onDelete = { onDelete(sender) },
                onBlock = { onBlock(sender) },
                deletes = state.deletesMessages(sender),
                blocks = state.blocksNumber(sender),
                replies = state.sendsReply(sender),
                onSetDelete = { onSetDelete(sender, it) },
                onSetBlock = { onSetBlock(sender, it) },
                onSetSendReply = { onSetSendReply(sender, it) },
                onKeywordChange = { onKeywordChange(sender, it) },
            )
        }
    }

    detail?.let { sender ->
        SenderDetailDialog(
            sender = sender,
            keyword = state.keywordFor(sender),
            handled = state.handledAddresses[sender.normalizedAddress],
            replies = state.sendsReply(sender),
            selected = sender.normalizedAddress in state.selected,
            onToggleSelection = { onToggle(sender) },
            onDismiss = { detailAddress = null },
        )
    }
}

/**
 * Everything known about one sender, in full.
 *
 * The row can only ever show an excerpt - three lines of a message that may be twenty, a chip
 * for a keyword whose provenance takes a sentence to explain. Deciding whether a thread is spam
 * or a delivery notice from somebody real needs the whole thing, and this is where the user gets
 * it before ticking a box that deletes it.
 */
@Composable
private fun SenderDetailDialog(
    sender: SpamSender,
    keyword: String,
    handled: HandledReason?,
    replies: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sender.displayAddress) },
        text = {
            // Long messages are the reason this dialog exists, so the body scrolls rather than
            // pushing the buttons off the bottom of a small screen.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailSection("Latest message", stamp.format(Date(sender.latestDate))) {
                    Text(sender.latestBody, style = MaterialTheme.typography.bodyMedium)
                }

                DetailSection(
                    "This sender",
                    countOf(sender.messageCount, "unread message") + when {
                        sender.isAllMms -> " - all MMS"
                        sender.hasMms -> " - ${sender.mmsCount} of them MMS"
                        else -> ""
                    },
                ) {
                    val status = sender.optedOut
                    Text(
                        when {
                            sender.ignoredOptOut ->
                                "Confirmed the opt-out and messaged again anyway on " +
                                    dates.format(Date(sender.optOutViolatedAt!!)) +
                                    ". Asking has already been tried and did not work."
                            status?.isConfirmed == true ->
                                "Unsubscribed - \"${status.keyword}\" was sent on " +
                                    dates.format(Date(status.sentAtMillis)) + " and they " +
                                    "confirmed on " + dates.format(Date(status.confirmedAtMillis!!)) + "."
                            status != null ->
                                "\"${status.keyword}\" was sent on " +
                                    dates.format(Date(status.sentAtMillis)) +
                                    ", with no confirmation back yet."
                            else -> "No opt-out has been sent to this number yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    handled?.let {
                        Text(
                            if (it == HandledReason.BLOCKED) {
                                "Your system already blocks this number."
                            } else {
                                "This thread is archived in your messaging app."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                DetailSection("The reply", null) {
                    Text(
                        when {
                            !sender.canReply ->
                                "Nothing will be sent - this sender has already been told to " +
                                    "stop. Selecting it only clears the thread."
                            !replies ->
                                "Nothing will be sent - you chose to delete this thread instead."
                            else -> when (sender.keyword.confidence) {
                                KeywordConfidence.EXPLICIT ->
                                    "\"$keyword\" - the message spells out this keyword, so it " +
                                        "is what they asked for."
                                KeywordConfidence.LIKELY ->
                                    "\"$keyword\" - opt-out wording is present and this keyword " +
                                        "is near it, but the phrasing is loose."
                                KeywordConfidence.ASSUMED ->
                                    "\"$keyword\" - a guess. Nothing in this message says how to " +
                                        "opt out, so a reply probably will not stop them and " +
                                        "does tell them the number is live."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (replies && sender.canReply && !sender.hasOptOutLanguage) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onToggleSelection()
                    onDismiss()
                },
            ) { Text(if (selected) "Remove from batch" else "Add to batch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A labelled block in [SenderDetailDialog], with an optional grey note beside the label. */
@Composable
private fun DetailSection(title: String, note: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        note?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun SenderRow(
    sender: SpamSender,
    keyword: String,
    handled: HandledReason?,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenDetails: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    deletes: Boolean,
    blocks: Boolean,
    replies: Boolean,
    onSetDelete: (Boolean) -> Unit,
    onSetBlock: (Boolean) -> Unit,
    onSetSendReply: (Boolean) -> Unit,
    onKeywordChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onOpenDetails),
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
                        handled?.let { HandledBadge(it) }
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
                // The card shows an excerpt; tapping it opens the whole thing. Said out loud
                // because the card used to toggle selection on tap, and a control that quietly
                // changes what it does is worse than one that never did it.
                Text(
                    "Tap for the full message",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Keyed on canReply, not on isUnsubscribed: a sender that was sent an opt-out
                // and never answered is still one the batch will clear rather than text, and
                // showing it a "Reply STOP" chip would promise something that will not happen.
                if (sender.canReply) {
                    ReplyToggle(
                        enabled = selected,
                        checked = replies,
                        keyword = keyword,
                        guessed = !sender.hasOptOutLanguage,
                        onToggle = onSetSendReply,
                        onKeywordChange = onKeywordChange,
                    )
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
 * Marks a sender the messaging app already dealt with - blocked, or archived.
 *
 * Only ever seen with the hide toggle off, and that is the point: once the rows are shown, the
 * user needs to know which of them are the ones normally filtered away. Without it, turning the
 * filter off produces a longer list with no indication of what was added.
 *
 * Error-toned rather than neutral - not as a warning, but because it is the one status here that
 * says the system is already refusing this sender's messages.
 */
@Composable
private fun HandledBadge(reason: HandledReason) {
    Text(
        if (reason == HandledReason.BLOCKED) "Blocked" else "Archived",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
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
 * Whether this sender gets texted at all, and exactly what it gets.
 *
 * The keyword is typed in place rather than described, because what the app detected is only a
 * proposal - the sender is the one who decides which word works, and only they know when it is
 * something other than STOP. Editing it here means never having to carry a wrong guess through
 * to the Review screen to fix it.
 *
 * The tick sits on the same line for the same reason it always did: unticked, the words beside
 * it describe a message nobody will receive. The thread is still dealt with - the batch clears
 * it - which is what makes unticking this the "just get rid of it" answer.
 */
@Composable
private fun ReplyToggle(
    enabled: Boolean,
    checked: Boolean,
    keyword: String,
    guessed: Boolean,
    onToggle: (Boolean) -> Unit,
    onKeywordChange: (String) -> Unit,
) {
    val editable = enabled && checked
    val underline = MaterialTheme.colorScheme.outline
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        !checked -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The outer swallow keeps a stray tap from reaching the card and opening the dialog
        // over a field the user is in the middle of typing into.
        Row(
            modifier = Modifier.then(if (enabled) Modifier.noRippleClickable { } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .then(if (enabled) Modifier.noRippleClickable { onToggle(!checked) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = onToggle, enabled = enabled)
                Text("Reply \"", style = MaterialTheme.typography.bodyMedium, color = ink)
            }

            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                enabled = editable,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = ink,
                    textDecoration = if (checked) null else TextDecoration.LineThrough,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Keywords are upper-case by convention and `setKeyword` upper-cases anyway;
                // the keyboard may as well not fight it.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .width(96.dp)
                    .drawBehind {
                        // Underlined rather than boxed: it has to read as a word inside a
                        // sentence, which a full input outline would break up.
                        if (editable) {
                            drawLine(
                                color = underline,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    },
            )

            Text("\"", style = MaterialTheme.typography.bodyMedium, color = ink)
        }

        // What the dropped "(probable)" suffix used to carry. Only the one case is worth a line
        // of its own: the others say a keyword was found, this one says none was, which is the
        // difference between a reply that stops somebody and a reply that confirms the number
        // is live to a stranger.
        if (guessed && checked) {
            Text(
                "No opt-out offered - this keyword is a guess",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
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

