package com.batya.stopsmsspam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The gate in front of everything else. Taking the SMS role is a bigger deal than a normal
 * permission - it displaces the user's messaging app - so the trade-offs are stated here in full
 * before they agree to it, not buried in a settings screen afterwards.
 */
@Composable
fun SetupScreen(
    state: UiState,
    contentPadding: PaddingValues,
    onRequestPermissions: () -> Unit,
    onRequestRole: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Bulk-reply STOP to spam",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "This app lists your unread texts grouped by sender, lets you pick the spam, and " +
                "sends each one the opt-out keyword it asks for - spaced out so your carrier " +
                "and Android's own SMS limit do not choke on the burst.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Read this before granting the role", style = MaterialTheme.typography.titleMedium)
                Text(
                    "To mark spam as read and file its replies into your real conversations, this " +
                        "app has to become your default SMS app. While it holds that role:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Incoming texts are saved and shown as notifications by this app. They " +
                        "stay in your message database, so your normal app will have them all " +
                        "when you switch back.\n" +
                        "• Picture and group messages (MMS) will NOT arrive. This app parks " +
                        "them unopened and tells you. Nothing else recovers them.\n" +
                        "• So: take the role, clean up your spam, and hand it straight back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        StepCard(
            number = 1,
            title = "Allow reading and sending SMS",
            done = state.hasSmsPermissions,
        ) {
            Button(onClick = onRequestPermissions, enabled = !state.hasSmsPermissions) {
                Text(if (state.hasSmsPermissions) "Granted" else "Grant permissions")
            }
        }

        StepCard(
            number = 2,
            title = "Become the default SMS app",
            done = state.isDefaultSmsApp,
            subtitle = state.currentDefaultLabel
                ?.takeUnless { state.isDefaultSmsApp }
                ?.let { "Currently: $it" },
        ) {
            Button(onClick = onRequestRole, enabled = !state.isDefaultSmsApp) {
                Text(if (state.isDefaultSmsApp) "This app is the default" else "Make this the SMS app")
            }
        }

        if (state.ready) {
            Text(
                "Setup complete. Your unread messages are loading.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    subtitle: String? = null,
    action: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Step $number — $title" + if (done) "  ✓" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            action()
        }
    }
}

/** Shown from the top bar so handing the role back is always one tap away. */
@Composable
fun HandBackButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text("Restore my SMS app") }
}
