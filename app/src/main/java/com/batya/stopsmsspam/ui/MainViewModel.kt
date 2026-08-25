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
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.SenderMemory
import com.batya.stopsmsspam.data.SmsRepository
import com.batya.stopsmsspam.data.model.ClearReason
import com.batya.stopsmsspam.data.model.ReplyPlan
import com.batya.stopsmsspam.data.model.SpamSender
import com.batya.stopsmsspam.role.SmsRoleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { Setup, Inbox, Review, Progress }

/**
 * Why the messaging app is already done with a sender, and so why this app is hiding it.
 *
 * Kept as two values rather than one boolean because they mean different things to the user:
 * blocked is the system refusing the sender's messages, archived is only "filed away", and an
 * archived thread is far more likely to be something they still want to look at.
 */
enum class HandledReason { BLOCKED, ARCHIVED }

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
    /**
     * Senders the user chose on the Review screen to just delete rather than text, by normalized
     * address. Only meaningful for a sender that *could* be replied to - one already opted out of
     * was never going to be texted, so suppressing its reply would say nothing.
     *
     * Held as the exceptions, like [keepMessages]: replying is what selecting a sender means, and
     * this records where the user decided otherwise.
     */
    val replySuppressed: Set<String> = emptySet(),
    val settings: AppSettings = AppSettings(),
    /**
     * Senders that have acknowledged an opt-out, by normalized address. Kept beside the sender
     * list rather than derived from it, because the progress screen needs it for senders whose
     * threads are already gone.
     */
    val confirmedAddresses: Set<String> = emptySet(),
    /**
     * How many senders `senders` is currently hiding as already dealt with.
     *
     * Only a count, deliberately: the hidden senders are held in the view model, not here, so
     * nothing in the UI can reach past the filter by accident. Every existing use of [senders] -
     * "Select all", the unread total, the batch - is then correct without being audited.
     */
    val hiddenHandledCount: Int = 0,
    /**
     * Which of [senders] the messaging app had already dealt with, by normalized address, and
     * how. Only ever non-empty when the hide toggle is off - if they were being hidden they would
     * not be in [senders] to mark. A marker map, not a second sender list: it says something
     * *about* the visible senders rather than offering a way round the filter.
     */
    val handledAddresses: Map<String, HandledReason> = emptyMap(),
    /** Address whose block attempt failed, so the UI can say so rather than silently no-op. */
    val lastBlockFailed: String? = null,
    /**
     * False when READ_CONTACTS was declined, meaning senders in the user's contacts could not be
     * filtered out and may be sitting in the list. Surfaced rather than assumed: a filter that
     * fails open without saying so is worse than no filter, because the user stops checking.
     */
    val contactFilterActive: Boolean = false,
) {
    val ready: Boolean get() = isDefaultSmsApp && hasSmsPermissions
    val selectedSenders: List<SpamSender> get() = senders.filter { it.normalizedAddress in selected }

    /**
     * Whether an opt-out will actually be texted to this sender: the app has something to send,
     * and the user has not said to just delete the thread instead.
     */
    fun sendsReply(sender: SpamSender): Boolean =
        sender.canReply && sender.normalizedAddress !in replySuppressed

    /** Selected senders that will actually be texted - what the confirmation dialog counts. */
    val selectedForReply: List<SpamSender> get() = selectedSenders.filter { sendsReply(it) }

    /** Selected senders that will only have their threads cleared. */
    val selectedForCleanup: List<SpamSender> get() = selectedSenders.filterNot { sendsReply(it) }

    /**
     * Cleared because there is nobody left to write to - an opt-out already went out.
     *
     * Kept apart from [selectedDeleteOnly] wherever the two are shown: one is the app reporting
     * a fact about the sender, the other is the user's own decision, and a list that merges them
     * would explain a choice they made as something the sender did.
     */
    val selectedAlreadyOptedOut: List<SpamSender> get() = selectedSenders.filterNot { it.canReply }

    /** Cleared because the user said so on Review, despite a reply being possible. */
    val selectedDeleteOnly: List<SpamSender>
        get() = selectedSenders.filter { it.canReply && it.normalizedAddress in replySuppressed }

    /** Whether the sender's messages will be deleted; false means only marked read. */
    fun deletesMessages(sender: SpamSender): Boolean =
        sender.normalizedAddress !in keepMessages

    /**
     * Whether the number will be blocked. Defaults on for a sender that acknowledged an opt-out
     * and messaged anyway - asking it politely has already been tried and demonstrably failed.
     */
    fun blocksNumber(sender: SpamSender): Boolean =
        blockOverrides[sender.normalizedAddress] ?: sender.ignoredOptOut

    /** What the keyword field shows - the user's own text, verbatim, including empty. */
    fun keywordFor(sender: SpamSender): String =
        keywordOverrides[sender.normalizedAddress] ?: sender.keyword.keyword

    /**
     * What actually gets texted.
     *
     * An emptied field falls back to the detected keyword rather than sending a blank message,
     * which is what clearing the box would otherwise queue up. Deliberately not folded into
     * [keywordFor]: the field has to let the user delete what is in it before typing something
     * else, and a value that refuses to go empty cannot be retyped.
     *
     * The Review screen states this resolved value, so what it says will be sent is what is.
     */
    fun outgoingKeyword(sender: SpamSender): String =
        keywordFor(sender).trim().ifEmpty { sender.keyword.keyword }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = SmsRepository(application)
    private val blockedNumbers = BlockedNumbers(application)
    private val senderMemory = SenderMemory(application)
    private val roleManager = SmsRoleManager(application)

    /**
     * Every sender the last load produced, before the blocked-sender toggle is applied. Kept off
     * [UiState] so the only sender list the UI can see is the filtered one.
     */
    private var loadedSenders: List<SpamSender> = emptyList()
    private var blockedAddresses: Set<String> = emptySet()
    private var archivedThreadIds: Set<Long> = emptySet()

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
            val confirmed = optOut.filterValues { it.isConfirmed }.keys
            loadedSenders = repository.loadUnreadSenders(fallback, optOut)
            blockedAddresses = blockedNumbers.blockedAmong(loadedSenders.map { it.displayAddress })
            archivedThreadIds = repository.archivedThreadIds()
            val contactFilterActive = repository.contactFilterActive()
            _state.update { current ->
                current.copy(loading = false, contactFilterActive = contactFilterActive)
            }
            applySenderVisibility(confirmedAddresses = confirmed)
        }
    }

    /** Turns the "hide senders already dealt with" filter on or off, without re-reading the inbox. */
    fun setHideHandledSenders(hide: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(hideHandledSenders = hide) }
            applySenderVisibility()
        }
    }

    /**
     * Why a sender counts as already dealt with.
     *
     * Blocked is checked first because it is the stronger statement - the system is refusing the
     * sender's messages outright, where an archived thread only means the user filed it away.
     */
    private fun handledReason(sender: SpamSender): HandledReason? = when {
        sender.normalizedAddress in blockedAddressKeys() -> HandledReason.BLOCKED
        sender.threadIds.isNotEmpty() && sender.threadIds.all { it in archivedThreadIds } ->
            HandledReason.ARCHIVED
        else -> null
    }

    private fun blockedAddressKeys(): Set<String> =
        blockedAddresses.map { PhoneAddress.normalize(it) }.filterNot { it.isEmpty() }.toSet()

    /**
     * Republishes [loadedSenders] through the blocked-sender toggle.
     *
     * Selections and per-sender edits are pruned to what is *visible*, not merely to what was
     * loaded: hiding a sender that was already ticked would otherwise leave it selected and in
     * the batch, which is the one way a filter the user can toggle could still text somebody they
     * thought they had put away.
     */
    private suspend fun applySenderVisibility(confirmedAddresses: Set<String>? = null) {
        val hide = settingsStore.current().hideHandledSenders
        val handled = loadedSenders.mapNotNull { s -> handledReason(s)?.let { s.normalizedAddress to it } }.toMap()
        val visible = if (hide) loadedSenders.filterNot { it.normalizedAddress in handled } else loadedSenders
        val hiddenCount = loadedSenders.size - visible.size
        val handledVisible = handled.filterKeys { key -> visible.any { it.normalizedAddress == key } }
        _state.update { current ->
            val liveKeys = visible.map { it.normalizedAddress }.toSet()
            current.copy(
                senders = visible,
                hiddenHandledCount = hiddenCount,
                handledAddresses = handledVisible,
                confirmedAddresses = confirmedAddresses ?: current.confirmedAddresses,
                selected = current.selected intersect liveKeys,
                keywordOverrides = current.keywordOverrides.filterKeys { it in liveKeys },
                keepMessages = current.keepMessages intersect liveKeys,
                blockOverrides = current.blockOverrides.filterKeys { it in liveKeys },
                replySuppressed = current.replySuppressed intersect liveKeys,
            )
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
            val keys = current.senders
                .filter { it.includedInSelectAll }
                .map { it.normalizedAddress }
                .toSet()
            current.copy(
                selected = keys,
                // A bulk action should land on the defaults, not on whatever was left over from
                // the last time these rows were ticked: everything it selects replies and
                // deletes. Only the keys it touches are reset, so a sender it deliberately
                // skipped keeps whatever the user had already set on it by hand.
                replySuppressed = current.replySuppressed - keys,
                keepMessages = current.keepMessages - keys,
            )
        }
    }

    /** Clears an already-unsubscribed sender's messages from the unread list. */
    fun markRead(sender: SpamSender) {
        viewModelScope.launch {
            repository.markRead(sender.messages)
            refreshInbox()
        }
    }

    /** Deletes an already-unsubscribed sender's messages outright. */
    fun deleteMessages(sender: SpamSender) {
        viewModelScope.launch {
            repository.delete(sender.messages)
            // Same rule as a batch delete: having deleted the thread, its reply is not wanted.
            senderMemory.markAutoDeleteResponses(sender.displayAddress)
            refreshInbox()
        }
    }

    fun markAllUnsubscribedRead() {
        viewModelScope.launch {
            val refs = _state.value.senders.filter { it.isUnsubscribed }.flatMap { it.messages }
            if (refs.isNotEmpty()) repository.markRead(refs)
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
            if (blocked) repository.markRead(sender.messages)
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

    /**
     * Chooses between texting a sender an opt-out and simply clearing its thread.
     *
     * Suppressing the reply also turns deleting back on. The control that calls this says
     * "delete instead", so a sender still marked "keep" from the inbox would otherwise be
     * neither replied to nor deleted - the batch would touch it only to mark it read, which is
     * not what the wording promised. The Review summary shows the resulting counts on the same
     * screen, so the change is visible rather than silent.
     */
    fun setSendReply(sender: SpamSender, send: Boolean) {
        _state.update { current ->
            val key = sender.normalizedAddress
            current.copy(
                replySuppressed =
                    if (send) current.replySuppressed - key else current.replySuppressed + key,
                keepMessages = if (send) current.keepMessages else current.keepMessages - key,
            )
        }
    }

    /**
     * Switches every selected sender that never offered an opt-out over to delete-only.
     *
     * These are exactly the senders the Review screen warns about: a reply probably will not
     * stop them and does confirm the number is live. The warning already told the user to
     * reconsider them, so it may as well carry the action.
     */
    fun deleteInsteadOfReplyingToSendersWithoutOptOut() {
        _state.update { current ->
            val keys = current.selectedForReply
                .filterNot { it.hasOptOutLanguage }
                .map { it.normalizedAddress }
            current.copy(
                replySuppressed = current.replySuppressed + keys,
                keepMessages = current.keepMessages - keys.toSet(),
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

    /**
     * Re-reads which senders have confirmed, without disturbing the batch on screen.
     *
     * A confirmation arrives after its send, often while the user is still watching the progress
     * list, and nothing else on that screen would prompt a reload.
     */
    fun refreshConfirmations() {
        viewModelScope.launch {
            val optOut = repository.loadOptOutStatus(senderMemory.rememberedConfirmations())
            _state.update { it.copy(confirmedAddresses = optOut.filterValues { s -> s.isConfirmed }.keys) }
        }
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
                keyword = current.outgoingKeyword(sender),
                messages = sender.messages,
                subscriptionId = sender.subscriptionId,
                // No reply goes out when one already did - a second opt-out would be noise, and
                // to a sender that ignored the first, useless - or when the user chose on this
                // screen to just delete the thread.
                sendReply = current.sendsReply(sender),
                clearReason = when {
                    current.sendsReply(sender) -> null
                    sender.canReply -> ClearReason.CHOSEN
                    else -> ClearReason.ALREADY_OPTED_OUT
                },
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
        _state.update {
            it.copy(selected = emptySet(), keywordOverrides = emptyMap(), replySuppressed = emptySet())
        }
        refreshInbox()
        goTo(Screen.Inbox)
    }
}
