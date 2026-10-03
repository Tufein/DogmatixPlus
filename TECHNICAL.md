# DogmatixPlus — technical documentation

*This is the detailed documentation for developers and curious readers. For a simple overview, see the [README](README.md).*

An unofficial modification of **[Dogmatix](https://github.com/cortinadev/dogmatix) 1.2** — the handheld-friendly fork of **[Milou](https://github.com/santiifm/milou)**, an Android app for discovering, downloading and managing retro games. Milou indexes the contents of `.torrent` files, magnet links and web directories, tags every file by console, region and language, and downloads straight into your ROMs folder; Dogmatix rebuilt the interface so the whole app works from a D-pad and buttons on Android handhelds; **DogmatixPlus adds tools to look after the library you build with it**: a duplicate finder, a library overview, backup & restore, scan progress, and Dutch, French, German, Italian and Portuguese.

> The scraping, indexing, download and extraction engine is Milou's work ([santiifm](https://github.com/santiifm)); the handheld interface, gamepad support, frontend integration, debrid and RomM support are Dogmatix's ([Rafa Cortina](https://github.com/cortinadev)). Everything they built is still here — see [Credits](#credits).

Download links and screenshots are in the [README](README.md).

## What DogmatixPlus adds

Everything is under **Settings**, works with a gamepad and with touch, and is available in English, Spanish, Dutch, French, German, Italian and Portuguese.

### Duplicate games
*Settings → Duplicate games* finds games that are on disk more than once, per console, and frees the space.

- **Two kinds of group**: *the same file in several folders* (same name and size) and *different versions or formats of one title* — regions, revisions, translations, a ROM next to its `.zip`. Each group shows how much space you win by keeping only the largest copy, which is marked.
- **Deleting is deliberate**: pick a copy and a confirmation lists every file that will go; only then is it deleted. A game's companion files go as one unit — `.cue` + `.bin` + `(Track N)`, `.cue` + `.iso` + audio tracks — and a per-game folder holding one disc image (`disc.gdi` + `track01.bin`…) counts as one game and is removed once empty. The delete finishes even if you leave the screen.
- **Built to report too little rather than too much**, because it deletes files:
  - only known game formats (ROMs, disc images, archives) are compared; documents, saves in unknown formats, engine data, updates / DLC and BIOS files are counted but never offered;
  - discs, disk sides, tapes and parts (`Disc 2`, `Disc Two`, `Disc II`, `Tape 1 of 2`) are never compared against each other, and saves, states and patches next to a ROM are never touched;
  - program folders, folder-format games (PS3, Wii U, GameCube…) and anything deeper than one folder below the console folder are not compared;
  - outside console folders a `.zip` / `.chd` / `.iso` says nothing about the system, so it is only offered when name and size are identical;
  - the same file reached through two storage routes (internal storage, the Downloads provider, an SD card) is counted once, and copies that cannot be told apart from one file seen twice are not offered.
- **Limits worth knowing**: different games that share a title (for example *Star Wars* on the NES in Japan and the US) can show up as "versions" — the file names in the confirmation tell them apart; and a console that is not set up in Sources is treated as an unknown folder, so its games need an identical twin to be offered.
- Scanning uses one provider query per folder instead of one per file, so thousands of ROMs stay quick.

### Library overview
*Settings → Library overview* puts the scan next to what is on your disk.

- Totals: consoles, games indexed, games you own (and the percentage), games on disk with their size, and the free space.
- Per console: indexed, owned, on disk (count and size), the resolved download folder, **when it was last scanned**, and a badge — *OK*, *No sources*, *Nothing found* or *Not scanned*.
- Rescan one console or all sources from here; folders without a console and loose games are listed so nothing hides. The numbers refresh by themselves when a scan finishes.

### Backup & restore
*Settings → Back up* writes one JSON file with your settings, sources, favourites and downloads list; *Restore backup* brings it back.

- **It contains your API keys** (TorBox, Real-Debrid, RomM) — keep the file private.
- A restore **reads and checks the whole file first**, then applies it in one step that leaving the screen cannot cancel; the sources are replaced in a single database transaction, so an interruption never leaves them half replaced.
- It keeps what a backup cannot carry: uploaded `.torrent` sources stay, and folders this install has no access to are kept as they are (you are asked to pick them again). Settings with the wrong type are dropped instead of crashing the app, numbers are kept within what Settings offers, backup files with a newer format version are refused, and a restore is refused while a source scan runs.
- The format uses fixed field names, so a backup made by one build restores in another, including the minified release build.

### Save sync with RomM *(1.1.0 beta)*
*Settings → Save sync* makes your RomM server the place your games save to and load from: the emulator saves and save states on the handheld and on the server are kept the same, in both directions, so a game can be continued on another device or in RomM's web player (EmulatorJS uses the same `.srm` / `.state` names as RetroArch).

- **Folders**: pick the emulator's saves folder and its save-states folder (RetroArch: `RetroArch/saves`, `RetroArch/states`; one folder for both works too — `.state`, `.state1`…, `.state.auto` are states, everything else a save). Files up to three folder levels deep are synced (RetroArch's *sort by core / by content folder*); thumbnails, configs and temporary files are skipped, and files over 64 MB are not sent.
- **Which game a file belongs to**: the file name without extension must equal the ROM's file name without extension (`Pokemon Emerald (USA).srm` ↔ `Pokemon Emerald (USA).gba`). Dogmatix asks RomM's search and checks the names exactly; when the same name exists on several platforms, a folder in the save's path named like the platform (`saves/gb/…`) decides, otherwise the file is left alone. A first-level folder (`saves/mGBA/…`, the RetroArch core) is sent as RomM's *emulator*, and a device that has a folder of that name gets the file in it.
- **What moves**: after each sync Dogmatix remembers what both sides looked like (size and time of the device file; time, size and hash of the server file). Next time, only the side that changed is copied; when **both** changed, nothing is overwritten and the file is listed with *◀ Device* / *RomM ▶* to keep one. The first time a file exists on both sides, equal contents are simply recorded and different contents are listed the same way. Device clocks never have to agree with the server's.
- **Safety**: a device file replaced by a download is first copied to the app's private storage (`files/save-backups/`, kept 30 days); downloads are written to a temporary file and only then replace the old one. Deletions are not synced: a file missing on one side is copied back from the other. Slot saves (RomM 5's dated history) are left alone.
- **When**: *Sync now*, and — when switched on — automatically when Dogmatix opens or comes back to the front (at most every two minutes), with a short message when something moved or needs a choice. Close the game before syncing: an emulator that is still running can write its older in-memory save over a freshly downloaded one.
- Works with RomM 3.10, 4 and 5 (tested against 3.10.3, 4.0.0 and 5.3.1), with an `rmm_…` client token (scopes *assets* read/write and *roms* read) or `user:password`.

### Favourites sync
A sources export (*Sources → Export*) now carries your ★ favourites as a `_favourites` list next to the consoles; importing such a file on another device **adds** them to the favourites there (a game starred on both keeps the earliest date; un-starring is not carried over). Favourites are keyed by console and file name, so they light up as soon as the rescan has indexed the games. Older versions ignore the list. Backups keep carrying favourites on their own.

### Scan progress
While sources are scanned the indicator in the top bar fills up and shows a **percentage**, counted per source. The overview adds *x of y sources done*, an estimate of the **time left** and a progress bar (the estimate is rough while torrents are involved).

### Dutch, French, German, Italian and Portuguese
The whole app — including scan messages, errors, the download notification and the update notice that used to be fixed English — is available in **Dutch, French and German** (1.0.0) and **Italian and Portuguese** (1.2.0-alpha.1) next to English and Spanish. Pick it in *Settings → Language*, or let it follow the system; Android's per-app language setting lists them too.

### What 1.2.0-alpha.1 adds
Everything below came with the alpha and is part of 1.2.0; the RomM parts have not been tried against a real RomM server yet (see the release notes for what was and was not tested).

**RomM**
- **Games on RomM are marked.** `RommLibraryService` reads the game list of every mapped platform (`/api/roms?platform_ids=…`), turns it into `consoleId|name-without-extension` keys (`RommMarks`, so `Game.zip` on the server matches `Game.gba` in the library), keeps them in `files/romm_library.json` and refreshes them after six hours, when the URL, token or platform map changes, or by hand. Rows that come from the server itself always count. A finished upload adds its key at once.
- **Self-signed HTTPS** uses trust on first use. `TlsTrust.probe` opens the TLS handshake only (no HTTP request, so no credentials) to read the leaf certificate; after the user confirms its SHA-256 fingerprint it is stored (`romm_trust_fingerprint`) and `TlsTrust.apply` gives every `HttpURLConnection` to **that host** a trust manager that accepts exactly that leaf certificate (and skips the hostname check for it) and otherwise defers to the system trust store. Other hosts are untouched. `JsonHttp` and the download client both call it, so RomM downloads and covers work too.
- **Resumable uploads.** The chunked upload session (`upload_id`, next chunk) of each file is written to `files/romm_upload_sessions.json` after every accepted chunk. A retry — also after the process died, picked up at the next start — skips the chunks already sent. A transient failure (no connection, 5xx, 408, 429) keeps the session; a 4xx on a resumed session means the server forgot it, so the upload starts over; other failures cancel the session. RomM has no endpoint to ask which chunks it holds, so the app trusts its own record.
- **Covers for ES-DE** (`RommCoverService`): pairs the games on disk (from the library scan) with the server's covers by file-name stem, per ES-DE system (the console's folder name), and writes missing ones to `<ES-DE folder>/downloaded_media/<system>/covers/<stem>.<ext>`. The cover URL is `/assets/romm/resources/<path_cover_large>`; existing covers are never replaced.

