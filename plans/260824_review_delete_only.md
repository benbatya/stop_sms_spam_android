# Review screen: delete a thread instead of texting it an opt-out

## What was asked for

> in the review screen, add a feature to simply delete instead of sending opt out

On the Review screen every selected sender that *can* be replied to is going to be texted.
The only way to say "I don't want to talk to this one at all, just get rid of it" is to go
back to the Inbox and deselect it — which then leaves the thread sitting there unread. The
ask is a per-sender escape hatch on Review: keep the sender in the batch, but clear/delete
its thread without sending anything.

## Approach

The batch machinery already supports this exactly. `ReplyPlan.sendReply = false` makes
`BulkReplyService` skip the send, run the same post-send cleanup (delete or mark read, block),
and report `CLEARED`. Today that flag is only ever `sender.canReply` — a fact about the sender,
not a choice. So the work is state + UI, not sending.

- **`UiState.replySuppressed: Set<String>`** — normalized addresses the user chose to clear
  rather than text. `sendsReply(sender)` = `canReply && not suppressed`, and
  `selectedForReply` / `selectedForCleanup` are re-derived from it. Everything already keyed
  off those two lists — the pacing estimate, the throttle warning, the short-code and
  no-opt-out counts, the bottom-bar label, the confirmation dialog — then follows for free.
- **`MainViewModel.setSendReply`** also forces `delete` on when suppressing: the control says
  "delete instead", so leaving the thread merely marked-read would not be what it promised.
  Pruned alongside the other per-sender maps in `applySenderVisibility`.
- **Review row control** — a "Delete instead of replying" action on each sender card, and the
  reverse on a suppressed one. Suppressed senders move into the cleanup section.
- **Cleanup section splits in two.** It currently says "These senders have already been sent
  an opt-out", which stops being true once the user can put senders there by choice. Already
  opted out and chosen-not-to-reply are shown apart.
- **`ClearReason` on `ReplyPlan`** — for the same reason, the progress line for a cleared
  plan hard-codes "Already unsubscribed - cleared without replying". A plan needs to carry
  which of the two it was so that line stays honest.
- **Bulk action on the no-opt-out warning.** That card already says "Consider removing them
  below" about senders that never offered an opt-out; replying to those is the one case with
  a real downside. It gets a button that suppresses all of them at once.

## Out of scope

- The Inbox screen's per-sender controls are untouched — this is a Review-screen decision
  about a batch that has already been assembled.
- No new persistence: like the selection and the keyword overrides, the choice lives for as
  long as the batch does.

## Open questions

- Should suppressing a reply also force `delete`? Taken as yes (see above), but it means one
  control silently changes another's state. The "Then" summary card updates in view, so the
  consequence is at least visible rather than hidden.
