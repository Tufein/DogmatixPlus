# Changelog

Public releases follow 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. See [release numbering](docs/releases/numbering.md) for the migration and original APK versions.

## [2.8.0] – 2026-10-10 · Dogmatix+

- Add a searchable, optional Libretro content catalog for 23 systems; adding sources preserves existing names, disabled entries and indexed games. Parse the actual size column in h5ai directory sources.
- Persist verified game-package receipts for extracted discs, tracks and nested folders; restore exact source/root relationships after restart without broadening shared-file removal.
- Check offline collections for readable files, disc references, BIOS and the effective selected emulator, with links to missing-item repair tools.
- Unify trash, interrupted moves, file-replacement recovery and verified save backups in a profile-aware recovery center with fresh previews.
- Track the exact selected storage folder, preserve partial transfers when it vanishes and resume its affected downloads only after an explicit write/read check.
- Add per-profile game notes and selected screenshots/PDF manuals; backups retain notes and attachment metadata and require reselecting inaccessible restored documents.
- Add a guided ordinary-save handoff with per-game identity, content hashes, stale-preview protection, conflicts and verified safety copies.
- Document the complete hardware/server validation roadmap and prepare an unpublished Reddit draft for the wider community launch.
- Seven translated interfaces; Android build 40, database version remains 15, established production and debug signing identities retained.

## [2.7.0] – 2026-10-10 · Dogmatix+

- Preserve directory paths in archives; reject traversal, sanitized-name collisions, excessive entries and blocked destinations before publishing files.
- Preflight all extraction targets, stage and verify every file, reuse identical content on retry and preserve conflicting existing files and the source archive.
- Recognize nested game folders in the library, grant safe child disc/track access at launch, and group automatic playlists within their own folder.
- Preflight every RomM upload file and reject folder layouts that the current upload API cannot safely preserve.
- Download replacements into a separate resumable file, verify source checksums and readback before publishing, and protect the completed original with a verified recovery journal.
- Recover interrupted file replacements from Tools → Files without overwriting different current files.
- Propagate save-backup failures before replacement or deletion; create unique durable safety copies with SHA-256 receipts and reject damaged new copies on restore.
- Validate all backed-up preference types and ranges; preserve current values for invalid settings and repair previously persisted wrong types before typed readers start.
- Separate source identity from storage basename, handle relative and absolute URLs correctly, and preserve literal plus signs.
- Check update package, version, checksum and complete signer set before installation; cancel unfinished update sessions cleanly.
- Use a separate stable signing key for published debug APKs from 2.7 onward; old debug builds with changing keys require a one-time backup/reinstall.
- Seven translated interfaces; Android build 39, database version remains 15, established production signing identity retained.

## [2.6.0] – 2026-10-09 · Dogmatix+

- Visible automatic retry countdown with Try now and Cancel retry; old attempts cannot control a newer transfer.
- Persist safe download failure categories, HTTP status and timestamps across restart; clear on retry.
- Editable daytime/nighttime and custom download presets for speed, concurrency and scheduling.
- Confirm another enabled source for the same file from Downloads; finish prior cleanup and discard incompatible partial data before restart.
- Share/import reviewed download plans with ordered selections and per-item conditions, using receiver-local sources and settings.
- Profile-scoped emulator variants/cores per game, effective-choice readiness checks and backup support.
- Seven translated interfaces, Android build 38 and database migration 14 → 15.
- Credential-free portable plan identities support relative/absolute source links and reject ambiguous recipient files; imported scheduling follows the recipient's actual filename.
- Retry settings are observed safely from startup; readiness displays and tests the effective emulator for each playable file.

## [2.5.0] – 2026-10-08 · Dogmatix+

- Ready-to-play file/disc/BIOS/emulator checks, guided console setup and a test launch.
- Dynamic collections by console, language tags, cached genre/year and profile played status.
- Offline quotas in GB, download previews and separate reserve budgets per storage volume.
- Batch version previews account for downloads, extraction and verified recovery copies.
- Personal favourites, language/version preferences, emulator choices and action history; existing general-profile data retained.
- Contextual troubleshooting from failed actions; default recoverable file-explorer removal including nested paths.
- Backups preserve per-profile favourites/emulators and map collection rules/quotas by name.
- Stable recovery serialization reads the previous signed release's field aliases.
- Android build 37, database 14; seven translated interface languages.

