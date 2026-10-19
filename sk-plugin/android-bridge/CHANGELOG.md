# Changelog

## 0.2.0

- The phone's GNSS: `navigation.position`, `navigation.speedOverGround` and
  `navigation.courseOverGroundTrue` under `$source: android.gnss`, once precise location is allowed
  in the app. Settings: GNSS on or off and its period (1 s by default).

## 0.1.0

- First version: connects to Pocket SK's bridge socket and publishes the phone's battery
  (`electrical.batteries.phone.*`) and barometer (`environment.outside.pressure`).
