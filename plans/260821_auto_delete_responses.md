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

## Open question — the fork

**A. Record the confirmation before deleting it.** A small persisted memo of
`{address → confirmedAt}`, written at the moment the evidence is destroyed and consulted when
the provider no longer has it. All current behaviour is preserved exactly, including the
escalation. The cost: it reintroduces a side record, which was deliberately removed one change
ago — though this one is narrower, storing only facts whose evidence the user asked to delete
rather than duplicating anything still derivable.

**B. Widen what counts as a violation.** Drop the requirement that a violation follow a
*confirmed* opt-out: any sender that was sent an opt-out and texted again afterwards is
escalated. No storage at all, and the provider stays the only source of truth. The cost: a
sender that never acknowledged and simply kept texting would now be labelled as having ignored
its opt-out — which it arguably did, though the current wording ("agreed to stop and then
messaged you anyway") would have to change, since no agreement was made.

Not guessing between these: the previous change deliberately chose deriving over recording, and
the previous change also deliberately kept *asked* and *answered* apart.

## Approach, once the fork is settled

- Track numbers whose thread was deleted, added when a batch's plan has `delete = true`.
- `SmsDeliverReceiver` checks that list on arrival and drops the message instead of persisting
  it — cheaper and tidier than writing a row and deleting it a moment later.
- Whatever the fork decides has to happen *before* the drop, since after it the evidence is gone.

## Out of scope

- Deleting anything from the Sent box. "Did we ask?" depends on it, and the user did not ask.
- Auto-deleting for senders whose thread was only marked read. The request is specifically about
  the ones marked for deletion.
