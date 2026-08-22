# Keep senders in the contacts list out of harm's way

## What was asked for

> filter out known senders from my contacts. I don't want to risk deleting those messages

The stated motivation is the important half: this is a **safety** change, not a tidiness one.
The batch deletes threads and can block numbers, and the default disposition for a selected
sender is *delete*. A real person's message caught in a "Select all" is data loss with no undo —
the app never copies a thread before deleting it.

## Approach

- Look each sender up with `ContactsContract.PhoneLookup.CONTENT_FILTER_URI`, which applies the
  platform's own number matching rather than a hand-rolled comparison. Contacts are stored in
  every format a human might type; matching them by string would be the bug this change exists
  to prevent.
- Requires `READ_CONTACTS`, a new runtime permission for this app.
- Short codes cannot be in contacts in any meaningful way, so they are looked up but expected
  to miss — no special case needed.
- Do the lookup once per distinct address per load, not per message.

## Open question, blocking the design

Whether a contact's thread should be **hidden entirely** or **shown but protected** (visible,
never auto-selected, excluded from "Select all"). "Filter out" suggests hiding; "don't want to
risk deleting" is satisfied by either, and protecting keeps the row explicable rather than
having messages silently absent. Asked before building.

## Out of scope, unless the answer above changes it

- Any change to what "unread" means, or to the SMS/MMS queries themselves.
- Contact-based *allow*-listing of spam (e.g. a saved short code) — that is the inverse problem.
