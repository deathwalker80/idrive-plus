package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foto dell'auto per la pagina "Porte, finestrini e tetto" e i suoi 11 punti di ancoraggio, per ogni auto del parco.
 * - MINI Plus ha di serie la foto della MINI dell'autore (res/drawable-nodpi/foto_auto.png, solo in quella versione) coi
 *   punti gia' messi (assets/auto/punti-predefiniti.json); le altre app partono senza foto.
 * - Chiunque puo' scegliere una foto sua (avatar): si salva sul telefono, ridotta a 1400 px al massimo; i punti
 *   ripartono da quelli predefiniti e si spostano a mano dall'app (Sistema i punti).
 * Punti: x e y da 0 a 1 sulla foto intera; nascosto = parte sul lato che la foto non mostra (anello vuoto).
 */
object Foto {

    class Punto(val x: Float, val y: Float, val nascosto: Boolean)

    private const val LATO_MASSIMO = 1400
    private var cacheChiave = ""
    private var cache: Bitmap? = null
    private var ritaglio: Rect? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun nomeFile(bt: String) = bt.trim().replace(Regex("[^A-Za-z0-9]"), "_").ifEmpty { "auto" }

    private fun file(ctx: Context, bt: String) = File(File(ctx.filesDir, "avatar"), nomeFile(bt) + ".png")

    private fun kPunti(bt: String) = "punti:${bt.trim()}"

    /** la foto di serie (solo MINI Plus); 0 = nessuna */
    private fun risorsa(ctx: Context): Int = ctx.resources.getIdentifier("foto_auto", "drawable", ctx.packageName)

    fun haAvatar(ctx: Context, bt: String?): Boolean = file(ctx, bt.orEmpty()).isFile

    fun haFotoDiSerie(ctx: Context): Boolean = risorsa(ctx) != 0

    /** c'e' una foto da mostrare (avatar o di serie) */
    fun haFoto(ctx: Context, bt: String?): Boolean = haAvatar(ctx, bt) || haFotoDiSerie(ctx)

    /** firma che cambia quando cambia la foto o i punti (per ridisegnare la pagina) */
    fun firma(ctx: Context, bt: String?): String {
        val f = file(ctx, bt.orEmpty())
        return (if (f.isFile) "a${f.lastModified()}" else if (haFotoDiSerie(ctx)) "serie" else "nessuna") +
            "/" + (prefs(ctx).getString(kPunti(bt.orEmpty()), "") ?: "")
    }

    /** la foto dell'auto (avatar, altrimenti quella di serie), null se non c'e' */
    @Synchronized
    fun immagine(ctx: Context, bt: String?): Bitmap? {
        val f = file(ctx, bt.orEmpty())
        val chiave = if (f.isFile) f.path + f.lastModified() else "serie"
        if (chiave == cacheChiave && cache != null) return cache
        val opz = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = if (f.isFile) BitmapFactory.decodeFile(f.path, opz)
        else risorsa(ctx).takeIf { it != 0 }?.let { BitmapFactory.decodeResource(ctx.resources, it, opz) }
        cache = b
        cacheChiave = chiave
        ritaglio = b?.let { contorno(it) }
        return b
    }

    /** la parte della foto che contiene l'auto: senza i bordi trasparenti (foto PNG ritagliate) */
    @Synchronized
    fun contornoAuto(ctx: Context, bt: String?): Rect? {
        immagine(ctx, bt)
        return ritaglio
    }

    private fun contorno(b: Bitmap): Rect {
        if (!b.hasAlpha()) return Rect(0, 0, b.width, b.height)
        val w = b.width
        val h = b.height
        val riga = IntArray(w)
        var l = w; var t = h; var r = -1; var giu = -1
        for (y in 0 until h) {
            b.getPixels(riga, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((riga[x] ushr 24) >= 128) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                giu = y
            }
        }
        return if (r < 0) Rect(0, 0, w, h) else Rect(l, t, r + 1, giu + 1)
    }

    /** foto scelta dall'utente: si legge, si riduce e si salva come PNG; i punti ripartono da quelli predefiniti */
    fun impostaAvatar(ctx: Context, bt: String?, uri: Uri): Boolean {
        val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, misure) } ?: return false
        if (misure.outWidth <= 0 || misure.outHeight <= 0) return false
        var campione = 1
        while (max(misure.outWidth, misure.outHeight) / (campione * 2) >= LATO_MASSIMO) campione *= 2
        val opz = BitmapFactory.Options().apply { inSampleSize = campione; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val letta = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opz) } ?: return false
        val s = min(1f, LATO_MASSIMO.toFloat() / max(letta.width, letta.height))
        val b = if (s < 1f) Bitmap.createScaledBitmap(letta, (letta.width * s).roundToInt(), (letta.height * s).roundToInt(), true) else letta
        val f = file(ctx, bt.orEmpty())
        f.parentFile?.mkdirs()
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prefs(ctx).edit().remove(kPunti(bt.orEmpty())).apply()
        synchronized(this) { cacheChiave = "" }
        return true
    }

    /** torna alla foto di serie (o a nessuna foto) */
    fun togliAvatar(ctx: Context, bt: String?) {
        file(ctx, bt.orEmpty()).delete()
        prefs(ctx).edit().remove(kPunti(bt.orEmpty())).apply()
        synchronized(this) { cacheChiave = "" }
    }

    fun predefiniti(ctx: Context): Map<DatiAuto.Parte, Punto> = try {
        val o = JSONObject(ctx.assets.open("auto/punti-predefiniti.json").bufferedReader().use { it.readText() }).getJSONObject("punti")
        leggi(o)
    } catch (e: Exception) {
        emptyMap()
    }

    private fun leggi(o: JSONObject): Map<DatiAuto.Parte, Punto> {
        val m = LinkedHashMap<DatiAuto.Parte, Punto>()
        for (p in DatiAuto.Parte.values()) {
            val q = o.optJSONObject(p.chiave) ?: continue
            m[p] = Punto(q.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f), q.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f), q.optBoolean("nascosto"))
        }
        return m
    }

    /** i punti dell'auto: quelli sistemati a mano, altrimenti i predefiniti (le parti mancanti dai predefiniti) */
    fun punti(ctx: Context, bt: String?): Map<DatiAuto.Parte, Punto> {
        val base = predefiniti(ctx)
        val salvati = prefs(ctx).getString(kPunti(bt.orEmpty()), null)?.let { s -> try { leggi(JSONObject(s)) } catch (e: Exception) { null } }
        return if (salvati == null) base else base + salvati
    }

    fun salvaPunti(ctx: Context, bt: String?, punti: Map<DatiAuto.Parte, Punto>) {
        val o = JSONObject()
        for ((p, q) in punti) o.put(p.chiave, JSONObject().put("x", q.x.toDouble()).put("y", q.y.toDouble()).put("nascosto", q.nascosto))
        prefs(ctx).edit().putString(kPunti(bt.orEmpty()), o.toString()).apply()
    }

    fun ripristinaPunti(ctx: Context, bt: String?) {
        prefs(ctx).edit().remove(kPunti(bt.orEmpty())).apply()
    }

    /** l'auto tolta dal parco: via anche foto e punti */
    fun cancella(ctx: Context, bt: String) = togliAvatar(ctx, bt)
}
