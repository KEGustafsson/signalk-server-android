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
| GNSS | `navigation.position`, `navigation.speedOverGround`, `navigation.courseOverGroundTrue`, `navigation.gnss.satellites` | `android.gnss` |
| Attitude and rate of turn | `navigation.attitude` (roll, pitch), `navigation.rateOfTurn` | `android.imu` |
| Magnetic heading (off by default) | `navigation.headingMagnetic`, and the attitude's yaw | `android.compass` |
| Cabin sensors | `environment.inside.temperature`, `.relativeHumidity`, `.illuminance`, those the phone has | `android.environment` |
| Cellular network | `communication.cellular.signalStrength` (0 to 1: the phone's bars), `.dbm` (the serving cell's power), `.operator`, `.roaming`, read every 30 s by default | `android.cellular` |
| USB and Bluetooth serial devices | whatever their NMEA 0183 says (position, AIS targets, depth, wind...) | `android.<device id>.<talker>` |

GNSS needs precise location allowed in the app (its Dock has the button); without it the other
sensors still run. The cellular reading needs no permission; a tablet without a cellular radio
says so in the server log and sends none.

The attitude is the boat's, not the phone's. With the phone in its holder and the boat lying level
and still, tap **Level now** in the app's Sensors card: roll and pitch then read zero as the phone
sits. Until then the phone is taken to lie flat, screen up, with its top towards the bow. The
boat's forward is where the top of the phone points, or its back when it stands upright; **Bow
offset** turns it from there (90 when the phone's right side faces the bow). Roll is positive to
starboard, pitch bow up, rate of turn to starboard, as Signal K has them.

The attitude uses the game rotation vector, which has no magnetometer, so the steel and wiring
around a mounted phone do not throw it. The magnetic heading needs the magnetometer and is off
unless turned on; its own source, `android.compass`, lets the server rank it below a real compass.

USB serial adapters plugged into the phone (FTDI, Prolific, Silicon Labs, CH34x and CDC-ACM ones:
GPS and AIS receivers, NMEA 0183 interfaces) are read by the app, since Android gives the server
no serial port, and their bytes come over the same socket. The plugin parses their NMEA 0183 with
the server's own parser, the one its data connections use, each device under its own source
(`android.usb-0403-6001-a10k1b2c.GP`), and gives every sentence to the server's `nmea0183` event,
so its NMEA 0183 TCP server on port 10110 passes them on to the boat's network. Allow each device
in Android's dialog when it is plugged in, or with **Allow** in the app's USB devices card, which
also shows each device's id, state and bytes read. **Baud rate** is 4800 unless set (38400 for
most AIS receivers); **Baud rate per device** sets it for one device by its id. Nothing is written
to the devices. The admin UI's options for data connections (sentence filters, talker groups,
logging) do not apply to them, since they are not data connections.

Bluetooth serial devices (the Serial Port Profile: Bluetooth GPS and AIS receivers, NMEA 0183
adapters) are read the same way, under `android.bt-<address>.<talker>`. Pair the device in the
phone's Bluetooth settings, allow Nearby devices in the app if it asks, and switch the device on in
the app's **Serial devices** card: pairing alone is not enough, since a phone is paired with
headsets and cars too. The app dials each chosen device, and dials it again whenever the link
drops. **Bluetooth serial devices** turns them all off.

The boat's alarms go the other way, to the phone. The server's notifications at **Alarm level** or
above (alarm and emergency by default; warn as well if set) ring on the phone with its own alarm
sound on the alarm volume, vibrate it, and fill its locked screen. That is an anchor alarm that
wakes a phone asleep in a pocket. The phone's **Silence** is done through the server's notification
API, so the alarm goes quiet on every client at once. An emergency, which Signal K does not let be
silenced, is acknowledged instead. Where the server does not manage notifications (its
`manageNotifications` setting is off), Silence quiets the phone alone. **Alarms on the phone** turns
it all off.

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
