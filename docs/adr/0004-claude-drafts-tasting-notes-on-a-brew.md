# Claude drafts tasting notes on a brew for the user to save

Claude helps the user taste a cup in conversation, and can now write down what they said. Its MCP tool `draft_tasting_notes` writes a row to `drafts` with kind `journals`, the brew's id as its id, and the brew's journal as Claude proposes it as its data. `records` stays read-only to Claude, as [ADR 0002](0002-claude-reads-brews-through-a-read-only-mcp-server.md) set out. The app fetches these drafts with the beans and recipe drafts on each sync and shows them on their brew as "tasting notes from Claude". Saving them, after any edits, sets the brew's journal and deletes the draft from the account, as does discarding them.

A brew has at most one such draft, because its id is the brew's. Claude corrects one by drafting again for the same brew, and the server builds each draft from the waiting draft, or else the brew's saved notes, with the fields Claude gives replaced, so notes the user already wrote are kept unless Claude clears them. Choices (sweetness, acidity, bitterness, body, finish length, flavor and mouthfeel tags) take only the options the app's form offers, so the user can see and change every one. `fixtures/draft-journal.json` is a draft as the server builds it, and the app's `McpContractTest` fails if the app couldn't save it as it is.

## Considered options

- **Let Claude write the brew's journal in `records`.** No review step, but a guess or a misheard answer would reach the journal unseen, and Claude's write could overwrite notes the user was editing on the phone.
- **Claude gives the user the notes to type in.** No server change, and what the brew coach did before, but it is the typing the user wanted to avoid.
- **A separate table for tasting notes drafts.** Clearer types, but another table, policy and fetch for the same lifecycle the drafts table already has.

## Consequences

- `20261123120000_tasting_notes_drafts.sql` widens the drafts' kind check; `draft_tasting_notes` fails until it is applied. App versions without this change ignore drafts of a kind they don't know, so the notes wait until the user updates.
- If Claude drafts notes again after the user saves or discards them on the phone but before the phone syncs, the phone's deletion removes the new draft too.
- Saving Claude's notes replaces any unsaved note edits for that brew, which the user didn't see in the review.
