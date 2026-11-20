# Local-first sync with Supabase, one row per item

The device's `brewprint.json` stays the source of truth, so the app keeps working offline and signed out. Signing in with Google (Credential Manager ID token, exchanged for a Supabase session) syncs beans, grinders, recipes, and brew records to a single Supabase table, `records`, with one row per item holding the item's existing JSON in `data`. Row-level security limits each user to their own rows.

## Considered options

- **One row per account holding the whole state.** The simplest, but two phones would overwrite each other's work.
- **A table and columns per entity.** Queryable on the server, but every model change would need a migration, and nothing reads the data on the server.

## Consequences

- A phone holds one account's data at a time. Signing out removes it after a full upload, and signing in to a different account replaces it, so accounts never merge. Either step is refused while the account still has changes to upload. Data made while signed out joins the next account signed in to.
- Deleting a brew record uploads a tombstone (`deleted = true`, `data = null`); a hard delete would let another phone bring it back.
- The server stamps `updated_at`, so device clocks never decide which change is newer. Pulls overlap by a minute to catch changes that committed late.
- supabase-kt is pinned to 3.1.x, the newest line built with Kotlin 2.1. Upgrading it means upgrading Kotlin first.
