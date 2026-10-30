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
        ctx.assets.open("icone/$nome.png").use { BitmapFactory.decodeStream(it) } ?: return null
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
    fun paginaMeteo(m: Meteo, allerta: Allerta? = null): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, m.citta, "Alba ${m.alba} · Tramonto ${m.tramonto}")
        // allerta della Protezione Civile: pastiglia colorata accanto al nome della citta'
        val avviso = allerta?.testoBreve().orEmpty()
        if (avviso.isNotEmpty()) {
            val pc = pennello(30f, true, BIANCO)
            val x = pc.measureText(m.citta) + 22f
            val colore = when (allerta!!.massima()) { 3 -> 0xFFE53935.toInt(); 2 -> 0xFFFB8C00.toInt(); else -> 0xFFFDD835.toInt() }
            val testo = if (allerta.massima() >= 2) BIANCO else 0xFF1A1A1A.toInt()
            val pa = pennello(17f, true, testo).apply { clearShadowLayer() }
            val larg = minOf(pa.measureText("⚠ $avviso") + 28f, 700f - x)
            val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore; setShadowLayer(3f, 0f, 1f, OMBRA) }
            c.drawRoundRect(RectF(x, 9f, x + larg, 45f), 18f, 18f, fondo)
            c.save(); c.clipRect(x, 9f, x + larg - 12f, 45f)
            scrivi(c, "⚠ $avviso", x + 14f, 15f, pa)
            c.restore()
        }

        // adesso (colonna sinistra, top 74)
        val wi = Dati.wmo(m.codice)
        disegnaIcona(c, Dati.iconaOra(wi.second, m.giorno), 0f, 74f, 108f)
        scrivi(c, "${m.temp}°", 122f, 70f, pennello(100f, true, BIANCO), 100f)
        scrivi(c, wi.first, 0f, 196f, pennello(26f, false, BIANCO))
        scrivi(c, "Max ${m.max}° · Min ${m.min}°", 0f, 232f, pennello(18f, false, TENUE))
        val celle = listOf(
            "Percepita" to "${m.percepita}°", "Umidità" to "${m.umidita}%",
            "Vento" to "${m.vento} km/h ${m.direzione}".trim(), "Raffiche" to "${m.raffiche} km/h",
            "Pioggia oggi" to "${m.pioggiaOggi}%", "Pressione" to "${m.pressione} hPa"
        )
        val pl = pennello(15f, false, TENUE)
        val pv = pennello(22f, true, BIANCO)
        celle.forEachIndexed { i, (l, v) ->
            val x = (i % 2) * 190f
            val y = 276f + (i / 2) * 52f
            scrivi(c, l, x, y, pl)
            scrivi(c, v, x, y + 15f * 1.15f + 2f, pv)
        }
        linea(c, 398f, 74f, 400f, TELA_H)

        // colonna destra (left 430, top 74)
        val x0 = 430f
        val ph2 = pennello(17f, true, BIANCO).apply { letterSpacing = 1f / 17f }
        scrivi(c, "PROSSIME ORE", x0, 74f, ph2)
        val yOre = 74f + 28f
        val pOra = pennello(17f, false, TENUE, Paint.Align.CENTER)
        val pT = pennello(21f, true, BIANCO, Paint.Align.CENTER)
        val pP = pennello(15f, false, AZZURRO, Paint.Align.CENTER)
        m.ore.forEachIndexed { i, o ->
            val cx = x0 + i * 96f + 48f
            scrivi(c, "${o.ora}:00", cx, yOre, pOra)
            disegnaIcona(c, Dati.iconaOra(Dati.wmo(o.codice).second, o.giorno), cx - 21f, yOre + 23.5f, 42f)
            scrivi(c, "${o.temp}°", cx, yOre + 72f, pT)
            scrivi(c, "${o.pioggia}%", cx, yOre + 98f, pP)
        }
        scrivi(c, "PROSSIMI GIORNI", x0, 74f + 170f, ph2)
        val yG = 74f + 198f
        val pN = pennello(18f, false, TENUE, Paint.Align.CENTER)
        val pMax = pennello(21f, true, BIANCO, Paint.Align.LEFT)
        val pMin = pennello(21f, false, TENUE, Paint.Align.LEFT)
        m.giorni.forEachIndexed { i, g ->
            val cx = x0 + i * 154f + 77f
            scrivi(c, g.nome, cx, yG, pN)
            disegnaIcona(c, Dati.wmo(g.codice).second, cx - 23f, yG + 24.7f, 46f)
            val sMax = "${g.max}° "
            val sMin = "${g.min}°"
            val larg = pMax.measureText(sMax) + pMin.measureText(sMin)
            val xs = cx - larg / 2f
            scrivi(c, sMax, xs, yG + 78f, pMax)
            scrivi(c, sMin, xs + pMax.measureText(sMax), yG + 78f, pMin)
            scrivi(c, "${g.pioggia}%", cx, yG + 104f, pP)
        }
        return png(b)
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

    // ------------------------------------------------------------------------------ carburanti ----
    fun paginaCarburanti(lista: List<Distributore>, carburante: String, luogo: String, raggio: Int): ByteArray {
        val (b, c) = nuovaTela()
        intestazione(c, "$carburante · $luogo", "MIMIT Osservaprezzi · entro $raggio km")
        if (lista.isEmpty()) {
            scrivi(c, "Nessun distributore con prezzo di $carburante entro $raggio km.", 0f, 90f, pennello(25f, false, TENUE))
            return png(b)
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

    private fun ellissi(s: String, p: Paint, larghezza: Float): String =
        TextUtils.ellipsize(s, TextPaint(p), larghezza, TextUtils.TruncateAt.END).toString()

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
