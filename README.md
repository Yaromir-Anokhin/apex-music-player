# 🎵 Apex Music Player

<p align="center">
  <img src="assets/icon-192.png" alt="Apex Music Player Logo" width="128" height="128" />
</p>

<p align="center">
  <strong>Modern, lightweight, privacy-focused open-source music player for Android</strong><br>
  Built-in YouTube audio & album cover downloader • Custom Playlists • 10-Band Equalizer • Background Service & Headset Controls
</p>

<p align="center">
  <a href="https://github.com/Yaromir-Anokhin/apex-music-player/actions"><img src="https://img.shields.io/github/actions/workflow/status/Yaromir-Anokhin/apex-music-player/build.yml?branch=main&label=CI%20Build&logo=github" alt="CI Build"></a>
  <a href="https://f-droid.org"><img src="https://img.shields.io/badge/F--Droid-Ready-blue?logo=f-droid" alt="F-Droid Ready"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-green.svg" alt="License: GPL-3.0"></a>
  <img src="https://img.shields.io/badge/Android-5.0%20to%2015%2B%20(Universal)-brightgreen?logo=android" alt="Android 5.0 to 15+">
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

---

## 🌍 Languages
- [English](#-english)

---

## 🇺🇸 English

### Highlights
- 🌟 **Universal Android Compatibility**: Runs on **ALL Android versions** from Android 5.0 (Lollipop) up to Android 15+ (API 21 to 35). Tested across **Samsung Galaxy, Google Pixel, Xiaomi / Redmi, Huawei, OnePlus, Motorola, Sony, Realme**, and more.
- 🎧 **Native Background Playback**: Foreground Service with lock-screen integration, notification shade controls (Play, Pause, Next, Prev), and headset button support (single, double, triple clicks).
- 💾 **State Preservation**: Your current track, position, custom playlists, and favorites are preserved even if Android kills the app in the background.
- 📥 **YouTube Downloader with Album Art**: Download high-quality audio streams and embed square-cropped high-resolution album covers (ID3v2.3 APIC) directly on device via `NewPipeExtractor`.
- 📂 **Custom Playlists**: Create unlimited playlists with customizable emoji badges, reorder tracks, and filter by collection (All, Favorites ★, Local ⬇, Demo, and Custom).
- 🎛️ **Convenience Controls**:
  - Playback speed adjustment (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
  - Quick seek buttons (±10 seconds).
  - Real-time search filter and track sorting (alphabetical by title, artist, or default).
- 🎚️ **Equalizer & 3D Visualizer**: 10-band audio equalizer with acoustic presets and dynamic Three.js audio-reactive spectrum.
- 🛡️ **100% Free & Open Source**: No ads, no trackers, no Google Play Services dependency.

---

### Pre-built APKs

| Target Device / OS | APK Filename | Notes |
| :--- | :--- | :--- |
| **🌟 All Androids (Universal)** | **`ApexMusic-Player-Universal.apk`** | **Recommended for ALL devices** (Android 5.0 – 15+, Samsung, Pixel, etc.) |
| **Huawei P20 Pro** | `ApexMusic-Player-HuaweiP20Pro.apk` | EMUI 10 / Android 10, OLED black theme, notch support |
| **Redmi / Xiaomi** | `ApexMusic-Player-RedmiAndroid15.apk` | Android 15 (API 35), HyperOS Edge-to-Edge, Scoped Storage |

---

### Building from Source

#### Prerequisites
- Node.js 18+ or 20+
- JDK 17
- Android SDK (API 21 to 35)

#### Steps
```bash
# 1. Clone repository
git clone https://github.com/Yaromir-Anokhin/apex-music-player.git
cd apex-music-player

# 2. Build web bundle
npm install
npm run build

# 3. Build Universal Release APK (for all Android devices)
./gradlew assembleUniversalRelease

# (Optional) Build Huawei or Redmi specific flavors
./gradlew assembleHuaweiRelease assembleRedmiRelease
```
Compiled APKs will be located in `app/build/outputs/apk/`.

---

### F-Droid Inclusion
The repository includes the official F-Droid build recipe in [`metadata/com.apex.meshplayer.yml`](metadata/com.apex.meshplayer.yml) and Fastlane store metadata in [`fastlane/metadata/android/`](fastlane/metadata/android/).
- Configured to build `universalRelease` for full compatibility with all Android devices in the F-Droid repository.
- Built purely with open-source dependencies (`NewPipeExtractor`, `okhttp3`, `desugar_jdk_libs`).
- Zero tracking, analytics, or proprietary Google libraries.

## 📜 License
This project is licensed under the [GNU General Public License v3.0](LICENSE).
Feel free to fork, contribute, and distribute!
