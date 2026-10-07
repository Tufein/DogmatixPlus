# Public release numbering

Every published GitHub release is a regular release, including releases originally labelled beta or pre-release. Versions advance once per release:

`1.0.0 → 1.1.0 → … → 1.9.0 → 2.0.0 → 2.1.0 → …`

The last component stays zero. There are no beta, alpha or other suffixes. The release workflow checks the complete published sequence before building and refuses skipped numbers, pre-releases and a non-increasing Android `versionCode`.

## Historical migration

The 12 releases published before this change were numbered by their original publication dates. The first remains 1.0.0; the last (originally 8.2.0 beta 2) becomes 2.1.0. The next feature release is 2.2.0, Android build 34. [renumber-map.json](renumber-map.json) records each source commit and original APK identity.

Release IDs, APK assets, checksums and publication dates are preserved. Only GitHub release labels/tags, regular-release status and explanatory notes change. Original tag objects remain under `archive/original-version/<old-tag>`, including tags which never had a published release. Those unpublished tags are archived rather than turned into extra releases. Original release-note files and the [legacy changelog](legacy-changelog.md) are retained.

Existing APKs keep their embedded version names; renaming a GitHub release cannot rewrite a signed APK. Old tag-based release/download links must be replaced by the new canonical links. For example, the former beta 2 is now [2.1.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.1.0).

## Installing across the rename

Apps installed under the old 8.x numbering cannot discover a lower public label automatically. Install [the signed release APK for 2.2.0](https://github.com/Tufein/DogmatixPlus/releases/download/v2.2.0/DogmatixPlus-release.apk) manually once. The application ID and signing certificate stay the same, and Android build 34 is higher than all historical builds, so it installs as an update with existing data intact.

From 2.2.0 onward the in-app update check compares the actual Android build number published in release metadata, not just the visible label. Every release includes a comment such as:

`<!-- dogmatix-release: {"versionCode":34,"versionName":"2.2.0"} -->`

Historical metadata records the unchanged original APK version. The newest release is selected by publication date.
