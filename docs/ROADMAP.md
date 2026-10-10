# Dogmatix+ — roadmap to the community launch

Updated: 10 October 2026. Published baseline: [2.7.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.7.0).

The community launch should make it easy to find a game, prepare it, play it and recover safely when a download, storage device or save transfer fails. Existing features already include controller navigation, downloads, collections, offline collection downloads, emulator selection, game checks, RomM, save sync, encrypted backups and profiles. The milestones below extend those features rather than presenting them as new.

## 2.8.0 — connected workflows and more content

Status: implementation and verification in progress. This is the next regular release, Android build 40. Features move to “released” only after the public APK and its upgrade have been verified.

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

## 2.9.0 — prove the complete journey on hardware

Status: planned. The priority is integration, performance and usability measured on real devices.

- Test a touch device and an Android handheld with a physical controller: first setup → select source → download → emulator launch → return → save sync → recover. Complete the controller route without requiring touch.
- Remove and reconnect a real SD card during a queued download, transfer and library refresh. Preserve completed games, partial downloads, package receipts and personal data; explain the next action.
- Test sleep, resume, app restart, interrupted downloads and updates on real hardware, including Android background restrictions.
- Add a reviewed package-receipt export/restore workflow for a reinstall or a new device. Current app updates and verified library/SD moves preserve receipts; the general backup retains settings and journals but does not export package receipts or game bytes. Restoring receipts must require a selected local folder and verified file contents.
- Test against an actual RomM and WebDAV server: authentication, certificate changes, network interruption, conflicting saves, backup restoration and retry behavior. Record server versions and supported configurations.
- Measure search, collection rendering, readiness and queue responsiveness with 10,000 and 50,000 indexed files. Record hardware, dataset and timings before making speed claims. Fix measured bottlenecks and avoid repeating whole-library hashing on each screen open.
- Review all new screens with long translations, large text, screen readers, portrait/landscape and controller focus. Preserve consistent back/cancel behavior.
- Capture current screenshots from actual UI using freely distributed or user-owned test content; identify simulated data in any promotional image.

## 3.0.0 — community launch on Reddit

Status: planned. Publication date is determined by the launch checks, not reserved in advance. Public numbering continues 2.8.0 → 2.9.0 → 3.0.0; there is no 2.10.0.

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
