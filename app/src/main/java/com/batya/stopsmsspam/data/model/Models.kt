package com.batya.stopsmsspam.data.model

import com.batya.stopsmsspam.data.OptOutStatus

/** A single unread SMS as stored in the system Telephony provider. */
data class SpamMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val threadId: Long,
    val subscriptionId: Int,
)

/** How sure we are that [DetectedKeyword.keyword] is really what this sender wants back. */
enum class KeywordConfidence {
    /** The body spells out the instruction, e.g. "Reply STOP to unsubscribe". */
    EXPLICIT,

    /** Opt-out language is present and a keyword is nearby, but the phrasing is loose. */
    LIKELY,

    /** Nothing was found; this is the user's configured fallback. */
    ASSUMED,
}

data class DetectedKeyword(
    val keyword: String,
    val confidence: KeywordConfidence,
)

/**
 * All unread messages from one sender, collapsed into a single row so that a number that
 * texted eight times gets exactly one opt-out reply.
 */
data class SpamSender(
    /** Grouping key; see `PhoneAddress.normalize`. Not for display or sending. */
    val normalizedAddress: String,
    /** The address exactly as the provider stored it. This is what we reply to. */
    val displayAddress: String,
    val messageIds: List<Long>,
    val latestBody: String,
    val latestDate: Long,
    val subscriptionId: Int,
    val keyword: DetectedKeyword,
    /**
     * Set when the message history shows this sender was already sent an opt-out. Their later
     * messages - typically the "you have been unsubscribed" confirmation - still arrive, but
     * replying again would re-open a conversation that is already closed.
     */
    val optedOut: OptOutStatus? = null,
    /**
     * When this sender texted again *after* confirming the opt-out. Set only for a confirmed
     * opt-out: without the acknowledgement there is no promise to have broken, and calling that
     * a violation would put a scarlet letter on senders that were merely slow to answer.
     */
    val optOutViolatedAt: Long? = null,
) {
    val messageCount: Int get() = messageIds.size

    /** The sender acknowledged the opt-out: asked *and* answered. */
    val isUnsubscribed: Boolean get() = optedOut?.isConfirmed == true

    /** An opt-out went out, but this sender has not acknowledged it. */
    val awaitingConfirmation: Boolean get() = optedOut != null && !optedOut.isConfirmed

    /** Whether the UI should offer to send this sender an opt-out at all. */
    val canReply: Boolean get() = optedOut == null

    /**
     * The sender said it had unsubscribed the user and then messaged them anyway. Replying again
     * is pointless - the system already proved it ignores its own opt-out - so this is the one
     * state where the app offers to block the number instead.
     */
    val ignoredOptOut: Boolean get() = optOutViolatedAt != null

    /**
     * False when the sender never told us how to opt out. Replying to these is usually a bad
     * idea - it confirms to a scammer that the number is live - so the UI flags them.
     */
    val hasOptOutLanguage: Boolean get() = keyword.confidence != KeywordConfidence.ASSUMED
}

/** One queued reply: what to send, to whom, and which inbox rows it clears. */
data class ReplyPlan(
    val address: String,
    val keyword: String,
    val messageIds: List<Long>,
    val subscriptionId: Int,
)

enum class SendStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,

    /**
     * Handed to the system, but no result came back in time. Android shows a per-message
     * confirmation dialog for some short codes, and a batch can outrun the user answering it -
     * so this message may still go out. Distinct from [FAILED] because reporting "failed" for a
     * message that later sends is a lie the user would act on.
     */
    UNCONFIRMED,

    CANCELLED,
}

data class SendOutcome(
    val address: String,
    val keyword: String,
    val status: SendStatus,
    val detail: String? = null,
    val timestamp: Long = 0L,
)

data class BatchProgress(
    val running: Boolean = false,
    val dryRun: Boolean = false,
    val total: Int = 0,
    val currentAddress: String? = null,
    /** Wall-clock time the next send is scheduled for, so the UI can count down. */
    val nextSendAtMillis: Long? = null,
    val outcomes: List<SendOutcome> = emptyList(),
) {
    val completed: Int get() = outcomes.size
    val sentCount: Int get() = outcomes.count { it.status == SendStatus.SENT }
    val failedCount: Int get() = outcomes.count { it.status == SendStatus.FAILED }
    val unconfirmedCount: Int get() = outcomes.count { it.status == SendStatus.UNCONFIRMED }
    val finished: Boolean get() = !running && outcomes.isNotEmpty()
}
