# Signal K Server for Android — project guide for Claude Code

Android app (package `fi.pocketsk`, working name "Pocket SK") that runs the real Node.js
Signal K Server on a phone or tablet. The APK ships a Node runtime and an installer; the server
itself is installed from npm at first run and upgraded in place. The plan of record is
[docs/PLAN.md](docs/PLAN.md); read its §1 and §3 before touching architecture. Sibling project:
[Crew Radio](https://github.com/KEGustafsson/crew-radio), from which the toolchain, the release
pipeline and several files were lifted (PLAN §17).

## Stack
- Android Gradle Plugin 9.4 with its built-in Kotlin (the Kotlin Android plugin is applied nowhere;
  the root build puts Kotlin 2.4 on the build classpath), Gradle 9.8, compileSdk 37, minSdk 29,
  targetSdk 37, JDK 17 (a real toolchain: Gradle will not download one). R8 shrinks release builds
  with names kept. Dependabot keeps AndroidX current; the toolchain is moved by hand.
- No third-party libraries beyond AndroidX (Compose among them, from its BOM, for the Dock) and
  Material, and nodejs-mobile's `libnode.so` (MIT, Node 24 from digidem's fork; PLAN §4.1) and npm
  (Artistic-2.0, the registry tarball; §4.3), which `node-runtime` downloads pinned by version and
  SHA-256 in `gradle.properties`.
  `usb-serial-for-android` (MIT, PLAN §7), which is published on JitPack only: `settings.gradle.kts`
  takes that repository for the `com.github.mik3y` group alone.
- Native: NDK r27d (27.3.13750724), the release libnode.so was built with, and CMake 3.31.6,
  the NDK pinned as `nodejsMobile.ndk` in `gradle.properties` (node-runtime builds with it, the
  app strips native libraries with it), CMake in `node-runtime/build.gradle.kts`; AGP fetches
  both from the SDK repository.
- Build: `./gradlew assembleDebug` (wrapper committed; needs an Android SDK with platform 37 via
  `ANDROID_HOME` or `local.properties`). A missing JDK 17 fails as "Cannot find a Java installation
  ... {languageVersion=17}". Install: `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk`
  (or `app-arm64-v8a-debug.apk`, the smaller split; ABI splits in `app/build.gradle.kts`).
- Versions come from git in `app/build.gradle.kts`: `versionCode` = commit count, `versionName` =
  `1.<count>`, `BuildConfig.GIT_SHA`. Never edit version numbers by hand. The Signal K server's
  version is a runtime matter, not a build one.
- Every dependency is checked against `gradle/verification-metadata.xml`. After changing a
  dependency: `./gradlew --write-verification-metadata sha256 help testDebugUnitTest assembleRelease assembleDebug lintRelease sbom`,
  review the diff, commit it with the change.
- Release pipeline: `.github/workflows/build.yml`, three jobs. `build` (every push/PR, read-only
  token): unit tests, debug-signed `assembleRelease`, Lint with warnings as errors. On `main`:
  `release` signs with the `SIGNALK_ANDROID_*` secrets, verifies the signer certificate against the
  `SIGNALK_ANDROID_CERT_SHA256` variable, attests; `publish` holds the only write token and does
  nothing but `gh release create v<version>` with `SignalKServer-<version>.apk` (universal),
  `SignalKServer-<version>-arm64-v8a.apk`, their `.sha256` files and the CycloneDX SBOM. Without the keystore `assembleRelease` falls back to the debug key.
