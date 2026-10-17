# AndroidBattery2Fenix

See your **Android phone's battery level on your Garmin fēnix 8** — right in the
glance carousel you get when you press **DOWN** from the watch face, next to
Weather, Heart Rate, Body Battery and friends.

It also works the other way round: the watch shows your phone's **sound mode**
and lets you **change it from your wrist** — normal ring, vibrate only, or
silent — without taking the phone out of your pocket.

The project has two parts, and you need **both**:

| Part | Path | What it is |
|------|------|------------|
| Watch widget | [`garmin/`](garmin/) | A Connect IQ *glance-capable widget* ("Phone Battery"). The glance shows the battery as a 5% band, e.g. `75-80% ▮▮▮▯`; opening it shows a full-screen view with charging state, data age and the phone's sound mode. Pressing **START** there opens a picker to change that sound mode. |
| Android companion | [`android/`](android/) | A small app ("Battery2Fenix") that watches the phone battery and sound mode and pushes every change to the watch through the Garmin Connect Mobile app — and applies sound-mode commands coming back from the watch. |

## How it works

```
Android phone                            Garmin fēnix 8
┌──────────────────────┐                ┌──────────────────────────┐
│ Battery2Fenix app    │  battery +     │ Garmin "Phone Battery"   │
│  (foreground svc)    ├──sound mode───►│ background service       │
│                      │                │  └─ saves to Storage     │
│  battery broadcasts  │                │ Glance / full view       │
│  AudioManager        │◄──set sound────┤  read Storage            │
│   .ringerMode        │     mode       │ START → sound picker     │
└──────────────────────┘                └──────────────────────────┘
        ▲                                          ▲
        └────── Bluetooth, via Garmin Connect ─────┘
```

Garmin watches cannot query the phone battery themselves — the Connect IQ API
has no such call — so the companion app is mandatory. It sends
`{"level": 0-100, "charging": true/false, "soundMode": 0|1|2}` messages
whenever the battery crosses into a new 5% band, the charging state changes,
or the ringer mode changes — throttled to at most one message per minute,
plus a 15-minute heartbeat. Every message wakes
a small background service on the watch, so sending sparsely keeps the impact
on the watch battery negligible; the watch displays a 5% range
(`75-80%`) anyway, so nothing is lost. The thresholds are constants
(`STEP_PCT`, `MIN_SEND_INTERVAL_MS`, `HEARTBEAT_MS`) at the top of
`BatteryForwardService.kt` if you want to tune them. On the watch, a Connect IQ
*background service* receives these messages **even while the widget is
closed**, stores the latest value, and the glance simply renders whatever was
stored last — so opening the glance is instant. If the data is older than
2 hours the glance greys it out, and the age (`5m`, `2h`, …) is always shown.

### Changing the sound mode from the watch

The reverse direction is a single command. Opening the full widget view and
pressing **START** gives a picker — *Normal ring*, *Vibrate only*,
*Silent / Mute* — and the choice is sent to the phone as
`{"cmd": "setSoundMode", "soundMode": 0|1|2}` **immediately**, rather than
waiting for the next battery update.

The phone is the source of truth. The watch greys the requested mode out while
the request is in flight rather than pretending it has landed; the phone
applies it and replies with the mode it actually ended up in, which is what
gets stored. If the change was refused, the unchanged mode comes back and the
watch reverts on its own — the same code path, not a special case.

**One permission caveat.** Moving the ringer into or out of silent toggles Do
Not Disturb, which Android only allows with *Notification Policy access*.
Normal ↔ vibrate needs nothing. There is no runtime permission dialog for it,
so the app offers the grant two ways: an **"Allow Do Not Disturb access"**
button in the app, and a notification it posts the first time a silent request
is refused. Both open the system settings screen. Until you grant it, picking
*Silent* on the watch simply bounces back to the previous mode.

Both sides are linked by the Connect IQ app UUID
`a57999dd5a9942a5a9a35acb96ff49e1` — it appears in `garmin/manifest.xml` and in
`android/.../BatteryForwardService.kt` (`WATCH_APP_ID`). If you ever change
one, change the other.

---

## 1. Building & installing the watch widget

### Prerequisites

