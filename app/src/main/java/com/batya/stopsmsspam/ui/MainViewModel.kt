package com.batya.stopsmsspam.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batya.stopsmsspam.bulk.BatchSnapshot
import com.batya.stopsmsspam.bulk.BulkReplyController
import com.batya.stopsmsspam.bulk.BulkReplyService
import com.batya.stopsmsspam.data.AppSettings
import com.batya.stopsmsspam.data.SettingsStore
import com.batya.stopsmsspam.data.BlockedNumbers
import com.batya.stopsmsspam.data.SmsRepository
import com.batya.stopsmsspam.data.model.ReplyPlan
import com.batya.stopsmsspam.data.model.SpamSender
import com.batya.stopsmsspam.role.SmsRoleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { Setup, Inbox, Review, Progress }

data class UiState(
    val screen: Screen = Screen.Setup,
    val isDefaultSmsApp: Boolean = false,
    val hasSmsPermissions: Boolean = false,
    val currentDefaultLabel: String? = null,
    val loading: Boolean = false,
    val senders: List<SpamSender> = emptyList(),
    /** Keyed by `SpamSender.normalizedAddress`. */
    val selected: Set<String> = emptySet(),
    /** User edits to the detected keyword, keyed by `SpamSender.normalizedAddress`. */
    val keywordOverrides: Map<String, String> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    /** Address whose block attempt failed, so the UI can say so rather than silently no-op. */
    val lastBlockFailed: String? = null,
) {
    val ready: Boolean get() = isDefaultSmsApp && hasSmsPermissions
    val selectedSenders: List<SpamSender> get() = senders.filter { it.normalizedAddress in selected }

    fun keywordFor(sender: SpamSender): String =
        keywordOverrides[sender.normalizedAddress] ?: sender.keyword.keyword
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = SmsRepository(application)
    private val blockedNumbers = BlockedNumbers(application)
    private val roleManager = SmsRoleManager(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val progress = BulkReplyController.progress

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        refreshEnvironment()
    }

    /**
     * Re-checks the role and permissions. Called on every resume, because the user grants both
     * of them in system UI that we hand off to and come back from.
     */
    fun refreshEnvironment() {
        val app = getApplication<Application>()
        val isDefault = roleManager.isDefaultSmsApp()
        val hasPermissions = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
            .all {
                ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
            }

        _state.update { current ->
            val nextScreen = when {
                BulkReplyController.progress.value.running -> Screen.Progress
                !(isDefault && hasPermissions) -> Screen.Setup
                current.screen == Screen.Setup -> Screen.Inbox
                else -> current.screen
            }
            current.copy(
                isDefaultSmsApp = isDefault,
                hasSmsPermissions = hasPermissions,
                currentDefaultLabel = roleManager.currentDefaultLabel(),
                screen = nextScreen,
            )
        }

        if (isDefault && hasPermissions) refreshInbox()
    }

    fun refreshInbox() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val fallback = settingsStore.current().fallbackKeyword
            val senders = repository.loadUnreadSenders(fallback, repository.loadOptOutStatus())
            _state.update { current ->
                // Drop selections and edits for senders that are no longer unread.
                val liveKeys = senders.filter { it.canReply }.map { it.normalizedAddress }.toSet()
                current.copy(
                    loading = false,
                    senders = senders,
                    selected = current.selected intersect liveKeys,
                    keywordOverrides = current.keywordOverrides.filterKeys { it in liveKeys },
                )
            }
        }
    }

    fun toggleSelection(sender: SpamSender) {
        // Already opted out: there is nothing to send them, so the row is not selectable.
        if (!sender.canReply) return
        _state.update { current ->
            val key = sender.normalizedAddress
            current.copy(
                selected = if (key in current.selected) current.selected - key else current.selected + key,
            )
        }
    }

    /**
     * Selects senders that published an opt-out keyword and have not already been opted out of.
     * Both exclusions matter: the first keeps the user from replying to scam numbers, the second
     * from re-texting a sender whose only new message is their own unsubscribe confirmation.
     */
    fun selectAllWithOptOut() {
        _state.update { current ->
            current.copy(
                selected = current.senders.filter { it.hasOptOutLanguage && it.canReply }
                    .map { it.normalizedAddress }
                    .toSet(),
            )
        }
    }

    /** Clears an already-unsubscribed sender's messages from the unread list. */
    fun markRead(sender: SpamSender) {
        viewModelScope.launch {
            repository.markRead(sender.messageIds)
            refreshInbox()
        }
    }

    /** Deletes an already-unsubscribed sender's messages outright. */
    fun deleteMessages(sender: SpamSender) {
        viewModelScope.launch {
            repository.delete(sender.messageIds)
            refreshInbox()
        }
    }

    fun markAllUnsubscribedRead() {
        viewModelScope.launch {
            val ids = _state.value.senders.filter { it.isUnsubscribed }.flatMap { it.messageIds }
            if (ids.isNotEmpty()) repository.markRead(ids)
            refreshInbox()
        }
    }

    /**
     * Blocks a sender that confirmed the opt-out and then texted anyway, and clears its messages.
     *
     * Deliberately one sender at a time and never part of a batch: blocking is system-wide and
     * outlives this app, so it should be a decision the user makes about a specific number.
     */
    fun blockSender(sender: SpamSender) {
        viewModelScope.launch {
            val blocked = blockedNumbers.block(sender.displayAddress)
            _state.update { it.copy(lastBlockFailed = if (blocked) null else sender.displayAddress) }
            if (blocked) repository.markRead(sender.messageIds)
            refreshInbox()
        }
    }

    fun dismissBlockError() {
        _state.update { it.copy(lastBlockFailed = null) }
    }

    /** Bulk mark-read for senders that acknowledged the opt-out. */

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun setKeyword(sender: SpamSender, keyword: String) {
        _state.update { current ->
            current.copy(
                keywordOverrides = current.keywordOverrides +
                    (sender.normalizedAddress to keyword.uppercase()),
            )
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun goTo(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    fun roleRequestIntent() = roleManager.requestRoleIntent()

    fun handBackIntent() = roleManager.handBackIntent()

    fun startBatch() {
        val current = _state.value
        val plans = current.selectedSenders.map { sender ->
            ReplyPlan(
                address = sender.displayAddress,
                keyword = current.keywordFor(sender),
                messageIds = sender.messageIds,
                subscriptionId = sender.subscriptionId,
            )
        }
        if (plans.isEmpty()) return

        BulkReplyService.start(
            getApplication(),
            BatchSnapshot(
                plans = plans,
                delaySeconds = current.settings.delaySeconds,
                jitterPercent = current.settings.jitterPercent,
                dryRun = current.settings.dryRun,
                markReadAfterSend = current.settings.markReadAfterSend,
                deleteAfterSend = current.settings.deleteAfterSend,
            ),
        )
        goTo(Screen.Progress)
    }

    fun cancelBatch() {
        BulkReplyService.cancel(getApplication())
    }

    /** Leaves the results screen: clears progress, reloads the inbox, drops the old selection. */
    fun finishBatch() {
        BulkReplyController.reset()
        _state.update { it.copy(selected = emptySet(), keywordOverrides = emptyMap()) }
        refreshInbox()
        goTo(Screen.Inbox)
    }
}
