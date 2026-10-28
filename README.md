# MG4 Bluetooth Auto Pair 🚗⚡

[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-28%20(Android%209)-brightgreen)](https://developer.android.com/about/versions/pie)
[![Android Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue)](https://developer.android.com/about/versions/14)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple)](https://kotlinlang.org/)
[![UI Framework](https://img.shields.io/badge/UI-Jetpack%20Compose-darkgreen)](https://developer.android.com/jetpack/compose)

An automated Android Accessibility Service designed specifically for **MG4 Electric Vehicles** (and other MG models) to automatically detect and accept Bluetooth pairing confirmation prompts and contact/call log access requests from the in-car infotainment system.

---

## 🛑 Problem Statement

The **MG4 Electric Vehicle** (and certain other MG models) has a known infotainment firmware quirk where its Bluetooth MAC address changes dynamically upon vehicle restart or reboot. As a result, paired phones treat every car startup as a new device, triggering repeated Bluetooth pairing prompts (*"Check pairing codes - Pair with MG?"*) and follow-up contact sharing permission popups.

Having to manually unlock your phone and tap **Pair** and **Allow** every time you enter your MG4 is inconvenient and unsafe while driving.

---

## ✨ Solution & Key Features

**MG4 Bluetooth Auto Pair** runs a background `AccessibilityService` (`BtAutoPairAccessibilityService`) that continuously monitors system window events for pairing requests originating from your MG4.

- 🤖 **Automated Pairing Acceptance:** Detects exact system pairing prompts containing `"MG"` / `"Pair with MG"` and automatically simulates an `ACTION_CLICK` on **Pair** / **Allow** / **Confirm**.
- 📇 **Automated Contact & Call Log Access:** Auto-checks *"Allow access to your contacts and call history"* checkboxes and approves follow-up contact sharing permission popups automatically.
- 📊 **Run Counter & Timestamp:** Tracks and displays how many times the service has auto-paired along with the exact date/time of the last action (with a manual reset option).
- 🧹 **Duplicate "MG" Bluetooth Device Cleanup:** Automatically unpairs leftover/stale `"MG"` entries from Android Bluetooth settings (both automatically after pairing and on-demand via a UI button) to prevent accumulating dozens of duplicate device entries.
- ⚙️ **Configurable Target Device Name:** Custom target device name (default: `MG`) saved persistently using **Preferences DataStore**.
- 🎨 **Modern Jetpack Compose UI:** Minimal single-activity dashboard displaying real-time service status (Active / Disabled) and Battery Optimization status.
- 🔓 **Android 13/14 Restricted Settings Guidance:** Built-in UI helper directing users through bypassing Android 13+ *"Restricted Setting"* security locks on sideloaded accessibility services.
- ⚡ **High Performance & Safe:** Safe tree traversal with explicit `AccessibilityNodeInfo` recycling to prevent memory leaks and strict system-package filtering.

---

## 🛠️ Architecture & Tech Stack

- **Target Vehicle:** MG4 EV & MG Infotainment Systems
- **Language:** 100% Idiomatic Kotlin
- **UI:** Jetpack Compose with Material 3 Design
- **State & Storage:** Preferences DataStore & Kotlin Coroutines Flow
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`) with modern Version Catalog (`gradle/libs.versions.toml`)
- **Android API:** Accessibility Service (`AccessibilityNodeInfo`, `AccessibilityEvent`)

---

## 🚀 Setup & Installation Guide

### 1. Build and Sideload the APK

Clone the repository and install the debug APK to your device using Android Studio or ADB:

```bash
git clone https://github.com/YOUR_USERNAME/MG4BLEfix.git
cd MG4BLEfix
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Enable Restricted Settings (Android 13 & 14+)

On Android 13+ (API 33+), sideloaded apps have accessibility toggles greyed out by default (*"Restricted setting"*):

1. Open **MG Bluetooth Auto Pair** on your phone.
2. Tap **Open App Info** on the red warning card.
3. Tap the **3 dots (⋮)** in the top-right corner of the App Info screen.
4. Tap **Allow restricted settings** and authenticate via PIN/Fingerprint.

### 3. Activate the Accessibility Service & Unrestricted Battery

1. Open the **MG Bluetooth Auto Pair** app.
2. Tap **Enable Accessibility Service** (or go to *Settings > Accessibility > Downloaded Apps*).
3. Select **MG Bluetooth Auto Pair Service** and toggle it **ON**.
4. Tap **Grant Unrestricted Battery Access** to prevent Android from sleeping the service while driving.

---

## 🔍 How It Works Under the Hood

```mermaid
graph TD
    A[Bluetooth Pairing Request / Permission Dialog Appears from MG4] --> B[AccessibilityService listens to TYPE_WINDOW_STATE_CHANGED]
    B --> C[Verify System Package Whitelist & Target Name 'MG']
    C --> D{Is Exact MG4 Pairing / Permission Prompt?}
    D -- No --> E[Ignore Event]
    D -- Yes --> F[Check Contact/Call History Checkbox if present]
    F --> G[Find Positive Action Button: 'Pair' / 'Allow' / 'Confirm']
    G --> H[Perform ACTION_CLICK on 'Pair']
    H --> I[Log Stats, Increment Counter & Auto-Clean Duplicate 'MG' Entries]
```

---

## 🛡️ Privacy & Security Statement

- **Strict Target Filtering:** The app **only** interacts with system dialogs containing your specified target device name (default: `MG`).
- **100% Local Processing:** The app does not request Internet access (`android.permission.INTERNET` is omitted) and sends zero data off your device.
- **Open Source:** Full source code is available in this repository for audit and inspection.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.
