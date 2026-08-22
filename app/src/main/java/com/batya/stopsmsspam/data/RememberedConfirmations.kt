package com.batya.stopsmsspam.data

/**
 * Folds confirmations the app had to remember back into what it derived from the provider.
 *
 * Kept pure and separate so the rule can be tested directly: a remembered confirmation only
 * counts if it postdates the opt-out it is supposed to answer. Without that check, a sender told
 * to stop a second time would look as though it had already acknowledged the second request.
 */
object RememberedConfirmations {

    fun applyTo(
        derived: Map<String, OptOutStatus>,
        remembered: Map<String, Long>,
    ): Map<String, OptOutStatus> {
        if (remembered.isEmpty()) return derived
        return derived.mapValues { (address, status) ->
            if (status.isConfirmed) return@mapValues status
            val at = remembered[address] ?: return@mapValues status
            if (at <= status.sentAtMillis) status else status.copy(confirmedAtMillis = at)
        }
    }
}
