# Keep game packages through a reinstall or device change

Game packages remember which discs, tracks, playlists and subfolders belong to a downloaded game. A normal app update keeps those records. Before uninstalling the app or moving to another device, export them separately under **Tools → Game package records**.

## Before a reinstall

1. Export the general app backup for settings, sources, collections and journals.
2. Open **Tools → Game package records** and export the package records to a JSON document outside the app's private storage.
3. Keep a separate copy of the game folders, preserving their complete relative layout. Keep saves separately too. The package document contains file names, sizes and SHA-256 checksums, not game or save bytes.
4. Check that the export succeeded before uninstalling. The app reads the document back before reporting success. A failed export may leave an incomplete document; export again to a new document.

The document contains game titles and paths, so share it only where you intend to share that library information. It does not contain local folder grants or download credentials. Export includes the latest record for each game source identity in the configured game folders. Package records are limited to 2,000 games, 50,000 file parts and an 8 MiB transfer document. A too-large export fails rather than silently dropping games to fit the limit.

## On the new installation

1. Restore the general app backup if you have one.
2. Copy or reconnect the game folders. Select the download folders again in Settings: Android folder permission does not transfer to a new installation.
3. Scan the same sources so the app can match each package to its original game. A changed download identity may require the original source to be restored.
4. Switch to an unrestricted profile. Import is disabled in a restricted profile because it registers access to existing game files in a shared folder. Exports from restricted profiles include only games whose indexed source rows are all visible.
5. Open **Tools → Game package records**, choose the JSON document and then choose a configured game folder. Check the file list if you need to confirm the expected layout.
6. Wait for the checks. Only packages whose source matches and whose complete file list passes size/checksum verification are eligible for restore. A different or corrupt existing record blocks that package. The screen reports how many packages passed in this folder and which ones failed; a failed package is never partially restored.
7. Confirm the eligible game count, file count and selected folder. The app checks those complete packages again immediately before saving the records. Other packages remain unchanged.

Import registers existing files. It does not copy, overwrite, move or delete games or saves. Repeating a successful import is safe; matching existing records are kept. If the app stops during registration, run the import again: individually committed records remain valid and the repeat completes any missing records.

## If a check fails

| Message | Next action |
|---|---|
| Source unavailable | Restore and scan the original source. A same-named game from another source does not prove it is the same package. |
| Missing files | Choose the right configured root and restore the complete original folder layout. |
| Changed files | Compare with your separate game backup. Restore is blocked even if a changed file has the same size. |
| Existing record differs | Keep the current record; inspect the game and original export before proceeding. The importer does not replace it. |
| Folder no longer configured or profile changed | Refresh, choose the folder again and run a new check. |

Games spread across several configured roots can be exported together. Check one configured root at a time and confirm only the complete packages verified there. The imported document stays open after restore so you can check the next folder. Alternatively, copy all layouts under one root before checking. Shared or colliding paths must still match the original checksums; the importer never relocates them for you.
