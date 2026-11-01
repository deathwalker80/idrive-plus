package it.deathwalker.idriveponte

import android.content.Context
import org.json.JSONObject

/**
 * Viaggio di ogni auto del parco: comincia quando il telefono si collega al Bluetooth dell'auto (luogo e ora di
 * partenza dal telefono), si aggiorna coi dati che il plugin Meteo legge nell'iDrive (DatiAuto) e finisce quando il
 * telefono si scollega.
 * Km dal contachilometri, carburante consumato dal livello del serbatoio (con i rifornimenti fatti nel frattempo),
 * temperature e aperture all'ultimo valore arrivato: a viaggio finito e' lo stato dell'auto al parcheggio.
 * Senza i dati dell'iDrive restano partenza e durata.
 */
object Viaggio {

    /** ricollegata entro 5 minuti (sosta breve, Bluetooth che salta): resta lo stesso viaggio */
    private const val RIPRESA_MS = 5 * 60 * 1000L
    /** i valori che arrivano entro un minuto dallo scollegamento sono ancora quelli del parcheggio */
    private const val CODA_MS = 60 * 1000L
    private const val RIFORNIMENTO_L = 3.0
    private const val SCRITTURA_MS = 5000L

    class Dati(
        val auto: String,
        val partenza: Long,
        var luogo: String,
        var arrivo: Long,                     // 0 = in corso
        var kmPartenza: Double?,
        var litriPartenza: Double?,
        var litriPrima: Double?,              // ultima lettura del serbatoio, per accorgersi dei rifornimenti
        var rifornito: Double,
        var grezzi: Map<String, String>,
        var aggiornato: Long,
    ) {
        val inCorso: Boolean get() = arrivo == 0L

        fun ora(): DatiAuto.Istantanea = DatiAuto.istantanea(grezzi)

        fun km(): Double? {
            val adesso = ora().km ?: return null
            val p = kmPartenza ?: return null
            return (adesso - p).takeIf { it >= 0 }
        }

        fun litri(): Double? {
            val adesso = ora().litri ?: return null
            val p = litriPartenza ?: return null
            return (p + rifornito - adesso).coerceAtLeast(0.0)
        }

        fun durata(adesso: Long = System.currentTimeMillis()): Long = (if (inCorso) adesso else arrivo) - partenza

        fun json(): String = JSONObject().apply {
            put("auto", auto); put("partenza", partenza); put("luogo", luogo); put("arrivo", arrivo)
            kmPartenza?.let { put("kmPartenza", it) }
            litriPartenza?.let { put("litriPartenza", it) }
            litriPrima?.let { put("litriPrima", it) }
            put("rifornito", rifornito)
            put("grezzi", JSONObject(grezzi as Map<*, *>))
            put("aggiornato", aggiornato)
        }.toString()

        companion object {
            fun da(testo: String): Dati? = try {
                val o = JSONObject(testo)
                val g = o.optJSONObject("grezzi")
                Dati(
                    o.getString("auto"), o.getLong("partenza"), o.optString("luogo"), o.optLong("arrivo"),
                    o.optDouble("kmPartenza").takeIf { !it.isNaN() }, o.optDouble("litriPartenza").takeIf { !it.isNaN() },
                    o.optDouble("litriPrima").takeIf { !it.isNaN() }, o.optDouble("rifornito", 0.0),
                    g?.keys()?.asSequence()?.associateWith { g.optString(it) } ?: emptyMap(),
                    o.optLong("aggiornato"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private val memoria = HashMap<String, Dati>()
    private val scritto = HashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun k(bt: String) = "viaggio:$bt"

    @Synchronized
    fun di(ctx: Context, auto: String?): Dati? {
        val bt = auto?.trim().orEmpty()
        memoria[bt]?.let { return it }
        val d = prefs(ctx).getString(k(bt), null)?.let { Dati.da(it) } ?: return null
        memoria[bt] = d
        return d
    }

    private fun salva(ctx: Context, d: Dati, forza: Boolean) {
        memoria[d.auto] = d
        val adesso = System.currentTimeMillis()
        if (!forza && adesso - (scritto[d.auto] ?: 0L) < SCRITTURA_MS) return
        scritto[d.auto] = adesso
        prefs(ctx).edit().putString(k(d.auto), d.json()).apply()
    }

    /** il telefono si e' collegato all'auto: nuovo viaggio, oppure lo stesso se ci si ricollega entro pochi minuti */
    @Synchronized
    fun inizia(ctx: Context, auto: String?) {
        val bt = auto?.trim().orEmpty()
        val adesso = System.currentTimeMillis()
        val d = di(ctx, bt)
        if (d != null && (d.inCorso || adesso - d.arrivo < RIPRESA_MS)) {
            d.arrivo = 0L
            salva(ctx, d, true)
            return
        }
        salva(ctx, Dati(bt, adesso, "", 0L, null, null, null, 0.0, emptyMap(), adesso), true)
    }

    /** luogo di partenza, appena il telefono trova l'indirizzo (solo se il viaggio non ne ha gia' uno) */
    @Synchronized
    fun impostaLuogo(ctx: Context, auto: String?, luogo: String) {
        val d = di(ctx, auto) ?: return
        if (!d.inCorso || d.luogo.isNotEmpty() || luogo.isEmpty()) return
        d.luogo = luogo
        salva(ctx, d, true)
    }

    /** nuovi valori dall'iDrive per l'auto attuale; forza = scrivi subito (fine del servizio) */
    @Synchronized
    fun aggiorna(ctx: Context, auto: String?, grezzi: Map<String, String>, forza: Boolean = false) {
        val bt = auto?.trim().orEmpty()
        val adesso = System.currentTimeMillis()
        var d = di(ctx, bt)
        if (d == null || (!d.inCorso && adesso - d.arrivo > CODA_MS)) {
            // dati senza un viaggio aperto (servizio acceso a mano): il viaggio parte adesso, senza luogo
            d = Dati(bt, adesso, "", 0L, null, null, null, 0.0, emptyMap(), adesso)
        }
        val ist = DatiAuto.istantanea(grezzi)
        if (d.kmPartenza == null) d.kmPartenza = ist.km
        if (d.litriPartenza == null) d.litriPartenza = ist.litri
        ist.litri?.let { l ->
            val prima = d.litriPrima
            if (prima != null && l - prima >= RIFORNIMENTO_L) d.rifornito += l - prima
            d.litriPrima = l
        }
        d.grezzi = grezzi
        d.aggiornato = adesso
        salva(ctx, d, forza)
    }

    /** il telefono si e' scollegato: il viaggio finisce, i valori restano come stato dell'auto al parcheggio */
    @Synchronized
    fun chiudi(ctx: Context, auto: String?) {
        val d = di(ctx, auto) ?: return
        if (!d.inCorso) return
        d.arrivo = System.currentTimeMillis()
        salva(ctx, d, true)
    }

    @Synchronized
    fun cancella(ctx: Context, auto: String) {
        val bt = auto.trim()
        memoria.remove(bt)
        scritto.remove(bt)
        prefs(ctx).edit().remove(k(bt)).apply()
    }
}
