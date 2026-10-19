# Signal K for Android — Implementation Plan

Repository: `KEGustafsson/signalk-server-android`. App working name: **Pocket SK** (package `fi.pocketsk`; rename freely). Licence: **EUPL-1.2**, same as Crew Radio, so code moves between the two repos without relicensing (EUPL-1.2 is GPL- and MPL-compatible; nodejs-mobile and usb-serial-for-android are MIT and can be included).

Sibling project: [Crew Radio](https://github.com/KEGustafsson/crew-radio) (`fi.crewradio`, same author). Section 17 lists what is lifted from it; the toolchain below matches it so copied files compile unchanged.

## 0. One-paragraph pitch

An Android app that turns any phone or tablet into a full Signal K Server. The APK ships **only a Node.js runtime and a bootstrap installer** — not Signal K itself. On first run the app installs `signalk-server` from npm into app-private storage, exactly as `npm install -g signalk-server` would on a Pi. The server, its App Store, plugins and webapps run unmodified. The app wraps this with a native control surface (service supervision, A/B upgrades with automatic rollback, USB/Bluetooth serial bridging, phone sensors as Signal K data, helm kiosk mode and real Android alarms).

## 1. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Android app process (Kotlin / Jetpack Compose)              │
│                                                              │
│  ┌──────────────┐  ┌───────────────┐  ┌────────────────────┐ │
│  │ Dock UI      │  │ Foreground    │  │ Bridge (Unix sock) │ │
│  │ (native)     │  │ Service       │  │  · USB serial      │ │
│  │ status, A/B, │  │  · supervisor │  │  · BT SPP serial   │ │
│  │ devices,     │  │  · wake/mcast │  │  · GNSS / IMU /    │ │
│  │ logs, kiosk  │  │    locks      │  │    baro / battery  │ │
│  └──────────────┘  │  · log ring   │  │  · alarms out      │ │
│  ┌──────────────┐  └───────┬───────┘  └─────────┬──────────┘ │
│  │ Opens admin  │          │ spawn               │            │
│  │ UI + webapps │          ▼                     │            │
│  │ in browser   │  ┌──────────────────────────────┴─────────┐ │
│  └──────────────┘  │ child process: libnode.so (exec'd)     │ │
│                    │  signalk-server (installed from npm)   │ │
│                    │  + @pocketsk/android-bridge plugin     │ │
│                    └────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 1.1 Key design decisions

| Decision | Choice | Why |
|---|---|---|
| Node runtime | `nodejs-mobile` (digidem fork, Node 24 line) built as `libnode.so`, plus a 30-line launcher executable | Current Node, N-API addons work, maintained |
| How Node runs | A **real executable** (`libnodeexec.so` in `jniLibs/`, exec'd from `nativeLibraryDir`) rather than JNI-embedded | Android forbids exec from writable dirs, but `nativeLibraryDir` is executable. A real `node` binary means `npm`, `child_process`, `process.execPath` and server restarts all work unmodified |
| Signal K | **Not bundled.** Installed at first run via npm into `files/sk/slots/<A|B>` | Tiny APK, server version independent of app releases, install == upgrade |
| Upgrades | A/B slots, health-gated promotion, automatic rollback | A failed upgrade on a boat must never brick the hub |
| Native addons | Prebuilt `android-arm64` / `armeabi-v7a` binaries from our own GitHub Releases, resolved by an npm `node-gyp` shim | No compiler on the phone; prebuild ecosystems have no Android targets |
| Hardware I/O | Kotlin bridge exposing USB serial, BT SPP and phone sensors over a Unix domain socket to a small Signal K provider plugin | No `/dev/tty*` without root |
| UI | Native Compose "Dock" for anything that must work when the server is down; the admin UI and webapps in a browser, the phone's own or any device's on the boat's network (§8.2) | Don't reimplement Signal K's UI, or wrap it: it already works in any browser |
| Toolchain | AGP 9.4 built-in Kotlin, Gradle 9.8, JDK 17 toolchain (no auto-download), compileSdk/targetSdk 37, **minSdk 29**, R8 with names kept, Lint warnings-as-errors, dependency verification metadata | Identical to Crew Radio, so its files, CI and release process drop in. nodejs-mobile needs only API 24, so minSdk 29 costs nothing real |
| Distribution | GitHub Releases + F-Droid first (with in-app self-updater); Play Store later | Play's "downloaded executable code" policy is a gray area for plugins |

## 2. Repository layout

The Gradle project sits at the repository root, as in Crew Radio, so its workflows apply unchanged.

```
signalk-server-android/
├── app/                             # the app: UI, service, bridge          (present)
│   └── src/main/java/fi/pocketsk/
│       ├── node/                    # NodeRuntime                           (present)
│       ├── net/                     # Backoff, Threads, NetworkChoice       (present, from Crew Radio)
│       └── signalk/                 # Client, Url, Discovery, Tree          (present, from Crew Radio)
├── node-runtime/                    # AAR: libnode.so + launcher + npm assets  (present)
├── native/                          #                                       (present)
│   ├── launcher/                    # C++: main() -> node::Start()
│   └── CMakeLists.txt
├── runtime-tools/
│   ├── gyp-shim/                    # replaces node-gyp on device
│   └── bootstrap/                   # first-run install scripts (JS)
├── sk-plugin/
│   └── android-bridge/              # @pocketsk/android-bridge (TS)
├── prebuilds/                       # CI matrix for native addons
│   ├── packages.json
│   └── build.sh
├── .github/workflows/               # build.yml, codeql.yml                 (present, from Crew Radio)
├── gradle/, build.gradle.kts, …     # toolchain                             (present, from Crew Radio)
└── docs/
```

## 3. Phase 0 — Risk spikes (do these first, ~1 week)

Each spike is a throwaway Android project. Stop and redesign if one fails.

| # | Spike | Pass criterion |
|---|---|---|
| R1 | Exec `libnodeexec.so` from `nativeLibraryDir` on Android 10, 12, 14, 15, 17 with `targetSdk 37` and `extractNativeLibs=true` | `node -e "console.log(process.version)"` prints 24.x |
| R1b | Android 17 local-network protection (`ACCESS_LOCAL_NETWORK`, see Crew Radio `LocalNetwork.kt`): does a child process inherit the grant, and does Node's `listen(0.0.0.0)` plus mDNS work once granted? | LAN client reaches `:3000`; mDNS advert seen from another phone |
| R2 | `dlopen` of a `.node` addon from `files/` (writable dir) | `require('./prebuilt.node')` works. SELinux blocks `execve` from app data, not `mmap PROT_EXEC` — verify |
| R3 | `npm install signalk-server` using `/system/bin/sh` for lifecycle scripts, `HOME` and `TMPDIR` set to app dirs | Clean install, no scripts needing coreutils that toybox lacks |
| R4 | Boot `signalk-server` with `--sample-nmea0183-data`, open `http://127.0.0.1:3000/admin/` in the browser | Admin UI loads, App Store lists plugins |
| R5 | Foreground service + wake lock survives 12 h screen-off on Samsung, Xiaomi, Pixel | Server still answering, no restarts in log |
| R6 | 16 KB page size devices (Pixel 9+) | Spikes R1–R2 pass; nodejs-mobile is already 16 KB aligned |

Fallback if R1 fails: embed Node via JNI in a `:node` process and patch Signal K's installer to run `@npmcli/arborist` in-process. Keep this path documented but don't build it unless needed.

## 4. Phase 1 — Node runtime module (`node-runtime/`)

### 4.1 Launcher executable

```cpp
// native/launcher/main.cc
#include "node.h"
int main(int argc, char** argv) {
  // nodejs-mobile exposes node::Start; argv[0] is whatever we exec'd
  return node::Start(argc, argv);
}
```

- CMake target `nodeexec`, output name forced to `libnodeexec.so` so Gradle packages it in `lib/<abi>/`. **Done** (`native/`, built by the `node-runtime` module).
- Links against `libnode.so` from the nodejs-mobile release zip (download in Gradle task, verify SHA-256). **Done**: the Node 24 line is published by digidem's fork as `nodejs-mobile-android-<A.B.C-R>.zip` (upstream `nodejs-mobile/nodejs-mobile` stops at 18.20.4, and signalk-server needs Node ≥ 22). It holds `bin/<abi>/libnode.so` for arm64-v8a, armeabi-v7a and x86_64 plus `include/node`; the version and SHA-256 are pinned in `gradle.properties`, the zip is cached in the Gradle user home and recorded in the SBOM. The `full` flavour, not `lite`: lite drops `node:sqlite` and changes V8's pointer compression, and plugins vary too much to bet on either.
- Also package `libc++_shared.so`. **Done** (`ANDROID_STL=c++_shared`). It has to come from the NDK libnode.so was built with, **r27d (27.3.13750724)**, so `ndkVersion` is pinned to it.
- The launcher carries `RUNPATH $ORIGIN`, so it finds `libnode.so` beside it even in a child process whose environment lost `LD_LIBRARY_PATH`. CMake is pinned to 3.31.6: AGP's default 3.22.1 writes that RPATH as a bare `$`.
- **Process groups.** `java.lang.Process` signals only the pid it forked, and Android gives an app no way to find that pid's children, so killing npm left its lifecycle scripts running (R3) and stopping the server would leave a plugin's helpers holding their ports and serial devices. So the node that `NodeRuntime.spawn` starts leads a process group of its own: the launcher reads `POCKETSK_GROUP_FILE`, removes it from the environment (a node that a script runs joins its parent's group instead of leading one), calls `setpgid(0, 0)` and writes its pid, the group's id, to that file under `no_backup/groups/` (not the cache, which Android may clear while a server runs) before Node starts. `NodeProcess` signals the whole group with `kill(-id)` and falls back to the one process when there is no record: not written yet, which means nothing else has started either, or the group could not be made or recorded. A record exists exactly when the node leads a group of its own, so a launcher that cannot write it gives the group up again and still starts Node; a group nobody can name would hide whatever is started in it. The id is a pid and pids are reused, so the group is forgotten once the process has ended and whatever it left running has been killed (`reap`). A script that makes a session or group of its own escapes this, as it would on Linux; Android still ends it with the app. **Done** (§16 step 9.1).
- `extractNativeLibs="true"` comes from `jniLibs.useLegacyPackaging = true` in the app's Gradle build; AGP warns about the attribute in a source manifest, so it is not there.
- APK cost: about 26 MB compressed per ABI (libnode.so is 80–87 MB uncompressed), so the universal release APK is about 80 MB. ABI splits for the release are a §10 decision.

### 4.2 Runtime filesystem (app-private)

```
files/
├── home/                    # $HOME
│   ├── .npmrc               # points node-gyp at the shim, cache dir, prefix
│   └── .signalk/            # SIGNALK_NODE_CONFIG_DIR (settings, plugins config)
├── sk/
│   ├── slots/A/             # npm global prefix: lib/node_modules/signalk-server
│   ├── slots/B/
│   └── current -> slots/A   # symlink; the only thing promotion changes
├── npm/                     # npm-cli.js + node_modules extracted from assets
├── bin/
│   ├── node -> <nativeLibraryDir>/libnodeexec.so   # symlink, for PATH; re-pointed on every start
│   ├── npm  -> <nativeLibraryDir>/libnodeexec.so   # the same executable; runs npm-cli.js when started as npm
│   └── node-gyp             # the shim (§9.2)
└── prebuilds/               # downloaded native addon cache
cache/tmp/                   # $TMPDIR
no_backup/groups/            # one record per spawned node: its process group's id (§4.1)
```

No `bin/npm` wrapper script: an app targeting API 29+ may not `exec()` a file in its own data directory (Android's W^X rule for app data), so a script under `files/` can be run as `sh <file>` but never by name. The app starts npm as `node files/npm/bin/npm-cli.js`. Signal K Server, though, runs `npm` by name: `npm --version` at start (its `/nodeInfo`) and `npm install` in the config directory for every App Store install; with no `npm` on PATH the first logged `npm: inaccessible or not found` on a phone (2026-10-19) and the second would fail every install. So `bin/npm` is a second link to the launcher, which, started under the name `npm`, runs node with `POCKETSK_NPM_CLI` (the unpacked npm-cli.js) in front of its arguments, as npm's wrapper does on a Pi. The same rule covers what npm's lifecycle scripts exec by name — `node_modules/.bin/*` (shebang scripts), and the `node-gyp` shim of §9.2 if it is a script — so finding out how much of that npm and Signal K's plugins need is part of R3 (§15).

### 4.3 `NodeRuntime` Kotlin API

```kotlin
class NodeRuntime(ctx: Context) {
  fun ensureLayout()                          // directories, and files/bin/node and files/bin/npm pointed at this install's launcher
  fun ensureExtracted()                       // ensureLayout + npm unpacked from assets; idempotent, stamped with npm's version and SHA-256
  fun env(): Map<String,String>               // HOME, TMPDIR, PATH, LD_LIBRARY_PATH, SIGNALK_NODE_CONFIG_DIR, NODE_OPTIONS
  fun spawn(vararg args: String, cwd: File, extraEnv: Map<String,String>): NodeProcess   // stderr merged into stdout; leads its own process group (§4.1)
  fun npm(vararg args: String, cwd: File): NodeProcess     // node files/npm/bin/npm-cli.js args
}

class NodeProcess {
  val process: Process
  fun terminate(): Boolean    // SIGTERM to the whole group; false when only the process could be reached
  fun kill(): Boolean         // SIGKILL to the whole group
  fun reap(): Boolean         // once it has ended: SIGKILL what is left of the group, then forget it
  fun drain(timeoutS: Long, onLine: (String) -> Unit): Int?   // read to the end, wait, reap; null = timed out, group killed
}
```

**Done** (in `node-runtime`, package `fi.pocketsk.node`). npm comes from the registry tarball (`npm-<version>.tgz`: the same npm a Node release carries, published with its dependencies bundled, 3 MB rather than the 30 MB Node tarball), the version that ships with the pinned Node (nodejs.org `dist/index.json`), pinned by SHA-256 in `gradle.properties` and listed in the SBOM. The build puts it into the APK as `assets/npm.tgz`; `Tar` (pure Kotlin, unit-tested, including against that real tarball) unpacks it into `files/npm.new` and renames it into place, refusing links and any path that leaves the destination.

Environment:

```
HOME=<files>/home
TMPDIR=<cache>/tmp
PATH=<files>/bin:/system/bin
LD_LIBRARY_PATH=<nativeLibraryDir>
SIGNALK_NODE_CONFIG_DIR=<files>/home/.signalk
NODE_OPTIONS=--max-old-space-size=<RAM-aware>
npm_config_cache=<files>/home/.npm
```

Deliverable: a `node-runtime` AAR usable by any app, published separately. (Side benefit: reusable by other marine Node projects.)

## 5. Phase 2 — Bootstrap installer and A/B upgrades

### 5.1 Install sources

Order of preference, all producing the same slot layout:

1. **npm registry** (default): `npm install -g --prefix <slot> signalk-server@<version> --omit=dev`. Global, as on a Pi, not a local install into the slot: a local install hoists signalk-server's dependencies to `<slot>/node_modules`, and the server looks for `@signalk/server-admin-ui` under its own `node_modules`, so the admin UI answers 500 (seen on desktop, 2026-10-17). Global keeps them nested, in `<slot>/lib/node_modules/signalk-server`.
2. **GitHub release tarball** mirror (if registry unreachable or rate-limited).
3. **Sideload** via Storage Access Framework: user picks a `.tgz` produced by `npm pack` on any machine. Important for boats with no internet at install time.

### 5.2 `InstallManager`

```kotlin
class InstallManager(context: Context, runtime: NodeRuntime) {   // everything blocks; one install or rollback at a time
  sealed interface Source { Registry(version: String?), Tarball(file: File) }
  sealed interface State { Installing(slot, tail), Verifying(slot, tail), Done(version, slot, history), Failed(reason, tail) }
  fun liveSlot(): String?                               // what files/sk/current points at
  fun installedVersion(slot: String): String?
  fun history(): InstallHistory                         // files/sk/install-history.json
  fun listAvailable(withPrereleases: Boolean = false): List<ServerVersion>   // npm view signalk-server versions --json, kept 24 h
  fun install(source: Source, allowBad: Boolean = false, listener: Listener): State
  fun rollback(bad: Boolean, reason: String?): String?  // the other slot made live again
}
```

**Done** (§16 step 9.2), as a blocking API with a listener rather than the first sketch's suspend function and Flow: the app has no coroutines and adds no library for one caller. `InstallJob` runs it on a thread of its own for the screens, one install per process, so a rotation or the wizard's next page sees the same one. There is no `Downloading(pct)`: npm reports no progress, only lines. The pure parts are unit-tested: `Slots` (which slot an install goes to), `ServerVersion` (npm's versions in semver order, betas only when asked), `InstallHistory` (what happened, and which versions the health gate rolled back as bad) and `HealthGate` (step 6's rules, for the supervisor to feed).

Algorithm:

1. Pick the inactive slot; wipe it.
2. Run npm install into it, streaming log lines to UI.
3. **Smoke test**: spawn `node <slot>/lib/node_modules/signalk-server/bin/signalk-server --sample-nmea0183-data` with `PORT=3999` and a temporary config dir copied from the live one (`cp -a .signalk → cache/verify`). signalk-server takes its ports from the environment, not from arguments (`PORT`, `TCPSTREAMPORT` for its 8375 stream, `NMEA0183PORT` for its 10110 feed; 2.33.0's `dist/ports.js` and `dist/interfaces`), so all three move beside the live server's, and the copy's `settings.json` gets `mdns: false` and `ssl: false`: the test server must neither announce a second Signal K server on the boat's network nor move to its HTTPS port. Poll `GET /signalk` until `{"endpoints":...}` or 60 s timeout. Kill its process group (§4.1), so no plugin it started keeps running from the slot about to go live. npm itself runs the same way: an install that runs out of time is killed with every lifecycle script it started, and only then is the slot wiped for the next try.
4. **Promote**: atomically repoint `current` symlink (rename of a temp symlink).
5. Signal the supervisor to restart the server from the new slot.
6. **Health gate**: if the live server fails to answer `GET /signalk` within 90 s of promotion, or crash-loops 3× in 5 min, flip `current` back and mark the version bad (stored in `install-history.json`, shown in UI).

Plugins and webapps are not in slots: Signal K installs them into `.signalk/node_modules`, same as on Linux, so they survive server upgrades. This matches stock behaviour.

### 5.3 First-run wizard (Compose)

1. Welcome → explain what will be downloaded (~90 MB).
2. Choose version (default: latest stable) or sideload.
3. Install with live log.
4. Permissions: notifications, battery optimization exemption, nearby devices (BT), location (for GNSS provider, optional).
5. Open admin UI.

**Done** (§16 step 9.4, `fi.pocketsk.ui.SetupScreen`): welcome with the download size, the newest five releases from `listAvailable` (the latest chosen) or a `.tgz` from the system file picker (copied into the cache, since npm needs a path), the install with its live log, then the permissions (notifications, Android 17's local network access, the battery-optimisation list — the list, not the one-tap request, which needs a permission Play restricts and lint flags — and autostart) and the start. Location is asked for with the bridge's GNSS (§7), not here. The wizard shows until it has been finished once; a phone with a server already live skips it.

## 6. Phase 3 — Foreground service and supervisor

`SignalKService : Service` (`foregroundServiceType="connectedDevice"`; `location` joins with the bridge's GNSS, §7). Not `dataSync`: since Android 15 a dataSync service gets six hours in 24 and may not be started from `BOOT_COMPLETED`, and the server runs for days. connectedDevice's prerequisite on Android 14+ is a connectivity permission, and `CHANGE_WIFI_MULTICAST_STATE` (mDNS) is one.

Start from Crew Radio's `PttService`: keep its startForeground-first-then-slow-work-on-own-thread pattern, `ServiceCompat.startForeground` with explicit types, immediate low-importance notification, lock handling and the 250 ms notification throttle. Replace the engine with the Node child process.

- Holds `PARTIAL_WAKE_LOCK`, the Wi-Fi locks from `WifiLockModes.forSdk()` (LOW_LATENCY + HIGH_PERF where it still exists) and `WifiManager.MulticastLock` (mDNS). Request `ACCESS_LOCAL_NETWORK` on API 37+ before the first start (`LocalNetwork` rules).
- Spawns `node <current>/lib/node_modules/signalk-server/bin/signalk-server`.
- Stop: SIGTERM to the server's process group (§4.1), the server and everything its plugins started; SIGKILL to the group after 10 s if the server is still there. After every exit, a stop, a crash or Signal K's own restart, whatever is left of the group is killed (`NodeProcess.reap`) before the next start, so a plugin's helper never holds a port or a serial device against its next instance.
- Restart policy: exit code 0 → immediate restart (this is Signal K's own "restart" button); non-zero → exponential backoff 2 s → 60 s; after 5 failures in 10 min → stop, notify, offer rollback.
- Captures stdout/stderr into a 2 MB ring buffer + rotating files in `files/logs/`.
- Watches `/signalk` health every 30 s; exposes `StateFlow<ServerState>` to UI.
- Persistent notification: state, version, local URL, quick actions Start/Stop/Restart.
- `START_STICKY`, restarted on boot by `BOOT_COMPLETED` receiver if "autostart" is on.
- Network change receiver: re-acquire multicast lock, log new IP for the notification.

**Done** (§16 step 9.3, `fi.pocketsk.server`), with these details settled:

- `Supervisor` holds the run, `SignalKService` the Android side (foreground, locks, notification, network watch, commands as intents). The run starts from the slot `current` names at that moment, so a promotion takes effect at the next start, which `promoted()` makes at once.
- `RestartPolicy` (pure, tested): an exit 0 within 5 s of the start counts as a failure, or a server that exits 0 straight away would be restarted for ever; a run that lasted 10 min resets the backoff. Exits asked for (Stop, Restart, a failed gate) are not counted.
- The health gate of §5.2 step 6 is fed here: the installer records the gate the version owes (`SignalKService.owesGate`) under its own lock just before `current` moves, so a run can never start it ungated, then tells the service it went live (`SignalKService.promoted`) and a running server moves to it, and `HealthGate` decides; the version owed a gate is kept in the service's preferences, so a gate the process died in runs on the next start. A failed gate rolls back with `InstallManager.rollback(bad = true)` and says so in an alert notification. After 5 failures in 10 min the alert offers Start again and Roll back to the other slot's version.
- Health: `GET /signalk` once a second until the first answer, then every 30 s. A server that stops answering is shown so, not killed: a plugin busy for a minute is not a reason to restart a boat's server. It asks on the HTTP port the live `settings.json` names (`port`, 3000 by default), read before every run, since the admin UI can move it; with `ssl` on, that port answers everything with a redirect to HTTPS (2.33.0's `startRedirectToSsl`), and the redirect is the answer (`ServerEndpoint`, pure, tested), so a server set up for SSL is never rolled back as dead. The notification and the admin link use the same port and let the redirect take the browser on.
- The UI gets a listener (`ServerStatus`, process-wide, so a screen opened after the service has gone still shows why), not a `StateFlow`: no coroutines in the app.
- Log: `LogRing` (2 M characters) and `LogFiles` (`files/logs/server.log` + 3 predecessors of 1 MB), every line stamped; `files/logs` is excluded from backups and device transfer.
- The notification shows the phone's LAN address (`LanAddress`, pure, tested: Wi-Fi, then the phone's own hotspot, then Ethernet, never mobile data). Its actions are Stop and Restart; Start lives on the alert, since a stopped server has no ongoing notification.
- `BootReceiver`: after boot when autostart is on, and after an app update when the server was running.
- The Quick Settings tile came with the Dock (step 9.4): see below.

Quick Settings tile (`TileService`): one tap Start/Stop, shows state. **Done** (§16 step 9.4, `ServerTile`): a stop is a command to the running service; a start is tried as a foreground start, and when Android refuses one from the background the tile opens the Dock with a start request instead, a screen in front being what every release allows. The service asks the tile for a fresh look on every state change.

## 7. Phase 4 — Android bridge (hardware & sensors)

Two halves talking over a Unix domain socket `<files>/bridge.sock` with newline-delimited JSON.

### 7.1 Kotlin side (`BridgeServer`)

Message types (bridge → plugin):

```json
{"t":"devices","list":[{"id":"usb:1a86:7523:1","kind":"usb-serial","name":"CH340","port":10001}]}
{"t":"sensor","path":"navigation.position","value":{"latitude":60.45,"longitude":22.27},"src":"android.gnss"}
{"t":"sensor","path":"navigation.attitude","value":{"roll":0.02,"pitch":-0.01,"yaw":null},"src":"android.imu"}
{"t":"sensor","path":"environment.outside.pressure","value":101325,"src":"android.baro"}
```

Plugin → bridge:

```json
{"t":"alarm","state":"emergency","method":["sound","visual"],"message":"Anchor dragging","path":"notifications.navigation.anchor"}
{"t":"subscribe","sensors":["gnss","imu","baro"],"rateHz":{"imu":5}}
```

- **USB serial**: `usb-serial-for-android` (MIT). Each attached device gets a localhost TCP port; data flows raw. Handles `USB_DEVICE_ATTACHED` intent with a device filter so the app launches on plug-in.
- **Bluetooth SPP**: same, for cheap BT NMEA 0183 senders and AIS receivers. Lift Crew Radio's `BluetoothTransport` dial/listen/redial loops (`dialLoop`, `redial`, `reconcile`, `closeQuietly`) and `Backoff`; drop its `StreamLink` framing, because NMEA is a plain byte stream.
- **Threading**: reuse `transport/Threads.kt` (`transportThread`, `sleepQuietly`, `reporting`, `Waiter`) for every bridge loop and the supervisor.
- **Sensors**: fused GNSS (`FusedLocationProvider` or raw `LocationManager`), rotation vector → roll/pitch (device mounting calibration stored in prefs), barometer, battery as `electrical.batteries.phone.*`, cellular signal as `communication.cellular.*`.
- **Alarms in**: Signal K notifications with `emergency`/`alarm` state → full-screen intent notification with alarm-channel sound, vibration pattern, and a "silence" action that writes back to Signal K. This gives an anchor alarm that wakes a sleeping phone, something no webapp can do.

### 7.2 Plugin side (`@pocketsk/android-bridge`, JavaScript)

Published to npm with Signal K keywords so it also appears in the App Store, but the app auto-installs and auto-enables it on first run.

- Connects to the socket; on `devices`, creates/updates Signal K connections via `app.config` or the admin REST API (`PUT /skServer/providers`), using provider type `tcp` pointing at `127.0.0.1:<port>`, device-type selectable in the Dock (NMEA 0183, Actisense serial for NGT-1, Seatalk).
- Emits sensor deltas with `$source: android.<sensor>`.
- Subscribes to `notifications.*` and forwards state changes to the bridge.
- Plugin config UI (standard JSON schema) exposes rates and which sensors to publish.

## 8. Phase 5 — UI

### 8.1 Dock (native Compose, bottom nav tab 1)

**First screen done** (§16 step 9.4, `fi.pocketsk.ui.DockScreen`): Status (state, version and slot, the LAN and on-phone URLs, Start/Stop/Restart, Open the admin UI), Updates (both slots, Check for updates, Install the newest that is newer and not rolled back as bad — `UpdateOffer`, pure, tested — Roll back to the other slot's version, the newest of the history), Settings (the wizard's permission rows and autostart) and the newest of the log. Not yet: uptime, CPU/RAM and delta rate on the Status card, the changelog, and the Devices, Sensors, Power, Logs (filter, diagnostics bundle) and Backup cards, which come with the steps that give them something to show. The bottom navigation comes when there is a second tab. The spike screen stays reachable from the Dock as "Runtime checks" until §3's spikes have passed on the phones they name.

- **Status card**: running/stopped, version, uptime, URL(s), CPU/RAM of child process, delta rate.
- **Updates**: current slot, available versions, "Install", "Rollback to <prev>", install history with pass/fail. Changelog pulled from GitHub releases.
- **Devices**: USB/BT list, per-device protocol selector, live byte counters, "forget".
- **Sensors**: mounting calibration ("hold phone level, tap": **Level now**, §16 9.5.3); the toggles are the bridge plugin's settings in the admin UI.
- **Power**: profiles *Harbor* (keep everything), *Passage* (dim screen, keep server), *Battery saver* (stop sensors except GNSS at 1 Hz).
- **Logs**: live tail, filter, "Share diagnostics bundle" (logs + settings sans tokens + device info as zip via share sheet).
- **Backup**: export/import `.signalk` as encrypted zip to SAF (works with Drive/Nextcloud).

### 8.2 Server (in a browser)

- No viewer of the app's own (decided 2026-10-18, PR #12). The admin UI and every webapp are used in a browser, as on any Signal K server: `http://127.0.0.1:3000` on the phone itself, where the Dock's "Open admin UI" hands that URL to the default browser, and `http://<phone address>:3000` from any other device on the boat's network. Signal K's UI already works in any browser, uploads (Signal K's own restore) and all, and the app carries no WebView with JavaScript on, so there is no in-app web surface to secure (R4's WebView was CodeQL's one high alert).
- The Dock's status card shows those URLs, one per address the phone has. Reaching the server from another device needs `ACCESS_LOCAL_NETWORK` on Android 17 (`LocalNetwork`), asked for before LAN access is turned on.
- Auth: the admin UI logs in as on any Signal K server, and Signal K security is enabled from it as usual (§11). The app needs a token of its own only for its own API calls (Dock status, the bridge's provider, alarms): Crew Radio's `SignalKClient.requestAccess`/`pollAccess` (Signal K's own access-request flow) and `SignalKUrl` for URL normalisation; the local server can auto-approve its own request through the admin API, so there is no prompt.
- Deep links `pocketsk://webapp/<name>` open that webapp's URL in the browser.

### 8.3 Helm / Kiosk mode (the novel bit)

- Any installed webapp (Freeboard-SK, KIP, Instrument Panel) can be pinned as a **launcher shortcut** (`ShortcutManager`) with its own icon pulled from the webapp's manifest, that opens the webapp in the browser (§8.2). A webapp with a web app manifest can also be installed from the browser as a full-screen app of its own.
- Screen kept on, brightness slider and **red night filter** as a system-level overlay (not just CSS: `TYPE_APPLICATION_OVERLAY`, so they work over the browser and the status bar is red too; the overlay window's keep-screen-on flag holds the screen).
- Holding a tablet at the helm on one webapp is Android's own app pinning of the browser (optional, unpinned with the PIN); the app does not pin another app's task itself.
- Home-screen **widgets**: 2×1 "depth / SOG / wind" cards fed by the local WebSocket, refresh every 2 s while visible.

### 8.4 Satellite mode (later)

When the phone joins a Wi-Fi where another Signal K server is discovered via mDNS (Crew Radio's `SignalKDiscovery`, `_signalk-http._tcp`, already handles the API 34+ resolve callbacks and leak cleanup), offer: *"Boat server found. Use this phone as a satellite?"* In satellite mode the local server runs with a connection to the main server (Signal K-over-WS provider), so phone sensors and USB devices become available to the main server, and the phone's webapps keep working if Wi-Fi drops. Zero config federation.

## 9. Native addon prebuilds (`prebuilds/`)

Problem: no compiler on the phone. Solution: CI builds and a shim.

### 9.1 CI matrix (GitHub Actions, Linux + NDK r27)

`packages.json`:

```json
[
  {"name":"@serialport/bindings-cpp","versions":["12.x","13.x"],"strategy":"prebuildify"},
  {"name":"better-sqlite3","versions":["11.x","12.x"],"strategy":"node-gyp"},
  {"name":"sqlite3","versions":["5.x"],"strategy":"node-gyp"},
  {"name":"bufferutil","versions":["4.x"],"strategy":"prebuildify"},
  {"name":"utf-8-validate","versions":["6.x"],"strategy":"prebuildify"},
  {"name":"@abandonware/noble","versions":["1.9.x"],"strategy":"skip","reason":"needs BlueZ"}
]
```

Build with nodejs-mobile headers, `--arch arm64 --target_platform android`, output `prebuilds/android-arm64/node.napi.node`. Upload as `pocket-sk-prebuilds` GitHub release assets: `<name>@<version>-android-arm64.tar.gz`.

### 9.2 `gyp-shim` (on device, `files/bin/node-gyp`)

A Node script that npm invokes instead of node-gyp when a package has no matching prebuild:

1. Read `package.json` name/version from cwd.
2. Try `files/prebuilds/` cache, then fetch from the release index (`index.json`, cached 24 h).
3. Extract into `build/Release/` and `prebuilds/<platform>-<arch>/` so both `bindings` and `node-gyp-build` resolution succeed.
4. If nothing found: exit non-zero with a readable message. npm marks optional deps as skipped; for hard deps the plugin install fails visibly and the Dock shows *"needs a native build we don't ship yet — request it"* with a one-tap GitHub issue link containing name/version.

Wire it with `.npmrc`: `node_gyp=<files>/bin/node-gyp`.

Because N-API is ABI-stable, prebuilds survive Node runtime bumps.

## 10. App self-update and release pipeline

- Check GitHub Releases API daily (configurable); show in Dock.
- Download APK to `cache/`, verify signature matches installed app (`PackageManager.getPackageArchiveInfo` + signing cert compare), launch `PackageInstaller` session. Standard Obtainium-style flow.
- F-Droid users get updates via F-Droid; detect installer package name and hide self-update there.
- **Pipeline**: copy Crew Radio's `build.yml` whole. Version = `1.<commit count>` from git, shallow-clone guard, keystore from `POCKETSK_*` secrets, split `build` / `release` (read-only token, signs, verifies cert SHA-256, attests) / `publish` (only write token, no checkout, `gh release create`). Add: `nodejs-mobile` zip download pinned by SHA-256 in `verification-metadata`-style, SBOM including `libnode.so` provenance, and the APK's `.sha256` + CycloneDX SBOM as release assets. Add `prebuilds.yml` (§9) and reuse `signalk-ci.yml` for `sk-plugin/android-bridge`.

## 11. Security

- Server binds to all interfaces by default (it's a hub). Dock shows a prominent switch "LAN access", off = `127.0.0.1` only.
- Signal K security can be enabled from the admin UI as usual; app's own admin token stored in `EncryptedSharedPreferences`.
- Unix socket permissions: created in app-private dir, so only our UID.
- No `INTERNET`-facing endpoints added by the app itself.
- Diagnostics bundle redacts tokens, passwords, SSIDs.

## 12. Distribution and policy

1. **GitHub Releases** (signed APK + self-updater) from day one.
2. **F-Droid** via the main repo (requires reproducible build of nodejs-mobile or shipping it as a documented binary blob — check F-Droid's policy; `IzzyOnDroid` as fallback, it accepts prebuilt binaries).
3. **Play Store**: attempt after 1.0. Risks: policy on downloaded executable code (JS plugins — argue interpreter content; native `.node` prebuilds are the hard part). Possible Play-only variant that disables the native-addon shim and ships a curated prebuild set inside the APK.

## 13. Milestones

| Milestone | Scope | Exit criteria |
|---|---|---|
| M0 Spikes | §3 | All R1–R6 green on 3 devices |
| M1 Runtime | §4 | `node-runtime` AAR, `node -v` from a test app |
| M2 Installer | §5 | Fresh phone → running Signal K from npm in <5 min on Wi-Fi; sideload works offline |
| M3 Service | §6 | 48 h soak with sample data, no restarts; App Store install + restart loop works |
| M4 Bridge | §7 | USB CH340 NMEA 0183 → data in Signal K; phone GNSS shows in Freeboard; anchor alarm wakes a locked phone |
| M5 UI | §8.1–8.3 | Dock complete, kiosk mode, 1 widget |
| M6 Prebuilds | §9 | serialport-based plugins and `signalk-to-influxdb`-class plugins with sqlite install from the App Store |
| 1.0 | §10–12 | GitHub release, F-Droid submission, docs |
| 1.x | §8.4 | Satellite mode |

Rough effort for one experienced developer: M0–M3 ≈ 3–4 weeks, M4–M5 ≈ 4 weeks, M6 ≈ 1–2 weeks plus ongoing.

## 14. Upstream contributions worth making early

- `signalk-server`: make the module installer pluggable (`app.config.settings.moduleInstaller`) and expose `process.execPath` override — reduces our patch surface to zero even if R1 ever regresses.
- `node-gyp-build` / `prebuildify`: document `android-arm64` target so plugin authors can publish Android prebuilds themselves.
- `nodejs-mobile`: contribute the launcher executable as an official artifact (`node` CLI for Android).

## 15. Open questions to resolve during M0

- Does `/system/bin/sh` (toybox) break any npm lifecycle scripts in the top-50 Signal K plugins? Script a crawl of the registry by keyword and grep `scripts.install/postinstall`.
- R3's expected result, from the same install on desktop Linux (Node 22, signalk-server 2.33.0, 2026-10-17): 758 packages, six with install scripts. Four run `node …` (core-js, es5-ext, storage-engine, @scarf/scarf), which should work, `node` being the symlink into `nativeLibraryDir`. `@canboat/canboatjs` runs `node-gyp rebuild || echo …`, harmless if node-gyp cannot be exec'd. `@serialport/bindings-cpp` runs `node-gyp-build`, i.e. `node_modules/.bin/node-gyp-build`, a shebang script in app storage: the one W^X is expected to break, and a failed install script fails the install. If the phone shows that, the candidates are `script-shell` pointed at a launcher in `nativeLibraryDir` that runs shebang scripts as `node <script>`, or `--ignore-scripts` with §9's prebuilds put in place by the installer itself.
- The same crawl answers the W^X question of §4.2: which of those scripts exec something by name from the app's data directory (`node_modules/.bin/*`, `node-gyp`, `prebuild-install`)? If many do, npm's `script-shell` can point at a small launcher in `nativeLibraryDir` that runs a shebang script as `node <script>`.
- Memory ceiling on 3–4 GB phones with Freeboard + 10 plugins; choose `--max-old-space-size` policy.
- F-Droid reproducibility of nodejs-mobile; if not feasible, IzzyOnDroid.

## 16. First tasks for the coding agent

1. ~~Create the Gradle project from Crew Radio's skeleton~~ **Done**: root build files, wrapper, verification metadata, `app/` with the reusable Kotlin ported (§17.1), the R1 spike `MainActivity`, `NodeRuntime`, workflows and `CLAUDE.md`. Not yet built on a machine with an SDK: the first CI run is the first build. Compose is added together with the Dock (§8), so the verification metadata changes once, not twice.
2. ~~Gradle task in `node-runtime` to download `nodejs-mobile-android-24.x.y-z.zip`, verify checksum, unpack `libnode.so` + headers~~ **Done**: unpacked into the module's build directory (not `src/`), and CMake links and packages them (§4.1).
3. ~~`native/launcher/main.cc` + CMake → `libnodeexec.so`~~ **Done**. With it, the `MainActivity` button of step 5 runs a real `node -v`.
4. ~~`NodeRuntime.kt` (§4.3) with asset extraction of `npm` (vendored from the matching Node release tarball into `assets/npm.tar`)~~ **Done**, from the registry tarball instead (§4.3). The spike screen's button runs `npm -v` after `node -v`.
5. ~~Spike activity~~ `MainActivity` already does this: it shows `nativeLibraryDir` and the environment, and runs `node -v` once `libnodeexec.so` is in the APK. **This is R1.**
6. ~~Add `.node` test addon (hello-world N-API built in CI) to `assets/`, copy to `files/`, `require` it~~ **Done**, built by the app's own CMake rather than in a separate CI job (`native/spike-addon`): it is packaged in `lib/<abi>/`, and the spike screen copies it to `files/prebuilds/spike/` and requires it from there. **R2.** It is built twice, because R2 also settles how §9's prebuilds must be linked: `spike_napi` as prebuildify/node-gyp build for Linux (no link to Node, `napi_*` left undefined for the loading process to supply) and `spike_napi_linked` as nodejs-mobile's own addon test builds (`-lnode`). If only the linked one loads, §9 links every prebuild against `libnode.so`.
7. ~~Button: `npm install --prefix files/sk/slots/A signalk-server`, stream log~~ **Done** (`SpikeInstall`): the spike screen's second button wipes slot A, runs that install as a global one (`-g --prefix`, §5.1) with `--omit=dev --foreground-scripts`, streams the tail onto the screen, writes the whole log to `files/logs/spike-r3.log`, and on success prints the installed version, the package count and the packages with install scripts or a `binding.gyp`. **R3.**
8. ~~Button: start server, open WebView~~ **Done** (`SpikeServer`): the third button starts `node <slot A>/lib/node_modules/signalk-server/bin/signalk-server --sample-nmea0183-data` with NodeRuntime's environment, streams its output (whole log in `files/logs/spike-r4.log`), polls `GET /signalk` once a second for up to 90 s, opens `http://127.0.0.1:3000/admin/` in the phone's browser when it answers, and stops the server on the next tap. A first version showed it in an in-app WebView; that was dropped (§8.2): the admin UI works as it is in a browser, and a WebView with JavaScript on was a surface to secure for nothing. On desktop the same start answered in 5 s, with the admin UI and sample navigation data. **R4.** Step 9.3 replaced the button with the service's (`SpikeServer` is gone).
9. Only then: `SignalKService`, `InstallManager`, wizard, bridge, per §5–§8. Too much for one pull request, so in these parts, one each:
   1. ~~Process groups~~ **Done** (`NodeProcess`, the launcher's `POCKETSK_GROUP_FILE`, §4.1): every spawn leads a process group of its own, and the spike screen's npm, summary and server are stopped and reaped with theirs. The R4 server's Start stays off until its group is reaped, and Stop kills the group 10 s after SIGTERM, as §6 will. R3's force-stop guard is left only for a phone where the group cannot be made.
   2. ~~`InstallManager` (§5.1–5.2)~~ **Done** (`fi.pocketsk.install`): slots, the `current` link, the smoke test on port 3999, promotion, rollback and `install-history.json`, from the registry or a sideloaded `.tgz`; the health gate's rules as `HealthGate`, for the supervisor of part 3 to feed. The spike screen's third button runs it (the latest release into the slot that is not live), so it can be tried on a phone before the wizard exists. The GitHub mirror (§5.1, source 2) is not in yet.
   3. ~~`SignalKService` (§6)~~ **Done** (`fi.pocketsk.server`): the supervisor, locks, notification, restart policy, log ring and health watch, the health gate fed and rolling back, boot and update restarts. The spike screen's fourth button starts and stops it in place of R4's, the fifth opens the admin UI, and the installer's button moves a running server to the new version. The Quick Settings tile moves to part 4.
   4. ~~The first-run wizard and the Dock's first screen (§5.3, §8.1), with Compose~~ **Done** (`fi.pocketsk.ui`, `MainActivity`): Compose (BOM 2026.09.00, Material 3, activity-compose) joins the build; the wizard, the Dock's first screen and the Quick Settings tile (§6). The spike screen is not removed but moved behind the Dock's "Runtime checks", since the spikes of §3 have still to pass on real phones; it goes when they have.
   5. The bridge (§7), in parts. The owner chose to start it before M3's soak (§13) rather than after; runtime and service fixes the phones find are carried into it.
      1. ~~The socket, the plugin and the first sensors~~ **Done** (`fi.pocketsk.bridge`, `sk-plugin/android-bridge`): `BridgeServer` serves `files/bridge.sock` (a filesystem Unix socket, so only the app's own user reaches it, and each peer's uid is checked), one peer at a time, NDJSON lines of at most 64 KiB, a bounded send queue that drops the oldest; `PhoneSensors` reports the battery (`electrical.batteries.phone.capacity.stateOfCharge`, `.voltage`, `.temperature`, on change, at most every 10 s and at least every 5 min) and the barometer (`environment.outside.pressure`, averaged per subscribed period) while the plugin subscribes to them; `BridgeProtocol` and the plugin's `lib/protocol.js` are the two halves of one protocol (hello, sensor, subscribe). Decisions: the plugin is **plain JavaScript with no dependencies**, not TypeScript, so the APK can carry it as a tar and `BridgePlugin` can put it in place before every start with no build step, no npm and no network; it lives in `files/plugins/android-bridge`, is symlinked into `.signalk/node_modules/@pocketsk/android-bridge` and declared as a `file:` dependency in `.signalk/package.json`, so an App Store install (an `npm install` there) keeps it instead of pruning it; its settings file is written enabled only when there is none. The server finds the socket through `POCKETSK_BRIDGE`.
      2. ~~GNSS~~ **Done**: the platform's GPS provider through AndroidX's `LocationManagerCompat` (not Play services' fused provider, a third-party library), at the subscribed rate (1 Hz by default, 0.1–5 Hz), as `navigation.position` (altitude when known), `navigation.speedOverGround` and `navigation.courseOverGroundTrue` under `android.gnss`. Precise location is asked for on its own in the Dock and the wizard, not before every start, since the server runs without it; once Android stops showing the dialog, the same button opens the app's settings page. A fix without speed or course sets that path to null. Decision (the owner's): **while-in-use location only**, no background location, so there is no second grant in the system settings and no Play background-location review. The service takes the `location` foreground type when the permission is granted, and renews it whenever a screen of the app comes to the front, so a server started from the screen keeps its fixes with the screen off, and one started at boot gains them the first time the app is opened (the GPS request is then made afresh).
      3. ~~Attitude and the phone's other sensors~~ **Done**: `navigation.attitude` (roll, pitch) and `navigation.rateOfTurn` under `android.imu`, `navigation.headingMagnetic` under `android.compass`, the cabin's `environment.inside.temperature`, `.relativeHumidity` and `.illuminance` under `android.environment` (those the phone has), and `navigation.gnss.satellites` with each fix. `Attitude` (pure, tested) turns the rotation sensor into the boat's frame: the mounting is down as measured by **Level now** in the Dock's Sensors card (kept in the app's `sensors` preferences, since it belongs to the phone where it is mounted), forward the phone's top laid level, or its back when the phone stands upright, turned by the plugin's bow offset; never levelled, the phone is taken to lie flat, screen up, top to the bow. Rate of turn is the gyroscope taken into the world frame, so it needs no mounting. Decisions: the attitude uses the **game rotation vector** (no magnetometer, so steel and wiring beside the phone do not throw roll and pitch) and switches to the rotation vector only for the heading, which is **off by default** and has a source of its own so a server ranks it below a real compass; the toggles, periods and bow offset are the plugin's settings (admin UI), the level is the app's. Accelerometer heave and cellular signal (`communication.cellular.*`, §8.1) are not in; the cellular reading is left for its own part.
      4. USB serial (`usb-serial-for-android`, a TCP port per device, providers through the plugin).
      5. Bluetooth SPP.
      6. Alarms in: notifications to a full-screen alarm that wakes the phone, and the silence action.

## 17. Reuse from Crew Radio

Crew Radio (`app/src/main/java/fi/crewradio`, ~13.7 k lines, EUPL-1.2, pure Kotlin on AndroidX with no third-party libraries) already solved most of the "keep a network thing alive on an Android phone" problems. Copy, don't depend: the two apps should not share a library yet. Keep the file-level doc comments; they are the reasoning.

### 17.1 Copy as-is (rename package only)

| File | Lines | Use in Pocket SK |
|---|---|---|
| `WifiLockModes.kt` | 20 | Which Wi-Fi lock modes to hold per SDK (HIGH_PERF skipped on 14+) |
| `LocalNetwork.kt` | 56 | Android 17 `ACCESS_LOCAL_NETWORK` decision logic; `forServer()` applies directly to LAN clients and mDNS |
| `LegacyPlatform.kt` | 66 | Deprecated-API shims kept in one place (NSD resolve, etc.) |
| `transport/Backoff.kt` | 24 | Supervisor restart delays, BT redial, npm retry |
| `transport/Threads.kt` | 93 | `transportThread`, `sleepQuietly`, `reporting`, `Waiter` for all bridge and supervisor loops |
| `ask/SignalKDiscovery.kt` | 158 | mDNS discovery of other Signal K servers (satellite mode, §8.4); already handles API 34 resolve callbacks and the leak on silent servers |
| `ask/SignalKUrl.kt` | 97 | URL normalisation, `resolve(base, href)` hardening, endpoint builders |
| `ask/SignalKClient.kt` | 236 | Blocking `HttpURLConnection` client with bounded reads, `requestAccess`/`pollAccess` device flow, typed failures. Use for health polling, token bootstrap, provider registration |
| `ask/SignalKTree.kt` | 147 | Walks a Signal K full-model branch, picks a leaf, respects staleness. Feeds the home-screen widgets (§8.3) |
| `res/xml/data_extraction_rules.xml`, `backup_rules.xml` | | Exclude tokens and `.signalk/security.json` from cloud backup the way Crew Radio excludes the channel key |

### 17.2 Adapt (take the structure, change the body)

| Source | Becomes | What changes |
|---|---|---|
| `PttService.kt` (685) | `SignalKService.kt` | Engine → `ProcessBuilder` child; keep `connect()`'s startForeground-first pattern, `foregroundTypes()`, `buildNotification()` with actions, `createChannel()`, `acquireLocks()`/`releaseLocks()`, notification throttling. Drop audio, headset, telecom, proximity |
| `transport/BluetoothTransport.kt` (382) | `bridge/BluetoothSerial.kt` | Keep listen/dial/redial/reconcile loops and `closeQuietly`; replace `StreamLink` packet framing with raw byte pump to a localhost TCP socket; SPP UUID `00001101-…` |
| `transport/StreamLink.kt` + `SendQueue.kt` | `bridge/BytePump.kt` | Non-blocking offer/send-loop/read-loop shape, without the uint16 framing |
| `Prefs.kt` + `SettingsRules.kt` (402) | `Prefs.kt` | Keep the pure `SettingsRules` validators (`validPort`, `parseHostPort`), the managed-configuration (`RestrictionsManager`) overlay and `isManaged()` greying. Add `app_restrictions.xml` entries: pinned Signal K version, LAN access on/off, autostart, kiosk webapp. Gives fleet/charter operators central config for free |
| `StatusActivity.kt` | Dock › Logs | Last-N status lines with timestamps, packet-style counters become delta/s, restarts, bytes per device, "Check for updates" row |
| `MainActivity.kt` permission flow | First-run wizard (§5.3) | Ask-when-needed pattern, "App settings" fallback row when a permission is permanently denied |
| `ask/AskSheet.kt`, `AskVoice.kt`, `AskRecognizer.kt` | Optional **Ask boat data** in Pocket SK | Same feature against `localhost`; no pairing needed because the server is local. Cheap win once the bridge exists |
| `.github/workflows/build.yml`, `codeql.yml`, `signalk-ci.yml` | same names | See §10. all three are in; `signalk-ci.yml` runs Signal K's plugin CI on `sk-plugin/android-bridge` |
| `app/build.gradle.kts` | same | Git-derived version, signing guard, SBOM task, lint gates. Add `node-runtime` download task and `extractNativeLibs=true` |
| `docs/SECURITY.md`, `docs/cra/*` | `docs/` | Threat model and CRA mapping templates; Pocket SK is a network-facing hub, so Annex I mapping matters more here, not less |
| `CLAUDE.md` | `CLAUDE.md` | Same structure: stack, architecture diagram, threading rules, how to test on devices |

### 17.3 Deliberately not reused

- Audio stack, Opus, mixer, telecom integration: no voice in Pocket SK.
- `WifiAwareTransport`, `LanTransport` multicast mesh, `rns/*` Reticulum: Signal K speaks TCP/HTTP/WS over ordinary Wi-Fi. (Reticulum as a Signal K transport for a phone ashore is an interesting later experiment, but it belongs in a plugin.)
- `ChannelCrypto`, `Packet`, `SeqTracker`, `RateLimiter`: protocol-specific.

### 17.4 Where the two apps meet

- Pocket SK can host `signalk-crewradio` like any plugin, so a phone running Pocket SK becomes the "boat voice" on the Crew Radio channel with no Pi aboard. Test this in M3; it is the best end-to-end demo of both projects.
- The Android bridge's alarm path (§7.1) and `signalk-crewradio`'s announcement path are the same event; document which wins when both apps are on the same phone (suggest: Crew Radio speaks it, Pocket SK shows the full-screen alarm, one "silence" action clears both through the Signal K notification state).
- Keep `SignalKClient`, `SignalKUrl`, `SignalKDiscovery`, `SignalKTree` byte-identical in both repos for now; when they diverge for the third time, extract `fi.signalk.android` as a small MIT/EUPL library both depend on.
