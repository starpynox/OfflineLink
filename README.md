# OfflineLink: Hybrid Offline Communication for Android

**OfflineLink** is an open-source native Android application built with Kotlin and Jetpack Compose that enables nearby Android devices to discover each other, exchange encrypted messages, broadcast emergency SOS alerts, transfer files, and opportunistically store-and-forward messages across successive peer encounters—without cellular connectivity, internet access, an external Wi-Fi router, or central servers.

---

## 1. Features

- **Hybrid Ad-Hoc Transports**:
  - **Bluetooth Classic RFCOMM**: Serial stream socket communication using standard Serial Port Profile (SPP) and dedicated application service UUIDs with Channel 1 fallback.
  - **Wi-Fi Direct (Wi-Fi P2P)**: High-speed local peer-to-peer data channel using standard TCP client/server sockets on port `8988`.
  - **Hybrid Transport Manager**: Automatic transport arbitration, session deduplication, and failover.
- **Binary Length-Prefixed Framing Protocol**:
  - `FrameCodec` framing with `0x4F4C4E4B` ("OLNK") magic header, protocol versioning, big-endian length prefix, and IEEE 802.3 CRC32 integrity checksums.
  - Handles byte-stream fragmentation, chunk concatenation, and malformed frame recovery.
- **Security & Privacy**:
  - **AES-256-GCM Authenticated Encryption**: 256-bit symmetric encryption with a cryptographically secure 96-bit random IV and 128-bit authentication tag.
  - **Short Authentication String (SAS)**: 6-digit numeric verification code derived from HMAC-SHA256 of peer fingerprints for out-of-band Man-in-the-Middle (MITM) verification.
  - **Zero-Knowledge Relaying**: Intermediate forwarding nodes inspect only envelope routing headers (`messageId`, `senderId`, `recipientId`, `hopCount`, `expiry`) and cannot decrypt message payloads.
- **Opportunistic Delay-Tolerant Networking (DTN)**:
  - Controlled epidemic store-and-forward routing with summary vector exchange (`ROUTING_ADVERT` / `ROUTING_FETCH`).
  - Hop count limits (configurable max hops) and persistent duplicate suppression (`seen_messages` table) to prevent broadcast loops.
- **Emergency SOS & Diagnostics**:
  - High-priority SOS broadcast alerting nearby responders.
  - Optional one-time GPS coordinate snapshot attached to emergency alerts.
  - Real-time application-level Round-Trip Time (RTT) ping/pong tracker and sequential socket throughput burst benchmarking.
  - Comprehensive telemetry logging and CSV/JSON export.
- **Local Persistence**:
  - Built on AndroidX Room (SQLite) storing peers, conversations, message queues, seen message filters, file transfer chunks, and diagnostic event logs.

---

## 2. Technology Stack

| Layer | Component | Version / Description |
| :--- | :--- | :--- |
| **Language** | Kotlin | `2.0.21` |
| **Target Platforms** | Android SDK | `minSdk 24` (Android 7.0), `compileSdk 35`, `targetSdk 35` |
| **Build System** | Gradle / AGP | Gradle `8.11.1` / Android Gradle Plugin `8.9.1` |
| **Java Target** | OpenJDK | JDK 17 |
| **UI Framework** | Jetpack Compose & Material 3 | Compose BOM `2024.10.00` |
| **Concurrency** | Kotlin Coroutines & Flow | Coroutines `1.9.0` with structured concurrency |
| **Local Database** | AndroidX Room | Room `2.6.1` with KSP `2.0.21-1.0.28` |
| **Cryptography** | Java Cryptography Extension | `AES/GCM/NoPadding` (256-bit), HMAC-SHA256 |
| **Background Ops**| Android Jetpack Lifecycle | Foreground Service with `connectedDevice` type and WakeLock |

---

