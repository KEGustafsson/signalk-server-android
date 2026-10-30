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

The other way, besides the boat's alarms, the plugin tells the app the server's delta rate every
5 s, as the server's own statistics count it, for the Status card of the app's Dock. While the
app's home-screen widget is placed and the phone's screen is on, it also sends the boat's depth,
speed over ground and apparent wind every 2 s, read from the server's own data, with the time the
server got each one.

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

Bluetooth LE sensors (Ruuvi, Victron, Govee, Inkbird, Xiaomi, BTHome and the many others that
advertise their readings) are heard by the phone's own Bluetooth. Every advertisement goes to the
server's Bluetooth LE API, with the phone registered as a provider ("Android phone"), and to
plugins written for a Raspberry Pi's Bluetooth: the app gives the server a stand-in for
`@naugehyde/node-ble` that is fed by the phone, so **BT Sensors** (`bt-sensors-plugin-sk`) runs on
the phone as it is, adapter `hci0` and all. Such a plugin starts the scan itself; **Bluetooth LE
devices** starts it for the server's API alone. The addresses such a plugin is set up for are kept
heard with the phone's screen off, which Android stops for a scan that names none. Allow Nearby
devices in the app when it asks (below Android 12: location). Sensors that need a connection
(GATT: most battery monitors) do not work yet; they report that connections are not available on
Android.

The boat's alarms go the other way, to the phone. The server's notifications at **Alarm level** or
above (alarm and emergency by default; warn as well if set) ring on the phone with its own alarm
sound on the alarm volume, vibrate it, and fill its locked screen. That is an anchor alarm that
wakes a phone asleep in a pocket. The phone's **Silence** is done through the server's notification
API, so the alarm goes quiet on every client at once. An emergency, which Signal K does not let be
silenced, is acknowledged instead. Where the server does not manage notifications (its
`manageNotifications` setting is off), Silence quiets the phone alone. **Alarms on the phone** turns
it all off.

## Satellite mode

When the app's Dock follows the boat's main Signal K server (a Raspberry Pi, say), this plugin
holds the link: it opens the main server's delta stream with the device token its admin approved
for the phone, and gives every delta to this server, the main server's vessel as this one's own.
The plugin's status line says how the link stands. The token stays in the app, which hands it to
the plugin over its socket. With read/write access, the phone's own sensors and serial devices go
to the main server too, as the app's Dock chose them, under the phone's own name there
(`pocketsk.<id>.android.*`), and the phone's Silence silences the main server's alarm.

## Files

The plugin's web page, **Pocket SK files** among the server's webapps, uploads files into the
server's data folders: offline charts (MBTiles and the like) into `charts`, where the charts plugin
finds them as they arrive, or any other data folder of the server's configuration. Drop the files
on the page or choose them. They go in pieces of 16 MB, so a chart of several gigabytes survives a
dropped Wi-Fi: choose the same file again and the upload carries on where it stopped. A file is in
the folder only once it is whole, and replaces one of the same name. On a phone that keeps the
server's data on an SD card, the files go to the card. The page lists each folder's files and the
uploads not yet finished, with the room left, and deletes them. Only an admin may use it while the
server's security is on. It works on any server, phone or not.

What the charts plugin serves from `charts` is what a chart plotter shows. Freeboard-SK, for
example, draws an S-57 chart and OpenStreetMap with a depth layer like this:

<p>
  <img src="https://raw.githubusercontent.com/KEGustafsson/signalk-server-android/main/docs/images/freeboard-s57-chart.png" alt="An S-57 chart of Government Cut, Miami, drawn by Freeboard-SK" width="49%">
  <img src="https://raw.githubusercontent.com/KEGustafsson/signalk-server-android/main/docs/images/freeboard-osm-depth.webp" alt="Freeboard-SK with OpenStreetMap, a depth layer and the boat's heading" width="49%">
</p>

From a laptop over USB without the network: copy the files into the phone's
`Android/data/fi.pocketsk/files/inbox/charts` (or `inbox/<folder>`) and the app takes them into
that folder when the server starts or with **Take them in** in its Storage card. With USB tethering
on, the laptop can also open this page at the phone's address.

On a server that is not running on Pocket SK the plugin finds no socket, says so in its status and
otherwise does nothing; the file page works there too.

## Development

```sh
npm test          # node --test
npm run coverage  # with the coverage thresholds CI enforces
```

The protocol (newline-delimited JSON, `lib/protocol.js`) is mirrored by the app's
`fi.pocketsk.bridge.BridgeProtocol`; change both together.

Licence: EUPL-1.2.
