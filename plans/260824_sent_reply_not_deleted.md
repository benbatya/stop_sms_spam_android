# The opt-out reply survives the thread it belonged to

## What was asked for

> when a reply is sent and the sender thread is deleted on the device, the original
> message and response is deleted but the reply is still displayed in the regular
> message list. debug why

## Why it happens

Read from the code before touching anything; the path is unambiguous.

1. `MainViewModel.startBatch` snapshots each sender's `messages` into `ReplyPlan.messages`.
   Those refs are the **inbound** rows that were unread at the moment the batch was built.
2. `SmsSender.sendText` sends, and on `RESULT_OK` calls `SmsRepository.logSent`, which
   **inserts a new row** into `Telephony.Sms.Sent`. It carries the sender's address, so the
   provider files it under the same `thread_id` as the conversation.
3. `applyPostSend` then calls `repository.delete(plan.messages)` — and `delete` only ever
   removes the ids it is handed (`idSelection` → `_ID IN (…)`).

The sent row was created in step 2, after the list in step 1 was captured, so it is not in
`plan.messages` and step 3 cannot delete it. The inbound messages go, the outgoing "STOP"
stays, and the messaging app shows a thread containing nothing but our own reply.

There is no race: `logSent` runs inside `sendText` before `applyPostSend` is called. The bug
is purely that the delete addresses a stale list rather than the thread.

## The thing that makes this non-trivial

**That Sent row is the app's own memory.** `SmsRepository.loadOptOutStatus` reconstructs
"who has already been told to stop" by querying `Telephony.Sms.Sent` — it is what drives
`SpamSender.optedOut`, `isUnsubscribed`, `awaitingConfirmation`, `ignoredOptOut`, the
"already opted out - cleared, not replied to" path, and the "STOP sent Aug 21" chip.

So the naive fix — delete the sent row too — silently erases the record that stops the app
re-texting a sender it already opted out of. Any fix has to keep that knowledge somewhere.

## Options, not yet chosen

- **Delete the sent row and move the record into `SenderMemory`.** That store already
  persists per-sender memos (`markAutoDeleteResponses`, `rememberConfirmation`), so it is
  the natural home. Cost: `loadOptOutStatus` currently works for senders opted out *before*
  this app existed, by reading history it did not write. Moving to DataStore loses that.
- **Delete by `thread_id` rather than by id list**, after the send. Removes whatever is in
  the conversation including our reply, and matches what "delete the thread" means to a
  user. Same memory problem, plus it can delete rows the user never selected.
- **Leave the Sent row and accept the empty thread**, documenting it. Cheapest, and keeps
  opt-out tracking exactly as is — but it is the behaviour being reported as wrong.
- **Delete it only once the opt-out is confirmed**, when `SenderMemory` has already
  recorded the confirmation and the Sent row is redundant. More moving parts.

## Open question

Whether opt-out history for senders handled *outside* this app (or before install) is worth
preserving. That single answer decides between the first two options and the third.

## Out of scope until the cause is confirmed on a device

No fix is committed on this branch yet. The reasoning above is read from source; the next
step is to reproduce it on hardware and watch the provider rows, so the fix is aimed at an
observed failure rather than an inferred one.
