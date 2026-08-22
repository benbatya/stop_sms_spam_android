# Track opt-out state from the message history, and escalate senders that ignore it

## The problem, from manual testing on a real phone

Opting out is not the end of the conversation. A sender that honours STOP usually **replies to
confirm it** — "You have been unsubscribed" — and that confirmation arrives as a new unread SMS
from the same address. The app, having no notion of having opted out, listed that sender again as
fresh spam and invited the user to text them a second time. Marking the originals read did not
help: the confirmation is a *newer, different* message.

## Three states, all derived from the provider

| State | Means | What selecting it does |
|---|---|---|
| repliable | nothing sent to this sender | sends the opt-out, then clears the thread |
| awaiting confirmation | an opt-out went out, no acknowledgement | clears the thread, sends nothing |
| unsubscribed | the sender acknowledged it | clears the thread, sends nothing |
| **STOP ignored** | acknowledged, **then texted again** | clears the thread; also offers **Block number** |

*Asked* and *answered* are kept apart deliberately. A sender that never replies stays in the
second state rather than being reported as done, because nothing supports saying so.

**Every state keeps its checkbox.** An earlier cut removed it from senders already opted out of,
reasoning that there was nothing to send them. That was the wrong conclusion from the right
premise: the user still wants those threads dealt with, they just want them cleared rather than
texted. So selection is uniform and the *plan* carries what to do — `ReplyPlan.sendReply`, fixed
when the batch is confirmed rather than recomputed mid-run.

Consequences worth stating:

- A cleared thread **does not consume the pacing delay**. Pacing protects the radio and the
  carrier; clearing touches neither, so ten cleared threads and two sent ones cost one gap, not
  eleven.
- The throttle warning, the short-code warning and the confirmation dialog all count **only the
  messages that leave the phone**. Counting cleared threads would warn about a burst that never
  happens and ask the user to confirm texts that are not being sent.
- The action button says what will happen — "Send 1, clear 1" — rather than a single total that
  hides the split.

## Source of truth: the provider, not a side record

The first implementation kept a DataStore log of what the app had sent. That was replaced, on
review, with derivation from the Telephony provider on each load:

- **Did we ask?** A Sent-box message to that address whose body *is* an opt-out keyword.
- **Did they answer?** An inbox message from that address, **dated after** ours, matching
  confirmation language.

This is the better source of truth, not merely a tidier one. It makes an opt-out the user sent
from their **normal messaging app** count exactly as much as one this app sent — the gap the log
version had to declare and accept. It cannot drift from the messages, and it survives reinstall,
because the messages do.

The objection raised against it earlier — that the app deliberately files no Sent row for an
`UNCONFIRMED` send — turned out to argue *for* it. An unconfirmed send is precisely one we cannot
claim went out, so its absence from the Sent box is the correct answer, not a gap.

Cost, accepted: two provider queries per load instead of one preference read. The second is
bounded by the first — no opt-outs sent means no second query, and otherwise only messages newer
than the earliest opt-out can confirm one.

## The distinction the whole thing rests on: tense

Solicitations invite the action — "reply STOP to **unsubscribe**". Acknowledgements report it
done — "you have been **unsubscribed**". Matching the past tense is what stops every piece of
spam from confirming its own opt-out. Getting this wrong in the permissive direction would mark
senders unsubscribed before anything was sent, so the confirmation patterns are deliberately
narrow, and a missed confirmation merely leaves a sender "awaiting confirmation" until it goes
quiet.

The same care applies to reading the Sent box: an opt-out reply is the bare keyword and nothing
else, so `isOptOutReply` requires a single token. "did the STOP work?" is a message about an
opt-out, not one.

## Blocking

A sender that confirmed and then texted anyway has already proved it ignores its own opt-out, so
replying again is pointless. That row gets **Block number**, which writes to the system
blocked-numbers list — restricted to the default SMS app, dialer and carrier apps, which this app
qualifies as *exactly while it holds the SMS role*.

Always one sender at a time, never part of a batch: blocking is system-wide and outlives this
app, so it should be a decision about a specific number. A failed block sets `lastBlockFailed`
rather than silently doing nothing.

