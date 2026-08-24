# Toggle: hide senders already blocked

## What was asked for

> add a toggle to filter out messages already marked as spam. they are not appearing in the
> normal list of messages so they might be irrelevant

The reasoning is the useful half: if the messaging app has already dealt with these, acting on
them here is wasted effort. Unlike the contacts filter, this is about *noise*, not safety - which
is why it is a toggle rather than an unconditional exclusion.

## "Marked as spam" had to be pinned down first

**AOSP has no spam column.** The full column list on `content://sms` is `_id, thread_id, address,
person, date, date_sent, protocol, read, status, type, reply_path_present, subject, body,
service_center, locked, sub_id, error_code, creator, seen` - nothing about spam, junk or
classification. Three candidates were put to the user:

1. **The system blocked-numbers list.** Google Messages' "Block & report spam" writes here, and
   blocked senders stop appearing in its conversation list - matching the description exactly.
2. **Archived threads** (`archived` does exist on the conversations table), but archiving is a
   manual tidy-away, not a spam verdict.
3. **Google Messages' own spam folder** - its private database, readable by no other app, so
   that reading would have been "cannot be built as asked".

**Chosen initially: the blocked list.** The app already reads it for its own Block feature.

**That turned out to be the wrong signal, and the device proved it.** On the user's phone the
line never appeared. A temporary diagnostic settled why:

```
checked=875  blocked=0  errors=0
```

All 875 senders checked, no exceptions, **none blocked**. Two reasons, and both are structural
rather than accidental:

- Google Messages' automatic **spam** classification does not write to `BlockedNumberContract`.
  Only an explicit "Block" does. Both land in the same "Spam & blocked" folder in its UI, which
  is why they look like one thing from outside.
- Even for a number that *is* blocked, Android drops its incoming SMS before delivery - so a
  blocked sender stops accumulating unread messages. A well-populated blocked list can still
  legitimately yield zero hidden senders here.

**Archived threads are the signal that matches.** Of the 925 threads holding unread messages on
that phone, **831 were archived and 94 were not** - Google Messages archives what it files as
spam, and `archived` is on the conversations table where anyone can read it. So the toggle now
covers both: blocked, or every one of the sender's threads archived.

## Where the filter lives, and why there

`UiState.senders` *is* the visible list. The unfiltered list and the blocked-address set are held
privately in the view model and never published.

That was deliberate. `senders` is read in eight places - the unread count, "Select all", the
empty state, the batch - and a design where the UI holds both lists would need every one of them
audited for using the right one, plus every future one. Keeping only the filtered list reachable
makes the whole class of mistake unrepresentable; `hiddenBlockedCount` is exposed as a bare count
so the banner can report without being handed the senders themselves.

**Selections are pruned to what is visible, not to what was loaded.** Hiding a sender that was
already ticked would otherwise leave it selected and in the batch - the one way a filter the user
can toggle could still text somebody they thought they had put away. Verified on device, below.

`ContactFilter` became `SenderExclusion`, since two callers with different motives now share it:
contacts, which must never be touched, and blocked numbers, which are merely noise. The mechanics
are identical; only the address source and whether the user can turn it off differ. A `matching`
companion returns what `exclude` would drop, for the count.

## Failing open is fine here, and that is not a contradiction

Reading the blocked list needs the SMS role, so losing the role turns `blockedAmong` into an
empty set - "nothing is blocked". That is the safe direction *here*: the worst case is showing
senders that could have been hidden. It is the opposite call from the contacts filter, where
failing open had to be announced in red, because there the cost was deleting a person's messages.

## The banner appears only when it can do something

Shown when senders are hidden, or when the filter is off. With the filter on and nothing blocked
there is no line - toggling would change nothing visible, so the control would be noise. This was
briefly mistaken for a discoverability bug; it is not, because the control appears exactly when
it has an effect.

## Shown senders say why they were hidden

With the filter off the list simply got longer, with nothing saying which rows had been added -
so a hidden-when-on sender carries a badge reading **Blocked** or **Archived**.

Two values rather than one boolean, because they mean different things: blocked is the system
refusing the sender's messages, archived is only "filed away" - and an archived thread is far
more likely to hold something the user still wants. Blocked wins when both apply, being the
stronger statement.

**A sender counts as archived only when *every* one of its threads is.** A sender with one
archived thread and one live one still has somewhere the user is reading, and hiding it would
lose a real message. That is why thread ids had to be carried onto `SpamSender` through grouping
rather than checked per message.

`blockedAddresses` on `UiState` is a *marker* set, not a second sender list: it says something
about the senders already visible rather than offering a route around the filter, so it does not
reopen the hole the private-list design closes. It is only ever non-empty with the toggle off -
a hidden sender is not in `senders` to be marked.

Error-toned rather than neutral. Not as a warning: it is the one status in the list where the
system is already refusing this sender's messages, which is worth reading differently from
"unsubscribed" or "MMS".

## Verified

76 unit tests, `lintDebug` and `assembleDebug` clean.

End to end on the Android 12 emulator, which needed a trick: **every path that blocks a number
also marks that sender's messages read**, so "blocked *and* still unread" cannot be produced by
the app's own actions - and `adb` cannot write the blocked list at all (`Caller must be system,
default dialer or default SMS app`). The state was created by starting a two-sender batch at 61s
with Block ticked on the second, then sending that sender a further message during the gap, so it
arrived after the plan snapshot and survived the cleanup.

1. Inbox shows **`1 sender hidden - already blocked`**, and `41414` is absent while
   `content://sms/inbox` confirms it has an unread row.
2. "Show them" → `41414` returns, unread count 2 → 3, label flips to "Showing senders you have
   already blocked".
3. `41414` selected (bottom bar "Review 1 reply") → "Hide them" → bottom bar returns to
   "Select the spam to reply to". The hidden sender left the batch.
4. Force-stop and relaunch with the filter off → still off.

Then again with four blocked senders, built the same way (a five-sender batch with Block ticked
on three, and further messages sent after the plan snapshot):

5. Filter off → `51511`, `52522`, `53533` and `41414` each show a red **Blocked** badge beside
   the number; `18885551234` does not.
6. "Hide them" → **`4 senders hidden - already blocked`**, list down to `Unread (1)`.

79 unit tests after the archived work, three of them on thread ids surviving grouping and on the
all-threads-or-nothing rule.

**The archived path has been measured but not yet seen running.** The 831-of-925 figure comes
from querying the phone's own provider directly, so the filter will hide roughly that many - but
the phone locked before the rebuilt app could be observed, so the banner and badges have only
been watched on the emulator, against blocked senders this app created itself. What the emulator
cannot supply is an archived thread, and what the phone has not yet shown is either.

## Out of scope

- Classifying spam ourselves. The app deliberately has no spam detector.
- Archived threads. A candidate above, not chosen; the address plumbing would suit it if wanted.
- Any change to what "unread" means.
