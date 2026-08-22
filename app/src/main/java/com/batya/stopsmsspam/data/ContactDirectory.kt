package com.batya.stopsmsspam.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Answers whether a number belongs to somebody in the user's contacts.
 *
 * This exists to keep real people out of a batch that deletes threads and blocks numbers. It is
 * a safety check, so where it is uncertain it errs towards *is a contact* - a spam sender that
 * survives one run costs the user another tap, while a person's thread deleted by "Select all"
 * is gone with no undo.
 */
class ContactDirectory(private val context: Context) {

    fun canReadContacts(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Looks up one address, using the platform's own number matching.
     *
     * `PhoneLookup` is deliberately not a string comparison: a contact saved as
     * `(802) 216-0869` has to match a message from `+18022160869`, and every attempt to
     * reimplement that - strip punctuation, compare suffixes - is a chance to get it wrong in
     * the direction that deletes somebody's messages.
     *
     * Its matching is loose by design (it compares trailing digits), so a false positive is
     * possible. That is the acceptable direction: the cost is a spam row that stays in the list.
     */
    private fun lookup(address: String): Boolean {
        if (address.isBlank()) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /**
     * The subset of [addresses] that belong to a contact.
     *
     * Returns nothing without the permission - the caller decides what an unanswerable question
     * means, rather than this silently reporting "none of them are contacts", which reads
     * identically to a successful lookup and would make the filter fail open.
     *
     * One query per *distinct* address, not per message: an inbox of a thousand unread from a
     * few hundred senders would otherwise do a thousand round trips.
     */
    fun contactsAmong(addresses: Collection<String>): Set<String> {
        if (!canReadContacts()) return emptySet()
        return addresses.distinct().filter { lookup(it) }.toSet()
    }
}
