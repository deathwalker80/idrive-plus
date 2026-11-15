package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * Sagome di auto generiche (vista laterale, lato destro) per chi non vuole caricare una foto della sua auto:
 * carrozzerie disegnate da tools/sagome/sagome.py, nessun modello reale e nessun logo.
 * Ogni carrozzeria (assets/auto/sagome/<id>/) ha quattro livelli: ombre (grigi, alfa = carrozzeria), luci (riflessi
 * bianchi), tetto (maschera per il tetto a contrasto), parti (vetri, ruote, fari, ombra a terra), piu' i suoi 11 punti.
 * Il colore si applica con la stessa formula dell'anteprima di sagome.py: ombre x colore, luci sopra, parti sopra.
 */
object Sagome {

    class Carrozzeria(val id: String, val nome: String, val marche: List<String>)
    class Colore(val nome: String, val rgb: Int)
    class Scelta(val id: String, val colore: Int, val tetto: Int?) {
        fun codice() = "$id|$colore|${tetto ?: ""}"
    }

    class Elenco(val carrozzerie: List<Carrozzeria>, val colori: List<Colore>, val tetti: List<Pair<String, Int?>>)

    private var elenco: Elenco? = null

    @Synchronized
    fun elenco(ctx: Context): Elenco {
        elenco?.let { return it }
        val o = JSONObject(ctx.assets.open("auto/sagome/elenco.json").bufferedReader().use { it.readText() })
        val car = o.getJSONArray("carrozzerie")
        val carrozzerie = (0 until car.length()).map { i ->
            val c = car.getJSONObject(i)
            val m = c.getJSONArray("marche")
            Carrozzeria(c.getString("id"), c.getString("nome"), (0 until m.length()).map { m.getString(it) })
        }
        val col = o.getJSONArray("colori")
        val colori = (0 until col.length()).map { i ->
            val c = col.getJSONObject(i)
            Colore(c.getString("nome"), rgb(c.getJSONArray("rgb")))
        }
        val te = o.getJSONArray("tetti")
        val tetti = (0 until te.length()).map { i ->
            val t = te.getJSONObject(i)
            t.getString("nome") to (if (t.isNull("rgb")) null else rgb(t.getJSONArray("rgb")))
        }
        return Elenco(carrozzerie, colori, tetti).also { elenco = it }
    }

    private fun rgb(a: org.json.JSONArray) = (0xFF shl 24) or (a.getInt(0) shl 16) or (a.getInt(1) shl 8) or a.getInt(2)

    /** le carrozzerie della marca dell'app prima, poi le altre */
    fun ordinate(ctx: Context): List<Carrozzeria> {
        val tutte = elenco(ctx).carrozzerie
        return tutte.filter { Marca.AUTO in it.marche } + tutte.filter { Marca.AUTO !in it.marche }
    }

    fun nome(ctx: Context, id: String): String = elenco(ctx).carrozzerie.firstOrNull { it.id == id }?.nome ?: id

    fun nomeColore(ctx: Context, rgb: Int): String = elenco(ctx).colori.firstOrNull { it.rgb == rgb }?.nome ?: "colore"

    fun leggi(codice: String?): Scelta? {
        val p = codice?.split("|") ?: return null
        if (p.size != 3 || p[0].isEmpty()) return null
        val colore = p[1].toIntOrNull() ?: return null
        return Scelta(p[0], colore, p[2].toIntOrNull())
    }

    private fun livello(ctx: Context, id: String, nome: String): Bitmap? = try {
        ctx.assets.open("auto/sagome/$id/$nome").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })
        }
    } catch (e: Exception) {
        null
    }

    /** la sagoma colorata (ARGB, sfondo trasparente); null se la carrozzeria non c'e' */
    fun componi(ctx: Context, s: Scelta): Bitmap? {
        val ombre = livello(ctx, s.id, "ombre.png") ?: return null
        val luci = livello(ctx, s.id, "luci.png") ?: return null
        val tetto = livello(ctx, s.id, "tetto.png") ?: return null
        val parti = livello(ctx, s.id, "parti.png") ?: return null
        val w = ombre.width
        val h = ombre.height
        if (luci.width != w || tetto.width != w || parti.width != w || luci.height != h || tetto.height != h || parti.height != h) return null
        val n = w * h
        val om = IntArray(n).also { ombre.getPixels(it, 0, w, 0, 0, w, h) }
        val lu = IntArray(n).also { luci.getPixels(it, 0, w, 0, 0, w, h) }
        val te = IntArray(n).also { tetto.getPixels(it, 0, w, 0, 0, w, h) }
        val pa = IntArray(n).also { parti.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(n)
        val cr = (s.colore shr 16 and 255) / 255f
        val cg = (s.colore shr 8 and 255) / 255f
        val cb = (s.colore and 255) / 255f
        val t = s.tetto
        val tr = if (t != null) (t shr 16 and 255) / 255f else cr
        val tg = if (t != null) (t shr 8 and 255) / 255f else cg
        val tb = if (t != null) (t and 255) / 255f else cb
        for (i in 0 until n) {
            val a = (om[i] ushr 24) / 255f
            val pA = (pa[i] ushr 24) / 255f
            val outA = pA + a * (1f - pA)
            if (outA <= 0f) continue
            val g = (om[i] shr 16 and 255) / 255f
            val m = if (t != null) (te[i] shr 16 and 255) / 255f else 0f
            var r = g * (cr * (1f - m) + tr * m)
            var gg = g * (cg * (1f - m) + tg * m)
            var b = g * (cb * (1f - m) + tb * m)
            val l = (lu[i] ushr 24) / 255f
            r = r * (1f - l) + l
            gg = gg * (1f - l) + l
            b = b * (1f - l) + l
            val k = a * (1f - pA)
            val oR = ((pa[i] shr 16 and 255) / 255f * pA + r * k) / outA
            val og = ((pa[i] shr 8 and 255) / 255f * pA + gg * k) / outA
            val ob = ((pa[i] and 255) / 255f * pA + b * k) / outA
            out[i] = (canale(outA) shl 24) or (canale(oR) shl 16) or (canale(og) shl 8) or canale(ob)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun canale(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** gli 11 punti della carrozzeria (gia' al loro posto: niente da sistemare a mano) */
    fun punti(ctx: Context, id: String): Map<DatiAuto.Parte, Foto.Punto> = try {
        val o = JSONObject(ctx.assets.open("auto/sagome/$id/punti.json").bufferedReader().use { it.readText() }).getJSONObject("punti")
        val m = LinkedHashMap<DatiAuto.Parte, Foto.Punto>()
        for (p in DatiAuto.Parte.values()) {
            val q = o.optJSONObject(p.chiave) ?: continue
            m[p] = Foto.Punto(q.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f), q.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f), q.optBoolean("nascosto"))
        }
        m
    } catch (e: Exception) {
        emptyMap()
    }
}
