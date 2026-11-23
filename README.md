# Olive Finder

Olive Finder turns an Android phone, iPhone, or iPad into an electronic finder and sky atlas for your telescope. Mounted to a telescope—or paired with an external USB astronomy camera on Android or iPad—it captures night-sky images and uses on-device plate-solving to determine where the telescope is pointing.

- **Website:** [oakamil.github.io/olive-app](https://oakamil.github.io/olive-app/)
- **Android:** [Google Play](https://play.google.com/store/apps/details?id=com.oakamil.olive)
- **iOS / iPadOS (Beta):** [Apple TestFlight](https://testflight.apple.com/join/VZ6ZQzry)

## Features

- **Interactive Sky Map:** Full-screen star map with optional DSS2 sky background imagery, high-resolution object previews, constellation lines, horizon markers, and a live plate-solved camera overlay.
- **Sky Time Simulation (1900–2050):** Step forward or backward in time (`1m`, `1h`, `1d`, `1mo`, `1y`) or enter a custom date and time with IAU 1976 axial precession and DE421/MPC solar system ephemerides.
- **Offline Catalog:** Search and filter Deep Sky Objects (Messier, NGC, IC, Caldwell, Collinder, Herschel 400, Barnard, Sharpless, Abell, Arp, and more), bright and multiple stars, planets, the Moon, asteroids, and comets.
- **Observing Lists & Logging:** Create custom observing lists, log observations with notes and equipment details, and import or export lists and logs as CSV files.
- **Internal & USB Camera Support:** Use the built-in camera or connect an external UVC astronomy camera on Android or iPad (an SC2210 sensor with a 25mm lens is recommended), with automatic exposure and gain control.
- **Hybrid Tracking:** Combines continuous plate-solving with internal motion sensors for responsive positional updates while slewing.
- **Boresight Calibration & Focus:** Align the targeting reticle with your telescope's optical center and inspect live star HFR readouts while focusing.
- **Night-Vision UI:** Red night-vision and day themes, custom built-in keyboards, reversible left/right layout, screen brightness control, and low-power dimming.
- **Planetarium Integration:** Emulates a Meade LX200 (TCP 4030) and ASCOM Alpaca (TCP 11111 + UDP discovery) mount over WiFi for connecting to apps such as SkySafari and Stellarium.

> **Note:** A secure phone/tablet mount or piggyback adapter is required when using the built-in camera or motion sensors.

## Repository Structure

Olive Finder is a Kotlin Multiplatform + Compose Multiplatform app backed by Rust plate-solving and camera engines:

| Path | Description |
|---|---|
| `shared/` | Shared Kotlin Multiplatform business logic, astrometry, HiPS tile rendering, databases, and Compose UI |
| `app/` | Android application shell, Camera2/UVC camera wrappers, foreground service, and unit tests |
| `app/src/main/rust/` | Android Rust JNI library (`olive-jni`) bridging `olive-engine` with Android Camera2/NDK, UVC, and IMU |
| `iosApp/` | Swift/SwiftUI iOS & iPadOS entry point, `AVFoundation` camera capture, `CoreMotion` IMU, and Xcode project |
| `crates/olive-ios/` | iOS Rust static library (`olive-ios`) exposing the C FFI (`OliveBridge.h`) to Kotlin/Native and Swift |
| `docs/` | Static GitHub Pages site (landing page, support, and privacy policy) |
| `generate_db.py` | Utility script to rebuild `app/src/main/assets/catalog.db` from source catalogs, `catalog_descriptions.md`, and `asteroids.dat` |

Core solving and tracking crates live in separate repositories:
- [`oakamil/olive-engine`](https://github.com/oakamil/olive-engine) — Plate-solving orchestration, auto-exposure, and LX200 / ASCOM Alpaca servers
- [`oakamil/olive-solve`](https://github.com/oakamil/olive-solve) — Star extraction, Tetra3-derived plate solver, and IMU sensor fusion

## Building from Source

### 1. Clone Repositories

Both Android and iOS Rust crates currently reference `olive-engine` as a sibling directory next to `olive-app` (`olive-solve` is fetched automatically by Cargo from GitHub):

```bash
git clone https://github.com/oakamil/olive-app.git
git clone https://github.com/oakamil/olive-engine.git
```

Directory layout:
```text
parent-dir/
├── olive-app/
└── olive-engine/
```

### 2. Android

**Prerequisites:**
- JDK 17 or 21 (`JAVA_HOME` set, or build via Android Studio)
- Android SDK (compileSdk 36) and Android NDK (`ANDROID_HOME` / `ANDROID_NDK_HOME` or `local.properties`)
- Rust toolchain (`rustup`) with Android targets and `cargo-ndk`:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi
  cargo install cargo-ndk
  ```
- `ninja` and `cmake` (for vendored native dependencies)

**Build & Test:**
```bash
cd olive-app
./gradlew :app:testReleaseUnitTest
./gradlew :app:assembleRelease
```

Release builds are signed with the default Android debug key unless you configure your own signing keystore locally.

### 3. iOS / iPadOS (macOS only)

**Prerequisites:**
- Xcode 16+
- JDK 17 or 21 (e.g. `brew install openjdk@21`)
- Rust toolchain with Apple iOS targets:
  ```bash
  rustup target add aarch64-apple-ios aarch64-apple-ios-sim
  ```

**Configure Local Signing:**
From the `olive-app` directory, copy the example signing config and set your own Apple Developer Team ID and Bundle Identifier (gitignored):
```bash
cd olive-app
cp iosApp/LocalSigning.xcconfig.example iosApp/LocalSigning.xcconfig
```

**Build:**
Open `iosApp/iosApp.xcodeproj` in Xcode and build the **Olive Finder** scheme, or build for the Apple Silicon iOS Simulator from the command line:
```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme "Olive Finder" \
  -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" \
  -configuration Release \
  ARCHS=arm64 \
  ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## License, Attributions & Branding

- **License:** Olive Finder (`olive-app`) is licensed under the **PolyForm Noncommercial License 1.0.0** (see [LICENSE](LICENSE)). Personal, educational, academic, and other non-commercial uses and modifications are permitted; commercial use requires separate written permission.
- **Branding:** The "Olive Finder" name and app icon are reserved for official releases. Non-commercial forks are welcome under the license, provided they use a distinct name and application/bundle identifier.
- **Privacy:** See [PRIVACY.md](PRIVACY.md).
- **Third-Party Credits:** Astronomical catalog, ephemeris (JPL DE421 / MPC), and DSS2 HiPS imagery attributions are listed in [LICENSE](LICENSE) and [catalog_descriptions.md](catalog_descriptions.md).
