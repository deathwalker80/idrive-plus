# NBT Plus

🇮🇹 [Leggi in italiano](README.md)

Weather, news, local news, fuel prices and traffic **inside iDrive** on cars with the
**NBT EVO (ID6)** head unit, without changing any control unit and without touching Android Auto.

The head unit has no internet of its own: the phone app downloads the data (over the phone's data connection)
and hands it to iDrive over the wireless Android Auto Wi-Fi. The iDrive plugins ask the app for ready-made pages
(port 8765) and update themselves.

One app, **NBT Plus**, for all three cars. The brand is recognised from the car's Bluetooth name
(or chosen in the settings) and sets the app's colour and the car's name in the texts:

| Car | Colour |
|---|---|
| MINI (F5x / F60) | light blue |
| BMW with NBT EVO | yellow |
| Toyota GR Supra (J29) | red |

> **Status:** tested on a MINI F60 (8.8" screen). On BMW and Supra these still need checking on a real car:
> the online-services menu where the entries appear, the car's Bluetooth name and, for the 10.25" screen
> (1440×540), the page calibration.

## Requirements
- NBT EVO head unit with **root SSH** access.
- **Wireless Android Auto** on the head unit ([Sq00ky's nbt-aad](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto)):
  the phone must be on the car's Wi-Fi.
- Android 8 or later on the phone.

## Features
- **Weather** (open-meteo.com) with the **Italian Civil Protection warnings** for the municipality you are in.
- **National news** and **local news** for a chosen city (ANSA RSS feeds), with thumbnails.
- **Fuel stations** nearby with official MIMIT (Osservaprezzi) prices and the brand logo.
- **Traffic** nearby (Autostrade per l'Italia).
- **Where I parked**: when the phone disconnects from the car's Bluetooth, the position is saved.
- The app starts by itself with the car's Bluetooth and stops when you get out.
- **Car name** of your choice in the settings (e.g. "Lucy" instead of "MINI12345"): it only changes what the app shows.

Data sources are Italian, so the news, fuel and traffic sections are for Italy; the weather works anywhere.

## Layout
```
app/        Android app (Kotlin, no external libraries)
plugin/     source of the five iDrive plugins (C++ without the standard library, ARM/QNX)
chiavetta/  USB-stick install scripts, plugin descriptions and "waiting" pages
```

## Building
**App**
```
cd app
./gradlew assembleRelease          # APK in app/build/outputs/apk/release/
./gradlew bundleRelease            # AAB for Google Play in app/build/outputs/bundle/release/
./gradlew testDebugUnitTest        # tests + sample pages in app/app/build/pagine/
```
**Plugins** — see `plugin/compila.sh`. The HMI libraries (`libAppFramework.A.so`,
`libCoreFramework.A.so`) are not in the repository: copy them from your own head unit.

To sign the release with your own key, create `app/keystore.properties` (not in the repository):
```
storeFile=your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```
Without that file the release is signed with the debug key.

## Installing
The APK and the USB-stick zip are in the **Releases**. Install only NBT Plus on the phone: if one of the
old apps (MINI Plus, BMW Plus or Supra Plus) is still there, uninstall it, because two apps at once would
fight over the link with iDrive.

Step-by-step instructions (Italian) in [`chiavetta/LEGGIMI-v20.txt`](chiavetta/LEGGIMI-v20.txt); in short, on the head unit shell:
```
mount -o remount rw /net/hu-omap/fs/sda0
on -f /net/hu-omap/ /bin/sh
sh /mnt/umass*/00-installa.sh          # same for MINI, BMW and Supra
```
Then restart the head unit (hold the power button for 30 seconds). To remove everything: `sh /mnt/umass*/10-togli.sh`.

## Data sources
open-meteo.com · ANSA (RSS feeds) · MIMIT Osservaprezzi carburanti · Autostrade per l'Italia ·
Dipartimento della Protezione Civile (criticality bulletins, CC BY-SA 4.0).
The app reads the data live, on the phone; the repository does not contain it
(except one sample bulletin for the tests).

## License
[PolyForm Noncommercial 1.0.0](LICENSE.md), the same as nbt-aad: free to use, change and share for
non-commercial purposes. Selling it, or bundling it with anything sold, is not permitted.

Required Notice: Copyright (c) 2026 DeathWalkeR - NBT Plus (https://github.com/deathwalker80/idrive-plus)

## Privacy
The app has no accounts, collects no data and sends nothing to any server of ours: [privacy policy](PRIVACY.md).

## Notes
Personal project, not affiliated with MINI, BMW or Toyota: the brand names belong to their owners and only say
which cars it works with. Use at your own risk.
