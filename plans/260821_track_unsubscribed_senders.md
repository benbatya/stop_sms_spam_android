# Track opt-out state from the message history, and escalate senders that ignore it

## The problem, from manual testing on a real phone

Opting out is not the end of the conversation. A sender that honours STOP usually **replies to
confirm it** — "You have been unsubscribed" — and that confirmation arrives as a new unread SMS
from the same address. The app, having no notion of having opted out, listed that sender again as
fresh spam and invited the user to text them a second time. Marking the originals read did not
help: the confirmation is a *newer, different* message.

## Three states, all derived from the provider

| State | Means | Offered |
|---|---|---|
| repliable | nothing sent to this sender | checkbox, suggested keyword |
| awaiting confirmation | an opt-out went out, no acknowledgement | Mark read, Delete |
| unsubscribed | the sender acknowledged it | Mark read, Delete |
| **STOP ignored** | acknowledged, **then texted again** | **Block number**, Mark read, Delete |

*Asked* and *answered* are kept apart deliberately. A sender that never replies stays in the
second state rather than being reported as done, because nothing supports saying so.

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

53 unit tests (up from 42). The ones that matter: a solicitation is not its own confirmation;
`isOptOutReply` accepts a bare keyword and rejects a sentence containing one; an unconfirmed
opt-out cannot be "violated"; a sender that only repeats its acknowledgement is not accused of
violating it.

End-to-end on the Android 12 emulator, with the Sent box carrying opt-outs from earlier runs:

- `43733` confirmed → shown **Unsubscribed – they confirmed on Aug 21, 2026**, no checkbox.
- `22395` confirmed and then sent a fresh sale → shown **STOP IGNORED**, in error colours, with
  the explanation and a Block button.
- Tapping **Block number** cleared it from unread. Blocking was then proved functionally rather
  than by reading the list back (that read is itself privileged): a further message from `22395`
  **never reached the provider**, while a control message from `55411` sent at the same moment
  arrived normally.

## Known limitation

Opt-out state is derived from the whole message history with no lookback window, so a sender
opted out of long ago can never be re-sent to from this app. If a sender goes quiet for a year
and returns, the user would have to reply from their normal messaging app. A time bound would fix
it but is a policy guess; leaving it until the case is actually hit.
