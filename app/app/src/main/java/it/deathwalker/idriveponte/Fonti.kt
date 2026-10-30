package it.deathwalker.idriveponte

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Distributore(
    val marchio: String, val nome: String, var indirizzo: String,
    val prezzo: Double, val self: Boolean, val km: Double, val aggiornato: String, val id: Long,
    var logo: android.graphics.Bitmap? = null
)

class EventoTraffico(
    val strada: String, val tipo: String, val testo: String, val km: Double,
    val codaKm: Double, val ora: String, val gravita: Int      // 2 rosso (chiuso/incidente/coda), 1 arancio (lavori/rallentamenti), 0 giallo
)

class Allerta(val zona: String, val oggi: Int, val oggiRischi: String, val domani: Int, val domaniRischi: String) {
    /** 0 nessuna, 1 gialla, 2 arancione, 3 rossa */
    fun massima() = maxOf(oggi, domani)

    fun testoBreve(): String {
        val nomi = arrayOf("", "gialla", "arancione", "rossa")
        return when {
            oggi > 0 && domani > oggi -> "Allerta ${nomi[oggi]} oggi, ${nomi[domani]} domani"
            oggi > 0 -> "Allerta ${nomi[oggi]} oggi" + (if (oggiRischi.isNotEmpty()) " · $oggiRischi" else "")
            domani > 0 -> "Allerta ${nomi[domani]} domani" + (if (domaniRischi.isNotEmpty()) " · $domaniRischi" else "")
            else -> ""
        }
    }
}

