#!/bin/sh
# Removes all the iDrive Plus / NBT Plus plugins (moved to /opt/aa/plugins_off, recoverable). Android Auto is not touched.
# Switch off without removing anything:  echo 1 > /opt/aa/ponte.disable   (+ reboot)
# Run it INSIDE the omap shell (after mount + on -f). Usage:
#   sh /mnt/umass*/10-togli.sh

P=/opt/hmi/ID5/data/ro/Plugins
OFF=/opt/aa/plugins_off
mkdir -p "$OFF"
for N in Meteo Notizie Locali Carburanti Traffico Auto; do
  if [ -d "$P/$N" ]; then
    DEST="$OFF/$N"; if [ -e "$DEST" ]; then DEST="$OFF/${N}_$$"; fi
    if mv "$P/$N" "$DEST"; then echo "OK: $N removed (moved to $DEST)"; else echo "WARNING: cannot move $N - switching them off with /opt/aa/ponte.disable"; echo 1 > /opt/aa/ponte.disable; fi
  else
    echo "$N not present, nothing to do"
  fi
done
echo "Remaining plugins:"; ls "$P"
echo "NOW BY HAND - REBOOT: hold the power button for 30 seconds."
