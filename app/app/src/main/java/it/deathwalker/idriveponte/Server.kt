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
 *                             carburanti=<epoch> traffico=<epoch>"
 *   GET /meteo.png, /carburanti.png, /traffico.png -> pagine 1280x480
 *   GET /locali/...       -> come le notizie qui sotto, per le notizie locali
 *   GET /notizie.txt      -> riga 1 "NOTIZIE <epoch> <n>", poi un titolo per riga (ISO-8859-1: l'iDrive legge cosi'
 *                            il testo dei plugin; in UTF-8 "Morì" diventava "MorÃ¬")
 *   GET /notizia/<i>.png  -> pagina della notizia i (0..7)
 *   GET /mini/<i>.png     -> miniatura 63x54 della notizia i
 *   GET /anteprima/<i>.png -> scheda 640x480 della notizia i (accanto alla lista, quando la riga e' selezionata)
 * Risponde solo a indirizzi della rete locale (Wi-Fi della macchina o casa), mai a internet.
 */
class Server(private val quandoChiede: () -> Unit) {

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

    private fun locale(sock: Socket): Boolean {
        val a = sock.inetAddress ?: return false
        return a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
    }

    private fun servi(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 8000
                if (!locale(s)) return
                val riga = leggiRichiesta(BufferedInputStream(s.getInputStream())) ?: return
                val parti = riga.split(' ')
                if (parti.size < 2 || parti[0] != "GET") { rispondi(s.getOutputStream(), 405, "text/plain", "solo GET\n".toByteArray()); return }
                val percorso = parti[1].substringBefore('?')
                Stato.ultimaRichiestaAuto = System.currentTimeMillis()
                Stato.ipAuto = s.inetAddress?.hostAddress ?: ""
                Stato.richiesteAuto++
                quandoChiede()
                val out = s.getOutputStream()
                instrada(out, percorso)
            } catch (e: Exception) {
                // connessione chiusa dall'iDrive o tempo scaduto: niente da fare
            }
        }
    }

    private fun instrada(out: OutputStream, percorso: String) {
        val pagine = mapOf("/meteo.png" to Stato.meteo, "/carburanti.png" to Stato.carburanti, "/traffico.png" to Stato.traffico)
        val pagina = pagine[percorso]
        when {
            percorso == "/ponte" -> rispondi(
                out, 200, "text/plain",
                ("IDRIVEPONTE ${Stato.VERSIONE} meteo=${Stato.meteo.epoch} notizie=${Stato.notizie.epoch} n=${Stato.notizie.notizie.size} " +
                    "locali=${Stato.locali.epoch} carburanti=${Stato.carburanti.epoch} traffico=${Stato.traffico.epoch}\n").toByteArray()
            )
            pagina != null -> {
                val png = pagina.png
                if (png == null) rispondi(out, 503, "text/plain", "${pagina.nome} in preparazione\n".toByteArray())
                else rispondi(out, 200, "image/png", png)
            }
            percorso.startsWith("/locali/") -> elenco(out, Stato.locali, percorso.removePrefix("/locali"))
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
                    sb.append(ora).append(n.titolo.replace('\n', ' ').replace('\r', ' ')).append('\n')
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
        val stato = when (codice) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Service Unavailable" }
        val testa = "HTTP/1.0 $codice $stato\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(testa.toByteArray(Charsets.US_ASCII))
        out.write(corpo)
        out.flush()
    }
}
