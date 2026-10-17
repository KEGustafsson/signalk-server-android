# Signal K Server for Android

A full [Signal K Server](https://github.com/SignalK/signalk-server) on any Android phone or tablet: the real Node.js server, its App Store, plugins and webapps, unmodified.

The APK ships only a Node.js runtime and a bootstrap installer. On first run the app installs `signalk-server` from npm into app-private storage, the same way `npm install -g signalk-server` does on a Pi. Around that it adds what a phone needs and a Pi does not:

- a foreground service that keeps the server alive with the screen off, and restarts it the way systemd would
- A/B upgrades of the server with a health gate and automatic rollback
- USB and Bluetooth serial bridged into Signal K (no root, no `/dev/tty*`)
- the phone's own GNSS, attitude, barometer and battery as Signal K data
- Signal K alarms as real Android alarms that wake a locked phone
- a helm kiosk mode: any webapp as its own launcher icon, red night overlay, widgets

Sibling project: [Crew Radio](https://github.com/KEGustafsson/crew-radio), whose service, networking and Signal K client code this app reuses.

## Status

Planning. Nothing builds yet. The plan, including the risk spikes that decide the architecture, is in [docs/PLAN.md](docs/PLAN.md).

## Licence

EUPL-1.2.
