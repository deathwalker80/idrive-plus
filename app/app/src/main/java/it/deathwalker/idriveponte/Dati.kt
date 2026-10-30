package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class Ora(val ora: String, val codice: Int, val giorno: Boolean, val temp: Int, val pioggia: Int)
class Giorno(val nome: String, val codice: Int, val max: Int, val min: Int, val pioggia: Int)

class Meteo(
    val citta: String,
    val temp: Int, val percepita: Int, val codice: Int, val giorno: Boolean,
    val umidita: Int, val vento: Int, val direzione: String, val raffiche: Int, val pressione: Int,
    val max: String, val min: String, val pioggiaOggi: Int, val alba: String, val tramonto: String,
    val ore: List<Ora>, val giorni: List<Giorno>
)

class Notizia(val titolo: String, val ora: String, var testo: String, val link: String, var foto: Bitmap? = null)

class Citta(val nome: String, val lat: Double, val lon: Double, val regione: String = "", val provincia: String = "")

object Dati {

    val ROMA: TimeZone = TimeZone.getTimeZone("Europe/Rome")
    private const val RSS_ANSA = "https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml"
    const val MAX_NOTIZIE = 8

    // ---------------------------------------------------------------- meteo (open-meteo.com) ----
    fun meteo(ctx: Context, citta: Citta): Meteo {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${citta.lat}&longitude=${citta.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=Europe%2FRome&forecast_days=6"
        return meteoDaJson(Rete.scaricaTesto(ctx, url), citta.nome)
    }

    fun meteoDaJson(testo: String, nomeCitta: String): Meteo {
        val j = JSONObject(testo)
        val c = j.getJSONObject("current")
        val d = j.optJSONObject("daily")
        val h = j.optJSONObject("hourly")

        // prossime 8 ore a partire dall'ora corrente (ora italiana)
        val ore = ArrayList<Ora>()
        if (h != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = ROMA }
            val adesso = fmt.format(Date())
            val t = h.getJSONArray("time")
            for (i in 0 until t.length()) {
                if (ore.size >= 8) break
                val quando = t.getString(i)
                if (quando < adesso) continue
                ore.add(
                    Ora(
                        quando.substring(11, 13),
                        h.getJSONArray("weather_code").optInt(i),
                        h.getJSONArray("is_day").optInt(i) == 1,
                        h.getJSONArray("temperature_2m").optDouble(i).roundToInt(),
                        h.getJSONArray("precipitation_probability").optInt(i)
                    )
                )
            }
        }

        // prossimi 5 giorni (da domani)
        val nomi = arrayOf("Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab")
        val giorni = ArrayList<Giorno>()
        if (d != null) {
            val t = d.getJSONArray("time")
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ROMA }
            for (i in 1 until t.length()) {
                if (giorni.size >= 5) break
                val cal = Calendar.getInstance(ROMA).apply { time = fmt.parse(t.getString(i)) ?: Date() }
                giorni.add(
                    Giorno(
                        nomi[cal.get(Calendar.DAY_OF_WEEK) - 1],
                        d.getJSONArray("weather_code").optInt(i),
                        d.getJSONArray("temperature_2m_max").optDouble(i).roundToInt(),
                        d.getJSONArray("temperature_2m_min").optDouble(i).roundToInt(),
                        d.getJSONArray("precipitation_probability_max").optInt(i)
                    )
                )
            }
        }

        fun daily(nome: String): String =
            d?.optJSONArray(nome)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()?.toString() ?: "--"
        fun orario(nome: String): String =
            d?.optJSONArray(nome)?.optString(0)?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "--:--"

