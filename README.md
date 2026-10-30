# NBT Plus

🇬🇧 [Read in English](README.en.md)

Meteo, notizie, notizie locali, carburanti e traffico **dentro l'iDrive** delle auto con testata
**NBT EVO (ID6)**, senza modificare nessuna centralina e senza toccare Android Auto.

La testata non ha internet suo: i dati li scarica l'app del telefono (connessione dati del telefono)
e li passa all'iDrive sul Wi-Fi di Android Auto wireless. I plugin nell'iDrive chiedono le pagine pronte
all'app (porta 8765) e si aggiornano da soli.

Un'app sola, **NBT Plus**, per tutte e tre le auto. La marca si riconosce da sola dal nome Bluetooth
dell'auto (oppure si sceglie nelle impostazioni) e cambia il colore dell'app e il nome dell'auto nei testi:

| Auto | Colore |
|---|---|
| MINI (F5x / F60) | azzurro |
| BMW con NBT EVO | giallo |
| Toyota GR Supra (J29) | rosso |

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
app/        app Android (Kotlin, nessuna libreria esterna)
plugin/     sorgente dei cinque plugin iDrive (C++ senza libreria standard, ARM/QNX)
chiavetta/  script di installazione, descrizioni dei plugin e pagine "in attesa"
```

## Compilare
**App**
```
cd app
./gradlew assembleRelease          # APK in app/build/outputs/apk/release/
./gradlew bundleRelease            # AAB per Google Play in app/build/outputs/bundle/release/
./gradlew testDebugUnitTest        # test + pagine di prova in app/app/build/pagine/
```
**Plugin** — vedi `plugin/compila.sh`. Le librerie dell'HMI (`libAppFramework.A.so`,
`libCoreFramework.A.so`) non sono nel repository: vanno copiate dalla propria testata.

Per firmare la release con la propria chiave serve `app/keystore.properties` (non è nel repository):
```
storeFile=nome-della-chiave.jks
storePassword=...
keyAlias=...
keyPassword=...
```
Senza quel file la release si firma con la chiave di debug.

## Installare
L'APK e lo zip della chiavetta sono nelle **Release**. Sul telefono va installata solo NBT Plus:
se c'è ancora una delle vecchie app (MINI Plus, BMW Plus o Supra Plus) va disinstallata, perché
due app insieme si contenderebbero il collegamento con l'iDrive.

Istruzioni passo passo in [`chiavetta/LEGGIMI-v20.txt`](chiavetta/LEGGIMI-v20.txt); in breve, sulla shell della testata:
```
mount -o remount rw /net/hu-omap/fs/sda0
on -f /net/hu-omap/ /bin/sh
sh /mnt/umass*/00-installa.sh          # uguale per MINI, BMW e Supra
```
Per togliere tutto: `sh /mnt/umass*/10-togli.sh`.

## Fonti dei dati
open-meteo.com · ANSA (feed RSS) · MIMIT Osservaprezzi carburanti · Autostrade per l'Italia ·
Dipartimento della Protezione Civile (bollettini di criticità, CC BY-SA 4.0).
I dati vengono letti dall'app al momento, sul telefono; il repository non li contiene
(salvo un bollettino di esempio per i test).

## Licenza
[PolyForm Noncommercial 1.0.0](LICENSE.md), la stessa di nbt-aad: uso, modifiche e condivisione liberi
per scopi non commerciali. Venderlo, o includerlo in qualcosa che si vende, non è permesso.

Required Notice: Copyright (c) 2026 DeathWalkeR - NBT Plus (https://github.com/deathwalker80/idrive-plus)

## Privacy
L'app non ha account, non raccoglie dati e non li manda a nessun server nostro: [informativa](PRIVACY.md).

## Note
Progetto personale, non collegato a MINI, BMW o Toyota: i nomi delle marche sono dei rispettivi proprietari
e indicano solo con quali auto funziona. Si usa a proprio rischio.
