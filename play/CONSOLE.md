# NBT Plus — risposte per la Play Console

Testi pronti da incollare nei moduli della Play Console. I testi della scheda sono in `listings/`
(it-IT e en-US), le grafiche nella release.

## Dati dell'app
- **Nome dell'app:** NBT Plus
- **Pacchetto:** `it.deathwalker.nbtplus`
- **Categoria:** Auto e veicoli
- **Gratuita**, senza pubblicità, senza acquisti in-app
- **Informativa sulla privacy:** https://github.com/deathwalker80/idrive-plus/blob/main/PRIVACY.md
- **Pubblico di destinazione:** 18 anni e oltre (l'app è per chi guida)
- **Classificazione dei contenuti:** questionario IARC con "Utilità", tutte le risposte No

## Sicurezza dei dati
- La tua app raccoglie o condivide dati utente? **Sì**
- Tutti i dati sono criptati in transito? **Sì** (solo HTTPS)
- Gli utenti possono chiedere la cancellazione? **No**: non ci sono account né dati sul server; si cancella
  tutto disinstallando l'app (spiegarlo nel campo note, se richiesto)

| Tipo di dati | Raccolti | Condivisi | Elaborati in modo temporaneo | Obbligatorio | Finalità |
|---|---|---|---|---|---|
| Posizione approssimativa | Sì | Sì | Sì | No (facoltativo) | Funzionalità dell'app |
| Posizione precisa | Sì | Sì | Sì | No (facoltativo) | Funzionalità dell'app |

"Condivisi" perché le coordinate vanno a servizi di terzi (open-meteo.com, MIMIT) per avere i dati della zona.
Nessun altro tipo di dati: niente informazioni personali, foto, file, contatti, identificativi del dispositivo,
cronologia o statistiche.

## Autorizzazioni: posizione in background
**Funzionalità che la usa:** Dove ho parcheggiato; meteo, carburanti e traffico della zona mentre si guida.

**Descrizione da incollare:**
> NBT Plus mostra sullo schermo della testata dell'auto (NBT EVO) il meteo, i distributori e il traffico della
> zona in cui si trova l'auto, e salva dove l'utente ha parcheggiato. L'app si accende da sola quando il telefono
> si collega al Bluetooth dell'auto e lavora con lo schermo del telefono spento, in tasca: per questo la posizione
> serve anche quando l'app non è aperta. Al momento in cui il telefono si scollega dal Bluetooth dell'auto, l'app
> legge l'ultima posizione nota e la salva come posto del parcheggio, con una notifica "Portami all'auto".
> Senza posizione in background queste funzioni non possono funzionare mentre si guida. Prima della richiesta
> l'app mostra un'informativa che spiega quali dati raccoglie, perché e a chi li manda.

**Video (link YouTube non in elenco, 30-60 s):**
1. aprire NBT Plus, scheda Permessi, toccare "Consenti" su "Posizione anche in auto";
2. mostrare l'informativa "Uso della posizione" e toccare "Continua";
3. nella pagina di Android scegliere "Consenti sempre";
4. in auto: telefono in tasca, il meteo e i carburanti della zona compaiono sullo schermo della testata;
5. scendere dall'auto: compare la notifica del parcheggio con "Portami alla MINI".

## Autorizzazioni: servizio in primo piano
- **Tipo:** Dispositivo collegato (`connectedDevice`)
- **Descrizione da incollare:**
> Mentre il telefono è collegato all'auto (Bluetooth e Wi-Fi di Android Auto wireless), NBT Plus tiene acceso un
> piccolo server locale da cui la testata dell'auto chiede le pagine di meteo, notizie, carburanti e traffico.
> Il servizio parte quando il telefono si collega al Bluetooth dell'auto e si ferma quando si scollega.
> Se venisse rimandato o interrotto, lo schermo dell'auto resterebbe sulla pagina "in attesa del telefono".
- **Video:** lo stesso di sopra va bene (si vede la notifica del servizio e le pagine in auto).

## Accesso all'app (istruzioni per la revisione)
> NBT Plus serve un'auto con testata NBT EVO modificata (plugin installati dalla chiavetta USB, vedi
> github.com/deathwalker80/idrive-plus), quindi la parte in auto non si può provare senza l'auto. La parte sul
> telefono si prova così, senza account:
> 1. aprire l'app e concedere i permessi dalla scheda "Permessi";
> 2. toccare "Accendi", poi "Aggiorna tutto";
> 3. dopo qualche secondo nelle schede Meteo, Notizie, Notizie locali, Carburanti e Traffico compaiono le
>    anteprime delle pagine che l'auto mostra sullo schermo;
> 4. nelle Impostazioni si sceglie la marca dell'auto (MINI, BMW, Supra) e cambiano i colori.
> Notizie, carburanti e traffico usano fonti italiane: fuori dall'Italia le schede possono restare vuote.
> Il video allegato mostra l'app in funzione in auto.

## Pubblicità
- La tua app contiene annunci? **No**

## Test chiuso (account personale nuovo)
- Traccia **Test chiuso**, almeno **12 tester** iscritti per **14 giorni di fila**, poi "Richiedi l'accesso in produzione".
- Lista tester: indirizzi Gmail, o un Gruppo Google.
