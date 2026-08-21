# Fix lintDebug, and clear two pieces of drift

## What was asked for

Three small things that accumulated and were flagged but deliberately not fixed mid-merge:

1. **`./gradlew lintDebug` fails** with two `MissingPermission` errors, at
   `BulkReplyService.kt:229` and `:247`. It has failed since the initial commit, on `main`, so
   nothing can currently be validated by lint.
2. **`README.md` says "28 JVM tests"** — it is 42.
3. **`plans/master.md` is orphaned** — written when the trunk was called `master`, left behind
   when the branch was renamed to `main`.

## The lint failure, and why it is a real fix rather than a suppression

Both errors are the same call: `NotificationManagerCompat.notify` inside `runCatching { }`.
`runCatching` *does* catch the `SecurityException` a missing `POST_NOTIFICATIONS` would throw, so
the code is safe — but lint cannot see through it and reports the call as unguarded.

The telling detail is that `Notifications.notifySafely` makes the same call and is **not**
flagged, because it uses an explicit `try { } catch (_: SecurityException)`. So the codebase
already contains the shape lint accepts; the two offending sites just duplicate it badly.

The fix is therefore to delete the duplication rather than to suppress the warning: give
`Notifications` a `notifyBatchProgress` that builds the batch notification and posts it through
the existing `notifySafely`, and have `BulkReplyService` call that. `BulkReplyService` ends up
with no direct `notify` call at all, the one remaining call site is the guarded one, and the
"build a batch notification and post it to `BATCH_NOTIFICATION_ID`" logic stops being written
out in three places.

Deliberately **not** doing either of the tempting alternatives:

- `@SuppressLint("MissingPermission")` — silences the report without improving anything, and
  leaves the duplication.
- A `lint-baseline.xml` — hides the two errors *and* every future one, which is worse than the
  status quo: the point of fixing this is to make `lintDebug` usable as a check again.

## Outcome

`./gradlew lintDebug` **exits 0**, with zero errors remaining — no suppression, no baseline. The
two errors were not masking anything further.

`Notifications.notifyBatchProgress` now builds and posts the batch notification through
`notifySafely`, and `BulkReplyService` has no direct `NotificationManagerCompat` reference at all
(the import is gone). Three copies of "build a batch notification and post it to
`BATCH_NOTIFICATION_ID`" collapsed to one.

### README

The stale count was **removed rather than corrected**. Writing "42 JVM tests" only resets the
clock on the same drift — the number is not what a reader needs, and it is wrong again the next
time a test is added. The line now names the command and what it is for, and `lintDebug` was
added alongside it now that it passes and is worth running.

`plans/main.md` still says "28 JVM unit tests"; that one is left alone deliberately. It is a
record of what the initial commit contained, and it was accurate then.

### Verified

- `testDebugUnitTest` 42/0/0, `assembleDebug` clean, `lintDebug` clean — all exit 0.
- On the Android 16 emulator, a dry-run batch of 11 senders ran to completion with the foreground
  service posting through the refactored path: `foregroundNoti` on channel `batch_progress`
  during the run, and the notification updating to "Opt-out batch finished" afterwards. That last
  post is the `finish()` call site, one of the two that changed, so both are exercised. No
  crashes.

### Answered

`plans/master.md` was **renamed** to `plans/main.md` rather than deleted, with a line noting why
the old name existed. The reasoning behind the initial implementation is worth keeping; only the
filename was wrong.

## Out of scope

- Lint *warnings* (21 of them at last count). Only the errors block the task.
- Wiring lint into `/merge`'s check list; that is a workflow change, not this one.

