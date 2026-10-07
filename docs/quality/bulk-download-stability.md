# Bulk-download stability — 8.2.0 beta 2

The reported screenshot shows Android's repeated-crash dialog. It contains no stack
trace and cannot distinguish a Java exception, native crash, or an ANR followed by
termination. The changes below fix concrete failure paths found in beta 1's code.

| Failure path | Correction | Automated coverage |
| --- | --- | --- |
| Selected retries and several enqueue paths repeatedly scan/update the queue on Main; bulk planning reads preferences per row; folder validation calls a storage provider on Main. | Batch retries/enqueues, one preference snapshot, CPU work on Default and provider checks on IO. | 500-download Android start, duplicate-tap, stop and retry regression; existing bulk/version-selection tests. |
| A large queue copies/emits its full growing waiting list once per item. | Coalesce automatic large-queue publications; preserve immediate user reordering and per-host/cancellation rules. | 2,000 blocked waiters; order, host limits, cancellation and slot reclamation; 500-waiter Android main-looper regression. |
| Foreground-service lifecycle starts/stops the native torrent session on Main; each selected sibling allocates/reads a whole torrent progress array. | Registry owns a lazy IO-started session/listener; poll once per torrent, read selected indices, free the native vector explicitly. | Torrent progress policy tests, including sparse selection in a million-file collection. Native lifecycle remains a real-device check. |
| A stale torrent callback can replace COPYING, UNZIPPING or terminal state. | Atomically restrict callback transitions to QUEUED/DOWNLOADING; completion requires verified per-file bytes. | Torrent policy tests and Android guarded-transition regression. |
| HTTP cancellation checks a job map entry that pause/stop already removed; an unlimited transfer has no cancellable suspension. | Check the transfer's coroutine before/after reads and before completion; finalize the output before post-processing. | Transfer cancellation during a read and between chunks; storage-write and network-read error classification. |
| Stop/pause can race batch registration; old transfer or native cleanup can overwrite a rapid retry. | Register jobs and publish rows under one lock; replacement workers wait for previous transfer and native cleanup; old workers cannot mutate a newer job's state. | Android bulk control and rapid restart regressions. |
| History restore can run before its fields are initialized; transfers can finish before their initial history insert; independent status writes can leave an older status on disk. | Initialize first, insert history before releasing transfers, and use one conflated status writer. | Real Room with 500 concurrent completion transitions and reconstructed history. |
| Every finished download can start another storage hash scan; both screens repeatedly compute over full queues on Main. | One verification slot; prepare second-screen and tile state on Default. | Existing checksum/queue display tests plus the Android bulk regressions. SD-card contention remains a device check. |

Release requires `testDebugUnitTest`, `lintDebug`, debug/release builds and
`connectedDebugAndroidTest` on the CI API 35 emulator. The workflow verifies the
established release certificate and version/tag match before uploading APKs.

## Real-device acceptance

1. Install the signed beta 2 release APK over beta 1. Confirm existing folders,
   history and source settings remain available.
2. Queue 500 small HTTP files with no speed limit; switch between Library and
   Downloads, then background/foreground the app and use both handheld screens.
3. Pause, stop and retry a large selection. Paused/stopped transfers must stop
   writing and must not later become completed. Test resuming with and without
   server Range support.
4. Download several files from the same large torrent. Finish/copy one while the
   others continue, pause/resume a sibling, then stop the queue. No sibling should
   fail due to cache deletion or be declared complete from whole-torrent progress.
5. Repeat with an SD-card SAF folder and optional extraction/checksums enabled.
   Remove access or fill the destination on a small disposable batch: report a
   failed/stopped download while the app stays responsive.
6. Restart during a batch. Completed rows must stay completed; interrupted rows
   follow the user's restart setting. Repeat once with restart continuation off.
7. If the app still closes, share **Settings → Diagnostics**. Its Recent exits,
   ANR main-thread trace and Last crash identify the remaining failure; record
   source type, batch size and exact app version with it.

No physical handheld, removable SD card or live RomM/debrid server is connected to
the build environment. Emulator and unit-test success do not substitute for those
checks or prove the exact photographed crash has been reproduced.
