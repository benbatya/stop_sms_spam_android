package com.batya.stopsmsspam.data

/**
 * Grouping keys for SMS originating addresses.
 *
 * The provider is inconsistent about how it stores the same sender - "+1 555-123-4567",
 * "15551234567" and "5551234567" are all the same number - so messages are grouped by a
 * normalized key rather than by the raw string. Short codes and alphanumeric sender IDs are
 * left alone, since truncating those would merge unrelated senders.
 */
object PhoneAddress {

    /** Below this length an all-digit address is a short code, not a phone number. */
    private const val SHORT_CODE_MAX_DIGITS = 8

    /** Number of trailing digits that identify a subscriber line. */
    private const val SUBSCRIBER_DIGITS = 10

    /**
     * True for short codes, which Android treats differently on the way out: some of them raise
     * a per-message "this may cause charges" confirmation dialog that the user has to answer.
     */
    fun isShortCode(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isLetter() }) return false
        val digits = trimmed.filter { it.isDigit() }
        return digits.isNotEmpty() && digits.length <= SHORT_CODE_MAX_DIGITS
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // Alphanumeric sender IDs ("VERIZON", "AMZN") are used verbatim.
        if (trimmed.any { it.isLetter() }) return trimmed.uppercase()

        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return trimmed

        // Short codes are already canonical, and their leading digits are significant.
        if (digits.length <= SHORT_CODE_MAX_DIGITS) return digits

        // For real numbers, compare on the trailing subscriber digits so that country code and
        // trunk prefix variations of one number collapse together.
        return digits.takeLast(SUBSCRIBER_DIGITS)
    }
}
