# AYN Thor test run — Android 13

This is the physical-device checklist for the 3.0.0 community launch. No Thor result is recorded yet. RomM is optional for the local-library checks; the owner currently has no RomM installation.

Record the app version/build, Thor firmware, Android version, controller mode, emulator package/version and SD-card format. Mark each step passed, failed or unavailable and attach the reproduction steps for a failure. The [launch matrix](../quality/community-launch-matrix.md) contains the expected results and integration scope.

## Install and prepare

Download the APK from the [GitHub releases page](https://github.com/Tufein/DogmatixPlus/releases). The normal APK updates the normal app in place. The debug APK is a separate test app with separate data; published debug versions from 2.7.0 update that test app in place. Use the test app for reinstall and interruption exercises.

Keep a general app backup and separate copies of test games/saves. Package records use their own export and do not include the game bytes. Choose a temporary game folder containing freely distributed test content or spare copies of games you own. Configure an installed emulator for that console.

## Complete the main journey

1. Finish setup, select the game folder and add a source. The optional **Sources → More romsets** catalog supplies content collections alongside personal sources; scan the selected source before choosing a test file.
2. With the physical controller, move through Library, Downloads, Sources, Settings and Tools. Check focus, confirm and back/cancel. Open a game page, mark a favorite and add a game to a collection.
3. Download a small test game. Check progress, pause/resume, extraction if relevant and the final file. Choose the installed emulator, launch the game and return to Dogmatix+.
4. Repeat navigation on both displays with the optional second-screen feature. Rotate where supported and increase text size. Confirm that labels, dialogs and cancel actions remain reachable. Emulator screenshots do not count as this hardware result.
5. Put the Thor to sleep during a test download, wake it and return from the emulator. Record any Android background restriction and verify that completed files and pause state remain correct.

## Check recovery and updates

- **Storage reconnect:** during a disposable test transfer, disconnect and reconnect the selected SD storage using the device’s supported removal procedure. Completed files, partial bytes and package records should remain available after the folder returns. Resume affected work through a fresh storage check; unrelated user-paused downloads should stay paused.
- **Package records:** export records, keep the game bytes separately and reinstall only the test app. Select the storage again, scan the original source and restore records from **Tools → Game package records**. Check each configured root separately. A wrong folder, missing track or changed file must block that package; other files must remain unchanged. See [package recovery](package-recovery.md).
- **Update:** install the next signed APK over the corresponding current app. Verify sources, disabled sources, profiles, favorites, collections, notes, backups and package records. Do not uninstall for a normal update.
- **Accessibility:** use increased text size and a screen reader to navigate the new screen and its confirmations. Verify action names, status and a reachable cancel action.

## Server checks

Mark RomM/WebDAV **unavailable** until an actual server is configured. Record its version, HTTPS/proxy setup and emulator save format before testing authentication, download, ordinary save transfer, conflicts, backup restore and connection loss. An emulator/provider fixture cannot certify a live server. Multi-file NAS/RomM game upload remains future work.

Report issues through the [bug template](../../.github/ISSUE_TEMPLATE/bug_report.yml), including app/device/integration versions and whether local bytes changed. Exclude passwords and tokens. The Reddit draft remains unpublished until the launch evidence and community rules are checked.
