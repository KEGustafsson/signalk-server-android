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
- No third-party libraries beyond AndroidX and Material, and nodejs-mobile's `libnode.so` (MIT,
  Node 24 from digidem's fork; PLAN §4.1) and npm (Artistic-2.0, the registry tarball; §4.3),
  which `node-runtime` downloads pinned by version and SHA-256 in `gradle.properties`.
  `usb-serial-for-android` (MIT) joins with the bridge (PLAN §7).
- Native: NDK r27d (27.3.13750724), the release libnode.so was built with, and CMake 3.31.6,
  both pinned in `node-runtime/build.gradle.kts` and fetched by AGP from the SDK repository.
- Build: `./gradlew assembleDebug` (wrapper committed; needs an Android SDK with platform 37 via
  `ANDROID_HOME` or `local.properties`). A missing JDK 17 fails as "Cannot find a Java installation
  ... {languageVersion=17}". Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
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
  nothing but `gh release create v<version>` with `SignalKServer-<version>.apk`, its `.sha256` and
  CycloneDX SBOM. Without the keystore `assembleRelease` falls back to the debug key.
- Unit tests (JUnit 4, pure Kotlin, no Android runtime): `./gradlew testDebugUnitTest`. Anything
  that touches the runtime, the service or the bridge is tested on a device; the emulator has no
  USB host, no Bluetooth and a different SELinux policy for exec.

## Layout
```
app/                 the Android app
  fi.pocketsk        MainActivity (currently the R1–R4 spike screen), SpikeInstall (R3), SpikeServer (R4),
                     App, LocalNetwork, WifiLockModes, LegacyPlatform
  fi.pocketsk.net    Backoff, Threads (transportThread, Waiter), NetworkChoice
  fi.pocketsk.signalk SignalKClient, SignalKUrl, SignalKDiscovery, SignalKTree, Quantity (Role only)
node-runtime/        Android library: nodejs-mobile's libnode.so per ABI, the launcher, npm as an asset
  fi.pocketsk.node   NodeRuntime (paths, environment, spawn, npm), Tar (unpacks npm)
native/              CMake: launcher/main.cc -> lib/<abi>/libnodeexec.so, the `node` the app execs;
                     spike-addon/ (R2 test addon, two builds; leaves with the spike screen)
docs/PLAN.md         the plan; keep it current when a decision changes
```
Planned, not yet present (PLAN §2): `runtime-tools/` (node-gyp shim, bootstrap), `sk-plugin/android-bridge/`,
`prebuilds/`.

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
  layout in `NodeRuntime`. `files/npm`, `files/bin` and `files/prebuilds` are excluded too: the APK
  makes them again, and `files/bin/node` points into the old install.
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
