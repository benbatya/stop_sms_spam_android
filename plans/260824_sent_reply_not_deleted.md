# The opt-out reply survives the thread it belonged to

## What was asked for

> when a reply is sent and the sender thread is deleted on the device, the original
> message and response is deleted but the reply is still displayed in the regular
> message list. debug why

## Why it happens

Read from the code first, then reproduced on a device before anything was changed — the
provider rows in "Verified" below are what confirmed this rather than merely suggesting it.

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

## The fix

**Chosen with the user: delete the sent row, and read opt-out state from the union of the
Sent box and `SenderMemory`.** Pre-existing history still counts, so an opt-out sent from the
user's own messaging app — or before this app was installed — keeps working exactly as before.

- `SenderMemory.rememberOptOutSent(address, keyword, at)` records the send. It *overwrites*,
  where `rememberConfirmation` keeps the first: the derivation uses "latest opt-out wins",
  because a sender told to stop twice must be answered by a confirmation postdating the second
  attempt. This fits the store's existing charter — facts the provider cannot answer because
  the user asked us to destroy them.
- `SmsSender` records it on `RESULT_OK` *before* filing the Sent row, and now returns that
  row's id as `SendResult.Success(sentRef)`.
- `applyPostSend` deletes `plan.messages + sentRef`, so the reply goes with the thread.
- `loadOptOutStatus` takes `rememberedOptOuts` and **seeds the map with it before** the
  Sent-box pass. The ordering is load-bearing twice over: the pass overwrites per address so a
  surviving row still wins on recency, and the existing `if (optOuts.isEmpty()) return
  emptyMap()` would otherwise throw away the remembered entries — precisely the case that
  matters, since they exist *because* their rows were deleted.

## Verified

Measured on the Android 12 emulator, not inferred.

**Reproduced first, on the pre-fix build.** One inbound row `_id=1 type=1 thread_id=2`; after
a real (dry-run off) batch the inbox was empty but `_id=2 type=2 thread_id=2 body=END`
remained, and `content://mms-sms/conversations` still listed thread 2 — the empty conversation
the user reported.

**After the fix**, the same batch on a fresh sender left nothing: the inbound row gone, no Sent
row ever surviving, and thread 3 absent from the conversations list entirely. An untouched
sender in the same provider was undisturbed.

**The memory holds.** With that number's Sent row deleted, it texted again — and the app still
showed `"END" sent Aug 24, 2026 - no confirmation yet` and "no reply will be sent", so it will
not re-text a sender it already opted out of. A second sender whose Sent row still exists
rendered identically, proving both arms of the union produce the same result.

`testDebugUnitTest` (91 tests, 0 failures) and `lintDebug` pass. `RememberedOptOutsTest` pins
the merge arithmetic: newer-wins in both directions, a confirmation still attaching to a
remembered opt-out, and a confirmation predating it still rejected.

## Out of scope

- **MMS.** `logSent` only ever files SMS, so only an SMS reply can be orphaned this way.
- **Threads emptied before this fix.** Existing stranded Sent rows are left where they are;
  cleaning them up would mean deleting message history on evidence this branch did not gather.
