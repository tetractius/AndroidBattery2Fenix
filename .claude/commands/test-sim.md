---
description: Drive the Connect IQ simulator + Android emulator to test watch-initiated sound mode control
---

# Test watch → phone sound mode control in the simulators

Verify the feature on branch `feature/watch-sound-mode-control`: a picker on the
watch widget (Normal ring / Vibrate only / Silent) that transmits a command to
the Android companion app, which changes the phone's ringer mode and answers
immediately.

This has only ever been compile-verified. Nothing below has been run end to end,
so treat every step as a hypothesis to confirm, and report what actually
happened rather than what was supposed to happen.

## Division of labour — read first

You **cannot click the Connect IQ simulator**. It is a macOS GUI app and this
session has no screen-control tool for it. So:

- You do: builds, launching the simulator and emulator, ADB wiring, installing,
  reading logs, asserting on state.
- The human does: pressing buttons in the Connect IQ simulator window.

When you need a click, stop and ask for exactly one action in plain terms
("in the simulator window, press the START button"), then continue. Do not
guess that a click happened — wait for confirmation.

## Environment

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/connectiq-sdk-mac-9.2.0-2026-06-09-92a1605b2/bin:$PATH"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

`android/local.properties` is gitignored and may not exist; `ANDROID_HOME`
covers it. The only AVD on this machine is `Medium_Phone_API_36.0`.

## Step 1 — Watch app into the simulator

```bash
cd garmin
monkeyc -f monkey.jungle -d fenix847mm -o build/fenix847mm.prg -y ~/garmin_dev.der
```

Launch the simulator (background; it is a GUI app and will not return):

```bash
connectiq
```

Give it a few seconds to come up, then push the app:

```bash
monkeydo build/fenix847mm.prg fenix847mm
```

`monkeydo` fails if the simulator is not already running — if it errors, wait
and retry rather than assuming the build is broken.

## Step 2 — Companion app in TETHERED mode

The committed app uses `IQConnectType.WIRELESS`, which requires Garmin Connect
Mobile and a real watch. To talk to the **simulator** it must use `TETHERED`
instead — same Maven artifact, no dependency swap, and Garmin Connect Mobile is
not needed.

Make this edit in `BatteryForwardService.onCreate`:

```kotlin
// TEMPORARY - simulator testing only, revert before committing
connectIQ = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
connectIQ.setAdbPort(7381)
```

**This edit must never be committed.** Note the exact original line before you
touch it, and revert it in Step 6. If you prefer, make it conditional on
`BuildConfig.DEBUG` plus a gradle property instead — cleaner, but that is a real
source change the user has not asked for, so ask before going that route.

Then:

```bash
emulator -avd Medium_Phone_API_36.0 &
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'

cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:7381 tcp:7381
adb shell am start -n io.github.tetractius.battery2fenix/.MainActivity
```

`adb forward` must be re-issued if the emulator restarts.

## Step 3 — Connect the two

Ask the human to press **Ctrl-F1** in the simulator window, or use its
**Connection → Start** menu. Until this happens the companion app will report no
connected devices.

Confirm from the Android side:

```bash
adb logcat -c
adb logcat -d -s BatteryForward
```

You want `Connect IQ SDK ready` followed by `Listening for commands from ...`.
If the second line never appears, `registerForAppEvents` found no connected
device — the simulator connection is not up.

## Step 4 — Establish the ringer baseline before testing

Do not trust any assumed mapping between `AudioManager` constants and what adb
reports. Establish it empirically first. Set each mode by hand and record what
the read command returns:

```bash
adb shell cmd notification allow_dnd io.github.tetractius.battery2fenix
adb shell settings get global mode_ringer
adb shell dumpsys audio | grep -i ringer
```

The `allow_dnd` command is the non-interactive way to grant Notification Policy
access (needed for anything touching Silent). **I have not verified that this
command exists on API 36** — if it fails, grant it through the app's own
"Allow Do Not Disturb access" button on the emulator screen, which you can tap
with `adb shell input tap`, or via
`adb shell am start -a android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS`.

Whichever read command gives a stable, distinct value per mode is your
assertion for Step 5. Write down the three values before proceeding.

## Step 5 — The actual test

For each case, ask the human to drive the simulator, then assert from adb.

Reaching the picker: the widget's full view is where the picker lives, not the
glance. The human presses START to open the widget from the glance, then START
again to open the sound picker, then selects an item.

| # | Action on watch | Expect on phone | Expect back on watch |
|---|---|---|---|
| 1 | Pick "Vibrate only" from Normal | ringer → vibrate, no permission needed | row shows Vibrate, orange |
| 2 | Pick "Normal ring" from Vibrate | ringer → normal | row shows Normal, green |
| 3 | Pick "Silent" **with** DND access | ringer → silent | row shows Silent, red |
| 4 | Pick "Silent" **without** DND access | ringer unchanged, high-priority notification posted | row reverts to previous mode |
| 5 | Pick anything with the connection stopped | nothing | row reverts (transmit `onError`) |

Case 4 is the interesting one — revoke access first with
`adb shell cmd notification disallow_dnd io.github.tetractius.battery2fenix`
(again, unverified; otherwise revoke it in Settings). The watch reverting is the
point: it proves the phone is the source of truth rather than the watch
optimistically lying.

Case 5 needs the simulator connection stopped (Connection → Stop).

Assertions per case:

```bash
adb shell settings get global mode_ringer      # or whatever Step 4 established
adb logcat -d -s BatteryForward | tail -30
adb shell dumpsys notification --noredact | grep -i -A3 battery2fenix
```

The watch-side result is visual — ask the human what the sound row reads and
what colour it is. If you need machine-readable watch output instead, add a
temporary `System.println` in `SoundControl.request` and in
`BatteryData.saveFromMessage`; the simulator prints it to the console, and
`monkeydo` streams it to stdout.

## Step 6 — Clean up

1. **Revert the TETHERED edit** in `BatteryForwardService.kt` and confirm with
   `git diff` that the only remaining changes are ones the user wants.
2. Remove any temporary `System.println` calls.
3. `adb forward --remove tcp:7381`
4. Rebuild both sides to confirm the tree still compiles after the reverts:
   `./gradlew assembleDebug` and the four-device `monkeyc` loop from `CLAUDE.md`.
5. Kill the emulator and simulator if you started them.

## Reporting

Say plainly which of the five cases passed, which failed, and which you could
not reach. "Not tested" is a valid and useful result — do not present an
untested case as passing. If the feature is broken, the likely suspects in
rough order:

- `onSelect` never fires, so the picker does not open (widget input limits)
- `registerForAppEvents` silently binds to nothing because the device list was
  empty at registration time
- The message arrives but `map["cmd"]` does not match, because Monkey C
  dictionary keys crossed the bridge as a different type than expected
- The reply arrives but `saveFromMessage` drops it

Known-good facts you can rely on: the feature compiles for all four fenix 8
targets on SDK 9.2.0, the debug APK builds clean, and the Connect IQ Android SDK
2.2.0 does expose `registerForAppEvents` and `IQConnectType.TETHERED`.
