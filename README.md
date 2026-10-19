# Flow Center

> **Flow Center** is a core component of **Project:Flow** — a dedicated FOSS (Free and Open-Source Software) initiative designed to bring the sleek, fluid, and modern **ColorOS 14–16 design language** to any Android device, free of stutter and bloat.

---

## ✨ Features

- **ColorOS 14–16 Aesthetics**: Inspired by the clean cards, smooth blurs, and polished iconography of modern ColorOS.
- **Blazing Fast & Lightweight**: Crafted to replace sluggish system control panels with a responsive, lag-free experience.
- **100% FOSS**: Open-source, transparent, and privacy-respecting.
- **Customizable**: Built to integrate seamlessly and adapt to your Android workflow.

---

## 🛠️ Tech Stack & Requirements

- **Language**: Kotlin
- **Min SDK**: 21 (Android 5.0+)
- **Target/Compile SDK**: 36 (Android 14/15/16)
- **UI Architecture**: ViewBinding, Material Components

---

## 🚀 Building & Development

To build the app locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/zev3nz/flow-center.git
   cd flow-center
   ```
2. Build the debug APK:
   ```bash
   chmod +x gradlew
   ./gradlew assembleDebug
   ```
   The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🤖 CI / CD & Automated Releases

This project uses **GitHub Actions** (`.github/workflows/release-beta-apk.yml`) to automatically:
- Build debug APKs when pushing beta tags (e.g. `v0.1.0-beta`).
- Create GitHub Pre-releases and attach the built APKs for easy downloading.
- Support manual workflow dispatch directly from the GitHub Actions tab.

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
