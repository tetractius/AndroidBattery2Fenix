# AndroidBattery2Fenix

See your **Android phone's battery level on your Garmin fēnix 8** — right in the
glance carousel you get when you press **DOWN** from the watch face, next to
Weather, Heart Rate, Body Battery and friends.

The project has two parts, and you need **both**:

| Part | Path | What it is |
|------|------|------------|
| Watch widget | [`garmin/`](garmin/) | A Connect IQ *glance-capable widget* ("Phone Battery"). The glance shows `78% ▮▮▮▯` at a glance; opening it shows a full-screen view with charging state and data age. |
| Android companion | [`android/`](android/) | A small app ("Battery2Fenix") that watches the phone battery and pushes every change to the watch through the Garmin Connect Mobile app. |

## How it works

```
Android phone                        Garmin fēnix 8
┌─────────────────────┐              ┌──────────────────────────┐
│ Battery2Fenix app   │   Bluetooth  │ Garmin "Phone Battery"   │
│  (foreground svc)   ├─────────────►│ background service       │
│  battery broadcasts │  via Garmin  │  └─ saves to Storage     │
└─────────────────────┘  Connect app │ Glance reads Storage     │
                                     └──────────────────────────┘
```

Garmin watches cannot query the phone battery themselves — the Connect IQ API
has no such call — so the companion app is mandatory. It sends
`{"level": 0-100, "charging": true/false}` messages whenever the percentage or
charging state changes (plus a 15-minute heartbeat). On the watch, a Connect IQ
*background service* receives these messages **even while the widget is
closed**, stores the latest value, and the glance simply renders whatever was
stored last — so opening the glance is instant. If the data is older than
2 hours the glance greys it out, and the age (`5m`, `2h`, …) is always shown.

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

1. Open the `garmin/` folder in VS Code.
2. `Ctrl+Shift+P → Monkey C: Build for Device`, pick your exact model
   (e.g. *fēnix 8 47mm*) and an output folder. You get a `.prg` file.
3. Connect the watch over USB (it mounts as a drive) and copy the `.prg`
   into the watch's `/GARMIN/Apps` folder. Unplug — done.

   *If your fēnix 8 mounts in MTP mode on Linux/macOS, use
   [Android File Transfer/OpenMTP] or Garmin Express to copy the file.*

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

* [Android Studio](https://developer.android.com/studio) (any recent version).
* The **Garmin Connect** app installed on your phone and paired with your
  fēnix 8 (you almost certainly have this already).

### Build & install

1. Open the `android/` folder in Android Studio (File → Open).
2. Let Gradle sync — the Garmin Connect IQ Mobile SDK
   (`com.garmin.connectiq:ciq-companion-app-sdk:2.2.0`) is pulled from Maven
   Central automatically.
3. Enable *Developer options → USB debugging* on your phone, plug it in and
   press **Run ▶**. (Or *Build → Build APK*, copy the APK to the phone and
   install it — you'll need to allow "install from unknown sources".)
4. Launch **Battery2Fenix** on the phone. It asks for notification permission
   (needed for its persistent "forwarding" notification) and starts the
   forwarding service automatically. The notification shows what was last
   sent, e.g. *"78% sent at 14:02"*.

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
    BatteryData.mc                 # shared storage + formatting helpers
android/
  app/src/main/java/io/github/tetractius/battery2fenix/
    MainActivity.kt                # minimal UI, start/stop
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
