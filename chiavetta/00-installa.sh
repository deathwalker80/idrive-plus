#!/bin/sh
# iDrive Plus / NBT Plus - USB stick v27 (one for all the apps) - iDrive plugins v12: Weather (with alerts and car
# data for the app), News, Local news, Fuel, Traffic, Vehicle status. Updated by the phone app.
# Android Auto (NbtAad) is not touched.
# The libraries are the same for all the apps: what changes are the "waiting for the phone" pages, with the app
# name, and with mini the Vehicle status and Traffic icons (with the MINIs drawn in).
# Libraries in use by the HMI can't be overwritten: they are renamed (.vecchia_*) and the new one is copied.
# Run it INSIDE the omap shell (after mount + on -f). Usage, depending on the app on your phone:
#   sh /mnt/umass*/00-installa.sh            NBT Plus (Play Store), on MINI, BMW or Supra
#   sh /mnt/umass*/00-installa.sh mini       MINI Plus
#   sh /mnt/umass*/00-installa.sh bmw        BMW Plus
#   sh /mnt/umass*/00-installa.sh supra      Supra Plus

M=${1:-nbt}
case "$M" in
  nbt|mini|bmw|supra) ;;
  *) echo "ERROR: unknown '$M' - nothing (NBT Plus), or mini, bmw, supra"; exit 1 ;;
esac

U=""
for d in /mnt/umass*; do if [ -d "$d" ]; then U=${d#/mnt/}; break; fi; done
if [ -z "$U" ]; then echo "ERROR: umass USB stick not found in /mnt"; exit 1; fi
echo "USB stick: /mnt/$U - waiting pages for: $M"

P=/opt/hmi/ID5/data/ro/Plugins
if [ ! -d "$P/NbtAad" ]; then echo "ERROR: $P/NbtAad missing - are you on the omap shell?"; exit 1; fi

for N in Meteo Notizie Locali Carburanti Traffico Auto; do
  if [ ! -f "/mnt/$U/$N/lib$N.so" ]; then echo "ERROR: /mnt/$U/$N/lib$N.so missing - did you extract the zip to the root?"; exit 1; fi
  if [ ! -f "/mnt/$U/$N/attesa.png" ]; then echo "ERROR: /mnt/$U/$N/attesa.png missing"; exit 1; fi
  if [ "$M" != nbt ] && [ ! -f "/mnt/$U/marche/$M/$N/attesa.png" ]; then echo "ERROR: /mnt/$U/marche/$M/$N/attesa.png missing"; exit 1; fi
done

for N in Meteo Notizie Locali Carburanti Traffico Auto; do
  mkdir -p "$P/$N"
  rm -f "$P/$N"/lib$N.so.vecchia_* 2>/dev/null
  if [ -f "$P/$N/lib$N.so" ]; then
    mv "$P/$N/lib$N.so" "$P/$N/lib$N.so.vecchia_$$" && echo "OK: old lib$N.so renamed (in use until reboot)"
  fi
  cp "/mnt/$U/$N"/* "$P/$N/" || { echo "ERROR: copying $N failed"; exit 1; }
  if [ "$M" != nbt ]; then
    cp "/mnt/$U/marche/$M/$N"/* "$P/$N/" || { echo "ERROR: copying the $M pages of $N failed"; exit 1; }
  fi
  rm -f "/dev/shmem/$N.avvii"
  echo "OK: $N"
done
# v27: the "Meteo prova" test is now the normal Weather: if it is installed it goes to /opt/aa/plugins_off
if [ -d "$P/MeteoProva" ]; then
  mkdir -p /opt/aa/plugins_off
  DEST=/opt/aa/plugins_off/MeteoProva; if [ -e "$DEST" ]; then DEST="${DEST}_$$"; fi
  if mv "$P/MeteoProva" "$DEST"; then echo "OK: Meteo prova removed (now it is Weather), moved to $DEST"
  else echo "WARNING: cannot move $P/MeteoProva - remove it by hand"; fi
fi
if [ -e /opt/aa/ponte.disable ]; then echo "WARNING: /opt/aa/ponte.disable exists, the plugins will stay off"; fi
if [ -e /opt/aa/auto.disable ]; then echo "NOTE: /opt/aa/auto.disable exists, Weather will not read the car data"; fi

echo "===== ACTIVE PLUGINS ====="
ls "$P"
echo ""
echo "########## DONE - NOW REBOOT: hold the power button for 30 seconds ##########"
echo "Report: cat /dev/shmem/Meteo.log /dev/shmem/Notizie.log /dev/shmem/Locali.log /dev/shmem/Carburanti.log /dev/shmem/Traffico.log /dev/shmem/Auto.log"
echo "Car data: grep auto: /dev/shmem/Meteo.log"
