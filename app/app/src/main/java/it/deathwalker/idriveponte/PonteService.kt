package it.deathwalker.idriveponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlin.math.max

/**
 * Servizio in primo piano di NBT Plus (notifica minima, silenziosa).
 * - tiene acceso il server HTTP per l'iDrive;
 * - quando il telefono e' sul Wi-Fi della macchina (o l'iDrive ha chiesto qualcosa da poco)
 *   scarica i dati con la connessione del telefono e prepara le pagine, ognuna col suo ritmo:
 *   meteo e notizie ogni 10 minuti, traffico ogni 5, carburanti ogni 30 (o subito se ti sposti di 3 km),
 *   notizie locali ogni 15, allerte della Protezione Civile ogni 3 ore (Preparazione); la pagina Stato auto
 *   quando arrivano dati nuovi dall'iDrive (al massimo ogni 30 secondi).
 * - parte con le ultime pagine salvate (Cache), preparate anche lontano dall'auto (PreparaJob), e salva le nuove.
 * Lo accende il Bluetooth dell'auto (AutoBtReceiver) o il tasto nell'app; si spegne quando l'auto si scollega.
 */
class PonteService : Service() {

    companion object {
        const val AZIONE_AVVIA = "it.deathwalker.idriveponte.AVVIA"
        const val AZIONE_AGGIORNA = "it.deathwalker.idriveponte.AGGIORNA"
        const val EXTRA_DA = "da"
        private const val CANALE = "ponte"
        private const val ID_NOTIFICA = 1
        private const val MIN = 60 * 1000L
        private const val RICHIESTA_RECENTE_MS = 15 * MIN

        fun avvia(ctx: Context, da: String) {
            val i = Intent(ctx, PonteService::class.java).setAction(AZIONE_AVVIA).putExtra(EXTRA_DA, da)
            ctx.startForegroundService(i)
        }

        fun aggiornaOra(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PonteService::class.java).setAction(AZIONE_AGGIORNA).putExtra(EXTRA_DA, "a mano"))
        }

        fun ferma(ctx: Context) {
            ctx.stopService(Intent(ctx, PonteService::class.java))
        }
    }

    private var server: Server? = null
    private var lavoro: Thread? = null
    @Volatile private var attivo = false
    @Volatile private var forza = false
    private val sveglia = Object()
    private var testoNotifica = ""
    private val completando = HashSet<Long>()
    private var firmaAuto = ""
    private var autoDisegnata = 0L
    private var firmaPorte = ""
    private var porteDisegnate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Marca.carica(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        creaCanale()
        val da = intent?.getStringExtra(EXTRA_DA) ?: "riavvio di Android"
        if (Stato.avviatoDa.isEmpty() || intent?.action == AZIONE_AVVIA) Stato.avviatoDa = da
        val n = notifica("In attesa del Wi-Fi della macchina")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIFICA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID_NOTIFICA, n)

        if (!attivo) {
            attivo = true
            Stato.servizioAttivo = true
            // le ultime pagine salvate: l'iDrive le ha subito, intanto si scaricano quelle nuove
            Cache.carica(this)
            // i dati dell'auto arrivano dal plugin Meteo dell'iDrive e vanno nel viaggio dell'auto attuale
            DatiAuto.azzera()
            server = Server { synchronized(sveglia) { sveglia.notifyAll() } }.also {
                it.datiAuto = { g ->
                    // un rifornimento appena riconosciuto: dove e a che prezzo (rete, fuori dal thread del server)
                    Viaggio.aggiorna(this, Parco.ultima(this), g)?.let { r ->
                        if (r.lat == null && synchronized(completando) { completando.add(r.quando) })
                            Thread({ Rifornimenti.completa(this, r) }, "rifornimento").start()
                    }
                }
                it.avvia()
            }
            lavoro = Thread({ ciclo() }, "nbtplus-lavoro").apply { isDaemon = true; start() }
        }
        if (intent?.action == AZIONE_AGGIORNA) {
            forza = true
            synchronized(sveglia) { sveglia.notifyAll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        attivo = false
        Stato.servizioAttivo = false
        Stato.avviatoDa = ""
        server?.ferma()
        server = null
        if (DatiAuto.grezzi.isNotEmpty()) Viaggio.aggiorna(this, Parco.ultima(this), DatiAuto.grezzi, forza = true)
        synchronized(sveglia) { sveglia.notifyAll() }
        super.onDestroy()
    }

    private fun ciclo() {
        val disegno = Disegno(this)
        while (attivo) {
            val wifi = Rete.ipWifiAuto()
            Stato.wifiAuto = wifi
            val adesso = System.currentTimeMillis()
            val chiestoDaPoco = adesso - Stato.ultimaRichiestaAuto < RICHIESTA_RECENTE_MS
            aggiornaNotifica(
                when {
                    wifi != null && chiestoDaPoco -> "Collegato all'iDrive"
                    wifi != null -> "Sul Wi-Fi della macchina, in attesa dell'iDrive"
                    else -> "In attesa del Wi-Fi della macchina"
                }
            )
            if (forza || wifi != null || chiestoDaPoco) {
                val tutto = forza
                forza = false
                try {
                    aggiorna(disegno, tutto)
                } catch (e: Exception) {
                    // nessun errore deve fermare il ciclo
                } finally {
                    Stato.aggiornamentoInCorso = ""
                }
            }
            synchronized(sveglia) {
                if (attivo && !forza) try { sveglia.wait(15000) } catch (e: InterruptedException) { }
            }
        }
    }

    /**
     * pagina "Stato auto" dai dati dell'iDrive: si ridisegna quando arrivano valori nuovi (al massimo ogni 30 secondi:
     * ogni ridisegno e' una pagina che il plugin Auto riscarica e l'iDrive rimette sullo schermo) o quando passa un
     * minuto (durata del viaggio); niente rete
     */
    private fun paginaAuto(disegno: Disegno, tutto: Boolean) {
        val bt = Parco.ultima(this)
        val adesso = System.currentTimeMillis()
        val firma = "${DatiAuto.ultimoDato}/${adesso / 60000}/$bt/${Parco.mostra(this, bt)}/${Foto.firma(this, bt)}/" +
            PartiVisibili.firma(this, bt)
        if (!tutto && (firma == firmaAuto || adesso - autoDisegnata < 30_000)) return
        val vivi = DatiAuto.grezzi
        // quali parti ha quest'auto (il tetto non c'e' su tutte) e quali l'utente vuole vedere
        PartiVisibili.registra(this, bt, vivi)
        val mostra = PartiVisibili.mostrate(this, bt)
        // finche' l'iDrive non manda i valori nuovi: gli ultimi letti (con la loro ora), porte escluse (l'ultimo
        // stato arriva quando scendi, con la tua porta aperta)
        val ultimi = if (vivi.isEmpty()) Viaggio.ultimiDati(this, bt) else null
        val grezzi = if (ultimi != null) ultimi.first.filterKeys { it != "sensors.doors" } else vivi
        val quando = if (ultimi != null) ultimi.second else DatiAuto.ultimoDato
        val ist = if (grezzi.isEmpty()) null else DatiAuto.istantanea(grezzi, mostra)
        val v = Viaggio.di(this, bt)
        val rifornimenti = Rifornimenti.elenco(this, bt)
        // capienza del serbatoio: il livello piu' alto visto dopo un rifornimento (per l'arco dell'anello)
        val riepilogo = disegno.paginaAuto(Parco.mostra(this, bt).ifEmpty { Marca.AUTO }, ist, v, rifornimenti.firstOrNull(),
            quando, rifornimenti.maxOfOrNull { it.livello })
        Stato.auto.metti(riepilogo)

        // Stato vettura (plugin Auto v9): Riepilogo + Porte, finestrini e tetto. La pagina delle porte si ridisegna
        // solo quando cambia qualcosa (stati, foto, punti) o ogni 5 minuti: il plugin la riscarica solo allora.
        val parti = DatiAuto.parti(grezzi).filterKeys { it in mostra }
        val firmaP = "$parti/${Foto.firma(this, bt)}/$bt/${mostra.joinToString(",")}"
        val vecchie = Stato.vettura.notizie
        val porte = if (firmaP == firmaPorte && vecchie.size == 2 && adesso - porteDisegnate < 5 * 60_000) vecchie[1] else {
            val foto = Foto.immagine(this, bt)
            val ritaglio = Foto.contornoAuto(this, bt)
            firmaPorte = firmaP
            porteDisegnate = adesso
            NotiziaPronta("Porte, finestrini e tetto", "", disegno.paginaPorte(foto, ritaglio, Foto.punti(this, bt), parti, quando, mostra),
                disegno.miniaturaPorte(foto, ritaglio, parti), ByteArray(0), max(adesso / 1000, (vecchie.getOrNull(1)?.versione ?: 0) + 1))
        }
        val versioneR = max(adesso / 1000, (vecchie.getOrNull(0)?.versione ?: 0) + 1)
        Stato.vettura.metti(listOf(NotiziaPronta("Riepilogo", "", riepilogo, disegno.miniaturaRiepilogo(ist), ByteArray(0), versioneR), porte))
        firmaAuto = firma
        autoDisegnata = adesso
        Stato.auto.ultimo = adesso
        Stato.auto.esito = if (grezzi.isEmpty()) "in attesa dei dati dell'auto dal plugin Meteo dell'iDrive"
        else if (ultimi != null) "in attesa dei dati dell'auto: per ora gli ultimi letti"
        else "OK alle ${oraAdesso()} - ${grezzi.size} valori dall'iDrive"
        Stato.vettura.ultimo = adesso
        Stato.vettura.esito = if (grezzi.isEmpty()) Stato.auto.esito else "OK alle ${oraAdesso()} - ${disegno.riassuntoAperture(parti)}"
    }

    private fun aggiorna(disegno: Disegno, tutto: Boolean) {
        try {
            paginaAuto(disegno, tutto)
        } catch (e: Exception) {
            Stato.auto.esito = "ERRORE alle ${oraAdesso()}: ${e.message ?: e.javaClass.simpleName}"
        }
        Preparazione.aggiorna(this, disegno, tutto) { attivo }
        Cache.salva(this)
    }

    private fun oraAdesso(): String = Preparazione.ora()

    // ------------------------------------------------------------------------- notifica ----
    private fun creaCanale() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            val ch = NotificationChannel(CANALE, "${Marca.APP} in auto", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun notifica(testo: String): Notification {
        testoNotifica = testo
        val apri = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_stato)
            .setContentTitle(Marca.APP)
            .setContentText(testo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(apri)
            .build()
    }

    private fun aggiornaNotifica(testo: String) {
        if (testo == testoNotifica) return
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICA, notifica(testo))
    }
}
