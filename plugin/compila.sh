#!/bin/sh
# Compila i cinque plugin iDrive (ARM, QNX) in plugin/out/: libMeteo.so, libNotizie.so, libLocali.so,
# libCarburanti.so, libTraffico.so. Serve clang/lld 18 (pacchetti clang e lld su Ubuntu 24.04).
#
# Le due librerie dell'HMI, libAppFramework.A.so e libCoreFramework.A.so, NON sono nel repository
# (sono della testata, non si possono ridistribuire): copiale dalla TUA head unit in plugin/link/
#   (sulla testata le trovi con:  find / -name "lib*Framework.A.so")
# libc.so.3 e libsocket.so.3 invece si costruiscono qui da stub vuoti: servono solo al collegamento,
# in auto il loader usa quelle vere della testata.
set -e
cd "$(dirname "$0")"
L=link
for f in libAppFramework.A.so libCoreFramework.A.so; do
  [ -f "$L/$f" ] || { echo "ERRORE: manca $L/$f - copiala dalla tua head unit (vedi sopra)"; exit 1; }
done
mkdir -p out
ARM="--target=armv7a-unknown-linux-gnueabi -mfloat-abi=soft -marm -O2 -fPIC -ffreestanding -fno-builtin"
for n in c socket; do
  clang $ARM -nostdlib -shared -fuse-ld=lld -Wl,-soname,lib$n.so.3 -o out/lib$n.so.3 $L/lib${n}_stub.c
done
for P in METEO:Meteo CARBURANTI:Carburanti TRAFFICO:Traffico NOTIZIE:Notizie LOCALI:Locali; do
  D=${P%%:*}; N=${P##*:}
  clang $ARM -fno-exceptions -fno-rtti -fno-threadsafe-statics -fno-stack-protector -fvisibility=hidden \
    -DPLUGIN_$D -c ponte_plugin.cpp -o out/$N.o
  ld.lld -shared --hash-style=sysv -z norelro -z nognustack --no-rosegment -z max-page-size=0x1000 --no-undefined \
    -soname lib$N.so -o out/lib$N.so out/$N.o $L/libAppFramework.A.so $L/libCoreFramework.A.so out/libsocket.so.3 out/libc.so.3
  rm -f out/$N.o
  echo "OK: out/lib$N.so"
done
echo "Copia ogni out/lib<Nome>.so nella cartella <Nome> della chiavetta (chiavetta/<Nome>/)."
