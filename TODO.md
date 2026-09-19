# TODO — agent work plan

Working notes for a Claude session picking this up. Build commands and the
message protocol live in [CLAUDE.md](CLAUDE.md); this file is only about what
is left to do and what is in the way.

Status as of 2026-09-19. Branch `feature/watch-sound-mode-control` is
compile-verified and **not tested on hardware or in a simulator**, and not
merged.

---

## 1. Finish the sound mode feature

Watch-initiated phone sound mode control (Normal / Vibrate / Silent), commit
`58bc3c7` on `feature/watch-sound-mode-control`.

- [ ] Run `/test-sim` and record which of its five cases actually pass.
      Headless unit tests first; only the `onSelect`-fires-and-menu-renders
      check needs a human at the simulator.
- [ ] Sideload to the real fēnix 8 and test against a real phone. The
      simulator path uses `IQConnectType.TETHERED`, which is **not** how the
      shipped app talks to a watch, so a green simulator run does not prove
      the BLE path works.
- [ ] Confirm the Do Not Disturb prompt appears and is actionable on a real
      phone when Silent is picked without the grant.
- [ ] Open a PR and merge to `main`.

Highest-risk assumption, still unverified: that `BehaviorDelegate.onSelect`
fires in the widget's full view on fēnix 8 hardware. If it does not, the
picker is unreachable and the feature needs rethinking — the fallback would
be converting `type="widget"` to `type="watch-app"` in `garmin/manifest.xml`,
which changes where the app appears on the watch.

---

## 2. Publish to the Connect IQ Store

Do this **after** the feature is merged, so the first release includes it.

Nothing here is blocked — this is mostly paperwork and assets.

- [ ] Create a developer account on developer.garmin.com if there is not one.
- [ ] Decide the release scope. `garmin/manifest.xml` lists only four fēnix 8
      products. That is a defensible v1, but check whether other Connect IQ
      6.x devices could be added cheaply — every product in `<iq:products>`
      widens reach and the code has no device-specific logic.
- [ ] Build the store package. The CLI flag is `-e` / `--package-app`
      ("Create an application package"), producing a `.iq` containing binaries
      for every supported product:
      ```bash
      cd garmin
      monkeyc -e -f monkey.jungle -o build/PhoneBattery.iq -y ~/garmin_dev.der -r
      ```
      **Keep `~/garmin_dev.der` safe and backed up.** Store updates must be
      signed with the same key; losing it means losing the ability to update
      the published app.
- [ ] Produce store assets: description, screenshots (take them from the
      simulator), and a hero/icon image.
- [ ] Write a privacy statement. The app holds the `Communications`
      permission and moves phone battery level and ringer state off the
      phone, and Garmin's publishing guide puts GDPR compliance squarely on
      the developer. Keep it short and honest: data goes phone → watch over
      the user's own Bluetooth link, nothing is collected, nothing leaves the
      pair of devices.
- [ ] Make the listing state plainly that the companion Android app is
      **required**, and link to it. Without it the widget shows "No phone
      data" forever, which is a predictable one-star review.
- [ ] Submit and wait. Garmin's stated review time is 72 hours.

---

## 3. Publish to Google Play

**Blocked right now.** Fix the target API level before anything else here.

### 3a. The blocker

`android/app/build.gradle` has `compileSdk 34` / `targetSdk 34`. Google Play
required new apps and updates to target **API 36 (Android 16)** from
**31 August 2026** — that deadline has already passed, so a submission at
API 34 will be rejected outright. An extension to 1 November 2026 was
available but had to be requested in advance.

- [ ] Bump `compileSdk` and `targetSdk` to 36.
- [ ] Expect the toolchain to need bumping with it: AGP is on 8.5.2 and
      Kotlin on 1.9.24, both likely too old for `compileSdk 36`. Bumping AGP
      will probably force a Gradle wrapper bump too. Java 17 should stay
      fine. Verify rather than guessing at versions — check what AGP actually
      demands and follow the error messages.
- [ ] Android 16 removes the ability to opt out of edge-to-edge. The single
      `LinearLayout` in `activity_main.xml` has no insets handling, so
      content will likely slide under the status and navigation bars. Apply
      window insets and check it on a device with gesture navigation.
- [ ] Re-test the whole app after the bump. `minSdk 26` stays; the foreground
      service, the boot receiver and the ringer control are all areas where
      newer Android has tightened rules.

### 3b. Release build

- [ ] Generate an upload keystore and write `android/keystore.properties`
      (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The gradle
      config already reads that file and it is gitignored. **Back up the
      keystore** — losing it means never updating the listing again, same as
      the Garmin key.
- [ ] Set a real `versionCode` / `versionName`; both are still at the `1` /
      `1.0` defaults.
- [ ] Consider `minifyEnabled true`. It is currently off; if turning it on,
      keep the Connect IQ SDK classes, since they are reached reflectively
      through Garmin Connect Mobile.
- [ ] `./gradlew bundleRelease` — Play wants an `.aab`, not the `.apk` the
      debug flow produces.

### 3c. Console paperwork

- [ ] Developer account (one-off fee).
- [ ] Declare the foreground service. The app uses
      `FOREGROUND_SERVICE_CONNECTED_DEVICE`; Play requires declaring and
      justifying foreground service types, and "forwards battery state to a
      paired watch" is exactly the intended use.
- [ ] Privacy policy URL — mandatory, and needs to be publicly hosted. The
      same text as the Garmin one can serve.
- [ ] Data safety form. The honest answer is that no data is collected or
      shared; battery level and ringer state go only to the user's own paired
      watch.
- [ ] Content rating questionnaire, store listing, screenshots.
- [ ] Note `ACCESS_NOTIFICATION_POLICY` in the listing description so the DND
      prompt does not surprise users. It is not on Play's sensitive-permission
      declaration list, so no separate form is needed.
- [ ] The `<queries>` entry targets the Garmin Connect package specifically,
      not `QUERY_ALL_PACKAGES`, so it needs no justification.

---

## 4. Loose ends

- [ ] `README.md` still documents the message payload as
      `{"level", "charging"}`. It has gained `soundMode` and a watch → phone
      command; update it when the feature merges.
- [ ] The two stores are separate submissions with separate review cycles.
      Publish the Android app first, or at least do not let the watch app go
      live while the companion is unavailable — the widget is useless alone.
