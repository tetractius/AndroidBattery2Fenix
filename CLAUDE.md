# CLAUDE.md — developer notes for AI assistants

## Project structure

Two independent sub-projects that must be compiled separately:

- `garmin/` — Connect IQ watch widget (Monkey C)
- `android/` — Android companion app (Kotlin/Gradle)

## Building the Garmin watch widget (macOS)

### SDK location

The Connect IQ SDK Manager installs to:
```
~/Library/Application Support/Garmin/ConnectIQ/Sdks/<version>/
```
(NOT `~/.Garmin/` — that path does not exist on macOS.)

Add `monkeyc` to PATH:
```bash
export PATH="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/connectiq-sdk-mac-9.2.0-2026-06-09-92a1605b2/bin:$PATH"
```

Device profiles land in:
```
~/Library/Application Support/Garmin/ConnectIQ/Devices/
```

### Developer key (generate once)

```bash
openssl genrsa -out ~/garmin_dev.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in ~/garmin_dev.pem -out ~/garmin_dev.der -nocrypt
```

### Compile (debug)

```bash
cd garmin/
mkdir -p build
for device in fenix843mm fenix847mm fenix8solar47mm fenix8solar51mm; do
  monkeyc -f monkey.jungle -d "$device" -o "build/$device.prg" -y ~/garmin_dev.der
done
```

### Compile (release — strip debug info, use for sideloading and store submission)

```bash
cd garmin/
mkdir -p build
for device in fenix843mm fenix847mm fenix8solar47mm fenix8solar51mm; do
  monkeyc -f monkey.jungle -d "$device" -o "build/$device.prg" -y ~/garmin_dev.der -r
done
```

### Known issue — SDK 9.2.0 type checking

SDK 9.2.0 enforces strict types on `Communications.registerForPhoneAppMessages`.
The callback must be typed explicitly:

```monkey-c
// correct
function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void { ... }

// wrong — SDK 9.2.0 rejects this
function onPhoneMessage(msg) { ... }
```

## Building the Android companion app (macOS)

### Java version

Gradle 8.7 (used by this project) supports up to Java 22. Java 26+ will fail
with "Unsupported class file major version". Use Java 17:

```bash
brew install --cask temurin@17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Add the export to `~/.bashrc` or `~/.zshrc` to make it permanent.

### Android SDK location

`android/local.properties` is gitignored, so a fresh checkout has no `sdk.dir`.
Either create that file or export the path:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

### Compile (debug APK)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd android/
./gradlew assembleDebug
# APK → android/app/build/outputs/apk/debug/app-debug.apk
```

## App UUID

`a57999dd5a9942a5a9a35acb96ff49e1` — must match in both:
- `garmin/manifest.xml`
- `android/app/src/main/java/.../BatteryForwardService.kt` (`WATCH_APP_ID`)

## Watch ↔ phone message protocol

Both directions are plain dictionaries over `Communications`/`ConnectIQ`. The
two sides are compiled separately, so any change here has to land in both.

Phone → watch (`BatteryForwardService.sendToWatch`, read by
`BatteryData.saveFromMessage`):

| Key | Type | Notes |
| --- | --- | --- |
| `level` | 0..100 | May be absent in a sound-only reply |
| `charging` | Boolean | Ignored when `level` is absent |
| `soundMode` | 0/1/2 | Normal / Vibrate / Silent |

Watch → phone (`SoundControl.request`, read by
`BatteryForwardService.handleWatchMessages`):

| Key | Type | Notes |
| --- | --- | --- |
| `cmd` | `"setSoundMode"` | Only command so far |
| `soundMode` | 0/1/2 | Requested mode |

The phone always answers a command immediately with the mode it actually ended
up in — the watch does not wait for the next battery update. If the change was
refused the unchanged mode comes back, which is what clears the watch's
pending state.

### Do Not Disturb

`AudioManager.setRingerMode` needs `ACCESS_NOTIFICATION_POLICY` for any change
that toggles DND — i.e. into silent or out of it. Normal ↔ vibrate needs
nothing. There is no runtime dialog: the user grants it on a system settings
screen, offered both by a button in `MainActivity` and by a notification the
service posts the first time a change is refused.
