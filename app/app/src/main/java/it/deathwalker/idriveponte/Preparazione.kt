package it.deathwalker.idriveponte

import android.content.Context

/**
 * Scarica i dati e prepara le pagine per l'iDrive (meteo con allerta, notizie, traffico, carburanti, notizie locali),
 * ognuna col suo ritmo. La usa il servizio in auto e, lontano dall'auto, il lavoro di ogni ora (PreparaJob): cosi'
 * quando sali in macchina le pagine sono gia' pronte. Uno solo alla volta (synchronized).
 */
object Preparazione {

    private const val MIN = 60 * 1000L

    private var allerta: Allerta? = null
    private var allertaLetta = 0L
    private var latCarburanti = 0.0
    private var lonCarburanti = 0.0
    private var tipoCarburanti = 0
    private var meteoComune = ""
    private val tentativi = HashMap<String, Long>()

    /** true se e' ora di rifare la sezione: scaduta, oppure fallita da piu' di 2 minuti */
    private fun scaduta(ultimo: Long, ogni: Long, tentativo: Long): Boolean {
        val adesso = System.currentTimeMillis()
        return adesso - ultimo > ogni && adesso - tentativo > 2 * MIN
    }

    private inline fun sezione(nome: String, ultimo: Long, ogni: Long, tutto: Boolean, continua: () -> Boolean, corpo: () -> Unit) {
        if (!continua()) return
        if (!tutto && !scaduta(ultimo, ogni, tentativi[nome] ?: 0L)) return
        tentativi[nome] = System.currentTimeMillis()
        Stato.aggiornamentoInCorso = nome
        corpo()
    }

    /** continua: false appena chi l'ha chiamata deve fermarsi (servizio spento, lavoro interrotto da Android) */
    @Synchronized
    fun aggiorna(ctx: Context, disegno: Disegno, tutto: Boolean, continua: () -> Boolean) {
        val prefs = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)
        val salvata = Citta(
            prefs.getString("citta", "La Spezia") ?: "La Spezia",
            prefs.getFloat("lat", 44.1025f).toDouble(),
            prefs.getFloat("lon", 9.8241f).toDouble()
        )
        val gps = if (prefs.getBoolean("usa_posizione", true)) Posizione.citta(ctx) else null
        val qui = gps ?: salvata
        Stato.fonteCitta = if (gps != null) "posizione del telefono" else "città salvata"

        // allerta della Protezione Civile (comune dove sei), riletta ogni 3 ore, ogni 20 minuti dalle 15 finche' non
        // c'e' il bollettino del giorno: finisce nella pagina meteo
        // e a ogni cambio di giorno, perche' "oggi" e "domani" si spostano anche con lo stesso bollettino
        val ogniAllerta = Fonti.intervalloBollettino(Fonti.bollettinoLetto(), System.currentTimeMillis())
        val altroGiorno = Fonti.giornoItalia(allertaLetta) != Fonti.giornoItalia(System.currentTimeMillis())
        if (tutto || altroGiorno || System.currentTimeMillis() - allertaLetta > ogniAllerta) {
            allertaLetta = System.currentTimeMillis()
            try {
                val a = Fonti.allerta(ctx, qui.nome, qui.lat, qui.lon)
                allerta = a
                Stato.allerta = if (a == null) "il comune \"${qui.nome}\" non c'è nelle zone del bollettino"
                else a.testoBreve().ifEmpty {
                    if (a.domaniNoto) "nessuna allerta oggi e domani (zona ${a.zona})"
                    else "nessuna allerta oggi; per domani il bollettino non è ancora uscito (zona ${a.zona})"
                }
            } catch (e: Exception) {
                if (e is BollettinoNonValido) allerta = null          // niente allerta vecchia nella pagina
                Stato.allerta = "ERRORE nel leggere il bollettino: ${e.message ?: e.javaClass.simpleName}"
                allertaLetta = System.currentTimeMillis() - ogniAllerta + 10 * MIN      // si riprova tra 10 minuti
            }
            Stato.meteo.ultimo = 0L                                  // ridisegna il meteo con l'allerta nuova
        }

