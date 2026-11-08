iDrive Plus / NBT Plus - USB stick v25 (iDrive plugins v10)
===========================================================
WHAT CHANGES FROM v24 (iDrive slowdowns, issue #1)
 - The plugins share the phone's address (/dev/shmem/idriveponte.rete): one finds it, the others use it.
   The ARP table is read by a single plugin at most every 15 s (before: all six every 20 s) and 172.16.224.x
   is scanned at most every 5 minutes (before: each of the six, nonstop, when the phone wasn't there).
 - With no phone or no app, retries get rarer: 20 s, 40, 80, 160, then every 5 minutes; as soon as one
   plugin finds the phone, the others start right away.
 - Staggered start (0-18 s) and images downloaded one at a time across all plugins
   (/dev/shmem/idriveponte.turno): less traffic all at once on the Wi-Fi Android Auto also uses, and fewer
   images for the iDrive to decode at the same time.
 - Pages that didn't arrive are retried at 10, 20, 40, 80 s instead of always every 10 s.
 - A response cut off halfway (shorter than its Content-Length) is discarded instead of reaching the screen.
 - The "waiting" page is loaded only in the first row of each list (before: in all eight).
 - With NBT Plus 3.23 / MINI, BMW, Supra Plus 2.28 the Vehicle status summary is redrawn at most every 30 s
   (before: 10 s).
 - In the log, when the phone isn't in the ARP table: "scansione di 172.16.224.x".

WHAT CHANGED IN v24 FROM v23
 - "Car status" becomes "Vehicle status", with two entries: Summary (the previous page) and "Doors, windows
   and roof": the photo of the car with the 11 callouts, one state per row (green dot closed, orange open).
   The photo and the points are chosen in the app (Vehicle status tab); MINI Plus already has a MINI photo.
 - Pages are downloaded again only when they change: the doors page only if something changes.
 - Meteo sends all the car data again when the phone app restarts (before, values that don't change, like
   closed doors and kilometres with the engine off, stayed empty).
 - New icons for Weather, News, Local news and Fuel; with "mini" also Vehicle status and Traffic with the
   MINIs drawn in (with nothing, bmw and supra the generic icons stay).
 - Requires NBT Plus 3.14 or MINI/BMW/Supra Plus 2.18 or later (with earlier ones Vehicle status keeps waiting).

ONE USB STICK FOR ALL THE APPS
 - The stick is the same for NBT Plus (Play Store) and for MINI Plus, BMW Plus and Supra Plus.
   The plugins are the same; only the app name written on the "waiting for the phone" pages changes,
   and it is chosen in the install command (see CAR, step 3).

ALREADY THERE SINCE v20 / v22
 - "Car status" entry in the online services menu (next to Weather, News, Fuel...): range, litres in the
   tank, kilometres, average consumption, the current trip, outside and inside temperature, windows, roof,
   tailgate and bonnet. The page is drawn by the app with the data read by the Meteo plugin.
   With /opt/aa/auto.disable the page stays "waiting for the car data".
 - In the app: trip history, refuels detected from the tank level (with the price at the nearest MIMIT
   station) and a phone alert if you get out with a window, the roof, the tailgate or the bonnet open.
 - The Meteo plugin reads the car data (kilometres, fuel, average consumption, outside and inside temperature,
   doors, tailgate, bonnet, windows and roof) and sends it to the app over the car's Wi-Fi. Read only, the
   same way NbtAad reads the gear; no AAIdrive and no extra Bluetooth connection.
 - After the first trip:  grep auto: /dev/shmem/Meteo.log  shows the first values received from the car
   (or the Meteo.log file on the stick, if it is plugged in).

PHONE (install ONLY ONE: two apps together would fight over the link with the iDrive)
 - NBT Plus:   NBTPlus-3.23-release.apk (or from the Play Store), for MINI, BMW and Supra
 - MINI Plus:  MINIPlus-2.28-release.apk  (installs over 2.6 and later, keeps the settings)
 - BMW Plus:   BMWPlus-2.28-release.apk
 - Supra Plus: SupraPlus-2.28-release.apk

CAR
 0. Extract EVERYTHING in this zip to the ROOT of the USB stick (overwrite).
 1. PuTTY: host 169.254.199.99  port 22  user root
 2.   mount -o remount rw /net/hu-omap/fs/sda0
      on -f /net/hu-omap/ /bin/sh
 3.   depending on the app on your phone:
        NBT Plus:    sh /mnt/umass*/00-installa.sh
        MINI Plus:   sh /mnt/umass*/00-installa.sh mini
        BMW Plus:    sh /mnt/umass*/00-installa.sh bmw
        Supra Plus:  sh /mnt/umass*/00-installa.sh supra
 4. Reboot: hold the power button for 30 seconds.

BMW AND SUPRA: STILL TO BE CHECKED ON A REAL CAR
 - The entries appear in the online services menu (MINI Connected / ConnectedDrive / Toyota Supra Connect):
   tested on the MINI; on BMW and Supra it still has to be checked that they are there.
 - 10.25" screen (1440x540, many BMWs): the pages are drawn for 1280x480 and need recalibrating.
 - The car's Bluetooth name: if the app doesn't switch on by itself, the exact name has to be read.

REMOVING
 - sh /mnt/umass*/10-togli.sh  (+ reboot): removes all six plugins, Vehicle status included
 - switch off without removing: echo 1 > /opt/aa/ponte.disable  (+ reboot)
 - car data only: echo 1 > /opt/aa/auto.disable  (+ reboot); to switch it back on: rm /opt/aa/auto.disable
 Android Auto (NbtAad) is never touched.