## [2.4.0] – 2026-10-08 · Dogmatix+

- Compatible emulator choices per console, with application-specific launch recipes and explicit installed variants.
- Separate launch and removal plans so playing can read shared disc dependencies without removing shared files.
- Explain and save global/per-console language, region, revision and dump preferences; fixed game versions are respected by automatic and duplicate selection.
- Searchable action history with recovery and profile-aware download-again actions; refresh the library after restore and report persistence failures accurately.
- Preserve revision facts independently of active ranking rules and normalize encoded source names when identifying version preferences.
- Seven translated interfaces, Android build 36 and database version 13; release signing identity retained.

## [2.3.0] – 2026-10-07 · Dogmatix+

- Search Downloads by title or file name and filter active, waiting, paused, problem and completed rows.
- Select matching rows for batch actions and move waiting selections to the front while preserving their queue order.
- Validate resumed HTTP responses before changing a partial file and reject incomplete transfers before completion or extraction.
- Cancel outdated automatic-retry timers when a download is restarted, stopped or removed; disabled automatic retry no longer starts scheduled attempts.
- Download completion and low-space notifications tolerate notification permission being revoked during a transfer.
- Cancelled torrent metadata fetches release their unowned native handles without deleting cached files.
- Android build 35; database version remains 13.

## [2.2.0] – 2026-10-07 · Dogmatix+

- Smoothed per-download speed and remaining-time estimates; unknown sizes and stalled transfers are shown without misleading estimates.
- Actionable download errors distinguish connection, server, storage, permission and extraction problems.
- All GitHub releases use consecutive public version numbers and are regular releases.
- Updates compare Android build numbers so future updates work across the public version rename. The first upgrade from legacy APKs must be installed manually.
- Android build 34; package, release signing key and database version 13 retained.

## [2.1.0] – 2026-10-07 · Dogmatix+

Originally `v8.2.0-beta.2`; preserved APK version `8.2.0-beta.2`, Android build 33.

- Bulk download selection, retry and queue controls run off the main thread; retries and suggestion downloads use the batch enqueue path.
- Large waiting queues coalesce automatic list updates instead of copying the entire queue for every added item.
- Torrent progress is polled once per torrent instead of once per selected file. Native session startup and shutdown no longer block the foreground service lifecycle on the main thread.
- HTTP transfers observe coroutine cancellation directly, including when the speed limit is disabled; cancelled transfers cannot continue writing or report completion.
- Serialize stop/pause cleanup before replacement transfers, preventing rapid retries from being stopped by an older torrent operation.
- Finished-file verification is limited to one file at a time to reduce storage contention during bulk downloads.
- Fix a download-history initialization race and handle background persistence failures without crashing the process.
- Store history before transfers can finish and serialize status writes, preventing stale in-flight rows from being requeued after a restart.
- Database remains version 13. These fixes address code-level failure paths; the photographed crash still requires device diagnostics to identify its exact stack trace.

## [2.0.0] – 2026-10-07 · Dogmatix+

Originally `v8.2.0-beta.1`; preserved APK version `8.2.0-beta.1`, Android build 32.

- Verified file copies, protected destination conflicts, durable move receipts and recovery actions.
- Recovery trash for game removal, duplicates, free-space selection and Downloads; original paths can be restored. Empty trash to reclaim space. The general file explorer still deletes permanently.
- Play owned games through compatible applications; remember the application per console.
- Explain version differences and pin a preferred version for automatic selection.
- Privacy-filtered operation reports, seven translated interfaces, mandatory release signing and Android integration checks.
- Fix a library-index initialization race and API 29–32 compatibility of bounded stream reads.
- Database version remains 13. Physical SD cards, controller hardware and live RomM/WebDAV interoperability still require acceptance testing; see `docs/quality/recovery-acceptance.md`.

## [1.9.0] – 2026-10-06 · Dogmatix+

Originally `v8.1.0`; preserved APK version `8.1.0`, Android build 31.

