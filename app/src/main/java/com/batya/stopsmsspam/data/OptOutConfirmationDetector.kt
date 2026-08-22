package com.batya.stopsmsspam.data

/**
 * Recognises a sender's own acknowledgement that an opt-out took effect.
 *
 * The distinction this rests on is tense. Solicitations invite the action - "reply STOP to
 * **unsubscribe**", "text QUIT to be removed" - while acknowledgements report it as done: "you
 * have been **unsubscribed**". Matching the past tense is what keeps the original spam from
 * reading as its own confirmation.
 *
 * Callers additionally require the message to have arrived *after* the opt-out was sent, so a
 * sender who happens to use the word in a solicitation still cannot confirm itself.
 */
object OptOutConfirmationDetector {

    /**
     * Past-tense acknowledgements. Deliberately narrow: a false positive here tells the user a
     * sender is done with them when it is not, which is worse than leaving a real confirmation
     * unrecognised - that case merely shows "awaiting confirmation" until the sender is silent.
     */
    private val CONFIRMATIONS = listOf(
        Regex("\\bunsubscribed\\b", RegexOption.IGNORE_CASE),
        Regex("\\bhave been removed\\b", RegexOption.IGNORE_CASE),
        Regex("\\bopted[\\s-]?out\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno longer receive\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwill (?:no longer|not) receive\\b", RegexOption.IGNORE_CASE),
    )

    fun isConfirmation(body: String): Boolean =
        body.isNotBlank() && CONFIRMATIONS.any { it.containsMatchIn(body) }
}
