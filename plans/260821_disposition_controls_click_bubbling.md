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

## Verify

Reproduce first, then fix: select a sender, tap the *label text* rather than the checkbox, and
confirm the row stays selected and the setting flips.