        return Meteo(
            citta = nomeCitta,
            temp = c.optDouble("temperature_2m").roundToInt(),
            percepita = c.optDouble("apparent_temperature").roundToInt(),
            codice = c.optInt("weather_code"),
            giorno = c.optInt("is_day") == 1,
            umidita = c.optDouble("relative_humidity_2m").roundToInt(),
            vento = c.optDouble("wind_speed_10m").roundToInt(),
            direzione = direzione(c.optDouble("wind_direction_10m")),
            raffiche = c.optDouble("wind_gusts_10m").roundToInt(),
            pressione = c.optDouble("pressure_msl").roundToInt(),
            max = daily("temperature_2m_max"),
            min = daily("temperature_2m_min"),
            pioggiaOggi = d?.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
            alba = orario("sunrise"),
            tramonto = orario("sunset"),
            ore = ore,
            giorni = giorni
        )
    }

    private fun direzione(gradi: Double): String {
        val p = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        if (gradi.isNaN()) return ""
        return p[(((gradi % 360) / 45).roundToInt()) % 8]
    }

    /** codice meteo WMO -> testo + nome icona */
    fun wmo(codice: Int): Pair<String, String> = when (codice) {
        0 -> "Sereno" to "sun"
        1 -> "Poco nuvoloso" to "partly"
        2 -> "Parz. nuvoloso" to "partly"
        3 -> "Nuvoloso" to "cloud"
        45, 48 -> "Nebbia" to "fog"
        51, 53, 55 -> "Pioviggine" to "rain"
        56, 57 -> "Pioviggine gelata" to "rain"
        61 -> "Pioggia debole" to "rain"
        63 -> "Pioggia" to "rain"
        65 -> "Pioggia forte" to "rain"
        66, 67 -> "Pioggia gelata" to "rain"
        71 -> "Neve debole" to "snow"
        73 -> "Neve" to "snow"
        75 -> "Neve forte" to "snow"
        77 -> "Nevischio" to "snow"
        80, 81 -> "Rovesci" to "rain"
        82 -> "Rovesci forti" to "rain"
        85, 86 -> "Rovesci di neve" to "snow"
        95, 96, 99 -> "Temporale" to "storm"
        else -> "--" to "cloud"
    }

    /** di notte sereno e poco nuvoloso diventano luna */
    fun iconaOra(tipo: String, giorno: Boolean): String = when {
        giorno -> tipo
        tipo == "sun" -> "moon"
        tipo == "partly" -> "moonpartly"
        else -> tipo
    }

    // ---------------------------------------------------------------- citta' (geocoding open-meteo) ----
    fun cercaCitta(ctx: Context, nome: String): Citta? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(nome.trim(), "UTF-8") + "&count=1&language=it&format=json"
        val j = JSONObject(Rete.scaricaTesto(ctx, url))
        val r = j.optJSONArray("results") ?: return null
        if (r.length() == 0) return null
        val o = r.getJSONObject(0)
        return Citta(
            o.optString("name", nome.trim()), o.getDouble("latitude"), o.getDouble("longitude"),
            o.optString("admin1", ""), o.optString("admin2", "")
        )
    }

    // ---------------------------------------------------------------- notizie (ANSA) ----
    /**
     * notizie da un feed RSS ANSA (nazionale o regionale) con foto e testo dalla pagina dell'articolo.
     * preferite: parole (citta', provincia) che portano in cima le notizie che le nominano.
     */
    fun notizie(ctx: Context, feed: String = RSS_ANSA, preferite: List<String> = emptyList()): List<Notizia> {
        val tutte = notizieDaRss(Rete.scarica(ctx, feed, 12000), if (preferite.isEmpty()) MAX_NOTIZIE else 60)
        val chiavi = preferite.map { normalizza(it) }.filter { it.length >= 3 }
        val lista = if (chiavi.isEmpty()) tutte else {
            val (vicine, altre) = tutte.partition { n -> val t = normalizza(n.titolo + " " + n.testo); chiavi.any { t.contains(it) } }
            (vicine + altre).take(MAX_NOTIZIE)
        }
        // foto e testo piu' lungo dalla pagina dell'articolo (anteprima per le condivisioni)
        for (n in lista) {
            if (n.link.isEmpty()) continue
            try {
                val html = Rete.scaricaTesto(ctx, n.link, 8000)
                    .let { if (it.length > 400000) it.substring(0, 400000) else it }
                val og = meta(html, "og:description")
                if (og.length > n.testo.length) n.testo = og
                val img = assoluto(meta(html, "og:image"), n.link)
                if (img.isNotEmpty()) n.foto = decodificaFoto(Rete.scarica(ctx, img, 10000))
            } catch (e: Exception) {
                // notizia senza foto: si usa la miniatura neutra
            }
        }
        return lista
    }

    fun notizieDaRss(xml: ByteArray, limite: Int = MAX_NOTIZIE): List<Notizia> {
        val lista = ArrayList<Notizia>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        var dentroItem = false
        var campo = ""
        var titolo = ""; var link = ""; var desc = ""; var data = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && lista.size < limite) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "item") { dentroItem = true; titolo = ""; link = ""; desc = ""; data = "" }
                    campo = p.name
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (dentroItem) {
                    val t = p.text ?: ""
                    when (campo) {
                        "title" -> titolo += t
                        "link" -> link += t
                        "description" -> desc += t
                        "pubDate" -> data += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item" && dentroItem) {
                        dentroItem = false
                        val tt = pulisci(titolo)
                        if (tt.isNotEmpty()) lista.add(Notizia(tt, oraDa(data), pulisci(desc), link.trim()))
                    }
                    campo = ""
                }
            }
            ev = p.next()
        }
        return lista
    }

    /** minuscole senza accenti, per confrontare nomi di comuni e testi */
    fun normalizza(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.ITALY), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun pulisci(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private fun oraDa(pubDate: String): String = try {
        val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(pubDate.trim())
        SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = ROMA }.format(d!!)
    } catch (e: Exception) {
        ""
    }

    private fun meta(html: String, prop: String): String {
        val p = Regex.escape(prop)
        val a = Regex("<meta[^>]+(?:property|name)=[\"']$p[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)
        val b = a ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$p[\"']", RegexOption.IGNORE_CASE).find(html)
        return b?.groupValues?.get(1)?.let { pulisci(it) } ?: ""
    }

    private fun assoluto(img: String, link: String): String {
        if (img.isEmpty()) return ""
        return try {
            URI(link).resolve(if (img.startsWith("//")) "https:$img" else img).toString()
        } catch (e: Exception) {
            ""
        }
    }

    /** decodifica ridotta: basta una larghezza di circa 940 px (foto grande della notizia aperta) */
    fun decodificaFoto(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        var campione = 1
        while (o.outWidth / (campione * 2) >= 940) campione *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = campione })
    }
}
