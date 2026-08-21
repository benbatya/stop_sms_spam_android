package com.batya.stopsmsspam.data

import com.batya.stopsmsspam.data.model.DetectedKeyword
import com.batya.stopsmsspam.data.model.KeywordConfidence
import com.batya.stopsmsspam.data.model.SpamSender

/**
 * Pulls the opt-out keyword a sender asks for out of its own message body.
 *
 * Bulk senders are required to state the keyword, but they phrase it a dozen different ways
 * ("Reply STOP to end", "Txt QUIT to cancel", "STOP to opt out"), and not all of them use
 * STOP. Guessing wrong means the opt-out silently does nothing, so this reports its
 * confidence and the UI lets the user override every value before anything is sent.
 */
object OptOutKeywordDetector {

    /**
     * Keywords we accept even when the sender wrote them in lower case. Anything outside this
     * set has to appear in caps in the original text to be believed, which keeps ordinary
     * words like "to" or "me" from being mistaken for a keyword.
     */
    private val KNOWN_KEYWORDS = setOf(
        "STOP", "STOPALL", "END", "QUIT", "CANCEL", "UNSUBSCRIBE", "UNSUB",
        "OPTOUT", "REMOVE", "ARRET", "ALTO",
    )

    /** Words that follow the keyword in an opt-out instruction. */
    private const val STOP_INTENT =
        "(?:stop|end|quit|cancel|unsub\\w*|opt[\\s-]?out|remove|no more|be removed|stop receiving)"

    private const val KEYWORD_TOKEN = "([A-Za-z][A-Za-z0-9]{1,14})"

    private val EXPLICIT_PATTERNS = listOf(
        // "Reply STOP to unsubscribe" / "Text QUIT to cancel" / "send 'END' for no more"
        Regex(
            "\\b(?:reply|text|txt|send|respond)\\s+(?:with\\s+)?[\"'“]?$KEYWORD_TOKEN[\"'”]?\\s+(?:to|for)\\s+$STOP_INTENT",
            RegexOption.IGNORE_CASE,
        ),
        // "STOP to opt out" / "UNSUB to cancel"
        Regex("\\b$KEYWORD_TOKEN\\s+to\\s+$STOP_INTENT", RegexOption.IGNORE_CASE),
        // "Reply STOP" with no trailing clause
        Regex(
            "\\b(?:reply|text|txt|send|respond)\\s+(?:with\\s+)?[\"'“]?$KEYWORD_TOKEN[\"'”]?\\b",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** Opt-out language somewhere in the body with a keyword within a short distance of it. */
    private val LIKELY_PATTERN = Regex(
        "$STOP_INTENT[^.!?]{0,40}?\\b$KEYWORD_TOKEN\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Keywords distinctive enough that shouting one on its own is an opt-out instruction.
     * Deliberately narrower than [KNOWN_KEYWORDS]: a bare "END" or "CANCEL" in caps is just as
     * likely to be marketing copy ("SALE ENDS TONIGHT"), and a false positive here would wrongly
     * tell the user that a sender offers an opt-out.
     */
    private val UNAMBIGUOUS_KEYWORDS = listOf(
        "STOPALL", "STOP", "UNSUBSCRIBE", "UNSUB", "OPTOUT", "ARRET", "ALTO",
    )

    /**
     * A compound like "STOP2STOP" or "End2End" tacked onto the end of a message - texting
     * shorthand where "2" stands for "to". Senders append these as a sign-off rather than
     * spelling out an instruction.
     *
     * Anchored to the *end* of the body on purpose. Mid-sentence, "End2End" is ordinary English
     * ("our End2End encrypted chat is live"), and a false positive there does more than mislabel
     * a row: it flips [SpamSender.hasOptOutLanguage] to true, which is what "Select all with
     * opt-out" relies on to keep the user from replying to outright scam numbers. A trailing
     * token is a sign-off; the same token inside a sentence is prose.
     */
    private val TRAILING_COMPOUND = Regex("""\b([A-Za-z]{2,15})2([A-Za-z]{2,15})[\s.!?]*$""")

    /** A bare shouted keyword, which is how many senders abbreviate the instruction. */
    private val BARE_PATTERN =
        Regex("\\b(" + UNAMBIGUOUS_KEYWORDS.joinToString("|") + ")\\b")

    /**
     * @return the keyword this sender asks for, or null when the body contains no opt-out
     * instruction at all - in which case the caller should fall back and warn.
     */
    fun detect(body: String): DetectedKeyword? {
        if (body.isBlank()) return null

        for (pattern in EXPLICIT_PATTERNS) {
            val candidate = pattern.findAll(body)
                .mapNotNull { acceptKeyword(it.groupValues[1]) }
                .firstOrNull()
            if (candidate != null) {
                return DetectedKeyword(candidate, KeywordConfidence.EXPLICIT)
            }
        }

        TRAILING_COMPOUND.find(body.trim())
            ?.groupValues?.get(1)
            ?.uppercase()
            ?.takeIf { it in KNOWN_KEYWORDS }
            ?.let { return DetectedKeyword(it, KeywordConfidence.LIKELY) }

        LIKELY_PATTERN.findAll(body)
            .mapNotNull { acceptKeyword(it.groupValues[1]) }
            .firstOrNull()
            ?.let { return DetectedKeyword(it, KeywordConfidence.LIKELY) }

        BARE_PATTERN.find(body)
            ?.let { return DetectedKeyword(it.groupValues[1].uppercase(), KeywordConfidence.LIKELY) }

        return null
    }

    /**
     * Whether an outgoing message body *is* an opt-out reply, rather than ordinary conversation.
     *
     * Used to find opt-outs in the Sent box, which is how the app knows a sender has already
     * been told to stop - including ones sent from a different messaging app. An opt-out reply
     * is the bare keyword and nothing else, so requiring a single token keeps a real message
     * that merely mentions one ("did the STOP work?") from counting.
     */
    fun isOptOutReply(body: String): Boolean {
        val token = body.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return false
        if (!token.all { it.isLetterOrDigit() }) return false
        if (token.length !in 2..15) return false
        val upper = token.uppercase()
        // Either a keyword we know, or shouted - the convention these are written in.
        return upper in KNOWN_KEYWORDS || TRAILING_COMPOUND.matches(token) || token == upper
    }

    /** Same as [detect] but substitutes [fallback] instead of returning null. */
    fun detectOrFallback(body: String, fallback: String): DetectedKeyword =
        detect(body) ?: DetectedKeyword(fallback.uppercase(), KeywordConfidence.ASSUMED)

    /**
     * A captured token counts as a keyword if we recognise it, or if the sender shouted it -
     * caps are how these instructions are conventionally written, and requiring them stops
     * "reply now to cancel" from yielding the keyword "NOW".
     */
    private fun acceptKeyword(raw: String): String? {
        val upper = raw.uppercase()
        return when {
            upper in KNOWN_KEYWORDS -> upper
            raw.length >= 3 && raw == upper -> upper
            else -> null
        }
    }
}