/** Fonti pubbliche, tutte senza chiave: MIMIT (carburanti), Autostrade per l'Italia (traffico), Protezione Civile (allerte). */
object Fonti {

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dl = Math.toRadians(lat2 - lat1)
        val dn = Math.toRadians(lon2 - lon1)
        val h = sin(dl / 2) * sin(dl / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dn / 2) * sin(dn / 2)
        return 2 * r * asin(sqrt(h))
    }

    private fun post(ctx: Context, indirizzo: String, json: String): String {
        val url = URL(indirizzo)
        val rete = Rete.reteConInternet(ctx)
        val c = (rete?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) " + Marca.APP.replace(" ", "") + "/" + Stato.VERSIONE)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} da ${url.host}")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ carburanti (MIMIT Osservaprezzi) ----
    /** codici MIMIT: 1 benzina, 2 gasolio, 3 metano, 4 GPL */
    val CARBURANTI = linkedMapOf(1 to "Benzina", 2 to "Gasolio", 4 to "GPL", 3 to "Metano")

    fun carburanti(ctx: Context, lat: Double, lon: Double, tipo: Int, raggioKm: Int): List<Distributore> {
        val body = """{"points":[{"lat":$lat,"lng":$lon}],"radius":$raggioKm,"priceOrder":"asc","fuelType":"$tipo-x"}"""
        val j = JSONObject(post(ctx, "https://carburanti.mise.gov.it/ospzApi/search/zone", body))
        val r = j.optJSONArray("results") ?: return emptyList()
        val fmtIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val fmtOut = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).apply { timeZone = Dati.ROMA }
        val lista = ArrayList<Distributore>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val fuels = o.optJSONArray("fuels") ?: continue
            // prezzo del carburante scelto: il self se c'e', altrimenti il servito; il piu' basso fra le varianti
            var miglioreSelf = Double.MAX_VALUE
            var miglioreServito = Double.MAX_VALUE
            for (k in 0 until fuels.length()) {
                val f = fuels.getJSONObject(k)
                if (f.optInt("fuelId") != tipo) continue
                val p = f.optDouble("price")
                if (p.isNaN() || p <= 0.3) continue
                if (f.optBoolean("isSelf")) miglioreSelf = minOf(miglioreSelf, p) else miglioreServito = minOf(miglioreServito, p)
            }
            val self = miglioreSelf < Double.MAX_VALUE
            val prezzo = if (self) miglioreSelf else miglioreServito
            if (prezzo == Double.MAX_VALUE) continue
            val loc = o.optJSONObject("location")
            val dist = if (loc != null) km(lat, lon, loc.optDouble("lat"), loc.optDouble("lng")) else o.optString("distance").toDoubleOrNull() ?: 0.0
            val agg = try { fmtOut.format(fmtIn.parse(o.optString("insertDate"))!!) } catch (e: Exception) { "" }
            lista.add(
                Distributore(
                    o.optString("brand").ifEmpty { "Pompa bianca" }, o.optString("name"),
                    if (o.isNull("address")) "" else o.optString("address"), prezzo, self, dist, agg, o.optLong("id")
                )
            )
        }
        val ordinati = lista.sortedWith(compareBy({ it.prezzo }, { it.km })).take(7)
        // indirizzo dalla scheda dell'impianto (la ricerca non lo restituisce)
        for (d in ordinati) {
            if (d.indirizzo.isNotEmpty()) continue
            try {
                val s = JSONObject(Rete.scaricaTesto(ctx, "https://carburanti.mise.gov.it/ospzApi/registry/servicearea/${d.id}", 8000))
                d.indirizzo = s.optString("address").let { a -> a.substringBefore(" - ").ifEmpty { a } }
                    .lowercase(Locale.ITALY).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ITALY) } }
            } catch (e: Exception) { }
        }
        for (d in ordinati) d.logo = logo(ctx, d.marchio)
        return ordinati
    }

    // ------------------------------------------------------------------ loghi dei marchi ----
    private val loghi = HashMap<String, android.graphics.Bitmap?>()
    private var loghiLetti = false

    private fun chiaveMarchio(s: String) = Dati.normalizza(s).replace(" ", "")

    /** logo del marchio: dalla cartella del telefono, scaricata da MIMIT (registry/alllogos) al massimo ogni 7 giorni */
    fun logo(ctx: Context, marchio: String): android.graphics.Bitmap? {
        val cartella = java.io.File(ctx.filesDir, "loghi").apply { mkdirs() }
        val segno = java.io.File(cartella, ".scaricati")
        if (!loghiLetti || (!segno.exists() || System.currentTimeMillis() - segno.lastModified() > 7L * 24 * 60 * 60 * 1000)) {
            loghiLetti = true
            if (!segno.exists() || System.currentTimeMillis() - segno.lastModified() > 7L * 24 * 60 * 60 * 1000) {
                try { scaricaLoghi(ctx, cartella); segno.writeText(System.currentTimeMillis().toString()) } catch (e: Exception) { }
            }
        }
        val k = chiaveMarchio(marchio)
        if (k.isEmpty()) return null
        return loghi.getOrPut(k) {
            val f = java.io.File(cartella, "$k.png").takeIf { it.exists() }
                ?: cartella.listFiles()?.firstOrNull { it.name.endsWith(".png") && (k.startsWith(it.nameWithoutExtension) || it.nameWithoutExtension.startsWith(k)) && it.nameWithoutExtension.length >= 2 }
            f?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        }
    }

    private fun scaricaLoghi(ctx: Context, cartella: java.io.File) {
        val testo = Rete.scaricaTesto(ctx, "https://carburanti.mise.gov.it/ospzApi/registry/alllogos", 20000)
        val t = testo.trim()
        val elenco = if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t).optJSONArray("loghi") ?: org.json.JSONArray()
        for (i in 0 until elenco.length()) {
            val o = elenco.optJSONObject(i) ?: continue
            val k = chiaveMarchio(o.optString("bandiera"))
            if (k.isEmpty()) continue
            val marker = o.optJSONArray("logoMarkerList") ?: continue
            // si preferisce il logo pieno ("logo"), poi le altre misure; solo png/jpg (gli svg Android non li legge)
            val scelte = (0 until marker.length()).mapNotNull { marker.optJSONObject(it) }
                .filter { it.optString("estensione").lowercase(Locale.ROOT) in listOf("png", "jpg", "jpeg") }
                .sortedBy { if (it.optString("tipoFile") == "logo") 0 else 1 }
            for (m in scelte) {
                val b64 = m.optString("content").substringAfter("base64,")
                val byte = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (e: Exception) { continue }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(byte, 0, byte.size) ?: continue
                java.io.File(cartella, "$k.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                break
            }
        }
        loghi.clear()
    }

    // ------------------------------------------------------------------ traffico (Autostrade per l'Italia) ----
    fun traffico(ctx: Context, lat: Double, lon: Double, raggioKm: Int): List<EventoTraffico> {
        val j = JSONObject(Rete.scaricaTesto(ctx, "https://viabilita.autostrade.it/json/eventi.json?index=" + Math.random(), 15000))
        val ev = j.optJSONArray("events") ?: return emptyList()
        val fmt = SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = Dati.ROMA }
        val lista = ArrayList<EventoTraffico>()
        for (i in 0 until ev.length()) {
            val o = ev.getJSONObject(i)
            val testo = o.optString("t_des_it").replace(Regex("\\s+"), " ").trim()
            val tipo = o.optString("t_des_fra_it").trim()
            // gli avvisi delle aree di servizio (GPL, camper service...) non sono traffico
            if (testo.contains("area di servizio", true) || testo.contains("camper", true)) continue
            val d = km(lat, lon, o.optDouble("n_crd_lat"), o.optDouble("n_crd_lon"))
            if (d.isNaN() || d > raggioKm) continue
            val t = (tipo + " " + testo).lowercase(Locale.ITALY)
            val gravita = when {
                t.contains("chius") || t.contains("incident") || t.contains("coda") || t.contains("bloccat") -> 2
                t.contains("lavori") || t.contains("cantier") || t.contains("rallent") || t.contains("restring") -> 1
                else -> 0
            }
            val agg = o.optLong("d_agg")
            lista.add(
                EventoTraffico(
                    o.optString("t_des_str").replace("️", "").trim(), tipo, testo, d,
                    o.optDouble("lunghezzaCoda").takeIf { !it.isNaN() } ?: 0.0,
                    if (agg > 0) fmt.format(Date(agg)) else "", gravita
                )
            )
        }
        return lista.sortedWith(compareBy({ -it.gravita }, { it.km })).take(6).sortedBy { it.km }
    }

    // ------------------------------------------------------------------ allerte (Protezione Civile) ----
    private const val REPO = "https://raw.githubusercontent.com/pcm-dpc/DPC-Bollettini-Criticita-Idrogeologica-Idraulica/master/"
    private var bollettinoNome = ""
    private var bollettinoLetto = 0L
    private var zoneOggi: Zone? = null
    private var zoneDomani: Zone? = null

    /**
     * Zone di allerta di un giorno, dal TopoJSON ufficiale: proprieta' di ogni zona (nome, livelli, comuni)
     * e i suoi contorni, per trovare la zona sia dal nome del comune sia dalla posizione (punto nel poligono).
     */
    class Zone(testo: String) {
        val proprieta = ArrayList<JSONObject>()
        private val anelli = ArrayList<List<List<List<DoubleArray>>>>()  // per zona: poligoni -> anelli -> punti (lon, lat)

        init {
            val j = JSONObject(testo)
            val t = j.optJSONObject("transform")
            val sx = t?.optJSONArray("scale")?.optDouble(0) ?: 1.0
            val sy = t?.optJSONArray("scale")?.optDouble(1) ?: 1.0
            val tx = t?.optJSONArray("translate")?.optDouble(0) ?: 0.0
            val ty = t?.optJSONArray("translate")?.optDouble(1) ?: 0.0
            // archi: coordinate quantizzate e a differenze (formato TopoJSON)
            val ja = j.getJSONArray("arcs")
            val archi = ArrayList<List<DoubleArray>>(ja.length())
            for (i in 0 until ja.length()) {
                val pts = ja.getJSONArray(i)
                var x = 0.0; var y = 0.0
                val l = ArrayList<DoubleArray>(pts.length())
                for (k in 0 until pts.length()) {
                    val q = pts.getJSONArray(k)
                    if (t != null) { x += q.getDouble(0); y += q.getDouble(1); l.add(doubleArrayOf(x * sx + tx, y * sy + ty)) }
                    else l.add(doubleArrayOf(q.getDouble(0), q.getDouble(1)))
                }
                archi.add(l)
            }
            fun anello(r: org.json.JSONArray): List<DoubleArray> {
                val out = ArrayList<DoubleArray>()
                for (k in 0 until r.length()) {
                    val idx = r.getInt(k)
                    val arco = if (idx >= 0) archi[idx] else archi[idx.inv()].asReversed()
                    out.addAll(if (out.isEmpty()) arco else arco.drop(1))
                }
                return out
            }
            val obj = j.getJSONObject("objects")
            val chiave = obj.keys().next()
            val g = obj.getJSONObject(chiave).getJSONArray("geometries")
            for (i in 0 until g.length()) {
                val geo = g.getJSONObject(i)
                val p = geo.optJSONObject("properties") ?: continue
                val poligoni = ArrayList<List<List<DoubleArray>>>()
                val arcs = geo.optJSONArray("arcs")
                when (geo.optString("type")) {
                    "Polygon" -> if (arcs != null) poligoni.add((0 until arcs.length()).map { anello(arcs.getJSONArray(it)) })
                    "MultiPolygon" -> if (arcs != null) for (m in 0 until arcs.length()) {
                        val pol = arcs.getJSONArray(m)
                        poligoni.add((0 until pol.length()).map { anello(pol.getJSONArray(it)) })
                    }
                }
                proprieta.add(p)
                anelli.add(poligoni)
            }
        }

        /** zona che contiene il punto: primo anello = contorno, gli altri = buchi */
        fun perPunto(lat: Double, lon: Double): JSONObject? {
            for (z in proprieta.indices) {
                for (pol in anelli[z]) {
                    if (pol.isEmpty() || !dentro(pol[0], lon, lat)) continue
                    if ((1 until pol.size).none { dentro(pol[it], lon, lat) }) return proprieta[z]
                }
            }
            return null
        }

        private fun dentro(r: List<DoubleArray>, x: Double, y: Double): Boolean {
            var c = false
            var k = r.size - 1
            for (i in r.indices) {
                val xi = r[i][0]; val yi = r[i][1]; val xk = r[k][0]; val yk = r[k][1]
                if ((yi > y) != (yk > y) && x < (xk - xi) * (y - yi) / (yk - yi) + xi) c = !c
                k = i
            }
            return c
        }

        fun perComune(comune: String): JSONObject? {
            val cerca = Dati.normalizza(comune)
            if (cerca.isEmpty()) return null
            for (p in proprieta) {
                val c = p.optJSONArray("Comuni") ?: continue
                for (k in 0 until c.length()) if (Dati.normalizza(c.optString(k)) == cerca) return p
            }
            return null
        }
    }

    /** livello dall'etichetta ufficiale ("... / ALLERTA GIALLA") */
    private fun livello(s: String): Int {
        val u = s.uppercase(Locale.ITALY)
        return when {
            u.contains("ALLERTA ROSSA") -> 3
            u.contains("ALLERTA ARANCIONE") -> 2
            u.contains("ALLERTA GIALLA") -> 1
            else -> 0
        }
    }

    /** nome dell'ultimo bollettino (files/AAAAMMGG_HHMM.json) dagli ultimi commit del repository ufficiale */
    private fun ultimoBollettino(ctx: Context): String {
        val api = "https://api.github.com/repos/pcm-dpc/DPC-Bollettini-Criticita-Idrogeologica-Idraulica/commits?per_page=15"
        val commit = org.json.JSONArray(Rete.scaricaTesto(ctx, api, 10000))
        val re = Regex("^files/(\\d{8}_\\d{4})\\.json$")
        var migliore = ""
        for (i in 0 until commit.length()) {
            val sha = commit.getJSONObject(i).optString("sha")
            val det = JSONObject(Rete.scaricaTesto(ctx, "https://api.github.com/repos/pcm-dpc/DPC-Bollettini-Criticita-Idrogeologica-Idraulica/commits/$sha", 10000))
            val files = det.optJSONArray("files") ?: continue
            for (k in 0 until files.length()) {
                val m = re.find(files.getJSONObject(k).optString("filename")) ?: continue
                if (m.groupValues[1] > migliore) migliore = m.groupValues[1]
            }
            if (migliore.isNotEmpty()) break
        }
        // ripiego: il nome delle anteprime (files/preview/AAAAMMGG_HHMM_domani.png) indica lo stesso bollettino
        if (migliore.isEmpty()) {
            val rp = Regex("^files/preview/(\\d{8}_\\d{4})_")
            val det = JSONObject(Rete.scaricaTesto(ctx, "https://api.github.com/repos/pcm-dpc/DPC-Bollettini-Criticita-Idrogeologica-Idraulica/commits/" + commit.getJSONObject(0).optString("sha"), 10000))
            val files = det.optJSONArray("files")
            if (files != null) for (k in 0 until files.length()) {
                val m = rp.find(files.getJSONObject(k).optString("filename")) ?: continue
                if (m.groupValues[1] > migliore) migliore = m.groupValues[1]
            }
        }
        return migliore
    }

    private fun rischi(p: JSONObject): String {
        val nomi = listOf("Per rischio idrogeologico" to "idrogeologico", "Per rischio temporali" to "temporali", "Per rischio idraulico" to "idraulico")
        val livelli = nomi.map { (k, n) -> n to livello(p.optString(k)) }
        val max = livelli.maxOf { it.second }
        return if (max == 0) "" else livelli.filter { it.second == max }.joinToString(", ") { it.first }
    }

    /**
     * allerta del punto in cui sei (zona che contiene la posizione); se il punto non cade in nessuna zona
     * (es. in mare) si cerca il comune per nome. null se non si trova la zona.
     */
    fun allerta(ctx: Context, comune: String, lat: Double, lon: Double): Allerta? {
        val adesso = System.currentTimeMillis()
        if (adesso - bollettinoLetto > 3 * 60 * 60 * 1000L || zoneOggi == null) {
            val nome = ultimoBollettino(ctx)
            if (nome.isEmpty()) throw IllegalStateException("nessun bollettino trovato negli ultimi aggiornamenti del repository")
            if (nome != bollettinoNome || zoneOggi == null) {
                // il descrittore del bollettino dice dove stanno le zone di oggi e di domani
                val desc = JSONObject(Rete.scaricaTesto(ctx, REPO + "files/$nome.json", 10000))
                val urlOggi = desc.optJSONObject("today")?.optString("topo_json").orEmpty()
                    .ifEmpty { REPO + "files/topojson/${nome}_today.json" }
                val urlDomani = desc.optJSONObject("tomorrow")?.optString("topo_json").orEmpty()
                zoneOggi = Zone(Rete.scaricaTesto(ctx, urlOggi, 20000))
                zoneDomani = if (urlDomani.isEmpty()) null else try { Zone(Rete.scaricaTesto(ctx, urlDomani, 20000)) } catch (e: Exception) { null }
                bollettinoNome = nome
            }
            bollettinoLetto = adesso
        }
        return allertaDa(zoneOggi!!, zoneDomani, comune, lat, lon)
    }

    fun allertaDa(oggi: Zone, domani: Zone?, comune: String, lat: Double, lon: Double): Allerta? {
        val o = oggi.perPunto(lat, lon) ?: oggi.perComune(comune) ?: return null
        val nomeZona = o.optString("Nome zona")
        val d = domani?.let { z -> z.proprieta.firstOrNull { it.optString("Nome zona") == nomeZona } ?: z.perPunto(lat, lon) }
        return Allerta(
            nomeZona,
            livello(o.optString("Rappresentata nella mappa")), rischi(o),
            if (d != null) livello(d.optString("Rappresentata nella mappa")) else 0, if (d != null) rischi(d) else ""
        )
    }

    // ------------------------------------------------------------------ notizie locali (ANSA regionale) ----
    /** feed ANSA della regione (nome come lo da' la geocodifica: "Liguria", "Emilia-Romagna", ...) */
    fun feedRegione(regione: String): Pair<String, String>? {
        val r = Dati.normalizza(regione)
        val tabella = listOf(
            "abruzzo" to "abruzzo", "basilicata" to "basilicata", "calabria" to "calabria", "campania" to "campania",
            "emilia" to "emiliaromagna", "friuli" to "friuliveneziagiulia", "lazio" to "lazio", "liguria" to "liguria",
            "lombardia" to "lombardia", "marche" to "marche", "molise" to "molise", "piemonte" to "piemonte",
            "puglia" to "puglia", "sardegna" to "sardegna", "sicilia" to "sicilia", "toscana" to "toscana",
            "trentino" to "trentino", "umbria" to "umbria", "valle d aosta" to "valledaosta", "aosta" to "valledaosta",
            "veneto" to "veneto"
        )
        val slug = tabella.firstOrNull { r.contains(it.first) }?.second ?: return null
        return "https://www.ansa.it/$slug/notizie/${slug}_rss.xml" to regione
    }
}
