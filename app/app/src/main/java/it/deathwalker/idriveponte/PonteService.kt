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

/**
 * Servizio in primo piano di NBT Plus (notifica minima, silenziosa).
 * - tiene acceso il server HTTP per l'iDrive;
 * - quando il telefono e' sul Wi-Fi della macchina (o l'iDrive ha chiesto qualcosa da poco)
 *   scarica i dati con la connessione del telefono e prepara le pagine, ognuna col suo ritmo:
 *   meteo e notizie ogni 10 minuti, traffico ogni 5, carburanti ogni 30 (o subito se ti sposti di 3 km),
 *   notizie locali ogni 15, allerte della Protezione Civile ogni 3 ore.
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
    private var allerta: Allerta? = null
    private var allertaLetta = 0L
    private var latCarburanti = 0.0
    private var lonCarburanti = 0.0

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
            server = Server { synchronized(sveglia) { sveglia.notifyAll() } }.also { it.avvia() }
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

    /** true se e' ora di rifare la sezione: scaduta, oppure fallita da piu' di 2 minuti */
    private fun scaduta(ultimo: Long, ogni: Long, tentativo: Long): Boolean {
        val adesso = System.currentTimeMillis()
        return adesso - ultimo > ogni && adesso - tentativo > 2 * MIN
    }

    private val tentativi = HashMap<String, Long>()

    private inline fun sezione(nome: String, ultimo: Long, ogni: Long, tutto: Boolean, corpo: () -> Unit) {
        if (!attivo) return
        if (!tutto && !scaduta(ultimo, ogni, tentativi[nome] ?: 0L)) return
        tentativi[nome] = System.currentTimeMillis()
        Stato.aggiornamentoInCorso = nome
        corpo()
    }

    private fun aggiorna(disegno: Disegno, tutto: Boolean) {
        val prefs = getSharedPreferences("ponte", MODE_PRIVATE)
        val salvata = Citta(
            prefs.getString("citta", "La Spezia") ?: "La Spezia",
            prefs.getFloat("lat", 44.1025f).toDouble(),
            prefs.getFloat("lon", 9.8241f).toDouble()
        )
        val gps = if (prefs.getBoolean("usa_posizione", true)) Posizione.citta(this) else null
        val qui = gps ?: salvata
        Stato.fonteCitta = if (gps != null) "posizione del telefono" else "città salvata"

        // allerta della Protezione Civile (comune dove sei), riletta ogni 3 ore: finisce nella pagina meteo
        if (tutto || System.currentTimeMillis() - allertaLetta > 3 * 60 * MIN) {
            allertaLetta = System.currentTimeMillis()
            try {
                val a = Fonti.allerta(this, qui.nome, qui.lat, qui.lon)
                allerta = a
                Stato.allerta = if (a == null) "il comune \"${qui.nome}\" non c'è nelle zone del bollettino"
                else a.testoBreve().ifEmpty { "nessuna allerta oggi e domani (zona ${a.zona})" }
            } catch (e: Exception) {
                Stato.allerta = "ERRORE nel leggere il bollettino: ${e.message ?: e.javaClass.simpleName}"
                allertaLetta = System.currentTimeMillis() - 3 * 60 * MIN + 10 * MIN      // si riprova tra 10 minuti
            }
            Stato.meteo.ultimo = 0L                                  // ridisegna il meteo con l'allerta nuova
        }

        sezione("Meteo", Stato.meteo.ultimo, 10 * MIN, tutto) {
            try {
                val m = Dati.meteo(this, qui)
                Stato.meteo.metti(disegno.paginaMeteo(m, allerta))
                Stato.meteo.ultimo = System.currentTimeMillis()
                Stato.meteo.esito = "OK alle ${oraAdesso()} - ${m.citta} ${m.temp}° (${Stato.fonteCitta})"
            } catch (e: Exception) {
                Stato.meteo.esito = "ERRORE alle ${oraAdesso()}: ${e.message ?: e.javaClass.simpleName}"
                if (Stato.meteo.png == null) Stato.meteo.metti(disegno.paginaAvviso("Meteo", "Meteo non disponibile in questo momento. Nuovo tentativo tra poco."))
            }
        }

        sezione("Notizie", Stato.notizie.ultimo, 10 * MIN, tutto) {
            elenco(Stato.notizie, disegno, "ANSA") { Dati.notizie(this) }
        }

        sezione("Traffico", Stato.traffico.ultimo, 5 * MIN, tutto) {
            val raggio = prefs.getInt("raggio_traffico", 100)
            try {
                val lista = Fonti.traffico(this, qui.lat, qui.lon, raggio)
                Stato.traffico.metti(disegno.paginaTraffico(lista, qui.nome, raggio))
                Stato.traffico.ultimo = System.currentTimeMillis()
                Stato.traffico.esito = "OK alle ${oraAdesso()} - ${lista.size} eventi entro $raggio km"
            } catch (e: Exception) {
                Stato.traffico.esito = "ERRORE alle ${oraAdesso()}: ${e.message ?: e.javaClass.simpleName}"
                if (Stato.traffico.png == null) Stato.traffico.metti(disegno.paginaAvviso("Traffico", "Traffico non disponibile in questo momento. Nuovo tentativo tra poco."))
            }
        }

        // carburanti: ogni 30 minuti, oppure subito se ti sei spostato di almeno 3 km
        val spostato = Fonti.km(latCarburanti, lonCarburanti, qui.lat, qui.lon) > 3.0
        sezione("Carburanti", if (spostato) 0L else Stato.carburanti.ultimo, 30 * MIN, tutto) {
            val tipo = prefs.getInt("carburante", 1)
            val raggio = prefs.getInt("raggio_carburanti", 5)
            val nome = Fonti.CARBURANTI[tipo] ?: "Benzina"
            try {
                val lista = Fonti.carburanti(this, qui.lat, qui.lon, tipo, raggio)
                Stato.carburanti.metti(disegno.paginaCarburanti(lista, nome, qui.nome, raggio))
                Stato.carburanti.ultimo = System.currentTimeMillis()
                latCarburanti = qui.lat; lonCarburanti = qui.lon
                Stato.carburanti.esito = "OK alle ${oraAdesso()} - $nome, ${lista.size} distributori entro $raggio km" +
                    (lista.firstOrNull()?.let { String.format(java.util.Locale.ITALY, ", il migliore %.3f €", it.prezzo) } ?: "")
            } catch (e: Exception) {
                Stato.carburanti.esito = "ERRORE alle ${oraAdesso()}: ${e.message ?: e.javaClass.simpleName}"
                if (Stato.carburanti.png == null) Stato.carburanti.metti(disegno.paginaAvviso("Carburanti", "Prezzi dei carburanti non disponibili in questo momento. Nuovo tentativo tra poco."))
            }
        }

        // notizie locali: la citta' scelta nell'app (zona fissa), feed ANSA della sua regione
        sezione("Notizie locali", Stato.locali.ultimo, 15 * MIN, tutto) {
            val citta = prefs.getString("locali_citta", "La Spezia") ?: "La Spezia"
            val regione = prefs.getString("locali_regione", "Liguria") ?: "Liguria"
            val provincia = prefs.getString("locali_provincia", "La Spezia") ?: ""
            val feed = Fonti.feedRegione(regione)
            if (feed == null) {
                Stato.locali.esito = "ERRORE: regione \"$regione\" sconosciuta, scegli di nuovo la città nell'app"
            } else {
                elenco(Stato.locali, disegno, "ANSA ${feed.second}") {
                    Dati.notizie(this, feed.first, listOf(citta, provincia).filter { it.isNotEmpty() }.distinct())
                }
            }
        }
    }

    private inline fun elenco(e: Elenco, disegno: Disegno, fonte: String, scarica: () -> List<Notizia>) {
        try {
            val lista = scarica()
            if (lista.isEmpty()) throw IllegalStateException("nessuna notizia nel feed")
            val pronte = lista.map { n -> NotiziaPronta(n.titolo, n.ora, disegno.paginaNotizia(n, fonte), disegno.miniatura(n), disegno.anteprima(n, fonte)) }
            val conFoto = lista.count { it.foto != null }
            lista.forEach { it.foto?.recycle() }
            e.metti(pronte)
            e.ultimo = System.currentTimeMillis()
            e.esito = "OK alle ${oraAdesso()} - ${pronte.size} notizie, $conFoto con foto"
        } catch (ex: Exception) {
            e.esito = "ERRORE alle ${oraAdesso()}: ${ex.message ?: ex.javaClass.simpleName}"
        }
    }

    private fun oraAdesso(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).apply { timeZone = Dati.ROMA }.format(java.util.Date())

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
