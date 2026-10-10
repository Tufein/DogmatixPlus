# Dogmatix+ Reddit launch draft

Status: draft, not posted. Target subreddit has not been selected. Adapt to that community’s current posting rules before publication. The latest verified public release at the start of this work is [2.7.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.7.0); do not describe the in-progress roadmap as released.

## Ready-to-use project update

**Title:** Dogmatix+: an Android game-library manager — recovery, offline collections and the road to the community release

Hi everyone,

I’m working on Dogmatix+, an Android app for managing a retro game library, downloading from configured sources and opening games in an emulator.

It already has controller navigation, search and filters, favorites and collections, pause/resume downloads, per-game emulator choices, BIOS and disc checks, RomM integration, cloud saves and backups. The latest published release, 2.7.0, focuses on safer downloads, extraction, save backups and app updates.

For the next releases, I’m bringing those features together:

- One recovery screen for deleted files, interrupted moves and safety copies.
- Complete game packages that keep discs, tracks and folders together after restarting the app.
- An offline collection check that shows what is missing before you leave home.
- Clear storage status and controlled resume when an SD card returns.
- Personal notes and user-selected screenshots or manuals for each game.
- A guided save transfer before switching to another device.
- An optional catalog of Libretro-hosted content collections for more systems, added alongside your existing sources.

The catalog includes homebrew, demos and test software. You can also configure your own supported sources or use a RomM server.

Before the wider launch, I’m checking the complete flow on real hardware: controller-only navigation, SD-card reconnects, interrupted transfers, update data retention and real RomM/WebDAV behavior. I’ll publish the tested combinations and remaining limitations with the launch build.

Downloads and source: [Dogmatix+ on GitHub](https://github.com/Tufein/DogmatixPlus).

Current release: [2.7.0](https://github.com/Tufein/DogmatixPlus/releases/tag/v2.7.0).

Full roadmap: [Roadmap](https://github.com/Tufein/DogmatixPlus/blob/master/docs/ROADMAP.md) — this link becomes public when these changes are merged.

I’d like feedback on which consoles, content collections and emulator setups should get priority. If you report an issue, please include the app version, device and Android version, the relevant server/emulator version and the steps to reproduce it. Please leave passwords and tokens out of reports.

## Final launch rewrite checklist

- Replace the current-release paragraph and link with the actual verified community-launch version.
- Move only released features out of the future-work list.
- Add actual hardware, controller, SD-card and server test results; preserve remaining limitations.
- Attach screenshots from the released build and label any simulated content.
- Check the chosen subreddit’s current rules and use its required flair.
- Confirm every public link resolves. Posting remains a separate action.
