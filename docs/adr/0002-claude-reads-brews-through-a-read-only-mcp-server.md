# Claude reads brews through a read-only MCP server on Supabase

Claude reaches a user's beans, grinders, recipes and brew records through an MCP server, `supabase/functions/brewprint-mcp`, an Edge Function next to the `records` table. Claude signs in through Supabase Auth's OAuth 2.1 server as the user, approves access on a consent page served by GitHub Pages (`web/oauth/consent.html`), and row-level security limits it to that user's rows. Claude can only read: the policies refuse inserts and updates from any token with a `client_id` claim, which Supabase puts on tokens it issues to OAuth clients and not on the app's own sessions.

ADR 0001 kept each item's JSON in `records.data` partly because nothing read it on the server. The MCP server now reads it, but still only as whole items, so the JSON column stays. `fixtures/brew-record.json` holds a brew as the app writes it, and the app's `McpContractTest` fails when the Kotlin model and that fixture disagree, so a model change can't silently break the server.

## Considered options

- **Export from the app and attach the file to Claude.** No server, but every question would start with a fresh export, and nothing works from the Claude app on its own.
- **Let Claude write, such as saving a suggested recipe.** Useful later, but a write from outside the app would have to produce items the app accepts and merges. Starting read-only keeps a mistake from reaching anyone's journal.
- **A Claude feature inside the app (Claude API).** Needs a server to hold an API key and pays per token for every user; connecting the user's own Claude costs Brewprint nothing.

## Consequences

- The MCP server accepts any valid access token for a user of this project, from Claude or from the app, because Supabase's OAuth server doesn't yet bind a token's audience to the resource it was issued for. That holds only while this project's auth server protects nothing but Brewprint's records. Before another service trusts its tokens, bind audiences and check them here.
- The Edge Function runs with gateway JWT checks off (`verify_jwt = false`) and checks the bearer token itself, so it can answer MCP clients with the `WWW-Authenticate` hint they use to find the OAuth server.
- Dynamic client registration is on, so any MCP client can ask to connect. The consent page, which names the client and where it returns to, is what stops one the user didn't start.
- The consent page lives on the Site URL (Supabase Auth → URL Configuration). The app signs in with Google ID tokens, so nothing in the app depends on that URL.
