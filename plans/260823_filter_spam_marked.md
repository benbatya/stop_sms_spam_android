# Toggle: hide messages already marked as spam

## What was asked for

> add a toggle to filter out messages already marked as spam. they are not appearing in the
> normal list of messages so they might be irrelevant

The reasoning is the useful part: if the messaging app has already dealt with these, acting on
them here is wasted effort - and unlike the contacts filter, this one is about *noise*, not
safety, which is why it is a toggle rather than an unconditional exclusion.

## What "marked as spam" can actually mean

Checked against the provider rather than assumed, because **AOSP has no spam column**. The full
column list on `content://sms` is `_id, thread_id, address, person, date, date_sent, protocol,
read, status, type, reply_path_present, subject, body, service_center, locked, sub_id, error_code,
creator, seen` - nothing about spam, junk or classification. So the phrase has to map onto
something else:

1. **The system blocked-numbers list** (`BlockedNumberContract`). Google Messages' "Block & report
   spam" writes here, and blocked senders stop appearing in its conversation list - which matches
   the description exactly. Readable: the app already calls `isBlocked` for its own blocking
   feature, and access is granted to the default SMS app, which this is.
2. **Archived threads** (`Threads.ARCHIVED`, which does exist on the conversations table).
   Readable, but archiving is a manual tidy-away rather than a spam verdict.
3. **Google Messages' own spam folder.** Its private database. **Not readable by any other app**,
   so if this is the meaning, the feature cannot be built as asked and the honest answer is to
   say so rather than approximate it.

## Open question, blocking the design

Which of the above the user means. Asked before building, because (1) and (2) are different
filters and (3) is not implementable.

## Approach, assuming it is the blocked list

- A persisted toggle in settings, default **on** given the stated reasoning.
- Filter at the same seam as the contacts filter - after grouping, one lookup per distinct
  sender - so both exclusions live in one place.
- Unlike contacts, say how many were hidden and let the toggle bring them back: this is noise
  reduction, not protection, so silently vanishing rows would be the wrong trade.

## Out of scope

- Classifying spam ourselves. The app deliberately has no spam detector; see the note on
  `hasOptOutLanguage` being the absence of a signal rather than a verdict.
- Any change to what "unread" means.
