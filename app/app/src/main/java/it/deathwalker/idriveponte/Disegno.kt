package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Disegna le pagine per l'iDrive: PNG 1280x480 a sfondo trasparente, testo bianco con ombra leggera,
 * stessa impaginazione di meteo.php / notizie.php (tela di progetto 1200x440 scalata nell'area sicura).
 * Area sicura misurata in auto con la pagina larga: margini sx 10, dx 140 (freccia ">"), su 6, giu' 12.
 */
class Disegno(private val ctx: Context) {

    companion object {
        const val W = 1280
        const val H = 480
        private const val PL = 10f
        private const val PR = 140f
        private const val PT = 6f
        private const val PB = 12f
        private const val TELA_W = 1200f
        private const val TELA_H = 440f
        const val MINI_W = 63
        const val MINI_H = 54

        private const val BIANCO = 0xFFFFFFFF.toInt()
        private const val TENUE = 0xCCFFFFFF.toInt()          // 80%
        private const val AZZURRO = 0xFFBFE3FF.toInt()        // probabilita' di pioggia
        private const val RIGA = 0x40FFFFFF                   // separatori 25%
        private const val OMBRA = 0x73000000
        private const val OMBRA_TENUE = 0x66000000
    }

    private val normale: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val grassetto: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val icone = HashMap<String, Bitmap>()

    private fun icona(nome: String): Bitmap? = icone.getOrPut(nome) {
        ctx.assets.open("meteo/$nome.png").use { BitmapFactory.decodeStream(it) } ?: return null
    }

    private fun nuovaTela(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val scala = min((W - PL - PR) / TELA_W, (H - PT - PB) / TELA_H)
        c.translate(PL, PT)
        c.scale(scala, scala)
        return b to c
    }

    private fun png(b: Bitmap): ByteArray {
        // 2.33: PNG a 256 colori (circa 5 volte piu' piccolo); "Immagini per l'iDrive: piene" torna come prima
        if (PngLeggero.attivo(ctx)) {
            val leggero = try { PngLeggero.codifica(b) } catch (e: Throwable) { null }
            if (leggero != null) { b.recycle(); return leggero }
        }
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    private fun pennello(dim: Float, bold: Boolean, colore: Int, allinea: Paint.Align = Paint.Align.LEFT): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = dim
            typeface = if (bold) grassetto else normale
            color = colore
            textAlign = allinea
            if (colore == BIANCO) setShadowLayer(3f, 0f, 1f, OMBRA) else setShadowLayer(2f, 0f, 1f, OMBRA_TENUE)
        }

    /** scrive una riga con la casella "alla CSS": top = bordo alto della riga (altezza riga 1.15 x dimensione) */
    private fun scrivi(c: Canvas, s: String, x: Float, top: Float, p: Paint, altezzaRiga: Float = p.textSize * 1.15f) {
        val fm = p.fontMetrics
        val base = top + (altezzaRiga - (fm.descent - fm.ascent)) / 2f - fm.ascent
        c.drawText(s, x, base, p)
    }

    private fun linea(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Paint().apply { color = RIGA; style = Paint.Style.FILL }
        c.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), p)
    }

    private fun disegnaIcona(c: Canvas, nome: String, x: Float, y: Float, lato: Float) {
        val b = icona(nome) ?: return
        c.drawBitmap(b, null, RectF(x, y, x + lato, y + lato), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun intestazione(c: Canvas, sinistra: String, destra: String) {
        scrivi(c, sinistra, 0f, 4f, pennello(30f, true, BIANCO))
        if (destra.isNotEmpty()) scrivi(c, destra, TELA_W, 12f, pennello(18f, false, TENUE, Paint.Align.RIGHT))
        linea(c, 0f, 56f, TELA_W, 58f)
    }

    // ------------------------------------------------------------------------------ meteo ----
    /** pioggia prevista in millimetri: "0 mm", "0,4 mm", "12 mm" */
    private fun mm(v: Double): String = when {
        v < 0.05 -> "0 mm"
        v < 10 -> String.format(Locale.ITALY, "%.1f mm", v)
        else -> String.format(Locale.ITALY, "%.0f mm", v)
    }

    /**
     * pagina Meteo come quella di serie dell'iDrive (Foreca): a sinistra il tempo di adesso con l'icona grande,
     * a destra una colonna per giorno con data, giorno, icona, massima e minima (senza evidenziare: la pagina non si scorre)
     */
    fun paginaMeteo(m: Meteo, allerta: Allerta? = null, evidenzia: Int = -1): ByteArray = png(bitmapMeteo(m, allerta, evidenzia))

    /** oggi + i 5 giorni seguenti: le sei colonne della pagina Meteo */
    fun giorniMeteo(m: Meteo): List<Giorno> {
        val oggi = m.oggi ?: Giorno(
            "Oggi", if (m.codiceOggi >= 0) m.codiceOggi else m.codice,
            m.max.toIntOrNull() ?: m.temp, m.min.toIntOrNull() ?: m.temp, m.pioggiaOggi, m.pioggiaOggiMm,
            java.text.SimpleDateFormat("dd.MM.", Locale.ITALY).format(java.util.Date())
        )
        return listOf(oggi) + m.giorni.take(5)
    }

    /**
     * Meteo coi giorni selezionabili (plugin Meteo v12): dove cade sullo schermo 1280x480 la colonna del giorno i
     * (x, y, larghezza, altezza): li' il plugin mette il pulsante del giorno (Meteo.xml)
     */
    fun rettangoloGiorno(i: Int): IntArray {
        val scala = min((W - PL - PR) / TELA_W, (H - PT - PB) / TELA_H)
        val x0 = 424f
        val lc = (TELA_W - x0) / 6f
        return intArrayOf(
            (PL + (x0 + i * lc) * scala).toInt(), PT.toInt(),
            (lc * scala).toInt(), ((TELA_H - 8f) * scala).toInt()
        )
    }

    /** la colonna del giorno i ritagliata dalla pagina (immagine del pulsante) */
    fun colonnaMeteo(m: Meteo, i: Int): ByteArray {
        val pagina = bitmapMeteo(m, null, -1)
        val r = rettangoloGiorno(i)
        val col = Bitmap.createBitmap(pagina, r[0], r[1], r[2], r[3])
        pagina.recycle()
        return png(col)
    }

    /**
     * la pagina di un giorno, quando lo premi: icona, massima e minima, i dettagli del giorno (pioggia, vento, raffiche,
     * umidita', UV, alba e tramonto: quelli che la fonte da') e sotto il giorno ora per ora (oggi le prossime 8 ore,
     * gli altri giorni ogni 3 ore)
     */
    fun paginaGiornoMeteo(m: Meteo, i: Int): ByteArray {
        val g = giorniMeteo(m).getOrNull(i) ?: return paginaAvviso("Meteo", "Giorno non disponibile")
        val (b, c) = nuovaTela()
        intestazione(c, "${if (i == 0) "Oggi" else g.nome} ${g.data} · ${m.citta}", Dati.cielo(g.codice))

        // ---- a sinistra: icona, massima, minima, percepita
        disegnaIcona(c, Dati.iconaMeteo(g.codice, true), -6f, 64f, 210f)
        scrivi(c, "${g.max} °C", 230f, 82f, pennello(64f, true, BIANCO), 70f)
        scrivi(c, "minima ${g.min} °C", 230f, 160f, pennello(28f, false, TENUE))
        if (g.percMax != null && g.percMin != null)
            scrivi(c, "percepita ${g.percMax}° / ${g.percMin}°", 230f, 200f, pennello(20f, false, TENUE))

        // ---- a destra: i dettagli che ci sono, su due colonne
        val pioggia = when {
            m.inMillimetri -> mm(g.mm)
            g.mm >= 0.05 -> "${g.pioggia}% · ${mm(g.mm)}"
            else -> "${g.pioggia}%"
        }
        val dettagli = listOfNotNull(
            "Pioggia" to pioggia,
            if (g.ventoMax >= 0) "Vento" to "${g.ventoMax} km/h ${g.direzione}".trim() else null,
            if (g.raffiche >= 0) "Raffiche" to "${g.raffiche} km/h" else null,
            if (g.umidita >= 0) "Umidità" to "${g.umidita}%" else null,
            if (g.uv >= 0) "Indice UV" to String.format(Locale.ITALY, "%.0f", g.uv) else null,
            if (g.alba.isNotEmpty() && g.tramonto.isNotEmpty()) "Alba · tramonto" to "${g.alba} · ${g.tramonto}" else null,
        )
        val pEt = pennello(18f, false, TENUE)
        val pVal = pennello(26f, true, BIANCO)
        dettagli.forEachIndexed { k, (et, v) ->
            val x = 560f + (k / 3) * 330f
            val y = 70f + (k % 3) * 62f
            scrivi(c, et, x, y, pEt)
            scrivi(c, ellissi(v, pVal, 320f), x, y + 22f, pVal)
        }

        // ---- sotto: il giorno ora per ora
        val ore = if (i == 0) m.ore.take(8) else g.ore.take(8)
        if (ore.isNotEmpty()) {
            linea(c, 0f, 262f, TELA_W, 264f)
            val lo = TELA_W / 8f
            ore.forEachIndexed { k, o ->
                val cx = k * lo + lo / 2f
                scrivi(c, "${o.ora}:00", cx, 274f, pennello(19f, false, TENUE, Paint.Align.CENTER))
                disegnaIcona(c, Dati.iconaMeteo(o.codice, o.giorno), cx - 30f, 298f, 60f)
                scrivi(c, "${o.temp}°", cx, 362f, pennello(24f, true, BIANCO, Paint.Align.CENTER))
                if (!m.inMillimetri) scrivi(c, "☂ ${o.pioggia}%", cx, 396f, pennello(18f, false, AZZURRO, Paint.Align.CENTER))
            }
        }
        if (m.fonte != "open-meteo") scrivi(c, "dati ${m.fonte}", TELA_W, 418f, pennello(14f, false, TENUE, Paint.Align.RIGHT))
        return png(b)
    }

    /**
     * testo della pastiglia dell'allerta (larga al massimo 400, 14 di margine per parte): coi rischi se ci stanno,
     * altrimenti senza (i rischi restano nell'app), all'ultimo coi puntini
     */
    fun testoPastiglia(allerta: Allerta, pa: Paint = pennello(17f, true, BIANCO)): String {
        val spazio = 400f - 28f
        return listOf("⚠ ${allerta.testoBreve()}", "⚠ ${allerta.testoBreve(false)}").firstOrNull { pa.measureText(it) <= spazio }
            ?: ellissi("⚠ ${allerta.testoBreve(false)}", pa, spazio)
    }

    private fun bitmapMeteo(m: Meteo, allerta: Allerta?, evidenzia: Int): Bitmap {
        val (b, c) = nuovaTela()

        // ---- adesso (colonna sinistra, larga 400)
        scrivi(c, "Meteo attuale:", 0f, 0f, pennello(20f, false, TENUE))
        val pc = pennello(32f, true, BIANCO)
        scrivi(c, ellissi(m.citta, pc, 400f), 0f, 24f, pc)
        // allerta della Protezione Civile: pastiglia colorata sotto il nome della citta'
        if (allerta != null && allerta.testoBreve().isNotEmpty()) {
            val colore = when (allerta.massima()) { 3 -> 0xFFE53935.toInt(); 2 -> 0xFFFB8C00.toInt(); else -> 0xFFFDD835.toInt() }
            val testo = if (allerta.massima() >= 2) BIANCO else 0xFF1A1A1A.toInt()
            val pa = pennello(17f, true, testo).apply { clearShadowLayer() }
            val avviso = testoPastiglia(allerta, pa)
            val larg = pa.measureText(avviso) + 28f
            val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; setShadowLayer(3f, 0f, 1f, OMBRA) }
            c.drawRoundRect(RectF(0f, 70f, larg, 104f), 17f, 17f, fondo)
            scrivi(c, avviso, 14f, 75f, pa)
        }
        disegnaIcona(c, Dati.iconaMeteo(m.codice, m.giorno, m.raffiche >= 60 || m.vento >= 45), -8f, 110f, 236f)
        scrivi(c, "${m.temp} °C", 400f, 140f, pennello(64f, true, BIANCO, Paint.Align.RIGHT), 70f)
        val pd = pennello(22f, false, BIANCO, Paint.Align.RIGHT)
        scrivi(c, ellissi(Dati.cielo(m.codice), pd, 190f), 400f, 216f, pd)
        scrivi(c, "Percepita ${m.percepita}°", 400f, 250f, pennello(18f, false, TENUE, Paint.Align.RIGHT))
        val pl = pennello(18f, false, TENUE)
        scrivi(c, "Umidità ${m.umidita}% · Vento ${m.vento} km/h ${m.direzione}".trim(), 0f, 356f, pl)
        scrivi(c, if (m.inMillimetri) "Nuvole ${m.nuvole}% · Pressione ${m.pressione} hPa"
            else "Raffiche ${m.raffiche} km/h · Pressione ${m.pressione} hPa", 0f, 382f, pl)
        // MET Norway (dati CC BY 4.0) va citato: accanto ad alba e tramonto
        scrivi(c, "Alba ${m.alba} · Tramonto ${m.tramonto}" + if (m.fonte != "open-meteo") " · dati ${m.fonte}" else "", 0f, 408f, pl)

        // ---- giorni (a destra): oggi + i 5 seguenti, una colonna ciascuno
        val giorni = giorniMeteo(m)
        val x0 = 424f
        val lc = (TELA_W - x0) / 6f
        // il giorno scelto con la manopola evidenziato (plugin Meteo v12)
        if (evidenzia in giorni.indices) {
            val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; setShadowLayer(6f, 0f, 2f, 0x33000000) }
            c.drawRoundRect(RectF(x0 + evidenzia * lc + 4f, 0f, x0 + (evidenzia + 1) * lc - 4f, TELA_H - 8f), 14f, 14f, fondo)
        }
        val pData = pennello(28f, true, BIANCO, Paint.Align.CENTER)
        val pNome = pennello(23f, false, TENUE, Paint.Align.CENTER)
        val pMax = pennello(32f, true, BIANCO, Paint.Align.CENTER)
        val pMin = pennello(27f, false, TENUE, Paint.Align.CENTER)
        val pP = pennello(20f, false, AZZURRO, Paint.Align.CENTER)
        giorni.forEachIndexed { i, g ->
            val cx = x0 + i * lc + lc / 2f
            if (i > 0) linea(c, x0 + i * lc - 1f, 16f, x0 + i * lc, TELA_H - 24f)
            scrivi(c, g.data, cx, 22f, pData)
            scrivi(c, g.nome, cx, 60f, pNome)
            disegnaIcona(c, Dati.iconaMeteo(g.codice, true), cx - 62f, 104f, 124f)
            scrivi(c, "${g.max} °C", cx, 252f, pMax)
            scrivi(c, "${g.min} °C", cx, 298f, pMin)
            scrivi(c, if (m.inMillimetri) "☂ " + mm(g.mm) else "☂ ${g.pioggia}%", cx, 358f, pP)
        }
        return b
    }

    // ------------------------------------------------------------------------------ notizie ----
    fun paginaNotizia(n: Notizia, fonte: String = "ANSA"): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, n.ora.ifEmpty { "Notizia" }, fonte)
        val top = 78f
        val foto = n.foto
        val largTesto = if (foto != null) 690 else 1200
        if (foto != null) {
            val r = RectF(730f, top, 1200f, top + 314f)
            val ombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF; setShadowLayer(6f, 0f, 2f, 0x59000000) }
            c.drawRoundRect(r, 6f, 6f, ombra)
            disegnaCopertina(c, foto, r, 6f)
        }
        val pt = pennello(34f, true, BIANCO)
        val lt = blocco(n.titolo, pt, largTesto, 42f, 4)
        c.save(); c.translate(0f, top); lt.draw(c); c.restore()
        if (n.testo.isNotEmpty()) {
            val pd = pennello(25f, false, TENUE)
            val ld = blocco(n.testo, pd, largTesto, 33f, 4)
            c.save(); c.translate(0f, top + lt.height + 20f); ld.draw(c); c.restore()
        }
        return png(b)
    }

    fun paginaAvviso(titolo: String, testo: String): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, titolo, "")
        val l = blocco(testo, pennello(25f, false, TENUE), 1200, 33f, 4)
        c.save(); c.translate(0f, 78f); l.draw(c); c.restore()
        return png(b)
    }

    /**
     * scheda della notizia accanto alla lista (640x480, sfondo trasparente): foto in alto,
     * ora e fonte, titolo e inizio del testo. L'iDrive la mostra quando la riga della notizia e' selezionata.
     */
    fun anteprima(n: Notizia, fonte: String = "ANSA"): ByteArray {
        val b = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        var y = 0f
        val foto = n.foto
        if (foto != null) {
            val r = RectF(4f, 4f, 636f, 270f)
            val ombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF; setShadowLayer(6f, 0f, 2f, 0x59000000) }
            c.drawRoundRect(r, 10f, 10f, ombra)
            disegnaCopertina(c, foto, r, 10f)
            y = 286f
        }
        val testata = listOf(n.ora, fonte).filter { it.isNotEmpty() }.joinToString(" · ")
        scrivi(c, testata, 4f, y, pennello(22f, true, AZZURRO))
        y += 22f * 1.15f + 6f
        val lt = blocco(n.titolo, pennello(30f, true, BIANCO), 632, 37f, if (foto != null) 2 else 4)
        c.save(); c.translate(4f, y); lt.draw(c); c.restore()
        y += lt.height + 10f
        val righeTesto = ((480f - y) / 29f).toInt()
        if (n.testo.isNotEmpty() && righeTesto > 0) {
            val ld = blocco(n.testo, pennello(23f, false, TENUE), 632, 29f, righeTesto)
            c.save(); c.translate(4f, y); ld.draw(c); c.restore()
        }
        return png(b)
    }

    // ----------------------------------------------------------------- pagine con le schede da scegliere ----
    // (plugin Notizie, Notizie locali e Carburanti v13, come il Meteo v12: pulsanti con posizione e misura sopra uno
    // sfondo che il telefono ridisegna con la scheda scelta; premendo si apre la pagina della scheda)

    /** un riquadro della tela 1200x440 in coordinate dello schermo 1280x480 (x, y, larghezza, altezza) */
    private fun suSchermo(x: Float, y: Float, w: Float, h: Float): IntArray {
        val scala = min((W - PL - PR) / TELA_W, (H - PT - PB) / TELA_H)
        return intArrayOf((PL + x * scala).toInt(), (PT + y * scala).toInt(), (w * scala).toInt(), (h * scala).toInt())
    }

    /** il pezzo di pagina sotto un pulsante (immagine del pulsante) */
    private fun ritaglio(pagina: Bitmap, r: IntArray): ByteArray {
        val pezzo = Bitmap.createBitmap(pagina, r[0], r[1], r[2], r[3])
        pagina.recycle()
        return png(pezzo)
    }

    /** la scheda scelta con la manopola: alone e bordo nel colore della marca */
    private fun evidenziaScheda(c: Canvas, r: RectF, raggio: Float) {
        c.drawRoundRect(r, raggio, raggio, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x38FFFFFF; setShadowLayer(14f, 0f, 0f, Marca.ACCENTO and 0x00FFFFFF or 0xAA000000.toInt())
        })
        c.drawRoundRect(RectF(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f), raggio, raggio,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Marca.ACCENTO })
    }

    // ---- notizie "in primo piano": a sinistra la notizia scelta in grande, a destra l'elenco
    private val ppX = 606f
    private val ppY = 66f
    private val ppRiga = 46.5f

    /** dove cade sullo schermo la riga i dell'elenco: li' il plugin mette il pulsante (Notizie.xml, Locali.xml) */
    fun rettangoloRigaNotizia(i: Int): IntArray = suSchermo(ppX, ppY + i * ppRiga, TELA_W - ppX, ppRiga)

    /** la pagina con la notizia "scelta" in grande e la sua riga evidenziata */
    fun primoPiano(lista: List<Notizia>, titolo: String, fonte: String, scelta: Int): ByteArray =
        png(bitmapPrimoPiano(lista, titolo, fonte, scelta))

    /** la riga i senza evidenza (immagine del pulsante) */
    fun rigaNotizia(lista: List<Notizia>, titolo: String, fonte: String, i: Int): ByteArray =
        ritaglio(bitmapPrimoPiano(lista, titolo, fonte, -1), rettangoloRigaNotizia(i))

    private fun bitmapPrimoPiano(lista: List<Notizia>, titolo: String, fonte: String, scelta: Int): Bitmap {
        val (b, c) = nuovaTela()
        intestazione(c, titolo, "$fonte · ${lista.size} notizie")

        // a sinistra: foto, ora, titolo e inizio del testo della notizia scelta
        val n = lista.getOrNull(scelta)
        val grande = RectF(0f, 70f, ppX - 18f, TELA_H - 2f)
        c.drawRoundRect(grande, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF; setShadowLayer(6f, 0f, 2f, 0x40000000) })
        if (n != null) {
            var y = grande.top + 8f
            val foto = n.foto
            if (foto != null) {
                val r = RectF(grande.left + 8f, y, grande.right - 8f, y + 196f)
                disegnaCopertina(c, foto, r, 12f)
                y = r.bottom + 10f
            } else {
                y += 6f
            }
            val testata = listOf(n.ora, fonte).filter { it.isNotEmpty() }.joinToString(" · ")
            scrivi(c, testata, grande.left + 20f, y, pennello(18f, true, Marca.ACCENTO))
            y += 26f
            val lw = (grande.width() - 40f).toInt()
            val lt = blocco(n.titolo, pennello(26f, true, BIANCO), lw, 31f, if (foto != null) 2 else 4)
            c.save(); c.translate(grande.left + 20f, y); lt.draw(c); c.restore()
            y += lt.height + 8f
            val righe = ((grande.bottom - 10f - y) / 23f).toInt()
            if (n.testo.isNotEmpty() && righe > 0) {
                val ld = blocco(n.testo, pennello(18f, false, TENUE), lw, 23f, righe)
                c.save(); c.translate(grande.left + 20f, y); ld.draw(c); c.restore()
            }
        } else {
            val l = blocco("Gira la manopola per scegliere una notizia, premi per leggerla.", pennello(22f, false, TENUE),
                (grande.width() - 40f).toInt(), 28f, 3)
            c.save(); c.translate(grande.left + 20f, grande.centerY() - l.height / 2f); l.draw(c); c.restore()
        }

        // a destra: una riga per notizia (miniatura, ora, titolo), la scelta evidenziata
        lista.take(8).forEachIndexed { i, nn ->
            val y = ppY + i * ppRiga
            val riga = RectF(ppX, y + 2f, TELA_W, y + ppRiga - 2f)
            val sel = i == scelta
            if (sel) evidenziaScheda(c, riga, 12f)
            val mr = RectF(ppX + 8f, y + 6f, ppX + 64f, y + ppRiga - 6f)
            val f = nn.foto
            if (f != null) disegnaCopertina(c, f, mr, 6f)
            else c.drawRoundRect(mr, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF })
            val po = pennello(16f, true, if (sel) BIANCO else TENUE)
            scrivi(c, nn.ora, ppX + 74f, y + 13f, po)
            val x = ppX + 74f + max(po.measureText("00:00"), po.measureText(nn.ora)) + 12f
            val pt = pennello(18f, sel, if (sel) BIANCO else TENUE)
            scrivi(c, ellissi(nn.titolo, pt, TELA_W - x - 12f), x, y + 12f, pt)
        }
        return b
    }

    // ------------------------------------------------------------------------------ carburanti ----
    fun paginaCarburanti(lista: List<Distributore>, carburante: String, luogo: String, raggio: Int, scelto: Int = -1): ByteArray =
        png(bitmapCarburanti(lista, carburante, luogo, raggio, scelto))

    /** dove cade sullo schermo la scheda del distributore i: li' il plugin mette il pulsante (Carburanti.xml) */
    fun rettangoloDistributore(i: Int): IntArray = suSchermo((i % 3) * (388f + 18f), 74f + (i / 3) * (172f + 18f), 388f, 172f)

    /** la scheda del distributore i senza evidenza (immagine del pulsante) */
    fun schedaDistributore(lista: List<Distributore>, carburante: String, luogo: String, raggio: Int, i: Int): ByteArray =
        ritaglio(bitmapCarburanti(lista, carburante, luogo, raggio, -1), rettangoloDistributore(i))

    private fun bitmapCarburanti(lista: List<Distributore>, carburante: String, luogo: String, raggio: Int, scelto: Int): Bitmap {
        val (b, c) = nuovaTela()
        intestazione(c, "$carburante · $luogo", "MIMIT Osservaprezzi · entro $raggio km")
        if (lista.isEmpty()) {
            scrivi(c, "Nessun distributore con prezzo di $carburante entro $raggio km.", 0f, 90f, pennello(25f, false, TENUE))
            return b
        }
        // sei schede in due file da tre: logo, prezzo grande, marchio, indirizzo, distanza; la piu' conveniente in verde
        val minimo = lista.minOf { it.prezzo }
        val lw = 388f
        val lh = 172f
        val gap = 18f
        lista.take(6).forEachIndexed { i, d ->
            val x = (i % 3) * (lw + gap)
            val y = 74f + (i / 3) * (lh + gap)
            val r = RectF(x, y, x + lw, y + lh)
            val migliore = d.prezzo == minimo
            if (i == scelto) evidenziaScheda(c, RectF(r.left - 3f, r.top - 3f, r.right + 3f, r.bottom + 3f), 20f)
            val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (migliore) 0x3343A047 else 0x24FFFFFF; setShadowLayer(6f, 0f, 2f, 0x40000000) }
            c.drawRoundRect(r, 18f, 18f, fondo)
            val bordo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = if (migliore) 4f else 1.5f
                color = if (migliore) 0xFF66BB6A.toInt() else 0x40FFFFFF
            }
            c.drawRoundRect(RectF(r.left + 1, r.top + 1, r.right - 1, r.bottom - 1), 18f, 18f, bordo)

            // logo su tondo bianco (o iniziali del marchio)
            val lr = RectF(x + 18f, y + 18f, x + 98f, y + 98f)
            c.drawRoundRect(lr, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(3f, 0f, 1f, 0x40000000) })
            val logo = d.logo
            if (logo != null) {
                val sc = minOf((lr.width() - 12f) / logo.width, (lr.height() - 12f) / logo.height)
                val w = logo.width * sc
                val h = logo.height * sc
                c.drawBitmap(logo, null, RectF(lr.centerX() - w / 2, lr.centerY() - h / 2, lr.centerX() + w / 2, lr.centerY() + h / 2),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            } else {
                val iniziali = d.marchio.split(' ', '-').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase(Locale.ITALY)
                    .ifEmpty { "?" }
                val pi = pennello(34f, true, coloreMarchio(d.marchio), Paint.Align.CENTER).apply { clearShadowLayer() }
                scrivi(c, iniziali, lr.centerX(), lr.top + 20f, pi, 40f)
            }

            // prezzo
            val prezzo = String.format(Locale.ITALY, "%.3f", d.prezzo)
            val pp = pennello(50f, true, BIANCO)
            scrivi(c, prezzo, x + 116f, y + 14f, pp, 56f)
            val larg = pp.measureText(prezzo)
            scrivi(c, "€/l", x + 122f + larg, y + 34f, pennello(18f, false, TENUE), 22f)
            val etichetta = if (d.self) "SELF" else "SERVITO"
            val pe = pennello(15f, true, if (d.self) 0xFF0D2A3A.toInt() else 0xFF1A1A1A.toInt()).apply { clearShadowLayer() }
            val le = pe.measureText(etichetta) + 18f
            c.drawRoundRect(RectF(x + 118f, y + 74f, x + 118f + le, y + 98f), 12f, 12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (d.self) AZZURRO else 0xFFE0E0E0.toInt() })
            scrivi(c, etichetta, x + 127f, y + 77f, pe, 18f)
            if (migliore) {
                val pm = pennello(15f, true, 0xFF0B2E12.toInt()).apply { clearShadowLayer() }
                val t = "IL PIÙ CONVENIENTE"
                val lm = pm.measureText(t) + 18f
                c.drawRoundRect(RectF(x + 126f + le, y + 74f, x + 126f + le + lm, y + 98f), 12f, 12f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF81C784.toInt() })
                scrivi(c, t, x + 135f + le, y + 77f, pm, 18f)
            }

            // marchio, indirizzo, distanza
            val pdist = pennello(22f, true, BIANCO, Paint.Align.RIGHT)
            val dist = String.format(Locale.ITALY, "%.1f km", d.km)
            scrivi(c, dist, x + lw - 18f, y + 112f, pdist)
            val pm = pennello(22f, true, BIANCO)
            scrivi(c, ellissi(d.marchio, pm, lw - 54f - pdist.measureText(dist)), x + 18f, y + 112f, pm)
            val pv = pennello(17f, false, TENUE)
            val via = d.indirizzo.ifEmpty { d.nome }
            scrivi(c, ellissi(via + (if (d.aggiornato.isNotEmpty()) " · agg. ${d.aggiornato}" else ""), pv, lw - 36f), x + 18f, y + 140f, pv)
        }
        return b
    }

    /**
     * la scheda di un distributore, quando lo premi: logo, marchio e indirizzo, distanza, quanto costa rispetto alla
     * media dei distributori vicini e tutti i prezzi dell'impianto (quello cercato evidenziato)
     */
    fun paginaDistributore(d: Distributore, carburante: String, media: Double?): ByteArray {
        val (b, c) = nuovaTela()
        val km = String.format(Locale.ITALY, "%.1f km", d.km)
        intestazione(c, "${d.marchio} · $km", if (d.aggiornato.isNotEmpty()) "prezzi aggiornati ${d.aggiornato}" else "MIMIT Osservaprezzi")

        // a sinistra: chi e dove
        val sx = RectF(0f, 70f, 500f, TELA_H - 2f)
        c.drawRoundRect(sx, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF; setShadowLayer(6f, 0f, 2f, 0x40000000) })
        val lr = RectF(22f, 92f, 142f, 212f)
        c.drawRoundRect(lr, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(3f, 0f, 1f, 0x40000000) })
        val logo = d.logo
        if (logo != null) {
            val sc = minOf((lr.width() - 16f) / logo.width, (lr.height() - 16f) / logo.height)
            val w = logo.width * sc
            val h = logo.height * sc
            c.drawBitmap(logo, null, RectF(lr.centerX() - w / 2, lr.centerY() - h / 2, lr.centerX() + w / 2, lr.centerY() + h / 2),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            val iniziali = d.marchio.split(' ', '-').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase(Locale.ITALY)
                .ifEmpty { "?" }
            scrivi(c, iniziali, lr.centerX(), lr.top + 34f, pennello(44f, true, coloreMarchio(d.marchio), Paint.Align.CENTER).apply { clearShadowLayer() }, 52f)
        }
        val pm = pennello(28f, true, BIANCO)
        scrivi(c, ellissi(d.marchio, pm, 330f), 160f, 92f, pm)
        val dove = listOf(d.nome, d.indirizzo).filter { it.isNotEmpty() && !it.equals(d.marchio, true) }.distinct().joinToString("\n")
        if (dove.isNotEmpty()) {
            val l = blocco(dove, pennello(17f, false, TENUE), 320, 23f, 4)
            c.save(); c.translate(160f, 132f); l.draw(c); c.restore()
        }
        scrivi(c, "Distanza", 22f, 240f, pennello(17f, false, TENUE))
        scrivi(c, "$km in linea d'aria", 22f, 262f, pennello(26f, true, BIANCO))
        if (media != null) {
            val diff = d.prezzo - media
            val testo = when {
                kotlin.math.abs(diff) < 0.0005 -> "nella media"
                diff < 0 -> String.format(Locale.ITALY, "%.3f €/l in meno", -diff)
                else -> String.format(Locale.ITALY, "%.3f €/l in più", diff)
            }
            scrivi(c, "Rispetto alla media dei distributori vicini", 22f, 318f, pennello(17f, false, TENUE))
            scrivi(c, testo, 22f, 340f, pennello(26f, true, if (diff < -0.0005) 0xFF81C784.toInt() else if (diff > 0.0005) 0xFFFFB74D.toInt() else BIANCO))
        }

        // a destra: i prezzi dell'impianto
        val prezzi = d.prezzi.ifEmpty { listOf(Prezzo(carburante, d.self, d.prezzo)) }.take(5)
        val ph = 62f
        val gap = (TELA_H - 70f - prezzi.size * ph).coerceAtMost(12f * (prezzi.size - 1)) / maxOf(1, prezzi.size - 1)
        prezzi.forEachIndexed { k, p ->
            val y = 70f + k * (ph + gap)
            val r = RectF(520f, y, TELA_W, y + ph)
            val cercato = p.nome.equals(carburante, true) && p.self == d.self && kotlin.math.abs(p.prezzo - d.prezzo) < 0.0005
            if (cercato) evidenziaScheda(c, r, 14f)
            else c.drawRoundRect(r, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF })
            val pn = pennello(24f, true, BIANCO)
            scrivi(c, ellissi(p.nome, pn, 300f), 542f, y + 16f, pn)
            scrivi(c, if (p.self) "self" else "servito", 870f, y + 20f, pennello(18f, false, TENUE))
            scrivi(c, String.format(Locale.ITALY, "%.3f €/l", p.prezzo), TELA_W - 20f, y + 12f, pennello(30f, true, BIANCO, Paint.Align.RIGHT))
        }
        return png(b)
    }

    /** colore per le iniziali dei marchi senza logo */
    private fun coloreMarchio(m: String): Int {
        val k = m.lowercase(Locale.ITALY)
        return when {
            "eni" in k || "agip" in k -> 0xFFF9A825.toInt()
            "q8" in k || "kuwait" in k -> 0xFF1565C0.toInt()
            "ip" == k.trim() || k.startsWith("api") || k.startsWith("ip ") -> 0xFF1E88E5.toInt()
            "tamoil" in k -> 0xFFE65100.toInt()
            "esso" in k -> 0xFFC62828.toInt()
            "shell" in k -> 0xFFD32F2F.toInt()
            "total" in k -> 0xFFD84315.toInt()
            else -> 0xFF37474F.toInt()
        }
    }

    // ------------------------------------------------------------------------------ stato auto ----
    /** ora dei dati dell'auto: "10:31" se sono di oggi, "07/11 18:30" se sono gli ultimi di un altro giorno */
    fun quandoDati(quando: Long): String {
        val giorno = java.text.SimpleDateFormat("yyyyMMdd", Locale.ITALY).apply { timeZone = Dati.ROMA }
        val oggi = giorno.format(java.util.Date()) == giorno.format(java.util.Date(quando))
        return java.text.SimpleDateFormat(if (oggi) "HH:mm" else "dd/MM HH:mm", Locale.ITALY)
            .apply { timeZone = Dati.ROMA }.format(java.util.Date(quando))
    }

    /**
     * stato dell'auto per l'iDrive, nello stile delle altre pagine (niente riquadri pieni: il fondo e' quello dell'iDrive).
     * A sinistra l'anello dell'autonomia, come gli strumenti tondi della MINI: l'arco e' il carburante nel serbatoio
     * rispetto al pieno (capienza = livello piu' alto visto dopo un rifornimento; senza, solo l'anello).
     * A destra chilometri, consumo, temperature, aperture e il viaggio, coi dati che il plugin Meteo legge nell'iDrive.
     */
    fun paginaAuto(
        nome: String, i: DatiAuto.Istantanea?, v: Viaggio.Dati?, rifornimento: Rifornimenti.Voce?, quando: Long, pieno: Double? = null,
    ): ByteArray {
        val (b, c) = nuovaTela()
        val ora = java.text.SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = Dati.ROMA }
        intestazione(c, nome, if (i != null && quando > 0) "Dati dell'iDrive · ${quandoDati(quando)}" else "")
        if (i == null || listOf(i.km, i.litri, i.autonomia, i.kmLitro, i.esterna, i.interna).all { it == null } && i.aperti == null) {
            val l = blocco("In attesa dei dati dell'auto. Li legge il plugin Meteo della chiavetta e li manda al telefono sul Wi-Fi " +
                "dell'auto, pochi secondi dopo l'accensione dell'iDrive.", pennello(25f, false, TENUE), 1200, 33f, 4)
            c.save(); c.translate(0f, 78f); l.draw(c); c.restore()
            return png(b)
        }
        val trattino = "–"
        val arancio = 0xFFFFA726.toInt()
        val verde = 0xFF66BB6A.toInt()

        // ---- anello dell'autonomia (colonna sinistra)
        val cx = 196f
        val cy = 262f
        val r = 150f
        val giro = RectF(cx - r, cy - r, cx + r, cy + r)
        val inizio = 135f
        val ampiezza = 270f
        val traccia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; color = 0x38FFFFFF
        }
        c.drawArc(giro, inizio, ampiezza, false, traccia)
        // tacche ogni quarto, sottili come quelle degli strumenti
        val pTacca = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; color = 0x66FFFFFF }
        for (k in 0..4) {
            val a = Math.toRadians((inizio + ampiezza * k / 4).toDouble())
            val r1 = r - 22f
            val r2 = r - 12f
            c.drawLine(cx + r1 * Math.cos(a).toFloat(), cy + r1 * Math.sin(a).toFloat(), cx + r2 * Math.cos(a).toFloat(), cy + r2 * Math.sin(a).toFloat(), pTacca)
        }
        val litri = i.litri
        val riserva = (i.autonomia ?: Double.MAX_VALUE) < 60
        if (litri != null && pieno != null && pieno >= litri && pieno > 0) {
            val parte = (litri / pieno).coerceIn(0.02, 1.0).toFloat()
            val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND
                color = if (riserva) arancio else Marca.ACCENTO
                setShadowLayer(8f, 0f, 0f, (if (riserva) arancio else Marca.ACCENTO) and 0x00FFFFFF or 0x99000000.toInt())
            }
            c.drawArc(giro, inizio, ampiezza * parte, false, arco)
        }
        val autonomia = i.autonomia?.let { String.format(Locale.ITALY, "%.0f", it) } ?: trattino
        scrivi(c, autonomia, cx, cy - 70f, pennello(92f, true, if (riserva) arancio else BIANCO, Paint.Align.CENTER), 96f)
        scrivi(c, "km di autonomia", cx, cy + 30f, pennello(20f, false, TENUE, Paint.Align.CENTER))
        scrivi(c, litri?.let { String.format(Locale.ITALY, "%.0f litri", it) } ?: trattino, cx, cy + 106f, pennello(26f, true, BIANCO, Paint.Align.CENTER))
        linea(c, 398f, 74f, 400f, TELA_H)

        // ---- colonna destra
        val x0 = 430f
        val ph = pennello(17f, true, BIANCO).apply { letterSpacing = 1f / 17f }
        val pl = pennello(16f, false, TENUE)
        val pv = pennello(30f, true, BIANCO)
        scrivi(c, "AUTO", x0, 74f, ph)
        val celle = listOf(
            "Chilometri" to (i.km?.let { String.format(Locale.ITALY, "%,.0f km", it) } ?: trattino),
            "Consumo medio" to (i.kmLitro?.let { String.format(Locale.ITALY, "%.1f km/l", it) } ?: trattino),
            "Esterna" to (i.esterna?.let { String.format(Locale.ITALY, "%.0f°", it) } ?: trattino),
            "Interna" to (i.interna?.let { String.format(Locale.ITALY, "%.0f°", it) } ?: trattino),
        )
        val wc = (TELA_W - x0) / 4f
        celle.forEachIndexed { k, (l, valore) ->
            val x = x0 + k * wc
            scrivi(c, l, x, 104f, pl)
            scrivi(c, valore, x, 126f, pv, 36f)
        }
        // aperture: pallino verde "tutto chiuso", arancio con cosa e' aperto
        val aperti = i.aperti
        val yA = 178f
        if (aperti != null) {
            val colore = if (aperti.isEmpty()) verde else arancio
            c.drawCircle(x0 + 9f, yA + 15f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; setShadowLayer(3f, 0f, 1f, OMBRA) })
            // qui solo il riassunto: cosa e' aperto, una voce per riga, sta nella pagina "Porte, finestrini e tetto"
            val testo = when (aperti.size) {
                0 -> "Porte, finestrini e tetto chiusi"
                1 -> "1 apertura: vedi Porte, finestrini e tetto"
                else -> "${aperti.size} aperture: vedi Porte, finestrini e tetto"
            }
            val pa = pennello(22f, aperti.isNotEmpty(), if (aperti.isEmpty()) BIANCO else arancio)
            scrivi(c, ellissi(testo, pa, TELA_W - x0 - 30f), x0 + 28f, yA, pa)
        }
        linea(c, x0, 226f, TELA_W, 227f)

        scrivi(c, if (v != null && v.inCorso) "QUESTO VIAGGIO" else "ULTIMO VIAGGIO", x0, 246f, ph)
        if (v != null) {
            val minuti = v.durata() / 60000
            val durata = if (minuti < 60) "$minuti min" else "${minuti / 60} h ${"%02d".format(minuti % 60)}"
            val viaggio = listOf(
                (v.km()?.let { String.format(Locale.ITALY, if (it < 10) "%.1f km" else "%.0f km", it) } ?: trattino) to "percorsi",
                durata to "in viaggio",
                (v.litri()?.let { String.format(Locale.ITALY, "%.1f l", it) } ?: trattino) to (if (v.rifornito > 0) "consumati (con rifornimento)" else "consumati"),
            )
            val wv = (TELA_W - x0) / 3f
            val pg = pennello(40f, true, BIANCO)
            viaggio.forEachIndexed { k, (valore, l) ->
                val x = x0 + k * wv
                scrivi(c, valore, x, 274f, pg, 46f)
                scrivi(c, ellissi(l, pl, wv - 16f), x, 322f, pl)
            }
            val partenza = "Partito alle ${ora.format(java.util.Date(v.partenza))}" + if (v.luogo.isNotEmpty()) " da ${v.luogo}" else ""
            scrivi(c, ellissi(partenza, pennello(19f, false, TENUE), TELA_W - x0), x0, 362f, pennello(19f, false, TENUE))
        } else {
            scrivi(c, "Nessun viaggio ancora", x0, 278f, pennello(24f, false, TENUE))
        }
        rifornimento?.let { rf ->
            val giorno = java.text.SimpleDateFormat("dd/MM", Locale.ITALY).apply { timeZone = Dati.ROMA }.format(java.util.Date(rf.quando))
            val pr = pennello(19f, false, TENUE)
            scrivi(c, ellissi(String.format(Locale.ITALY, "Ultimo rifornimento %s · %.0f litri", giorno, rf.litri), pr, TELA_W - x0), x0, 392f, pr)
        }
        return png(b)
    }

    // ------------------------------------------------------------------------------ porte, finestrini e tetto ----
    private val sinistraPorte = listOf(
        DatiAuto.Parte.TETTO, DatiAuto.Parte.PORTELLONE, DatiAuto.Parte.FINESTRINO_POST_DX,
        DatiAuto.Parte.FINESTRINO_ANT_DX, DatiAuto.Parte.PORTA_POST_DX, DatiAuto.Parte.PORTA_ANT_DX,
    )
    private val destraPorte = listOf(
        DatiAuto.Parte.PORTA_POST_SX, DatiAuto.Parte.FINESTRINO_POST_SX, DatiAuto.Parte.FINESTRINO_ANT_SX,
        DatiAuto.Parte.PORTA_ANT_SX, DatiAuto.Parte.COFANO,
    )

    /** "Tutto chiuso", "1 apertura", "3 aperture"; "Stato non arrivato" se l'auto non ha mandato niente */
    fun riassuntoAperture(parti: Map<DatiAuto.Parte, DatiAuto.Stato>): String {
        val aperte = parti.values.count { it != DatiAuto.Stato.CHIUSO }
        return when {
            parti.isEmpty() -> "Stato non arrivato"
            aperte == 0 -> "Tutto chiuso"
            aperte == 1 -> "1 apertura"
            else -> "$aperte aperture"
        }
    }

    /**
     * pagina "Porte, finestrini e tetto": la foto dell'auto al centro con un alone chiaro, 11 richiami su due colonne
     * (DX passeggero a sinistra, SX guidatore a destra), uno stato per riga. Pallino verde = chiuso, arancio = aperto o
     * sollevato, anello vuoto = parte sul lato che la foto non mostra, grigio = stato non arrivato.
     * Senza foto (app senza foto di serie e senza avatar) restano le due colonne coi pallini accanto al testo.
     */
    fun paginaPorte(
        foto: Bitmap?, ritaglio: android.graphics.Rect?, punti: Map<DatiAuto.Parte, Foto.Punto>,
        parti: Map<DatiAuto.Parte, DatiAuto.Stato>, quando: Long,
        mostra: Set<DatiAuto.Parte> = DatiAuto.Parte.values().toSet(),
    ): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, "Porte, finestrini e tetto",
            riassuntoAperture(parti) + if (quando > 0) "  ·  Dati dell'iDrive ${quandoDati(quando)}" else "")
        val verde = 0xFF66BB6A.toInt()
        val arancio = 0xFFFFA726.toInt()
        val grigio = 0x99FFFFFF.toInt()

        // ---- la foto: ritagliata all'auto, alta 345, centrata fra le due colonne
        val pos = HashMap<DatiAuto.Parte, Pair<Float, Float>>()
        if (foto != null) {
            val r = ritaglio ?: android.graphics.Rect(0, 0, foto.width, foto.height)
            val larghezzaMax = 640f
            var sc = 345f / r.height()
            if (r.width() * sc > larghezzaMax) sc = larghezzaMax / r.width()
            val cw = r.width() * sc
            val ch = r.height() * sc
            val cx0 = (TELA_W - cw) / 2f - 8f
            val cy0 = 78f + (345f - ch) / 2f
            val dove = RectF(cx0, cy0, cx0 + cw, cy0 + ch)
            // alone: la sagoma dell'auto sfocata, bianca al 35% (con margine: la sfocatura non si taglia)
            try {
                val sfoca = Paint().apply { maskFilter = android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
                val sagoma = Bitmap.createBitmap(foto, r.left, r.top, r.width(), r.height()).extractAlpha(sfoca, IntArray(2))
                val margine = (sagoma.width - r.width()) / 2f * sc
                c.drawBitmap(sagoma, null, RectF(dove.left - margine, dove.top - margine, dove.right + margine, dove.bottom + margine),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x59FFFFFF })
            } catch (e: Exception) {
                // senza alone se la sfocatura non e' disponibile
            }
            c.drawBitmap(foto, r, dove, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            for ((p, q) in punti) {
                pos[p] = (cx0 + (q.x * foto.width - r.left) * sc) to (cy0 + (q.y * foto.height - r.top) * sc)
            }
        } else {
            val pm = pennello(20f, false, TENUE, Paint.Align.CENTER)
            scrivi(c, "Scegli una foto della tua auto nell'app:", TELA_W / 2f - 8f, 210f, pm)
            scrivi(c, "Stato vettura, Foto dell'auto", TELA_W / 2f - 8f, 238f, pm)
        }

        val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x96FFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE }
        val bordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x78000000; strokeWidth = 1f; style = Paint.Style.STROKE }
        fun pallino(x: Float, y: Float, colore: Int, vuoto: Boolean) {
            if (vuoto) c.drawCircle(x, y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; strokeWidth = 2.5f; style = Paint.Style.STROKE })
            else {
                c.drawCircle(x, y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; setShadowLayer(3f, 0f, 1f, OMBRA) })
                c.drawCircle(x, y, 6f, bordo)
            }
        }
        fun richiamo(p: DatiAuto.Parte, x: Float, top: Float, aSinistra: Boolean) {
            val s = parti[p]
            val aperto = s != null && s != DatiAuto.Stato.CHIUSO
            val colore = when { s == null -> grigio; aperto -> arancio; else -> verde }
            val allinea = if (aSinistra) Paint.Align.RIGHT else Paint.Align.LEFT
            scrivi(c, p.etichetta, x, top, pennello(19f, false, TENUE, allinea))
            scrivi(c, s?.let { DatiAuto.parola(p, it) } ?: "–", x, top + 20f,
                pennello(21f, aperto, if (aperto) arancio else if (s == null) TENUE else BIANCO, allinea))
            val q = punti[p]
            val punto = pos[p]
            if (punto != null) {
                val ax = if (aSinistra) x + 6f else x - 14f
                val gomito = if (aSinistra) ax + 18f else ax - 18f
                val path = android.graphics.Path().apply { moveTo(ax, top + 16f); lineTo(gomito, top + 16f); lineTo(punto.first, punto.second) }
                c.drawPath(path, linea)
                pallino(punto.first, punto.second, colore, q?.nascosto == true)
            } else {
                // senza foto: il pallino accanto al nome
                pallino(if (aSinistra) x + 18f else x - 18f, top + 12f, colore, false)
            }
        }
        scrivi(c, "DX · PASSEGGERO", 244f, 66f, pennello(15f, true, TENUE, Paint.Align.RIGHT).apply { letterSpacing = 0.06f })
        scrivi(c, "SX · GUIDATORE", 945f, 66f, pennello(15f, true, TENUE).apply { letterSpacing = 0.06f })
        // solo le parti da mostrare, ricompattate dall'alto (il tetto non c'e' su tutte le auto)
        sinistraPorte.filter { it in mostra }.forEachIndexed { k, p -> richiamo(p, 244f, 92f + k * 58f, true) }
        destraPorte.filter { it in mostra }.forEachIndexed { k, p -> richiamo(p, 945f, 96f + k * 68f, false) }
        return png(b)
    }

    /** miniatura della riga "Riepilogo" (63x54): anello dell'autonomia col numero dentro */
    fun miniaturaRiepilogo(i: DatiAuto.Istantanea?): ByteArray {
        val b = Bitmap.createBitmap(MINI_W, MINI_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = MINI_W / 2f
        val cy = MINI_H / 2f + 1f
        val giro = RectF(cx - 22f, cy - 22f, cx + 22f, cy + 22f)
        c.drawArc(giro, 135f, 270f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; color = 0x55FFFFFF
        })
        c.drawArc(giro, 135f, 200f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; color = Marca.ACCENTO
        })
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = grassetto; textSize = 15f; color = BIANCO; textAlign = Paint.Align.CENTER }
        val testo = i?.autonomia?.let { String.format(Locale.ITALY, "%.0f", it) } ?: "km"
        c.drawText(testo, cx, cy - (t.descent() + t.ascent()) / 2f, t)
        return png(b)
    }

    /** miniatura della riga "Porte, finestrini e tetto" (63x54): la foto dell'auto col pallino del riassunto */
    fun miniaturaPorte(foto: Bitmap?, ritaglio: android.graphics.Rect?, parti: Map<DatiAuto.Parte, DatiAuto.Stato>): ByteArray {
        val b = Bitmap.createBitmap(MINI_W, MINI_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (foto != null) {
            val r = ritaglio ?: android.graphics.Rect(0, 0, foto.width, foto.height)
            val s = min(MINI_W / r.width().toFloat(), (MINI_H - 6f) / r.height())
            val w = r.width() * s
            val h = r.height() * s
            c.drawBitmap(foto, r, RectF((MINI_W - w) / 2f, (MINI_H - h) / 2f, (MINI_W + w) / 2f, (MINI_H + h) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawRoundRect(RectF(0f, 0f, MINI_W.toFloat(), MINI_H.toFloat()), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 44, 54) })
        }
        if (parti.isNotEmpty()) {
            val aperte = parti.values.any { it != DatiAuto.Stato.CHIUSO }
            c.drawCircle(MINI_W - 8f, MINI_H - 8f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (aperte) 0xFFFFA726.toInt() else 0xFF66BB6A.toInt() })
            c.drawCircle(MINI_W - 8f, MINI_H - 8f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f })
        }
        return png(b)
    }

    // ------------------------------------------------------------------------------ traffico ----
    fun paginaTraffico(lista: List<EventoTraffico>, luogo: String, raggio: Int): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, "Traffico · $luogo", "Autostrade per l'Italia · entro $raggio km")
        if (lista.isEmpty()) {
            scrivi(c, "Nessun evento segnalato sulle autostrade entro $raggio km.", 0f, 90f, pennello(25f, false, TENUE))
            scrivi(c, "Fonte: rete Autostrade per l'Italia (le tratte di altri gestori non sono comprese).", 0f, 130f, pennello(19f, false, TENUE))
            return png(b)
        }
        val pStrada = pennello(23f, true, BIANCO)
        val pTesto = pennello(19f, false, TENUE)
        val pDx = pennello(19f, false, TENUE, Paint.Align.RIGHT)
        val pDxB = pennello(22f, true, BIANCO, Paint.Align.RIGHT)
        lista.take(6).forEachIndexed { i, e ->
            val y = 70f + i * 61f
            if (i > 0) linea(c, 0f, y - 5f, TELA_W, y - 4f)
            val colore = when (e.gravita) { 2 -> 0xFFE53935.toInt(); 1 -> 0xFFFB8C00.toInt(); else -> 0xFFFDD835.toInt() }
            val pallino = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; setShadowLayer(3f, 0f, 1f, OMBRA) }
            c.drawCircle(14f, y + 24f, 12f, pallino)
            val titolo = e.strada + (if (e.tipo.isNotEmpty()) " · " + e.tipo else "") + (if (e.codaKm >= 1) String.format(Locale.ITALY, " %.0f km", e.codaKm) else "")
            scrivi(c, ellissi(titolo, pStrada, 980f), 40f, y, pStrada)
            scrivi(c, ellissi(e.testo, pTesto, 980f), 40f, y + 29f, pTesto)
            scrivi(c, String.format(Locale.ITALY, "%.0f km", e.km), TELA_W, y, pDxB)
            if (e.ora.isNotEmpty()) scrivi(c, e.ora, TELA_W, y + 29f, pDx)
        }
        return png(b)
    }

    private fun ellissi(s: String, p: Paint, larghezza: Float): String {
        if (p.measureText(s) <= larghezza) return s
        // tagliata a fine parola se si puo', coi puntini (misurata a mano: uguale sul telefono e nelle prove)
        var fine = s.length
        while (fine > 0 && p.measureText(s, 0, fine) + p.measureText("…") > larghezza) fine--
        val spazio = s.lastIndexOf(' ', fine - 1)
        val taglio = if (spazio > fine * 2 / 3) spazio else fine
        return s.substring(0, taglio).trimEnd(' ', ',', '.', ':', ';', '·', '-') + "…"
    }

    /** miniatura di riga per la lista delle notizie (63x54, angoli arrotondati) */
    fun miniatura(n: Notizia): ByteArray {
        val b = Bitmap.createBitmap(MINI_W, MINI_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = RectF(0f, 0f, MINI_W.toFloat(), MINI_H.toFloat())
        val foto = n.foto
        if (foto != null) {
            disegnaCopertina(c, foto, r, 6f)
        } else {
            // senza foto: tassello scuro con l'ora nel colore della marca, cosi' ogni riga ha la sua miniatura
            c.drawRoundRect(r, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 44, 54) })
            c.drawRoundRect(RectF(0f, MINI_H - 5f, MINI_W.toFloat(), MINI_H.toFloat()), 2f, 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Marca.ACCENTO })
            val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = grassetto; textSize = 17f; color = Marca.ACCENTO; textAlign = Paint.Align.CENTER
            }
            val testo = n.ora.ifEmpty { "ANSA" }
            c.drawText(testo, MINI_W / 2f, (MINI_H - 5f) / 2f - (t.descent() + t.ascent()) / 2f, t)
        }
        return png(b)
    }

    private fun blocco(s: String, p: TextPaint, larghezza: Int, altezzaRiga: Float, righe: Int): StaticLayout {
        val fm = p.fontMetrics
        val naturale = fm.descent - fm.ascent
        return StaticLayout.Builder.obtain(s, 0, s.length, p, larghezza)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(altezzaRiga - naturale, 1f)
            .setIncludePad(false)
            .setMaxLines(righe)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    /** foto ritagliata "a copertura" dentro il rettangolo, con angoli arrotondati */
    private fun disegnaCopertina(c: Canvas, foto: Bitmap, r: RectF, raggio: Float) {
        val sc = max(r.width() / foto.width, r.height() / foto.height)
        val m = Matrix()
        m.setScale(sc, sc)
        m.postTranslate(r.left + (r.width() - foto.width * sc) / 2f, r.top + (r.height() - foto.height * sc) / 2f)
        val shader = BitmapShader(foto, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        c.drawRoundRect(r, raggio, raggio, p)
    }
}
