# The app updates itself from GitHub Releases

Brewprint isn't on Google Play. Each release is an APK on the repository's GitHub Releases, so until now an update meant finding the release page and installing by hand. Now the app checks GitHub's releases API (`/repos/crabnuggets/brewprint/releases`, public, no token) once each time it opens, and Settings → Version says whether a newer release is out. "Update" downloads the release's APK to the app's cache, checks its size and the SHA-256 that GitHub lists for it, and hands it to Android's `PackageInstaller`. Android asks the user to confirm and installs it over the running app, which closes.

A release counts as a beta when GitHub marks it as a pre-release or its tag has `-beta.N`. Betas are offered only with "Beta releases" on, a setting kept on the phone. Versions compare as the tags do: `1.2.0-beta.1` comes after `1.1.0` and before `1.2.0`.

## Considered options

- **Leave updates to the user.** No code and no new permission, but nobody learns a release is out, and phones drift apart on versions that sync with each other.
- **A separate updater app, such as Obtainium.** Nothing to build, but every user has to install and set it up.
- **Google Play and its in-app updates API.** The usual path, but it means publishing on Play, which Brewprint doesn't. Play also forbids an app updating itself this way, so moving to Play means replacing this.
- **An update notification.** Not asked for yet; Settings is where the user looks for the version.

## Consequences

- The app holds `REQUEST_INSTALL_PACKAGES`. Android asks the user once to allow Brewprint to install apps; "Update" opens that setting first when needed.
- Android installs an update only when it is signed with the same key and has a higher version code. The release workflow already does both, so `release.yml` and its asset name need no change. A release must keep exactly one `.apk` asset, or the app skips it.
- Debug builds don't check: they are signed with another key, so Android would refuse a release over them.
- "Update" waits while a brew's timer is running, since installing closes the app.
- GitHub allows 60 unauthenticated API requests an hour per address. One check per launch, plus one when "Beta releases" changes or the user checks again, stays well within it.
- A user on a beta who turns "Beta releases" off gets the next full release once it is newer than their beta; the app never offers an older version.
