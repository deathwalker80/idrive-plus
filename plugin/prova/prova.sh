#!/bin/sh
# Prova sul PC (Linux x86) della logica dei plugin, con un finto framework iDrive e un finto telefono.
# Prima genera le pagine col test dell'app:  cd app && ./gradlew testMiniDebugUnitTest
set -e
cd "$(dirname "$0")"
python3 finto_telefono.py & T=$!
trap 'kill $T' EXIT
sleep 1
for P in METEO NOTIZIE LOCALI CARBURANTI; do
  clang++ -O1 -fno-exceptions -fno-rtti -DPROVA_HOST -DPLUGIN_$P prova_host.cpp -o /tmp/prova_$P
  echo "===== $P"; /tmp/prova_$P
done
