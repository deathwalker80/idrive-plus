# iDrive Plus — MINI Plus · BMW Plus · Supra Plus

Meteo, notizie, notizie locali, carburanti e traffico **dentro l'iDrive** delle auto con testata
**NBT EVO (ID6)**, senza modificare nessuna centralina e senza toccare Android Auto.

La testata non ha internet suo: i dati li scarica l'app del telefono (connessione dati del telefono)
e li passa all'iDrive sul Wi-Fi di Android Auto wireless. I plugin nell'iDrive chiedono le pagine pronte
all'app (porta 8765) e si aggiornano da soli.

| Auto | App | Colore |
|---|---|---|
| MINI (F5x / F60) | MINI Plus | azzurro |
| BMW con NBT EVO | BMW Plus | giallo |
| Toyota GR Supra (J29) | Supra Plus | rosso |

Stesso codice per tutte e tre: cambiano solo nome, icona, colore e il nome dell'auto nei testi.

> **Stato:** provato sulla MINI F60 (schermo 8,8"). Su BMW e Supra resta da verificare sull'auto vera
> il menu dei servizi online in cui compaiono le voci, il nome Bluetooth dell'auto e, per lo schermo
> da 10,25" (1440×540), la calibrazione delle pagine.

## Cosa serve
- Testata NBT EVO con accesso **SSH root**.
- **Android Auto wireless** sulla testata ([nbt-aad di Sq00ky](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto)):
  il telefono deve stare sul Wi-Fi dell'auto.
- Telefono Android 8 o successivo.

## Funzioni
- **Meteo** (open-meteo.com) con le **allerte della Protezione Civile** per il comune in cui ti trovi.
- **Notizie** nazionali e **notizie locali** della città scelta (feed RSS ANSA), con miniature.
- **Carburanti** vicini con prezzi ufficiali MIMIT (Osservaprezzi) e logo del marchio.
- **Traffico** vicino (Autostrade per l'Italia).
- **Dove ho parcheggiato**: quando il telefono si scollega dal Bluetooth dell'auto salva la posizione.
- L'app si accende da sola col Bluetooth dell'auto e si spegne quando scendi.

## Struttura
```
app/        app Android (Kotlin, nessuna libreria esterna) - flavor mini / bmw / supra
plugin/     sorgente dei cinque plugin iDrive (C++ senza libreria standard, ARM/QNX)
chiavetta/  script di installazione, descrizioni dei plugin, pagine "in attesa" per marca
```

## Compilare
**App**
```
cd app
./gradlew assembleRelease          # app/build/outputs/apk/{mini,bmw,supra}/release/
./gradlew testMiniDebugUnitTest    # test + pagine di prova in app/app/build/pagine/mini/
```
**Plugin** — vedi `plugin/compila.sh`. Le librerie dell'HMI (`libAppFramework.A.so`,
`libCoreFramework.A.so`) non sono nel repository: vanno copiate dalla propria testata.

## Installare
Le APK e lo zip della chiavetta sono nelle **Release**. Istruzioni passo passo in
[`chiavetta/LEGGIMI-v18.txt`](chiavetta/LEGGIMI-v18.txt); in breve, sulla shell della testata:
```
mount -o remount rw /net/hu-omap/fs/sda0
on -f /net/hu-omap/ /bin/sh
sh /mnt/umass*/00-installa.sh mini     # oppure bmw, oppure supra
```
Per togliere tutto: `sh /mnt/umass*/10-togli.sh`.

## Fonti dei dati
open-meteo.com · ANSA (feed RSS) · MIMIT Osservaprezzi carburanti · Autostrade per l'Italia ·
Dipartimento della Protezione Civile (bollettini di criticità, CC BY-SA 4.0).
I dati vengono letti dall'app al momento, sul telefono; il repository non li contiene
(salvo un bollettino di esempio per i test).

## Note
Progetto personale, non collegato a MINI, BMW o Toyota: i nomi delle marche sono dei rispettivi proprietari
e indicano solo con quali auto funziona. Si usa a proprio rischio.
