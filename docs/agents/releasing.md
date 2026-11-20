# Releasing

A release is a git tag on `main`. Pushing a tag such as `v0.2.0` runs `.github/workflows/release.yml`. The workflow checks the tag, runs the unit tests, builds an APK signed with the release keystore from the repository's Actions secrets, and publishes it as a GitHub Release, with notes listing the PRs merged since the previous tag.

## Versions

Tags are `vMAJOR.MINOR.PATCH`, for example `v0.2.0`. Bump one part and reset the parts after it to 0:

- **PATCH** (`v0.2.0` → `v0.2.1`): fixes only, nothing new to learn.
- **MINOR** (`v0.2.1` → `v0.3.0`): a new feature or a change people will notice.
- **MAJOR** (`v0.3.0` → `v1.0.0`): a deliberate milestone, or a change that breaks existing data or sync between app versions.

Brewprint stays on `0.x` until the owner calls it 1.0.

A build for testing before a release adds `-beta.N`: `v0.3.0-beta.1`, then `v0.3.0-beta.2`, then `v0.3.0`. Beta releases are marked as pre-releases on GitHub.

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

Install the APK from the GitHub Release over the previous release on a phone. Check that it updates without uninstalling, and that "Sign in with Google" still works. Release builds are signed with a different key from debug builds, so a debug build must be uninstalled before a release can be installed, which deletes its local data. Sign in on the debug build and let it sync first.
