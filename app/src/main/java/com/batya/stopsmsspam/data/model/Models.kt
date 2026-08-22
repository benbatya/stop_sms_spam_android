package com.batya.stopsmsspam.data.model

import com.batya.stopsmsspam.data.OptOutStatus

/**
 * Which provider table a message lives in. They are separate stores with separate ids, so an id
 * alone cannot be marked read or deleted - it has to say where it came from.
 */
enum class MessageSource { SMS, MMS }

/** Points at one message, unambiguously, across both tables. */
data class MessageRef(val id: Long, val source: MessageSource)

/** A single unread message as stored in the system Telephony provider. */
data class SpamMessage(
    val id: Long,
    val address: String,
    val body: String,
    /** Milliseconds. MMS stores seconds, so that reader multiplies before constructing this. */
    val date: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val source: MessageSource = MessageSource.SMS,
) {
    val ref: MessageRef get() = MessageRef(id, source)
}

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
    val messages: List<MessageRef>,
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
    val messageCount: Int get() = messages.size

    val mmsCount: Int get() = messages.count { it.source == MessageSource.MMS }

    /** True when any of this sender's messages arrived as MMS. */
    val hasMms: Boolean get() = mmsCount > 0

    /** True when every one of them did, which is the common case for a picture-message blast. */
    val isAllMms: Boolean get() = hasMms && mmsCount == messages.size

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
     * False when the sender's message contained no opt-out instruction at all.
     *
     * This is **not** scam detection, and nothing in the app is: it is the absence of one
     * signal, which makes it a proxy for "a reply probably will not help here". It cuts both
     * ways - a legitimate sender that forgot the opt-out line is flagged, and a scammer that
     * writes "Reply STOP to opt out" is not. What it does support is the warning that replying
     * to such a sender tells them the number is live while likely stopping nothing.
     */
    val hasOptOutLanguage: Boolean get() = keyword.confidence != KeywordConfidence.ASSUMED

    /**
     * Whether "Select all" may include this sender.
     *
     * It skips exactly one case: a sender that would be *texted* despite never having offered an
     * opt-out. Replying there is the move with a real downside - it confirms the number is live
     * to someone who never asked for a keyword and probably will not honour one - so it should
     * be a sender the user picked deliberately, not one swept up in a bulk action.
     *
     * A sender already opted out is still included even with no opt-out language, because the
     * batch only clears its thread; nothing gets sent, so there is nothing to be careful about.
     */
    val includedInSelectAll: Boolean get() = !canReply || hasOptOutLanguage
}

/** One queued reply: what to send, to whom, and which inbox rows it clears. */
data class ReplyPlan(
    val address: String,
    val keyword: String,
    val messages: List<MessageRef>,
    val subscriptionId: Int,
    /**
     * False for a sender already opted out of: the thread is still cleaned up, but no message is
     * sent. Carried on the plan rather than re-derived in the service so that what the batch
     * will do is fixed at the moment the user confirms it, not recomputed mid-run.
     */
    val sendReply: Boolean = true,
    /**
     * Delete the sender's messages once handled, rather than only marking them read. The default
     * for everything - the point of the app is to be rid of these - with a per-sender toggle to
     * keep a thread that is worth keeping.
     */
    val delete: Boolean = true,
    /**
     * Block the number outright. Defaults on only for a sender that acknowledged an opt-out and
     * then messaged anyway: it has already demonstrated that asking does not work.
     */
    val block: Boolean = false,
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

    /**
     * Cleaned up without texting anyone. A sender that was already opted out of still gets
     * selected and dealt with, but sending it a second STOP would be noise at best - so the
     * batch marks its thread read (or deletes it) and moves on.
     */
    CLEARED,
}

data class SendOutcome(
    val address: String,
    val keyword: String,
    val status: SendStatus,
    val detail: String? = null,
    val timestamp: Long = 0L,
    val blocked: Boolean = false,
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
    val clearedCount: Int get() = outcomes.count { it.status == SendStatus.CLEARED }
    val blockedCount: Int get() = outcomes.count { it.blocked }
    val finished: Boolean get() = !running && outcomes.isNotEmpty()
}
