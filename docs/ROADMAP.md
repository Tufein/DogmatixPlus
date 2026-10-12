# Dogmatix+ — roadmap to the community launch

Updated: 11 October 2026. Verified public release: [2.9.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.9.0).

The community launch should make it easy to find a game, prepare it, play it and recover safely when a download, storage device or save transfer fails. Existing features already include controller navigation, downloads, collections, offline collection downloads, emulator selection, game checks, RomM, save sync, encrypted backups and profiles. The milestones below extend those features rather than presenting them as new.

## 2.8.0 — connected workflows and more content

Status: released. The public production and test APKs, checksums, signing continuity and updates from 2.7.0 have been verified. Android build 40. See the [acceptance record](quality/v2.8.0-acceptance.md) for actual results and the remaining hardware/server checks.

| Work item | Result for the player | Acceptance requirement |
|---|---|---|
| Optional romset catalog | Select content collections for 23 systems without replacing personal sources. | Directory sources resolve and parse; counts are dated snapshots, not promised game counts. Adding twice is idempotent. Disabled sources, custom names, favorites and indexed files survive. |
| Durable game packages | Extracted games retain the relationship between discs, tracks and subfolders after restart. | Source/console/root identities are isolated. Corrupt receipts never grant files. Missing storage retains receipts. Relative descriptors resolve safely. Existing conservative removal rules remain. |
| Offline readiness | See which collection games have their files, discs, BIOS and emulator ready. | Per-game results have repair links. An unavailable folder, incomplete package or inaccessible emulator never reports ready. Profile restrictions apply. |
| Guided recovery | Find trash, interrupted moves, replacement recovery copies and save backups in one place. | Preview the original location and affected files. Recheck content before restoring. Protect newer files. Bound scanning and filter by profile. |
| Storage reconnect | Understand why a selected folder is unavailable and explicitly resume its affected downloads after it returns. | Work against the exact selected folder. Keep partial bytes. Resume only after a write/read probe and a fresh identity check. Never resume an unrelated user pause. |
| Personal game journal | Keep notes and user-selected screenshots or manuals per game and profile. | Bound input and attachment sizes; use selected documents only. Notes are included in backups. Restored attachment references that lack access ask the user to select them again. |
| Guided device handoff | Review and upload ordinary game saves before continuing on another device. | Fresh per-game preview, conflict checks, a 16 MiB handoff limit per ordinary save and existing verified backups. Failures block “ready”. Save-state portability is not assumed. |

The catalog points to Libretro-hosted content collections, including homebrew, demos and test software. It does not promise complete commercial ROM libraries. Personal HTTP, torrent and RomM sources remain supported. Initial catalog verification: [Libretro content](https://github.com/libretro/libretro-content), [directory listings](https://buildbot.libretro.com/assets/cores/). Individual files and availability may change; users scan the current source before downloading.

## 2.9.0 — portable package records and large-library preparation

Status: released, Android build 41. Both public APKs, checksums, signing continuity and updates from 2.8.0 are verified in the [acceptance record](quality/v2.9.0-acceptance.md).

Completed:

- Portable package-record export/readback and restoration against configured roots and original sources, with fresh hashes, conflict protection, profile checks, multi-root confirmation and idempotent registration. Game/save bytes remain separate from metadata exports.
- Offline collection preparation matches relevant games and batches tags. The 10,000/50,000-row backend harness checks search, collection pages, package matches and queue summaries; measurements and dataset details are recorded without claiming Thor UI performance.
- Seven translations, large-text/controller package-screen tests, full French navigation labels on a narrow screen and actual labeled emulator screenshots.
- All 194 Android tests passed on Android 13/15 ARM and Android 15 x86_64; signed public updates preserve app data, journals, backups and private package records.

Physical launch checks continue under 3.0.0: the AYN Thor journey, both displays, actual controller, sleep/resume, SD-card interruptions, accessibility and measured device responsiveness. The owner has no RomM installation. Live server combinations remain unverified until their versions and results can be recorded. Use the [launch matrix](quality/community-launch-matrix.md) and [Thor test guide](guides/ayn-thor-test.md).

## 3.0.0 — community launch on Reddit

Status: the 3.0.0 software release is published (build 42). The community launch itself is not done: the physical and server checks below have no recorded results and the Reddit post has not been made. Public numbering continues 2.8.0 → 2.9.0 → 3.0.0; there is no 2.10.0.

What 3.0.0 adds on top of 2.9.0 ([release notes](release-notes/v3.0.0.md), [acceptance](quality/v3.0.0-acceptance.md)):

- Up next list per profile with explained suggestions (implemented; unit-tested; not verified by a person on a handheld).
- Library repair with previews and Trash-backed removals (implemented; same status).
- Report a problem builder for GitHub/Reddit feedback (implemented; same status).
- Action-history fixes from the 2.4 review.

The launch package includes:

1. A signed, verified public APK and a separate stable-signed test APK, each with checksums and an exact source commit.
2. A tested upgrade from the latest regular release that retains sources, profiles, favorites, collections, journals and backup records. Explain the one-time older debug APK signing transition.
3. A concise setup guide, supported integrations, storage/recovery guide and clear limitations.
4. A dated test matrix with results, open issues and the hardware/server combinations actually tested. No open issue may threaten game/save loss or block the documented primary journey.
5. A community post that separates current capabilities, measured checks and future plans, with canonical GitHub download links.
6. A feedback template that asks for app version, device/Android version, integration version and reproduction steps. Shared diagnostics must exclude tokens, passwords and private source details.

Release exit checks: no known data-loss or profile-isolation regression; targeted and full automated suites pass; builds and release identity checks pass; hardware/server checks above are recorded; screenshots and claims match the released build. A passing emulator suite does not substitute for real SD-card or server testing.

## Further work after the community launch

- Full multi-file transfer to RomM or a NAS, preserving folder structure and validating the complete package. Research the supported transport and recovery before implementation. [RomM upload documentation](https://docs.romm.app/5.3.0/using/uploads/) currently directs multi-file games to direct file transfer rather than the web uploader.
- Verified package export with a preview of files, space requirements and destination; keep shared files safe and avoid bundling saves or unrelated private documents.
- Expand the reviewed source catalog based on community requests, keeping provider attribution, console mapping, availability checks and additive imports.
- Extend device handoff only for emulator/save combinations that have been tested; show compatibility and a recovery path before adding automation.

## How scope changes

Record each item as planned, implemented, verified or released, with a link to its proof. A feature is implemented when the UI and underlying behavior work together; it is verified when its failure paths and persistence have passed the stated checks. Unsupported integration work remains planned even if its local building blocks exist.

The [Reddit draft](community/reddit-launch-draft.md) is prepared for the community launch. It must be updated with the actual launch version and recorded test results before posting.
