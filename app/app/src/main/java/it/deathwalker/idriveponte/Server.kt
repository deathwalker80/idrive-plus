package it.deathwalker.idriveponte

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Piccolo server HTTP sul telefono, porta 8765: i plugin dell'iDrive chiedono qui le pagine pronte.
 *   GET /ponte            -> "IDRIVEPONTE <versione> meteo=<epoch> notizie=<epoch> n=<numero> locali=<epoch>
 *                             carburanti=<epoch> traffico=<epoch> auto=<epoch> vettura=<epoch> avvio=<secondi> meteogiorni=<epoch>"
 *   GET /meteo.png, /carburanti.png, /traffico.png, /auto.png -> pagine 1280x480 (auto = riepilogo dello stato auto,
 *                            per il plugin Auto v8; meteo per il plugin Meteo fino alla v11)
 *   GET /locali/...       -> come le notizie qui sotto, per le notizie locali
 *   GET /vettura/...      -> come le notizie, per lo Stato vettura (plugin Auto v9): riga 0 Riepilogo, riga 1 Porte,
 *                            finestrini e tetto; dopo il titolo un TAB e la versione della riga
 *   GET /meteogiorni/...  -> plugin Meteo v12: come le notizie, 6 righe = i giorni del meteo; mini = colonna del
 *                            giorno, notizia = pagina del giorno coi dettagli, anteprima = pagina col giorno evidenziato
 *   GET /notizie.txt      -> riga 1 "NOTIZIE <epoch> <n>", poi un titolo per riga (ISO-8859-1: l'iDrive legge cosi'
 *                            il testo dei plugin; in UTF-8 "Morì" diventava "MorÃ¬")
 *   GET /notizia/<i>.png  -> pagina della notizia i (0..7)
 *   GET /mini/<i>.png     -> miniatura 63x54 della notizia i
 *   GET /anteprima/<i>.png -> scheda 640x480 della notizia i (accanto alla lista, quando la riga e' selezionata)
 *   GET /auto?n=<proprieta'>&v=<JSON> -> un dato dell'auto letto dal plugin Meteo (DatiAuto): risponde "OK"
 * Risponde solo alla testata sul Wi-Fi della macchina (e al telefono stesso): su qualunque altra rete,
 * anche locale (casa, bar, albergo), le connessioni si chiudono subito senza rispondere niente.
 */
class Server(private val quandoChiede: () -> Unit) {

    companion object {
        /** caratteri che stanno in una riga della lista accanto alla miniatura (ora compresa), misurati in auto */
        const val RIGA_MAX = 62

        /** riga della lista: l'iDrive non mette i puntini e un titolo lungo esce dallo schermo tondo della MINI (si
         *  vedeva tagliato a meta' parola sul bordo); oltre RIGA_MAX caratteri si taglia all'ultima parola intera + "..." */
        internal fun accorcia(s: String): String {
            val t = s.trim().replace(Regex("\\s+"), " ")
            if (t.length <= RIGA_MAX) return t
            val taglio = t.substring(0, RIGA_MAX - 3)
            val spazio = taglio.lastIndexOf(' ')
            val base = if (spazio >= RIGA_MAX / 2) taglio.substring(0, spazio) else taglio
            return base.trimEnd(' ', ',', ';', ':', '.', '-', '·') + "..."
        }
    }

    /** chiamata a ogni dato dell'auto ricevuto, con tutti gli ultimi valori */
    @Volatile var datiAuto: (Map<String, String>) -> Unit = {}

    @Volatile private var socket: ServerSocket? = null
    private val lavoratori = Executors.newFixedThreadPool(4)

    fun avvia() {
        if (socket != null) return
        Thread({ ciclo() }, "ponte-server").apply { isDaemon = true }.start()
    }

    fun ferma() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        lavoratori.shutdownNow()
    }

    private fun ciclo() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(Stato.PORTA), 8)
            socket = s
            Stato.erroreServer = ""
            while (!s.isClosed) {
                val cli = try { s.accept() } catch (e: Exception) { break }
                lavoratori.execute { servi(cli) }
            }
        } catch (e: Exception) {
            Stato.erroreServer = "server non avviato: ${e.message}"
        }
    }

    /** true solo per il telefono stesso o per un indirizzo della stessa rete /24 del Wi-Fi della macchina
     *  (172.16.224.x o 172.16.222.x, vedi Rete.ipWifiAuto), e solo se il telefono e' davvero su quella rete */
    private fun dallAuto(sock: Socket): Boolean {
        val a = sock.inetAddress ?: return false
        if (a.isLoopbackAddress) return true
        val cliente = a.hostAddress ?: return false
        val telefono = Rete.ipWifiAuto() ?: return false
        return cliente.substringBeforeLast('.') == telefono.substringBeforeLast('.')
    }

    private fun servi(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 8000
                if (!dallAuto(s)) return
                val riga = leggiRichiesta(BufferedInputStream(s.getInputStream())) ?: return
                val parti = riga.split(' ')
                if (parti.size < 2 || parti[0] != "GET") { rispondi(s.getOutputStream(), 405, "text/plain", "solo GET\n".toByteArray()); return }
                val percorso = parti[1].substringBefore('?')
                val query = parti[1].substringAfter('?', "")
                Stato.ultimaRichiestaAuto = System.currentTimeMillis()
                Stato.ipAuto = s.inetAddress?.hostAddress ?: ""
                Stato.richiesteAuto++
                quandoChiede()
                val out = s.getOutputStream()
                instrada(out, percorso, query)
            } catch (e: Exception) {
                // connessione chiusa dall'iDrive o tempo scaduto: niente da fare
            }
        }
    }

    private fun instrada(out: OutputStream, percorso: String, query: String) {
        val pagine = mapOf(
            "/meteo.png" to Stato.meteo, "/carburanti.png" to Stato.carburanti, "/traffico.png" to Stato.traffico, "/auto.png" to Stato.auto
        )
        val pagina = pagine[percorso]
        when {
            percorso == "/ponte" -> rispondi(
                out, 200, "text/plain",
                ("IDRIVEPONTE ${Stato.VERSIONE} meteo=${Stato.meteo.epoch} notizie=${Stato.notizie.epoch} n=${Stato.notizie.notizie.size} " +
                    "locali=${Stato.locali.epoch} carburanti=${Stato.carburanti.epoch} traffico=${Stato.traffico.epoch} " +
                    "auto=${Stato.auto.epoch} vettura=${Stato.vettura.epoch} avvio=${Stato.avvio} meteogiorni=${Stato.meteoGiorni.epoch}\n").toByteArray()
            )
            pagina != null -> {
                val png = pagina.png
                if (png == null) rispondi(out, 503, "text/plain", "${pagina.nome} in preparazione\n".toByteArray())
                else rispondi(out, 200, "image/png", png)
            }
            percorso == "/auto" -> {
                if (DatiAuto.ricevi(query)) {
                    datiAuto(DatiAuto.grezzi)
                    rispondi(out, 200, "text/plain", "OK\n".toByteArray())
                } else rispondi(out, 400, "text/plain", "dato non valido\n".toByteArray())
            }
            percorso.startsWith("/locali/") -> elenco(out, Stato.locali, percorso.removePrefix("/locali"))
            percorso.startsWith("/vettura/") -> elenco(out, Stato.vettura, percorso.removePrefix("/vettura"))
            percorso.startsWith("/meteogiorni/") -> elenco(out, Stato.meteoGiorni, percorso.removePrefix("/meteogiorni"))
            else -> elenco(out, Stato.notizie, percorso)
        }
    }

    /** /notizie.txt, /notizia/<i>.png, /mini/<i>.png, /anteprima/<i>.png di un elenco */
    private fun elenco(out: OutputStream, e: Elenco, percorso: String) {
        val notizie = e.notizie
        when {
            percorso == "/notizie.txt" -> {
                val sb = StringBuilder("NOTIZIE ${e.epoch} ${notizie.size}\n")
                for (n in notizie) {
                    val ora = if (n.ora.isNotEmpty()) n.ora + " · " else ""
                    sb.append(accorcia(ora + n.titolo.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')))
                    if (n.versione > 0) sb.append('\t').append(n.versione)       // plugin v9: riscarica solo le righe cambiate
                    sb.append('\n')
                }
                rispondi(out, 200, "text/plain; charset=iso-8859-1", latino1(sb.toString()))
            }
            percorso.startsWith("/notizia/") || percorso.startsWith("/mini/") || percorso.startsWith("/anteprima/") -> {
                val i = percorso.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: -1
                val n = notizie.getOrNull(i)
                if (n == null) rispondi(out, 404, "text/plain", "notizia $i non presente\n".toByteArray())
                else rispondi(
                    out, 200, "image/png",
                    when {
                        percorso.startsWith("/mini/") -> n.mini
                        percorso.startsWith("/anteprima/") -> n.anteprima
                        else -> n.pagina
                    }
                )
            }
            else -> rispondi(out, 404, "text/plain", "sconosciuto\n".toByteArray())
        }
    }

    /** testo in ISO-8859-1: i segni tipografici che non ci sono diventano i loro equivalenti semplici */
    private fun latino1(s: String): ByteArray {
        val t = s.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-').replace("\u2026", "...").replace("\u20AC", "EUR")
            .replace('\u00A0', ' ')
        val out = ByteArray(t.length)
        for (i in t.indices) { val ch = t[i]; out[i] = if (ch.code in 1..255) ch.code.toByte() else '?'.code.toByte() }
        return out
    }

    /** legge la prima riga e salta le intestazioni fino alla riga vuota (massimo 8 KB) */
    private fun leggiRichiesta(ing: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prima: String? = null
        var letti = 0
        while (letti < 8192) {
            val ch = ing.read()
            if (ch < 0) return prima
            letti++
            if (ch == '\n'.code) {
                val r = sb.toString().trimEnd('\r')
                if (prima == null) prima = r else if (r.isEmpty()) return prima
                if (prima.isEmpty()) return null
                sb.setLength(0)
            } else sb.append(ch.toChar())
        }
        return prima
    }

    private fun rispondi(out: OutputStream, codice: Int, tipo: String, corpo: ByteArray) {
        val stato = when (codice) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Service Unavailable" }
        val testa = "HTTP/1.0 $codice $stato\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(testa.toByteArray(Charsets.US_ASCII))
        out.write(corpo)
        out.flush()
    }
}
