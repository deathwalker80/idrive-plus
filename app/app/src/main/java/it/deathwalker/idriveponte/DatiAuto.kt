package it.deathwalker.idriveponte

import org.json.JSONObject
import java.net.URLDecoder

/**
 * Dati dell'auto: li legge il plugin Meteo dentro l'iDrive dal CDS della testata (lo stesso meccanismo con cui
 * NBT-AAD legge la marcia) e li manda qui sul Wi-Fi dell'auto, con GET /auto?n=<proprieta'>&v=<valore JSON>.
 * Nessun collegamento Bluetooth in piu' e nessuna app BMW: solo lettura, all'auto non arriva niente.
 *
 * Nomi delle proprieta': IDriveConnectKit (CDSProperty.kt); formati dei valori: AAIdrive (CDSMetrics.kt).
 * I valori arrivano nelle unita' dell'auto: km, litri, gradi C, consumo in l/100 km.
 */
object DatiAuto {

    /** le proprieta' che il plugin manda (stesso elenco di kProprieta in plugin/ponte_plugin.cpp) */
    val PROPRIETA = setOf(
        "driving.odometer", "sensors.fuel", "driving.averageConsumption",
        "sensors.temperatureExterior", "sensors.temperatureInterior",
        "sensors.doors", "sensors.trunk", "sensors.lid", "controls.sunroof",
        "controls.windowDriverFront", "controls.windowPassengerFront",
        "controls.windowDriverRear", "controls.windowPassengerRear",
    )
    private const val MAX_VALORE = 400

    /** ultimo valore ricevuto per ogni proprieta' (testo JSON del CDS) */
    @Volatile var grezzi: Map<String, String> = emptyMap()
    @Volatile var ultimoDato = 0L

    /** a ogni accensione del servizio si riparte da zero: il primo valore che arriva e' quello di partenza */
    fun azzera() {
        grezzi = emptyMap()
        ultimoDato = 0L
    }

    /** una richiesta /auto del plugin ("n=sensors.fuel&v=%7B...%7D"): true se il valore e' stato preso */
    fun ricevi(query: String): Boolean {
        var nome = ""
        var valore = ""
        for (coppia in query.split('&')) {
            val k = coppia.substringBefore('=')
            val v = try { URLDecoder.decode(coppia.substringAfter('=', ""), "UTF-8") } catch (e: Exception) { return false }
            if (k == "n") nome = v else if (k == "v") valore = v
        }
        if (nome !in PROPRIETA || valore.isEmpty() || valore.length > MAX_VALORE) return false
        if (try { JSONObject(valore); false } catch (e: Exception) { true }) return false
        grezzi = grezzi + (nome to valore)
        ultimoDato = System.currentTimeMillis()
        return true
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
