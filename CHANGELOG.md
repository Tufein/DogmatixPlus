# Changelog

All notable changes to Dogmatix are listed here. Dogmatix is a fork of
[Milou](https://github.com/santiifm/milou) focused on UI/UX for Android handhelds.

## [7.5.0] – 2026-10-06 · Dogmatix+ (pre-release)

### Added
- Second screen: Pause all / Resume all and per-download pause/resume.
- Pause on low battery (with hysteresis) and when the device is hot (battery temperature and Android's thermal status); "Waits for: low battery / device too hot".
- Notification actions: Pause all, Resume all, Stop all on the ongoing notification; per-game "downloaded" notice with Open in the library and Open the downloads.
- Best source: a per-source track record (speed, success rate), ranking by reliability then speed, one automatic switch to the next source after a final failure; track record on the source cards.

### Changed
- The queue hold (*Pause all*) now parks running downloads that can resume (torrents, web downloads with a partial file); the others still finish.

### Notes
- No database change (version 13). Not yet tried on a real device.

## [7.0.0] – 2026-10-06 · Dogmatix+

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

## [6.0.0] – 2026-10-05 · Dogmatix+

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

## [5.0.0] – 2026-10-05 · Dogmatix+

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

## [4.0.0] – 2026-10-04 · Dogmatix+

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

## [3.5.0] – 2026-10-04 · Dogmatix+

### Downloads
- **Download all takes a whole console set**: up to 3000 games per tap instead of 500. The dialog
  still shows how many games and how much space before anything starts.
- **Hold the queue** (*Downloads → Hold the queue*): running downloads finish, nothing new starts
  until *Resume the queue*. The hold stays after a restart.
- **What is left**: the line above the Downloads list shows how much the queue still has to fetch and,
  while downloads run, roughly how long that takes at the current speed.

### What's new
- After an update Dogmatix+ shows the highlights of the new version once (not on a fresh install).

### Settings and Tools, tidied up
- **Settings in groups**: *Look and controls*, *Downloads*, *When to download*, *After a download*,
  *Torrents and debrid*, *Library and sources*, *Frontends*, *RomM and saves*, *Profiles*, *Backup*
  and *App and updates*, each with a heading, instead of one long list split over two columns at
  random. The way into *Tools* is now the first row. In portrait **LB / RB** jump to the previous or
  next group; in landscape they still hop between the two columns.
- *Library overview* and *Duplicates* are no longer listed twice: they live in *Tools*.
- **Tools in groups** as well: *Library*, *Games and checks*, *Frontends* and *Export*.

### Fixed
- **"App isn't responding" after queueing a whole console.** Every time a download finished, the
  library looked up *every* finished download again in the database, by file name, and that column
  has no index: with a few thousand finished rows that grew into millions of full table scans, which
  kept the database and the processor busy long after the batch (and again after a restart, from the
  download history). Each finished download is now handled once, with the console it came from
  instead of a database lookup.
- The Downloads list looked up the game behind each row one by one (again a full table scan per
  row); it now asks a few hundred at a time, off the UI thread, and the rows fill in per batch.
- The counters above the Downloads list, the "already downloading" marks in the library and the
  *Download all* planning are worked out off the UI thread.
- After-download steps (covers, playlists, RomM upload, the download log) no longer miss games when
  many small downloads finish in quick succession.

## [3.3.0] – 2026-10-04 · Dogmatix+

### Downloads
- **Failed downloads retry by themselves** (*Settings → Retry failed downloads by itself*, on by
  default): a download that failed because the connection dropped, timed out or the server was
  busy (HTTP 408, 429 or 5xx) is started again after 1, then 5, then 15 minutes, and then left to
  you. A missing file (404), a refused one (403) or a folder problem is not retried. Pressing
  *Retry* yourself, or deleting the row, starts the count again. Torrents and debrid downloads that
  end in a failure of their own are not retried this way.
- **Checked against your DAT after the download.** When the source publishes no checksum but you
  imported a DAT for that console (see *DAT check*), the finished file is looked up in it right
  away and the row says *✓ matches the DAT* or *not in the DAT* (another dump or version, or a
  modified one), instead of waiting for a full check of the folder. A source's own checksum still
  goes first; unpacked archives and formats DATs do not describe (CHD, RVZ, 7z…) show nothing.

### Storage
- **Move the library to another storage** (*Tools → Storage → Move the library*): pick a folder
  (for example on the SD card); every game is copied to the same place below it, each copy is checked
  against the original's size, and only then is the original deleted. A game that cannot be copied
  stays where it is. When all files are moved Dogmatix switches to the new folder. Stop your
  downloads first and keep Dogmatix open; after an interruption start it again and it carries on
  (a file already at the target with exactly the same size counts as copied). Folders you gave a
  console of its own are not moved. Not tried on a real device yet; try it on a small folder first.

### RomM
- **Upload what the server lacks** (*Settings → RomM → Upload what the server lacks*): sends the
  finished downloads that RomM does not list yet, for games downloaded before *Upload finished
  downloads* was on. Only games this app downloaded, still on the device, for a console mapped to a
  RomM platform, and not from the server itself. It needs *Mark games already in RomM*, so Dogmatix
  knows what the server has. The existing resumable chunked upload does the sending.

### Wishlist
- **Share the wishlist** (*Wishlist → Share the wishlist*): *Export* saves it as a small file
  (titles and consoles), *Import* adds the wishes of such a file to the list, skipping the ones
  already on it. An imported wish starts as "wanted" again.

### Tools
- **Frontend check** (*Tools → Frontend check*): one list of ES-DE, iiSU, Daijishō, Pegasus and
  RetroArch with what is ready, what is still to do and what only works by hand, each with a tap
  through to Settings. It reads Dogmatix's own settings, not the frontends' folders.

## [3.2.0] – 2026-10-04 · Dogmatix+

### Wishlist
- **Already have it?** A wanted game that is on the device (in the download folders) or on your
  RomM server now says so — *Already on this device* / *Already on your RomM server*, with a
  *Have* / *RomM* badge — instead of "Not in the library yet". Such a game is not announced or
  downloaded automatically when a source lists it. Every word of the wish must be in the name, and
  a wish for one console only looks at that console. RomM counts when *Mark games already in RomM*
  is on (the default once RomM is set up).

### Covers for more frontends
- **Pegasus** (*Settings → Covers for Pegasus*): after a download the cover goes to
  `media/<game>/boxFront.<ext>` next to the game, where Pegasus looks when the console's
  `metadata.pegasus.txt` sits in that folder. A `boxFront` already there is left alone.
- **RetroArch** (*Settings → Covers for RetroArch*, pick RetroArch's `thumbnails` folder): after a
  download the libretro-thumbnails box art goes to
  `<system>/Named_Boxarts/<game>.png`, the names RetroArch's playlists use
  (`Nintendo - Game Boy Advance/Named_Boxarts/Advance Wars (USA).png`). PNG only, as RetroArch reads
  it; an existing thumbnail is left alone.

## [3.1.0] – 2026-10-04 · Dogmatix+

### Downloads
- **Whole-queue buttons** on the Downloads screen: *Stop all*, *Retry failed* and *Clear finished*
  (the files stay), each with the number of rows it acts on and only shown when it has something to
  do. They work in one batch off the UI thread, so a queue of hundreds stays responsive.
- **Pause web downloads**, running or still waiting in the queue (until now only torrents could
  pause). The partial file stays and *Resume* continues from it; a paused download is not put back
  in the queue after a restart.
- **One notification when the queue is done**: how many downloads went through, failed or were
  stopped, for runs of two or more; tapping it opens Downloads (*Settings → Notify when the queue is
  done*, on by default).

### Save sync
- **Saves folders of standalone emulators** (*Settings → Save sync → Add an emulator's saves
  folder*): DraStic, melonDS, mGBA, Pizza Boy GBA / GBC, GBA.emu, GBC.emu, Snes9x EX+, NES.emu and
  MD.emu sync their in-game saves with RomM next to RetroArch. Each folder belongs to one emulator,
  whose name goes to RomM as the save's *emulator*; server saves of that emulator come down into
  it, and its console picks the right game when the same name exists on several platforms. The
  names say "(standalone)" where RetroArch has a core folder of the same name, so the two never
  mix. Save states of these emulators are not synced.

### Profiles
- The PIN is stored with a random salt and PBKDF2 instead of a fixed-salt SHA-256 (a PIN set in
  3.0.0 keeps working).

### Faster with big queues
- Progress of all running downloads lands in the list together (at most twice a second) instead
  of every download publishing its own copy of the whole list each second, and the app shell no
  longer redraws on every progress tick (it only needs the number of active downloads).

### Fixed
- **Not-responding reports with a big queue.** *Continue the queue after a restart* put every
  interrupted download back one by one on the UI thread — hundreds of list updates, database
  writes and service requests while the first screen was drawing. Android closed the app, and the
  next start did it all again. The queue now comes back as one batch, off the UI thread.
- A download whose partial file was already complete (the app closed just before it finished)
  failed on every retry with `HTTP 416`. It now starts over from zero instead.

## [3.0.0] – 2026-10-04 · Dogmatix+

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

## [2.6.0] – 2026-10-04 · Dogmatix+

### Fixed
- **Force quits with big queues.** Starting hundreds of downloads at once (*Download everything
  shown*) did the work for every game on the UI thread, and every waiting download was woken on
  every change of the queue; with a few hundred queued, Android reported the app as not responding
  and closed it. A bulk start is now one list update and one database write, off the UI thread,
  and each waiting download is woken only when it is its turn (1,000 queued downloads are handed
  out in 40 ms in the tests). Measured on an emulator with 500 queued downloads, the Downloads
  screen open and the app sent to the background and back three times: no "not responding" any
  more, and 90 % of frames drawn within 77 ms (was 1.5 s).
- **The download notification no longer races the app.** The foreground service used to be
  started and stopped from the outside for every download; a start and a stop close together made
  Android end the app (a service started in the foreground that stopped before it showed its
  notification). The service now keeps itself alive while anything is queued or running and stops
  a few seconds after the last download; a refused start (from the background) no longer takes the
  app down, and Android 15's time limit for such services is handled.
- Two waiting downloads with the same name can no longer block each other; a download started
  twice at the same moment (a tap and a bulk start) starts once.
- Progress is drawn once a second instead of twice, which halves the work of a long list.

### New
- **Cocoon**: Dogmatix+ now offers Android app shortcuts — Downloads, each saved view and each
  console — and a "create shortcut" picker. Cocoon picks these up (*Add Games* → Android shortcuts)
  and shows them as tiles that open Dogmatix+ on that console or view; launchers show them on a
  long press of the icon. *Settings → Cocoon* explains the steps.
- **New icon**: a game cartridge with dog ears, a download arrow and a plus, on the app's orange,
  with a themed (Material You) version. The banner Dogmatix+ gives ES-DE uses it too.
- **Share diagnostics** now includes why the app last stopped (crash, "not responding", memory, …)
  with the main thread's state for a "not responding", and the last crash's stack trace.

## [2.5.0] – 2026-10-04 · Dogmatix+

### Emulators
- **BIOS check** (*Library tools → BIOS check*): pick the emulator's BIOS folder (RetroArch's
  `system`, or the BIOS folder of a standalone emulator) and see per console whether the files it
  needs are there and are the known good dumps (MD5, as libretro lists them). About twenty systems:
  PlayStation, PS2, Saturn, Sega CD, PC Engine CD, PC-FX, Dreamcast, DS, GBA, Game Boy, Famicom Disk
  System, 3DO, Lynx, Atari 5200 / 7800, ColecoVision, Intellivision, Odyssey², Neo Geo and Pokémon
  mini. Names are matched without case and one sub-folder deep (`dc/dc_boot.bin`); a file with
  another checksum is reported as *another dump*, not as wrong. *My consoles* shows only the systems
  in Sources, *All systems* the whole list.
- **Apply a patch** (*File explorer → a game → Apply patch*): IPS (with run-length records and
  truncation), UPS and BPS. The result is a new file next to the game, `Game [patch name].ext`;
  the original is not touched. UPS and BPS refuse a game whose checksum is not the one the patch was
  made for, which is the usual reason a patched game does not start. Games up to 256 MB.
- **Second screen**: on a dual-screen handheld or with a TV connected, the second display shows the
  game under the cursor (cover, title, tags, description) and the downloads in progress. With touch,
  the game whose details are open is shown. *Settings → Second screen* turns it off.

### Downloads
- **Share a link to the app**: share a download link, a magnet or a `.torrent` link from the browser
  (or open a magnet) and pick the console: download it straight into the console's folder, or add it
  (or the folder the file is in) as a source. A link without a known size takes the size the server
  announces.
- **Queue order**: waiting downloads show their place (*In queue · #2*) and can be moved up or down
  (▲ ▼, or **Y** to put one first). The queue serves strictly in that order.
- **Keep free space** (*Settings → Keep free space*: off, 1, 2, 5, 10, 20 or 50 GB): no new download
  starts below that, and a running one stops (its partial file removed) before the space runs out;
  a notification says why, and *Retry* starts it again once there is room. The waiting row reads *waiting for free space*.
- **Reserve addresses for downloads**: a file whose source address fails three times is fetched from
  the source's reserve addresses (2.0 used them for scans only).

### Library
- **Saved views**: save the current filters under a name (★ next to the result count, or *Save these
  filters*), apply them from the new *View* filter row, and manage them in *Library tools →
  Collections*. *Shortcut* puts a `★ name.dgmtx` file in the view's console folders, so ES-DE (or a
  file manager) opens Dogmatix+ on exactly that view.
- **Favourites from ES-DE** (*Library tools*): stars every game you marked as favourite in ES-DE
  (`gamelists/<system>/gamelist.xml`), matched by file name; says how many were found, how many stars
  are new and how many games are not in the library.
- **Statistics** (*Library tools → Statistics*): downloads and data per month (last 12 months), the
  consoles you download most for, and how many new games your sources brought per week (last 8).
- **RomM collections** (*Library tools → Collections → From RomM / To RomM*): pull the server's
  collections into your own (games that are not in the library are counted, not added), or send
  yours to the server (new ones are created, existing ones of your account get the missing games).
  RomM's favourites collection becomes stars instead of a collection; another user's public
  collection is never changed.
- **DAT from Redump** (*DAT check → a console → From Redump*): fetches the newest Redump DAT for
  disc systems (PlayStation 1–3, PSP, Saturn, Sega CD, Dreamcast, GameCube, Wii, PC Engine CD, PC-FX,
  3DO, Neo Geo CD, Xbox, CD-i, Jaguar CD) without leaving the app.

### Settings
- **Automatic backup** (*Settings → Automatic backup*): once a week, while the device charges, the
  backup file (the same as *Create backup*) is written to a folder you pick; the newest five are
  kept, and *Back up now* makes one at once. One file per day: backing up twice on a day replaces it.

### Fixed
- Reading a large DAT (a 12 MB Redump DAT of 10,900 games) took minutes on Android, whose regex
  engine is far slower than the JVM's for this; the XML reader no longer uses regular expressions.
  The Redump PlayStation DAT now downloads and imports in about 20 seconds on an emulator.
- The BIOS check counted a 512 KB PlayStation BIOS (`scph5501.bin`) as the PS2 BIOS; PS2 now needs
  a 4 MB file.
- The second screen ignored the app's language and stayed in the system language.
- Small wording: plural forms for "weeks ago" and shortcut messages, the stats line, a shorter *View*
  label so the chosen view fits.

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
