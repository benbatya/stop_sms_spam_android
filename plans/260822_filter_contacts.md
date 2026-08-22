# Keep senders in the contacts list out of harm's way

## What was asked for

> filter out known senders from my contacts. I don't want to risk deleting those messages

and, during the work:

> if a sender doesn't have an opt out, do not allow the "Select All" button to include it in the
> bulk selection. Instead, the user can manually select the sender

The stated motivation is the important half of the first, and the two turn out to be the same
change: **"Select all" must not sweep up anything the user would not have picked deliberately.**
The batch deletes threads and can block numbers, the default disposition is *delete*, and there
is no undo - the app never copies a thread before removing it.

## Contacts are dropped outright

Asked, because "filter out" and "don't risk deleting" admit both readings. The answer was the
strongest one: contacts are excluded from the query results entirely - no row, no count, no
toggle to reveal them. The app cannot tell you a contact texted, and in exchange nothing it does
can ever touch them.

`ContactsContract.PhoneLookup.CONTENT_FILTER_URI` does the matching, not a string comparison. A
contact saved as `(802) 216-0869` has to match a message from `+18022160869`, and every attempt
to reimplement that - strip punctuation, compare suffixes - is a chance to get it wrong in the
one direction that deletes somebody's messages. `PhoneLookup` matches loosely by design, which
can produce a false positive; that is the acceptable direction, since the cost is a spam row
that stays in the list.

One lookup per *distinct sender*, after grouping rather than per message: a thousand unread from
a few hundred senders would otherwise be a thousand round trips.

## A filter that cannot run says so

`READ_CONTACTS` is a new runtime permission and the user can decline it. The lookup then returns
an empty set, which is indistinguishable from "none of these are contacts" - so on its own the
filter would **fail open silently**, and the user would keep trusting a guarantee that had
stopped holding.

So the empty set is kept meaning "filtered nobody", and the inbox carries a red banner saying
contacts access is off, anyone in the list could be someone you know, and the batch deletes -
with a button to grant it. The app still works without the permission; it just stops promising
something it cannot deliver.

## "Select all" skips senders that offered no opt-out

`includedInSelectAll` is false for exactly one case: a sender that would be *texted* despite
never having offered an opt-out. Replying there has a real downside - it confirms the number is
live to someone who never asked for a keyword and probably will not honour one - so it should
take a deliberate tap.

The qualifier matters. A sender that has **already** been opted out is still included even with
no opt-out language, because the batch only clears its thread; nothing is sent, so there is
nothing to be careful about. Without that, this would have quietly re-broken bulk clearing,
which was itself a correction made earlier ("All messages should be selected").

A line above the list says how many were skipped and why, since a "Select all" that visibly
does not select everything otherwise reads as a bug.

## Verified

73 unit tests, seven new: four on the contacts rule (drops a contact, matches across formatting,
keeps everyone on an empty result, and an unusable address does not drop everyone) and three on
the selection rule (offered / never offered / already opted out). `lintDebug` and
`assembleDebug` clean.

End to end on the Android 12 emulator, with a contact inserted for `44551`:

1. Unread messages from `44551` (a contact, with opt-out language), `18885551234` (no opt-out)
   and `52525` (already unsubscribed).
2. The list shows **two** senders - `44551` is absent, while `content://sms/inbox` confirms its
   row is present and `read=0`. Nothing but the filter could account for that.
3. "Select all" ticks `52525` and leaves `18885551234` unticked; the hint reads "skips 1 sender".
4. Revoking `READ_CONTACTS` and reopening: the red banner appears and `44551` **returns** to the
   list - the filter's absence is visible rather than silent.

The contact it was tested against was inserted through the provider by hand, so what is proven
is the *rule*, not `PhoneLookup` against a real contacts database with its accounts, duplicates
and merged entries. Installed to the physical device for that, where `READ_CONTACTS` sits
ungranted until the user accepts the prompt - deliberately not granted over adb, since it is
their contacts to hand over.

## Out of scope

- Any change to what "unread" means, or to the SMS/MMS queries themselves.
- Contact-based allow-listing of spam (a saved short code stays hidden, by design).
- Warning at send time. The guard is at selection; nothing reaches the batch to warn about.
