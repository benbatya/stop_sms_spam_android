# Track unsubscribed senders, and stop offering to re-reply

## The problem, from manual testing on a real phone

Opting out is not the end of the conversation. A sender that honours STOP usually **replies to
confirm it** — "You have been unsubscribed from ACME alerts" — and that confirmation arrives as a
new unread SMS from the same address.

The app had no memory of having opted out, so it listed that sender again as fresh spam, with a
checkbox and a suggested keyword, inviting the user to text them a second time. Marking the
original messages read did not help: the confirmation is a *different, newer* message.

At best that is noise. At worst it re-opens a conversation the user just closed.

## What changed

**`OptOutLog`** — a DataStore record of senders this app has sent a confirmed opt-out to, keyed by
the same normalized address the grouping uses, holding the keyword and the timestamp.

**Only confirmed sends are recorded.** A dry run does not write to it, and neither does an
`UNCONFIRMED` send. If Android never told us the message went out, calling the sender
unsubscribed would be a guess — and the wrong direction to guess in, since it would suppress a
retry the user may still need.

**Unsubscribed senders are not repliable.** `SpamSender.canReply` is false for them:
`selectAllWithOptOut` skips them, `toggleSelection` refuses them, and a stale selection is
dropped on refresh. In the list they lose their checkbox entirely rather than merely being
discouraged — there is nothing to send, so offering the choice would be the invitation this
change exists to remove.

**They get the two actions that do make sense:** *Mark read* and *Delete*, per sender, plus a
banner with *Mark all as read* when any exist, since the confirmations arrive in bulk after a
batch. The badge says what was sent and when — "Unsubscribed — sent "STOP" on Aug 21, 2026" — so
the row explains itself rather than just being inert.

**`reopenSender`** clears a sender's record, for one that keeps texting after being told to stop.
Wired in the ViewModel; no UI entry point yet, deliberately — the case is real but rare, and
guessing at its UI without having hit it would be inventing a design.

## Why a record rather than reading the Sent box

Scanning `content://sms/sent` for a matching keyword would need no new storage and would also
catch STOPs the user sent from their normal messaging app — genuinely appealing.

It was not used because the two are not the same fact. The Sent box says *a message was filed*;
the log says *this app sent this keyword and the radio confirmed it*. The app deliberately does
not write a Sent row for an `UNCONFIRMED` send, so inferring from Sent would silently inherit
that gap, and a body that happens to equal "STOP" would read as an opt-out it was not.

Known gap, accepted: an opt-out the user sent from another app is invisible here, so the app may
offer to send a duplicate. A duplicate STOP is harmless; wrongly suppressing a needed one is not.

## Verified

45 unit tests, up from 42 — three new: a sender in the log is marked unsubscribed and not
repliable, recognition works across address formats (opt-out sent to `+1 555-123-4567`,
confirmation arrives from `5551234567`), and an empty log leaves everything repliable.

End-to-end on the Android 12 emulator: ran a real batch, confirmed `optouts.preferences_pb`
holds `{"22395":{"k":"STOP","t":...},...}`, then injected the confirmation message from 22395.
The app showed it with the Unsubscribed badge, no checkbox, and Mark read / Delete, while an
untouched sender (55411) kept its checkbox and suggested keyword. *Mark all as read* banner
appeared for the 2 unsubscribed senders. Tapping *Mark read* cleared that sender's messages and
left the other two alone. No crashes.

## Out of scope

- Any change to detection or pacing.
- A UI entry point for `reopenSender` (see above).