**Save sync**
- **Background**: `SaveSyncScheduler` keeps one periodic `JobScheduler` job in step with the settings (interval, unmetered network or any, charging, persisted across reboots). `SaveSyncJobService` runs `SaveSyncService.sync()` and posts a notification only for conflicts or failures.
- **Deletions** (opt-in): the planner knows `DeleteRemote` and `DeleteLocal`. A file deleted on the device is deleted on the server only when the server copy still equals the record of the last sync (time, size, hash); the other way round likewise, with the device file backed up first. Only kinds with a picked folder are considered, so un-picking a folder never looks like a mass deletion. The engine holds all deletions back when there are more than `max(3, records / 4)` until the user confirms. RomM's `POST /api/saves/delete` (`/api/states/delete`) is used, with `DELETE /api/saves/{id}` as a fallback.
- **Conflicts** show both times and sizes, which copy looks newer (`SaveConflictInfo`; the clocks may differ, so it is a hint) and the size difference.

**Library tools** (*Settings → Library tools*)
- **Game sets**: `SheetParser` reads the files a `.cue`, `.gdi` or `.m3u` names; `SetChecker` reports tracks or discs missing from the same folder, empty and unreadable sheets. `PlaylistPlanner` finds games with several discs in one folder (`(Disc 1)`, `[CD II]`, `- Disc 2`) and no playlist and writes the `.m3u` (the sheet or image of each disc, never its tracks).
- **Storage**: `StorageInsights` — space per console, the biggest games, and the queue's need (bytes left plus room to unpack archives) against the free space.
- **Wishlist**: a Room table (`wishlist`); after each finished scan `WishlistRepository` searches the library (`searchKey LIKE`) and notifies once per wanted title.
- **Export**: `CollectionExport` writes CSV (spreadsheet formulas are defused) or a script-free HTML page.
- **Duplicates**: `KeepSuggester` ranks the copies with `VersionPicker`; identical files keep the shallowest folder.