        // meteo: ogni ora, oppure subito quando passi in un altro comune; se open-meteo non risponde (servizio
        // gratuito con un limite di richieste per indirizzo, condiviso da chi esce sulla stessa rete) c'e' MET Norway
        val altroComune = meteoComune.isNotEmpty() && qui.nome != meteoComune
        sezione("Meteo", if (altroComune) 0L else Stato.meteo.ultimo, 60 * MIN, tutto, continua) {
            try {
                val m = try { Dati.meteo(ctx, qui) } catch (e: Exception) { Dati.meteoMet(ctx, qui) }
                val paginaMeteo = disegno.paginaMeteo(m, allerta)
                Stato.meteo.metti(paginaMeteo)
                // plugin Meteo v12: un giorno per riga, con colonna, pagina del giorno e la pagina (la stessa per tutti
                // i giorni: l'evidenza del giorno scelto la mette l'iDrive)
                Stato.meteoGiorni.metti(disegno.giorniMeteo(m).indices.map { i ->
                    val g = disegno.giorniMeteo(m)[i]
                    NotiziaPronta("${g.nome} ${g.max}°/${g.min}°", "", disegno.paginaGiornoMeteo(m, i),
                        disegno.colonnaMeteo(m, i), paginaMeteo)
                })
                Stato.meteo.ultimo = System.currentTimeMillis()
                meteoComune = qui.nome
                Stato.meteoBreve = Stato.MeteoBreve(m.citta, m.temp, Dati.cielo(m.codice),
                    Dati.iconaMeteo(m.codice, m.giorno, ventoForte(m)))
                Stato.meteo.esito = "OK alle ${ora()} - ${m.citta} ${m.temp}° (${Stato.fonteCitta}, ${m.fonte})"
            } catch (e: Exception) {
                Stato.meteo.esito = "ERRORE alle ${ora()}: ${e.message ?: e.javaClass.simpleName}"
                val avviso = disegno.paginaAvviso("Meteo", "Meteo non disponibile in questo momento. Nuovo tentativo tra poco.")
                if (Stato.meteo.png == null) Stato.meteo.metti(avviso)
                if (Stato.meteoGiorni.notizie.isEmpty())
                    Stato.meteoGiorni.metti(listOf(NotiziaPronta("Meteo non disponibile", "", avviso, avviso, avviso)))
            }
        }

        sezione("Notizie", Stato.notizie.ultimo, 10 * MIN, tutto, continua) {
            elenco(Stato.notizie, Stato.notizieScelta, "Notizie", disegno, "ANSA") { Dati.notizie(ctx) }
        }

        sezione("Traffico", Stato.traffico.ultimo, 5 * MIN, tutto, continua) {
            val raggio = prefs.getInt("raggio_traffico", 100)
            try {
                val lista = Fonti.traffico(ctx, qui.lat, qui.lon, raggio)
                Stato.traffico.metti(disegno.paginaTraffico(lista, qui.nome, raggio))
                Stato.traffico.ultimo = System.currentTimeMillis()
                Stato.traffico.esito = "OK alle ${ora()} - ${lista.size} eventi entro $raggio km"
                val grave = lista.maxByOrNull { it.gravita }
                Stato.trafficoBreve = (if (lista.isEmpty()) "Nessun evento" else if (lista.size == 1) "1 evento" else "${lista.size} eventi") to
                    (grave?.let { listOf(it.strada, it.tipo).filter { t -> t.isNotEmpty() }.joinToString(": ") } ?: "entro $raggio km")
            } catch (e: Exception) {
                Stato.traffico.esito = "ERRORE alle ${ora()}: ${e.message ?: e.javaClass.simpleName}"
                if (Stato.traffico.png == null) Stato.traffico.metti(disegno.paginaAvviso("Traffico", "Traffico non disponibile in questo momento. Nuovo tentativo tra poco."))
            }
        }

