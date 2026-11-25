# Releasing

A release is a git tag on `main`. Pushing a tag such as `v0.2.0` runs `.github/workflows/release.yml`. The workflow checks the tag, runs the unit tests, builds an APK signed with the release keystore from the repository's Actions secrets, and publishes it as a GitHub Release, with notes listing the PRs merged since the previous tag.

## Versions

Tags are `vMAJOR.MINOR.PATCH`, for example `v0.2.0`. Bump one part and reset the parts after it to 0:

- **PATCH** (`v0.2.0` → `v0.2.1`): fixes only, nothing new to learn.
- **MINOR** (`v0.2.1` → `v0.3.0`): a new feature or a change people will notice.
- **MAJOR** (`v0.3.0` → `v1.0.0`): a deliberate milestone, or a change that breaks existing data or sync between app versions.

Brewprint stays on `0.x` until the owner calls it 1.0.

A build for testing before a release adds `-beta.N`: `v0.3.0-beta.1`, then `v0.3.0-beta.2`, then `v0.3.0`. Beta releases are marked as pre-releases on GitHub.

The app finds releases through GitHub's releases API and offers the newest one in Settings → Version ([ADR 0005](../adr/0005-the-app-updates-itself-from-github-releases.md)). It offers a pre-release, or a tag with `-beta.N`, only to phones with "Beta releases" on. It installs a release's one `.apk` asset, after checking its size and the SHA-256 GitHub lists, so keep exactly one APK on each release and don't edit a published release's assets.

The tag sets both numbers in the APK. `versionName` is the tag without its `v`. `versionCode` is the release workflow's run number, which only goes up, so each release installs over the previous one. Local builds are `0.1.0` with code 1.

## Labeling every PR

Every PR carries exactly one release label, set by whoever opens it, because that is when the kind of change is clearest:

- `release: fix`: fixes, and changes that don't reach users, such as docs, CI, tests and refactors.
- `release: feature`: a new feature or a change people will notice.
- `release: breaking`: breaks existing data, or sync between app versions.

Add it when filing the PR (`gh pr create --label "release: fix"`) or right after. The "Release label" check fails until the PR has exactly one. If the PR's scope changes during review, change the label too.

## Choosing the next version

1. Find the newest tag: `git fetch origin --tags && git tag -l 'v*' --sort=-v:refname | head -1`.
2. List the PRs merged since then: `git log <tag>..origin/main --format=%s`. Each squash commit ends in its PR number.
3. Read their labels: `gh pr list --state merged --base main --limit 200 --search "merged:>=<tag's date, YYYY-MM-DD>" --json number,title,labels`. Keep only the PRs from step 2.
4. The highest label wins: any `release: breaking` bumps MAJOR, otherwise any `release: feature` bumps MINOR, otherwise PATCH. Before any tag exists, the first release is `v0.2.0`.
5. A PR without a label: read it, judge it, and say so in the proposal.
6. Propose the version to the owner with the evidence, and wait for the go-ahead:
   > v0.3.0: #21 Add CSV export (feature); #22, #23 (fix)

## Rules

- **Only the owner decides to release.** Never push a tag unless the owner asked for that exact release. When a release seems due, propose the version and give the reason in one line, for example "v0.3.0: adds CSV export".
- **Tag `main`, after the PRs are merged:** `git fetch origin && git tag vX.Y.Z origin/main && git push origin vX.Y.Z`.
- **Never move, delete, or reuse a released tag.** If a release is broken, fix it on `main` and release the next PATCH. A failed workflow run can be re-run; it keeps its number.
- **Don't edit `versionCode` or `versionName` in `app/build.gradle.kts`.** The workflow sets them.
- **Don't rename `.github/workflows/release.yml`.** GitHub restarts run numbers for a renamed workflow, so `versionCode` would drop and updates would stop installing. If it must be renamed, first derive `versionCode` from the version instead, for example `MAJOR × 10000 + MINOR × 100 + PATCH`.

The workflow rejects a tag that isn't in the format above, isn't on `main`, or isn't higher than the newest existing tag. Nothing is built or published then. Push a correct tag; delete the rejected one with `git push origin :refs/tags/<tag>`.

## After a release

On a phone with the previous release, open Settings → Version, tap "Update", and confirm Android's dialog. Check that it updates without uninstalling, that Settings then shows the new version as "Up to date", and that "Sign in with Google" still works. For a beta, turn on "Beta releases" first. Release builds are signed with a different key from debug builds, so a debug build must be uninstalled before a release can be installed, which deletes its local data. Sign in on the debug build and let it sync first.

## The Claude plugin and its server

The Claude plugin is not part of an app release and has no tag. Three parts ship when their PR merges to `main`:

- **MCP server** (`supabase/functions/brewprint-mcp`): `.github/workflows/deploy-mcp.yml` deploys it to the Supabase project. It needs the `SUPABASE_ACCESS_TOKEN` Actions secret, a Supabase personal access token.
- **Consent page** (`web/`): `.github/workflows/consent-page.yml` publishes it to GitHub Pages at `https://crabnuggets.github.io/brewprint/oauth/consent.html`. Pages must use "GitHub Actions" as its source.
- **Plugin** (`plugins/brewprint`, listed in `.claude-plugin/marketplace.json`): Claude Code reads it from `main`. Bump `version` in `plugins/brewprint/.claude-plugin/plugin.json` whenever the plugin's files change, using the version rules above; installed copies only update when it changes.

Database migrations in `supabase/migrations/` are not applied by any workflow. The owner applies them, with `supabase link --project-ref ftwdmparhchrafsbmplv` and then `supabase db push`, before merging a PR whose code depends on them.

Label these PRs like any other: `release: feature` for something people will notice, such as a new tool, and `release: fix` otherwise.

### One-time setup, in the Supabase dashboard

1. Authentication → URL Configuration: Site URL `https://crabnuggets.github.io/brewprint`.
2. Authentication → OAuth Server: turn it on, set the authorization path to `/oauth/consent.html`, and allow dynamic client registration.
3. In Google Cloud, add `https://crabnuggets.github.io` to the Web OAuth client's authorized JavaScript origins, so the consent page can sign in with Google.
4. Edge Functions → Secrets: `FIREBASE_SERVICE_ACCOUNT`, the whole JSON key of a service account in the Firebase project (Firebase console → Project settings → Service accounts → Generate new private key). `brewprint-mcp` uses it to tell phones their drafts changed ([ADR 0006](../adr/0006-the-server-tells-phones-when-drafts-change.md)); without it, drafts still wait in the app. The app's `app/google-services.json` names the same Firebase project.
