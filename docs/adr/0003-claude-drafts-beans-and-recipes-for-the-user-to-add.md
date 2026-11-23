# Claude drafts beans and recipes for the user to add

Claude can now propose beans and recipes, for example from a roaster's product page, a photo of a bag or a recipe someone posted. Its MCP tools (`draft_beans`, `draft_recipe`, `discard_draft`) write to a separate table, `drafts`, which row-level security opens to any of the user's tokens, Claude's included. `records` stays read-only to Claude, as [ADR 0002](0002-claude-reads-brews-through-a-read-only-mcp-server.md) set out. The app fetches every draft on each sync and lists them in the Library under "Drafts from Claude". Reviewing one opens the usual beans or recipe form, filled in. "Add to Library" saves it as a library item with the draft's id, and the draft is then deleted from the account, as is a draft the user discards. Until then Claude can correct a draft by its id, in the same conversation or a later one.

Each draft holds the item's JSON exactly as the app saves it. The server checks a recipe with the app's own rules, so Claude learns of a mistake, such as pulses that don't add up to the brew water, while it can still fix it. `fixtures/draft-beans.json` and `fixtures/draft-recipe.json` are drafts as the server builds them, and the app's `McpContractTest` fails if the app couldn't add them.

## Considered options

- **Let Claude write beans and recipes to `records`, marked as drafts.** One table, but app versions without draft support would show Claude's guesses as real library items, and a mistake would reach the journal without the user seeing it.
- **Drafts as another kind in `records`.** Reuses incremental sync, but an app that pulled past a draft before it understood drafts would never fetch it again. Drafts are few and short-lived, so fetching them all each sync is simpler.
- **Claude gives the user the details to type in.** No server change, but it is the typing the user wanted to avoid.

## Consequences

- The app only syncs once this migration is applied: it reads `drafts` on every sync, so apply `20261121120000_drafts.sql` before releasing an app that does.
- If Claude corrects a draft after the user adds it on the phone but before the phone syncs, the correction is lost when the draft is deleted. The added item is what the user saw.
- A draft added or discarded offline waits to be deleted, like any change not yet uploaded, and signing out waits for it too.

## Amendment: the ratio comes from the pours

Recipes no longer take a ratio: the app and `drafts.ts` work it out from the dose and the pulses, so pulses no longer have to add up to dose × ratio. The synced `ratio` field stays, set on every save to the pours' ratio to 6 decimals, because app versions before this change check dose × ratio against the pours and would refuse the recipe otherwise. While those versions may still be installed, `draft_recipe` still accepts a `ratio` and checks the pours against it; a later change removes the argument once every phone is updated.

