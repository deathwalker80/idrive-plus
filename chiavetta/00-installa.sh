#!/bin/sh
# MINI Plus / BMW Plus / Supra Plus v18 - plugin iDrive v6: Meteo (con allerte), Notizie, Notizie locali,
# Carburanti, Traffico. Aggiornati dall'app del telefono. Android Auto (NbtAad) non viene toccato.
# La chiavetta e' la stessa per tutte e tre le auto: cambia solo la marca scritta nel comando
# (pagina e testo "in attesa del telefono" con il nome della app giusta).
# Le librerie in uso dall'HMI non si possono sovrascrivere: si rinominano (.vecchia_*) e si copia la nuova.
# Da lanciare DENTRO la shell di omap (dopo mount + on -f). Invocazione:
#   sh /mnt/umass*/00-installa.sh mini      (oppure bmw, oppure supra; senza niente vale mini)

M=${1:-mini}
case "$M" in
  mini|bmw|supra) ;;
  *) echo "ERRORE: marca '$M' sconosciuta - scrivi mini, bmw oppure supra"; exit 1 ;;
esac

U=""
for d in /mnt/umass*; do if [ -d "$d" ]; then U=${d#/mnt/}; break; fi; done
if [ -z "$U" ]; then echo "ERRORE: chiavetta umass non trovata in /mnt"; exit 1; fi
echo "Chiavetta: /mnt/$U - marca: $M"

P=/opt/hmi/ID5/data/ro/Plugins
if [ ! -d "$P/NbtAad" ]; then echo "ERRORE: $P/NbtAad non c'e' - sei sulla shell di omap?"; exit 1; fi

for N in Meteo Notizie Locali Carburanti Traffico; do
  if [ ! -f "/mnt/$U/$N/lib$N.so" ]; then echo "ERRORE: /mnt/$U/$N/lib$N.so non c'e' - hai estratto lo zip nella radice?"; exit 1; fi
  if [ ! -f "/mnt/$U/marche/$M/$N/attesa.png" ]; then echo "ERRORE: /mnt/$U/marche/$M/$N/attesa.png non c'e'"; exit 1; fi
done

for N in Meteo Notizie Locali Carburanti Traffico; do
  mkdir -p "$P/$N"
  rm -f "$P/$N"/lib$N.so.vecchia_* 2>/dev/null
  if [ -f "$P/$N/lib$N.so" ]; then
    mv "$P/$N/lib$N.so" "$P/$N/lib$N.so.vecchia_$$" && echo "OK: lib$N.so vecchia rinominata (in uso fino al riavvio)"
  fi
  cp "/mnt/$U/$N"/* "$P/$N/" || { echo "ERRORE: copia $N fallita"; exit 1; }
  cp "/mnt/$U/marche/$M/$N"/* "$P/$N/" || { echo "ERRORE: copia dei file $M di $N fallita"; exit 1; }
  rm -f "/dev/shmem/$N.avvii"
  echo "OK: $N"
done
if [ -e /opt/aa/ponte.disable ]; then echo "ATTENZIONE: esiste /opt/aa/ponte.disable, i plugin resteranno spenti"; fi

echo "===== PLUGIN ATTIVI ====="
ls "$P"
echo ""
echo "########## FATTO - ORA RIAVVIA: tasto power premuto 30 secondi ##########"
echo "Resoconto: cat /dev/shmem/Meteo.log /dev/shmem/Notizie.log /dev/shmem/Locali.log /dev/shmem/Carburanti.log /dev/shmem/Traffico.log"
