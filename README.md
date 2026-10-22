# 🎵 Apex Music Player

<p align="center">
  <img src="assets/icon-192.png" alt="Apex Music Player Logo" width="128" height="128" />
</p>

<p align="center">
  <strong>Modern, lightweight, privacy-focused open-source music player for Android</strong><br>
  Built-in YouTube audio & album cover downloader • Custom Playlists • 10-Band Equalizer • Background Service & Headset Controls
</p>

<p align="center">
  <a href="https://github.com/apex-player/apex-music-player/actions"><img src="https://img.shields.io/github/actions/workflow/status/apex-player/apex-music-player/build.yml?branch=main&label=CI%20Build&logo=github" alt="CI Build"></a>
  <a href="https://f-droid.org"><img src="https://img.shields.io/badge/F--Droid-Ready-blue?logo=f-droid" alt="F-Droid Ready"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-green.svg" alt="License: GPL-3.0"></a>
  <img src="https://img.shields.io/badge/Android-10%20(Huawei)%20%7C%2015%20(Redmi)-orange?logo=android" alt="Android 10 & 15">
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome">
</p>

---

## 🌍 Languages / Языки
- [English](#-english)
- [Русский](#-русский)

---

## 🇺🇸 English

### Highlights
- 🎧 **Native Background Playback**: Foreground Service with lock-screen integration, notification shade controls (Play, Pause, Next, Prev), and headset button support (single, double, triple clicks).
- 💾 **State Preservation**: Your current track, position, custom playlists, and favorites are preserved even if Android kills the app in the background.
- 📥 **YouTube Downloader with Album Art**: Download high-quality audio streams and embed square-cropped high-resolution album covers (ID3v2.3 APIC) directly on device via `NewPipeExtractor`.
- 📂 **Custom Playlists**: Create unlimited playlists with customizable emoji badges, reorder tracks, and filter by collection (All, Favorites ★, Local ⬇, Demo, and Custom).
- 🎛️ **Convenience Controls**:
  - Playback speed adjustment (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
  - Quick seek buttons (±10 seconds).
  - Real-time search filter and track sorting (alphabetical by title, artist, or default).
- 🎚️ **Equalizer & 3D Visualizer**: 10-band audio equalizer with acoustic presets and dynamic Three.js audio-reactive spectrum.
- 📱 **Dual Platform Optimization**:
  - **Huawei P20 Pro**: Android 10 / EMUI 10, OLED True Black (`#07070A`), notch support.
  - **Redmi / Xiaomi**: Android 15 / HyperOS (API 35), Scoped Storage MediaStore, Edge-to-Edge WindowInsets.
- 🛡️ **100% Free & Open Source**: No ads, no trackers, no Google Play Services dependency.

---

### Pre-built APKs

| Target Device / OS | APK Filename | Architecture & Features |
| :--- | :--- | :--- |
| **Huawei P20 Pro** (Android 10) | `ApexMusic-Player-HuaweiP20Pro.apk` | EMUI 10, OLED black theme, MediaSession |
| **Redmi / Xiaomi** (Android 15) | `ApexMusic-Player-RedmiAndroid15.apk` | Android 15 (API 35), HyperOS Edge-to-Edge, Scoped Storage |

---

### Building from Source

#### Prerequisites
- Node.js 18+ or 20+
- JDK 17
- Android SDK (API 26 to 35)

#### Steps
```bash
# 1. Clone repository
git clone https://github.com/apex-player/apex-music-player.git
cd apex-music-player

# 2. Build web bundle
npm install
npm run build

# 3. Build APKs
# Huawei P20 Pro (Debug or Release)
./gradlew assembleHuaweiRelease

# Redmi Android 15 (Debug or Release)
./gradlew assembleRedmiRelease
```
Compiled APKs will be located in `app/build/outputs/apk/`.

---

### F-Droid Inclusion
The repository includes the official F-Droid build recipe in [`metadata/com.apex.meshplayer.yml`](metadata/com.apex.meshplayer.yml) and Fastlane store metadata in [`fastlane/metadata/android/`](fastlane/metadata/android/).
- Built purely with open-source dependencies (`NewPipeExtractor`, `okhttp3`, `desugar_jdk_libs`).
- Does not contain proprietary Google Play Services or analytics SDKs.

---

## 🇷🇺 Русский

### Главные особенности
- 🎧 **Фоновое воспроизведение**: Foreground Service с карточкой в шторке уведомлений, обложками на экране блокировки и поддержкой кнопок наушников (одно, два и три нажатия).
- 💾 **Сохранение состояния**: очередь, позиция трека, скорость и плейлисты не сбрасываются даже после закрытия приложения или выгрузки из оперативной памяти Android.
- 📥 **Скачивание из YouTube с обложками**: встроенный поиск и загрузка аудиопотока высокого качества с зашитой квадратной обложкой (ID3v2.3 APIC) через `NewPipeExtractor` без внешних серверов.
- 📂 **Система плейлистов**: создание отдельных плейлистов с эмодзи-значками, добавление и удаление треков, фильтры («Все», «Любимые ★», «Скачанные ⬇», «Демо» и пользовательские).
- ⚡ **Элементы удобства**:
  - Быстрое изменение скорости трека (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
  - Кнопки быстрой перемотки на ±10 секунд.
  - Поиск в реальном времени и сортировка по названию или исполнителю.
- 🎚️ **Эквалайзер и 3D визуализатор**: 10 полос частот, готовые пресеты (Bass Boost, Rock, Pop, Vocal) и живой спектр звука.
- 📱 **Две оптимизированные сборки**:
  - **Huawei P20 Pro**: Android 10 / EMUI 10, поддержка выреза Notch, True Black OLED-дизайн.
  - **Redmi / Xiaomi**: Android 15 / HyperOS (API 35), Scoped Storage (MediaStore), прозрачный Edge-to-Edge интерфейс.
- 🛡️ **100% Свободный софт**: без рекламы, без трекеров, без сервисов Google.

---

### Установка на телефон

#### 1. Через USB (Умный скрипт)
1. Подключите телефон по кабелю (включите «Отладка по USB»).
2. Запустите скрипт `install_to_phone.bat`.
3. Скрипт сам определит смартфон и установит нужный APK.

#### 2. По Wi-Fi
1. Запустите `start_phone_transfer.bat`.
2. Откройте в браузере телефона адрес из терминала или отсканируйте QR-код.
3. Скачайте нужный APK прямо на телефон.

---

## 📂 Структура проекта / Project Structure
```
├── app/
│   ├── src/main/java/com/apex/meshplayer/
│   │   ├── MainActivity.java           # WebView, WebAppInterface, MediaSession, Foreground Service
│   │   ├── MusicPlaybackService.java    # Android Background Audio & Notification Controls
│   │   ├── YouTubeExtractorHelper.java # Local NewPipeExtractor parsing & cover art processing
│   │   └── LocalMediaScanner.java      # Scoped storage & MediaStore audio retriever
│   └── build.gradle                    # Gradle config & Flavors (huawei, redmi)
├── src/
│   ├── index.html                      # UI markup, modals, playlists bar & controls dock
│   ├── style.css                       # Modern glassmorphism dark theme styling
│   └── main.js                         # Audio playback engine, playlists, state management
├── metadata/
│   └── com.apex.meshplayer.yml         # F-Droid build recipe & metadata
├── fastlane/
│   └── metadata/android/               # Store listings in en-US and ru-RU
├── .github/
│   └── workflows/                      # GitHub Actions CI & Release automation
└── LICENSE                             # GNU General Public License v3.0
```

---

## 📜 License
This project is licensed under the [GNU General Public License v3.0](LICENSE).
Feel free to fork, contribute, and distribute!
