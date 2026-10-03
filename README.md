# DogmatixPlus

**Find, download and organise retro games on your Android phone or handheld.**

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

### Keep your collection tidy *(new in DogmatixPlus)*
- **Duplicate games**: finds games that are on your device more than once, shows how much space you win, and lets you delete the extra copy. Nothing is deleted before you have seen exactly which files will go.
- **Library overview**: for every console, how many games are listed, how many you own, how many are on your device and how big that is, and when it was last scanned.
- **Back up and restore**: save your settings, sources, favourites and downloads in one file and put them back later — handy for a new device.
- **Scan progress**: a percentage and the time left while your sources are being read.

### Made for handhelds
- **Control everything with a gamepad**: D-pad, A/B/X/Y and the shoulder buttons. On-screen hints at the bottom match your pad (Xbox, Nintendo or PlayStation), and you can swap the buttons if your pad reports them the other way round.
- **Landscape and portrait** layouts, with a filter panel next to the list on wide screens.
- **Light, dark or pure black** theme (nice on OLED screens) and five accent colours.

### Works with your game launcher
- **ES-DE** and **iiSU** get a "Search for more games" entry in every console, set up with one button. **Daijishō** shows you the few values to type in.

### Works with RomM
- Send finished downloads to your **RomM** server, or use RomM as a source of games.

### Your sources, your way
- Add sources by hand, or **import and export** them as a file to share between devices.
- A short **first-start guide** helps you pick your ROMs folder and import your sources.

### Languages
- **English, Spanish, Dutch, French and German** (*Settings → Language*, or follow your phone).

---

## Install

1. Open the **[Releases page](https://github.com/Tufein/DogmatixPlus/releases)** on your phone or handheld, or download there and copy the file over.
2. Download one of the two files:
   - **`DogmatixPlus-debug.apk`** — *the easy choice.* It installs **next to** the official Dogmatix, so nothing of yours is touched.
   - **`DogmatixPlus-release.apk`** — a smaller version. It can't be installed over the official Dogmatix; you would have to remove that first (make a backup first).
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

---

## Credits

DogmatixPlus is a small layer on top of two other projects. Most of what you use every day is theirs.

| Project | Made by | What it brought |
|---|---|---|
| **[Milou](https://github.com/santiifm/milou)** | [santiifm](https://github.com/santiifm) | The original app and its whole engine: reading sources, sorting games by console / region / language, searching, downloading and unpacking. |
| **[Dogmatix](https://github.com/cortinadev/dogmatix)** | [Rafa Cortina](https://github.com/cortinadev) | The handheld version: gamepad control, landscape layout, themes, favourites, pause and resume, the first-start guide, ES-DE / iiSU / Daijishō, TorBox and Real-Debrid, RomM. |
| **DogmatixPlus** | [Tufein](https://github.com/Tufein) | Duplicate finder, library overview, back up and restore, scan progress, the Dutch, French and German translations, and these releases. |

DogmatixPlus was **made with the help of A.I.**: the code, the tests and the documentation were written together with an AI assistant and checked in several review rounds. Decisions, direction and publishing are the maintainer's.

## Disclaimer

This app is for educational purposes only. You are responsible for making sure you have the legal right to download any content.

Milou and Dogmatix have no licence, so all rights to their code stay with their authors. DogmatixPlus is an unofficial personal modification and is not affiliated with either project. If you are one of the original authors and would like something changed or removed, please open an issue.

---

## For developers

The technical details — how it is built, how the duplicate finder decides, the folder layout, deep links, the tech stack and more — are in **[TECHNICAL.md](TECHNICAL.md)**. See also [FRONTENDS.md](FRONTENDS.md) for the launcher setup and [CHANGELOG.md](CHANGELOG.md) for every change.