## 3. High-Level Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                      PRESENTATION LAYER (Jetpack Compose)              │
│  Dashboard │ Nearby │ Chat │ Emergency SOS │ File Transfers │ Queue    │
│            Network Diagnostics │ Security & Peer Trust                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                       APPLICATION & VIEWMODEL LAYER                    │
│  OfflineLinkViewModel │ StateFlow Streams │ SAF File Picker │ Location │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                    OPPORTUNISTIC ROUTING & QUEUE LAYER                 │
│  Router │ Store-and-Forward Engine │ Controlled Epidemic Advert/Fetch   │
│  Hop Limit (Max 4-5) │ Hop Count Tracking │ Persistent Dedup (Seen)    │
└───────────────────────┬───────────────────────────────┬────────────────┘
                        │                               │
┌───────────────────────▼─────────────┐ ┌───────────────▼────────────────┐
│      PROTOCOL & FRAMING LAYER       │ │         SECURITY LAYER         │
│  FrameCodec (14-byte binary header) │ │  CryptoEngine (AES-256-GCM)    │
│  CRC32 verification │ Versioned     │ │  HMAC-SHA256 Auth │ 6-digit SAS│
│  MessageEnvelope │ Protocol ACKs    │ │  Persistent Identity & Secrets │
└───────────────────────┬─────────────┘ └────────────────────────────────┘
                        │
┌───────────────────────▼────────────────────────────────────────────────┐
│                       HYBRID TRANSPORT LAYER                           │
│  HybridTransportManager (Policy: Auto / Bluetooth / Wi-Fi Direct)     │
│  ├─ BluetoothRfcommTransport (RFCOMM UUID fa87c0d0-afac-11de-8a39-...) │
│  └─ WifiDirectTransport (WifiP2pManager + TCP Server/Client :8988)     │
└───────────────────────┬────────────────────────────────────────────────┘
                        │
┌───────────────────────▼────────────────────────────────────────────────┐
│                       LOCAL PERSISTENCE LAYER (Room)                   │
│  OfflineLinkDatabase (SQLite via Room)                                 │
│  Peers │ Messages │ Message Queue │ Seen Messages │ Transfers │ Logs   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Binary Framing Specification

Stream-oriented sockets (RFCOMM and TCP) transmit length-prefixed binary frames encoded by `FrameCodec`:

```
+---------------------+-------------------+------------------+---------------+
| 0x4F 0x4C 0x4E 0x4B | Version (2 bytes) | Length (4 bytes) | CRC32 (4 B)   |
|   MAGIC ("OLNK")    |    Protocol v1    |    Big-Endian    |  Big-Endian   |
+---------------------+-------------------+------------------+---------------+
|                         RAW PAYLOAD (N Bytes)                              |
+----------------------------------------------------------------------------+
```

- **Header Size**: 14 bytes fixed.
- **Max Payload Size**: 2 MB safety cap per frame.
- **Integrity**: Any bit alteration or truncation triggers immediate rejection via CRC32 verification before payload parsing.

---

## 5. Prerequisites & Environment Setup

- **Java Development Kit**: JDK 17 (e.g. Eclipse Temurin 17 or OpenJDK 17).
- **Android SDK**: Platform `android-35`, Build-Tools `35.0.1` or newer.
- **Android Studio**: Android Studio Ladybug (2024.2) or later recommended.
- **Physical Test Devices**: At least two Android devices running Android 7.0+ (API 24+) equipped with Bluetooth and Wi-Fi Direct.

---

## 6. Opening and Building in Android Studio

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/OfflineLink.git
   cd OfflineLink
   ```
2. **Open in Android Studio**:
   - Launch Android Studio.
   - Select **Open**, navigate to the cloned `OfflineLink` directory, and select it.
   - Allow Gradle to sync dependencies automatically.
3. **Build from Command Line**:
   ```bash
   # On Windows (PowerShell):
   .\gradlew.bat assembleDebug

   # On Linux / macOS:
   ./gradlew assembleDebug
   ```
4. **Execute Automated Unit Tests**:
   ```bash
   # On Windows (PowerShell):
   .\gradlew.bat testDebugUnitTest

   # On Linux / macOS:
   ./gradlew testDebugUnitTest
   ```

---

## 7. Installing and Running the Application

1. Connect your Android device via USB and enable USB Debugging.
2. Install the debug APK via ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Alternatively, click **Run 'app'** in Android Studio.
4. Launch **OfflineLink** on both devices and grant the required runtime permissions when prompted.

---

## 8. Android Permissions

The application requests the following Android permissions declared in `AndroidManifest.xml`:

- **Bluetooth**:
  - `android.permission.BLUETOOTH` & `BLUETOOTH_ADMIN` (Legacy devices, API $\le$ 30).
  - `android.permission.BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+, API $\ge$ 31).
