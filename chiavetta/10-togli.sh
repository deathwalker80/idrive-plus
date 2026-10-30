#!/bin/sh
# Toglie tutti i plugin di NBT Plus (spostati in /opt/aa/plugins_off, recuperabili). Android Auto non viene toccato.
# Spegnimento senza togliere niente:  echo 1 > /opt/aa/ponte.disable   (+ riavvio)
# Da lanciare DENTRO la shell di omap (dopo mount + on -f). Invocazione:
#   sh /mnt/umass*/10-togli.sh

P=/opt/hmi/ID5/data/ro/Plugins
OFF=/opt/aa/plugins_off
mkdir -p "$OFF"
for N in Meteo Notizie Locali Carburanti Traffico; do
  if [ -d "$P/$N" ]; then
    DEST="$OFF/$N"; if [ -e "$DEST" ]; then DEST="$OFF/${N}_$$"; fi
    if mv "$P/$N" "$DEST"; then echo "OK: $N tolto (spostato in $DEST)"; else echo "ATTENZIONE: non riesco a spostare $N - li spengo con /opt/aa/ponte.disable"; echo 1 > /opt/aa/ponte.disable; fi
  else
    echo "$N non presente, niente da fare"
  fi
done
echo "Plugin rimasti:"; ls "$P"
echo "ORA A MANO - RIAVVIA: tieni premuto il tasto power 30 secondi."