**Downloads**
- **Schedule**: `DownloadGate` watches the network (`NET_CAPABILITY_NOT_METERED`), the battery and the clock; `DownloadPolicy` decides. Downloads that have not started wait inside their job, running ones are not interrupted, *Start now* releases everything waiting at that moment.
- **Checksum**: RomM's `sha1_hash` / `md5_hash` / `crc_hash` are stored with the indexed file (`expectedHash`, `algo:hex`) and travel into the download history; `Content-MD5` and `Digest` response headers are used when the source gives none. The finished, not unpacked file is hashed in the background and compared (`Checksums`).
- **Best version**: `VersionPicker` scores the versions of a title by region order (from the favourite languages; *World* first), wanted language, unwanted markers (beta, proto, demo, unlicensed, bad dumps) and revision.
- **Open**: an `ACTION_VIEW` intent on the finished file's document URI, with a read grant.

**Handheld and project**
- **◀ ▶ in the library** jump by first letter (`LetterJump`), or ten rows when the list is sorted by size.
- **Updates**: the check can include pre-releases (`Settings → Include pre-releases`); a pre-release counts as older than its final release.
- **Diagnostics**: `DiagnosticsService` builds the report; `DiagnosticsRedactor` removes tokens, URLs, magnet links, IP addresses, e-mail addresses and the saved secrets before it is shared.
- **Italian and Portuguese** (European) join the other languages.

### What 1.2.0 adds
- **Faster scanning.** `DatabaseScrapingService` runs every enabled source of every console as its own coroutine, capped per kind (`ScrapingConstants.PARALLEL_HTTP` = 4, `PARALLEL_TORRENTS` = 3, `PARALLEL_ROMM` = 2) and per host (`PARALLEL_PER_HOST` = 2, held only while the listing downloads; parsing happens outside it). The fixed 1 s pause before each request and the 1 s pause per sub-folder row are gone; a 0.2 s pause per request stays. Rows are found by walking the row (`FileParsingUtils.linkOf`) instead of two CSS queries each, the name, tag and size patterns are compiled once, and each source is stored with `DownloadableFileDao.insertSource` in one transaction (tags follow their file by position). Benchmark (emulator, debug build, 6 local web directories × 4,000 files with 8 sub-folders each): 83 s before, about 21 s after; parsing on the device is now the largest share.
- **Torrent metadata cache.** After a magnet's metadata is fetched, its info dictionary is written to `files/torrent_meta/<sha1 of the magnet>.torrent`; the next fetch adds the torrent with that metadata (the magnet's trackers are kept), so a rescan indexes it at once.
- **Owned-games index.** `LibraryIndexService.refresh` lists folders through `DiskScanner` (one provider query per folder) and reads up to four console folders at the same time.
- **Dogmatix+ and its own package.** The application id is `com.tufein.dogmatixplus` (`.debug` for the debug build); the Kotlin namespace stays `com.cortinadev.dogmatix`, so the launch component frontends use is `com.tufein.dogmatixplus/com.cortinadev.dogmatix.MainActivity`. App name, header and welcome screen say *Dogmatix+*.
- **Colours.** Twelve accent presets and Material You (`AccentPresets.dynamic`, stored as `dynamic`): on Android 12+ the theme uses `dynamicLight/DarkColorScheme` (pure black keeps a black background) and derives the Dogmatix tokens from it; the theme is built at one call site so switching never resets the screen.

