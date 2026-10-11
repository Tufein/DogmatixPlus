# Community launch test matrix

Updated: 11 October 2026. Target: AYN Thor, Android 13. The owner has not installed RomM yet; no live server version is recorded. These are target details, not a completed hardware test.

## Automated evidence

| Area | Setup / scope | Recorded result |
|---|---|---|
| Existing 2.8.0 checks | Android 15 ARM and x86_64 emulators; published APK upgrades | Passed; [2.8.0 acceptance](v2.8.0-acceptance.md). |
| Package transfer format | Strict JSON/UTF-8, exact layout, duplicate identities and portable roots | Five new unit tests passed. |
| Package export and registration | Room, DataStore and a real Android documents-provider fixture on isolated Android 13/15 ARM | Eleven new integration tests passed: multi-root export/registration, export/readback, receipt-loss recovery, idempotent repeat, wrong/missing folders, same-size mutation, corrupt existing records, inaccessible bytes, source mismatch, changed profiles/restrictions, duplicate-source hidden tags and write-failure rollback. |
| Large library | Synthetic 10,000 / 50,000 indexed rows, two tags per row, 5,000 collection members | Harness passed on Android 15 ARM. At 50k rows, search-50 median 44.67 ms, collection-page-100 median 30.91 ms; matching 2,000 packages used ten SELECTs. The old 50,000 individual tag SELECTs took 11.32 s; 56 bounded tag SELECTs had a 278.59 ms median. Backend timings do not establish UI frame rate or Thor performance. |
| New screen | Seven translations; 160% text; portrait/landscape; preview/confirmation and controller B | Targeted UI test passed on Android 13 landscape and Android 15 portrait. Count/text polish repeat pending; no physical controller or screen-reader claim. |
| Android 13 | Isolated emulator matching the target Android API level | All 193 Android tests passed. Emulator behavior does not establish actual handheld behavior. |
| Public release identity / upgrade | Signed production and stable test APKs | New candidate verification pending; the last verified public artifacts are 2.8.0/build 40. |

## AYN Thor journey

Use freely distributed test content or games you own. Record app build, firmware, Android version, controller mode, emulator package/version and SD-card format. Keep the original test save and game hashes outside the app. Do not use the only copy of an important save for interruption tests.

| Check | Expected outcome | Result |
|---|---|---|
| First setup → source scan → download → extract → choose emulator → launch → return | Complete with the physical controls; no touch-only action, trapped focus or unwanted launch. | Pending hardware. |
| Upper/lower displays, portrait/landscape, large text | Text and controls remain reachable; back/cancel returns to the expected screen. Secondary-screen status reflects the current queue. | Pending hardware. |
| Sleep/resume during a download; return from emulator | No duplicated work or lost pause; explain Android background restrictions when relevant. | Pending hardware. |
| Disconnect/reconnect SD during queued download, storage move and refresh | Retain completed files, partial bytes, private package receipts and personal data; resume only the affected work after a fresh storage check. | Pending hardware. |
| Export, reinstall and restore package records using test content | Select storage again and rescan sources; verify every byte before registration. Same-size changes and wrong roots block restoration. | Fixture passed; physical SD pending. |
| Update from the latest regular public APK | Keep sources, disabled sources, profiles, favorites, collections, notes and backups. | Pending new public candidate. |
| Screen reader, increased text size, long translations | Actions are named, status is understandable and all confirmations/cancel actions remain accessible. | Pending device accessibility pass. |

## RomM / WebDAV

No live deployment is available from the owner yet. Existing automated HTTP/provider fixtures cover selected protocol and failure paths; they do not certify every server release. Record the exact server version, HTTPS/proxy setup and emulator save format when testing becomes possible.

Check connection and authentication, console mapping, source scan, game download, ordinary save upload/download, two-device conflicts, verified backup recovery, network loss/retry and changed certificates. No conflict should silently replace a newer save. A failed transfer must not report the game ready for handoff. Save states require a separately verified emulator/version combination.

Full multi-file game upload to a NAS or RomM remains future work. Do not describe the package-record importer as a game uploader or a game-byte backup.

## Launch decision

Publish the exact source commit, APK checksums, signer continuity and actual test results with each regular release. Public numbering is 2.8.0 → 2.9.0 → 3.0.0. The wider Reddit launch still requires the primary handheld journey and physical storage checks. Keep unavailable server combinations explicitly unverified in the release and community post; do not infer them from emulator or HTTP fixtures.

For feedback, use the [bug-report template](../../.github/ISSUE_TEMPLATE/bug_report.yml). Include app/device/Android/emulator/server versions, reproduction steps, expected/actual behavior and whether local bytes changed. Remove passwords, tokens and private source addresses from diagnostics.
