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
import com.batya.stopsmsspam.data.SenderMemory
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
    /**
     * Senders the user chose to keep rather than delete, keyed by normalized address. Deleting
     * is the default - being rid of the messages is the point - so this records the exceptions.
     */
    val keepMessages: Set<String> = emptySet(),
    /** Per-sender overrides of the default blocking decision, keyed by normalized address. */
    val blockOverrides: Map<String, Boolean> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    /** Address whose block attempt failed, so the UI can say so rather than silently no-op. */
    val lastBlockFailed: String? = null,
) {
    val ready: Boolean get() = isDefaultSmsApp && hasSmsPermissions
    val selectedSenders: List<SpamSender> get() = senders.filter { it.normalizedAddress in selected }

    /** Selected senders that will actually be texted - what the confirmation dialog counts. */
    val selectedForReply: List<SpamSender> get() = selectedSenders.filter { it.canReply }

    /** Selected senders that will only have their threads cleared. */
    val selectedForCleanup: List<SpamSender> get() = selectedSenders.filter { !it.canReply }

    /** Whether the sender's messages will be deleted; false means only marked read. */
    fun deletesMessages(sender: SpamSender): Boolean =
        sender.normalizedAddress !in keepMessages

    /**
     * Whether the number will be blocked. Defaults on for a sender that acknowledged an opt-out
     * and messaged anyway - asking it politely has already been tried and demonstrably failed.
     */
    fun blocksNumber(sender: SpamSender): Boolean =
        blockOverrides[sender.normalizedAddress] ?: sender.ignoredOptOut

    fun keywordFor(sender: SpamSender): String =
        keywordOverrides[sender.normalizedAddress] ?: sender.keyword.keyword
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = SmsRepository(application)
    private val blockedNumbers = BlockedNumbers(application)
    private val senderMemory = SenderMemory(application)
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
            val optOut = repository.loadOptOutStatus(senderMemory.rememberedConfirmations())
            val senders = repository.loadUnreadSenders(fallback, optOut)
            _state.update { current ->
                // Drop selections and edits for senders that are no longer unread.
                val liveKeys = senders.map { it.normalizedAddress }.toSet()
                current.copy(
                    loading = false,
                    senders = senders,
                    selected = current.selected intersect liveKeys,
                    keywordOverrides = current.keywordOverrides.filterKeys { it in liveKeys },
                    keepMessages = current.keepMessages intersect liveKeys,
                    blockOverrides = current.blockOverrides.filterKeys { it in liveKeys },
                )
            }
        }
    }

    fun toggleSelection(sender: SpamSender) {
        _state.update { current ->
            val key = sender.normalizedAddress
            current.copy(
                selected = if (key in current.selected) current.selected - key else current.selected + key,
            )
        }
    }

    /**
     * Selects every sender in the list.
     *
     * It used to exclude senders already opted out of, from when selecting one meant texting it.
     * That is no longer true - such a sender is cleared, not re-texted - so excluding them just
     * meant the bulk action skipped exactly the threads the user most wanted swept up.
     *
     * Senders that never offered an opt-out are included too, but they are not silently texted
     * into: the Review screen still counts them and says that replying to a number which never
     * offered a way out confirms it is live rather than stopping it.
     */
    fun selectAll() {
        _state.update { current ->
            current.copy(selected = current.senders.map { it.normalizedAddress }.toSet())
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
            // Same rule as a batch delete: having deleted the thread, its reply is not wanted.
            senderMemory.markAutoDeleteResponses(sender.displayAddress)
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

    /** Flips a sender between "delete the messages" and "just mark them read". */
    fun setDeleteMessages(sender: SpamSender, delete: Boolean) {
        _state.update { current ->
            val key = sender.normalizedAddress
            current.copy(
                keepMessages = if (delete) current.keepMessages - key else current.keepMessages + key,
            )
        }
    }

    fun setBlockNumber(sender: SpamSender, block: Boolean) {
        _state.update { current ->
            current.copy(blockOverrides = current.blockOverrides + (sender.normalizedAddress to block))
        }
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
                // Already opted out of: the thread still gets cleaned up, but sending a second
                // opt-out would be noise - and to a sender that ignored the first, useless.
                sendReply = sender.canReply,
                delete = current.deletesMessages(sender),
                block = current.blocksNumber(sender),
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