- **Wi-Fi & Wi-Fi Direct**:
  - `android.permission.ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`, `ACCESS_NETWORK_STATE`.
  - `android.permission.NEARBY_WIFI_DEVICES` (`neverForLocation` on Android 13+, API $\ge$ 33).
- **Location**:
  - `android.permission.ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION` (Required by Android framework for Wi-Fi Direct peer discovery and optional emergency SOS location snapshot).
- **Background & Power**:
  - `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Maintains socket listening when the screen is locked).
  - `android.permission.POST_NOTIFICATIONS` (Notification status for foreground service).
  - `android.permission.WAKE_LOCK` (Prevents CPU sleep during active file transfers and benchmarks).

---

## 9. Current Implementation Limitations & Notes

1. **Cryptographic Key Derivation**:
   - Pairwise encryption keys are currently derived symmetrically via `HMAC-SHA256` from a pre-shared master salt and sorted node identifiers (`min(ID_A, ID_B) || max(ID_A, ID_B)`).
   - An over-the-air asymmetric Elliptic Curve Diffie-Hellman (ECDH) key exchange protocol is planned for future releases to provide forward secrecy.
2. **Wi-Fi Direct OEM Behaviors**:
   - On certain OEM operating systems (such as ColorOS, OxygenOS, and MIUI), incoming Wi-Fi Direct connection invitations may appear quietly in the notification shade rather than triggering a modal system dialog. Users must pull down the notification shade to accept invitations when connecting via standard P2P negotiation.
3. **Opportunistic Multi-Hop Deployment**:
   - The store-and-forward routing logic, summary vector synchronization (`ROUTING_ADVERT`), hop counting, and duplicate suppression filters are fully validated in automated tests. Large-scale physical field testing across $\ge 3$ physical devices is subject to hardware availability.

---

## 10. Repository Structure

```
OfflineLink/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/offlinelink/
│   │   │   │   ├── data/                 # Room Database, DAOs, Entities, Repository
│   │   │   │   ├── diagnostics/          # Throughput benchmarks, RTT ping, Telemetry
│   │   │   │   ├── features/             # File chunking/reassembly, Location snapshot
│   │   │   │   ├── presentation/         # Jetpack Compose UI, ViewModels, Screens
│   │   │   │   ├── protocol/             # FrameCodec, MessageEnvelope, Payload models
│   │   │   │   ├── routing/              # Store-and-Forward Router, Epidemic logic
│   │   │   │   ├── security/             # AES-256-GCM, HMAC-SHA256, SAS, Identity
│   │   │   │   ├── service/              # Foreground service for persistent sockets
│   │   │   │   └── transport/            # RFCOMM, Wi-Fi Direct, Hybrid Manager
│   │   │   ├── res/                      # XML layouts, drawables, values, mipmaps
│   │   │   └── AndroidManifest.xml       # Permissions, services, activity definitions
│   │   └── test/java/com/offlinelink/    # Automated JUnit4 unit test suites
│   ├── build.gradle.kts                  # Module-level Gradle build configuration
│   └── proguard-rules.pro
├── gradle/
│   └── wrapper/                          # Gradle wrapper JAR and properties
├── .gitignore                            # Comprehensive Android gitignore
├── README.md                             # Software documentation & usage guide
├── build.gradle.kts                      # Root Gradle build configuration
├── gradle.properties                     # JVM and AndroidX configuration
├── gradlew                               # Unix Gradle wrapper script
├── gradlew.bat                           # Windows Gradle wrapper batch script
└── settings.gradle.kts                   # Project repositories and module settings
```

---

## 11. License

This project is licensed under the Apache License, Version 2.0. See the `LICENSE` file for details.
