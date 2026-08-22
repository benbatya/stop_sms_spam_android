package com.batya.stopsmsspam.data

/**
 * What the message history says about opting out from one sender.
 *
 * Derived from the Telephony provider on every load rather than recorded separately. The
 * provider is the app's only message store, and a parallel record would be a second version of
 * the same truth - one that drifts, misses opt-outs the user sent from their normal messaging
 * app, and is lost on reinstall while the messages themselves are not.
 *
 * The two timestamps answer different questions. [sentAtMillis] means *we asked*; only
 * [confirmedAtMillis] means *they answered*. A sender that never replies stays in the first
 * state, which is worth showing rather than papering over.
 */
data class OptOutStatus(
    val keyword: String,
    val sentAtMillis: Long,
    val confirmedAtMillis: Long? = null,
) {
    val isConfirmed: Boolean get() = confirmedAtMillis != null
}
