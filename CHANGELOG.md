# Changelog

All notable changes to Dogmatix are listed here. Dogmatix is a fork of
[Milou](https://github.com/santiifm/milou) focused on UI/UX for Android handhelds.

## [2.0.0] – 2026-10-03 · Dogmatix+

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

## [1.3.0] – 2026-10-03 · Dogmatix+

### Fixed
- **Far fewer scan errors with busy servers** (seen with large Nintendo Switch sets). 1.2.0 scanned
  sources side by side; servers that limit how often they may be asked answered with *429 Too Many
  Requests* or *503*, and those sources failed after a few quick retries. Now:
  - a listing is tried up to five times, waiting what the server asks (`Retry-After`, up to two
    minutes) or 3, 6, 12, 24 s;
  - a server that pushes back is asked one request at a time, 1.5 s apart, for the rest of the scan;
  - a missing page (404) or refused access (403) is not retried pointlessly;
  - a torrent whose file list does not arrive in time gets a second try;
  - two torrent sources are fetched at a time instead of three.

### Added
- **Scan report**: failures no longer pop up one by one; at the end one dialog lists every source
  that failed with the reason (rate limit, server error, not found, bot check, timeout, no connection,
  no file list, torrent metadata) and **Scan these again** retries only those — nothing else is cleared.
- **Status per source** in Sources: under every URL, how many games its last scan gave and when, or
  why it failed.
- **File explorer** (*Settings → Library tools → File explorer*): browse the download folder, the
  per-console folders, the save folders and the ES-DE folder; sort by name or size; add up a folder's
  size; check a folder's disc sets; see whether a file counts as a game; open a file in another app
  or delete it (after a confirmation; there is no recycle bin).

### Changed
- 271 unit tests.

## [1.2.0] – 2026-10-03 · Dogmatix+

The first full release since 1.0.0: everything from 1.1.0-beta.1 and 1.2.0-alpha.1, plus the changes below.

### Changed
- **Scanning is much faster.** Sources are scanned side by side (up to 4 web directories, 3 torrents
  and 2 RomM platforms at a time, at most 2 listings per server) instead of one after the other; the
  fixed 1-second pause before every request — and after every sub-folder row, which was never even
  read — is gone (a 0.2 s pause per request stays, out of politeness); each source is written in
  one database transaction; rows are parsed without a CSS query each and the name / size patterns
  are compiled once. In a test with 6 web directories of 4,000 games each (emulator, debug build) a
  full rescan went from **83 s to about 21 s**.
- **Torrent metadata is kept** after the first fetch (`files/torrent_meta`), so a rescan indexes a
  magnet at once instead of asking the swarm again.
- The check which games are already on the device lists each folder with one query instead of one
  per file, and reads the console folders a few at a time.
- **The app is called Dogmatix+** (header, launcher and welcome screen) and has its **own package
  name, `com.tufein.dogmatixplus`** (debug build: `com.tufein.dogmatixplus.debug`). It installs as
  a new app next to the official Dogmatix and next to older DogmatixPlus builds; move your setup
  over with *Settings → Back up* in the old app and *Restore backup* in the new one. Frontend setups
  (ES-DE, iiSU, Daijishō) have to be run once more, because they point at the app by package name.
- **Accent colours**: 12 instead of 5, picked from a dialog, plus **Material You** (Android 12 and
  later): the whole colour scheme then follows the wallpaper, in light, dark and pure black.

### Fixed
- Two files with the same name in different folders of one torrent no longer share their tags.

## [1.2.0-alpha.1] – 2026-10-03 · DogmatixPlus (pre-release)

### Added
- **RomM**
  - **Games already on RomM are marked** in the library with a *RomM* tag, and the details card says
    so (Settings → RomM server → *Mark games on RomM*; the server's list is read per mapped
    platform, kept on the device and refreshed every six hours, on a change of the settings or by
    hand). A finished upload marks its game at once.
  - **Self-signed HTTPS**: *Server certificate* → *Check* reads the certificate without sending any
    credentials, shows its SHA-256 fingerprint and, after you confirm, trusts exactly that
    certificate for that server (a name mismatch such as an IP address is accepted for it too).
    A failed connection test with a certificate problem offers the same question. Every other host
    and certificate still goes through Android's normal checks; *Forget* removes the trust.
  - **Uploads resume**: a cut-short chunked upload keeps its server session (written to disk), so the
    next try continues at the first chunk the server has not got, also after the app was killed
    (picked up again at the next start). If the server no longer knows the upload it starts over.
  - **Covers for ES-DE**: fetches RomM's cover art for games on the device that ES-DE shows without
    one, into `downloaded_media/<system>/covers`; existing covers are never replaced.
- **Save sync**
  - **Background sync** (Settings → Save sync): a periodic job every 1, 3, 6, 12 or 24 hours, on Wi-Fi
    or any network, optionally only while charging; it survives a reboot and notifies only when a
    save needs a choice or something failed.
  - **Sync deletions** (opt-in): a save deleted on one side is deleted on the other, but only when the
    other side is exactly as the last sync left it (a changed save is never deleted); a device file is
    backed up first; more than a few deletions at once (over a quarter of the synced files) are held
    back until you apply them.
  - **Clearer conflicts**: both times, both sizes, which copy looks newer and the size difference.
- **Library tools** (Settings → Library tools)
  - **Game sets**: finds disc images that cannot run — a `.cue` or `.gdi` naming tracks that are gone, a
    `.m3u` naming a deleted disc, an empty sheet — and multi-disc games without a playlist, whose
    `.m3u` can be created in one tap.
  - **Storage**: space per console, the 15 biggest games (deletable after a confirmation) and whether
    the downloads still queued fit in the free space; the Downloads list warns when they do not.
  - **Wishlist**: titles you want (for any console or one); after every scan the library is searched
    and you get a notification the first time one turns up. A search without results offers to add
    what you typed. Part of backups.
  - **Export** the collection as a CSV file or a self-contained web page.
  - **Duplicates**: suggests which copy of each group to keep (your regions and languages, no demos,
    prototypes or bad dumps; the shallowest folder for identical files) and removes the rest after
    one confirmation that lists every file; no suggestion where two copies rank the same.
- **Downloads**
  - **Schedule**: only on Wi-Fi, only while charging, only in a night window (default 23:00–07:00);
    waiting downloads say what they wait for and *Start now* lets them go.
  - **Checksum**: a finished file is compared with the hash its source published — RomM lists one per
    game; `Content-MD5` and `Digest` headers are used too — and shows *✓ checksum verified* or a
    warning. Archives that are unpacked are not checked.
  - **Best version**: the details card shows how many versions of a game the library lists and offers
    *Best version* (region and language from your favourite languages, no demos, later revisions).
  - **Open**: a finished download opens in whichever app handles the file.
- **Handheld**: **◀ ▶ jump through the library by first letter** (by ten rows when sorted by size).
- **Project**: **pre-releases in the update check** (Settings → *Include pre-releases*) and a *Check for
  updates* button; **share diagnostics** — a text report with versions, setup and the app's own
  recent log, with tokens, server addresses and magnet links removed; **Italian and Portuguese**.

### Changed
- Version 1.2.0-alpha.1. The database moves to version 10 (wishlist table, expected-hash columns); an
  existing library is kept.
- 260 unit tests (93 more than 1.1.0-beta.1).

## [1.1.0-beta.1] – 2026-10-03 · DogmatixPlus (pre-release)

### Added
- **Save sync with RomM** (Settings → Save sync): the emulator saves and save states on the
  device and on the RomM server are kept the same, in both directions, so a game can be
  continued on another device or in RomM's web player.
  - Pick the saves folder and the save-states folder (RetroArch: `saves`, `states`; one folder
    for both works too). Files up to three folder levels deep are synced; thumbnails, configs,
    temporary files and files over 64 MB are skipped.
  - A file belongs to the ROM with the same name without extension, found with RomM's search
    and checked exactly; a platform-named folder (`saves/gb/…`) settles names that exist on
    several platforms, otherwise the file is left alone. The first folder level (the
    RetroArch core) is sent as RomM's *emulator* and used again when downloading.
  - Three-way: only the side that changed since the last sync is copied; when both changed,
    nothing is overwritten and the screen lists the file to keep *◀ Device* or *RomM ▶*. On
    first meeting, equal files are recorded and different ones listed. Changes are detected
    from size, time and RomM's content hash, so device clocks do not matter.
  - A device file replaced by a download is copied to the app's private storage first (kept
    30 days); downloads replace the old file only once fully written. Deletions are not synced.
  - *Sync now*, and optionally automatically when the app opens or comes back to the front
    (at most every two minutes), with a short message when something moved.
  - Works with RomM 3.10, 4 and 5 (tested against 3.10.3, 4.0.0 and 5.3.1; newer servers take
    `saveFile` / `stateFile`, older ones `saves` / `states`; RomM 3's phrase search is
    handled), with an `rmm_…` client token or `user:password`.
  - The two folders and the switch are part of backups.