### Changed
- Settings, RomM and Save sync screens: pure black (#000) ground in the dark and True black themes, black cards with a thin outline, no background glow. The light theme stays light.
- Settings layout is symmetric: equal outer margins, a centred content column with a maximum width, equal card padding and gaps, two-column rows of equal height with a divider between the columns, icons and controls on one vertical line, all steppers the same width.
- Two columns only on landscape screens of at least 800dp wide; smaller screens (such as the Thor's bottom screen) get one centred column.

### Notes
- No database change (version 13). Not yet tried on a real device.

## [1.8.0] – 2026-10-06 · Dogmatix+

Originally `v8.0.0`; preserved APK version `8.0.0`, Android build 30.

### Added
- **Game page**: A or a tap on a library game opens a full-screen page with its art, a main Download button, actions (best version, favourite, collections, share, remove after a confirmation) and tabs About / Versions / Progress (achievements, cloud saves) / More like this; LB / RB switch tabs.
- **Quick menu**: hold SELECT for a ring of shortcuts (Search, Search everything, Surprise me, Downloads, Pause all / Resume all, Tools, Settings); the stick or D-pad chooses, letting go or A opens.
- **Smart storage** (optional, off by default, *Tools → Storage*): consoles not played recently (ES-DE play data) and without favourites move as a whole folder to a chosen SD-card folder and come back when played again. Copy, verify, then delete; *Check* shows a dry run; the weekly run only happens while charging. ES-DE's system path follows (restart ES-DE); other launchers must be pointed to the new folder by hand.
- **Search everything**: one search for settings, tools, screens and library games, from the quick menu, the Tools title or *Settings → Search settings and tools*; a Settings result jumps to its row and highlights it.
- **TV mode** (*Settings → Look and controls*, Auto / On / Off): larger text and rows, safe margins for TV edges, remote keys (channel and page keys switch sections), the legend with a remote. The app also shows in the Android TV launcher with its own banner.

### Changed
- A on a library row opens the game page instead of downloading straight away; X or a long press still opens the quick details card.
- A short SELECT press still marks a favourite, now on release.
- Texts in Settings are never cut off: titles, hints and values wrap in full.

### Notes
- No database change (version 13).
- Not yet tried on a real device, a TV or an SD card.

## [1.7.0] – 2026-10-06 · Dogmatix+

Originally `v7.5.0`; preserved APK version `7.5.0`, Android build 29.

### Added
- Second screen: Pause all / Resume all and per-download pause/resume.
- Pause on low battery (with hysteresis) and when the device is hot (battery temperature and Android's thermal status); "Waits for: low battery / device too hot".
- Notification actions: Pause all, Resume all, Stop all on the ongoing notification; per-game "downloaded" notice with Open in the library and Open the downloads.
- Best source: a per-source track record (speed, success rate), ranking by reliability then speed, one automatic switch to the next source after a final failure; track record on the source cards.

### Changed
- The queue hold (*Pause all*) now parks running downloads that can resume (torrents, web downloads with a partial file); the others still finish.

### Notes
- No database change (version 13). Not yet tried on a real device.

## [1.6.0] – 2026-10-06 · Dogmatix+

Originally `v7.0.0`; preserved APK version `7.0.0`, Android build 28.

### Added
- **Offline collections**: a collection can be kept on the device; new games in it are queued automatically (per-run cap, free-space check, optional Wi-Fi only). A review list offers games that left the collection; nothing is deleted automatically.
- **Free up space** tool, **Descriptions for your launcher** (ES-DE gamelist.xml, Pegasus metadata.txt), **Best games per console** (RetroAchievements), **Better versions**, **Year in games** with a share card.
- **Weekly digest** notification (opt-in), **Quick Settings tile** for the download queue, launcher shortcuts for Surprise me, Downloads and Search.

### Changed
- Settings: all rows share one icon style; the cloud screens, the Tools hub and the collection goals screen follow.
- The second screen shows the app icon instead of the mascot.

### Notes
- No database change (version 13).
- Not yet tried on a real device or against real servers.

## [1.5.0] – 2026-10-05 · Dogmatix+

Originally `v6.0.0`; preserved APK version `6.0.0`, Android build 27.

Finding, collecting and sharing. Layout and key bindings stay as they were.

### Added
- **Genre and decade filters**, **More like this** in the details card, **Surprise me** on the Start button, **compact lists** (Settings → Look and controls).
- **Collection goals** and **Play history** in Tools.
- **Share a game** as a card, **share the wishlist** as text, a **Continue playing** home-screen widget, and a notification when a wanted game appears on RomM.
- **Download when it suits**: Wi-Fi, charging, both, tonight or at a set time, per game or per selection.
- **Shared wishlist** on a WebDAV server, with who added and who found each game.
- **Check everything** in Tools: one health report with a fix per problem.

### Changed
- WebDAV sync and backup: conditional re-read and read-back writes, a rollback guard, an account-aware sync base, rotating backup names uploaded as `.part` then moved, an 8 MB backup limit, a warning for unencrypted addresses, one export/restore at a time.
- Cloud-save restore only picks a single same-name file when the folder or RomM id matches, and the dialog names the file it replaces.
- The old download rules class is now `DownloadRules`; `DownloadConditions` holds the per-item conditions.

### Notes
- No database change (version 13).
- Not yet tried on a real device or against a real RomM or WebDAV server.

## [1.4.0] – 2026-10-05 · Dogmatix+

Originally `v5.0.0`; preserved APK version `5.0.0`, Android build 26.

The look and the cloud. Layout, navigation and key bindings stay as they were; everything is
restyled, and RomM, a WebDAV server and RetroAchievements now show up where you play.

### A new look
- **Depth and colour**: content sits on panels with a soft top light and a hairline edge, a faint
  glow of the accent colour lights the background, and every colour role of the theme is filled in
  (light, dark and true black, all accents and Material You). Console colours (Nintendo red, PlayStation
  blue, Sega blue, Game Boy green…) tint chips, cover placeholders and charts.
- **Focus is the hero**: a tonal fill, a ring and a soft halo that animate in the draw phase only;
  touch gets press feedback everywhere. Switches, steppers and rows share one set of parts.
- **Covers**: in the library list (optional), the details card, Downloads, the wishlist, the duplicates and
  statistics tools, and the second screen. Source order: RomM cover, libretro box art, a stored metadata image, then a gradient tile with the
  console name. One shared image cache; RomM images are fetched with the RomM login and trust.
- **Typography and icons**: screen titles, section headers with icons, large tabular figures, and
  about 80 Material Symbols icons in place of text glyphs.
- **Charts**: donut rings, a segmented storage bar, column charts and sparklines in the Downloads header and
  in *Tools* (Storage, Statistics, Library overview), the second screen and the achievements.
- **Motion with restraint**: screen changes fade and slide, rows expand and collapse, the queue animates
  its placement. *Settings → Look and controls* has **Animations** (everything snaps when off),
  **Background glow**, and **Covers in the library list**, with a preview.
- **Empty states** with a picture, one line and a button; a new **Downloads header** (speed, time
  left, space needed against free space) and **source cards** with health.
- **Second screen, widget and shortcut picker** use the app's theme.

### Cloud hub
- **Settings → Cloud** shows each cloud feature at a glance: RomM (reachable, version, user, games,
  platforms), Save sync, Cloud backup, Device sync, RetroAchievements and Debrid, each with its main
  action. A small **cloud icon in the top bar** shows idle, syncing (with progress) or "needs you"
  with a count.

### RomM in the app
- **Details card, "On RomM"**: summary, genres, year, rating and screenshots from the server, your
  **play status** (backlog, playing, finished, retired…) and your **rating**, written back to RomM.
- **Favourites in step with RomM** (two ways, optional): stars go to RomM's Favourites collection and
  back.
- **BIOS from RomM**: *Tools → BIOS check* fetches the missing files from the server's firmware, checks
  each file's MD5 and never replaces a file that is there.
- **Server status**: version, user and counts, shown in the hub.

### Cloud saves
- **Per game** (details card): the saves and states on the server, with the state screenshot, next to
  the safety copies on the device, each with **Restore**; **Upload now** sends this device's newer file.
- **Continue playing** on Home: the games last saved on any device, or last played in ES-DE
  (*Settings*, on by default).
- Safety copies stay for 30 days; a restore keeps the file it replaces.

### Your own cloud (WebDAV)
- **Encrypted backup** to Nextcloud, ownCloud, Synology, Koofr or any WebDAV server: AES-256-GCM with a key
  from your passphrase (PBKDF2), automatic (daily, on Wi-Fi, while charging), a list of backups with
  restore, and a number to keep. The password and passphrase stay on the device: they are not in
  backups or diagnostics.
- **Device sync**: favourites, wishlist and collections are merged between your handhelds
  with timestamps and tombstones; a sync that would remove many items waits for your OK.
- A server with a self-signed certificate can be trusted after you have seen its fingerprint.

### RetroAchievements
- **Per game**: a progress ring and a grid of badges (earned, hardcore, missable) in the details card.
- **Profile** in the hub and in *Tools → RetroAchievements*: points, rank, recently played.

### Good to know
- Updating from 4.0.0 keeps everything. The database gets one small table for found covers (version 13).
- Everything here is covered by unit tests and a debug build in CI. **None of it has been tried on a
  real device, nor against a real RomM, WebDAV or RetroAchievements server.** Try *Cloud backup* with
  a test folder first, and read the hub's messages if something does not connect.

## [1.3.0] – 2026-10-04 · Dogmatix+

Originally `v4.0.0`; preserved APK version `4.0.0`, Android build 25.

The first full release since 3.0.0: it carries everything of 3.1 to 3.5 below, plus the following.

### Import a list
- **Tools → Import a list**: a text file or the clipboard with one game per line. Numbering
  ("1.", "2)"), bullets, quotes, comment lines (`#`) and extra tab-separated columns are left out.
  Each listed game is looked up in one console or in all of them, by exactly the same title words
  (tags, the extension and words like "the" do not count, so *Super Mario World* does not take
  *Super Mario World 2*). The best version by your languages is downloaded in one go; games already
  on the device or downloading are skipped, and the ones no source lists can go on the wishlist.
- **DAT check → Look for them in your sources** hands the missing games of a console to *Import a
  list*.

### Library
- **Recent searches**: the last six searches show under the search field while it is empty; a tap
  searches again, the × forgets them. A search is remembered once the typing stops, and the
  shorter steps typed on the way are not kept.
- **Surprise me** opens the details of a random game from the list on screen.

### Look
- **Text size** (*Settings → Look and controls*): 85, 100, 115, 130 or 150 %, on top of Android's
  own font size.

### Faster
- Database version 12: an index on the file name, which every finished download and the Downloads
  list look up.

### Code
- Inline package names became imports, the settings screen reads its state per section instead of
  per version, and the grab-bag tests of earlier versions are split into one test class per subject.
- Local IDE files are no longer versioned (the shared code style still is).
- The release workflow publishes half versions (x.5.0) as pre-releases.

## [1.2.0] – 2026-10-04 · Dogmatix+

Originally `v3.0.0`; preserved APK version `3.0.0`, Android build 20.

### Downloads
- **Continue where it stopped.** A web download cut off by a full storage, a closed app or a
  reboot now carries on from its partial file when you retry: Dogmatix+ asks for the rest with a
  `Range` request and appends it only when the server answers with exactly the missing part of the
  same file (`If-Range` with the ETag / Last-Modified it saw first). A file of the same name that
  Dogmatix+ did not write itself is never appended to. *Settings → Continue interrupted downloads*
  (on by default). Tested: 7.3 of 8 MB, app force-stopped, reopened — it fetched the last 1.1 MB and
  the file's MD5 matched.
- **The queue survives a restart.** Downloads that were queued or running when the app closed (or
  the phone rebooted) join the queue again when you open Dogmatix+, with a short notice. Paused ones
  stay paused. *Settings → Continue the queue after a restart*.
- **Per server**: at most 1–6 downloads at once from one server (*Settings → Per server*), so a
  strict host does not block you; a download whose server is at its limit lets those of other
  servers go first.
- **Download wishlist games automatically** (*Settings*): when a scan finds a wanted game, its best
  version (your languages and regions) is downloaded at once, unless you already have it. Every
  word of the wish must be in the file name, so "Game 00321" does not take "Game 03215".
- **Playlists for multi-disc games**: when the last disc of a game is in, its `.m3u` is written
  next to the discs (*Settings*, on by default).
- **Storage advisor in "Download everything shown"**: the dialog shows where the space goes per
  console, how much is missing when it does not fit, and how much removing duplicate games would
  free, with a button to the duplicates screen.

### Library
- **Covers for ES-DE** (*Settings*): after a download the game's box art goes to ES-DE's
  `downloaded_media/<system>/covers`, and name, description, date, developer and genre to its
  gamelist (unless ES-DE already has the game). Cocoon's ES-DE link reads them too. Box art comes
  from libretro-thumbnails (matched on the No-Intro / Redump file name, no key needed) when the game
  databases have none; the details card uses it as well.
- **RetroAchievements** (*Library tools → RetroAchievements*): with your RA name and web API key,
  check per console which games have achievements — files on the device by their RA hash (RA's own
  rules: NES / SNES / Lynx / 7800 headers, N64 byte order; cartridge systems), games only in your
  sources through an imported No-Intro DAT. Supported games get an **RA** badge in the library; the
  details card says how many achievements (or "probably", when matched by title only).
- **Profiles** (*Settings → Profiles*): a profile hides consoles and games with chosen tags from
  the library and its downloads (a child's profile, a couch profile). With a PIN, only someone who
  knows it can leave a restricted profile, and profiles and the PIN cannot be changed from inside one.
- **What you play** (*Statistics*): the games ES-DE started most and most recently. ES-DE records
  play counts, not play time; Cocoon keeps its play time in its own private database.

### Fixed
- The app read some settings with a blocking call while starting; on a slow device that could
  delay the first screen. Those reads now happen in the background.

## [1.1.0] – 2026-10-03 · Dogmatix+

Originally `v2.0.0`; preserved APK version `2.0.0`, Android build 17.

### Scanning
- **Rescans skip what did not change.** Every library row now remembers its source, and a rescan
  replaces one source at a time. A web directory that sends an `ETag` / `Last-Modified` is asked
  whether it changed (a *304* costs almost nothing); otherwise the listing is compared with the one
  from last time. A magnet that was read before is not fetched again at all (its content cannot
  change). Measured on the test set: a rescan of six unchanged sources of 4,000 games went from
  18 s to 2 s. *Settings → Library tools → Library overview → Full rescan* still reads everything.
- **A source that fails keeps what it gave last time** instead of disappearing from the library
  until the next good scan.
- **Reserve addresses** per web source: tried in order when the source's own address fails; the
  line under the source says when a reserve address answered.
- **Background scan** (*Settings → Scan sources automatically*): every 12 h, day, two days or
  week, by default only on Wi-Fi, while charging and at night. A notification says how many new
  games turned up (and whether sources failed).

### Library
- **New games**: files a rescan found in the last 14 days get a *New* badge; the *New* filter and the
  *Newest first* sort show them. The line under each source says how many it added.
- **Collections**: your own lists ("Couch co-op", "To finish") next to the favourites. Add a game
  from its details card (X), filter the library by collection, manage them in *Library tools →
  Collections*. They travel in exports and backups.
- **Download everything shown**: one button downloads every game the filters show — optionally only
  the best version of each game — after showing the count, the total size and the free space (and
  refusing when it does not fit). Games you have or are downloading are skipped; at most 500 at once.
- **Nintendo Switch updates and DLC**: files with a title ID (`[0100…]`) are recognised as base game,
  update or DLC. The details card says which update your sources have against the one on your device
  and how many DLC are missing, with buttons to fetch them; *Library tools → Switch updates & DLC*
  lists every game on your device that has something newer.
- **DAT check** (*Library tools → DAT check*): import a No-Intro / Redump / TOSEC DAT (also inside a
  ZIP) per console and check the console's folder: good dumps, good dumps under another name (rename
  one or all to the DAT's name), files the DAT does not know, and the games you do not have yet.
  ZIPs are checked from their index without unpacking; other files are hashed once and remembered.

### Downloads
- **The speed limit now works and covers all downloads together**: it was set in KB/s but applied as
  MB/s, so it never slowed anything down. Web downloads now share one limit (three downloads no
  longer get three times the limit), it follows the setting while downloading, and torrents get it
  too. New: **no limit at night** (the night window of the downloads).

### Tools and the rest
- **File explorer**: rename, move (to any folder the app may use) and unpack archives.
- **Share sources as QR codes**: *Sources → Share* shows the list as one or more QR codes; the other
  device reads them with its camera or from pictures (screenshots work too) and takes the list over.
- **Install updates from the app**: when *Check for updates* finds a newer release it offers to
  download the APK, checks it against the release's SHA256SUMS and hands it to Android's installer.
- **Home-screen widget**: downloads in progress and the newest games.
- **Bold focus ring** (*Settings*): a thicker ring with a dark edge, easier to follow with a
  controller on a TV or in sunlight. The new screens can be used entirely with a gamepad.

### Changed
- Database version 11 (the update migrates it; the first rescan after updating reads every source
  once, because sources were not remembered per row before).
- ZXing (QR codes) is a new dependency.
- 302 unit tests.

## [1.0.0] – 2026-10-03 · DogmatixPlus

Originally `v1.0.0`; preserved APK version `1.0.0`, Android build 12.

DogmatixPlus is an unofficial modification of Dogmatix 1.2 by Rafa Cortina (itself a fork of Milou
by santiifm). Its version numbers start again at 1.0.0; everything listed under 1.2 and below comes
from Dogmatix. The Credits screen shows the whole lineage, and the update check now looks at this
repository's releases instead of the original project's.

### Added
- **Duplicate games** (Settings): finds games that are on disk more than once per console —
  the same file in several folders, or different releases / formats of one title (regions,
  revisions, a ROM next to its `.zip`) — shows how much space each group frees and deletes the
  copy you pick after a confirmation that lists every file it removes. Because it deletes
  files, it is built to report too little rather than too much:
  - only known game formats (ROMs, disc images, archives) are compared; documents, saves in
    unknown formats, engine data, updates / DLC, BIOS files and generic names are never offered;
  - a game's companion files stay one unit (`.cue` + `.bin` + `(Track N)`, `.cue` + `.iso` +
    audio tracks, `DOOM.EXE` + `DOOM.WAD`), and a folder holding one disc image
    (`disc.gdi` + `track01.bin`…) counts, and is deleted, as one game;
  - discs, disk sides, tapes and parts (`Disc 2`, `Disc Two`, `Disc II`, `Tape 1 of 2`) are never
    compared against each other, and saves / states / patches next to a ROM are never touched;
  - program folders, folder-format games (PS3, Wii U, GameCube…) and anything deeper than one
    folder below the console folder are counted but not compared;
  - outside console folders a zip / chd / iso says nothing about the system, so it is only
    offered when name and size are identical;
  - the same file reached through two storage routes (internal storage, the Downloads
    provider, an SD card) is counted once, and copies that cannot be told apart from one file
    seen twice are not offered.
- **Library overview** (Settings): per console the games the last scan indexed, how many you
  already own, what is on disk (games and size), the download folder and when it was last
  scanned, with badges for consoles that need attention (no sources, nothing found, never
  scanned). One console or all sources can be rescanned from there; folders and loose files
  that match no console are listed.
- **Backup & restore** (Settings): one JSON file with settings, sources, favourites and the
  downloads list (it includes API keys — keep it private). A restore first checks the whole
  file, then applies it in one go that leaving the screen cannot cancel, replaces the sources
  in a single database transaction, keeps uploaded `.torrent` sources, keeps folders this
  install cannot access (and asks to pick them again), drops settings of the wrong type, and
  is refused while a source scan runs. Files written by debug and minified release builds
  restore into each other.
- **Scan progress**: the scan indicator fills up and shows the percentage of sources done;
  the library overview adds "x of y sources done", an estimate of the time left and a bar.
- **Dutch, French and German** translations of the whole app (next to English and Spanish),
  selectable in Settings → Language. Texts that were hard-coded in English (scan errors and
  messages, the download notification, the update toast) now come from the translations.
