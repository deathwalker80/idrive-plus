package it.deathwalker.idriveponte

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rifornimenti di ogni auto del parco, riconosciuti da soli: il livello del serbatoio che l'iDrive manda (plugin Meteo)
 * sale di almeno 5 litri rispetto all'ultimo valore noto, anche fra un viaggio e l'altro (motore spento alla pompa).
 * L'indicatore puo' salire a scatti: le salite ravvicinate sono lo stesso rifornimento.
 * Per ogni rifornimento: quando, litri, livello dopo, chilometri dell'auto, dove (posizione del telefono) e, se il
 * telefono era a meno di 300 m da un distributore dell'elenco MIMIT, marchio, indirizzo e prezzo del carburante
 * dell'auto (il self se c'e'): la spesa e' una stima, il prezzo pagato davvero puo' essere diverso.
 * Gli ultimi 50 per auto, dal piu' recente. Tutto resta sul telefono.
 */
object Rifornimenti {

    const val MASSIMO = 50
    private const val MINIMO_L = 5.0
    /** salite del livello entro 5 minuti l'una dall'altra si sommano (indicatore che sale a scatti) */
    private const val SALITA_MS = 5 * 60 * 1000L
    /** dopo il primo scatto, per 20 minuti le salite allungano lo stesso rifornimento */
    private const val UNISCI_MS = 20 * 60 * 1000L
    private const val POSIZIONE_FRESCA_MS = 15 * 60 * 1000L
    private const val VICINO_KM = 0.3

