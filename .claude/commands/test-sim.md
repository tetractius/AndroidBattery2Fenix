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
assertion for Steps 5 and 6. Write down the three values before proceeding.

## Step 5 — Headless tests first

Before asking the human for anything, get as far as you can with
`Toybox.Test` ("Run No Evil"), which runs in the simulator with no clicking and
prints machine-readable pass/fail:

```bash
cd garmin
monkeyc -f monkey.jungle -d fenix847mm -o build/test.prg -y ~/garmin_dev.der --unit-test
monkeydo build/test.prg fenix847mm -t
```

A single test: `monkeydo build/test.prg fenix847mm -t testName`. Test code is
stripped from debug and release builds, so these files are safe to keep — but
put them in a new `garmin/source/SoundControlTest.mc` rather than editing
existing sources, and ask before committing them.

### 5a. Pure logic — these will work

The message-handling rules are the part most likely to be subtly wrong, and they
need no connection at all. Write tests for:

- `BatteryData.saveFromMessage({"soundMode" => 2})` — a sound-only reply, no
  battery fields. `getSoundMode()` must become 2. This is the case the old code
  dropped on the floor, so it is worth pinning down.
- Setting `BatteryData.pendingSoundMode = 1`, then delivering a message with
  `soundMode` — pending must clear.
- Delivering a message with **no** `soundMode` key — pending must NOT clear.
- Out-of-range values (`-1`, `3`) and wrong types (`"2"`) must be ignored, not
  stored.
- A battery-only message must not disturb the stored sound mode.

Note these mutate real `Storage`, so each test should set up its own state
rather than assuming a clean slate.

### 5b. End-to-end without clicking — try it, it may not work

A `(:test)` function can call `SoundControl.request(2)` directly, which skips
the menu entirely and fires the real `Communications.transmit`. With the ADB
connection from Steps 2–3 live, that should reach the emulator and move the
ringer — a full watch-code-to-phone assertion with no human involved:

```monkeyc
(:test)
function testTransmitSilent(logger as Test.Logger) as Boolean {
    logger.debug("transmitting silent request");
    SoundControl.request(2);
    return true; // the real assertion is on the phone, via adb
}
```

Then assert with the read command you established in Step 4, polling for a few
seconds rather than checking once.

Two reasons this may legitimately fail, neither of which means the feature is
broken — report which one you hit:

- `transmit` is asynchronous and the test returns immediately, so the app may be
  torn down before the message leaves.
- `Communications` may not be wired up in the unit-test harness the way it is in
  a normally-launched app.

If it fails, fall back to Step 6 for the integration check. Do not spend long
fighting it.

## Step 6 — The UI path (needs the human)

Whatever 5b could not cover, plus the one thing no headless test can reach:
**whether `onSelect` actually fires in the widget view and the menu renders.**
That is the riskiest assumption in the whole change, so even if 5b passes,
case 1 below is still worth doing once.

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

## Step 7 — Clean up

1. **Revert the TETHERED edit** in `BatteryForwardService.kt` and confirm with
   `git diff` that the only remaining changes are ones the user wants.
2. Remove any temporary `System.println` calls. Unit test files are stripped
   at build time and can stay, but ask before committing them.
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