- **Favourites sync** (roadmap): a sources export now carries the ★ favourites, and importing it
  on another device adds them there (union; the earliest date wins; older versions ignore it).

### Fixed
- The update check compared `1.1.0-beta.1` as `1.1.0.1`, which would have hidden the final
  1.1.0 from beta users; a pre-release now counts as older than its release.

### Changed
- The RomM `user:password` login is encoded with `java.util.Base64` (same result).
- 167 unit tests (31 more), among them a two-device save sync against a real RomM server that
  runs when `ROMM_TEST_URL` / `ROMM_TEST_TOKEN` are set.

## [1.0.0] – 2026-10-03 · DogmatixPlus

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

## [1.2] – 2026-09-02

### Added
- **Gamepad layout**: a Settings stepper (Xbox / Nintendo / PlayStation) draws the button
  legend the way the pad in your hands is printed. Xbox keeps A/B/X/Y with the by-role colours
  (green confirms, red goes back) and names the shoulders `LB · RB` / `LT · RT`; Nintendo keeps
  the same letters in the Super Famicom colours (A red, B yellow, X blue, Y green) and calls
  the shoulders `L · R` / `ZL · ZR`; PlayStation draws ✕ ○ □ △, each in the colour of its
  shape, with `L1 · R1` / `L2 · R2`. Only the drawing changes: A (✕) always accepts and B (○)
  always goes back, whichever layout is picked.
