# Stop taps in DispositionControls from deselecting the sender

## The problem

`SenderRow`'s `Card` carries `.clickable(onClick = onToggle)` so that tapping anywhere on a
sender toggles its selection. `DispositionControls` — the "Delete these messages" and "Block this
number" checkboxes — is rendered *inside* that card, and only its `Checkbox`es handle clicks.

The label text, the gap between the two rows, and the padding around them therefore fall through
to the card's handler. A near-miss on a checkbox does not toggle that setting; it **deselects the
whole sender**, which also hides the controls the user was aiming at. The two most likely
mis-taps produce the most destructive result.

## Approach

Consume the events rather than letting them reach the card, and take the opportunity to make the
misses useful instead of merely harmless:

- Each control row becomes clickable in its own right, toggling *its own* checkbox. A tap on the
  words "Delete these messages" then does what the user obviously meant, and consumes the event
  on the way.
- The container swallows anything left over — padding, the gap between rows — so no stray tap in
  that region reaches the card.

Both use `indication = null`, since the card already provides the visual affordance and a second
ripple inside it would suggest a separate button.

## Out of scope

- The `TextButton`s in the unsubscribed row (Mark read / Delete / Block number). Buttons already
  consume their clicks.
- The disabled `AssistChip` keyword badge. A tap there falls through to the card and toggles
  selection, which is the same thing tapping the row does — harmless, and arguably wanted.

## Verified

Reproduced before fixing, on the Android 12 emulator: with `15557654321` selected, a tap on the
words "Delete these messages" **deselected the sender** — checkbox cleared, controls gone. The
setting it was aimed at did not change.

After the fix, the same tap at the same coordinates leaves the row selected and flips the
setting. Four label taps across both controls produced zero accidental
deselections, and the defaults were toggled back to confirm both directions.

One incidental finding: because each toggle row is `fillMaxWidth`, a tap well to the right of a
label still lands inside that row and toggles it. That is the intended hit target rather than a
gap, so the container's swallow only has the vertical space between rows left to catch.

## Two follow-on changes

**The controls are now always visible, disabled until the sender is selected**, rather than
appearing on selection. Hiding them meant the row changed height on every tap and, more
importantly, that the defaults were invisible until the user had already committed to acting on
the sender — including a pre-ticked **Block** on one that ignored its own opt-out, which is the
single most consequential default in the app.

Disabled, the container deliberately *stops* swallowing taps: one should then do what tapping
anywhere else on the card does — select the sender, which is also what makes the controls usable.
So the same tap means "enable these" when they are off and "toggle this" when they are on, and
never means "deselect".

**Laid out horizontally** rather than stacked, which matters more now that they occupy the row
permanently. Each toggle wraps its own width instead of filling, or the first would swallow the
row.

The labels became `Delete` and `Block`, stating the *action* rather than the current setting.
The previous first label flipped between "Delete these messages" and "Keep them, just mark read",
which made a checkbox pair read as two different questions — the checkbox already carries the
state. An unticked "Delete" means the thread is kept and marked read, and Review's summary
spells that consequence out.

## Note on the shape of the bug

Worth recording because it generalises: a parent that makes its whole surface clickable turns
every non-interactive child into a hazard. The severity was inverted from what a stray tap
usually costs — a near-miss did not merely fail, it undid the selection *and* hid the controls
being aimed at, so the recovery cost more than the original action.