## Verified

57 unit tests (up from 42). The ones that matter: a solicitation is not its own confirmation;
`isOptOutReply` accepts a bare keyword and rejects a sentence containing one; an unconfirmed
opt-out cannot be "violated"; a sender that only repeats its acknowledgement is not accused of
violating it.

End-to-end on the Android 12 emulator, with the Sent box carrying opt-outs from earlier runs:

- `43733` confirmed → shown **Unsubscribed – they confirmed on Aug 21, 2026**, no checkbox.
- `22395` confirmed and then sent a fresh sale → shown **STOP IGNORED**, in error colours, with
  the explanation and a Block button.
- A mixed batch with `62626` (repliable) and `43733` (already unsubscribed) selected together
  reported **"Send 1, clear 1"**, and the Sent box grew by exactly **one** row — to `62626`.
  `43733` still shows a single outgoing message, its original `UNSUB`, and both threads left the
  unread list.
- Tapping **Block number** cleared it from unread. Blocking was then proved functionally rather
  than by reading the list back (that read is itself privileged): a further message from `22395`
  **never reached the provider**, while a control message from `55411` sent at the same moment
  arrived normally.

## Disposition is per sender, chosen at selection

The Review screen used to carry two global switches — "mark the spam as read" and "delete the
spam instead". They are gone. One pair of switches cannot express "delete these, keep that one,
and block the one that ignored its own opt-out", which is the actual shape of a batch.

Selecting a sender now reveals its own controls, with defaults that match what the state implies:

| Sender | Delete | Block |
|---|---|---|
| ordinary spam | **on** — being rid of these is the point | off |
| STOP ignored | **on** | **on** — asking has already been tried and demonstrably failed |

Either can be toggled per sender. `ReplyPlan` carries `delete` and `block`, fixed when the batch
is confirmed, and `AppSettings`/`BatchSnapshot` lost their global equivalents.

**Blocking is never silent.** It is system-wide and outlives this app, so: the checkbox is
labelled in error colours, Review summarises "N numbers blocked — system-wide, and it outlives
this app", and the confirmation dialog names it. A batch that only blocks and clears — sending
nothing at all — still asks for confirmation, where previously confirmation was skipped whenever
no message was going out.

## "Select all with opt-out" became "Select all"

The bulk action excluded senders already opted out of. That made sense when selecting a sender
meant texting it — but it no longer does, since such a sender is cleared rather than re-texted.
The exclusion therefore skipped exactly the threads a user reaching for a bulk action most wants
swept up: the confirmations and the unanswered opt-outs.

It now selects everything, including senders that never offered an opt-out. **The protection
moves rather than disappearing**: those senders are still counted in Review's "never offered a
way to opt out — replying confirms your number is live" warning, and are still individually
deselectable. Excluding them from a button labelled "select all" would have been a quieter way
of doing the same job, and a more surprising one.

## A bug the four-state test scene caught

Setting up one sender in each state exposed that the list keyed its display on `isUnsubscribed`
(confirmed only) while the batch keys its behaviour on `canReply` (anything already sent an
opt-out). A sender in the middle state - opt-out sent, never acknowledged - therefore showed a
"Reply STOP" chip and a checkbox, while selecting it would have *cleared* the thread instead.
The row promised one thing and the plan did another.

Both now key on `canReply`. Worth noting the shape of the mistake: two predicates that agreed in
every case that had been looked at, and disagreed only in the state nothing had exercised yet.

## Follow-up not done here

The Review screen says "Replying to a **scam number** does not stop it". The app does no scam
detection whatsoever — the only signal is that a sender's message contained no opt-out
instruction, which is a proxy for "a reply probably will not help", not for "this is a scam". A
scammer that writes "Reply STOP to opt out" is treated as entirely legitimate. The wording
overstates what the code knows and should be softened; left alone here because it is a copy
decision, not part of this change.

## Known limitation

Opt-out state is derived from the whole message history with no lookback window, so a sender
opted out of long ago can never be re-sent to from this app. If a sender goes quiet for a year
and returns, the user would have to reply from their normal messaging app. A time bound would fix
it but is a policy guess; leaving it until the case is actually hit.
