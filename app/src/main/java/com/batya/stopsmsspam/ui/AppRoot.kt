package com.batya.stopsmsspam.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmSend by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshEnvironment() }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshEnvironment() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.screen) {
                            Screen.Setup -> "Stop SMS Spam"
                            Screen.Inbox -> "Unread (${state.senders.size})"
                            Screen.Review -> "Review " + countOf(state.selected.size, "reply", "replies")
                            Screen.Progress -> "Opt-out batch"
                        },
                    )
                },
                navigationIcon = {
                    if (state.screen == Screen.Review) {
                        IconButton(onClick = { viewModel.goTo(Screen.Inbox) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (state.screen == Screen.Inbox) {
                        IconButton(onClick = viewModel::refreshInbox) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (state.isDefaultSmsApp && state.screen != Screen.Progress) {
                        TextButton(onClick = { context.startActivity(viewModel.handBackIntent()) }) {
                            Text("Restore SMS app")
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomActions(
                state = state,
                running = progress.running,
                finished = progress.finished,
                onReview = { viewModel.goTo(Screen.Review) },
                onSend = {
                    val blocking = state.selectedSenders.any { state.blocksNumber(it) }
                    val needsConfirming =
                        !state.settings.dryRun && (state.selectedForReply.isNotEmpty() || blocking)
                    if (needsConfirming) confirmSend = true else viewModel.startBatch()
                },
                onCancel = viewModel::cancelBatch,
                onDone = viewModel::finishBatch,
            )
        },
    ) { padding ->
        when (state.screen) {
            Screen.Setup -> SetupScreen(
                state = state,
                contentPadding = padding,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                onRequestRole = {
                    viewModel.roleRequestIntent()?.let(roleLauncher::launch)
                },
            )

            Screen.Inbox -> InboxScreen(
                state = state,
                contentPadding = padding,
                onRequestContacts = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                },
                onToggle = viewModel::toggleSelection,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onMarkRead = viewModel::markRead,
                onDelete = viewModel::deleteMessages,
                onMarkAllUnsubscribedRead = viewModel::markAllUnsubscribedRead,
                onBlock = viewModel::blockSender,
                onSetDelete = viewModel::setDeleteMessages,
                onSetBlock = viewModel::setBlockNumber,
            )

            Screen.Review -> ReviewScreen(
                state = state,
                contentPadding = padding,
                onKeywordChange = viewModel::setKeyword,
                onSettingsChange = { updated -> viewModel.updateSettings { updated } },
            )

            Screen.Progress -> {
                // Confirmations arrive while this screen is up, so it polls gently rather than
                // waiting for a resume the user may not perform - they are watching this list
                // precisely to see whether the opt-outs took.
                LaunchedEffect(progress.outcomes.size, progress.running) {
                    while (true) {
                        viewModel.refreshConfirmations()
                        delay(5_000)
                    }
                }
                ProgressScreen(
                    progress = progress,
                    confirmedAddresses = state.confirmedAddresses,
                    contentPadding = padding,
                )
            }
        }
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = {
                Text(
                    if (state.selectedForReply.isEmpty()) "Block and clear these threads?"
                    else "Send " + countOf(state.selectedForReply.size, "real text message") + "?",
                )
            },
            text = {
                Text(
                    buildString {
                        if (state.selectedForReply.isNotEmpty()) {
                            append("Dry run is off. This will text ")
                            append(countOf(state.selectedForReply.size, "number"))
                            append(", ${state.settings.delaySeconds}s apart, from your SIM. ")
                            append("Standard message rates apply.")
                        }
                        if (state.selectedForCleanup.isNotEmpty()) {
                            append(
                                " The other ${state.selectedForCleanup.size} " +
                                    "will be cleared without being texted.",
                            )
                        }
                        val blocking = state.selectedSenders.count { state.blocksNumber(it) }
                        if (blocking > 0) {
                            append(
                                " " + countOf(blocking, "number") + " will be blocked " +
                                    "system-wide, which outlives this app.",
                            )
                        }
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmSend = false
                        viewModel.startBatch()
                    },
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSend = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BottomActions(
    state: UiState,
    running: Boolean,
    finished: Boolean,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    when (state.screen) {
        Screen.Setup -> Unit

        Screen.Inbox -> BottomBarRow {
            Button(
                onClick = onReview,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.selected.isEmpty()) "Select the spam to reply to"
                    else "Review " + countOf(state.selected.size, "reply", "replies"),
                )
            }
        }

        Screen.Review -> BottomBarRow {
            Button(
                onClick = onSend,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.settings.dryRun -> "Start dry run (${state.selected.size})"
                        state.selectedForReply.isEmpty() ->
                            "Clear " + countOf(state.selected.size, "thread")
                        state.selectedForCleanup.isEmpty() ->
                            "Send " + countOf(state.selectedForReply.size, "opt-out reply", "opt-out replies")
                        else ->
                            "Send ${state.selectedForReply.size}, clear ${state.selectedForCleanup.size}"
                    },
                )
            }
        }

        Screen.Progress -> BottomBarRow {
            if (running) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel batch")
                }
            } else if (finished) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun BottomBarRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // Scaffold does not inset a custom bottomBar, so without this the button sits
            // under the gesture/navigation bar.
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * SMS permissions plus notifications - the latter is not cosmetic here, it is how the user sees
 * texts at all while this app holds the SMS role.
 */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    // Not required to run, but without it the app cannot tell a stranger from somebody in the
    // user's contacts, and the batch deletes threads.
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

