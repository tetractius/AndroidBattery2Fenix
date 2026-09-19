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

## 2. Decide how the Android app reaches users — do this first

Everything downstream depends on this choice, and Google Play is only one of
the options. Worth thinking about properly before committing weeks to it.

**The Connect IQ Store is this app's real discovery channel.** The people who
want this own a fēnix 8, and that is where they look. The Android app is a
dependency of the watch app, not a product in its own right — which means it
does not have to be on Google Play at all. It only has to be *reachable* from
the Connect IQ listing.

### Option A — GitHub Releases (lowest friction)

`origin` is already `github.com/tetractius/AndroidBattery2Fenix`. Attach a
signed APK to a release and link it from the Connect IQ store description.

- No review, no fee, no testers, no `targetSdk` deadline. **This alone removes
  the section 3 blocker**, since the API 36 requirement is a Play rule, not an
  Android one.
- Costs: users see "install unknown apps" warnings, and there is no
  auto-update. [Obtainium](https://github.com/ImranR98/Obtainium) solves
  updates for users who want it, by watching GitHub releases.
- **Time limit worth knowing:** Google is phasing in developer verification
  for *all* install methods including sideloading — September 2026 for Brazil,
  Indonesia, Singapore and Thailand, global in 2027. It costs a one-off US$25
  plus government ID, roughly the same paperwork as a Play account. ADB
  installs are explicitly unaffected. So this route is frictionless now and
  needs a one-time verification later.

### Option B — Google Play

Widest reach and automatic updates, at the cost of the API 36 bump and,
probably, the 12-tester closed test. See section 3.

### Option C — Play with an organization account

The 12-tester rule applies only to **personal** accounts created on or after
13 November 2023. An organization account is exempt, but needs a D-U-N-S
number — free to request, though it takes time to come through. Worth pricing
against the effort of finding 12 testers.

### Option D — Samsung Galaxy Store / Amazon Appstore

No closed-testing requirement, free accounts. Samsung has real reach; Amazon
has little on phones. Reasonable as a supplement, not as the main channel.

### Option E — F-Droid

Probably not viable, for two reasons worth checking before spending time:

- **There is no `LICENSE` file in this repo.** F-Droid needs an OSI-approved
  licence. Easy to fix — the code is yours — but it is a hard gate.
- The Garmin `ciq-companion-app-sdk` AAR is a **proprietary binary
  dependency**, which F-Droid's inclusion policy generally does not allow.
  This is the likely blocker, and no licence change fixes it.

### Recommendation

Start with Option A and treat Play as a later nice-to-have. It gets a working
release into users' hands in an afternoon instead of a month, and the audience
is coming from the Connect IQ store anyway. If the app gains traction, revisit
Play — nothing about Option A forecloses it.

- [ ] Pick a route before doing any store work.
- [ ] Add a `LICENSE` file regardless. The repo has none, which leaves the
      code technically all-rights-reserved and blocks every open-source
      distribution channel.

---

## 3. Google Play — only if Option B or C

**Blocked** by the target API level, and the long pole. Skip this section
entirely if the answer to section 2 was "GitHub Releases".

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

### 3b. The 14-day closed test — check whether this applies

If the Play Console account is a **personal** account created on or after
13 November 2023, production access requires a closed test with **at least 12
testers, opted in continuously for 14 days**, and Google now checks the testers
actually used the app. Organization accounts and personal accounts older than
that date are exempt.

This is the single biggest schedule item — realistically weeks, not days — and
the clock only starts once 12 distinct Google accounts have opted in via the
link and installed on real devices. Emulators and duplicate accounts do not
count, and if one tester drops out the counter resets.

- [ ] Determine which account type applies before planning any dates.
- [ ] If it applies, line up 12 testers early. They do **not** need fēnix 8
      watches — expecting 12 people with the right watch is unrealistic, and
      the requirement is about app usage, not exercising the watch path.
- [ ] Start the closed test as soon as 2a is done; everything else can happen
      while the 14 days run.

### 3c. Release build

- [ ] Generate an upload keystore and write `android/keystore.properties`
      (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The gradle
      config already reads that file and it is gitignored. **Back up the
      keystore** — losing it means never updating the listing again.
- [ ] Set a real `versionCode` / `versionName`; both are still at the `1` /
      `1.0` defaults.
- [ ] Consider `minifyEnabled true`. It is currently off; if turning it on,
      keep the Connect IQ SDK classes, since they are reached reflectively
      through Garmin Connect Mobile.
- [ ] `./gradlew bundleRelease` — Play wants an `.aab`, not the `.apk` the
      debug flow produces.

### 3d. Console paperwork

- [ ] Developer account (one-off fee).
- [ ] Declare the foreground service. The app uses
      `FOREGROUND_SERVICE_CONNECTED_DEVICE`; Play requires declaring and
      justifying foreground service types, and "forwards battery state to a
      paired watch" is exactly the intended use.
- [ ] Privacy policy URL — mandatory, and needs to be publicly hosted.
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

## 4. Publish to the Connect IQ Store

This is the one that actually matters for reaching users. Unblocked — mostly
assets and paperwork.

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
      pair of devices. The same text can serve as the Play privacy policy.
- [ ] Make the listing state plainly that the companion Android app is
      **required**, and link to it. Without it the widget shows "No phone
      data" forever, which is a predictable one-star review. If the watch app
      goes live before the Android app, say so in the description.
- [ ] Submit and wait. Garmin's stated review time is 72 hours.

---

## 5. Loose ends

- [ ] `README.md` still documents the message payload as
      `{"level", "charging"}`. It has gained `soundMode` and a watch → phone
      command; update it when the feature merges.
- [ ] Decide what the store listings say about each other, and keep the two
      descriptions consistent about which app is required for what.
