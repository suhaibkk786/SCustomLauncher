# S Launcher (Android TV kiosk launcher)
Developer: Suhaib Hameed

Runs ONE app of your choice, always - or the built-in USB image/video player.
No ads, no app list or home screen for users, no internet permission.

## Install
1. Build the APK (push this folder to GitHub > Actions > "Build APK" > download app-debug.apk).
2. Install it on the TV (USB + file manager, or Downloader short link).
3. Make it the default Home app:
   - Admin panel > "Set as default Home", or
   - adb shell cmd package set-home-activity com.suhaib.scustomlauncher/.MainActivity
   - If another launcher (e.g. Dream Launcher) is the default: Settings > Apps > that launcher > Clear defaults, then press Home and choose S Launcher > Always.

## First run
The admin panel opens. Select the ONE app to run (or "USB media player") and press "Save & Start".
Default PIN: 1234 (change it with "Change PIN").

## Normal use
TV on -> black "Starting..." screen -> the selected app opens after ~5 seconds.
If it is closed, crashes, or Home is pressed, it reopens automatically. Back is disabled.

## Admin access later
Press Home, then press ANY remote button (or tap/click) 5 times quickly on the black screen,
enter the PIN (it opens by itself when the PIN is right). There you can: choose another app,
change the PIN, see Wi-Fi/LAN status and IP, open Wi-Fi / LAN / all settings, rotate the screen,
and set the image time and video sound of the USB player.

## USB media player (ads / menu boards)
1. Put images and videos on a USB drive, in a folder named "signage" (or the main folder of the drive).
   Files play in name order: 01_menu.jpg, 02_offer.mp4, 03_promo.png ...
   Best formats: JPG/PNG and MP4 (H.264).
2. Admin panel > "Allow USB access" (one time). If that screen does not exist on your TV, run from a PC:
   adb shell appops set com.suhaib.scustomlauncher MANAGE_EXTERNAL_STORAGE allow
   adb shell pm grant com.suhaib.scustomlauncher android.permission.READ_EXTERNAL_STORAGE
3. Admin panel > select "USB media player" > Save & Start.
- Videos play to the end, then the next file starts. Images use "Image time" (any seconds, 1-3600).
- The TV starts the USB player again after every restart and waits until the USB drive is ready.
- Locked: Back is disabled and the player restarts if closed.
- Swap or remove the USB drive any time: it rescans at the end of every loop.

## Wi-Fi / LAN, rotation
Admin buttons open the TV's network screens. "Rotation" cycles 0/90/180/270; press
"Allow rotation control" once so other apps rotate too.

## Forgotten PIN
A built-in master (bypass) PIN also opens the admin panel if the admin PIN is forgotten.
Ask the developer, Suhaib Hameed, for it. It is not written in this file.