- **Swap A/B and X/Y**: a Settings switch for pads that report their face buttons the other way
  round — it moves the actions and leaves the legend exactly as it is, so what the legend says
  matches the button you press. It applies everywhere, dialogs and the filter sheet included.
- **Maximum search results**: a new Settings stepper (50 / 100 / 250 / 500 / Unlimited,
  default 100) sets how many games a library search loads at once; "Load more" still fetches
  the next batch, and "Unlimited" drops the limit and lists everything the filters match.
- **Multi-selection in Downloads**: tick several downloads and act on all of them at once.
  SELECT (long press with touch) ticks the row under the cursor and turns the summary line
  into an action bar — retry, pause, stop, delete — showing only the actions the ticked rows
  accept. A ticks rows while selecting, Y ticks / unticks everything, X deletes the selection
  (one confirmation for the lot when some of them finished) and B drops it. Retry, pause and
  stop keep the selection so actions can be chained.
- **Frontend integration (.dgmtx shortcuts)**: Dogmatix opens `.dgmtx` files — tiny text
  files carrying a `dogmatix://library?…` deep link — so frontends like ES-DE can list it
  as an "emulator" per platform. A new Settings row ("Frontend shortcuts") drops a
  `★ Search for more games....dgmtx` shortcut into every console's download folder, ready
  for the frontend to scan (the star keeps it at one end of the game list); `banner.png`
  in the repo serves as its preview image (see FRONTENDS.md).
