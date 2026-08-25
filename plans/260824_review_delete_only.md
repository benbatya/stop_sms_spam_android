# Delete a thread instead of texting it an opt-out

## What was asked for

> in the review screen, add a feature to simply delete instead of sending opt out

Every selected sender that *can* be replied to was going to be texted. The only way to say
"I don't want to talk to this one at all, just get rid of it" was to go back to the Inbox and
deselect it — which then leaves the thread sitting there unread. The ask was an escape hatch:
keep the sender in the batch, but clear its thread without sending anything.

Four follow-up requests moved the control from the Review screen to the Inbox card, made the
keyword editable there, and turned Review into a read-only confirmation. The sections below
describe where things landed, not the order they were built in.

## Where the choice lives

**On the Inbox card, on the keyword's own line.** A row reads `[x] Reply "STOP"`, where the
tick is whether an opt-out goes out at all and `STOP` is an editable field. It belongs on that
line rather than beside Delete/Block because it is a statement *about that keyword*: unticked,
the words beside it describe a message nobody will ever receive, which is why the text is
struck through when it is off.

The keyword is typed in place rather than described, because detection only ever produces a
proposal — the sender decides which word works, and only the user knows when it is not STOP.

**The Review screen states the result and does not re-open it.** It lists every selected
sender, each with its reply, its thread disposition and whether the number gets blocked. It
carried per-sender editors for a while; they came out once the Inbox card had both controls,
because two places to change one setting means two places to look for its current value. What
a last-look screen in front of a batch that texts strangers and deletes threads is for is
stating the decisions.

The one control Review keeps is the bulk **"Delete N threads instead"** on the no-opt-out
warning. That is not a duplicate: there is no bulk equivalent on the Inbox, and the warning is
computed over the whole assembled batch, which is only knowable here.

## How it works

The batch machinery already supported this exactly. `ReplyPlan.sendReply = false` makes
`BulkReplyService` skip the send, run the same post-send cleanup (delete or mark read, block),
and report `CLEARED`. That flag was only ever `sender.canReply` — a fact about the sender, not
a choice. So the work was state and UI, not sending.

- **`UiState.replySuppressed: Set<String>`** — normalized addresses the user chose to clear
  rather than text. `sendsReply(sender)` = `canReply && not suppressed`, and
  `selectedForReply` / `selectedForCleanup` are re-derived from it. Everything already keyed
  off those two lists — the pacing estimate, the throttle warning, the short-code and
  no-opt-out counts, the bottom-bar label, the confirmation dialog — followed for free.
  Pruned alongside the other per-sender maps in `applySenderVisibility`.
- **`ClearReason` on `ReplyPlan`** — the progress line for a cleared plan used to hard-code
  "Already unsubscribed - cleared without replying". Once the user can put a sender there by
  choice that is a lie, so the plan carries which of the two it was. Persisted in `BatchStore`,
  or a resumed batch would report the user's own decision as something the sender did; its
  absence in an older file decodes to `ALREADY_OPTED_OUT`, which is all a pre-existing
  non-sending plan could have meant.
- **`outgoingKeyword` vs `keywordFor`** — see "Bug found" below.

## Decisions taken

- **Suppressing a reply forces `delete` on** (confirmed with the user). One control changing
  another's state is a real cost, but the alternative is a control labelled "delete instead"
  that, for a sender previously marked "keep", neither replies nor deletes. The "All together"
  card on the Review screen shows the resulting counts, so the consequence is visible.
- **"Select all" resets Reply and Delete on** for every sender it takes, so a bulk action lands
  on the defaults rather than on leftovers from the last time those rows were ticked. Only the
  keys it touches are reset, so a sender it deliberately skipped keeps what was set by hand.
  It still skips senders that never offered an opt-out — that exclusion is a separate safety
  decision, and the request was about the toggles' default state, not about which senders get
  selected.
- **Tapping an Inbox card opens a detail dialog instead of toggling selection.** The checkbox
  does the selecting. The row can only ever show an excerpt, and deciding whether a thread is
  spam or a delivery notice from somebody real needs the whole message. The card says "Tap for
  the full message" rather than letting a familiar control silently change what it does.
- **The confidence suffix was dropped, except the case that was not a suffix.** `(probable)`
  and `(they asked for it)` are gone. `ASSUMED` rendered the whole chip as "No opt-out offered
  - "STOP" is a guess", which is the difference between a reply that stops somebody and a reply
  that confirms a live number to a stranger; it survives as a red line under the field. Full
  provenance is in the detail dialog.
- **Wording distinguishes the two silent halves throughout** — "already opted out, so the
  thread is only cleared" vs "you chose to delete this one instead", and on the progress list
  "Already unsubscribed - cleared without replying" vs "Deleted without replying, as chosen".
  One is a fact about the sender; the other is the user's own decision.

## Bug found

Nothing stopped an emptied keyword field from queuing a **blank SMS**. It predates this branch
— the Review screen's keyword box had the same hole — but making the field editable on every
Inbox row put it one tap away.

`UiState.outgoingKeyword` resolves an empty box back to the detected keyword and is what
`startBatch` and the Review screen's `• Reply "..."` line both use, so what Review promises is
what goes out. It is deliberately *not* folded into `keywordFor`: the field has to let the user
delete what is in it before typing something else, and a value that refuses to go empty cannot
be retyped.

## Out of scope

- **No new persistence.** Like the selection and the keyword overrides, the reply choice lives
  only as long as the batch does.
- **The batch/sending path is unchanged** apart from `ClearReason` plumbing — no change to
  pacing, throttling, blocking, or how messages are sent.

## Verified

`testDebugUnitTest` and `lintDebug` pass. `DeleteInsteadOfReplyingTest` covers the state
derivation (suppression moves a sender between the two lists, chosen vs already-opted-out stay
distinguishable, suppressing an already-opted-out sender is a no-op, suppressed senders do not
count toward the send total) and the empty-keyword fallback.

Exercised on the Android 12 emulator, which is the app's minSdk floor:

- Unticking Reply moves a sender out of the send list; the pacing estimate (7 replies/30s ->
  6/25s) and the short-code count follow it.
- The no-opt-out warning's "Delete 1 thread instead" converts the flagged sender and the
  warning disappears.
- Typing `quit` over `STOP` on an Inbox row carried through to Review's `• Reply "QUIT"`.
- A real (dry-run off) batch of one delete-only sender reported "1 of 1 - 0 sent, 0 failed,
  1 cleared" with the detail "Deleted without replying, as chosen". `content://sms/sent` stayed
  empty and the sender's inbox rows were gone — nothing sent, thread actually deleted.

Also installed on a physical OnePlus 7T (HD1907, Android 12). The SMS role was deliberately
**not** granted there: taking it makes the app responsible for persisting every incoming
message on a real phone, and MMS is not implemented, so that is a decision for the user's own
setup screen rather than an adb command.
