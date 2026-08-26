# BLE Music Controller — Android

Complete Android Studio project. Scans for your IoT device over BLE, connects,
lets the user pick a song, set a timer, and send play / pause / stop / volume.

---

## 1. Build setup

**Requirements**

| | |
|---|---|
| Android Studio | Ladybug (2024.2) or newer |
| JDK | 17 (bundled with Android Studio) |
| Gradle | 8.11.1 (wrapper) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |

**Steps**

1. Open Android Studio → **Open** → select the `ble-music-app` folder (the one with
   `settings.gradle.kts`). Do not use "Import project".
2. Studio will report a missing Gradle wrapper JAR — that file is binary so it isn't
   included here. Fix it either way:
   - Let Studio prompt you and click **Use Gradle wrapper / Sync**, or
   - Run once from the project root: `gradle wrapper --gradle-version 8.11.1`
     (needs a system Gradle), or
   - Copy `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, and `gradlew.bat` from any
     existing Android project.
3. Let it sync, then **Run** on a **physical device**. BLE does not work on the
   emulator.

Command line, once the wrapper exists:

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # build + install on connected device
```

---

## 2. The one file you edit

Everything device-specific is isolated in **`app/src/main/java/com/example/blemusic/ble/BleSpec.kt`**:

```kotlin
val SERVICE_UUID      = UUID.fromString("0000ffe0-...")  // advertised service
val CONTROL_CHAR_UUID = UUID.fromString("0000ffe1-...")  // phone -> device, WRITE
val STATUS_CHAR_UUID  = UUID.fromString("0000ffe2-...")  // device -> phone, NOTIFY
```

Don't know your UUIDs? Install **nRF Connect** (Nordic, free), connect to the device,
and read them off the service list.

Then make the packet builders in the same file emit exactly what your firmware parses,
and replace the `TRACKS` list with your real filenames / SD-card indexes.

### Default wire protocol (binary, 4 bytes)

| Byte | Meaning |
|------|---------|
| 0 | Command: `0x01` PLAY, `0x02` STOP, `0x03` PAUSE, `0x04` VOLUME |
| 1 | Song index 1..255 — on VOLUME this is level 0..30 |
| 2 | Duration low byte (seconds, little-endian uint16) |
| 3 | Duration high byte — `0x0000` means play until stopped |

Play track 3 for 10 minutes → `01 03 58 02`

If your firmware speaks ASCII instead (typical for HM-10 / HC-05 transparent-serial
modules), `BleSpec.playAscii()` sends `PLAY:3,600\n`. Use that pair and delete the
binary one.

---

## 3. Two ways to do the timer — both are wired up

**A. Device-side duration — `PlayerViewModel.playNow()`**
The duration travels inside the PLAY packet; firmware stops itself after N seconds.
Survives the phone leaving range, screen off, app killed. Use this for
"play for 20 minutes."

**B. Phone-side scheduling — `schedule/PlayLaterWorker.kt`**
WorkManager wakes up later, reconnects, sends PLAY, disconnects. Use this for
"start at 7 AM." Doze can delay it by minutes; for alarm-clock accuracy swap
WorkManager for `AlarmManager.setExactAndAllowWhileIdle()` and uncomment
`SCHEDULE_EXACT_ALARM` in the manifest.

The UI picks between them automatically: start delay 0 → A, otherwise → B.

---

## 4. Permissions

- **Android 12+** — `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, requested at runtime.
  The manifest sets `usesPermissionFlags="neverForLocation"` so location is **not**
  needed. Remove that flag only if you derive location from beacons.
- **Android 11 and below** — `ACCESS_FINE_LOCATION` is mandatory for scanning, and
  Location Services must be switched on in system settings or scans return zero results.

`MainActivity.requiredPermissions()` picks the right set at runtime.

---

## 5. Project layout

```
ble-music-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml              version catalog
│   └── wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/blemusic/
        │   ├── ble/BleSpec.kt          <-- EDIT THIS
        │   ├── ble/BleManager.kt       scan / connect / GATT queue / write / notify
        │   ├── ui/MainActivity.kt      permissions + Compose UI
        │   ├── ui/PlayerViewModel.kt   state + commands
        │   ├── ui/Theme.kt             Material 3 theme
        │   └── schedule/PlayLaterWorker.kt
        └── res/
            ├── values/{strings,colors,themes}.xml
            ├── values-night/themes.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap/ic_launcher.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

The launcher icon is a placeholder vector. Replace it with
**Right-click `res` → New → Image Asset**.

---

## 6. BLE pitfalls already handled

These are the ones that burn most people:

1. **One GATT operation at a time.** Firing a write while a descriptor write is still
   pending silently drops it. There's an operation queue with a 5 s watchdog.
2. **Callbacks arrive on a binder thread**, not main. All GATT calls are re-posted to
   the main looper; the UI reads state through `StateFlow`.
3. **`discoverServices()` is delayed 300 ms** after connect. Calling it immediately
   fails on a lot of Samsung and Xiaomi devices.
4. **Notifications need the CCCD descriptor written**, not just
   `setCharacteristicNotification()`.
5. **MTU defaults to 23 → 20 usable bytes.** `requestMtu(185)` is issued after
   discovery. Irrelevant for 4-byte packets, matters if you later send a playlist.
6. **API 33 changed the write and descriptor signatures.** Both paths implemented.
7. **`gatt.close()` on every disconnect**, and the scan is stopped before connecting.

**Status 133** is almost always one of: stale GATT not closed, scanning while
connecting, or `autoConnect = true`. All three are avoided here.

---

## 7. Debugging

Filter Logcat on `BleManager` to watch scan → connect → discover → write. If the app
connects but shows "Service … not found", your UUIDs in `BleSpec.kt` don't match the
firmware — verify with nRF Connect before touching anything else.
# ble-music
