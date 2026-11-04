#!/bin/sh
# iDrive Plus / NBT Plus - chiavetta v24 (unica per tutte le app) - plugin iDrive v9: Meteo (con allerte e dati
# dell'auto per l'app), Notizie, Notizie locali, Carburanti, Traffico, Stato vettura. Aggiornati dall'app del telefono.
# Android Auto (NbtAad) non viene toccato.
# Le librerie sono le stesse per tutte le app: cambiano le pagine "in attesa del telefono", col nome dell'app, e con
# mini le icone di Stato vettura e Traffico (con le MINI disegnate).
# Le librerie in uso dall'HMI non si possono sovrascrivere: si rinominano (.vecchia_*) e si copia la nuova.
# Da lanciare DENTRO la shell di omap (dopo mount + on -f). Invocazione, secondo l'app che hai sul telefono:
#   sh /mnt/umass*/00-installa.sh            NBT Plus (Play Store), su MINI, BMW o Supra
#   sh /mnt/umass*/00-installa.sh mini       MINI Plus
#   sh /mnt/umass*/00-installa.sh bmw        BMW Plus
#   sh /mnt/umass*/00-installa.sh supra      Supra Plus

M=${1:-nbt}
case "$M" in
  nbt|mini|bmw|supra) ;;
  *) echo "ERRORE: '$M' sconosciuto - niente (NBT Plus), oppure mini, bmw, supra"; exit 1 ;;
esac

U=""
for d in /mnt/umass*; do if [ -d "$d" ]; then U=${d#/mnt/}; break; fi; done
if [ -z "$U" ]; then echo "ERRORE: chiavetta umass non trovata in /mnt"; exit 1; fi
echo "Chiavetta: /mnt/$U - pagine di attesa per: $M"

P=/opt/hmi/ID5/data/ro/Plugins
if [ ! -d "$P/NbtAad" ]; then echo "ERRORE: $P/NbtAad non c'e' - sei sulla shell di omap?"; exit 1; fi

for N in Meteo Notizie Locali Carburanti Traffico Auto; do
  if [ ! -f "/mnt/$U/$N/lib$N.so" ]; then echo "ERRORE: /mnt/$U/$N/lib$N.so non c'e' - hai estratto lo zip nella radice?"; exit 1; fi
  if [ ! -f "/mnt/$U/$N/attesa.png" ]; then echo "ERRORE: /mnt/$U/$N/attesa.png non c'e'"; exit 1; fi
  if [ "$M" != nbt ] && [ ! -f "/mnt/$U/marche/$M/$N/attesa.png" ]; then echo "ERRORE: /mnt/$U/marche/$M/$N/attesa.png non c'e'"; exit 1; fi
done

for N in Meteo Notizie Locali Carburanti Traffico Auto; do
  mkdir -p "$P/$N"
  rm -f "$P/$N"/lib$N.so.vecchia_* 2>/dev/null
  if [ -f "$P/$N/lib$N.so" ]; then
    mv "$P/$N/lib$N.so" "$P/$N/lib$N.so.vecchia_$$" && echo "OK: lib$N.so vecchia rinominata (in uso fino al riavvio)"
  fi
  cp "/mnt/$U/$N"/* "$P/$N/" || { echo "ERRORE: copia $N fallita"; exit 1; }
  if [ "$M" != nbt ]; then
    cp "/mnt/$U/marche/$M/$N"/* "$P/$N/" || { echo "ERRORE: copia delle pagine $M di $N fallita"; exit 1; }
  fi
  rm -f "/dev/shmem/$N.avvii"
  echo "OK: $N"
done
if [ -e /opt/aa/ponte.disable ]; then echo "ATTENZIONE: esiste /opt/aa/ponte.disable, i plugin resteranno spenti"; fi
if [ -e /opt/aa/auto.disable ]; then echo "NOTA: esiste /opt/aa/auto.disable, il Meteo non leggera' i dati dell'auto"; fi

echo "===== PLUGIN ATTIVI ====="
ls "$P"
echo ""
echo "########## FATTO - ORA RIAVVIA: tasto power premuto 30 secondi ##########"
echo "Resoconto: cat /dev/shmem/Meteo.log /dev/shmem/Notizie.log /dev/shmem/Locali.log /dev/shmem/Carburanti.log /dev/shmem/Traffico.log /dev/shmem/Auto.log"
echo "Dati dell'auto: grep auto: /dev/shmem/Meteo.log"