- Native addon prebuilds (PLAN §9.1): `.github/workflows/prebuilds.yml` runs `prebuilds/build.js`
  over `prebuilds/packages.json` with the NDK and nodejs-mobile of `gradle.properties`, on a change
  to either, weekly and by hand. The same three-job split: `build` (read-only; it runs the
  packages' binding.gyp), `attest`, and `publish`, which uploads to the Release tagged `prebuilds`
  (never latest) the builds and `index.json`, the list the phone's npm launcher reads
  (runtime-tools/npm). On a pull request
  only `build` runs, with `--strict`. The script's own tests: `node --test test/*.test.js` in
  `prebuilds`.
- Unit tests (JUnit 4, pure Kotlin, no Android runtime): `./gradlew testDebugUnitTest`. Anything
  that touches the runtime, the service or the bridge is tested on a device; the emulator has no
  USB host, no Bluetooth and a different SELinux policy for exec. The bridge plugin's tests:
  `npm test` in `sk-plugin/android-bridge` (`npm run coverage` enforces what CI does); CI runs them
  through Signal K's reusable plugin workflow (`.github/workflows/signalk-ci.yml`).

## Layout
```
app/                 the Android app
  fi.pocketsk        MainActivity (the Dock and the setup wizard, Compose; pocketsk://webapp/ links),
                     AlarmActivity (the boat's alarm over the lock screen), ServerActions,
                     WebappShortcuts (the installed webapps, pinned to the home screen),
                     SpikeActivity + SpikeInstall (the runtime checks, spikes R1–R3), App,
                     LocalNetwork, WifiLockModes, LegacyPlatform
  fi.pocketsk.ui     DockScreen, SetupScreen, AlarmScreen, Common (sections, permission rows),
                     AppModel, Theme
  fi.pocketsk.install InstallManager (slots, npm, smoke test, promotion, rollback), InstallJob,
                     ReleaseNotesSource (GitHub's release notes, a day's cache); Slots, ServerVersion,
                     InstallHistory, HealthGate, UpdateOffer, ReleaseNotes (pure, tested)
  fi.pocketsk.server SignalKService (foreground, locks, notification), Supervisor (runs, restarts,
                     health watch, health gate), ServerStatus, ServerLog, ServerPrefs, BootReceiver,
                     ServerTile (Quick Settings), ServerUsage (the Dock's CPU and memory, from /proc),
                     DataVolumes (the phone's shared storage and the cards the data may go to,
                     the USB inbox), DiagnosticsBundle
                     (the Logs card's zip, shared by the FileProvider from cache/diagnostics);
                     RestartPolicy, LogRing, LogFiles, LanAddress, ServerEndpoint, ProcStat,
                     DataStore (the data folders in the folder a computer sees, linked from .signalk),
                     ConfigMirror (.signalk's files copied there, the computer's changes taken in
                     at the next start), Inbox (files a computer copied over USB, into the data
                     folders), LogFilter, Diagnostics (the
                     bundle's contents, never anything under .signalk), AesZip (WinZip AES-256
                     zips, by hand), Backup (the Dock's backup: what is in it, how it goes back)
                     (pure, tested); BackupJob (the one backup or restore, npm for the plugins)
  fi.pocketsk.bridge Bridge (the socket, the sensors, USB and Bluetooth, run by the service),
                     BridgeServer (Unix socket files/bridge.sock, one peer, uid checked), PhoneSensors (battery,
                     barometer, GNSS, attitude, compass, cabin sensors, cellular network),
                     SensorPrefs (the level),
                     UsbSerial (USB serial adapters, their bytes to the plugin), BluetoothSerial
                     (the paired SPP devices chosen in the Dock, SerialPrefs), BleScanner (the
                     Bluetooth LE advertisements heard, to the plugin), PhoneAlarm (the
                     boat's alarms: notification, full-screen intent, alarm sound, vibration),
                     BridgePlugin (puts the plugin in place before every start);
                     BridgeProtocol, Json, Readings, Attitude, SerialIds, DeviceList, AlarmBook,
                     BleRules (ScanBudget, Repeats) (pure, tested)
  fi.pocketsk.update AppUpdater (the app's own update: GitHub's latest release, the download, the
                     checks, the PackageInstaller session), UpdateReceiver (the installer's status
                     and confirmation); AppRelease (pure, tested)
  fi.pocketsk.net    Backoff, Threads (transportThread, Waiter), NetworkChoice
  fi.pocketsk.signalk SignalKClient, SignalKUrl, SignalKDiscovery, SignalKTree, Quantity (Role only),
                     Webapps (a package.json as a webapp, the deep link) (pure, tested)
  fi.pocketsk.night  NightFilter (the helm screen's overlay: red filter, dimming, screen kept on;
                     App holds it), NightTile (Quick Settings); NightRules (pure, tested)
  fi.pocketsk.power  PowerPrefs (the Dock's power profile); PowerRules (what Harbor, Passage and
                     Battery saver take from the plugin's subscription and the screen) (pure, tested)
  fi.pocketsk.widget BoatWidget (the home-screen widget: depth, SOG, apparent wind), GlanceWatch
                     (asks the plugin for them while a widget is placed and the screen is on);
                     GlanceView (pure, tested)
node-runtime/        Android library: nodejs-mobile's libnode.so per ABI, the launcher, npm, the
                     preload and npm's launcher as assets
  fi.pocketsk.node   NodeRuntime (paths, environment, spawn, npm), NodeProcess (a spawn and its
                     process group), Tar (unpacks npm)
runtime-tools/preload/ every node loads these (NODE_OPTIONS --require, written to files/runtime,
                     NodeRuntime.PRELOAD_ASSETS): loopback.js, every TCP listener on 127.0.0.1
                     and no mDNS advertisement when POCKETSK_LOOPBACK=1 (the Dock's LAN access
                     off; NodeRuntime.LOOPBACK_ENV), failing closed; mdns-lookup.js, `.local`
                     names over mDNS, which Android gives native code no resolver for;
                     node-ble.js, a stand-in for
                     @naugehyde/node-ble fed by the phone's Bluetooth LE scanner, so BlueZ plugins
                     (bt-sensors-plugin-sk) run unchanged, sharing a hub with the bridge plugin on
                     Symbol.for('pocketsk.ble'). Plain CommonJS, no dependencies; `node --test
                     test/*.test.js` in runtime-tools (CI runs it). Nothing else goes in preload/:
                     the directory is an asset source as it stands
runtime-tools/npm/   what every npm on the phone runs (files/runtime, NodeRuntime.NPM_TOOL_ASSETS;
                     the same asset rule): npm-launch.js runs npm-cli.js and after an install
                     prebuilds.js puts the Android builds of native addons in place from the
                     `prebuilds` Release (index checked, SHA-256 checked, kept in files/prebuilds),
                     since the App Store installs with --ignore-scripts; its index checks mirror
                     prebuilds/lib.js: change both together
native/              CMake: launcher/main.cc -> lib/<abi>/libnodeexec.so, the `node` the app execs
                     (leads a process group when NodeRuntime asks), and `npm` when started
                     as files/bin/npm, for Signal K's App Store;
                     spike-addon/ (R2 test addon, two builds; leaves with the spike screen)
sk-plugin/android-bridge/  @pocketsk/android-bridge, the bridge's server half: plain CommonJS, no
                     dependencies, `node --test`; packed into the APK as assets/android-bridge.tar.
                     lib/protocol.js mirrors BridgeProtocol: change both together; lib/serial.js
                     parses serial devices' NMEA 0183 with the server's own parser (found through
                     require.main, the signalk-server script); lib/alarms.js sends the server's
                     notifications to the phone and silences them through app.notifications;
                     lib/protocol.js's glanceMessage reads depth, SOG and wind for the widget;
                     lib/ble.js parses the app's Bluetooth LE advertisements for the server's BLE
                     API (as a provider) and node-ble.js's hub; lib/files.js and public/index.html
                     (a webapp) upload files, charts above all, into the server's data folders
prebuilds/           packages.json (the addons built for Android), build.js (npm pack, node-gyp with
                     the NDK, linked against libnode.so, packed for the Release) and lib.js (its
                     pure part, tested); plain CommonJS, no dependencies, run by prebuilds.yml
docs/PLAN.md         the plan; keep it current when a decision changes
```
Planned, not yet present (PLAN §2): `runtime-tools/bootstrap`.

## Rules of the house
- Threading: nothing blocking on the main thread. Worker loops use `transportThread` and report
  errors through its callback rather than crashing; retries use `Backoff`.
- `LegacyPlatform` is the only file that may call a deprecated platform API, behind an SDK check
  at the caller. A deprecated constant with a stable value is written out beside its caller and
  pinned by a unit test.
- Deprecations, Lint warnings and unused resources are fixed, not suppressed; a suppression
  carries a comment saying why.
- Secrets (the admin token, anything under `files/home/.signalk`) never enter backups, logs or
  the diagnostics bundle. `data_extraction_rules.xml` excludes them; keep it in step with the
  layout in `NodeRuntime`. The server's data folders live in the folder a computer sees over USB,
  `Android/data/fi.pocketsk/files/signalk` on the phone's shared storage or a card (`DataStore`),
  and the files directly in `.signalk` and the plugins' settings files are copied there
  (`ConfigMirror`), as the owner asked (PLAN §16 9.5.11): the live files and `node_modules` stay in
  `files/home/.signalk`, and that folder is in the external backup domain, which the rules exclude. `files/npm`, `files/bin`, `files/prebuilds`, `files/plugins` (the
  bridge's plugin) and `files/runtime` (the preload) are excluded too, with `files/bridge.sock`: the APK makes them again, and `files/bin/node` points into the old install. So is `files/logs`, the
  server's output, which may carry whatever a plugin printed.
- Doc comments explain why, in full sentences. A file copied from Crew Radio says so at the top of
  its class comment, so a fix found in one repo is carried to the other.
- Commit messages: what changed and why, present tense, wrapped at 72. Version numbers are never
  part of a commit.

## Review and merge
The plan is implemented one step at a time (PLAN §16, then the milestones in §13), one pull
request per step. Nothing reaches `main` without a review, and a green, reviewed step is merged
without waiting so the next one can start.
- Every change goes through a pull request; nothing is pushed to `main` directly. The next step
  starts from the merged `main`, never on top of an unmerged branch.
- CodeRabbit reviews every pull request. Automatic reviews are off for this repository, so ask
  for one with a PR comment `@coderabbitai review` when the PR is opened and again after every
  push that follows a review. The plan allows about one review an hour; when CodeRabbit says the
  allowance is used up, wait for it and ask again rather than merging unreviewed.
- Every CodeRabbit finding gets an answer on its thread. Fixed: push the fix, reply naming the
  commit and what it changed, and ask CodeRabbit to resolve it (`@coderabbitai resolve`, or
  resolve the thread). Not fixed: reply with the reason. A finding is a claim to check against
  the code, not an order, but "optional" or "minor" is not a reason to leave it unanswered.
- Merge, without asking, when all of these hold on the PR's current head: CI green (Build and
  CodeQL), a CodeRabbit review of that head with no actionable comments left, every review
  thread resolved, no merge conflict. A push after the last review means a new review first.
- Do not merge, and ask the owner instead, when a person has requested changes or a step needs
  a decision the plan does not make. Say so on the PR.
- What only a phone can prove (the runtime, the service, the bridge; see Unit tests above) does
  not hold a merge: the PR lists it under "To check on a device", and PLAN §3 keeps the spike
  results.
- Merge with a merge commit, as the history so far does. The working branch is then reset onto
  the new `main` for the next step.
