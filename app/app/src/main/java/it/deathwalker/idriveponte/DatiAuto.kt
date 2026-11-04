package it.deathwalker.idriveponte

import org.json.JSONObject
import java.net.URLDecoder

/**
 * Dati dell'auto: li legge il plugin Meteo dentro l'iDrive dal CDS della testata (lo stesso meccanismo con cui
 * NBT-AAD legge la marcia) e li manda qui sul Wi-Fi dell'auto, con GET /auto?n=<proprieta'>&v=<valore JSON>.
 * Nessun collegamento Bluetooth in piu' e nessuna app BMW: solo lettura, all'auto non arriva niente.
 *
 * Nomi delle proprieta': IDriveConnectKit (CDSProperty.kt). Formati verificati sulla MINI F60 (Meteo.log, 4/11/2026):
 *   sensors.doors          {"doors":{"driver":0,"passenger":0,"driverRear":0,"passengerRear":0}}   0 chiusa, 1 aperta
 *   sensors.trunk / lid    {"trunk":0} / {"lid":0}                                                   0 chiuso, 1 aperto
 *   controls.window...     {"windowDriverFront":{"position":19,"status":1,"drive":1}}               status 0 chiuso,
 *                          1 aperto in parte, 2 aperto, 3 non valido (a quadro spento: position 127)
 *   controls.sunroof       {"sunroof":{"openPosition":0,"tiltPosition":0,"status":0}}             status 3 / 255 = non valido
 *   driving.averageConsumption {"averageConsumption":{"averageConsumption1":13.8,"averageConsumption2":14.2,"unit":4}}
 *                          unit 4 = km/l (la MINI mostra 14.2 km/l = averageConsumption2), 1 = l/100 km
 * I valori arrivano nelle unita' dell'auto: km, litri, gradi C.
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
        it.deathwalker.idriveponte.Stato.avvio = System.currentTimeMillis() / 1000     // l'oggetto Stato, non DatiAuto.Stato
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

    // ---- porte, finestrini, tetto, portellone e cofano ------------------------------------------------------------

    /** le 11 parti della pagina "Porte, finestrini e tetto"; DX = passeggero, SX = guidatore (guida a sinistra) */
    enum class Parte(val chiave: String, val etichetta: String, val femminile: Boolean) {
        TETTO("tetto", "Tetto", false),
        PORTELLONE("portellone", "Portellone", false),
        FINESTRINO_POST_DX("finestrino_post_dx", "Finestrino post. dx", false),
        FINESTRINO_ANT_DX("finestrino_ant_dx", "Finestrino ant. dx", false),
        PORTA_POST_DX("porta_post_dx", "Porta post. dx", true),
        PORTA_ANT_DX("porta_ant_dx", "Porta ant. dx", true),
        PORTA_POST_SX("porta_post_sx", "Porta post. sx", true),
        FINESTRINO_POST_SX("finestrino_post_sx", "Finestrino post. sx", false),
        FINESTRINO_ANT_SX("finestrino_ant_sx", "Finestrino ant. sx", false),
        PORTA_ANT_SX("porta_ant_sx", "Porta ant. sx", true),
        COFANO("cofano", "Cofano", false),
    }

    enum class Stato { CHIUSO, APERTO, SOLLEVATO }

    /** "chiusa", "aperto", "sollevato"... con l'accordo di genere della parte */
    fun parola(p: Parte, s: Stato): String = when (s) {
        Stato.CHIUSO -> if (p.femminile) "chiusa" else "chiuso"
        Stato.APERTO -> if (p.femminile) "aperta" else "aperto"
        Stato.SOLLEVATO -> "sollevato"
    }

    private val PORTE = mapOf(
        "driver" to Parte.PORTA_ANT_SX, "driverFront" to Parte.PORTA_ANT_SX,
        "passenger" to Parte.PORTA_ANT_DX, "passengerFront" to Parte.PORTA_ANT_DX,
        "driverRear" to Parte.PORTA_POST_SX, "passengerRear" to Parte.PORTA_POST_DX,
    )
    private val FINESTRINI = listOf(
        "windowDriverFront" to Parte.FINESTRINO_ANT_SX, "windowPassengerFront" to Parte.FINESTRINO_ANT_DX,
        "windowDriverRear" to Parte.FINESTRINO_POST_SX, "windowPassengerRear" to Parte.FINESTRINO_POST_DX,
    )

    private fun json(grezzi: Map<String, String>, nome: String): JSONObject? = grezzi[nome]?.let {
        try { JSONObject(it) } catch (e: Exception) { null }
    }

    /** 0 chiuso, 1 aperto; altro (valore non valido) = sconosciuto */
    private fun aperturaSemplice(v: Any?): Stato? = when ((v as? Number)?.toInt()) {
        0 -> Stato.CHIUSO
        1 -> Stato.APERTO
        else -> null
    }

    /** finestrino o tetto: status 0 chiuso, 1 aperto in parte, 2 aperto, 3 non valido; tilt = tetto sollevato */
    private fun apertura(o: JSONObject?): Stato? {
        if (o == null) return null
        val stato = o.optInt("status", -1)
        val posizione = if (o.has("position")) o.optInt("position", 0) else o.optInt("openPosition", 0)
        val sollevato = o.optInt("tiltPosition", 0)
        if (stato !in 0..2 || posizione >= 127 || sollevato >= 127) return null     // quadro spento, dato non valido
        return when {
            stato == 0 && posizione == 0 && sollevato == 0 -> Stato.CHIUSO
            sollevato > 0 && posizione == 0 -> Stato.SOLLEVATO
            else -> Stato.APERTO
        }
    }

    /** lo stato di ogni parte che l'auto ha mandato (le parti senza dato valido non ci sono) */
    fun parti(grezzi: Map<String, String>): Map<Parte, Stato> {
        val m = LinkedHashMap<Parte, Stato>()
        json(grezzi, "controls.sunroof")?.optJSONObject("sunroof")?.let { o -> apertura(o)?.let { m[Parte.TETTO] = it } }
        json(grezzi, "sensors.trunk")?.let { o -> aperturaSemplice(o.opt("trunk"))?.let { m[Parte.PORTELLONE] = it } }
        json(grezzi, "sensors.lid")?.let { o -> aperturaSemplice(o.opt("lid"))?.let { m[Parte.COFANO] = it } }
        json(grezzi, "sensors.doors")?.optJSONObject("doors")?.let { p ->
            for (k in p.keys()) { val parte = PORTE[k] ?: continue; aperturaSemplice(p.opt(k))?.let { m[parte] = it } }
        }
        for ((k, parte) in FINESTRINI) json(grezzi, "controls.$k")?.optJSONObject(k)?.let { o -> apertura(o)?.let { m[parte] = it } }
        return m
    }

    // ---- lettura dei valori ---------------------------------------------------------------------------------------

    class Istantanea(
        val km: Double?,
        val litri: Double?,
        val autonomia: Double?,
        val kmLitro: Double?,                 // consumo medio del computer di bordo, in km/l
        val esterna: Double?,
        val interna: Double?,
        val aperti: List<String>?,            // null = l'auto non ha mandato nessuno stato valido di porte/finestrini/tetto
    )

    /** consumo medio in km/l, dall'unita' dell'auto (4 km/l, 1 l/100 km, 2 mpg UK, 3 mpg US) */
    private fun kmLitro(o: JSONObject?): Double? {
        if (o == null) return null
        val v = o.optDouble("averageConsumption2").takeIf { !it.isNaN() && it > 0 }
            ?: o.optDouble("averageConsumption1").takeIf { !it.isNaN() && it > 0 } ?: return null
        val km = when (o.optInt("unit", 1)) {
            4 -> v
            2 -> v * 0.354006
            3 -> v * 0.425144
            else -> 100 / v
        }
        return km.takeIf { it in 1.0..99.0 }
    }

    fun istantanea(grezzi: Map<String, String>): Istantanea {
        val carburante = json(grezzi, "sensors.fuel")?.optJSONObject("fuel")
        val parti = parti(grezzi)
        val aperti = parti.filterValues { it != Stato.CHIUSO }.map { (p, s) ->
            p.etichetta.replaceFirstChar { it.lowercase() } + if (s == Stato.SOLLEVATO) " (sollevato)" else ""
        }
        return Istantanea(
            km = json(grezzi, "driving.odometer")?.optDouble("odometer")?.takeIf { !it.isNaN() && it > 0 },
            litri = carburante?.optDouble("tanklevel")?.takeIf { !it.isNaN() && it > 0 },
            autonomia = carburante?.optDouble("range")?.takeIf { !it.isNaN() && it > 0 },
            kmLitro = kmLitro(json(grezzi, "driving.averageConsumption")?.optJSONObject("averageConsumption")),
            esterna = json(grezzi, "sensors.temperatureExterior")?.optDouble("temperatureExterior")?.takeIf { !it.isNaN() && it > -60 && it < 90 },
            interna = json(grezzi, "sensors.temperatureInterior")?.optDouble("temperatureInterior")?.takeIf { !it.isNaN() && it > -60 && it < 90 },
            aperti = if (parti.isEmpty()) null else aperti,
        )
    }
}
