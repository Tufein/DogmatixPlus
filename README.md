# DogmatixPlus

**English** · [Nederlands](README.nl.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Español](README.es.md)

**Find, download and organise retro games on your Android phone or handheld.** On your device the app is called **Dogmatix+**.

DogmatixPlus keeps one big, searchable list of the games from the sources *you* add, downloads them into the right folders, and helps you keep your collection tidy. It works with touch *and* with a game controller, so it feels at home on handhelds such as the Retroid, Anbernic or Kinhank.

The app includes no games. **Sources → More romsets** offers optional Libretro-hosted content collections alongside your own sources.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/library.png" width="210" alt="The library"><br><sub>Your games in one list — a green ✓ means you already have it</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/duplicates.png" width="210" alt="Duplicate games"><br><sub>Find games you have twice</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/overview.png" width="210" alt="Library overview"><br><sub>See your collection per console</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/scan-progress.png" width="210" alt="Scan progress"><br><sub>See how far a scan is</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/delete-dialog.png" width="210" alt="Delete confirmation"><br><sub>You always see which files will go</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/settings.png" width="210" alt="Settings"><br><sub>The new tools are in Settings</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/credits.png" width="210" alt="Credits"><br><sub>Credits for everyone involved</sub></td>
    <td></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/library-landscape.png" width="720" alt="The library in landscape on a handheld"></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="The new Cloud hub (titles and numbers are made up)"><br><sub>The new Cloud hub (titles and numbers are made up)</sub></p>

*The screenshots use made-up game titles and empty placeholder files.*

---

The latest release is [2.7.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.7.0) (Android build 39).
Downloads keep a known-good file while replacements are checked, archive extraction protects paths and existing files, and save sync verifies safety copies before deletions.
In-app updates check the checksum, package, version and signing certificate before installation. See [release notes](docs/release-notes/v2.7.0.md) for usage and update details.

The next release, **2.8.0**, connects recovery, storage reconnect, durable game packages, offline readiness, personal journals and guided save handoff. The optional romset catalog starts with **23 content collections / 134 listed files**, checked on 10 October 2026. These are dated directory counts, not a claim of complete commercial ROM sets. See the [full community-launch roadmap](docs/ROADMAP.md), [Reddit draft](docs/community/reddit-launch-draft.md) and [acceptance checks](docs/quality/v2.8.0-acceptance.md).

## What can it do?

### New in 2.3.0

- Search Downloads by title or file name, filter by status and apply batch actions to matching rows. Move waiting selections to the front together.
- Safer HTTP resuming and complete-file checks; outdated automatic-retry timers cannot restart newer attempts.

