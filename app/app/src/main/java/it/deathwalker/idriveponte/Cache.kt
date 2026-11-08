package it.deathwalker.idriveponte

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Le pagine pronte per l'iDrive salvate nel telefono (cartella "pronte"): se Android chiude l'app fra un viaggio e
 * l'altro, al prossimo avvio il server ha subito le ultime pagine invece di "in attesa". Meteo, notizie, notizie
 * locali, carburanti, traffico e la prova del meteo; lo Stato vettura no (sono dati dal vivo dell'auto).
 * Si riscrive solo la sezione che e' cambiata.
 */
object Cache {

    private const val FORMATO = 1
    @Volatile private var caricata = false
    private val salvate = HashMap<String, Long>()       // sezione -> epoch gia' scritto

    private fun pagine() = listOf("meteo" to Stato.meteo, "carburanti" to Stato.carburanti, "traffico" to Stato.traffico)
    private fun elenchi() = listOf("notizie" to Stato.notizie, "locali" to Stato.locali, "meteoprova" to Stato.meteoProva)

    private fun cartella(ctx: Context) = File(ctx.filesDir, "pronte").apply { mkdirs() }

    /** una volta per processo, e solo nelle sezioni ancora vuote */
    @Synchronized
    fun carica(ctx: Context) {
        if (caricata) return
        caricata = true
        val dir = cartella(ctx)
        for ((nome, p) in pagine()) try {
            val f = File(dir, "$nome.bin")
            if (!f.exists() || p.png != null) continue
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != FORMATO) return@use
                val ultimo = d.readLong()
                val esito = d.readUTF()
                val png = ByteArray(d.readInt()).also { d.readFully(it) }
                p.metti(png)
                p.ultimo = ultimo
                p.esito = esito
                salvate[nome] = p.epoch
            }
        } catch (e: Exception) { }
        for ((nome, e) in elenchi()) try {
            val f = File(dir, "$nome.bin")
            if (!f.exists() || e.notizie.isNotEmpty()) continue
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != FORMATO) return@use
                val ultimo = d.readLong()
                val esito = d.readUTF()
                val righe = (0 until d.readInt()).map {
                    val titolo = d.readUTF()
                    val ora = d.readUTF()
                    val versione = d.readLong()
                    val b = (0 until 3).map { ByteArray(d.readInt()).also { a -> d.readFully(a) } }
                    NotiziaPronta(titolo, ora, b[0], b[1], b[2], versione)
                }
                e.metti(righe)
                e.ultimo = ultimo
                e.esito = esito
                salvate[nome] = e.epoch
            }
        } catch (ex: Exception) { }
        try {
            val f = File(dir, "brevi.bin")
            if (f.exists()) DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != FORMATO) return@use
                if (d.readBoolean() && Stato.meteoBreve == null)
                    Stato.meteoBreve = Stato.MeteoBreve(d.readUTF(), d.readInt(), d.readUTF(), d.readUTF())
                val c = if (d.readBoolean()) d.readUTF() to d.readUTF() else null
                val t = if (d.readBoolean()) d.readUTF() to d.readUTF() else null
                val a = d.readUTF()
                if (Stato.carburantiBreve == null) Stato.carburantiBreve = c
                if (Stato.trafficoBreve == null) Stato.trafficoBreve = t
                if (Stato.allerta.isEmpty()) Stato.allerta = a
            }
        } catch (e: Exception) { }
    }

    /** riscrive le sezioni cambiate dall'ultima volta (file nuovo e poi rinominato: mai un file a meta') */
    @Synchronized
    fun salva(ctx: Context) {
        val dir = cartella(ctx)
        var cambiato = false
        for ((nome, p) in pagine()) {
            val png = p.png ?: continue
            if (p.ultimo == 0L || salvate[nome] == p.epoch) continue
            if (scrivi(File(dir, "$nome.bin")) { d ->
                    d.writeLong(p.ultimo); d.writeUTF(p.esito); d.writeInt(png.size); d.write(png)
                }) { salvate[nome] = p.epoch; cambiato = true }
        }
        for ((nome, e) in elenchi()) {
            val righe = e.notizie
            if (righe.isEmpty() || e.ultimo == 0L || salvate[nome] == e.epoch) continue
            if (scrivi(File(dir, "$nome.bin")) { d ->
                    d.writeLong(e.ultimo); d.writeUTF(e.esito); d.writeInt(righe.size)
                    for (r in righe) {
                        d.writeUTF(r.titolo); d.writeUTF(r.ora); d.writeLong(r.versione)
                        for (b in listOf(r.pagina, r.mini, r.anteprima)) { d.writeInt(b.size); d.write(b) }
                    }
                }) { salvate[nome] = e.epoch; cambiato = true }
        }
        if (cambiato) scrivi(File(dir, "brevi.bin")) { d ->
            val m = Stato.meteoBreve
            d.writeBoolean(m != null)
            if (m != null) { d.writeUTF(m.citta); d.writeInt(m.temp); d.writeUTF(m.cielo); d.writeUTF(m.icona) }
            for (c in listOf(Stato.carburantiBreve, Stato.trafficoBreve)) {
                d.writeBoolean(c != null)
                if (c != null) { d.writeUTF(c.first); d.writeUTF(c.second) }
            }
            d.writeUTF(Stato.allerta)
        }
    }

    private fun scrivi(f: File, corpo: (DataOutputStream) -> Unit): Boolean = try {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d -> d.writeInt(FORMATO); corpo(d) }
        tmp.renameTo(f)
    } catch (e: Exception) {
        false
    }

    /** solo per i test: come un processo appena avviato */
    internal fun dimentica() {
        caricata = false
        salvate.clear()
    }
}
