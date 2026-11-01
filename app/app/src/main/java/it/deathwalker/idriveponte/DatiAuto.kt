package it.deathwalker.idriveponte

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject

/**
 * Dati dell'auto letti da AAIdrive, se e' installato e collegato all'auto: AAIdrive li espone agli altri programmi
 * del telefono nel suo ContentProvider io.bimmergestalt.cardata.provider (permesso CDS_normal, livello normal:
 * concesso all'installazione). Solo lettura, all'auto non arriva niente. Senza AAIdrive l'app funziona come prima.
 *
 * Nomi e numeri delle proprieta': IDriveConnectKit (CDSProperty.kt); formati dei valori: AAIdrive (CDSMetrics.kt).
 * I valori arrivano nelle unita' dell'auto: km, litri, gradi C, consumo in l/100 km.
 */
object DatiAuto {

    const val AUTORITA = "io.bimmergestalt.cardata.provider"
    private const val BASE = "content://$AUTORITA/cds"
    /** AAIdrive smette di seguire una proprieta' che nessuno legge da 10 s: la si rilegge prima */
    private const val MANTENIMENTO_MS = 5000L

    val PROPRIETA = linkedMapOf(
        39 to "driving.odometer",
        71 to "sensors.fuel",
        30 to "driving.averageConsumption",
        78 to "sensors.temperatureExterior",
        79 to "sensors.temperatureInterior",
        70 to "sensors.doors",
        81 to "sensors.trunk",
        104 to "sensors.lid",
        21 to "controls.sunroof",
        23 to "controls.windowDriverFront",
        25 to "controls.windowPassengerFront",
        24 to "controls.windowDriverRear",
        26 to "controls.windowPassengerRear",
    )

    /** ultimo valore ricevuto per ogni proprieta' (testo JSON di AAIdrive) */
    @Volatile var grezzi: Map<String, String> = emptyMap()
    @Volatile var ultimoDato = 0L

    fun aaidrive(ctx: Context): Boolean = try {
        ctx.packageManager.resolveContentProvider(AUTORITA, 0) != null
    } catch (e: Exception) {
        false
    }

    /** legge le proprieta' da AAIdrive finche' il servizio e' acceso; dopo() riceve i valori a ogni cambio */
    class Lettore(ctx: Context, private val dopo: (Map<String, String>) -> Unit) {
        private val app = ctx.applicationContext
        private val filo = HandlerThread("datiauto").apply { start() }
        private val mano = Handler(filo.looper)
        @Volatile private var attivo = false

