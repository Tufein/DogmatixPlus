# Download feedback acceptance

Release 2.2.0 keeps application ID `com.tufein.dogmatixplus`, the established release signing certificate and database version 13; Android build number advances to 34.

- Per-file speed uses monotonic sampling and a moving average. A single shared watcher clears rates after five seconds without byte advancement, including a blocked HTTP read without further callbacks. Pause, stop, copy, extraction and completion clear transfer speed immediately.
- Remaining time estimates network transfer only. Unknown sizes suppress per-file and whole-queue ETA; the main Downloads header does not show a partial known-size percentage as the complete queue.
- Typed failures never contain raw source URLs, credentials or exception messages. Connection, timeout, HTTP response, storage and extraction failures map to translated explanations and recovery actions. Failure details are session-local; restored history safely offers generic retry guidance.
- Download extraction runs in strict mode: empty or failed extraction cannot become a completed download. Each concurrent extraction has its own cache directory; outputs close on abort and cancellation propagates. File Explorer retains its existing permissive result handling.
- The update checker consumes Android versionCode metadata and selects by publication date. Existing old-numbering binaries require one manual upgrade; signed installation continuity follows the higher versionCode.

Verification covers pure rate/ETA/error/update policy regressions, a real tracker with Room and 500 stalled rows, status/reset races, eight Compose feedback/action scenarios, strict extraction/cancellation checks, and the previous bulk-download regressions. CI runs Android integration tests on API 35 and verifies the release certificate and APK identity before publication.

Local validation on 2026-10-07: 1,213 JVM tests, zero failures/errors and two existing skips; debug APK and Android-test APK built; lint passed against the unchanged baseline. Seven numbering-policy tests and five migration simulations passed. The GitHub migration independently verified every release ID, asset/checksum, publication date, canonical source commit and archived tag object.

The first CI emulator run caught an Android ICU regex incompatibility in the new release metadata parser. The closing JSON brace is now escaped explicitly, and four Android regressions verify metadata parsing, invalid inputs, build-number updates and publication-date selection. The failed candidate was never published; the corrected candidate must pass the full emulator suite before release.

Physical handhelds, long live transfers and individual SAF providers still need device acceptance. On a device, check HTTP and torrent transfer speed, pause/resume, unknown-size links, a disconnected connection, expired source access, revoked folder access, low space and malformed archives. Confirm that each message offers the appropriate next step and that bulk UI controls remain responsive.
