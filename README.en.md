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
- **Weather** (open-meteo.com; MET Norway when it does not answer) with the **Italian Civil Protection warnings** for the municipality you are in. Updated every hour and at once when you enter another municipality.
  In iDrive the six days (today and the next 5) are chosen with the controller and the page highlights the chosen one; pressing opens the day with maximum, minimum and feels-like, rain (probability and mm), wind and gusts, humidity, UV index, sunrise and sunset, and the day hour by hour (today the next 8 hours, the other days every 3 hours).
- **National news** and **local news** for a chosen city (ANSA RSS feeds). In iDrive they are "in primo piano": the eight news on the right, turning the controller shows the chosen one large on the left (photo, time, title, start of the text); pressing opens it.
- **Fuel stations** nearby with official MIMIT (Osservaprezzi) prices and the brand logo. The station is chosen with the controller; pressing opens its card with address, distance, difference from the nearby average and all its prices.
- **Traffic** nearby (Autostrade per l'Italia).
- **Vehicle status** in iDrive: the summary (range, litres in the tank, odometer, average consumption, the current trip, temperatures) and the **Doors, windows and roof** page with a photo of the car and one state for each door, window, roof, tailgate and bonnet. The photo and its anchor points are set in the app.
- **Where I parked**: when the phone disconnects from the car's Bluetooth, the position is saved.
- The app starts by itself with the car's Bluetooth and stops when you get out.
- **Pages ready before you get in**: about once an hour, even away from the car, weather, news, fuel and traffic update by themselves and stay saved on the phone, so iDrive has them at once (Settings: Yes, Wi-Fi only or No). Until iDrive sends new data, Vehicle status shows the last values read, with their date.
- **Trip**: while driving and then in the parking box, start place, duration, distance, fuel used and left, temperatures and openings (doors, windows, roof). The car data is read by the Meteo plugin inside iDrive (read only) and sent to the app over the car's Wi-Fi: no AAIdrive, no extra Bluetooth link.
- **Trip history**: every finished trip with start and end place, duration, distance, fuel used and km/l; the monthly summary.
- **Refuels**: detected on their own when the tank level goes up, with litres, place and, if you were at a station in the MIMIT list, brand, price and estimated cost.
- **Openings alert**: if you get out with a window, the roof, the tailgate or the bonnet open, the phone shows a notification.
- **My cars**: several cars, each recognised by its Bluetooth name, with its own name in the app (e.g. "Lucy" instead of "MINI12345", display only), fuel and parking spot. They add themselves on first connection, or from the cars paired with the phone.

Data sources are Italian, so the news, fuel and traffic sections are for Italy; the weather works anywhere.

## What it looks like
| In the car (MINI F60, 8.8" screen) | On the phone |
|---|---|
| <img src="docs/idrive_mini.gif" width="300" alt="MINI Connected menu with Weather, News, Local news, Fuel and Traffic"> | <img src="docs/app/home.jpg" width="300" alt="The app home on the phone"> |
| MINI Connected menu and the five pages. [Video (MP4, 30 s)](docs/idrive_mini.mp4) | NBT Plus (MINI Plus in the pictures, it looks the same): car photo with fuel, range, km and consumption, then the sections two per row |

The app sections:

| | | | |
|---|---|---|---|
| <img src="docs/app/in_auto.jpg" width="180" alt="In the car"><br>In the car | <img src="docs/app/stato_vettura.jpg" width="180" alt="Vehicle status"><br>Vehicle status | <img src="docs/app/parcheggio.jpg" width="180" alt="Parking"><br>Parking | <img src="docs/app/meteo.jpg" width="180" alt="Weather"><br>Weather |
| <img src="docs/app/notizie.jpg" width="180" alt="News"><br>News | <img src="docs/app/notizie_locali.jpg" width="180" alt="Local news"><br>Local news | <img src="docs/app/carburanti.jpg" width="180" alt="Fuel"><br>Fuel | <img src="docs/app/traffico.jpg" width="180" alt="Traffic"><br>Traffic |
| <img src="docs/app/impostazioni.jpg" width="180" alt="Settings"><br>Settings | <img src="docs/app/permessi.jpg" width="180" alt="Permissions"><br>Permissions | | |

Addresses, town and windscreen are blurred out; news photos are blurred.

## Layout
```
app/        Android app (Kotlin, no external libraries)
plugin/     source of the six iDrive plugins (C++ without the standard library, ARM/QNX)
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

Step-by-step instructions in [`chiavetta/README-v28.txt`](chiavetta/README-v28.txt); in short, on the head unit shell:
```
mount -o remount rw /net/hu-omap/fs/sda0
on -f /net/hu-omap/ /bin/sh
sh /mnt/umass*/00-installa.sh          # same for MINI, BMW and Supra
```
Then restart the head unit (hold the power button for 30 seconds). To remove everything: `sh /mnt/umass*/10-togli.sh`.

## Data sources
open-meteo.com · MET Norway (CC BY 4.0) · ANSA (RSS feeds) · MIMIT Osservaprezzi carburanti · Autostrade per l'Italia ·
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
