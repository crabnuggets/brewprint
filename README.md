# brewprint

## Claude plugin

Brewprint can connect to Claude, so Claude can read your beans, recipes and brews and help you taste and dial in. Claude can draft beans, recipes and a brew's tasting notes, which wait in the app until you add or save them; it can't change anything else. Your brews need to be synced: sign in with Google in the app's Settings first.

- **Claude app or claude.ai:** Settings → Connectors → Add custom connector, with the URL `https://ftwdmparhchrafsbmplv.supabase.co/functions/v1/brewprint-mcp`. To add the coaching skill too, zip the `plugins/brewprint/skills/brew-coach` folder and upload it as a skill in Claude's settings.
- **A Claude project for tasting:** paste [`docs/claude-project-instructions.md`](docs/claude-project-instructions.md) into the project's instructions, and turn on the Brewprint connector in its chats. It coaches tasting first and brewing second, so it replaces the skill there.
- **Claude Code:** `/plugin marketplace add crabnuggets/brewprint`, then `/plugin install brewprint@brewprint`.

Connecting opens a Brewprint page where you sign in with Google and allow access. Remove the connector in Claude to disconnect.