        private val osservatore = object : ContentObserver(mano) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // AAIdrive avvisa sia per numero sia per nome: basta seguire il numero
                val n = uri?.lastPathSegment?.toIntOrNull() ?: return
                if (attivo && n in PROPRIETA) leggi(n)
            }
        }

        private val giro = object : Runnable {
            override fun run() {
                if (!attivo) return
                for (n in PROPRIETA.keys) leggi(n)
                mano.postDelayed(this, MANTENIMENTO_MS)
            }
        }

        fun avvia() {
            if (attivo || !aaidrive(app)) return
            attivo = true
            grezzi = emptyMap()                  // ogni accensione riparte da zero: il primo valore e' quello di partenza
            try {
                app.contentResolver.registerContentObserver(Uri.parse(BASE), true, osservatore)
            } catch (e: Exception) {
                // senza permesso o senza AAIdrive si resta col giro di lettura, che a sua volta non trova niente
            }
            mano.post(giro)
        }

        fun ferma() {
            attivo = false
            try { app.contentResolver.unregisterContentObserver(osservatore) } catch (e: Exception) { }
            mano.removeCallbacksAndMessages(null)
            filo.quitSafely()
        }

        private fun leggi(n: Int) {
            val nome = PROPRIETA[n] ?: return
            try {
                app.contentResolver.query(Uri.parse("$BASE/$n"), null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return
                    val i = c.getColumnIndex("value")
                    if (i < 0) return
                    val v = c.getString(i) ?: return
                    if (grezzi[nome] == v) return
                    grezzi = grezzi + (nome to v)
                    ultimoDato = System.currentTimeMillis()
                    dopo(grezzi)
                }
            } catch (e: Exception) {
                // permesso mancante, AAIdrive non collegato o valore non ancora arrivato: si riprova al prossimo giro
            }
        }
    }

    // ---- lettura dei valori ---------------------------------------------------------------------------------------

    enum class Posto(val breve: String) { ANT_SX("ant. sx"), ANT_DX("ant. dx"), POST_SX("post. sx"), POST_DX("post. dx") }

    class Istantanea(
        val km: Double?,
        val litri: Double?,
        val autonomia: Double?,
        val consumo: Double?,                 // l/100 km medio dell'auto
        val esterna: Double?,
        val interna: Double?,
        val aperti: List<String>?,            // null = l'auto non ha mandato nessuno stato di porte/finestrini/tetto
    )

    private fun json(grezzi: Map<String, String>, nome: String): JSONObject? = grezzi[nome]?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }

    /** primo numero trovato in un valore {"chiave":n} o {"chiave":{"x":n,...}}, per gli stati semplici */
    private fun numero(o: JSONObject?, chiave: String): Double? {
        if (o == null || !o.has(chiave)) return null
        val v = o.opt(chiave)
        return when (v) {
            is Number -> v.toDouble()
            is JSONObject -> v.keys().asSequence().mapNotNull { (v.opt(it) as? Number)?.toDouble() }.firstOrNull()
            else -> null
        }
    }

    /** finestrino o tetto (AAIdrive parseWindowState): status 0 chiuso, 1 aperto in parte, 2 aperto; tilt = sollevato */
    private fun apertura(o: JSONObject?): String? {
        if (o == null) return null
        val stato = o.optInt("status", 0)
        val sollevato = o.optInt("tiltPosition", 0)
        val posizione = if (o.has("position")) o.optInt("position", 0) else o.optInt("openPosition", 0)
        if (posizione >= 150) return null                      // valore non valido
        return when {
            stato == 0 && sollevato == 0 -> "chiuso"
            sollevato > 0 && posizione == 0 -> "sollevato"
            posizione > 0 || stato > 0 -> "aperto"
            else -> "chiuso"
        }
    }

    fun istantanea(grezzi: Map<String, String>): Istantanea {
        val carburante = json(grezzi, "sensors.fuel")?.optJSONObject("fuel")
        val consumo = json(grezzi, "driving.averageConsumption")?.optJSONObject("averageConsumption")
        var visti = false
        val aperti = ArrayList<String>()

        // porte {"doors":{"driverFront":0,...}}: qualunque valore diverso da 0 = aperta
        json(grezzi, "sensors.doors")?.optJSONObject("doors")?.let { p ->
            visti = true
            val nomi = mapOf("driverFront" to Posto.ANT_SX, "passengerFront" to Posto.ANT_DX, "driverRear" to Posto.POST_SX, "passengerRear" to Posto.POST_DX)
            for (k in p.keys()) if (((p.opt(k) as? Number)?.toInt() ?: 0) != 0) aperti.add("porta " + (nomi[k]?.breve ?: k))
        }
        numero(json(grezzi, "sensors.trunk"), "trunk")?.let { visti = true; if (it != 0.0) aperti.add("portellone") }
        numero(json(grezzi, "sensors.lid"), "lid")?.let { visti = true; if (it != 0.0) aperti.add("cofano") }
        val finestrini = listOf(
            "windowDriverFront" to Posto.ANT_SX, "windowPassengerFront" to Posto.ANT_DX,
            "windowDriverRear" to Posto.POST_SX, "windowPassengerRear" to Posto.POST_DX,
        )
        for ((k, posto) in finestrini) apertura(json(grezzi, "controls.$k")?.optJSONObject(k))?.let { s ->
            visti = true
            if (s != "chiuso") aperti.add("finestrino ${posto.breve}" + if (s == "sollevato") " (sollevato)" else "")
        }
        apertura(json(grezzi, "controls.sunroof")?.optJSONObject("sunroof"))?.let { s ->
            visti = true
            if (s != "chiuso") aperti.add("tetto" + if (s == "sollevato") " (sollevato)" else "")
        }

        return Istantanea(
            km = json(grezzi, "driving.odometer")?.optDouble("odometer")?.takeIf { !it.isNaN() && it > 0 },
            litri = carburante?.optDouble("tanklevel")?.takeIf { !it.isNaN() && it > 0 },
            autonomia = carburante?.optDouble("range")?.takeIf { !it.isNaN() && it > 0 },
            consumo = consumo?.optDouble("averageConsumption1")?.takeIf { !it.isNaN() && it > 0 && it < 100 },
            esterna = json(grezzi, "sensors.temperatureExterior")?.optDouble("temperatureExterior")?.takeIf { !it.isNaN() && it > -60 && it < 90 },
            interna = json(grezzi, "sensors.temperatureInterior")?.optDouble("temperatureInterior")?.takeIf { !it.isNaN() && it > -60 && it < 90 },
            aperti = if (visti) aperti else null,
        )
    }
}
