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
│   ├── preload/                     # mdns-lookup.js, node-ble.js: loaded by every node (§4.3, §16 9.5.8)  (present)
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
│   └── .signalk/            # SIGNALK_NODE_CONFIG_DIR (settings, plugins config); its data folders may be links to a card (§16 9.5.9)
├── sk/
│   ├── slots/A/             # npm global prefix: lib/node_modules/signalk-server
│   ├── slots/B/
│   └── current -> slots/A   # symlink; the only thing promotion changes
├── npm/                     # npm-cli.js + node_modules extracted from assets
├── bin/
│   ├── node -> <nativeLibraryDir>/libnodeexec.so   # symlink, for PATH; re-pointed on every start
│   ├── npm  -> <nativeLibraryDir>/libnodeexec.so   # the same executable; runs npm-cli.js when started as npm
│   └── node-gyp             # the shim (§9.2)
├── runtime/                 # mdns-lookup.js, node-ble.js: the preloads every node loads (§4.3), from the APK's assets
└── prebuilds/               # downloaded native addon cache
cache/tmp/                   # $TMPDIR
no_backup/groups/            # one record per spawned node: its process group's id (§4.1)
```

No `bin/npm` wrapper script: an app targeting API 29+ may not `exec()` a file in its own data directory (Android's W^X rule for app data), so a script under `files/` can be run as `sh <file>` but never by name. The app starts npm as `node files/npm/bin/npm-cli.js`. Signal K Server, though, runs `npm` by name: `npm --version` at start (its `/nodeInfo`) and `npm install` in the config directory for every App Store install; with no `npm` on PATH the first logged `npm: inaccessible or not found` on a phone (2026-10-19) and the second would fail every install. So `bin/npm` is a second link to the launcher, which, started under the name `npm`, runs node with `POCKETSK_NPM_CLI` (the unpacked npm-cli.js) in front of its arguments, as npm's wrapper does on a Pi. Checked on a phone (2026-10-19, after §16's npm fix): the log no longer shows the error and App Store installs work. The same rule covers what npm's lifecycle scripts exec by name — `node_modules/.bin/*` (shebang scripts), and the `node-gyp` shim of §9.2 if it is a script — so finding out how much of that npm and Signal K's plugins need is part of R3 (§15).

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
NODE_OPTIONS=--max-old-space-size=<RAM-aware> --require="<files>/runtime/mdns-lookup.js" --require="<files>/runtime/node-ble.js"
npm_config_cache=<files>/home/.npm
```

**`.local` names** (2026-10-23). Android gives native code no mDNS resolver: Node's `getaddrinfo` of `boat.local` goes to the network's unicast DNS and fails. Signal K connects by such names all the time. A server its mDNS discovery finds is saved as a Signal K connection to `<host>.local.` (2.33's `discovery.js` keeps the address it saw beside the name, and `mdns-ws.js` connects by the name), and a name typed into a data connection is often one too. Reported from a phone on Android 17: `Connection error: getaddrinfo ENOTFOUND jetson-xavier-nx-devkit.local.`, while the same server given by its address worked. On a Pi, Avahi's nss-mdns answers these names. Here `runtime-tools/preload/mdns-lookup.js` does: an asset of `node-runtime`, written to `files/runtime` by `ensureLayout` and loaded by every node through `NODE_OPTIONS=--require`, before signalk-server or npm runs. It wraps `dns.lookup` (which `net`, `http`, `ws` and `fetch` all resolve through) and its promise forms. A name ending in `.local` is asked for over mDNS: a one-shot query for A and AAAA from a port of its own, sent to 224.0.0.251:5353 on every IPv4 interface, answered directly to that port (RFC 6762 §6.7), so nothing needs 5353, which the server's own advertising holds. An answer must echo the query's random id. Answers are collected until both families are in or 150 ms after the first, since a responder may answer the two questions separately. They are kept for their TTL, at most 60 s, and given in the order the caller asks for (`order`, `verbatim`, else Node's default result order), as Node's own lookup would. A name nobody answers within two tries of 1 s, and every other name, goes to Node's own lookup as before. Plain CommonJS with no dependencies, unit-tested with `node --test` in CI (`runtime-tools/test`). It never throws while loading, since a preload that failed would stop every node. Checked off the phone: a Signal K server started with it reached another by `testboat.local.` over a WebSocket connection and an NMEA 0183 TCP connection, answered by a responder on 224.0.0.251:5353. Without it, the same connection brought nothing. **To check on a device:** a discovered Signal K server connects; on Android 17 this needs the local network access the server asks for already (§6).

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

Quick Settings tile (`TileService`): one tap Start/Stop, shows state. **Done** (§16 step 9.4, `ServerTile`): a stop is a command to the running service; with precise location allowed a start opens the Dock with a start request, so the server takes the location type (§16 9.5.2); without it a start is tried as a foreground start, and when Android refuses one from the background the tile opens the Dock with a start request instead, a screen in front being what every release allows. The service asks the tile for a fresh look on every state change.

## 7. Phase 4 — Android bridge (hardware & sensors)

Two halves talking over a Unix domain socket `<files>/bridge.sock` with newline-delimited JSON.

### 7.1 Kotlin side (`BridgeServer`)

Message types (bridge → plugin):

```json
{"t":"devices","list":[{"id":"usb-1a86-7523","kind":"usb","name":"USB Serial","baud":4800,"state":"open"}]}
{"t":"serial","id":"usb-1a86-7523","data":"JEdQUk1DLC4uLg0K"}
{"t":"sensor","path":"navigation.position","value":{"latitude":60.45,"longitude":22.27},"src":"android.gnss"}
{"t":"sensor","path":"navigation.attitude","value":{"roll":0.02,"pitch":-0.01,"yaw":null},"src":"android.imu"}
{"t":"sensor","path":"environment.outside.pressure","value":101325,"src":"android.baro"}
{"t":"silence","id":"0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f"}
{"t":"adv","mac":"CB:B8:33:4C:88:4F","rssi":-67,"data":"AgEGG/+ZBAUS/FOUw3wABP/8BAysNkIAzcu4M0yITw==","conn":true,"ts":1760000000000}
```

Plugin → bridge:

```json
{"t":"alarms","list":[{"id":"0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f","path":"notifications.navigation.anchor","state":"alarm","message":"Anchor dragging","sound":true,"action":"silence"}]}
{"t":"subscribe","sensors":["gnss","imu","baro"],"rateHz":{"imu":5},"serial":{"usb":true,"baud":4800,"devices":{"usb-0403-6001-a10k1b2c":38400}},"ble":{"scan":true,"watch":["CB:B8:33:4C:88:4F"]}}
{"t":"stats","deltaRate":12.4}
```

- **USB serial**: `usb-serial-for-android` (MIT). Each attached device's bytes go to the plugin raw, base64 in `serial` lines over the bridge socket, not to a localhost TCP port (§16 9.5.4 says why). Handles `USB_DEVICE_ATTACHED` intent with a device filter so the app launches on plug-in.
- **Bluetooth SPP**: same, for cheap BT NMEA 0183 senders and AIS receivers. Lift Crew Radio's `BluetoothTransport` dial/listen/redial loops (`dialLoop`, `redial`, `reconcile`, `closeQuietly`) and `Backoff`; drop its `StreamLink` framing, because NMEA is a plain byte stream.
- **Bluetooth LE**: the phone's scanner, for the server's Bluetooth LE API and for BlueZ plugins through a node-ble stand-in (**done**, §16 9.5.8); no connections (GATT).
- **Threading**: reuse `transport/Threads.kt` (`transportThread`, `sleepQuietly`, `reporting`, `Waiter`) for every bridge loop and the supervisor.
- **Sensors**: fused GNSS (`FusedLocationProvider` or raw `LocationManager`), rotation vector → roll/pitch (device mounting calibration stored in prefs), barometer, battery as `electrical.batteries.phone.*`, cellular signal as `communication.cellular.*` (**done**, §16 9.5.7).
- **Alarms in**: Signal K notifications with `emergency`/`alarm` state → full-screen intent notification with alarm-channel sound, vibration pattern, and a "silence" action that writes back to Signal K. This gives an anchor alarm that wakes a sleeping phone, something no webapp can do. **Done** (§16 9.5.6): the plugin sends the whole list (`alarms`) whenever it changes, and the app's `silence` goes back by id.

### 7.2 Plugin side (`@pocketsk/android-bridge`, JavaScript)

Published to npm with Signal K keywords so it also appears in the App Store, but the app auto-installs and auto-enables it on first run.

- Connects to the socket; parses each serial device's NMEA 0183 itself with the server's own parser (`@signalk/nmea0183-signalk`) under `$source: android.<device id>.<talker>`, and gives each sentence to the server's `nmea0183` event as a data connection would (§16 9.5.4). An NGT-1 (Actisense binary NMEA 2000) and Seatalk would come the same way, with canboatjs from the server's dependencies.
- Emits sensor deltas with `$source: android.<sensor>`.
- Subscribes to `notifications.*` and forwards state changes to the bridge (`lib/alarms.js`), and silences them through the server's notification API.
- Plugin config UI (standard JSON schema) exposes rates and which sensors to publish.

## 8. Phase 5 — UI

### 8.1 Dock (native Compose, bottom nav tab 1)

**First screen done** (§16 step 9.4, `fi.pocketsk.ui.DockScreen`): Status (state, version and slot, the LAN and on-phone URLs, Start/Stop/Restart, Open the admin UI), Updates (both slots, Check for updates, Install the newest that is newer and not rolled back as bad — `UpdateOffer`, pure, tested — Roll back to the other slot's version, the newest of the history), Settings (the wizard's permission rows and autostart) and the newest of the log. Uptime, CPU and memory on the Status card are in (§16 10.1), the delta rate (10.2), the Updates card's changelog (10.3), the Logs card's filter and diagnostics bundle (10.4), and the Webapps card with its home-screen shortcuts (10.5, §8.3). The Backup card is in (10.8), and the Power card (10.9). The bottom navigation comes when there is a second tab. The spike screen stays reachable from the Dock as "Runtime checks" until §3's spikes have passed on the phones they name.

- **Status card**: running/stopped, version, uptime, URL(s), CPU/RAM of child process, delta rate.
- **Updates**: current slot, available versions, "Install", "Rollback to <prev>", install history with pass/fail. Changelog pulled from GitHub releases.
- **Devices**: USB/BT list, per-device protocol selector, live byte counters, "forget". **USB devices card done** (§16 9.5.4): each device's state, baud rate and bytes read, and Allow for one waiting for permission; the baud rates are the plugin's settings.
- **Sensors**: mounting calibration ("hold phone level, tap": **Level now**, §16 9.5.3); the toggles are the bridge plugin's settings in the admin UI.
- **Power**: profiles *Harbor* (keep everything), *Passage* (dim screen, keep server), *Battery saver* (stop sensors except GNSS at 1 Hz). **Done** (§16 10.9).
- **Logs**: live tail, filter, "Share diagnostics bundle" (logs + settings sans tokens + device info as zip via share sheet). **Done** (§16 10.4).
- **Backup**: export/import `.signalk` as encrypted zip to SAF (works with Drive/Nextcloud). **Done** (§16 10.8): the settings files only, in a WinZip AES-256 zip, as the owner chose (2026-10-26).

### 8.2 Server (in a browser)

- No viewer of the app's own (decided 2026-10-18, PR #12). The admin UI and every webapp are used in a browser, as on any Signal K server: `http://127.0.0.1:3000` on the phone itself, where the Dock's "Open admin UI" hands that URL to the default browser, and `http://<phone address>:3000` from any other device on the boat's network. Signal K's UI already works in any browser, uploads (Signal K's own restore) and all, and the app carries no WebView with JavaScript on, so there is no in-app web surface to secure (R4's WebView was CodeQL's one high alert).
- The Dock's status card shows those URLs, one per address the phone has. Reaching the server from another device needs `ACCESS_LOCAL_NETWORK` on Android 17 (`LocalNetwork`), asked for before LAN access is turned on.
- Auth: the admin UI logs in as on any Signal K server, and Signal K security is enabled from it as usual (§11). The app needs a token of its own only for its own API calls (Dock status, the bridge's provider, alarms): Crew Radio's `SignalKClient.requestAccess`/`pollAccess` (Signal K's own access-request flow) and `SignalKUrl` for URL normalisation; the local server can auto-approve its own request through the admin API, so there is no prompt.
- Deep links `pocketsk://webapp/<name>` open that webapp's URL in the browser. **Done** (§16 10.5).

### 8.3 Helm / Kiosk mode (the novel bit)

- Any installed webapp (Freeboard-SK, KIP, Instrument Panel) can be pinned as a **launcher shortcut** (`ShortcutManager`) with its own icon pulled from the webapp's manifest, that opens the webapp in the browser (§8.2). A webapp with a web app manifest can also be installed from the browser as a full-screen app of its own. **Done** (§16 10.5), with the icon from the package's `signalk.appIcon`.
- Screen kept on, brightness slider and **red night filter** as a system-level overlay (not just CSS: `TYPE_APPLICATION_OVERLAY`, so they work over the browser; the overlay window's keep-screen-on flag holds the screen). **Done** (§16 10.7). The status bar is not tinted: Android draws an app's overlay under the status and navigation bars.
- Holding a tablet at the helm on one webapp is Android's own app pinning of the browser (optional, unpinned with the PIN); the app does not pin another app's task itself.
- Home-screen **widgets**: 2×1 "depth / SOG / wind" cards, refreshed every 2 s while visible. **Done** (§16 10.6), fed through the bridge rather than the WebSocket.

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

**As built** (§16 step 11.1): `prebuilds/packages.json` lists the packages as above, with `keep` (the newest two versions of each range are built) and `skip` in place of `strategy`: every package here builds with node-gyp, prebuildify's included, since prebuildify is node-gyp underneath. `prebuilds/build.js` builds each version with `npm pack`, `npm install --ignore-scripts` for its dependencies, and node-gyp 13.1.0 driven by the NDK's clang for arm64 at API 29, against Node's headers for the version nodejs-mobile's headers name, and linked against nodejs-mobile's `libnode.so` with 16 KB pages allowed (R6). gyp's `OS` stays `linux`: the packages' binding.gyp files know Linux, whose sources build against bionic, and none knows Android. A node-pre-gyp package (sqlite3 5.1.6) is given the variables its binding.gyp takes from package.json's `binary` (`module_name`, `module_path`, `napi_build_version`), as node-pre-gyp would. Each asset holds `build/Release/<target>.node`, stripped, where `bindings` looks; the shim also puts it under `prebuilds/android-arm64/` for `node-gyp-build`. The assets go to one Release tagged `prebuilds`, never marked latest, with `index.json` (format 1: package, version, platform, arch, asset, SHA-256, size, files). A version in the index is not built again. Only `android-arm64` for now: the phones are arm64, and the 32-bit ABI waits until a phone needs it.

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
- The Dock's backup holds `security.json` and the server's secret key, so it is encrypted (AES-256, WinZip's format) with a passphrase typed each time and stored nowhere (§16 10.8).

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
- `@signalk/streams`: connect a discovered Signal K server by the `address` its mDNS record gave (`mdns-ws.js` keeps it but dials the `.local` name), so a host without an mDNS resolver (Android, many containers) reaches it without §4.3's preload.

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
      2. ~~GNSS~~ **Done**: the platform's GPS provider through AndroidX's `LocationManagerCompat` (not Play services' fused provider, a third-party library), at the subscribed rate (1 Hz by default, 0.1–5 Hz), as `navigation.position` (altitude when known), `navigation.speedOverGround` and `navigation.courseOverGroundTrue` under `android.gnss`. Precise location is asked for on its own in the Dock and the wizard, not before every start, since the server runs without it; once Android stops showing the dialog, the same button opens the app's settings page. A fix without speed or course sets that path to null. Decision (the owner's): **while-in-use location only**, no background location, so there is no second grant in the system settings and no Play background-location review. The service takes the `location` foreground type when the permission is granted, and tries to renew it whenever a screen of the app comes to the front (the GPS request is then made afresh), so a server started from the screen keeps its fixes with the screen off. Found on the phones (1.73): a server started from the Quick Settings tile is refused the type (while-in-use access needs the app in front), and the renewal did not bring it back; only a Stop and a Start from the Dock did. So the tile opens the Dock to start the server whenever precise location is allowed, and a server that runs without the type all the same (started at boot, or renewal refused) has the Dock's Sensors card say so, with **Start again with GPS**, a stop and a start from the screen (`ServerStatus.locationHeld`).
      3. ~~Attitude and the phone's other sensors~~ **Done**: `navigation.attitude` (roll, pitch) and `navigation.rateOfTurn` under `android.imu`, `navigation.headingMagnetic` under `android.compass`, the cabin's `environment.inside.temperature`, `.relativeHumidity` and `.illuminance` under `android.environment` (those the phone has), and `navigation.gnss.satellites` with each fix. `Attitude` (pure, tested) turns the rotation sensor into the boat's frame: the mounting is down as measured by **Level now** in the Dock's Sensors card (kept in the app's `sensors` preferences, since it belongs to the phone where it is mounted), forward the phone's top laid level, or its back when the phone stands upright, turned by the plugin's bow offset; never levelled, the phone is taken to lie flat, screen up, top to the bow. Rate of turn is the gyroscope taken into the world frame, so it needs no mounting. Decisions: the attitude uses the **game rotation vector** (no magnetometer, so steel and wiring beside the phone do not throw roll and pitch) and switches to the rotation vector only for the heading, which is **off by default** and has a source of its own so a server ranks it below a real compass; the toggles, periods and bow offset are the plugin's settings (admin UI), the level is the app's. Accelerometer heave and cellular signal (`communication.cellular.*`, §8.1) are not in; the cellular reading is left for its own part (9.5.7).
      4. ~~USB serial~~ **Done** (`UsbSerial`, `SerialIds`, the plugin's `lib/serial.js`): usb-serial-for-android 3.11.0 (MIT; published on JitPack only, so `settings.gradle.kts` takes that repository for its group alone) reads every FTDI, Prolific, Silicon Labs, CH34x and CDC-ACM adapter while the server runs and the plugin asks for USB; each device's bytes go over the bridge socket as they came (`serial`, base64), and the plugin cuts them into sentences and parses them with the server's own NMEA 0183 parser, as `$source: android.<device id>.<talker>`, giving each sentence to the server's `nmea0183` event too, so its NMEA 0183 TCP server (10110) passes them on. A device's id is its vendor and product ids and, once allowed, its serial number (`usb-0403-6001-a10k1b2c`; two alike without one are told apart by plug order); the baud rate is the plugin's setting, 4800 unless set, per device by id. Permission comes from Android's own dialog: the one it shows when a device the app's filter names is plugged in (`res/xml/usb_devices.xml`) or the Dock's Allow; the app does not ask by itself on plug-in, since Android's dialog is then already up. Read only: nothing is written to a device. **Decision (the owner's): through the bridge, not a TCP port per device.** The plan's route does not work on signalk-server 2.34: adding a data connection over the admin REST API needs an admin login the plugin does not have, and a connection a plugin writes into the settings starts only with a restart of the server. A localhost port the owner adds by hand would have worked, but any app on the phone could reach it, and the server cannot read an NGT-1 over TCP; through the bridge nothing is exposed, nothing needs setting up, and NMEA 2000 can follow the same way. What is given up: the admin UI's per-connection options (sentence filters, talker groups, logging) do not apply to these devices. Carried in from the phones (§16 9.5): the GNSS now says in the log when the GPS is on and waiting for a fix, when location is off in the phone's settings and when the first fix comes, since a receiver indoors may never get one and the log could not tell that from a GPS that is not on.
      5. ~~Bluetooth SPP~~ **Done** (`BluetoothSerial`, `SerialPrefs`, `DeviceList`): the phone's paired devices are listed in the Dock's devices card, now **Serial devices**, each with a switch, and the ones the owner switched on are dialled over RFCOMM (the Serial Port Profile's UUID) while the server runs and the plugin asks for Bluetooth, read like USB ones (`serial` lines, `$source: android.bt-<address>.<talker>`), and dialled again with `Backoff` whenever the link drops or the adapter comes back on: Crew Radio's dial and redial loop, without its listener, tie-break and framing. `DeviceList` (pure, tested) puts both kinds into one list for the plugin (the chosen devices only) and the Dock (all of them, with byte counts). BLUETOOTH_CONNECT is asked for in the Dock's card on Android 12 and later; below that the install-time BLUETOOTH permission (`maxSdkVersion` 30). Decision (not made by the plan): **a paired device is read only once the owner switches it on**, kept in the app's `serial` preferences, since a phone is paired with headsets and cars as well and dialling them would be no use to Signal K; there is no discovery or pairing in the app, which the phone's own Bluetooth settings do. Read only, like USB.
      6. ~~Alarms in~~ **Done** (`PhoneAlarm`, `AlarmBook`, `AlarmActivity`, the plugin's `lib/alarms.js`):
         - **What is sent.** The plugin subscribes to the vessel's `notifications.*` through the server's subscription manager. Whenever the set changes it sends the whole list at or above its **Alarm level** setting (alarm by default; warn or emergency may be chosen), the most urgent first. A list rather than one message per alarm, so a reconnect (`resend`) leaves the phone holding no stale alarm.
         - **How the phone rings.** `AlarmBook` (pure, tested) says what to show and whether to ring. `PhoneAlarm` posts a notification on a silent channel of its own (`alarms`, importance high, `CATEGORY_ALARM`) with a full-screen intent to `AlarmActivity`, which shows over the lock screen and turns the screen on. It plays the phone's alarm sound itself, looped on the alarm stream with transient audio focus, and vibrates with alarm attributes, until the alarm is silenced.
         - **Silence.** Silence comes from the notification's action, the alarm screen or the Dock's Alarms card. It quiets the phone at once and goes to the plugin as `silence` by id. The plugin does it through the server's notification API (`app.notifications`, signalk-server 2.x): `silence` while the server allows it, `acknowledge` for an emergency, which Signal K does not let be silenced. The server's next list then carries the alarm without sound, on every client.
         - **Decisions (not made by the plan).** These rest on what signalk-server 2.34 does.
           - **The sound is the app's, not the channel's.** A notification's sound plays once, or until the shade is opened when insistent, while an anchor alarm must go on until someone silences it.
           - **Silence goes through the server's API rather than a delta of the plugin's own on the alarm's path.** Such a delta would stay behind as a second source after the alarm's source went back to normal.
           - **Where the server does not manage notifications, Silence quiets the phone alone.** That happens when `manageNotifications` is off, and there is then no id to silence by. The phone stays quiet for that alarm until it gets worse, or goes and comes back.
           - **Alarms outlast a plugin restart.** When the plugin goes (a server restart), the phone's alarms stand for two minutes before they are cleared. A plugin just started sends no empty list until it has sent an alarm, since the alarm's own source raises it again only a moment after a restart.
           - **Full-screen permission.** Android 14 and later grant the full-screen intent to alarm and calling apps only, so the Dock's Settings card shows **Full-screen alarms** and opens the system page for it. Without it the alarm is a heads-up notification that still rings.
      7. ~~Cellular signal~~ **Done** (`PhoneSensors`, `Readings`, the plugin's `cellular` setting):
         - **What is sent.** Every period (30 s by default, 1 to 600 s), under `$source: android.cellular`:
           - `communication.cellular.signalStrength`: Android's bars (`SignalStrength.getLevel`, 0 to 4) as a ratio.
           - `communication.cellular.dbm`: the power of the cell with the most bars, in dBm.
           - `communication.cellular.operator`: the network's name.
           - `communication.cellular.roaming`: whether the phone roams.
         - **No signal report**, or one that measured no cell (no SIM, flight mode): the strength and power are null, so the server stops showing the last ones. A telephony call the phone refuses clears all four and stops the reading until the sensors are switched again. A tablet without a cellular radio says so once in the log and sends nothing.
         - **Protocol.** A sensor value may now be a short text (at most 64 characters, no control characters, cleaned by the app) or a yes or no, besides a number or an object of numbers; both halves check it.
         - **Decisions (not made by the plan).**
           - **No permission is asked for.** The network's generation (LTE, 5G: `getDataNetworkType`) needs READ_PHONE_STATE, the phone permission, which a Signal K server has no other use for, so it is left out. The strength, the operator and roaming need none.
           - **Read, not listened to.** The modem's last report is read once a period. A listener below Android 12 is `PhoneStateListener`, a deprecated API, and a reading every 30 s is all a boat needs to know whether it has a signal.
           - **The paths are the plan's own.** Signal K's specification has no cellular group. The power is in dBm, as every phone and modem gives it, rather than in an SI unit nobody reads.
      8. ~~Bluetooth LE sensors~~ **Done** (`BleScanner`, `BleRules`; the plugin's `lib/ble.js`; `runtime-tools/preload/node-ble.js`). Asked for by the owner (2026-10-24), after `bt-sensors-plugin-sk` (Ruuvi, Victron, Govee, Inkbird, Xiaomi, BTHome and some sixty others) failed on the phone with `connect ENOENT /var/run/dbus/system_bus_socket`: it talks to BlueZ over D-Bus through `@naugehyde/node-ble`, which Android has neither of. Its 1.3.7 does not use the server's own Bluetooth LE API, and the owner wants it working with no change to it.
         - **The app scans.** `BleScanner` runs an open scan, which finds devices, and a watch scan filtered on the addresses a Bluetooth LE plugin was set up for, since Android stops a scan that names no device while the screen is off. The SDK's address filter takes no address type, so on some stacks a sensor with a random static address passes it by and is heard by the open scan alone, with the screen on. Each advertisement goes to the plugin as it came, an `adv` line with the raw advertising data and scan response. A packet both scans hear goes once (`Repeats`, 500 ms). Restarts keep within Android's five starts in 30 s, past which a scan silently hears nothing (`ScanBudget`, four); only a start counts, and a stop goes at once. A scan Android refuses after it started (`onScanFailed`) is started again after 5 s doubling to 5 min (each scan its own pause, reset when it hears anything), unless the phone has no Bluetooth LE scanning at all. Scanning needs Nearby devices (BLUETOOTH_SCAN, asked for with BLUETOOTH_CONNECT in the Dock, one dialog); below Android 12 it needs precise location. The manifest keeps `neverForLocation`, which also leaves out the few beacons Android counts as location.
         - **The plugin parses** the AD structures (`parseAd`: manufacturer data by company id, service data and UUIDs as BlueZ's 128-bit strings, the name, TX power). It gives each advertisement to the server's Bluetooth LE API, registered as a provider ("Android phone", no GATT), so the admin UI lists the phone's devices. It also gives each one to the stand-in's hub. The subscribe's `ble` part asks for a scan when the plugin's **Bluetooth LE devices** setting is on (off by default: a scan costs battery) or a BlueZ plugin started discovery, and names the addresses to watch, at most 32.
         - **The stand-in.** `node-ble.js`, a second preload beside `mdns-lookup.js`, answers `require('@naugehyde/node-ble')` and `require('node-ble')` with objects that behave as node-ble's do where bt-sensors looks, including its reach past the public API: adapter `hci0`, always powered; values wrapped in dbus-next-style `Variant`s, inner `Variant('ay', Buffer)` entries in the data dicts included; a fresh Device per `getDevice`; `helper._prepare()` subscribing and `helper.removeListeners()` unsubscribing; each advertisement as `PropertiesChanged` with what it carried; `devices()` listing what was heard in the last 120 s. It shares a hub with the bridge plugin on a global symbol (`Symbol.for('pocketsk.ble')`). Connecting (GATT) rejects with a plain message, so a sensor that needs a connection (most battery monitors: JBD, Jikong, Renogy and the like) reports "GATT services unavailable" rather than waiting.
         - **Checked off the phone.** On signalk-server 2.33 with unmodified `bt-sensors-plugin-sk` 1.3.7, the real bridge plugin and a fake app on the socket sending a RuuviTag RAWv2 advertisement as `adv` lines: bt-sensors identified a RuuviTag and published `environment.cabin.temperature` 297.45 K, `.relativeHumidity` 0.53 and `.pressure` 100044 Pa, Ruuvi's own example values. The server's BLE API listed the tag, seen by `pocketsk-android-bridge`. There was no D-Bus error.
         - **Decisions (not made by the plan).**
           - **A stand-in for node-ble, not a fake BlueZ.** dbus-next could be pointed at another socket and a D-Bus service written to imitate BlueZ, but that is the wire protocol and BlueZ's object model, a far larger thing to get right. node-ble's JavaScript surface, as bt-sensors uses it, is small and was read from both packages' sources.
           - **The raw advertising data crosses the socket, and the plugin parses it.** That keeps one parser, in tested JavaScript, for both consumers, and gives them every AD structure, not only the fields Android's `ScanRecord` picks out.
           - **No GATT yet.** It needs connections from the app, a queue for them and a protocol of its own; it is its own step if the owner wants battery monitors.
         - **To check on a device:** a RuuviTag, Victron or other advertising sensor is found by BT Sensors on the phone and its readings arrive; the screen off for ten minutes, a watched sensor keeps arriving; the Dock asks for Nearby devices once and the scan starts after it is granted.

      9. ~~The server's data on an SD card~~ **Done** (`DataStore`, pure and tested; `DataVolumes`; the Dock's Storage card; `ServerState.Storage`). Asked for by the owner (2026-10-24): offline charts and other bulky data on a card, the owner choosing the card or the phone's own storage (eMMC), in space Android frees when the app is uninstalled.
         - **Where.** The app's own folder on each removable volume (`getExternalFilesDirs`: an SD card, a USB drive where the phone gives one), `Android/data/fi.pocketsk/files/signalk`. No permission, a real path the server's node uses as the app does (same user), and removed with the app. The phone is `files/home/.signalk` as before.
         - **What moves.** `.signalk` stays on the phone, and so do the files directly in it (`settings.json`, `security.json`, the SSL key, `package.json`), `node_modules` and the plugins' settings files in `plugin-config-data`. Every other folder (charts, server state, application data, logs) and each plugin's data folder in `plugin-config-data` is copied to the card and replaced by a symbolic link to the copy, so the server and its plugins find it where they always did. Before every start (`Supervisor.placeData`), so a folder a plugin made during a run follows on the next.
         - **Moves.** A copy into `<name>.partial` and a rename; the source is renamed to `<name>.moved` before it is deleted. A process killed at any point leaves the data whole in one place, and the next start carries on. A folder the card has already (charts copied onto it with a PC) is merged with; a name taken there keeps the phone's copy as `<name>.from-phone`. A card without the room (plus 64 MB) is refused before anything is written, and the server gives up with the reason. Back to the phone the same way; a folder whose card is gone comes back empty, said in the log, so the server still starts.
         - **The card out.** A chosen card that is not in the phone is waited for (`ServerState.Storage`, "Waiting for <card>"), looked for every 10 s and at once on `MEDIA_MOUNTED`, never started without: the server without its data would start as a new one. `MEDIA_EJECT` (and the unmount and removal broadcasts) end a run on it while the card is still mounted, so its files close first; the next run waits for it to come back.
         - **The smoke test.** The installer copies `.signalk` with `cp -a`, which keeps the links; `DataStore.detach` turns them into empty folders in the copy, so the test server never writes the live data on the card.
         - **The Dock.** A Storage card: the phone and each card in it, with their room, the chosen one marked; a chosen card that is out stays listed as "not in the phone". A choice is confirmed, then the server restarts and moves the data first (a stopped server moves it when started).
         - **Decisions (not made by the plan).**
           - **The secrets stay on the phone.** The live ones do; since 11 below, copies of `security.json` and the plugins' settings, credentials among them, are in the folder a computer sees, on the phone's shared storage or on the chosen card, where anyone who takes the card out can read them. The owner asked for `.signalk` on its own partition; a FAT32 or exFAT card is readable by anyone who takes it out, and has no symbolic links, which npm and the bridge's plugin need in `node_modules`. So the configuration and the plugins stay in the phone's encrypted storage, and the data moves.
           - **Links, not settings.** No plugin is told a new path: Signal K and its plugins write where they always do, so it works for plugins the app knows nothing of (the charts plugin reads `~/.signalk/charts` by default).
           - **The app's folder, not "All files access".** It needs no permission and is freed on uninstall, as asked; charts kept elsewhere on a card are copied into `Android/data/fi.pocketsk/files/signalk/charts` with a PC.
           - **Waiting, not falling back.** A server started on the phone while its data is on a missing card would make new, empty state and plugin data, and merge it with the card's on its return.
         - **To check on a device:** moving to a card and back with a few GB of MBTiles; the charts plugin serving them from the card; ejecting the card in Android's settings while the server runs (the server stops, waits, and comes back when the card is put in again); a FAT32 card with a file over 4 GB (refused, with the reason); a USB drive where the phone offers one.
      10. ~~Files into the server's data, from a browser and over USB~~ **Done** (the plugin's `lib/files.js` and `public/index.html`; `Inbox`, pure and tested; the Storage card's inbox row). Asked for by the owner (2026-10-24), with 9.5.9 and in its pull request: map files and other bulky data uploaded from the network or from a laptop over USB, into wherever the data is (the phone's eMMC or the card).
          - **From a browser.** The bridge plugin's routes, `/plugins/pocketsk-android-bridge/files`: the data folders (`charts` always), a folder's files and unfinished uploads with the room left, a piece of a file (`PUT .../files/<folder>/<name>?offset=&total=`, the raw bytes, at most 64 MB, each starting where the last ended, else 409 with what arrived), and deletion. Its page, **Pocket SK files** (a Signal K webapp, so the plugin's package now has `public/`), sends 16 MB pieces, retries through a dropped connection, and carries on an unfinished upload when the same file (name and size) is chosen again. Pieces gather in `.pocketsk-uploads/<folder>` beside the folder, on the folder's volume (through the card's link), and the whole file is renamed in, over one of the same name: the charts plugin, which watches its folders, never sees half a chart nor reloads for every piece. 507 when the volume has not the room plus 64 MB.
          - **Over USB.** A computer (MTP) sees the phone's shared storage only, so the app keeps `Android/data/fi.pocketsk/files/inbox/charts` (and `inbox/<folder>`) there, made when the Dock shows it. A file still for 30 s (a copy over USB may still be writing it) is copied into the folder's staging and renamed in, then deleted from the inbox: before every start (`Supervisor.placeData`, after the folders are placed) and with **Take them in** in the Storage card, which says how many files wait. A laptop on USB tethering can also open the page.
          - **Decisions (not made by the plan).**
            - **The plugin's routes, not a server of the app's.** They are on the server's own port and behind its own login: signalk-server lets admins alone use a plugin's routes while security is on, so uploading needs no other password and no other open port. They work on any server, phone or not.
            - **Pieces, not one request.** Node ends a request after five minutes, which a large chart over a boat's Wi-Fi outlasts; pieces also give resuming for nothing.
            - **Only data folders.** The same rule as `DataStore`: a plain folder name, not `node_modules` nor `plugin-config-data`, and a file name with no path: nothing uploaded can become code the server runs or a plugin's settings.
            - **The inbox stays on this phone.** It is in the external backup domain, which the backup and transfer rules now exclude.
          - **To check on a device:** a chart of a few GB uploaded over Wi-Fi to the phone and to a card, with the Wi-Fi dropped half way and the upload carried on; the charts plugin showing it without a restart; a laptop over USB writing into the inbox (Android/data visible over MTP on the phone's Android version) and the app taking it in, including files the computer's MTP wrote that the app must read and delete.
      11. ~~`.signalk` over USB~~ **Done** (`ConfigMirror`, pure and tested; `DataVolumes.phone`; `Supervisor.placeData` and `mirror`; the Storage card's text; `README.txt` in the folder). Asked for by the owner (2026-10-25): the end user sees `.signalk` and its folders from a computer over USB, at an Android path, as `/home/node/.signalk` is on a Linux box, with no more restriction than there.
          - **Where.** `Android/data/fi.pocketsk/files/signalk`, on the phone's shared storage unless the owner chose a card, and then on the card, as before: what a computer sees over USB (MTP), and what the card shows in a card reader. The phone's shared storage is now the default place of the data (`ServerPrefs.dataVolume` null), so an existing install moves its data folders there at its next start, the way a card choice moves them (9.5.9). Waited for like a card while it is not mounted.
          - **The data folders are the real thing.** Charts, server state, application data, logs and each plugin's data folder live there (`DataStore`, unchanged), linked from `.signalk`: what a computer copies in, the server sees at once.
          - **The settings files are copies, both ways.** `settings.json`, `security.json`, the SSL key and certificate, `package.json` and the other files directly in `.signalk`, and the plugins' settings files in `plugin-config-data`, are copied there every 30 s while the server runs and when it ends. A copy changed, added or deleted on the computer goes into `.signalk` before the next start (a stop and start, or a restart from the admin UI); while the server runs, the server's own changes to that file wait behind it rather than overwrite it. What was last copied is in `.pocketsk-copies` there; without it (a folder emptied) nothing is deleted. Files a computer leaves (`.DS_Store`, `._*`, `Thumbs.db`, `desktop.ini`) and the server's half-written saves (`*.tmp`) are not copied; a file over 16 MB is not either.
          - **`node_modules` is not there.** Android runs code only from the app's private storage (the shared storage is mounted without exec, which a native addon needs) and the shared storage has no symbolic links, which npm and the bridge's plugin need. Plugins are installed and removed in the admin UI's App Store, as before.
          - **Decisions (not made by the plan).**
            - **Copies, not links, for the files.** signalk-server 2.34 saves `settings.json`, `security.json`, `defaults.json` and the plugins' settings by writing a temporary file and renaming it over the old one (`atomicWrite`), which puts a file of its own where a link to the shared storage was; and it refuses to start with an SSL key, and fails to save `security.json`, where it cannot make them readable by its own user alone (`chmod 600`), which shared storage and a FAT card cannot do. The live files stay in `files/home/.signalk` with their modes; a file taken in from the computer is written there readable by the app alone.
            - **Taken in at the next start.** The server reads its settings when it starts, so on a Linux box too an edit takes effect at a restart. Taking an edit in while the server runs would have it overwritten by the server's next save, or read half-way.
            - **A deleted copy deletes the file.** As on a Linux box: deleting `security.json` is how a forgotten password is reset.
            - **No restriction, as asked.** Anyone who can open the folder can read `security.json` (the users' password hashes and the key logins are signed with) and the plugins' credentials: from Android 11 on, someone with the phone unlocked and a USB cable; on Android 10, also any app with the storage permission; on a card, anyone who takes the card out. The README in the folder says so, and the Storage card does.
            - **Still out of backups.** The folder is in the external backup domain, which the backup and transfer rules exclude; the diagnostics bundle reads nothing there.
          - **To check on a device:** the folder and its files seen from Windows, macOS and Linux over MTP (Android/data shown on the phone's Android version), and from a card reader; an existing install's data moved to the phone's shared storage at its first start; `settings.json` edited on a computer while the server runs, taken in at a restart from the admin UI; `security.json` deleted on a computer and the users reset; a file the server saves showing over MTP without a reconnect.
10. M5 UI (§13: §8.1–8.3), in parts, one each, after the bridge (9.5).
    1. ~~The Status card's uptime, CPU and memory~~ **Done** (`ProcStat`, pure and tested; `ServerUsage`; `ServerState.Running.startedAt`, `.group`):
       - **What it shows.** Under the server's state: "Up 3:12:05 · CPU 4 % of one core · memory 182 MB".
       - **How.** The supervisor puts the run's start time and its process group (`NodeProcess.groupIdOrNull`) into `ServerState.Running`. While the Dock is in front, `ServerUsage` lists `/proc` every 5 s and reads `/proc/<pid>/stat` for each process of that group: signalk-server and whatever a plugin started. Android hides other apps' processes, so the list is short.
       - **The figures.** CPU is the group's user and system ticks between two looks, as a percentage of one core, as `top` gives it; the first look of a run has none. Memory is the processes' resident sets added up, which counts a shared page twice: an upper bound, what a phone's own process list shows.
       - **Decisions (not made by the plan).**
         - **Only while the Dock is in front.** Nothing reads `/proc` with no screen to show it, so the figures cost nothing on a passage.
         - **Percent of one core.** Node runs JavaScript on one thread, so a server near 100 % is the one that is saturated, however many cores the phone has.
    2. ~~The Status card's delta rate~~ **Done** (`stats` lines, `ServerStatus.deltaRate`):
       - **What it shows.** Under the uptime: "Taking in 12 deltas a second", a tenth under ten ("2.4").
       - **How.** signalk-server counts its deltas and emits `SERVERSTATISTICS` every 5 s (the figure its dashboard shows), and keeps the last event of each type in `app.lastServerEvents`. The bridge's plugin reads it from there every 5 s and sends `{"t":"stats","deltaRate":…}` to the app; the bridge publishes it to the Dock, and clears it when the plugin goes.
       - **Decision (not made by the plan).** **Read from the server's last events, not listened to.** A listener a plugin adds with `app.on` stays on the server's emitter after the plugin stops (signalk-server 2.33 removes a data connection's listeners, not a plugin's), so a plugin restarted again and again would pile them up.
    3. ~~The Updates card's changelog~~ **Done** (`ReleaseNotes`, pure and tested; `ReleaseNotesSource`; `Notes` in `AppModel`):
       - **What it shows.** Under the offer to install a newer signalk-server, **What's new in 2.14.0** opens the release notes of every release from the live version up to the offered one, newest first: each one's version and date, its first twelve lines and how many more, at most ten releases, and **All release notes on GitHub**, which opens signalk-server's releases page in the browser.
       - **How.** `GET https://api.github.com/repos/SignalK/signalk-server/releases?per_page=30`, the first time the notes are opened, kept a day in the app's cache (`release-notes.json`) as npm's versions are; **Check for updates** reads them again. Without a token GitHub answers sixty requests an hour from one address; a cached answer stands in when it cannot be reached or refuses.
       - **The text.** Most of signalk-server's notes GitHub writes itself: "## What's Changed", a line per pull request ending "by @someone in https://github.com/…/pull/123", "## New Contributors" and "**Full Changelog**: …". `ReleaseNotes.lines` keeps the changes, each with its pull request's number, and leaves out the rest; Markdown is turned into plain text (links to their text, images, HTML and emphasis dropped, points as "•"), so the Dock needs no Markdown renderer. Drafts and tags that are not versions are left out; betas show only when a beta is on offer.
       - **Decisions (not made by the plan).**
         - **Read when opened, not with every check.** The notes are a press away, so a phone that only checks for updates never asks GitHub, and one on a metered link asks once a day at most.
         - **Plain lines, not rendered Markdown.** A renderer is a library or a WebView, and the app has neither (§8.2); what the notes say survives as text.
         - **api.github.com is pinned, to root CAs, until 2027-10-01** (`res/xml/network_security_config.xml`, the owner's call on CodeQL's missing-pinning finding). The pins are the SPKI hashes of Sectigo's USERTrust ECC and RSA roots, their successors Public Server Authentication Root E46 and R46, and DigiCert Global Root G2 and G3, so GitHub can change leaf and intermediate certificates freely; only a move to another CA breaks the notes, and that shows as the card's "could not be read" line, nothing more. The pins lapse on the expiration date, after which the connection falls back to plain system trust: **renew the pins and the date before 2027-10-01.** The same file keeps cleartext allowed for 127.0.0.1 and the LAN, since it replaces `android:usesCleartextTraffic`.
       - **To check on a device:** on a fresh install (no cached notes), with an older version live, the notes of the releases on the way show under the offer, which proves the root pins match GitHub's real chain (do this before a release, and again after renewing the pins); with no network, the error shows and the rest of the card works; a second open within the day needs no network; the admin UI on 127.0.0.1 and a LAN server over plain http still open (the network security config's base-config).
    4. ~~The Logs card~~ **Done** (`LogFilter` and `Diagnostics`, pure and tested; `DiagnosticsBundle`; the manifest's FileProvider):
       - **What it shows.** The server's newest sixty lines as they come (every half second at most, as before), with chips **All**, **Problems** and **Pocket SK**, and a field for words. Problems are the lines with an error, a warning, a failure, a fatal, a crash, an uncaught or unhandled one, a denial or an exception, in the words Node, npm and the plugins print them; Pocket SK is the supervisor's own `[pocketsk]` lines. A line is shown when it holds every word typed, in any case and order. A filter looks through the newest 5000 lines of the two megabytes in memory.
       - **Share diagnostics.** A zip in the app's cache (`cache/diagnostics/`, each under a name of its own, the ones before it kept an hour, three at most, so an app still reading one is not cut off), handed through the share sheet by a FileProvider that is not exported and serves that folder alone: `README.txt`; `device.txt` (the app's version and commit, the server's state, slots and data volume as the Dock sees them, `NodeRuntime.describe`: phone, ABIs, RAM, the runtime's environment); `settings.txt` (the app's three preferences files, by name); `install-history.json`; `logs/server.log` and its predecessors.
       - **What stays out.** Nothing under `files/home/.signalk`: `Diagnostics.write` refuses such a file before writing anything, a path with `..` included. The preferences files are listed by name, so one added later that holds a token stays out until it is added knowingly, and a key naming a secret (token, password, secret, key, auth, cookie, credential) is written as "(left out)". Bluetooth addresses, in the settings and in the log, keep their last two bytes; a log is copied in 64 KB pieces, not read whole, since a line printed by a plugin has no length limit. The log is otherwise as printed, plugins' output included, which the card says before sharing.
       - **Decisions (not made by the plan).**
         - **A word filter and three kinds, not log levels.** Signal K's plugins print free text through `debug` and `console`; there is no level to filter on, so the kinds match the words the problems are printed with.
         - **Bluetooth addresses shortened.** A bundle is easily posted in a public issue; the last two bytes still tell two devices apart.
       - **To check on a device:** the chips and the words narrow the lines and a new line still arrives while a filter is on; Share diagnostics offers the zip to a mail or chat app, which opens it; the zip holds the files above and nothing from `.signalk`.

    5. ~~Webapps on the home screen~~ **Done** (`Webapps`, pure and tested; `WebappShortcuts`; the deep link on `MainActivity`). Chosen by the owner as the next part (2026-10-25).
       - **What it shows.** A **Webapps** card under Status lists the installed webapps by title, each with **Open** and, where the launcher takes pinned shortcuts, **Home screen**. Open, a shortcut and a deep link `pocketsk://webapp/<package>` all go the same way: a stopped server is started first, the way the tile starts it, and the webapp opens in the browser at `http://127.0.0.1:<port>/<package>/` once the server answers. A rotation on the way keeps the webapp asked for; with no server installed yet (the wizard not finished) the link is dropped.
       - **Where they come from.** The packages on disk, not the running server: `.signalk/node_modules`, where the App Store installs, first, then the live server's own `node_modules`, a name in both keeping the first. A package is a webapp when its keywords hold `signalk-webapp` (`signalk-embeddable-webapp` alone is left out: those live inside the admin UI). Its title is `signalk.displayName`, else the name; its icon is `signalk.appIcon` when that is a bitmap inside the package, scaled to 192 px, else the app's own. Read again when the Dock comes to the front and when a run starts.
       - **What may ride in a link.** Only an npm package name (`[@scope/]name`, lower case): it becomes the URL's path, so nothing else (`..`, `?`, a host) gets in. The intent filter is not BROWSABLE: another app or a shortcut can open a webapp, a web page cannot start the server by a link.
       - **Decisions (not made by the plan).**
         - **From disk, not `/skServer/webapps`.** The card works while the server is stopped, and needs no login when security is on.
         - **Opened in the browser, as §8.2 decided.** The shortcut is the app's link, not the URL itself, so the port the admin UI may have changed and a stopped server are both handled.
       - **To check on a device:** the card lists Freeboard-SK or KIP once installed from the App Store; Home screen offers the launcher's dialog and the shortcut shows the webapp's icon; with the server stopped, the shortcut starts it and opens the webapp when it answers.
    6. ~~The home-screen widget~~ **Done** (`GlanceView`, pure and tested; `BoatWidget`; `GlanceWatch`; the plugin's `glanceMessage`). Chosen by the owner as the next part (2026-10-26).
       - **What it shows.** **Boat at a glance**, two cells by one and resizable: Depth, SOG and Wind in three columns, the units in their labels ("Depth m", "SOG kn", "Wind kn") and bare numbers below ("3.2", "5.0", "10"), the wind's angle off the bow small beside its speed ("35°P"): laid out for two cells, its smallest size. A reading the server has no number for, or one older than 10 s, is a dash. While the server is not running the widget says its state instead ("Signal K server: Stopped"), as the Quick Settings tile does. A tap opens the Dock.
       - **Where the numbers come from.** The server's own data, read by the bridge's plugin with `app.getSelfPath`: depth below the transducer, else below the keel, else below the surface; `navigation.speedOverGround`; `environment.wind.speedApparent` and `angleApparent`; a value no boat could give (a depth below 0 or past 12 km, a speed past 200 m/s, an angle past a turn) is skipped for the next path. The plugin sends them as `glance` lines (SI units, each with the time the server got it) every 2 s while the app has sent `glance` on, and stops at off or when the link drops. The app asks while a widget is placed and the screen is on.
       - **How it is drawn.** Each glance is drawn as it comes (`AppWidgetManager.updateAppWidget`, from the bridge's reader); the server's state is drawn whenever it changes, by an observer the process (`App`) keeps; a screen coming on redraws first, so readings gone stale while it was off show as dashes before the next glance comes. The system's own update timer is off.
       - **Decisions (not made by the plan).**
         - **Through the bridge, not the WebSocket.** The plugin is inside the server, so the widget needs no login when security is on, and the app needs no WebSocket client: Android has none built in, and the app takes no library beyond AndroidX. A plugin the owner disabled is a widget of dashes.
         - **"While visible" is a screen that is on.** Android does not tell an app whether its widget is in sight; with the screen off nothing is asked of the server, and a widget on another home-screen page costs a glance every 2 s.
         - **Apparent wind, depth below the transducer first.** What a cockpit display shows by default.
         - **No time, no number.** A reading without the server's time is not shown, as with a stale one: a dead instrument keeps its last value in the server for ever.
       - **To check on a device:** the widget is in the launcher's widget list with its preview; placed, it shows the server's state while stopped and the numbers once running with an instrument or the simulator; with the screen off the plugin sends nothing (its debug log); removing the last widget stops the glances; a reading that stops shows a dash within about 12 s.
    7. ~~The night filter~~ **Done** (`NightRules`, pure and tested; `NightFilter`; `NightTile`; the Dock's Helm screen card). Chosen by the owner as the next part (2026-10-26).
       - **What it does.** A **Helm screen** card under Webapps: **Red night filter** with its strength, **Dim the screen** with a brightness of 1 to 100 %, below what the phone's own slider reaches, and **Keep the screen on**. Each change shows at once. A Quick Settings tile, **Night filter**, turns the filter on and off at the helm without opening the app.
       - **How.** One overlay window over every app (`TYPE_APPLICATION_OVERLAY`, the app allowed to display over other apps, which the card asks for first): a dark red (#700000) at up to 80 % opacity for the filter, the window's own `screenBrightness` for the dimming, `FLAG_KEEP_SCREEN_ON` for the screen. It takes no touch and no focus, so the browser under it is used as if it were not there. With only dimming or the screen kept on, the window is clear. The settings are the phone's own (preferences file `night`), put back when the process starts again (`App`).
       - **Decisions (not made by the plan).**
         - **The status bar is not tinted.** Android draws every app's overlay under the status and navigation bars since Android 8; the plan's "the status bar is red too" cannot be had without being a system app.
         - **At most 80 % opaque.** Android 12 and later pass a touch through another app's window only while it is at most that opaque (`getMaximumObscuringOpacityForTouch`); a stronger filter would leave the webapp under it deaf.
         - **A dark red, not a bright one.** Over a chart's black at night a bright red would light the screen up; #700000 keeps the black nearly black and leaves what is lit only its red.
         - **One window for all three**, so the filter, the dimming and the screen kept on never fight over which is on top.
       - **To check on a device:** the filter tints Freeboard-SK or KIP in the browser and taps still reach it at full strength; dimming goes below the phone's lowest; the screen stays on with the app in the background; the tile turns the filter on and off and opens the Dock while the permission is missing; after the app is swiped away or the phone restarts, the filter is back once the app's process runs again (the server's autostart or opening the app).
    8. ~~The Backup card~~ **Done** (`AesZip` and `Backup`, pure and tested; `BackupJob`; the Dock's Backup card). Chosen by the owner as the next part (2026-10-26), with the format and the contents.
       - **What it does.** A **Backup** card under Storage: a passphrase, typed twice for a new backup, **Save a backup** and **Restore**. Save offers `pocketsk-backup-2026-10-26-1930.zip` to the system's file picker, so the file goes wherever the owner likes: the phone, a card, Drive or Nextcloud. Restore asks first, opens the file the owner picks, and only while the server is stopped.
       - **What is in it.** What Signal K's own backup has, less the plugins: every file directly in `.signalk` (`settings.json`, `security.json` and the server's secret key, the SSL key, `package.json`, `baseDeltas.json`) and the plugins' settings files in `plugin-config-data`; not dot files, logs or half-written saves. Not `node_modules`, since the plugins are installed again from `package.json`, nor the data folders, charts above all, which may be gigabytes and are in the USB folder already. Those were the owner's choice of three.
       - **The format, the owner's choice of two.** A zip whose every entry is encrypted the way WinZip does it (AE-2, AES-256, HMAC-SHA1, PBKDF2-HMAC-SHA1 with the format's 1000 rounds), written by the app with the platform's AES and HMAC: 7-Zip and WinZip open it on a computer with the passphrase. That key stretching is weak, so the passphrase is 12 to 64 printable ASCII characters (ASCII, so it is the same bytes on any phone and in 7-Zip). The app reads only zips encrypted that way and checks every entry's HMAC before it writes anything, so a damaged or altered file is refused whole; a zip made by pyzipper, another implementation, is a unit test.
       - **A restore.** Every name in the backup must be one a backup holds, and none may land on a folder or a link (the data folders on a card are links in `.signalk`); the files go into `files/home/.pocketsk-restore` readable by the app's user alone, then into `.signalk` one by one, under `DataStore.LOCK`, so a start asked for meanwhile waits. A file the backup does not hold is left, as Signal K's restore leaves it. Then the bridge's plugin is put in place and `npm install --ignore-scripts` runs in `.signalk`, the App Store's flags, to install the plugins `package.json` lists; without the network the settings are restored and the card says the plugins are not, to be restored again or installed from the App Store. The next start copies the restored files to the USB folder, and they win there over whatever the computer did to their copies before (`files/home/.pocketsk-restored` names them until that sync), so an older edit or deletion of a copy cannot undo the restore.
       - **Decisions (not made by the plan).**
         - **A passphrase typed each time, kept nowhere.** A backup the app could open on its own would be a copy of the boat's passwords that anyone with the phone could make.
         - **Restore with the server stopped.** The server rewrites its settings while it runs; files changed under it would be overwritten by it, or read half old.
         - **The app's own settings stay out.** The Bluetooth devices chosen, the data's card and the night filter belong to this phone, not to the server a backup moves to another.
       - **To check on a device:** Save a backup writes the zip to Drive or a card; 7-Zip on a computer opens it with the passphrase and lists the settings files; Restore with a wrong passphrase says so and changes nothing; on a fresh install, Restore brings back the users, the connections and the plugins (npm installs them with the network on), and the admin UI logs in with the old password after a start.
    9. ~~The power profiles~~ **Done** (`PowerRules`, pure and tested; `PowerPrefs`; the Dock's Power card). Chosen by the owner as the next part (2026-10-27), the last of M5.
       - **What it does.** A **Power** card under Helm screen, three profiles, each with a line saying what it does. The server runs in all three.
         - **Harbor**: everything the plugin asks of the phone, the screen as the owner set it.
         - **Passage**: as Harbor, with the screen dimmed to the helm screen's brightness, whether or not the helm screen's own Dim is on. It uses the helm screen's overlay, so it needs the app allowed to display over other apps; the card says so when it is not.
         - **Battery saver**: of the phone's sensors the plugin asked for, only GNSS, at no more than one fix a second, and the battery; no Bluetooth LE scan.
       - **How.** The bridge keeps what the plugin last asked for and serves it through `PowerRules.subscription`, again whenever the profile changes (the Dock tells a running service, `ACTION_POWER`). The overlay takes the profile's dimming into its own rules (`NightRules.overlay`). The profile is the phone's own (preferences file `power`), so it is neither the server's nor in a backup.
       - **Decisions (not made by the plan).**
         - **The battery stays on in Battery saver.** Android broadcasts the battery's state anyway, so reading it costs nothing, and it is what one watches with the battery low.
         - **The Bluetooth LE scan stops in Battery saver.** A scan keeps a radio listening all the time; the sensors heard that way are the phone's to hear, like its own.
         - **Serial instruments carry on in every profile.** USB and Bluetooth serial devices are the boat's instruments, and their data is what the server is for.
         - **Passage dims through the overlay.** An app can set its own windows' brightness, not the phone's; the overlay over every app is the one window that reaches the browser and its webapps.
       - **To check on a device:** Battery saver stops the attitude, barometer and cellular readings in the server's data browser and keeps the position and the battery, at one fix a second; the plugin's Bluetooth LE devices stop showing; Harbor brings them back without a restart; Passage dims the screen over the browser, and the card asks for the overlay permission while it is missing.
11. M6 Prebuilds (§13: §9), in parts, one each. Chosen by the owner as the next milestone (2026-10-27).
    1. The prebuild workflow (§9.1) **in review** (`prebuilds/`, `.github/workflows/prebuilds.yml`; `lib.js` pure and tested):
       - **What it does.** Builds the native addons of `prebuilds/packages.json` for Android arm64 on GitHub's runners and publishes them, with `index.json`, as assets of the Release tagged `prebuilds`. It runs when the list, the script or the pinned runtime changes, weekly for the packages' new releases, and by hand. On a pull request it only builds, and a package that does not build fails the check.
       - **How it is kept apart.** As in `build.yml`: `build` runs code from the registry (gyp may run a package's actions), so it has a read-only token and nothing else; `attest` signs provenance for its files; `publish` holds the only write token, without a checkout, and uploads the builds before the index, so the index never names a file not yet there.
       - **Decisions (not made by the plan).**
         - **Linked against `libnode.so`.** R2 has not been run on a phone yet. An addon linked so loads whether or not the unlinked kind does, since `libnode.so` is already in the process that loads it, and it is how nodejs-mobile builds addons for Android itself.
         - **The newest two versions of each range.** The newest is what an App Store install resolves a plugin's range to; the one before covers a plugin pinned a release behind. The index keeps every version built before, so the list only grows.
         - **One Release, not one per run.** The shim needs one index to read and one place to fetch from; a Release per run would make it search.
         - **A build that fails does not stop the others** on main and on the weekly run, where a package's new release could otherwise hold back every other; it is a warning there and an error on a pull request.
       - **Tried before review** (2026-10-27, the same NDK r27d and nodejs-mobile 24.20.0-0, Node 22 running node-gyp): all fourteen of the list's versions built, each an AArch64 shared object with 16 KB-aligned segments, needing `libnode.so` and `libc++_shared.so`, both in the APK; a second run with that index built nothing again.
       - **To check on a device:** with part 2's shim, an App Store plugin that needs serialport or better-sqlite3 installs and loads its addon.
    2. The node-gyp shim (§9.2): `files/bin/node-gyp`, `.npmrc`, the Dock's message for a build not shipped.

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
- The Android bridge's alarm path (§7.1) and `signalk-crewradio`'s announcement path are the same event; document which wins when both apps are on the same phone (suggest: Crew Radio speaks it, Pocket SK shows the full-screen alarm, one "silence" action clears both through the Signal K notification state). Since §16 9.5.6 the phone's Silence goes through the server's notification API, so the silenced state is the server's and every client, Crew Radio's plugin included, sees the alarm's method lose its sound.
- Keep `SignalKClient`, `SignalKUrl`, `SignalKDiscovery`, `SignalKTree` byte-identical in both repos for now; when they diverge for the third time, extract `fi.signalk.android` as a small MIT/EUPL library both depend on.