### Smaller changes
- The app version is **1.2.0** (1.2.0-alpha.1 and 1.1.0-beta.1 before it) (DogmatixPlus numbers its own releases; 1.0.0 was the first); the Credits screen shows the whole lineage, and the update check reads this repository's releases instead of the original project's. It skips pre-releases, and a beta counts as older than its final release.
- The `.md` extension counts as a Mega Drive ROM inside console folders (but not `README.md`).
- 264 unit tests (186 more than Dogmatix 1.2) cover the new logic. One of them runs the save sync of two devices against a real RomM server when `ROMM_TEST_URL` and `ROMM_TEST_TOKEN` are set (it is skipped otherwise); it passed against RomM 3.10.3, 4.0.0 and 5.3.1. The 1.0.0 screens were tried on an emulator, in portrait and landscape, in the debug and in the minified release build; the 1.1.0 beta screens were not (see the release notes).

The full list is in [CHANGELOG.md](CHANGELOG.md).

## Why Dogmatix exists

*This and the sections below are the Dogmatix documentation by [cortinadev](https://github.com/cortinadev/dogmatix), kept in full and updated where DogmatixPlus changes something.*

Milou was designed for phones and touch. On a handheld with a small landscape screen and a controller, that meant tapping a FAB to move between sections, opening full-screen overlays to filter, and reaching for the touchscreen for almost everything. The goal of Dogmatix, the fork DogmatixPlus builds on, is:

- **Gamepad first**: every action reachable from D-pad, A/B/X/Y and shoulder buttons, with a visible focus ring and an on-screen legend.
- **Landscape first**: fixed filter panel next to the list, tabs on top, no wasted vertical space. Portrait still works, with a bottom bar and modal sheets.
- **Flat, minimal look**: a single accent colour, light/dark theme, Manrope typeface, no cards-inside-cards.
- **Works with or without a controller**: every flow is designed for D-pad/buttons first and then checked with touch.

## What Dogmatix changed from Milou

### First run (new)
- Onboarding wizard: what Dogmatix does → pick the root ROMs folder → import a sources JSON. Every step can be skipped; B goes one step back; it never shows again once finished.
- The app ships **without default sources** (`consoles.json` is empty). Import a JSON (see *Sources*) or add consoles by hand.

### Shell and navigation
- The floating "Menu" button is gone. Landscape shows numbered tabs on top (Library · Downloads · Sources · Settings); portrait shows a bottom bar with a downloads badge.
- Full-screen immersive mode with swipe-to-reveal system bars; status bar icons follow the theme.
- Free space of the download volume shown in the shell; a rescan indicator next to the tabs.

### Gamepad support (new)
- Controller detection via `InputManager`; the legend at the bottom only appears when a gamepad is connected and is contextual to the focused area.
- **ZL / ZR** (L2/R2, button or axis) switch section · **LB / RB** switch panel (filters ⇄ list) · **R3** folds / unfolds the filter panel (opens / closes the filter sheet in portrait) · **X** opens the game details popup on the focused row (B or X closes it) · **Y** focuses search · **A** confirms · **B** undoes one layer at a time (sheet/dropdown → search → text → filters → focus back to tabs) and never closes the app.
- The legend is kept short on the list and filter panels (B and LB / RB are left out) so it fits on one line even on 4:3 screens; it scrolls sideways if it still does not fit.
- Every interactive control has a visible focus ring; when changing section the focus parks and the first D-pad press lands on the active tab.

### Library
- Fixed filter panel (Console, Region, Language, Tag, Favourites, Source, Sort) with ◀ ▶ quick change and multi-select dropdowns; portrait uses console chips and a modal filter sheet. Sort offers A → Z, Z → A and size in both directions (ties broken by name, so paging never repeats a game).
- The panel folds into a thin rail (arrow at its bottom-right corner, or R3) to give the list the full width; the slide is animated at 60 fps on the K56, with names, search box and focus ring following the panel edge and ellipses appearing progressively.
- Rows show a short console chip (NES, SNES, PS2…), tags and file extension. When the list is narrower than 560 dp (4:3 screens, or the panel open on a narrow one) rows stack the name above the tags instead of the single-line table, so names are never squeezed out.
- ✓ mark on games that are already in the download folder (`LibraryIndexService`, matched by name with and without extension).
- ★ **Favourites**: Select (or the ★ button in the details popup) stars a game; a "Favourites" filter row shows only starred games. Stored in their own Room table keyed by console + file name, so they survive rescans.
- Lenient search: accents, punctuation and doubled letters are ignored, so "yugioh" finds *Yu-Gi-Oh!* and "virtual tenis" finds *Virtua Tennis* (`SearchNormalizer`).
- Favorite languages (set in Settings) appear first in the language filter.
- Game details popup (X on a row): cover art, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB; results (hits and misses) are cached in Room. API keys go in a git-ignored `.env` (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the popup just says "no data".

### Downloads
- Each row shows the same tags as the library, so repeated versions of a game can be told apart.
- The downloads list is persisted in Room (`DownloadHistory`), so it survives app restarts; in-flight items come back as *Stopped* and can be retried (debrid downloads resume where they left off, see below).
- Rows can also show the RomM upload state (*↑ RomM n%*, *Uploaded*, *failed* + retry).
- **Multi-selection**: Select (long press with touch) ticks rows and turns the summary line into an action bar with only the actions the ticked rows accept — retry, pause, stop, delete — with a single confirmation for the lot. While selecting, A ticks, Y ticks/unticks everything, X deletes and B drops the selection.
- **Pause / resume for torrents**: the pause button parks a download keeping its data; play resumes from the pieces already on disk, even after the app was closed in between.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail and the retry continues it with a `Range` request instead of starting over.

### Sources
- Same structure as Milou, restyled (flat cards, tonal buttons) and navigable with the controller: dialogs open with the field focused, B closes them, deleting a console or URL asks for confirmation (focus starts on *Cancel*).
- URLs can be edited in place, not only added and removed. Deleting a manufacturer also deletes its consoles.
- **Export / import**: the share icon writes a `dogmatix-sources.json` and opens the Android share sheet; the import icon picks a JSON, replaces every source, rescans everything and re-indexes the library. The format is the one of upstream's `consoles.json` plus display names, a `short` label and folder `aliases` per console — export from any device to get one; no sources file is shipped in this repository.
- Each console stores its **short name** (the chip label: GBA, PS2…) and its **folder aliases** (names that count as its download folder). Empty values fall back to the built-in tables, and both are editable in the console dialog, so imported or hand-made consoles behave like the built-in ones.
- If a newer build ships additions in `consoles.json`, they are merged into the existing sources (nothing the user changed is touched) and only the new entries are scraped.
- Download-folder card with the resolved path per console.
- When "separate subfolders by console" is on and the chosen directory already contains a folder used by popular frontends (`gb`, `psx`, `snes`… — ES-DE, RetroArch, Batocera, EmulationStation naming), Dogmatix reuses it instead of creating a new one (`ConsoleFolderAliases`, `ConsoleDownloadPathResolver`).
- Merge folders dialog: when several folders for the same console live side by side (e.g. `gba` and `Gameboy Advance`), move everything into the one you pick.

### Debrid services (TorBox, Real-Debrid)
- Settings → *Debrid service* (Off / TorBox / Real-Debrid) + the matching *API key* row (with a *Test* button that reports the account). When a service is selected, torrent rows are handed to it: the magnet is submitted, the row shows *TorBox n%* / *Real-Debrid n%* while the service fetches it (cached torrents finish at once), then the file is downloaded over plain HTTP through the usual path (speed limit, per-console folder, auto-unzip). Cancelling deletes it from the account; the file is matched by name and size (`DebridMatcher`). Retrying a failed torrent re-reads the setting, so the service can be switched between attempts.
- [TorBox](https://torbox.app): key from *Settings → API*. [Real-Debrid](https://real-debrid.com/apitoken): needs a **premium** account (free accounts can validate the key but `addMagnet` answers `permission_denied`, which the row reports as *Failed*); the wanted file is selected with `selectFiles` before the service fetches it, and the link goes through `unrestrict/link`.
- **Resumable**: the service's torrent/file ids are kept in the download history, the partial file stays on disk, and a retry after the app was killed continues with an HTTP `Range` request instead of starting over (debrid links serve ranges; plain HTTP sources still restart from zero).
- **Limitation**: TorBox zips torrents with 100+ files and its cache is shared, so for the big Myrient/No-Intro sets it can only hand back one huge `.zip`, not single ROMs (`allow_zip=false` only affects torrents nobody has cached yet). When that happens Dogmatix removes the torrent from the account and silently falls back to the direct torrent download for that file, so the toggle is safe to leave on.

### RomM
- Settings → *Save sync* keeps emulator saves and save states in sync with the server (see [Save sync with RomM](#save-sync-with-romm-110-beta)).
- Settings → *RomM server*: URL, API token (`rmm_…` client token or `user:password`), *Test connection*, *Upload finished downloads*, and one stepper per console to pick the RomM platform it uploads to. Suggestions come from the same folder-alias table used for frontend folders (`gba`, `psx`, `snes`…); *Apply suggestions* maps every unmapped console at once.
- Every download that finishes while the switch is on (and whose console is mapped) is uploaded with RomM's chunked API (`/api/roms/upload/start` → chunks → `complete`); extracted archives upload each extracted file. The Downloads row shows *↑ RomM n%*, *Uploaded* or *failed* with a retry button. A cut-short upload resumes from the chunk it stopped at (also after the app was killed). Plain `http://` servers are allowed (cleartext traffic is enabled for the app); self-signed HTTPS works after you confirm the certificate's fingerprint once (*RomM server → Server certificate*).
- **RomM as a source**: in Sources → *Add URL*, pick one of the server's platforms (or type `romm://<slug>`) and the console indexes every ROM RomM has for it; downloads go through RomM's `/api/roms/{id}/content/…` with the account credentials, and files that came from RomM are never uploaded back.
- RomM only lists a ROM once it has scanned it, and scanning is not exposed through its REST API: either run a scan from the RomM web UI after uploading, or start RomM with `ENABLE_RESCAN_ON_FILESYSTEM_CHANGE=true` so uploaded files are picked up automatically.

### Settings
- Theme (System / Light / Dark / **True black**, a pure `#000000` background for AMOLED screens) and accent colour (5 presets), persisted in DataStore.
- Language (System / English / Spanish / Dutch / French / German / Italian / Portuguese). *(DogmatixPlus added Dutch, French, German, Italian and Portuguese.)*
- Download directory, concurrent downloads and speed limit as steppers (◀ ▶ with the controller), switches for auto-unzip and per-console subfolders, favorite languages picker, "About & contact".
- *Maximum search results* stepper (50 / 100 / 250 / 500 / Unlimited, default 100): how many games a library search loads at once; *Load more* fetches the next batch, *Unlimited* lists everything the filters match.
- *Metadata timeout* stepper (10–180 s, default 20): how long a rescan or a direct torrent download waits for a magnet's file list before giving up — raise it on slow trackers/DHT.
- *Debrid service* stepper (Off / TorBox / Real-Debrid) with a single *API key* row for the selected service (dialog with *Test*); the key is masked in the row and stored only on the device.
- *Gamepad layout* stepper (Xbox / Nintendo / PlayStation): draws the button legend the way your pad is printed — Xbox A/B/X/Y with `LB · RB` / `LT · RT`, Nintendo the same letters in Super Famicom colours (A red, B yellow, X blue, Y green) with `L · R` / `ZL · ZR`, PlayStation ✕ ○ □ △ each in its own colour with `L1 · R1` / `L2 · R2`. Names and colours only: A (✕) always accepts and B (○) always goes back.
- *Swap A/B and X/Y* switch, for pads that report their face buttons the other way round: it moves the actions and leaves the legend untouched, dialogs and the filter sheet included.
- **Frontends**: *Frontend shortcuts* drops a `.dgmtx` shortcut into every console folder; *Configure ES-DE* and *Configure iiSU* do the whole frontend setup in one button, and *Set up Daijishō* hands over the values its emulator form needs — see [FRONTENDS.md](FRONTENDS.md) and the *Deep links* section below.
- Two-column layout in landscape.
- **DogmatixPlus rows**: *Library overview*, *Duplicate games*, *Back up* and *Restore backup* (see [What DogmatixPlus adds](#what-dogmatixplus-adds)).

### Under the hood
- Package renamed to `com.cortinadev.dogmatix` (upstream: `com.santiifm.milou`); application class, database and theme renamed accordingly. The app id changed, so Dogmatix installs as a separate app and does not update over a Milou install.
- Room schema at v9 with explicit migrations (search key, download history, game metadata cache, per-console short name and folder aliases, favourites, debrid resume ids).
- All source handling lives in `SourcesRepository`; the JSON format is read and written by one pure `SourcesJson` object shared by the bundled asset, the Room `urls` column and export/import. Sources are routed by URL: `magnet:`/`.torrent` → libtorrent, `romm://` → the RomM API, anything else → HTML directory scraping.
- Downloads are routed in `DownloadService.perform()`: debrid service (when selected; `DebridClient` implemented by `TorBoxClient` and `RealDebridClient`) → HTTP with resume; torrent → libtorrent; HTTP otherwise (with the RomM credentials when the file comes from the RomM server). Integrations talk to their APIs with a tiny `JsonHttp` helper over `HttpURLConnection` + Gson — no OkHttp.
- Secrets (TorBox / Real-Debrid keys, RomM token) live only in the app's DataStore; nothing is baked into the APK or the repository.
- DogmatixPlus adds `DuplicateFinder`, `DiskScanner`, `LibraryScanService`, `BackupJson` and `BackupService` (see [What DogmatixPlus adds](#what-dogmatixplus-adds)); the unit tests grew from 78 to 136.
- Unit tests for the pure logic: search/parsing (`SearchNormalizer`, `ConsoleFolderAliases`, `GameTitleCleaner`, `LibraryKeys`), sources (`SourcesJson`), deep links and shortcuts (`DeepLinkParser`, `DeepLinkResolver`, `DgmtxFile`), frontends (`EsdeXml`, `IisuJson`, `DaijishoSetup`), debrid and RomM (`DebridMatcher`, `RommPlatformMapper`, `RommSource`), save sync (`SaveSyncPlanner`, `SaveSyncEngine` with an in-memory device and server, plus `RommSaveSyncLiveTest` against a real server), versions (`VersionUtils`), and the gamepad legend (`GamepadLayout`).
- Removed: FAB, `SearchSection`, old filter overlays/dropdowns, `RomList`, `SmallButtons`, `CommonButton`, `Spacing`/`Layout`.

## How it works (inherited from Milou)

A file called **Burnout Paradise (En,Es,Fr) (NTSC).zip** in a torrent becomes
**Name: Burnout Paradise · Tags: Languages [En, Es, Fr], Region [NTSC], Console: Xbox 360, Manufacturer: Microsoft, Extension: .zip**.

1. **First launch**: the app starts with no sources (`consoles.json` is empty). Add consoles by hand or import a sources JSON from the Sources tab (export one from a device that already has sources; source files are deliberately not part of this repository).
2. **Search**: filter by console, region, language, type or extension.
3. **Download**: press A / tap on a game; torrents and direct HTTP links are both supported.
4. **Manage**: follow progress in Downloads; archives are extracted automatically.
5. **Configure**: download folder, per-console paths, speed limit, concurrency, theme.

## Building

Requirements: JDK 17 or newer (the releases were built with JDK 21), Android SDK platform 36, build-tools 36.0.0.

```bash
git clone https://github.com/Tufein/DogmatixPlus.git
cd DogmatixPlus

./gradlew assembleDebug      # debug APK (applicationId com.cortinadev.dogmatix.debug)
./gradlew assembleRelease    # release APK (minified, signed with the debug keystore)
./gradlew test               # JVM unit tests
./gradlew lint
```

The debug build uses the `.debug` application-id suffix so it can be installed next to a release build. The package is `com.cortinadev.dogmatix` (renamed from upstream's `com.santiifm.milou`).

### Project structure
```
app/src/main/java/com/cortinadev/dogmatix/
├── data/        Room entities/DAOs, repositories, services (scraping, torrents, downloads, extraction, library scan, backup)
├── di/          Hilt modules
├── ui/
│   ├── common/      Gamepad detection and shortcut bus
│   ├── components/  AppShell, TagChip, FocusHighlight, Stepper, GamepadLegend
│   ├── screens/     onboarding, home (Library), download, sources, settings, tools (overview, duplicates), contact
│   └── theme/       DogmatixTheme, palettes, ThemeMode, accent presets
└── util/        File-name parsing, folder aliases, search normalization, storage helpers
app/src/main/assets/consoles.json   Default sources (empty in Dogmatix)
app/src/test/                       JVM unit tests
```

## Tech stack

### The app
- **Language**: Kotlin 2.3.20 — all of the code (Java 11 source / target), Android `minSdk` 29, `compileSdk` / `targetSdk` 36
- **UI**: Jetpack Compose (BOM 2026.03.01), Material 3, Navigation Compose 2.9.7, Coil 2.7.0 (cover art)
- **DI**: Hilt 2.59.2 (KSP)
- **Persistence**: Room 2.8.4 + FTS, DataStore Preferences 1.2.1
- **Files**: Storage Access Framework (`DocumentsContract`, `DocumentFile`) for the ROM folders — one provider query per folder when scanning
- **Torrents**: libtorrent4j 2.1.0-39 (arm64-v8a, armeabi-v7a, x86_64) · **HTTP**: `HttpURLConnection`, Jsoup 1.22.1
- **JSON**: Gson 2.13.2, plus hand-written readers and writers for sources and backups (they stay readable after R8 renames fields)
- **Archives**: 7-Zip-JBinding-4Android (ZIP/7z)
- **Concurrency**: Kotlin Coroutines 1.10.2 + Flow

### How DogmatixPlus was built
- **Build**: Gradle 9.3.1 (wrapper) with Android Gradle Plugin 9.1.0, JDK 21 (OpenJDK), Android SDK platform 36 and build-tools 36.0.0 from the command-line tools; **R8** minification and resource shrinking for the release build
- **Tests**: JUnit 4 — 136 JVM unit tests for the pure logic — and Android Lint
- **Trying it out**: the Android Emulator (an Android 15 / API 35 arm64 Google APIs image) driven with `adb` — installing, taps and swipes, logcat, screenshots, and `truncate` to create made-up ROM files — in portrait and landscape, in the debug and in the minified release build
- **Checking the APKs**: `aapt2` (package, version, languages) and `apksigner` (signature) on every APK before it was released
- **Test data**: small Python 3 scripts (not part of this repository) ran a local web server with made-up game indexes, so scans, the progress display and the duplicate finder could be tried without any real sources
- **Releases**: git and the GitHub REST API for commits, tags and the release with its APKs and checksums
- **AI assistance**: an AI coding assistant wrote and refactored code, tests and documentation, and independent AI review passes were run over the changes before they were published (see [Credits](#credits))

## Deep links

Other apps and frontends can open the library with filters already applied:

```
dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&q=mario&fav=1
```

| Parameter | Meaning |
|---|---|
| `console` | Console ids as shown in Sources (comma-separated) |
| `region`, `lang`, `type`, `filetype`, `tag` | Library tags (comma-separated); the kind is inferred from the value |
| `q` | Search text |
| `fav` | `1`/`true` shows favourites only, `0`/`false` everything |

Try it with `adb shell am start -a android.intent.action.VIEW -d "dogmatix://library?console=nintendo_snes&q=mario"`. A link opened while the app is on another tab switches to the Library; one opened during onboarding is applied once onboarding finishes.

Dogmatix also opens `.dgmtx` shortcut files (a text file carrying one of these links), and
Settings → *Frontend shortcuts* drops one into every platform folder — that's how it shows up
as an "emulator" inside ES-DE, iiSU or Daijishō. ES-DE and iiSU also get a one-button setup
in Settings ("Configure ES-DE" / "Configure iiSU"); Daijishō has no importable emulator
configuration, so "Set up Daijishō" hands over the values to type into it. See
[FRONTENDS.md](FRONTENDS.md) for the frontend configuration.

## Controller cheat sheet

| Button | Action |
|---|---|
| D-pad | Move focus |
| A | Select / download |
| B | Back one layer (close sheet, clear search, back to tabs) |
| X | Game details popup (B or X closes) |
| Y | Focus search |
| Select | Star / unstar the focused game · in Downloads, tick rows for multi-selection |
| LB / RB | Switch panel |
| R3 | Fold / unfold the filter panel |
| ZL / ZR | Previous / next section |

Everything is also reachable by touch; the legend only appears while a controller is connected, and button names and colours follow the *Gamepad layout* setting (Xbox / Nintendo / PlayStation).

## Roadmap

Planned features, in no particular order:

- Try everything in 1.2.0-alpha.1 against real RomM servers and a real handheld, and fix what that shows (certificate trust, resumed uploads, deletion sync, background sync, covers, the schedule and the checksum check have only been covered by unit tests so far).
- Per-console save folders for standalone emulators.
- A "wanted" status from the wishlist that also checks RomM's library, and cover art for frontends other than ES-DE.
- ~~Mark games already in RomM as owned in the library~~ — done in 1.2.0-alpha.1.
- ~~Save sync in the background~~ — done in 1.2.0-alpha.1.
- ~~Favourites sync across devices via the sources export~~ — done in 1.1.0.
- ~~Saves and states on the RomM server~~ — done in 1.1.0 (beta).

Ideas and requests for DogmatixPlus are welcome as [issues](https://github.com/Tufein/DogmatixPlus/issues); for the original Dogmatix, see [cortinadev/dogmatix](https://github.com/cortinadev/dogmatix/issues).

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.

Milou and Dogmatix do not carry a licence, so all rights to their code stay with their authors. DogmatixPlus is an unofficial personal modification, shared for educational purposes and not affiliated with either project. If you are one of the original authors and would like something changed or removed, please open an issue.

## Credits

DogmatixPlus is a small layer on top of two projects. Most of what you use every day is theirs.

| Project | Who | What it brought |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | The original app and its whole engine: indexing of torrents, magnets and web directories, tagging by console / region / language, searching, direct and torrent downloads, archive extraction. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) ([cortina.dev](https://cortina.dev)) | The handheld rebuild: gamepad-first navigation and legend, landscape layout, themes, favourites, multi-selection and pause / resume in Downloads, onboarding, ES-DE / iiSU / Daijishō integration, TorBox and Real-Debrid, RomM, the `dogmatix://` deep links. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | The duplicate finder, the library overview, backup & restore, scan progress with percentage and time left, save sync with RomM (also in the background, with deletions), favourites in the sources export, RomM game marks, self-signed HTTPS, resumable uploads and ES-DE covers, game-set checks and playlists, storage overview, wishlist, collection export, a keep-the-best-copy suggestion, a download schedule, checksum checks, best-version choice, letter jump, diagnostics, the Dutch, French, German, Italian and Portuguese translations (and moving the last hard-coded English texts into them), the update check pointing at this repository, this documentation and the releases. |

DogmatixPlus was **made with the help of A.I.**: the code, the tests and this documentation were written together with an AI assistant, then checked in several independent review rounds and tried on an emulator. Decisions, direction and publishing are the maintainer's.

Libraries the apps rely on are listed under [Tech stack](#tech-stack): Jetpack Compose, Hilt, Room, libtorrent4j, 7-Zip-JBinding, Jsoup and Kotlin Coroutines.
