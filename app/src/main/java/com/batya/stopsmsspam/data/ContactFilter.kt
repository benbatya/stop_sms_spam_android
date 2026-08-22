package com.batya.stopsmsspam.data

import com.batya.stopsmsspam.data.model.SpamSender

/**
 * Drops senders who are in the user's contacts.
 *
 * Free of Android types so the rule that decides whose messages are *eligible for deletion* can
 * be tested directly. That is the reason it is a separate object rather than a line inside the
 * repository: everything downstream - "Select all", the batch, delete, block - trusts this list
 * to contain nobody the user knows.
 */
object ContactFilter {

    /**
     * @param contactAddresses raw addresses found in contacts, as passed to the lookup.
     *  Compared on the normalized form, because a sender's `displayAddress` is whichever
     *  formatting the provider happened to store and need not match character for character.
     */
    fun exclude(senders: List<SpamSender>, contactAddresses: Set<String>): List<SpamSender> {
        if (contactAddresses.isEmpty()) return senders
        val keys = contactAddresses.map { PhoneAddress.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return senders
        return senders.filterNot { it.normalizedAddress in keys }
    }
}
