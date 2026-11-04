package it.deathwalker.idriveponte

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storico dei viaggi di ogni auto del parco. Quando un viaggio finisce (telefono scollegato dal Bluetooth dell'auto)
 * ne resta il riassunto: partenza e arrivo (ora e luogo), durata, km e carburante consumato coi dati dell'iDrive.
 * Gli ultimi 100 viaggi per auto, dal piu' recente. I valori che arrivano subito dopo lo scollegamento aggiornano il
 * viaggio appena chiuso; se l'auto si ricollega entro pochi minuti il viaggio riprende e lascia lo storico.
 * Tutto resta sul telefono.
 */
object Storico {

    const val MASSIMO = 100
    /** un viaggio piu' corto di un minuto non e' un viaggio (Bluetooth che si collega e si stacca) */
    private const val MINIMO_MS = 60 * 1000L

    class Voce(
        val auto: String,
        val partenza: Long,
        val arrivo: Long,
        val da: String,
        val a: String,
        val km: Double?,
        val litri: Double?,
        val rifornito: Double,
    ) {
        val durata: Long get() = arrivo - partenza

        /** km fatti con un litro, solo con abbastanza strada e carburante perche' il numero abbia senso */
        fun kmLitro(): Double? {
            val k = km ?: return null
            val l = litri ?: return null
            return if (k >= 5 && l >= 0.3) k / l else null
        }

        fun json(): JSONObject = JSONObject().apply {
            put("auto", auto); put("partenza", partenza); put("arrivo", arrivo); put("da", da); put("a", a)
            km?.let { put("km", it) }
            litri?.let { put("litri", it) }
            put("rifornito", rifornito)
        }

        companion object {
            fun da(o: JSONObject): Voce? = try {
                Voce(
                    o.getString("auto"), o.getLong("partenza"), o.getLong("arrivo"), o.optString("da"), o.optString("a"),
                    o.optDouble("km").takeIf { !it.isNaN() }, o.optDouble("litri").takeIf { !it.isNaN() },
                    o.optDouble("rifornito", 0.0),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun k(bt: String) = "storico:$bt"

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
        for (v in voci.sortedByDescending { it.partenza }.take(MASSIMO)) a.put(v.json())
        prefs(ctx).edit().putString(k(bt), a.toString()).apply()
    }

    /** i viaggi di un'auto, dal piu' recente */
    @Synchronized
    fun elenco(ctx: Context, auto: String?): List<Voce> = leggi(ctx, auto?.trim().orEmpty()).sortedByDescending { it.partenza }

    /** i viaggi di tutte le auto del parco, dal piu' recente */
    @Synchronized
    fun tutti(ctx: Context): List<Voce> = (Parco.elenco(ctx) + "").distinct().flatMap { leggi(ctx, it) }.sortedByDescending { it.partenza }

    /** il viaggio finito entra nello storico (o lo aggiorna, se c'e' gia': stessa ora di partenza) */
    @Synchronized
    fun registra(ctx: Context, d: Viaggio.Dati) {
        if (d.inCorso || d.durata() < MINIMO_MS) return
        val voci = leggi(ctx, d.auto)
        voci.removeAll { it.partenza == d.partenza }
        voci.add(Voce(d.auto, d.partenza, d.arrivo, d.luogo, d.luogoArrivo, d.km(), d.litri(), d.rifornito))
        scrivi(ctx, d.auto, voci)
    }

    /** il viaggio e' ripreso (auto ricollegata entro pochi minuti): torna in corso e lascia lo storico */
    @Synchronized
    fun togli(ctx: Context, auto: String, partenza: Long) {
        val voci = leggi(ctx, auto)
        if (voci.removeAll { it.partenza == partenza }) scrivi(ctx, auto, voci)
    }

    @Synchronized
    fun cancella(ctx: Context, auto: String) {
        prefs(ctx).edit().remove(k(auto.trim())).apply()
    }
}