    class Voce(
        val auto: String,
        val quando: Long,
        val litri: Double,
        val livello: Double,
        val km: Double?,
        val lat: Double?,
        val lon: Double?,
        val luogo: String,
        val marchio: String,
        val prezzo: Double?,
        val self: Boolean,
    ) {
        /** spesa stimata col prezzo del distributore (null se il distributore non e' stato riconosciuto) */
        fun spesa(): Double? = prezzo?.let { it * litri }

        fun json(): JSONObject = JSONObject().apply {
            put("auto", auto); put("quando", quando); put("litri", litri); put("livello", livello)
            km?.let { put("km", it) }
            lat?.let { put("lat", it) }
            lon?.let { put("lon", it) }
            put("luogo", luogo); put("marchio", marchio)
            prezzo?.let { put("prezzo", it) }
            put("self", self)
        }

        fun con(
            litri: Double = this.litri, livello: Double = this.livello, lat: Double? = this.lat, lon: Double? = this.lon,
            luogo: String = this.luogo, marchio: String = this.marchio, prezzo: Double? = this.prezzo, self: Boolean = this.self,
        ) = Voce(auto, quando, litri, livello, km, lat, lon, luogo, marchio, prezzo, self)

        companion object {
            fun da(o: JSONObject): Voce? = try {
                fun num(n: String) = o.optDouble(n).takeIf { !it.isNaN() }
                Voce(
                    o.getString("auto"), o.getLong("quando"), o.getDouble("litri"), o.optDouble("livello", 0.0),
                    num("km"), num("lat"), num("lon"), o.optString("luogo"), o.optString("marchio"), num("prezzo"),
                    o.optBoolean("self"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** stato del serbatoio di un'auto: ultimo livello letto e salita in corso */
    private class Serbatoio(var livello: Double, var salitaDa: Double, var salitaOra: Long, var rifornimento: Long, var base: Double)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun k(bt: String) = "rifornimenti:$bt"
    private fun kSerbatoio(bt: String) = "serbatoio:$bt"

    private fun leggi(ctx: Context, bt: String): MutableList<Voce> {
        val testo = prefs(ctx).getString(k(bt), null) ?: return ArrayList()
        return try {
            val a = JSONArray(testo)
            (0 until a.length()).mapNotNullTo(ArrayList()) { a.optJSONObject(it)?.let { o -> Voce.da(o) } }
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun scrivi(ctx: Context, bt: String, voci: List<Voce>) {
        if (voci.isEmpty()) {
            prefs(ctx).edit().remove(k(bt)).apply()
            return
        }
        val a = JSONArray()
        for (v in voci.sortedByDescending { it.quando }.take(MASSIMO)) a.put(v.json())
        prefs(ctx).edit().putString(k(bt), a.toString()).apply()
    }

    private fun serbatoio(ctx: Context, bt: String): Serbatoio? = try {
        prefs(ctx).getString(kSerbatoio(bt), null)?.let {
            val o = JSONObject(it)
            Serbatoio(o.getDouble("livello"), o.optDouble("salitaDa", -1.0), o.optLong("salitaOra"), o.optLong("rifornimento"), o.optDouble("base", -1.0))
        }
    } catch (e: Exception) {
        null
    }

    /** si scrive solo se e' cambiato qualcosa: il livello arriva ogni 2 secondi ma cambia di rado */
    private fun salvaSerbatoio(ctx: Context, bt: String, s: Serbatoio) {
        val o = JSONObject().put("livello", s.livello).put("salitaDa", s.salitaDa).put("salitaOra", s.salitaOra)
            .put("rifornimento", s.rifornimento).put("base", s.base).toString()
        if (o != prefs(ctx).getString(kSerbatoio(bt), null)) prefs(ctx).edit().putString(kSerbatoio(bt), o).apply()
    }

    /** i rifornimenti di un'auto, dal piu' recente */
    @Synchronized
    fun elenco(ctx: Context, auto: String?): List<Voce> = leggi(ctx, auto?.trim().orEmpty()).sortedByDescending { it.quando }

    /** i rifornimenti di tutte le auto del parco, dal piu' recente */
    @Synchronized
    fun tutti(ctx: Context): List<Voce> = (Parco.elenco(ctx) + "").distinct().flatMap { leggi(ctx, it) }.sortedByDescending { it.quando }

    /**
     * nuovo livello del serbatoio dall'iDrive. Ritorna il rifornimento appena riconosciuto (nuovo o allungato),
     * null se non e' cambiato niente. adesso si puo' passare per le prove.
     */
    @Synchronized
    fun livello(ctx: Context, auto: String?, litri: Double?, km: Double?, adesso: Long = System.currentTimeMillis()): Voce? {
        if (litri == null || litri <= 0) return null
        val bt = auto?.trim().orEmpty()
        val s = serbatoio(ctx, bt)
        if (s == null) {
            salvaSerbatoio(ctx, bt, Serbatoio(litri, -1.0, 0L, 0L, -1.0))
            return null
        }
        val prima = s.livello
        var trovato: Voce? = null
        when {
            litri > prima -> {
                // salita: si somma a quella in corso se e' recente, altrimenti ne comincia una nuova
                if (s.salitaDa < 0 || adesso - s.salitaOra > SALITA_MS) s.salitaDa = prima
                s.salitaOra = adesso
                val attivo = s.rifornimento != 0L && adesso - s.rifornimento <= UNISCI_MS
                if (attivo && s.base >= 0) {
                    trovato = allunga(ctx, bt, s.rifornimento, litri - s.base, litri)
                } else if (litri - s.salitaDa >= MINIMO_L) {
                    s.base = s.salitaDa
                    s.rifornimento = adesso
                    trovato = Voce(bt, adesso, litri - s.base, litri, km, null, null, "", "", null, false)
                    val voci = leggi(ctx, bt)
                    voci.add(trovato)
                    scrivi(ctx, bt, voci)
                }
            }
            litri < prima - 1.0 -> s.salitaDa = -1.0          // scende (si guida): nessuna salita in corso
        }
        if (s.rifornimento != 0L && adesso - s.rifornimento > UNISCI_MS) { s.rifornimento = 0L; s.base = -1.0 }
        s.livello = litri
        salvaSerbatoio(ctx, bt, s)
        return trovato
    }

    private fun allunga(ctx: Context, bt: String, quando: Long, litri: Double, livello: Double): Voce? {
        val voci = leggi(ctx, bt)
        val i = voci.indexOfFirst { it.quando == quando }
        if (i < 0) return null
        if (litri <= voci[i].litri) return null
        voci[i] = voci[i].con(litri = litri, livello = livello)
        scrivi(ctx, bt, voci)
        return voci[i]
    }

    /** dove e da chi: posizione del telefono e distributore MIMIT piu' vicino (rete: mai sul thread principale) */
    fun completa(ctx: Context, v: Voce) {
        val l = Posizione.ultima(ctx)?.takeIf { System.currentTimeMillis() - it.time < POSIZIONE_FRESCA_MS } ?: return
        var luogo = ""
        var marchio = ""
        var prezzo: Double? = null
        var self = false
        try {
            val vicino = Fonti.carburanti(ctx, l.latitude, l.longitude, Parco.carburante(ctx, v.auto), 1)
                .minByOrNull { it.km }?.takeIf { it.km <= VICINO_KM }
            if (vicino != null) {
                marchio = vicino.marchio
                luogo = vicino.indirizzo
                prezzo = vicino.prezzo
                self = vicino.self
            }
        } catch (e: Exception) {
            // MIMIT non risponde: resta la posizione
        }
        if (luogo.isEmpty()) luogo = Posizione.indirizzo(ctx, l.latitude, l.longitude)
        synchronized(this) {
            val voci = leggi(ctx, v.auto)
            val i = voci.indexOfFirst { it.quando == v.quando }
            if (i < 0) return
            voci[i] = voci[i].con(lat = l.latitude, lon = l.longitude, luogo = luogo, marchio = marchio, prezzo = prezzo, self = self)
            scrivi(ctx, v.auto, voci)
        }
    }

    @Synchronized
    fun cancella(ctx: Context, auto: String) {
        val bt = auto.trim()
        prefs(ctx).edit().remove(k(bt)).remove(kSerbatoio(bt)).apply()
    }
}
