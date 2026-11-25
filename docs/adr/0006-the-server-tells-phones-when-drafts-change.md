# The server tells phones when drafts change, through Firebase Cloud Messaging

Claude's drafts ([ADR 0003](0003-claude-drafts-beans-and-recipes-for-the-user-to-add.md), [ADR 0004](0004-claude-drafts-tasting-notes-on-a-brew.md)) only reached the phone when the app synced, and the app syncs only while it is open. A user who asked Claude for a draft had to remember to open Brewprint to find it. The app now shows a notification for each draft, and tapping it opens the review.

After `draft_beans`, `draft_recipe`, `draft_tasting_notes` or `discard_draft` writes, `brewprint-mcp` sends a data-only Firebase Cloud Messaging message, "drafts changed", to each of the user's phones. The message carries no draft content. The phone syncs, as it would on opening, and posts or updates its notifications from its own copy of the drafts, so a notification always matches the review it opens. After the app adds, saves or discards a draft, it asks `brewprint-mcp` at `/drafts-changed` to send the same message, so the user's other phones clear that draft's notification.

Each phone keeps its FCM token in a new table, `devices`, one row per phone and account. Row-level security lets the app's own sessions write and delete their user's rows, and refuses every token with a `client_id` claim, so Claude can neither read nor change it. `brewprint-mcp` reads the tokens with the service role key, and signs FCM requests with a Firebase service account key held as a Supabase secret.

## Considered options

- **Poll for drafts in the background with WorkManager.** No new service, but Android runs periodic work no more often than every 15 minutes, and ColorOS delays or stops it further, so a draft could take an hour to show up after the conversation that made it.
- **Supabase Realtime.** Delivers changes only while the app's process is running, which is when the app already syncs.
- **Put the draft's details in the push.** Saves one fetch, but would send coffee names through Google's servers, and the notification could disagree with a draft corrected since.
- **A database trigger calling a separate Edge Function.** Catches every write to `drafts`, but adds `pg_net`, a second function and its secret, for writes that only `brewprint-mcp` and the app make.

## Consequences

- FCM is free, and the app now depends on Google Play services for these notifications. Without them, drafts still arrive when the app opens.
- The repository holds `app/google-services.json`. Like the Supabase publishable key, it identifies the project and isn't a secret.
- Sending the push is best effort. A failed or slow push never fails a tool call, and the draft waits in the app as before.
- Notifications are opt-in, through a "Drafts from Claude" switch in Settings, so Android's notification permission prompt can now appear from two places. Firebase gets no token for the phone until the switch is turned on, and turning it off deletes the token and its `devices` row.
- Signing out deletes the phone's `devices` row and cancels its draft notifications. A token FCM reports as no longer registered is deleted when a send fails with it.
- `devices` must exist before an app that registers tokens is released: apply `20261125120000_devices.sql` first, as with `drafts`. Until the `FIREBASE_SERVICE_ACCOUNT` secret is set, `brewprint-mcp` sends nothing.
