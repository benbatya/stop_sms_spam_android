package com.batya.stopsmsspam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 */
@Composable
fun ReviewScreen(
    state: UiState,
    contentPadding: PaddingValues,
    onKeywordChange: (SpamSender, String) -> Unit,
    onSettingsChange: (com.batya.stopsmsspam.data.AppSettings) -> Unit,
) {
    val senders = state.selectedSenders
    val settings = state.settings
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

                    CommittingSlider(
                        value = settings.delaySeconds,
                        range = SendPacing.MIN_DELAY_SECONDS..SendPacing.MAX_DELAY_SECONDS,
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
                    SettingSwitch(
                        title = "Mark the spam as read",
                        subtitle = "Clears it from your unread list once the reply is confirmed",
                        checked = settings.markReadAfterSend,
                        onCheckedChange = { onSettingsChange(settings.copy(markReadAfterSend = it)) },
                    )
                    SettingSwitch(
                        title = "Delete the spam instead",
                        subtitle = "Permanently removes those messages. Overrides mark-as-read.",
                        checked = settings.deleteAfterSend,
                        onCheckedChange = { onSettingsChange(settings.copy(deleteAfterSend = it)) },
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
                    "$noOptOut of these senders never offered a way to opt out. Replying to a " +
                        "scam number does not stop it - it confirms your number is live. " +
                        "Consider removing them below.",
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        item {
            Text(
                "Messages to send",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        items(senders, key = { it.normalizedAddress }) { sender ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sender.displayAddress, style = MaterialTheme.typography.titleSmall)
                        Text(
                            sender.latestBody,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!sender.hasOptOutLanguage) {
                            Text(
                                "No opt-out instruction in this message",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.keywordFor(sender),
                        onValueChange = { onKeywordChange(sender, it) },
                        label = { Text("Send") },
                        singleLine = true,
                        modifier = Modifier.width(130.dp),
                    )
                }
            }
        }
    }
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
