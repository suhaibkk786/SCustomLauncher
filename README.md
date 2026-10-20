# S Custom launcher (Android TV kiosk launcher)

Runs ONE app of your choice, always. You pick the app in the admin panel.
No ads, no app list for users, no internet permission. Replaces the TV's
stock home screen (and its ads).

## Install
1. Build the APK (push this folder to GitHub > Actions > "Build APK" > download app-debug.apk).
2. Copy app-debug.apk to USB and install it on the TV with a file manager.
3. Press Home on the remote > choose "S Custom launcher" > Always.
   (or: adb shell cmd package set-home-activity com.suhaib.scustomlauncher/.MainActivity)

## First run
The admin panel opens automatically. Pick any app in the list and press "Save & Start".
Default PIN: 1234 (change it with "Change PIN").

## Normal use
- TV on -> launcher starts -> the selected app opens after ~5 seconds.
- If the app is closed or crashes, it reopens automatically.
- Users have no way to reach other apps. Back is disabled.

## Admin access later
Press Home on the remote, then press OK 5 times quickly on the black screen,
enter the PIN. From there you can: change the selected app, change the PIN, see
Wi-Fi/LAN status and IP address, open Wi-Fi or LAN (Ethernet) settings, rotate the screen,
and open all system settings.

## Note
A launcher controls what the user can reach, but cannot stop the selected app
from opening other apps itself. For a fully locked device, device-owner kiosk
mode can be added later.

## Compatibility
- Android 5.0 (API 21) and newer: Android TV, Google TV, Sony/Philips/TCL/Hisense/Xiaomi/Nvidia Shield,
  Android TV boxes and sticks. Works with remote, keyboard or mouse; no touchscreen needed.
- If the TV will NOT let you change the default Home app (some Google TV models):
  open the admin panel, press "Allow auto-start" and grant the permission. The launcher then
  starts by itself at boot. (In this mode it cannot reopen the app after it is closed,
  because the stock home screen takes over.)
- The launcher also appears in the normal app list, so it can be started by hand.

## Wi-Fi / LAN
Admin panel shows connection type and IP. "Wi-Fi" and "LAN / Ethernet" open the TV's own
network screens (screen names differ per brand; if one is missing it falls back to the main
network/settings screen). After you change a network setting and press Back, the launcher
restarts the selected app.

## Rotation
"Rotation" cycles 0 / 90 / 180 / 270 degrees. To rotate ALL apps (not just the launcher), press
"Allow rotation control" once and switch the permission on. Some TV firmwares ignore rotation;
if the picture turns the wrong way, press Rotation until it looks right.
