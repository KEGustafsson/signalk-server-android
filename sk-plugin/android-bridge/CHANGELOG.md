# Changelog

## 0.13.0

- Satellite mode, the push: the phone's sensors and its serial devices, as the app's Dock chose
  them (`push` in the app's satellite message), go to the main server on the stream followed, each
  source under the phone's label (`pocketsk.<id>.`), only while the main server lets the phone
  write (its login status, now in the satellite state sent to the app as `write`). What the phone
  sent comes back from the main server and is dropped. The phone's Silence of one of the main
  server's alarms is also done there, by the main server's own id for it.

## 0.12.0

- Satellite mode: when the app says so (`{"t":"satellite","url","token"}`), the server follows the
  boat's main Signal K server, its delta stream opened with the device token in the Authorization
  header, the main server's vessel as this one's self and its sources kept. The link's state goes
  back to the app and into the status line. signalk-server's own Signal K connection is not used:
  in 2.35.1 it sends its token where the server does not read it.

## 0.11.0

- Glance: for the app's home-screen widget, the boat's depth (below the transducer, else the keel,
  else the surface), speed over ground and apparent wind speed and angle, read from the server's
  own data (`app.getSelfPath`) and sent to the app every 2 s, only while the app asks (its widget
  is placed and the phone's screen is on). Each value comes with the time the server got it, so
  the app can blank one that went stale.

## 0.10.0

- Files: a web page (`public/index.html`, a Signal K webapp) and routes under
  `/plugins/pocketsk-android-bridge/files` that upload files into the server's data folders,
  charts above all: in pieces of at most 64 MB, each saying where it starts, resumed after a
  dropped connection, staged beside the folder on its volume and renamed in whole. Lists, room left
  and deletes. Admins only while security is on (the server's rule for plugin routes).

## 0.9.0

- Bluetooth LE: the app's `adv` lines (each advertisement the phone's scanner heard, as raw
  advertising data) are parsed and given to the server's Bluetooth LE API, with this plugin
  registered as a provider (`app.registerBLEProvider`), and to the node-ble stand-in the app
  preloads, through its hub, so plugins written for BlueZ (`bt-sensors-plugin-sk`) run unchanged.
  The subscribe's new `ble` part asks the app to scan when the new **Bluetooth LE devices** setting
  is on (off by default) or such a plugin started discovery, and names the addresses it was set up
  for, which the app keeps hearing with the screen off. No connections (GATT).

## 0.8.0

- The server's delta rate for the app's Dock: every 5 s the plugin passes on what the server's own
  statistics last counted (`SERVERSTATISTICS`, the figure the admin UI's dashboard shows), read
  from the server's last events rather than through a listener of the plugin's own.

## 0.7.0

- The phone's cellular network, under `$source: android.cellular`: `communication.cellular.signalStrength`
  (the phone's bars as 0 to 1), `.dbm` (the serving cell's power), `.operator` (the network's
  name) and `.roaming`. Settings: on or off and the period (30 s by default). A sensor value may
  now be a short text or a yes or no as well as a number.

## 0.6.0

- The boat's alarms on the phone: the server's notifications at the **Alarm level** or above
  (alarm, by default) are sent to the app, which rings, vibrates and wakes the phone's screen. The
  phone's Silence is done through the server's notification API (`app.notifications`): silence, or
  acknowledge for an emergency. Settings: alarms on or off, and the level.

## 0.5.0

- Bluetooth serial devices: the phone's paired NMEA 0183 senders and AIS receivers that the owner
  switched on in the app's Serial devices card, read like USB ones, under
  `$source: android.bt-<address>.<talker>`. Setting: Bluetooth on or off. The status line shows
  whether each is connected or being dialled again.

## 0.4.0

- USB serial devices plugged into the phone: their NMEA 0183, parsed with the server's own parser,
  under `$source: android.<device id>.<talker>`, and every sentence given to the server's
  `nmea0183` event (so its NMEA 0183 TCP server passes it on). Settings: USB on or off, the baud
  rate (4800 by default) and a rate per device. The status line shows each device and how it stands.

## 0.3.0

- The boat's attitude from the phone's rotation sensor: `navigation.attitude` roll and pitch, and
  `navigation.rateOfTurn` from the gyroscope, under `$source: android.imu`, levelled from the app's
  Sensors card. Settings: on or off, the period (1 s by default) and the bow offset.
- The phone's compass as `navigation.headingMagnetic` under `$source: android.compass`, off by
  default.
- The cabin's temperature, humidity and light as `environment.inside.temperature`,
  `.relativeHumidity` and `.illuminance`, those the phone has. Settings: on or off and the period.
- `navigation.gnss.satellites`: the satellites used in each fix.

## 0.2.0

- The phone's GNSS: `navigation.position`, `navigation.speedOverGround` and
  `navigation.courseOverGroundTrue` under `$source: android.gnss`, once precise location is allowed
  in the app. Settings: GNSS on or off and its period (1 s by default). A fix without speed or
  course sets that path to null, so the last value is not shown as current.
- The bridge protocol carries a null sensor value.

## 0.1.0

- First version: connects to Pocket SK's bridge socket and publishes the phone's battery
  (`electrical.batteries.phone.*`) and barometer (`environment.outside.pressure`).
