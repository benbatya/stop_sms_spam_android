# List MMS senders so they can be Stopped too

## Why

A message from `8022160869` was missing from the app. It turned out to be an **MMS**: zero rows
in the SMS table, one row in `content://mms` under thread 3744. The app reads
`content://sms/inbox` only, so it never saw it.

That is not a one-off. On the test phone: **1,183 unread inbox MMS**, 10,503 in total. A large
share of the spam this app exists to handle was invisible to it.

## Scope: read what is already stored, do not implement MMS

Full MMS support means WAP push PDU parsing and MMSC fetches — deliberately out of scope from the
start, and still is. This reads what the messaging app has **already** stored and treats those
senders like any other: detect the keyword, reply by SMS, mark read, delete, block.

Replying by SMS to a sender that messaged by MMS is fine — the opt-out keyword is a short text,
and the number is the same.

## How the data is actually shaped

Probed on the device rather than assumed:

- `content://mms` where `msg_box=1 AND read=0` gives `_id`, `thread_id`, `date`.
- **`date` is in seconds**, not milliseconds as in the SMS table. Mixing the two unconverted
  would sort every MMS to 1970.
- `content://mms/<id>/addr` holds the sender (`type=137`), but **only per message** — a bulk
  query against `content://mms/addr` returns nothing, so 1,183 messages would mean 1,183 queries.
- The bulk-friendly path is `conversations?simple=true` (`thread_id` → `recipient_ids`) joined to
  `canonical-addresses` (`_id` → `address`). Verified: thread 3744 → recipient 3426 →
  `+18022160869`.
- `content://mms/part` **does** query in bulk: `mid`, `ct`, `text` for `ct='text/plain'`, which
  supplies the body the keyword detector needs.

So four bulk queries, no per-message work.

## Approach

- Load MMS senders alongside SMS and feed both through the existing `SenderGrouping`, so keyword
  detection, opt-out state, disposition and blocking all apply unchanged.
- A message needs to carry which table it came from, since marking read and deleting differ.
  Replacing the bare `messageIds: List<Long>` with a reference that knows its source is cleaner
  than two parallel id lists threaded through `SpamSender`, `ReplyPlan` and the batch store.
- Group MMS threads with more than one recipient are skipped: a group thread has no single spam
  sender, and replying STOP into one would text strangers.

## What the real inbox showed

Replicating the four queries against the phone, 1,183 unread MMS resolve to **1,104 senders**
across 572 distinct addresses — then two things turned up that the emulator never would have.

**RCS and email-gateway participants come through the same table**, as
`…@rcs.google.com`. They were three of the five noisiest "senders", 142 messages between them.
They are excluded: an opt-out texted to one would fail, a number that is not a number cannot be
blocked, and listing them would fill the inbox with rows nothing in this app can act on. After
excluding them and group threads: **914 actionable unread MMS from 556 senders.**

**The message that prompted this change still will not appear**, and that is worth being plain
about. `8022160869` is `read=1` — it was already read in the messaging app. This surfaces
*unread* MMS, exactly as it does for SMS; it does not change what "unread" means. The 914 that
do appear are the point, not that one.

## The limitation this does not fix

While **this app holds the SMS role**, incoming MMS are not written to the provider at all —
`MmsDeliverReceiver` parks the PDU and notifies, by design. So this surfaces the backlog stored
by the previous default app, and any MMS that arrives while the app is default still will not
appear. Reading and receiving are different problems; only the first is solved here.

## Out of scope

- Rendering MMS content — attachments, images, subjects.
- Detecting an opt-out *confirmation* that arrives by MMS. Confirmations are near-universally
  SMS, and the derivation stays on the SMS inbox for now.
- Any change to `MmsDeliverReceiver`.
