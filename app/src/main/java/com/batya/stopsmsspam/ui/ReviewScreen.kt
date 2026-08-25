package com.batya.stopsmsspam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batya.stopsmsspam.bulk.SendPacing
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.model.SpamSender
import kotlin.math.roundToInt

/**
 * Last stop before anything is sent: every outgoing keyword is editable, the pacing is set here,
 * and the warnings that depend on the batch as a whole (throttle, senders with no opt-out) are
 * surfaced against the concrete list.
 *
 * It is also where a sender can be dropped from the conversation without being dropped from the
 * batch. Going back to the Inbox and deselecting one leaves its thread sitting there unread;
 * "Delete instead of replying" clears it and sends nothing, which for a sender that never
 * offered an opt-out is usually the better answer.
 */
@Composable
fun ReviewScreen(
    state: UiState,
    contentPadding: PaddingValues,
    onDeleteSendersWithoutOptOut: () -> Unit,
    onSettingsChange: (com.batya.stopsmsspam.data.AppSettings) -> Unit,
) {
    val settings = state.settings
    // Only senders that will actually be texted matter for pacing, throttling and the
    // short-code warning; a cleared thread never touches the radio.
    val senders = state.selectedForReply
    val alreadyOptedOut = state.selectedAlreadyOptedOut
    val deleteOnly = state.selectedDeleteOnly
    val throttled = SendPacing.exceedsFrameworkThrottle(senders.size, settings.delaySeconds)
    val noOptOut = senders.count { !it.hasOptOutLanguage }
    val shortCodes = senders.count { PhoneAddress.isShortCode(it.displayAddress) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Pacing", style = MaterialTheme.typography.titleMedium)

                    SteppedSlider(
                        value = settings.delaySeconds,
                        steps = SendPacing.DELAY_STEPS,
                        label = { "Delay between replies: ${it}s" },
                        onCommit = { onSettingsChange(settings.copy(delaySeconds = it)) },
                    )

                    CommittingSlider(
                        value = settings.jitterPercent,
                        range = 0..SendPacing.MAX_JITTER_PERCENT,
                        label = { "Random variation: ±$it%" },
                        onCommit = { onSettingsChange(settings.copy(jitterPercent = it)) },
                    )

                    Text(
                        countOf(senders.size, "reply", "replies") + ", roughly " +
                            formatDuration(
                                SendPacing.estimatedDurationSeconds(senders.size, settings.delaySeconds),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (throttled) {
                        // A safe delay only exists for batches small enough that one fits inside
                        // the supported range; past that the honest thing is to warn and say why,
                        // not to offer a button that changes a number without fixing anything.
                        val safeDelay = SendPacing.safeDelaySecondsFor(senders.size)
                        WarningCard(
                            buildString {
                                append("Android blocks an app after ")
                                append("${SendPacing.FRAMEWORK_BURST_LIMIT} messages in 30 ")
                                append("minutes and then asks you to confirm each one. ")
                                append("At this pace that limit will be hit. ")
                                if (safeDelay == null) {
                                    append(
                                        "No delay this app offers is long enough to avoid it for " +
                                            "a batch this size - send fewer at a time, or expect " +
                                            "to tap through a confirmation for the later ones.",
                                    )
                                }
                            },
                            action = safeDelay?.let { seconds ->
                                {
                                    TextButton(
                                        onClick = {
                                            onSettingsChange(settings.copy(delaySeconds = seconds))
                                        },
                                    ) { Text("Use a safe delay (${seconds}s)") }
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What happens after each send", style = MaterialTheme.typography.titleMedium)

                    SettingSwitch(
                        title = "Dry run",
                        subtitle = "Walk through the whole batch without sending a single message",
                        checked = settings.dryRun,
                        onCheckedChange = { onSettingsChange(settings.copy(dryRun = it)) },
                    )
                    // What becomes of each thread is chosen per sender in the list, not here:
                    // one global switch cannot say "delete these, keep that one, block the one
                    // that ignored its opt-out".
                    Text(
                        "What happens to each thread - delete, keep, or block - is set per " +
                            "sender when you select it. The summary below shows the result.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        if (shortCodes > 0) {
            item {
                WarningCard(
                    countOf(shortCodes, "of these is a short code", "of these are short codes") + ". Android asks you to confirm messages " +
                        "to some short codes one at a time - tick \"Remember my choice\" on the " +
                        "first dialog, or the batch will run ahead of you and mark them unconfirmed.",
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        if (noOptOut > 0) {
            item {
                WarningCard(
                    "$noOptOut of these senders never said how to opt out. A reply probably " +
                        "will not stop them, and it does tell them the number is live. " +
                        "Clear their threads instead, or remove them below.",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    // The card has been telling the user to reconsider these senders for a
                    // while; it may as well carry the action rather than describe it.
                    action = {
                        TextButton(onClick = onDeleteSendersWithoutOptOut) {
                            Text("Delete " + countOf(noOptOut, "thread") + " instead")
                        }
                    },
                )
            }
        }

        item {
            val toDelete = state.selectedSenders.count { state.deletesMessages(it) }
            val toKeep = state.selectedSenders.size - toDelete
            val toBlock = state.selectedSenders.count { state.blocksNumber(it) }
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("All together", style = MaterialTheme.typography.titleSmall)
                    if (senders.isNotEmpty()) {
                        Text("• " + countOf(senders.size, "opt-out reply", "opt-out replies") + " sent",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    // The two silent halves are counted apart, because only one of them is a
                    // decision the user made and could still change.
                    if (alreadyOptedOut.isNotEmpty()) {
                        Text(
                            "• " + countOf(alreadyOptedOut.size, "sender") +
                                " already opted out - cleared, nothing sent",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (deleteOnly.isNotEmpty()) {
                        Text(
                            "• " + countOf(deleteOnly.size, "sender") +
                                " you chose not to reply to",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (toDelete > 0) Text("• " + countOf(toDelete, "thread") + " deleted",
                        style = MaterialTheme.typography.bodySmall)
                    if (toKeep > 0) Text("• " + countOf(toKeep, "thread") + " kept, marked read",
                        style = MaterialTheme.typography.bodySmall)
                    if (toBlock > 0) {
                        Text(
                            "• " + countOf(toBlock, "number") + " blocked - system-wide, and it " +
                                "outlives this app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Sender by sender",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Every selected sender, not only the ones being texted. This screen is the last thing
        // between the user and a batch that deletes threads and blocks numbers, so it states
        // what will happen to each of them rather than listing the messages and leaving the
        // rest to be inferred from summary counts.
        items(state.selectedSenders, key = { it.normalizedAddress }) { sender ->
            SenderPlanCard(
                sender = sender,
                replies = state.sendsReply(sender),
                outgoing = state.outgoingKeyword(sender),
                deletes = state.deletesMessages(sender),
                blocks = state.blocksNumber(sender),
            )
        }
    }
}

/**
 * One selected sender, and everything the batch will do to it.
 *
 * Read-only, deliberately. Every choice it reports - reply or not, which keyword, delete or
 * keep, block or not - is made on the sender's own card in the Inbox, and duplicating those
 * controls here would mean two places to look for the current setting and two places to change
 * it. What this screen is for is the last look before a batch that texts strangers and deletes
 * threads: it states the decisions rather than re-opening them.
 */
@Composable
private fun SenderPlanCard(
    sender: SpamSender,
    replies: Boolean,
    outgoing: String,
    deletes: Boolean,
    blocks: Boolean,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sender.displayAddress, style = MaterialTheme.typography.titleSmall)
            Text(
                sender.latestBody,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            if (replies) {
                // The resolved keyword, not the raw field: an emptied box still sends the
                // detected word, and this line is the promise of what goes out.
                PlanLine("Reply \"$outgoing\"")
                if (!sender.hasOptOutLanguage) {
                    PlanLine(
                        "This sender never offered an opt-out - replying mostly confirms the " +
                            "number is live",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (sender.canReply) {
                PlanLine("No reply - you chose to delete this one instead")
            } else {
                PlanLine("No reply - already opted out, so the thread is only cleared")
            }

            PlanLine(if (deletes) "Delete the thread" else "Keep the thread, marked read")

            if (blocks) {
                PlanLine(
                    "Block the number - system-wide, and it outlives this app",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** One "this is what will happen" bullet. */
@Composable
private fun PlanLine(text: String, color: Color = Color.Unspecified) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/**
 * A slider that snaps to a fixed list of values rather than sliding continuously.
 *
 * The thumb travels over evenly spaced detents while the label shows the value each one maps to,
 * so the unevenly spaced delays in [SendPacing.DELAY_STEPS] are all equally easy to hit - the
 * point of stepping it in the first place.
 */
@Composable
private fun SteppedSlider(
    value: Int,
    steps: List<Int>,
    label: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    var index by remember(value) { mutableFloatStateOf(SendPacing.stepIndexOf(value).toFloat()) }
    val selected = steps[index.roundToInt().coerceIn(steps.indices)]

    Text(label(selected), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = index,
        onValueChange = { index = it },
        onValueChangeFinished = { onCommit(selected) },
        valueRange = 0f..(steps.size - 1).toFloat(),
        // Compose counts the detents *between* the ends, so N values means N-2 steps.
        steps = (steps.size - 2).coerceAtLeast(0),
    )
}

/**
 * A slider that reports only when the finger lifts. The value is persisted to DataStore, and
 * writing on every drag frame would queue dozens of disk writes for one gesture.
 */
@Composable
private fun CommittingSlider(
    value: Int,
    range: IntRange,
    label: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    var dragged by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text(label(dragged.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = dragged,
        onValueChange = { dragged = it },
        onValueChangeFinished = { onCommit(dragged.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WarningCard(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            action?.invoke()
        }
    }
}

internal fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds < 3600 -> "${seconds / 60} min ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
