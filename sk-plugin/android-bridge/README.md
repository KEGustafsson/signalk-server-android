# @pocketsk/android-bridge

The Signal K server's view of the Android phone it runs on, for
[Signal K Server for Android](https://github.com/KEGustafsson/signalk-server-android) ("Pocket SK"),
which runs the real signalk-server on a phone or tablet.

The app installs and enables this plugin by itself: there is nothing to install from the App
Store. It connects to a Unix socket in the app's private directory and publishes what the phone
knows as ordinary Signal K data:

| Setting | Signal K paths | `$source` |
| --- | --- | --- |
| Phone battery | `electrical.batteries.phone.capacity.stateOfCharge`, `.voltage`, `.temperature` | `android.battery` |
| Barometer | `environment.outside.pressure`, averaged over the chosen period | `android.baro` |
| GNSS | `navigation.position`, `navigation.speedOverGround`, `navigation.courseOverGroundTrue` | `android.gnss` |

GNSS needs precise location allowed in the app (its Dock has the button); without it the other
sensors still run. The phone's attitude, USB and Bluetooth NMEA devices, and alarms that wake a
locked phone come in later versions.

On a server that is not running on Pocket SK the plugin finds no socket, says so in its status and
does nothing.

## Development

```sh
npm test          # node --test
npm run coverage  # with the coverage thresholds CI enforces
```

The protocol (newline-delimited JSON, `lib/protocol.js`) is mirrored by the app's
`fi.pocketsk.bridge.BridgeProtocol`; change both together.

Licence: EUPL-1.2.
