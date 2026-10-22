# Changelog

All notable changes to **Apex Music Player** are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-10-22

### Added
- **Custom Playlists System**:
  - Create personal playlists with custom emoji badges and names.
  - Playlist management modal sheet: quick favorite toggle, add/remove from custom playlists.
  - Delete or clear custom playlists with safe confirmation.
  - Dedicated playlist filters: All tracks, Favorites (★), Local / Downloads (⬇), Demo, and custom collections.
- **Playback Convenience Controls**:
  - Variable playback speed control (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x) with quick header badge.
  - Quick seek jump buttons (±10 seconds) in playback control bar.
  - Real-time search filter in tracks list.
  - Track sorting options: default order, alphabetical by title, alphabetical by artist.
  - "Play All" button for the currently active playlist.
- **Universal Release for ALL Androids**:
  - Added `universal` productFlavor (`minSdk 21`, `targetSdk 35`) supporting Android 5.0 Lollipop through Android 15+.
  - Fully compatible with Samsung Galaxy, Google Pixel, Xiaomi / Redmi, Huawei, OnePlus, Motorola, and all Android devices.
  - Generates `ApexMusic-Player-Universal.apk`.
- **F-Droid & GitHub Deployment**:
  - Official F-Droid metadata recipe `metadata/com.apex.meshplayer.yml` targeting `universalRelease`.
  - Fastlane localized metadata in English (`en-US`) and Russian (`ru-RU`).
  - Automated GitHub Actions CI workflow for pull requests and main branch (`build.yml`).
  - Automated GitHub Actions Release workflow for tagged releases (`release.yml`).
  - GNU General Public License v3.0 (`LICENSE`).
  - Standardized `.gitignore`, `CONTRIBUTING.md`, `SECURITY.md`.

### Improved
- **Persistent State**:
  - Custom playlists, favorites, sort mode, playback speed, and active collection filter are persisted in LocalStorage.
  - Blob URLs sanitized on state save to prevent dead media references across app restarts.
  - Android back button closes open modal sheets before navigating between views.
- **Flavor & Architecture Support**:
  - Universal build for all Android devices (`minSdk 21`, `targetSdk 35`).
  - Huawei P20 Pro flavor (`targetSdk 34`, Android 10 / EMUI 10).
  - Redmi flavor (`targetSdk 35`, Android 15 / HyperOS).

---

## [1.0.0] - 2026-10-21

### Added
- Initial release of Apex Music Player.
- 3D audio-reactive spectrum visualizer using Three.js.
- 10-band audio equalizer with acoustic presets.
- YouTube audio extraction with embedded album artwork via `NewPipeExtractor`.
- Background playback via Android Foreground Service and MediaSessionCompat.
- Notification shade media controls and headset button handling.
