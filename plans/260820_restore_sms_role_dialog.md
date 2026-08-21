# Make "Restore SMS app" use the same system dialog as "Become the default SMS app"

## What was asked for

The two role buttons behave differently today:

- **"Become the default SMS app"** (setup screen) fires
  `RoleManager.createRequestRoleIntent(ROLE_SMS)` — a focused system dialog listing the
  eligible SMS apps with radio buttons and a "Set as default" action.
- **"Restore SMS app"** (top bar) fires `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` — the
  general "Default apps" list, from which the user has to find and drill into the SMS entry.

The handback should land the user on the same dialog as the grant, not a settings hierarchy.

## The catch

`createRequestRoleIntent` is very likely **not** usable as-is for the handback. AOSP's
`RequestRoleActivity` short-circuits when the calling package already holds the role — it sets
`RESULT_OK` and finishes without showing any UI. Since "Restore SMS app" is only reachable
*while* the app holds the role, wiring it to the same intent would plausibly make the button do
nothing at all — a worse outcome than today's extra taps.

So the intent that produces that picker for an app that already holds the role has to be
established empirically before anything is wired up. Candidates, in preference order:

1. `RoleManager.ACTION_MANAGE_DEFAULT_APP` + `EXTRA_ROLE_NAME = ROLE_SMS` — opens the picker for
   one specific role. May be `@SystemApi`; if so it is unusable from a normal app.
2. The existing `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (status quo fallback).

**Open question, to be answered on the emulator, not from memory:** does
`createRequestRoleIntent` show anything when the role is already held, and does
`ACTION_MANAGE_DEFAULT_APP` resolve for a non-system app?

## Outcome: not achievable, behaviour left as-is

All three routes to the role-specific SMS picker are closed to a normal app. Each was tried
against the Android 16 emulator with the app holding the role, and each fails differently:

| Route | Result |
|---|---|
| `createRequestRoleIntent` (what was asked for) | `RequestRoleActivity: Application is already a role holder` — returns RESULT_OK and finishes, no UI |
| `ACTION_MANAGE_DEFAULT_APP` + `EXTRA_ROLE_NAME` | Opens exactly the right picker, but `SecurityException: requires android.permission.MANAGE_ROLE_HOLDERS` — privileged. **Crashed the app** in testing |
| `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` | `PermissionPolicyService: Action Removed`, start result 102 — silently aborted |

The second is the instructive one: it resolves fine through `PackageManager`, so a
`resolveActivity()` guard passes and the failure only appears at start time. The third is the
dangerous one: it throws nothing and shows nothing, so a try/catch fallback chain never fires —
the button would have looked wired up while doing nothing at all.

Two dead ends worth recording separately, hit while probing:

- `DefaultAppActivity` reads `android.intent.extra.ROLE_NAME`, **not**
  `android.app.role.extra.ROLE_NAME`; the wrong key yields `Unknown role: null`.
- `<queries><intent>` package-visibility filtering covers activities and services but **not**
  broadcast receivers, so `queryBroadcastReceivers` for `SMS_DELIVER` returns nothing on
  Android 11+ regardless of what `<queries>` declares. Probing the `smsto:` send activity works.

So `handBackIntent()` keeps using `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`. The shipped
change is **documentation only**: a comment on `handBackIntent` recording all three failures so
the next reader (or a future me) does not retry them and ship a dead button. The plan file said
up front that shipping nothing was preferable to shipping a dead button, and that is what
happened.

If this matters enough later, the only real alternative is a UI change rather than an intent
change — e.g. spelling out "Settings > Default apps > SMS app" next to the button.

## Out of scope

- Any change to the grant path ("Become the default SMS app") — it already works.
- Actually assigning the role to another app programmatically. Android has no such API; the
  handback is necessarily a user action in system UI.

## Note

Carries an unrelated committed-later fix to `.claude/skills/send-spam/scripts/send-spam.sh`
(it now verifies which app holds the SMS role before claiming the receiver persisted anything),
at the user's request to bring it onto this branch rather than trunk.
