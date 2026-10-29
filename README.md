# Signal K Server for Android

A full [Signal K Server](https://github.com/SignalK/signalk-server) on any Android phone or tablet: the real Node.js server, its App Store, plugins and webapps, unmodified.

The APK ships only a Node.js runtime and a bootstrap installer. On first run the app installs `signalk-server` from npm into app-private storage, the same way `npm install -g signalk-server` does on a Pi. Around that it adds what a phone needs and a Pi does not:

- a foreground service that keeps the server alive with the screen off, and restarts it the way systemd would
- A/B upgrades of the server with a health gate and automatic rollback
- USB and Bluetooth serial bridged into Signal K (no root, no `/dev/tty*`)
- the phone's own GNSS, attitude, barometer and battery as Signal K data
- Signal K alarms as real Android alarms that wake a locked phone
- a helm kiosk mode: any webapp as its own launcher icon, red night overlay, widgets
- the server's offline charts and other mass data on the phone or on an SD card, chosen in the app,
  and filled from a browser on the boat's network or from a laptop over USB
- the app updating itself from this repository's Releases, the download checked against its
  published checksum and the app's own signing key (an F-Droid or Play install is left to its store)

<p>
  <img src="docs/images/freeboard-s57-chart.png" alt="An S-57 chart of Government Cut, Miami, drawn by Freeboard-SK" width="49%">
  <img src="docs/images/freeboard-osm-depth.webp" alt="Freeboard-SK with OpenStreetMap, a depth layer and the boat's heading" width="49%">
</p>

*Charts as Freeboard-SK draws them from a Signal K server: an S-57 chart (left) and OpenStreetMap
with a depth layer (right).*

Sibling project: [Crew Radio](https://github.com/KEGustafsson/crew-radio), whose service, networking and Signal K client code this app reuses.

## Status

Skeleton. The project builds an app whose only screen is the first risk spike (can an app exec the Node runtime?); the runtime itself is not in yet. The plan, including the spikes that decide the architecture, is in [docs/PLAN.md](docs/PLAN.md).

## Build it yourself

Open the folder in Android Studio (a release that supports Android Gradle Plugin 9.4) and build, or run `./gradlew assembleDebug` with an Android SDK (platform 37) and a JDK 17 on the machine. Unit tests: `./gradlew testDebugUnitTest`. Every merge to `main` builds a signed APK and publishes it on the Releases page; the build, signing and release procedure is the one documented for [Crew Radio](https://github.com/KEGustafsson/crew-radio/blob/main/docs/BUILDING.md), with `SIGNALK_ANDROID_` in place of `CREWRADIO_` in the secrets and environment variables.

## Licence

EUPL-1.2 ([LICENSE](LICENSE)).
