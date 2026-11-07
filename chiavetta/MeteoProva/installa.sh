#!/bin/sh
# PROVA "Meteo prova": Meteo coi giorni selezionabili con la manopola. Plugin a parte: il Meteo normale e gli altri
# plugin non vengono toccati. Si toglie con togli.sh.
# Da lanciare DENTRO la shell di omap (dopo  mount -o remount rw /net/hu-omap/fs/sda0  e  on -f /net/hu-omap/ /bin/sh):
#   sh /mnt/umass*/MeteoProva/installa.sh

U=""
for d in /mnt/umass*; do if [ -d "$d/MeteoProva" ]; then U=$d; break; fi; done
if [ -z "$U" ]; then echo "ERRORE: cartella MeteoProva non trovata sulla chiavetta"; exit 1; fi
P=/opt/hmi/ID5/data/ro/Plugins
if [ ! -d "$P/NbtAad" ]; then echo "ERRORE: $P/NbtAad non c'e' - sei sulla shell di omap?"; exit 1; fi
S="$U/MeteoProva"
for f in libMeteoProva.so MeteoProva.xml MeteoProva.manifest MeteoProva_ALL_Images.zip MeteoProva_ALL_Texts.zip attesa.png attesa.txt; do
  if [ ! -f "$S/$f" ]; then echo "ERRORE: manca $S/$f"; exit 1; fi
done
mkdir -p "$P/MeteoProva" || { echo "ERRORE: non riesco a scrivere in $P (hai fatto il mount rw?)"; exit 1; }
rm -f "$P/MeteoProva"/libMeteoProva.so.vecchia_* 2>/dev/null
if [ -f "$P/MeteoProva/libMeteoProva.so" ]; then mv "$P/MeteoProva/libMeteoProva.so" "$P/MeteoProva/libMeteoProva.so.vecchia_$$"; fi
for f in libMeteoProva.so MeteoProva.xml MeteoProva.manifest MeteoProva_ALL_Images.zip MeteoProva_ALL_Texts.zip attesa.png attesa.txt; do
  cp "$S/$f" "$P/MeteoProva/" || { echo "ERRORE: copia di $f fallita"; exit 1; }
done
rm -f /dev/shmem/MeteoProva.avvii
echo "OK: Meteo prova installato in $P/MeteoProva"
echo "########## ORA RIAVVIA: tasto power premuto 30 secondi ##########"
echo "Poi: MINI Connected -> Meteo prova. Dopo la prova:  cat /dev/shmem/MeteoProva.log"
