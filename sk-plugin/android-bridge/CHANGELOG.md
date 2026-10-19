# Changelog

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
