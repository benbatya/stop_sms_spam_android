# Auto-delete responses from senders whose thread was deleted

## What was asked for

When a sender's thread is marked for deletion during a batch, remember the number so that the
reply it sends back — the "you have been unsubscribed" confirmation — is deleted on arrival too.
Going back afterwards to delete the responses by hand should not be necessary.

## The collision

Opt-out state is derived from the message history, with no side record. Two halves:

- **Did we ask?** A Sent-box message whose body is an opt-out keyword. **Survives** — the app
  never deletes from the Sent box.
- **Did they answer?** An inbox message from them, dated after ours, in confirmation language.
  **This is exactly the message the request asks to destroy.**

Delete the confirmation and the sender's state silently drops from *unsubscribed* back to
*opt-out sent, no confirmation*. That is not cosmetic: **STOP IGNORED** — the state that offers
to block a number — is defined as *confirmed, then texted again*. Without the confirmation on
record, a sender that breaks its promise later looks merely unanswered, and the block prompt
never appears.

So "delete the response" and "keep deriving everything from the provider" cannot both hold in
full. Something must give, and which one is a judgement about the app, not a detail.

## Resolution: record it, then delete

Option A. A small `SenderMemory` store holds two facts per sender — whether its replies should be
dropped, and when it confirmed. It is written at the moment the evidence is destroyed and folded
back in on load by `RememberedConfirmations.applyTo`.

This is not a second source of truth competing with the provider. It answers only what the
provider **cannot**, because the user asked for those messages to be deleted. Everything still
derivable is still derived: "did we ask?" continues to come from the Sent box, which is never
deleted from, so only the answering half needed remembering at all.

## The refinement that fell out of it

**Only the acknowledgement is dropped, not everything from the number.** Registering the sender
and discarding all its future messages would also swallow a later marketing message — and that
message is the entire basis of the STOP IGNORED state, so the escalation this whole design goes
out of its way to preserve would have been made unreachable by the same stroke. The request's own
wording, "when a response is returned", already scopes it that way.

A remembered confirmation also has to postdate the opt-out it answers, or a sender told to stop a
second time would look as though it had already replied to the second request. That rule is a
pure function with its own tests, because it is the kind of thing that is easy to get subtly
wrong and impossible to notice.

A thread deleted from the row's own **Delete** button registers the sender too — deleting a
thread means the same thing however it was done.

## Cleared is not the same as hidden

The first cut dropped the confirmation with no trace at all — and that was wrong for a reason the
emulator could not show: it took away the one piece of good news the whole exercise produces.
The user asked not to have to *delete* the reply, not to be kept from knowing the STOP worked.

So the message is still cleared from the inbox, and a notification says what it said:
**"Unsubscribed from 64646"** with the sender's own wording.

It gets its own channel so it can be silenced without silencing real texts.

**The first attempt put that channel at DEFAULT importance**, reasoning that a confirmation
reports something finishing rather than someone trying to make contact. On the phone that was
simply wrong: DEFAULT posts **no heads-up banner**, so the notification landed silently in the
shade and the user reported seeing nothing. The evidence was unambiguous once looked at —
`opt_out_confirmed` had `mImportance=3, mShowBanner=false` against `incoming_sms` at
`mImportance=4, mShowBanner=true`, and eight confirmations had been recorded and cleared without
anyone noticing.

A notification the user does not see does not inform them, which was the entire requirement. The
channel is now HIGH.

**That correction needed a new channel id** (`opt_out_confirmed_v2`). A channel's importance is
fixed at creation — the system lets an app lower it later but never raise it — so editing the
constant alone would have changed nothing on any device that had already run the app, including
the one the bug was found on. The v1 channel is deleted so it does not linger in settings as a
dead entry.

## Verified

61 unit tests (up from 57), four of them on the merge rule: a deleted confirmation is restored,
one older than the opt-out is ignored, one still in the provider is left alone, and senders with
nothing remembered are untouched. `lintDebug` and `assembleDebug` clean.

End to end on the Android 12 emulator, in the order that matters:

1. Real batch to `71717` with delete on → `STOP` in the Sent box, thread gone, memory shows
   `{"71717":{"ad":true,"c":null}}`.
2. Confirmation arrives → **0 inbox rows**, `"c"` filled in with a timestamp, and a notification
   posted on the `opt_out_confirmed` channel titled "Unsubscribed from 64646" — informed without
   anything left to clean up.
3. A later marketing message from the same number → **does** arrive, 1 inbox row. Not swallowed.
4. Reopening the app shows it as **STOP IGNORED** with the Block button — the escalation intact
   from a confirmation that no longer exists anywhere in the provider.

## Out of scope

- Deleting anything from the Sent box. "Did we ask?" depends on it, and the user did not ask.
- Auto-deleting for senders whose thread was only marked read. The request is specifically about
  the ones marked for deletion.
