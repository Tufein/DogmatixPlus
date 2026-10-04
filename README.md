# DogmatixPlus

**English** · [Nederlands](README.nl.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Español](README.es.md)

**Find, download and organise retro games on your Android phone or handheld.** On your device the app is called **Dogmatix+**.

DogmatixPlus keeps one big, searchable list of the games from the sources *you* add, downloads them into the right folders, and helps you keep your collection tidy. It works with touch *and* with a game controller, so it feels at home on handhelds such as the Retroid, Anbernic or Kinhank.

> **The app comes without any games or download links.** You add your own sources, and you are responsible for only downloading what you are allowed to have.

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

*The screenshots use made-up game titles and empty placeholder files.*

---

## What can it do?

### New in 2.5
- **BIOS check**: see per console whether your emulator's BIOS files are there and are the right dumps (about twenty systems).
- **Apply IPS / UPS / BPS patches** from the file explorer; the patched game is a new copy, the original stays.
- **Share links to the app**: a download link or magnet from the browser goes straight into a console's folder, or becomes a source.
- **Reorder the download queue** (▲ ▼, or **Y** to put one first), **keep free space** so downloads stop before the storage is full, and downloads that fall back to a source's **reserve addresses**.
- **Saved views**: save your filters under a name and put a shortcut to them in ES-DE; **ES-DE favourites** become stars here.
- **Statistics**, **RomM collections** both ways, **DAT straight from Redump**, a **second screen** for dual-screen handhelds and TVs, and a weekly **automatic backup**.

### New in 2.0
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
- **Only on Wi-Fi, only while charging or only at night** *(new, alpha)*: new downloads wait until the conditions you set are met, and say what they wait for. A **checksum check** compares a finished file with the hash its source publishes. In the game info, **Best version** picks the version that suits you (your region and language, no demos). A finished download can be **opened** in an emulator.

### Keep your collection tidy *(new in DogmatixPlus)*
- **Duplicate games**: finds games that are on your device more than once, shows how much space you win, and lets you delete the extra copy. Nothing is deleted before you have seen exactly which files will go.
- **Library overview**: for every console, how many games are listed, how many you own, how many are on your device and how big that is, and when it was last scanned.
- **Back up and restore**: save your settings, sources, favourites and downloads in one file and put them back later — handy for a new device.
- **Scan progress**: a percentage and the time left while your sources are being read. Since 1.2.0 sources are **scanned side by side** and much faster.
- **Game sets** *(new, alpha)*: finds disc images that cannot run (a `.cue` whose track is gone, a playlist that names a deleted disc) and makes `.m3u` playlists for games with several discs.
- **Storage**: space per console, your biggest games, and whether the downloads in the queue still fit.
- **Wishlist**: write down games you want; you get a message when one turns up in your sources.
- **Export** your collection as a spreadsheet (CSV) or a web page. The duplicate finder can also **suggest which copy to keep**.
- **File explorer** *(1.3)*: look inside your folders, see sizes and which files count as games, check disc sets, open or delete files.
- **Scan report** *(1.3)*: after a scan, one overview of the sources that failed and why, with *Scan these again*; every source shows its last result.

### Made for handhelds
- **Control everything with a gamepad**: D-pad, A/B/X/Y and the shoulder buttons. On-screen hints at the bottom match your pad (Xbox, Nintendo or PlayStation), and you can swap the buttons if your pad reports them the other way round.
- **Landscape and portrait** layouts, with a filter panel next to the list on wide screens.
- **Light, dark or pure black** theme (nice on OLED screens), **twelve accent colours** and **Material You** (Android 12+: the colours follow your wallpaper).
- **◀ ▶ jumps through the library by first letter**, handy with a long list and no touch screen.

### Works with your game launcher
- **ES-DE** and **iiSU** get a "Search for more games" entry in every console, set up with one button. **Daijishō** shows you the few values to type in.

### Works with RomM
- Send finished downloads to your **RomM** server, or use RomM as a source of games.
- **Save sync** *(new, beta)*: your RomM server keeps your **game saves and save states**. Pick the folders your emulator saves into (for RetroArch: `saves` and `states`) and DogmatixPlus sends new progress up and brings newer progress — from another handheld or from RomM's web player — down. If a save changed on both sides, you choose which one to keep; a copy that gets replaced is kept for 30 days. It can run by itself when you open the app or come back to it from a game (*Settings → Save sync*).
- *(new, alpha)* Games your RomM server already has are **marked** in the library. A server at home with a **self-signed certificate** works once you have checked its fingerprint. An interrupted upload **continues** where it stopped. RomM's **cover art** can be fetched into ES-DE.
- *(new, alpha)* Save sync can run **in the background** every few hours and can also **carry deletions over** (off by default, with safeguards). If a save changed on both sides you now see both times and sizes.

### Your sources, your way
- Add sources by hand, or **import and export** them as a file to share between devices. The export now also carries your **★ favourites**, and importing it adds them on the other device.
- A short **first-start guide** helps you pick your ROMs folder and import your sources.

### Languages
- **English, Spanish, Dutch, French, German, Italian and Portuguese** (*Settings → Language*, or follow your phone).

---

## Install

1. Open the **[Releases page](https://github.com/Tufein/DogmatixPlus/releases)** on your phone or handheld, or download there and copy the file over.
2. Download one of the two files:
   - **`DogmatixPlus-release.apk`** — *the normal choice.* Since 1.2.0 the app is called **Dogmatix+** and has its own package name, so it installs **next to** the official Dogmatix and next to older DogmatixPlus versions; nothing of yours is touched.
   - **`DogmatixPlus-debug.apk`** — a debug build that also installs next to everything else.
   - **Coming from an older DogmatixPlus?** In the old app use *Settings → Back up*, then *Settings → Restore backup* in Dogmatix+, and run the ES-DE / iiSU / Daijishō setup once more.
3. Open the file and allow **"install unknown apps"** if Android asks.

You need **Android 10 or newer**. The app is not on Google Play.

## First start

1. The welcome guide explains the basics.
2. **Pick your ROMs folder** — the folder where your games should go.
3. **Import your sources** (a file with your game lists) — or skip and add sources later in the **Sources** tab.
4. Open the **Library**, find a game and tap it (or press **A**) to download it.

## Using a gamepad

| Button | What it does |
|---|---|
| D-pad | Move around |
| **A** | Choose / download |
| **B** | Go back one step |
| **X** | Game info |
| **Y** | Search |
| **Select** | Star or unstar a game |
| **LB / RB** | Switch between the filters and the list |
| **ZL / ZR** | Previous / next section |
| **R3** | Fold or unfold the filter panel |

Everything also works by touch. The hints only show while a controller is connected.

## Good to know

- **Deleting duplicates is permanent.** The app shows every file first, but there is no recycle bin.
- **A backup file contains your account keys** (TorBox, Real-Debrid, RomM). Keep it private.
- **The game info window stays empty in the downloads here**, because it needs a free key from a game database that is added when the app is built.
- DogmatixPlus does not look for games on its own. It only reads the sources **you** add.
- **The newest versions are pre-releases** (alpha, beta). The in-app update check skips them unless you switch on *Settings → Include pre-releases*.
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