- **One-button ES-DE setup**: Settings → "Configure ES-DE" picks the ES-DE data folder once
  and writes everything itself — shortcuts, find rule, per-platform system overrides (built
  from the es_systems.xml bundled inside the installed ES-DE, so nothing is lost or
  outdated), gamelist entries with `altemulator` (the system's default emulator is
  untouched), and the banner as cover art. Existing files are merged, never truncated.
  When ES-DE is detected, the first-run tour offers this same setup as its final step.
- **One-button iiSU setup**: Settings → "Configure iiSU" picks iiSU's `iiSULauncher` data
  folder once (inside `Android/media/com.iisulauncher/`, which SAF can reach) and adds
  Dogmatix as one more emulator in its `emuladores.json`: `.dgmtx` joins the console's
  accepted extensions and a `DOGMATIX` entry is appended last, so the console keeps its own
  default emulator and only the shortcut is pointed at Dogmatix with iiSU's per-ROM
  *Override Emulator*. Only consoles whose folder got a shortcut are touched and nothing is
  ever removed; on a fresh iiSU install the defaults bundled inside its APK are used as the
  base. Note that applying an `emuladores.json` update from iiSU's own updater drops these
  additions — running the setup again puts them back.
- **Assisted Daijishō setup**: Settings → "Set up Daijishō" deploys the shortcuts and shows
  the three values its *Add an emulator* form needs, each with a Copy button. Daijishō keeps
  its players in a private database and exposes no intent, deep link or importable emulator
  configuration, so that last step is typed in by hand; a custom emulator there is global, so
  one entry covers every console and it survives Daijishō's automatic platform updates.
- **Pause / resume for torrent downloads**: a pause button on active rows (A on the gamepad)
  parks the download keeping its data; play resumes from the pieces already on disk — even
  if the app was left and the torrent session restarted in between.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail (connection
  drop included) and the retry continues it with a Range request instead of starting over.
- **Sort the library by size**: the "Sort" filter row gains "Size: big → small" and
  "Size: small → big" next to A → Z and Z → A, handy for spotting the heavyweights (or the
  quick downloads) of a console. Ties are broken by name so paging through a long list never
  repeats or skips a game.

- A tiny, faint version indicator under the app logo in both headers.
- **True black theme**: a fourth theme mode ("True black") with a pure `#000000` background
  for AMOLED screens, next to System / Light / Dark in the Settings stepper. Panels and
  cards sit barely above black so the layout still reads; text and accents reuse the dark
  palette.

### Fixed
- Per-file download speed: rows from the same torrent showed the torrent's total rate.
- A deep link (or .dgmtx shortcut) for NES also selected SNES: for bare console ids like
  `super_nintendo_entertainment_system` the derived folder name dropped the first word,
  so SNES's alias set contained NES's full name. Also fixes the default download folder
  for such consoles (`playstation_2` would have created a folder literally named "2").

## [1.1.4] – 2026-08-31

### Added
- Each source URL now has an on/off switch in Sources: disabled sources are kept (and
  exported/imported) but skipped when rescanning, so their games drop out of the library on
  the next rescan and come back when re-enabled.

### Fixed
- **Downloads marked "Completed" with nothing (or a 0-byte file) in the ROMs folder.** When
  writing to the download cache failed (e.g. the internal storage filled up mid-download),
  the truncated file was still copied into place and the row marked completed; a later
  progress tick could also overwrite a failed status with "completed". Incomplete data now
  fails the download honestly, failed/stopped rows stay failed until retried, and the retry
  starts clean.
- **Retry button did nothing on "Completed" rows.** Downloads that the 1.1.2 bug had falsely
  marked completed showed a retry button that was silently refused; retrying a finished
  download (i.e. downloading it again) is now allowed.
