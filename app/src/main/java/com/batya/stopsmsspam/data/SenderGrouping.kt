package com.batya.stopsmsspam.data

import com.batya.stopsmsspam.data.model.MessageRef
import com.batya.stopsmsspam.data.model.SpamMessage
import com.batya.stopsmsspam.data.model.SpamSender

/**
 * Collapses unread messages into one row per sender.
 *
 * Kept separate from [SmsRepository] and free of Android types so the grouping rules - which
 * decide how many texts actually get sent - can be tested directly rather than through a
 * content provider.
 */
object SenderGrouping {

    /**
     * @param optedOut senders already sent a confirmed opt-out, keyed by normalized address.
     * @param messages unread messages, newest first. The first message seen for a sender is the
     *  one quoted in the UI and the one its keyword is detected from.
     */
    fun group(
        messages: List<SpamMessage>,
        fallbackKeyword: String,
        optedOut: Map<String, OptOutStatus> = emptyMap(),
    ): List<SpamSender> {
        val builders = LinkedHashMap<String, Builder>()
        // Latest offending message per sender: what makes the violation visible and recent.
        val violations = HashMap<String, Long>()

        for (message in messages) {
            val key = PhoneAddress.normalize(message.address)
            if (key.isEmpty()) continue

            val status = optedOut[key]
            if (status?.confirmedAtMillis != null &&
                message.date > status.confirmedAtMillis &&
                !OptOutConfirmationDetector.isConfirmation(message.body)
            ) {
                violations[key] = maxOf(violations[key] ?: 0L, message.date)
            }

            val builder = builders.getOrPut(key) {
                Builder(
                    normalizedAddress = key,
                    displayAddress = message.address,
                    latestBody = message.body,
                    latestDate = message.date,
                    subscriptionId = message.subscriptionId,
                )
            }
            builder.messages += message.ref
            builder.threadIds += message.threadId
        }

        return builders.values.map { builder ->
            SpamSender(
                normalizedAddress = builder.normalizedAddress,
                displayAddress = builder.displayAddress,
                messages = builder.messages.toList(),
                threadIds = builder.threadIds.toSet(),
                latestBody = builder.latestBody,
                latestDate = builder.latestDate,
                subscriptionId = builder.subscriptionId,
                keyword = OptOutKeywordDetector.detectOrFallback(builder.latestBody, fallbackKeyword),
                optedOut = optedOut[builder.normalizedAddress],
                optOutViolatedAt = violations[builder.normalizedAddress],
            )
        }
    }

    private class Builder(
        val normalizedAddress: String,
        val displayAddress: String,
        val latestBody: String,
        val latestDate: Long,
        val subscriptionId: Int,
        val messages: MutableList<MessageRef> = mutableListOf(),
        val threadIds: MutableSet<Long> = mutableSetOf(),
    )
}