        // carburanti: ogni 30 minuti, oppure subito se ti sei spostato di almeno 3 km
        // e subito anche quando cambia il carburante (un'altra auto del parco, o l'hai cambiato nell'app)
        val tipo = Parco.carburante(ctx, Parco.ultima(ctx))
        val spostato = Fonti.km(latCarburanti, lonCarburanti, qui.lat, qui.lon) > 3.0 || tipo != tipoCarburanti
        sezione("Carburanti", if (spostato) 0L else Stato.carburanti.ultimo, 30 * MIN, tutto, continua) {
            val raggio = prefs.getInt("raggio_carburanti", 5)
            val nome = Fonti.CARBURANTI[tipo] ?: "Benzina"
            try {
                val lista = Fonti.carburanti(ctx, qui.lat, qui.lon, tipo, raggio)
                val paginaCarburanti = disegno.paginaCarburanti(lista, nome, qui.nome, raggio)
                Stato.carburanti.metti(paginaCarburanti)
                // plugin Carburanti v13: un distributore per riga, la sua scheda quando lo premi
                val sei = lista.take(6)
                val media = if (lista.size > 1) lista.map { it.prezzo }.average() else null
                Stato.carburantiScelta.metti(
                    if (sei.isEmpty()) listOf(NotiziaPronta("Nessun distributore", "", Stato.carburanti.png!!, Stato.carburanti.png!!, Stato.carburanti.png!!))
                    else sei.indices.map { i ->
                        NotiziaPronta(sei[i].marchio, "", disegno.paginaDistributore(sei[i], nome, media),
                            disegno.schedaDistributore(lista, nome, qui.nome, raggio, i), paginaCarburanti)   // l'evidenza la mette l'iDrive
                    }
                )
                Stato.carburanti.ultimo = System.currentTimeMillis()
                latCarburanti = qui.lat; lonCarburanti = qui.lon; tipoCarburanti = tipo
                Stato.carburanti.esito = "OK alle ${ora()} - $nome, ${lista.size} distributori entro $raggio km" +
                    (lista.firstOrNull()?.let { String.format(java.util.Locale.ITALY, ", il migliore %.3f €", it.prezzo) } ?: "")
                Stato.carburantiBreve = lista.firstOrNull()?.let { d ->
                    String.format(java.util.Locale.ITALY, "%s %.3f €", nome, d.prezzo) to
                        listOf(d.marchio.ifEmpty { d.nome }, String.format(java.util.Locale.ITALY, "%.1f km", d.km)).joinToString(" · ")
                } ?: ("$nome: nessun prezzo" to "entro $raggio km")
            } catch (e: Exception) {
                Stato.carburanti.esito = "ERRORE alle ${ora()}: ${e.message ?: e.javaClass.simpleName}"
                if (Stato.carburanti.png == null) Stato.carburanti.metti(disegno.paginaAvviso("Carburanti", "Prezzi dei carburanti non disponibili in questo momento. Nuovo tentativo tra poco."))
                if (Stato.carburantiScelta.notizie.isEmpty()) Stato.carburanti.png?.let { a ->
                    Stato.carburantiScelta.metti(listOf(NotiziaPronta("Carburanti non disponibili", "", a, a, a)))
                }
            }
        }

        // notizie locali: la citta' scelta nell'app (zona fissa), feed ANSA della sua regione
        sezione("Notizie locali", Stato.locali.ultimo, 15 * MIN, tutto, continua) {
            val citta = prefs.getString("locali_citta", "La Spezia") ?: "La Spezia"
            val regione = prefs.getString("locali_regione", "Liguria") ?: "Liguria"
            val provincia = prefs.getString("locali_provincia", "La Spezia") ?: ""
            val feed = Fonti.feedRegione(regione)
            if (feed == null) {
                Stato.locali.esito = "ERRORE: regione \"$regione\" sconosciuta, scegli di nuovo la città nell'app"
            } else {
                elenco(Stato.locali, Stato.localiScelta, "Notizie locali", disegno, "ANSA ${feed.second}") {
                    Dati.notizie(ctx, feed.first, listOf(citta, provincia).filter { it.isNotEmpty() }.distinct())
                }
            }
        }
    }

    /**
     * e: l'elenco per i plugin fino alla v12 (lista dell'iDrive con miniature); scelta: lo stesso "in primo piano" per
     * i plugin v13 (riga = notizia, mini = riga dell'elenco disegnata, anteprima = pagina con la notizia scelta grande)
     */
    private inline fun elenco(e: Elenco, scelta: Elenco, titolo: String, disegno: Disegno, fonte: String, scarica: () -> List<Notizia>) {
        try {
            val lista = scarica()
            if (lista.isEmpty()) throw IllegalStateException("nessuna notizia nel feed")
            val pronte = lista.map { n -> NotiziaPronta(n.titolo, n.ora, disegno.paginaNotizia(n, fonte), disegno.miniatura(n), disegno.anteprima(n, fonte)) }
            val otto = lista.take(8)
            val scelte = otto.indices.map { i ->
                NotiziaPronta(otto[i].titolo, otto[i].ora, pronte[i].pagina, disegno.rigaNotizia(otto, titolo, fonte, i),
                    disegno.primoPiano(otto, titolo, fonte, i))
            }
            val conFoto = lista.count { it.foto != null }
            lista.forEach { it.foto?.recycle() }
            scelta.metti(scelte)
            scelta.ultimo = System.currentTimeMillis()
            e.metti(pronte)
            e.ultimo = System.currentTimeMillis()
            e.esito = "OK alle ${ora()} - ${pronte.size} notizie, $conFoto con foto"
        } catch (ex: Exception) {
            e.esito = "ERRORE alle ${ora()}: ${ex.message ?: ex.javaClass.simpleName}"
        }
    }

    /** vento forte: raffiche da 60 km/h o vento medio da 45 (MET Norway non da' le raffiche) */
    private fun ventoForte(m: Meteo) = m.raffiche >= 60 || m.vento >= 45

    fun ora(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).apply { timeZone = Dati.ROMA }.format(java.util.Date())
}