- **Downloads are now fully gamepad-accessible**: the per-row buttons could not be reached
  with the D-pad. A on a focused row retries (or stops an active download), X deletes it —
  with a confirmation dialog on completed rows that starts focused on "Keep file" and closes
  with B. The button legend shows the new shortcuts.

## [1.1.3] – 2026-08-31

### Fixed
- **Queued downloads from the same torrent failing** (1.1.2 regression): releasing a finished
  torrent deleted its cached files asynchronously, which could wipe the files of the same
  torrent when the next queued download re-added it (flashing Failed/Completed rows, endless
  partfile errors, nothing copied to the ROMs folder). Partial files — the partfile included —
  are now deleted synchronously and individually. Verified on device with six queued downloads
  from one torrent.
- The *Tag* filter lists the fixed set of content-type tags again (Game, Demo, Beta, Proto…),
  as *Type* did before 1.1.1 — only the filter's name changed.

## [1.1.2] – 2026-08-30

### Fixed
- **App cache growing to gigabytes.** Torrent downloads are staged in the app cache and were
  only removed after a successful copy: stopping or failing a download left the partial file
  behind for the rest of the session, and every rescan wrote a few random pieces while the
  metadata was being fetched. Torrents are now removed together with their files when stopped,
  failed or timed out, and metadata is fetched in upload mode (no pieces requested).

## [1.1.1] – 2026-08-30

### Added
- **Source filter** in the Library (All / Torrent / RomM / Direct link): shows where each
  entry comes from, so ROMs served by your RomM server can be listed on their own.
- **Tag filter** replaces the old *Type* filter. It covers every tag that is not a region,
  language, video standard or file extension — Game, Demo, Beta, Proto, Rev, Unl… — instead of
  the fixed content-type list, so tags like *Beta* or *Proto* are now filterable.

### Fixed
- **Metadata fetch failing on large torrents.** libtorrent rejects torrent info dicts above
  3 MiB by default; multi-TB collection torrents (e.g. MiNERVA's *Redump – Microsoft – Xbox*,
  ~30 MB of metadata) were rejected on every peer and always timed out. The limit is now
  256 MiB, and the same magnet fetches in about 20 s.
- **Metadata timeout is now an inactivity timeout.** The clock restarts whenever bytes or new
  peers arrive, so slow but healthy swarms are no longer cut off; it only gives up after the
  configured time with no progress (hard cap: 30 windows). Default raised from 20 s to 30 s.

### Changed
- Settings → *Metadata timeout* hint explains the new inactivity behaviour (EN/ES).
- Onboarding copy mentions the tag and source filters.

## [1.1] – 2026-08-29

### Added
- Favourites (★) with a *Favourites only* filter; Select on the gamepad toggles a game.
- Deep links: `dogmatix://library?console=…&region=…&lang=…&q=…&fav=1` (short console
  names and aliases accepted; tags case-insensitive).
- Debrid downloads through TorBox or Real-Debrid (API key in Settings, resumable, falls back
  to a direct torrent when the service only has a zipped set).
- RomM integration: automatic upload of finished downloads, and `romm://<platform>` as a
  library source.
- Spanish UI with a language setting (System / EN / ES).
- *Metadata timeout* stepper in Settings.

## [1.0.1] – 2026-08

### Fixed
- Content kept clear of display cutouts (notches) in every orientation.
- Owned-games index scoped by console folder (same name in `snes/` no longer marks `gbc/`).

## [1.0] – 2026-08

First Dogmatix release, forked from Milou:
- Package renamed to `com.cortinadev.dogmatix`.
- New flat theme (Manrope, light/dark, accent colour), full-screen shell with top tabs in
  landscape and bottom bar in portrait, gamepad legend and D-pad focus everywhere.
- Fixed filter panel (collapsible in landscape), game details card (RAWG / TheGamesDB),
  Settings redesign, onboarding, sources import/export as JSON.
