package com.batya.stopsmsspam.data

import com.batya.stopsmsspam.data.model.SpamSender

/**
 * Drops senders by address - the one place a sender is removed from the list.
 *
 * Free of Android types so the rules that decide which senders are *eligible for deletion* can be
 * tested directly. Two callers with different reasons share it: contacts, which must never be
 * touched, and already-blocked numbers, which are merely noise. The mechanics are identical; only
 * the source of the address set and whether the user can turn it off differ.
 */
object SenderExclusion {

    /**
     * @param addresses raw addresses to exclude, as passed to whatever looked them up. Compared
     *  on the normalized form, because a sender's `displayAddress` is whichever formatting the
     *  provider happened to store and need not match character for character.
     */
    fun exclude(senders: List<SpamSender>, addresses: Set<String>): List<SpamSender> {
        val keys = keysOf(addresses)
        if (keys.isEmpty()) return senders
        return senders.filterNot { it.normalizedAddress in keys }
    }

    /** The complement of [exclude] - what it would remove, for a UI that reports the count. */
    fun matching(senders: List<SpamSender>, addresses: Set<String>): List<SpamSender> {
        val keys = keysOf(addresses)
        if (keys.isEmpty()) return emptyList()
        return senders.filter { it.normalizedAddress in keys }
    }

    private fun keysOf(addresses: Set<String>): Set<String> =
        addresses.map { PhoneAddress.normalize(it) }.filterNot { it.isEmpty() }.toSet()
}