### New in 2.2.0
- **Clearer downloads:** per-file speed and estimated remaining time, honest feedback for unknown sizes or stalled transfers, and failure messages with a next step.
- **Regular releases only:** the history now follows 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. Original APK identities are recorded in the [version mapping](docs/releases/numbering.md).
- **Upgrading from the former 8.x labels:** install the [signed 2.2.0 APK](https://github.com/Tufein/DogmatixPlus/releases/download/v2.2.0/DogmatixPlus-release.apk) manually once; subsequent update checks use Android build numbers.

### New in 1.8.0
- **A page for every game**: A or a tap in the library opens a full-screen page with the art, a big Download button, the best version, favourite, collections, share and remove, and tabs for **About**, **Versions**, **Progress** (achievements, cloud saves) and **More like this**. X or a long press still opens the quick details card.
- **Quick menu**: hold SELECT for a ring with Search, Search everything, Surprise me, Downloads, Pause all / Resume all, Tools and Settings. A short press still marks a favourite.
- **Smart storage** (optional, *Tools → Storage*): consoles you have not played lately and have no favourites in move as a whole folder to the SD card, and come back when you play them again. Every file is copied and checked before the original goes; *Check* shows what would move first. ES-DE follows by itself, other launchers need the new folder by hand.
- **Search everything**: one search for settings, tools, screens and your games, from the quick menu, the Tools title or *Settings*; a setting you pick is scrolled into view and lit up.
- **TV mode** (*Settings → Look and controls*): larger text and rows, margins for the TV's edges and remote keys; the app also shows up in the Android TV launcher.
- **Settings text is never cut off** any more: titles, hints and values wrap in full.
- Not tried yet on a real device, a TV or an SD card.

### New in 1.6.0
- **Keep a collection on your device** (*Tools → Collections*): switch it on and new games in that collection download by themselves, a few per run, only when there is room. Nothing is deleted automatically; games that left the collection are offered in a review list.
- **Free up space** (*Tools*): games you never played, biggest first, with the space you would win. Favourites, collection games, RomM saves and achievements are protected. Remove them, or remove and put them on the wishlist.
- **Descriptions for your launcher** (*Tools*): writes description, genre, year and rating into ES-DE's gamelist.xml and Pegasus' metadata.txt, without touching what is there (a backup copy is made first).
- **Best games per console** (*Tools*, with a RetroAchievements key): the most-loved games, what you have, and a button for the ones you can still get.
- **Better version available** (*Tools*): a newer revision, a final release instead of a beta, or a good dump instead of a bad one, for games you already have.
- **A weekly digest** (optional), a **Quick Settings tile** and launcher shortcuts for the downloads, **your year in games** with a card to share.
- **The same look in every Settings row**, and your own icon on the second screen.
- Not tried yet on a real device or against a real RomM, WebDAV or RetroAchievements server.

### New in 1.5.0
- **Search by feel**: filters for **genre and decade** (from the game information you already have), **More like this** in the details card, **Surprise me** on the controller's Start button and a **compact list** option.
- **Collection goals and play history** (*Tools*): how complete each console is, with the missing titles ready to import, and a day-by-day timeline of what you downloaded and played.
- **Share a game** as a card with its cover, share the wishlist as text, and a **Continue playing** widget for the home screen. A wanted game that appears on your RomM server is announced.
- **Download when it suits**: only on Wi-Fi, while charging, tonight or at a set time, per game from the details card or the Downloads list.
- **A shared wishlist for the family** on your own WebDAV server, showing who added and who found each game; the WebDAV sync and backup became safer (no half-written files, no overwriting a newer version, a warning for unencrypted addresses).
- **Check everything** (*Tools*): one report on sources, storage, BIOS, RomM, backups, notifications and battery, with a fix button for each problem.
- Not tried yet on a real device or against a real RomM or WebDAV server.

### New in 1.4.0
- **A new look**: panels with depth, a soft glow of your accent colour, console colours, a focus you can see from the couch, **covers** (RomM, libretro box art or a coloured tile), charts, new icons and calm motion. Switches for animations, glow and covers in the list sit in *Settings → Look and controls*. Layout and buttons are unchanged.
- **Cloud hub** (*Settings → Cloud*): RomM, save sync, cloud backup, device sync, RetroAchievements and Debrid on one screen, and a small **cloud icon in the top bar** that shows idle, syncing or "needs you".
- **RomM on every game**: summary, genres, rating and screenshots in the details card, your **play status and rating** written back to RomM, **favourites in step with RomM**, and **BIOS files fetched from RomM** (checked by MD5).
- **Cloud saves per game**: the saves and states on the server (with the state's screenshot) next to the safety copies on the device, with **Restore**, and a **Continue playing** row on Home.
- **Your own cloud (WebDAV)**: an **encrypted backup** (AES-256-GCM, your passphrase) to Nextcloud, ownCloud or any WebDAV server, automatic and with restore, and **device sync** of favourites, wishlist and collections between your handhelds.
- **RetroAchievements progress** per game (ring and badges) and your profile in the hub.
- Not tried yet on a real device or against a real RomM, WebDAV or RetroAchievements server.

### New in 1.3.0
- **Whole console sets in one go**: *Download all* takes up to 3000 games, without "app isn't responding" during or after the batch. Big queues stay smooth, can be **held**, show the **time left**, and have whole-queue buttons (*Stop all*, *Retry failed*, *Clear finished*).
- **Downloads look after themselves**: failed downloads **retry by themselves**, web downloads can be **paused**, a finished file is **checked against your DAT**, and you get **a notification when the queue is done**.
- **Import a list** (*Tools*): a text file or the clipboard with one game per line. The best version of each game is downloaded in one go, and the rest can go on the wishlist. The DAT check uses it for the games you are missing.
- **Recent searches** under the search field, **Surprise me** (a random game from the list), and a **text size** setting.
- **Settings and Tools in clear groups**, a short **what's new** after an update, and a **frontend check** in Tools.
- **Covers for Pegasus and RetroArch** next to ES-DE, a **wishlist that knows what you already have** (and can be shared as a file), **move the library** to another storage, **upload what RomM lacks**, and **save sync for standalone emulators** (DraStic, melonDS, mGBA, Snes9x EX+ and more).

### New in 1.2.0
- **Downloads continue where they stopped** (full storage, closed app, reboot) and **the queue survives a restart**; a **per-server limit** keeps strict servers happy. **Reorder the queue** (▲ ▼, or **Y** to put one first) and **keep free space** so downloads stop before the storage is full.
- **Wishlist on autopilot**, **.m3u playlists** for multi-disc games, and a **storage advisor** when "Download everything shown" does not fit.
- **Covers for ES-DE** (and Cocoon's ES-DE link) after every download, from libretro-thumbnails. **Cocoon**: add Dogmatix+ as tiles per console, saved view or Downloads.
- **RetroAchievements badges**, **profiles with a PIN**, **statistics** with what you play from ES-DE, and **saved views** with a shortcut in ES-DE.
- **BIOS check** for about twenty systems, **IPS / UPS / BPS patches** from the file explorer, and **links shared to the app** go straight into a console's folder.
- **RomM collections** both ways, **DAT straight from Redump**, a **second screen** for dual-screen handhelds and TVs, a weekly **automatic backup**, and a new icon.

### New in 1.1.0
- **Faster rescans**: sources whose list did not change are skipped (6 sources of 4,000 games: from 18 s to 2 s). A source that fails keeps its games, and a web source can have **reserve addresses**.
- **Scan in the background** (daily, on Wi-Fi, while charging, at night) with a notification when new games turn up; new games get a **New** badge, a filter and a *Newest first* sort.
- **Collections**: your own lists next to the favourites.
- **Download everything shown**, after a check of count, size and free space; optionally only the best version of each game.
- **Nintendo Switch updates and DLC**: see which update or DLC you are missing and fetch it.
- **DAT check** against No-Intro / Redump: good dumps, wrong names (renamed with one tap), unknown files and missing games.
- **The speed limit works** (it never did before) for all downloads together, torrents included, optionally not at night.
- **File explorer** can rename, move and unpack; **share your sources as a QR code**; **install updates from inside the app**; a **home-screen widget**; a **bold focus ring** for TV and handheld use.

### Find games
- **One list** with the games from all your sources.
- **Search** that forgives mistakes: accents, dashes and doubled letters don't matter, so "yugioh" finds *Yu-Gi-Oh!*.
- **Filter** by console, region, language and type, and **sort** by name or size.
- A green **✓** marks the games you already have.
- **★ Favourites**: star the games you like and show only those.

### Download games
- Works with **direct links, torrents and magnet links**.
- **Several downloads at once**, with pause, resume, retry and a speed limit.
- **ZIP and 7z files are unpacked** for you.
- Every console gets **its own folder**. Folders you already have (like `gba` or `psx`) are reused, and you can merge two folders that mean the same console.
- Your **download list is kept** when you close the app. Select several downloads to stop, retry or delete them together.
- Optional: **TorBox** and **Real-Debrid** — paid services that fetch torrents for you, so the download is a normal fast file.
- **Only on Wi-Fi, only while charging or only at night**: new downloads wait until the conditions you set are met, and say what they wait for. A **checksum check** compares a finished file with the hash its source publishes. In the game info, **Best version** picks the version that suits you (your region and language, no demos). A finished download can be **opened** in an emulator.

### Keep your collection tidy *(new in DogmatixPlus)*
- **Duplicate games**: finds games that are on your device more than once, shows how much space you win, and lets you delete the extra copy. Nothing is deleted before you have seen exactly which files will go.
- **Library overview**: for every console, how many games are listed, how many you own, how many are on your device and how big that is, and when it was last scanned.
- **Back up and restore**: save your settings, sources, favourites and downloads in one file and put them back later — handy for a new device.
- **Scan progress**: a percentage and the time left while your sources are being read. Sources are **scanned side by side**, so this goes fast.
- **Game sets**: finds disc images that cannot run (a `.cue` whose track is gone, a playlist that names a deleted disc) and makes `.m3u` playlists for games with several discs.
- **Storage**: space per console, your biggest games, and whether the downloads in the queue still fit.
- **Wishlist**: write down games you want; you get a message when one turns up in your sources.
- **Export** your collection as a spreadsheet (CSV) or a web page. The duplicate finder can also **suggest which copy to keep**.
- **File explorer**: look inside your folders, see sizes and which files count as games, check disc sets, open or delete files.
- **Scan report**: after a scan, one overview of the sources that failed and why, with *Scan these again*; every source shows its last result.

### Made for handhelds
- **Control everything with a gamepad**: D-pad, A/B/X/Y and the shoulder buttons. On-screen hints at the bottom match your pad (Xbox, Nintendo or PlayStation), and you can swap the buttons if your pad reports them the other way round.
- **Landscape and portrait** layouts, with a filter panel next to the list on wide screens.
- **Light, dark or pure black** theme (nice on OLED screens), **twelve accent colours** and **Material You** (Android 12+: the colours follow your wallpaper).
- **◀ ▶ jumps through the library by first letter**, handy with a long list and no touch screen.

### Works with your game launcher
- **ES-DE** and **iiSU** get a "Search for more games" entry in every console, set up with one button. **Daijishō** shows you the few values to type in.

### Works with RomM
- Send finished downloads to your **RomM** server, or use RomM as a source of games.
- **Save sync**: your RomM server keeps your **game saves and save states**. Pick the folders your emulator saves into (for RetroArch: `saves` and `states`) and DogmatixPlus sends new progress up and brings newer progress — from another handheld or from RomM's web player — down. If a save changed on both sides, you choose which one to keep; a copy that gets replaced is kept for 30 days. It can run by itself when you open the app or come back to it from a game (*Settings → Save sync*).
- Games your RomM server already has are **marked** in the library. A server at home with a **self-signed certificate** works once you have checked its fingerprint. An interrupted upload **continues** where it stopped. RomM's **cover art** can be fetched into ES-DE.
- Save sync can run **in the background** every few hours and can also **carry deletions over** (off by default, with safeguards). If a save changed on both sides you now see both times and sizes.

### Your sources, your way
- Add sources by hand, or **import and export** them as a file to share between devices. The export now also carries your **★ favourites**, and importing it adds them on the other device.
- A short **first-start guide** helps you pick your ROMs folder and import your sources.

### Languages
- **English, Spanish, Dutch, French, German, Italian and Portuguese** (*Settings → Language*, or follow your phone).

---

### New in 2.4.0

Choose an installed emulator per console, compare game versions with saved language/region/revision rules, and search the action history. Restore removed games or download them again from history. Recovery copies keep their original paths and consume space until trash is emptied.

## Install

1. Open the **[Releases page](https://github.com/Tufein/DogmatixPlus/releases)** on your phone or handheld, or download there and copy the file over.
2. Download one of the two files:
   - **`DogmatixPlus-release.apk`** — *the normal choice.* The app is called **Dogmatix+** and has its own package name, so it installs **next to** the official Dogmatix and next to older DogmatixPlus versions; nothing of yours is touched.
   - **`DogmatixPlus-debug.apk`** — a debug build that also installs next to everything else.
   - **Coming from an older DogmatixPlus?** In the old app use *Settings → Back up*, then *Settings → Restore backup* in Dogmatix+, and run the ES-DE / iiSU / Daijishō setup once more.
3. Open the file and allow **"install unknown apps"** if Android asks.

You need **Android 10 or newer**. The app is not on Google Play.

## First start

1. The welcome guide explains the basics.
2. **Pick your ROMs folder** — the folder where your games should go.
3. **Import your sources** (a file with your game lists) — or skip and add sources later in the **Sources** tab.
4. Open the **Library**, find a game and tap it (or press **A**) to open its game page. Choose **Download** or **Play** there.

## Using a gamepad

| Button | What it does |
|---|---|
| D-pad | Move around |
| **A** | Choose / open the game page; download or play on that page |
| **B** | Go back one step |
| **X** | Game info |
| **Y** | Search |
| **Select** | Star or unstar a game |
| **LB / RB** | Switch between the filters and the list |
| **ZL / ZR** | Previous / next section |
| **R3** | Fold or unfold the filter panel |

Everything also works by touch. The hints only show while a controller is connected.

## Good to know

- Removed games go to recovery trash. Restore or permanently empty them in Tools → Trash and recovery. Space is freed only after emptying; the general file explorer still deletes permanently.
- **A backup file contains your account keys** (TorBox, Real-Debrid, RomM). Keep it private.
- **The game info window stays empty in the downloads here**, because it needs a free key from a game database that is added when the app is built.
- DogmatixPlus does not look for games on its own. It only reads the sources **you** add.
- **All public releases are regular releases.** Versions follow 1.0.0, 1.1.0, …, 1.9.0, 2.0.0. See [release numbering](docs/releases/numbering.md).
- **Something not working?** *Settings → Share diagnostics* makes a text report for a bug report; tokens, server addresses and magnet links are removed first.

---

## Credits

DogmatixPlus is a small layer on top of two other projects. Most of what you use every day is theirs.

| Project | Made by | What it brought |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | The original app and its whole engine: reading sources, sorting games by console / region / language, searching, downloading and unpacking. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) | The handheld version: gamepad control, landscape layout, themes, favourites, pause and resume, the first-start guide, ES-DE / iiSU / Daijishō, TorBox and Real-Debrid, RomM. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | Duplicate finder, library overview, back up and restore, scan progress, save sync with RomM, game-set checks, storage overview, wishlist, export, download schedule, checksum checks, the Dutch, French, German, Italian and Portuguese translations, and these releases. |

DogmatixPlus was **made with the help of A.I.**: the code, the tests and the documentation were written together with an AI assistant and checked in several review rounds. Decisions, direction and publishing are the maintainer's.

## Disclaimer

This app is for educational purposes only. You are responsible for making sure you have the legal right to download any content.

Milou and Dogmatix have no licence, so all rights to their code stay with their authors. DogmatixPlus is an unofficial personal modification and is not affiliated with either project. If you are one of the original authors and would like something changed or removed, please open an issue.

---

## For developers

The technical details — how it is built, how the duplicate finder decides, the folder layout, deep links, the tech stack and more — are in **[TECHNICAL.md](TECHNICAL.md)**. See also [FRONTENDS.md](FRONTENDS.md) for the launcher setup and [CHANGELOG.md](CHANGELOG.md) for every change.