1. Create a free account at [developer.garmin.com](https://developer.garmin.com/connect-iq/sdk/)
   and install the **Connect IQ SDK Manager**. Use it to download the latest
   SDK and the **fēnix 8** device files.
2. Install [Visual Studio Code](https://code.visualstudio.com/) with the
   **Monkey C** extension (by Garmin).
3. In VS Code run `Ctrl+Shift+P → Monkey C: Generate a Developer Key` (one
   time; the key signs your builds).

### Build & sideload (no store needed)

#### Option A — VS Code

1. Open the `garmin/` folder in VS Code.
2. `Ctrl+Shift+P → Monkey C: Build for Device`, pick your exact model
   (e.g. *fēnix 8 47mm*) and an output folder. You get a `.prg` file.

#### Option B — Command line (macOS)

Install the **Connect IQ SDK Manager** from developer.garmin.com, use it to
install the SDK (tested with 9.2.0) and the fēnix 8 device profiles, then
add `monkeyc` to your PATH:

```bash
# adjust the version string if you installed a different SDK
export PATH="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/connectiq-sdk-mac-9.2.0-2026-06-09-92a1605b2/bin:$PATH"
```

Generate a developer key once:

```bash
openssl genrsa -out ~/garmin_dev.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in ~/garmin_dev.pem -out ~/garmin_dev.der -nocrypt
```

Build for all supported fēnix 8 variants:

```bash
cd garmin/
mkdir -p build
for device in fenix843mm fenix847mm fenix8solar47mm fenix8solar51mm; do
  monkeyc -f monkey.jungle -d "$device" -o "build/$device.prg" -y ~/garmin_dev.der
done
```

The `.prg` files land in `garmin/build/`.

#### Sideloading

3. Connect the watch over USB and copy the `.prg` into the watch's
   `GARMIN/Apps` folder. **Unplug** — disconnecting is what makes the watch
   scan that folder and install the app.

   *Notes from doing this on macOS with a fēnix 8 Solar:*

   * The fēnix 8 is **MTP-only** — there is no mass-storage USB mode, so no
     `GARMIN` volume appears in Finder. Use
     [OpenMTP](https://openmtp.ganeshrvel.com/) and drag the file across.
     Garmin Express cannot sideload an arbitrary `.prg`.
   * The command-line MTP tools do **not** work for writing: `libmtp`'s
     `mtp-sendfile` cannot resolve a storage id, and `aft-mtp-cli`'s `put`
     hangs. *Reading* works with both, so you can verify a copy by pulling the
     file back off the watch and comparing checksums.
   * macOS blocks the watch until you accept its **USB accessory** prompt. Before
     you do, the watch does not appear at all — not even in `ioreg` — while
     still charging happily, which looks exactly like a dead cable.
   * Only one MTP client can hold the device at a time, so quit OpenMTP before
     using any CLI tool against it.

4. On the watch, press **DOWN** from the watch face to open the glance
   carousel — "Phone Battery" is now in the list (hold **UP** →
   *Glances* → *Add* if you want to reorder or it isn't shown yet).
5. **Open the widget once** (select the glance, press START). This first run
   registers the background listener that receives phone messages.

You can also test without hardware: `Monkey C: Build for Device` → choose the
simulator, then use the SDK's `Simulation → Phone App Messaging` menu to
inject messages.

### Supported devices

The manifest currently lists `fenix843mm`, `fenix847mm`, `fenix8solar47mm`,
`fenix8solar51mm`. To support more models (fēnix 8 Pro, Epix, etc.), add the
matching `<iq:product id="..."/>` lines in `garmin/manifest.xml` — the valid
ids are shown in the SDK Manager's device list.

---

## 2. Building & installing the Android companion app

### Prerequisites

* Java 17 (Gradle 8.7 requires it — Java 18+ will fail). Install with
  `brew install --cask temurin@17` on macOS.
* The **Garmin Connect** app installed on your phone and paired with your
  fēnix 8 (you almost certainly have this already).

### Build & install

#### Option A — Android Studio

1. Open the `android/` folder in Android Studio (File → Open).
2. Let Gradle sync — the Garmin Connect IQ Mobile SDK
   (`com.garmin.connectiq:ciq-companion-app-sdk:2.2.0`) is pulled from Maven
   Central automatically.
3. Enable *Developer options → USB debugging* on your phone, plug it in and
   press **Run ▶**. (Or *Build → Build APK*, copy the APK to the phone and
   install it — you'll need to allow "install from unknown sources".)

#### Option B — Command line (macOS)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd android/
./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

#### Then, either way

Launch **Battery2Fenix** on the phone. It asks for notification and Bluetooth
permissions (the first is needed for its persistent "forwarding" notification)
and starts the forwarding service automatically. The notification shows what
was last sent, e.g. *"78% sent at 14:02"*, or *"Sound set to vibrate only from
watch"* after a command.

If you want to change the phone to **silent** from the watch, also tap
**"Allow Do Not Disturb access"** and grant it on the settings screen that
opens. See [Changing the sound mode from the watch](#changing-the-sound-mode-from-the-watch).

*Upgrading an existing install?* Debug APKs are signed with a per-machine key,
so installing one built on a different computer fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old copy first; the app
stores nothing, so you lose only the Do Not Disturb grant.

### Making it survive in the background

The app runs as a foreground service, but aggressive vendors (Samsung,
Xiaomi, …) may still kill it. If updates stop:

* Set the app's battery usage to **Unrestricted** (Settings → Apps →
  Battery2Fenix → Battery).
* After a reboot, the service tries to restart itself; on some Android
  versions you may need to open the app once.

---

## 3. First-run checklist

1. Watch: widget sideloaded, opened once. Glance shows `--%` for now.
2. Phone: Garmin Connect paired and connected to the watch.
3. Phone: Battery2Fenix running (persistent notification visible).
4. Within seconds the glance should show your phone's battery. Unplug/plug
   the charger to force an immediate update.
5. Open the widget (select the glance, press **START**) and press **START**
   again — the sound picker appears, and choosing an entry should change the
   phone's ringer within about a second.

---

## 4. Publishing

### Watch widget → Connect IQ Store

Garmin's store is free and the only way to distribute Connect IQ apps
without sideloading:

1. `Ctrl+Shift+P → Monkey C: Export Project` in VS Code (or
   `monkeyc -e ...`) to produce a signed `.iq` package.
2. Go to [apps.garmin.com/developer](https://apps.garmin.com/developer/dashboard)
   (or developer.garmin.com → Connect IQ → *App Submission*), sign in with
   your Garmin account and choose **Upload an app**.
3. Upload the `.iq` file, fill in name, description, category (*Widgets*),
   screenshots (grab them from the simulator: `File → Save Screenshot`) and a
   store icon.
4. In the listing, link the companion app: there's a field for an associated
   Android app / Google Play URL — point it at your Play Store listing (see
   below) so users find both halves.
5. Submit for review. Approval typically takes a few days; updates are
   uploads of a new `.iq` with a bumped version.

Once approved, users install it from the Connect IQ Store app on their phone
and it syncs to the watch automatically — no sideloading.

### Android app → Google Play

1. Create a [Google Play Console](https://play.google.com/console) developer
   account (one-time \$25 fee).
2. In Android Studio: *Build → Generate Signed App Bundle* → create an upload
   keystore (**back it up!**) → build a release `.aab`.
3. In Play Console: *Create app* → upload the `.aab` under *Production* (or
   *Internal testing* first — recommended), fill in the store listing,
   content rating questionnaire and privacy declarations.
   * The app collects no personal data; the data-safety form can say "no data
     collected/shared".
   * Declare the foreground-service (`connectedDevice`) usage: it maintains a
     Bluetooth-companion link to a wearable.
4. Roll out. Reviews usually take a day or two.

**Cheaper alternative:** skip Play entirely and attach the release APK to a
[GitHub Release](../../releases) — users sideload it. Fine for personal use
and friends; Play is only needed for wide distribution.

---

## Project layout

```
garmin/
  manifest.xml                     # app id, type=widget, fenix 8 targets, permissions
  monkey.jungle
  resources/                       # strings, launcher icon
  source/
    PhoneBatteryApp.mc             # app entry: glance/widget/background wiring
    PhoneBatteryBackgroundService.mc  # receives phone messages in background
    PhoneBatteryGlanceView.mc      # the DOWN-button glance
    PhoneBatteryView.mc            # full-screen widget view
    SoundControl.mc                # sound-mode picker, transmit, pending state
    BatteryData.mc                 # shared storage + formatting helpers
android/
  app/src/main/java/io/github/tetractius/battery2fenix/
    MainActivity.kt                # minimal UI, start/stop, DND grant button
    BatteryForwardService.kt       # foreground service, Connect IQ messaging
    BootReceiver.kt                # restart after reboot
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| Glance stuck at `--%` | Open the widget once (registers the listener); check the phone notification says "sent"; check Garmin Connect shows the watch as connected. |
| Notification says "No Garmin watch connected" | Open Garmin Connect and let it reconnect to the watch. |
| Value goes grey / age keeps growing | Android killed the service — set battery usage to Unrestricted and reopen the app. |
| Gradle can't find the Garmin SDK | Make sure Maven Central isn't blocked; the dependency is `com.garmin.connectiq:ciq-companion-app-sdk:2.2.0@aar`. |
| Build error on an added device id | That model's device files aren't downloaded in the SDK Manager, or the id is misspelled. |
| Picking *Silent* on the watch snaps back to the previous mode | Do Not Disturb access isn't granted. Tap "Allow Do Not Disturb access" in the phone app, or the notification it posted. Normal ↔ vibrate works without it. |
| Sound picker shows "Sending..." and never settles | The phone didn't answer — check Garmin Connect shows the watch connected and that Battery2Fenix is running. The watch clears the pending state as soon as any reply arrives. |
| Sound mode row on the watch is stale | It only updates when the phone sends; the ringer-change broadcast is immediate, but a watch that was out of range catches up on the next heartbeat. |
| Watch doesn't appear over USB on macOS | Accept the USB accessory prompt. Until then it is invisible to the system while still charging, which mimics a broken cable. |
