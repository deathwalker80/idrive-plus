#!/bin/sh
# Toglie la PROVA "Meteo prova" (spostata in /opt/aa/plugins_off, recuperabile). Gli altri plugin non vengono toccati.
# Da lanciare DENTRO la shell di omap (dopo mount + on -f):
#   sh /mnt/umass*/MeteoProva/togli.sh

P=/opt/hmi/ID5/data/ro/Plugins
OFF=/opt/aa/plugins_off
if [ ! -d "$P/MeteoProva" ]; then echo "Meteo prova non e' installato, niente da fare"; exit 0; fi
mkdir -p "$OFF"
DEST="$OFF/MeteoProva"; if [ -e "$DEST" ]; then DEST="$OFF/MeteoProva_$$"; fi
if mv "$P/MeteoProva" "$DEST"; then echo "OK: Meteo prova tolto (spostato in $DEST)"; else echo "ERRORE: non riesco a spostarlo"; exit 1; fi
echo "ORA RIAVVIA: tasto power premuto 30 secondi."
