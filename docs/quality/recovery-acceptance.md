# Recovery acceptance

The new file operations preserve conflicting targets, stage new copies, re-read SHA-256, and keep originals until their copies are verified. A library move journals its files before switching folders, then cleans up; an interrupted operation remains visible in Tools → Trash and recovery.

## Automated checks

- JVM: equal-size content conflicts; damaged copies; interruption and stream closure; game artifacts versus saves/metadata; safe disc references; fixed versions across consoles, sequels and discs; bulk selection.
- Android: stream and rename behavior, conflicting targets, failed reads, changed sources and durable journal recreation.
- Android SAF test provider: equal-size conflicts, source read failure, trash/restore at the original path with a save sibling, changed source, and visibility of recovery directories in strict deletion checks.
- CI: unit tests, debug build, lint against the unmodified 8.1.0 baseline, and Android integration tests. Public releases additionally require the fixed signing key and a pinned certificate fingerprint.

## Local results (2026-10-07)

- 1,164 JVM tests: zero failures/errors; one RomM test skipped without live server credentials.
- 10 Android integration tests passed on Android 15 / API 35 (arm64 emulator), including actual ContentResolver queries, streams, rename, conflict protection and trash restoration with a save sibling.
- Debug APK and Android test APK built successfully. Android lint passed against the unmodified 8.1.0 baseline; this does not mean that inherited lint findings have all been fixed.
- App startup, navigation to recovery, and the recovery layout were checked at 1920×1080 and at 720×1280 with 130% Android font size.

## Manual release checks

These remain distinct from automated success:

- Internal storage and a real removable SD card; full disk, card removal, provider refusing rename, revoked grants, process termination during copy/switch/cleanup/restore.
- Upgrade an existing signed APK and retain the database/settings.
- Two real RomM/WebDAV clients: changed saves, conflicts, restore, interrupted upload and backup rollback.
- Gamepad and TV remote: focus, back, confirmation cancellation; real emulator handlers receiving content URIs; small second display and large font.
- All supported languages, especially long German/French labels, on both narrow and landscape layouts.

## Behaviors and limits

- A conflicting destination is refused; it is never silently overwritten. Resolve the conflict or choose a different destination.
- A provider that refuses a safe rename is refused. The original remains available.
- The trash uses space on the original volume. Retention cleanup is opt-in and runs when the app opens, and during an enabled smart-storage weekly run.
- Restore uses the original folder and refuses a conflicting restored file. If the grant or original volume is unavailable, it keeps the recovery copy for another attempt.
- Play lists applications declaring support for ACTION_VIEW with a binary content URI. Applications requiring their own proprietary launch protocol are not advertised as compatible.
- A fixed version wins automatic selection when present in the candidate set; source reliability still chooses between sources of the same file. Missing versions use the normal ranking.
- DAT results come from a completed DAT check; a filename's good-dump marker is explicitly a claim, not a hash verification. RetroAchievements reports hash versus title matching separately.
- Exported action reports contain only time, kind, phase and file count. They exclude paths, server URLs, game names and exception messages.
