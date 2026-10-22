# Contributing to Apex Music Player

Thank you for your interest in contributing to **Apex Music Player**! We welcome bug reports, feature suggestions, translations, and pull requests.

## Code of Conduct
This project is open-source and welcoming to everyone. Please be respectful, constructive, and helpful when opening issues or submitting PRs.

## Development Setup

### Prerequisites
1. **Node.js**: v18 or v20+
2. **Java Development Kit**: JDK 17
3. **Android SDK**: API level 26 minimum, API level 35 target/compile

### Building Locally

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Yaromir-Anokhin/apex-music-player.git
   cd apex-music-player
   ```

2. **Install web dependencies and build UI bundle**:
   ```bash
   npm install
   npm run build
   ```

3. **Build Android APKs**:
   - For **Huawei P20 Pro** (Android 10 / EMUI 10):
     ```bash
     ./gradlew assembleHuaweiDebug
     ```
   - For **Redmi / Xiaomi** (Android 15 / HyperOS):
     ```bash
     ./gradlew assembleRedmiDebug
     ```
   - For **Release APKs**:
     ```bash
     ./gradlew assembleHuaweiRelease assembleRedmiRelease
     ```

## Submitting Pull Requests

1. Fork the repo and create your branch from `main`:
   ```bash
   git checkout -b feature/my-new-feature
   ```
2. Ensure `npm run build` succeeds without errors.
3. Verify that `./gradlew assembleHuaweiRelease assembleRedmiRelease` builds cleanly.
4. Keep commits descriptive and modular.
5. Push to your fork and submit a Pull Request describing your changes.

## License
By contributing to Apex Music Player, you agree that your contributions will be licensed under the GNU General Public License v3.0 (GPL-3.0).
