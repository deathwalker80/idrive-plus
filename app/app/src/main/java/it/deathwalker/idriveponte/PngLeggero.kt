package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pagine per l'iDrive in PNG a tavolozza (256 colori con trasparenza): circa 5 volte piu' piccole del PNG a colori
 * pieni, quindi meno da mandare sul Wi-Fi e meno da decomprimere per la testata.
 * Tavolozza: se la pagina ha al massimo 256 colori si usano quelli esatti; altrimenti taglio mediano sui colori
 * ridotti (5 bit per R, G, B e 4 per la trasparenza) pesati per quanti pixel li usano. Le foto (pixel opachi)
 * hanno la diffusione dell'errore (Floyd-Steinberg) per non fare le strisce sulle sfumature; i bordi del testo e le
 * ombre (semitrasparenti) prendono il colore piu' vicino.
 * Impostazione "Immagini per l'iDrive": leggere (questo) o piene (Bitmap.compress come prima).
 */
object PngLeggero {

    private const val CHIAVE = "png_leggero"

    fun attivo(ctx: Context): Boolean = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).getBoolean(CHIAVE, true)

    fun imposta(ctx: Context, si: Boolean) {
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putBoolean(CHIAVE, si).apply()
    }

    fun codifica(b: Bitmap): ByteArray {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return codifica(px, w, h)
    }

    /** px: ARGB non premoltiplicati, come Bitmap.getPixels */
    fun codifica(px: IntArray, w: Int, h: Int): ByteArray {
        val t = tavolozza(px, w, h)
        return scrivi(t.colori, t.indici, w, h)
    }

    private class Risultato(val colori: IntArray, val indici: ByteArray)

    private fun trasparente(c: Int) = (c ushr 24) < 8

    private fun tavolozza(px: IntArray, w: Int, h: Int): Risultato {
        // 1) al massimo 256 colori: tavolozza esatta
        val esatti = HashMap<Int, Int>()
        var tanti = false
        for (c in px) {
            val k = if (trasparente(c)) 0 else c
            if (!esatti.containsKey(k)) {
                if (esatti.size == 256) { tanti = true; break }
                esatti[k] = esatti.size
            }
        }
        if (!tanti) {
            val colori = IntArray(esatti.size)
            esatti.forEach { (c, i) -> colori[i] = c }
            val ind = ByteArray(px.size)
            for (i in px.indices) ind[i] = esatti[if (trasparente(px[i])) 0 else px[i]]!!.toByte()
            return Risultato(colori, ind)
        }
        // 2) istogramma dei colori ridotti (pixel del tutto trasparenti a parte)
        val conti = IntArray(1 shl 19)
        var trasparenti = false
        for (c in px) if (trasparente(c)) trasparenti = true else conti[chiave(c)]++
        val posto = IntArray(1 shl 19)
        var n = 0
        for (k in conti.indices) if (conti[k] > 0) posto[k] = n++
        val peso = LongArray(n)
        val somme = LongArray(n * 4)
        for (c in px) {
            if (trasparente(c)) continue
            val s = posto[chiave(c)]
            peso[s]++
            somme[s * 4] += (c ushr 16 and 255).toLong()
            somme[s * 4 + 1] += (c ushr 8 and 255).toLong()
            somme[s * 4 + 2] += (c and 255).toLong()
            somme[s * 4 + 3] += (c ushr 24).toLong()
        }
        val v = Array(4) { IntArray(n) }                     // colore medio di ogni casella dell'istogramma
        for (s in 0 until n) for (ch in 0 until 4) v[ch][s] = (somme[s * 4 + ch] / peso[s]).toInt()
        // 3) taglio mediano: si divide sempre la scatola con l'errore (pesato) piu' grande
        val posti = if (trasparenti) 255 else 256
        val ord = IntArray(n) { it }
        val inizio = IntArray(posti)
        val fine = IntArray(posti)
        val errore = DoubleArray(posti)
        val canale = IntArray(posti)
        var scatole = 1
        fine[0] = n
        fun misura(b: Int) {
            var tot = 0.0
            var meglio = -1.0
            for (ch in 0 until 4) {
                var p = 0.0; var s = 0.0; var q = 0.0
                for (i in inizio[b] until fine[b]) {
                    val x = v[ch][ord[i]].toDouble(); val wgt = peso[ord[i]].toDouble()
                    p += wgt; s += wgt * x; q += wgt * x * x
                }
                val sse = (q - s * s / p) * (if (ch == 3) 2.0 else 1.0)     // la trasparenza conta di piu' (bordi del testo)
                tot += sse
                if (sse > meglio) { meglio = sse; canale[b] = ch }
            }
            errore[b] = if (fine[b] - inizio[b] < 2) -1.0 else tot
        }
        misura(0)
        val ordina = LongArray(n)
        while (scatole < posti) {
            var b = -1
            for (i in 0 until scatole) if (errore[i] > 0 && (b < 0 || errore[i] > errore[b])) b = i
            if (b < 0) break
            val ch = canale[b]
            val da = inizio[b]
            val a = fine[b]
            for (i in da until a) ordina[i] = (v[ch][ord[i]].toLong() shl 20) or ord[i].toLong()
            java.util.Arrays.sort(ordina, da, a)
            var totale = 0L
            for (i in da until a) { ord[i] = (ordina[i] and 0xFFFFF).toInt(); totale += peso[ord[i]] }
            // a meta' del peso; se la meta' arriva solo con l'ultimo colore (un colore che pesa piu' di tutti gli altri
            // insieme) quell'ultimo resta da solo
            var acc = 0L
            var taglio = a - 1
            for (i in da until a - 1) { acc += peso[ord[i]]; if (acc * 2 >= totale) { taglio = i + 1; break } }
            inizio[scatole] = taglio; fine[scatole] = a
            fine[b] = taglio
            misura(b); misura(scatole)
            scatole++
        }
        val base = if (trasparenti) 1 else 0
        val colori = IntArray(scatole + base)
        for (b in 0 until scatole) {
            var p = 0.0
            val m = DoubleArray(4)
            for (i in inizio[b] until fine[b]) {
                val s = ord[i]; val wgt = peso[s].toDouble()
                p += wgt
                for (ch in 0 until 4) m[ch] += wgt * v[ch][s]
            }
            val r = (m[0] / p + 0.5).toInt(); val g = (m[1] / p + 0.5).toInt(); val bl = (m[2] / p + 0.5).toInt()
            var al = (m[3] / p + 0.5).toInt()
            if (al >= 250) al = 255
            colori[base + b] = (al shl 24) or (r shl 16) or (g shl 8) or bl
        }
        // 4) ogni pixel al colore piu' vicino; i pixel opachi con la diffusione dell'errore
        val vicino = IntArray(1 shl 19) { -1 }
        fun indice(r: Int, g: Int, b: Int, a: Int): Int {
            val k = ((a ushr 4) shl 15) or ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
            val gia = vicino[k]
            if (gia >= 0) return gia
            var meglio = base; var d = Long.MAX_VALUE
            for (i in base until colori.size) {
                val c = colori[i]
                val dr = (c ushr 16 and 255) - r; val dg = (c ushr 8 and 255) - g
                val db = (c and 255) - b; val da = (c ushr 24) - a
                val e = 2L * dr * dr + 4L * dg * dg + 3L * db * db + 6L * da * da
                if (e < d) { d = e; meglio = i }
            }
            vicino[k] = meglio
            return meglio
        }
        val ind = ByteArray(px.size)
        var qui = IntArray((w + 2) * 3)
        var sotto = IntArray((w + 2) * 3)
        for (y in 0 until h) {
            java.util.Arrays.fill(sotto, 0)
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                if (trasparente(c)) { ind[i] = 0; continue }
                val a = c ushr 24
                if (a < 255) { ind[i] = indice(c ushr 16 and 255, c ushr 8 and 255, c and 255, a).toByte(); continue }
                val e = (x + 1) * 3
                val r = (c ushr 16 and 255) + qui[e] / 16
                val g = (c ushr 8 and 255) + qui[e + 1] / 16
                val b = (c and 255) + qui[e + 2] / 16
                val rr = if (r < 0) 0 else if (r > 255) 255 else r
                val gg = if (g < 0) 0 else if (g > 255) 255 else g
                val bb = if (b < 0) 0 else if (b > 255) 255 else b
                val k = indice(rr, gg, bb, 255)
                ind[i] = k.toByte()
                val p = colori[k]
                val er = rr - (p ushr 16 and 255); val eg = gg - (p ushr 8 and 255); val eb = bb - (p and 255)
                // solo verso i pixel opachi: lo sfondo trasparente non si sporca
                fun spargi(dest: IntArray, xx: Int, yy: Int, f: Int) {
                    if (xx < 0 || xx >= w || yy >= h || (px[yy * w + xx] ushr 24) < 255) return
                    val j = (xx + 1) * 3
                    dest[j] += er * f; dest[j + 1] += eg * f; dest[j + 2] += eb * f
                }
                spargi(qui, x + 1, y, 7)
                spargi(sotto, x - 1, y + 1, 3)
                spargi(sotto, x, y + 1, 5)
                spargi(sotto, x + 1, y + 1, 1)
            }
            val t = qui; qui = sotto; sotto = t
        }
        return Risultato(colori, ind)
    }

    private fun chiave(c: Int): Int =
        ((c ushr 28) shl 15) or ((c ushr 19 and 31) shl 10) or ((c ushr 11 and 31) shl 5) or (c ushr 3 and 31)

    private fun scrivi(colori: IntArray, ind: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream()
        int32(ihdr, w); int32(ihdr, h)
        ihdr.write(byteArrayOf(8, 3, 0, 0, 0))                // 8 bit, tavolozza, deflate, filtro 0, non interlacciato
        pezzo(out, "IHDR", ihdr.toByteArray())
        val plte = ByteArray(colori.size * 3)
        for (i in colori.indices) {
            plte[i * 3] = (colori[i] ushr 16).toByte(); plte[i * 3 + 1] = (colori[i] ushr 8).toByte(); plte[i * 3 + 2] = colori[i].toByte()
        }
        pezzo(out, "PLTE", plte)
        var ultimo = colori.size
        while (ultimo > 0 && (colori[ultimo - 1] ushr 24) == 255) ultimo--
        if (ultimo > 0) pezzo(out, "tRNS", ByteArray(ultimo) { (colori[it] ushr 24).toByte() })
        val righe = ByteArray((w + 1) * h)
        for (y in 0 until h) System.arraycopy(ind, y * w, righe, y * (w + 1) + 1, w)   // filtro 0 su ogni riga
        val def = Deflater(Deflater.BEST_COMPRESSION)
        def.setInput(righe)
        def.finish()
        val z = ByteArrayOutputStream(righe.size / 4)
        val buf = ByteArray(64 * 1024)
        while (!def.finished()) { val k = def.deflate(buf); z.write(buf, 0, k) }
        def.end()
        pezzo(out, "IDAT", z.toByteArray())
        pezzo(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun int32(o: ByteArrayOutputStream, v: Int) {
        o.write(v ushr 24); o.write(v ushr 16 and 255); o.write(v ushr 8 and 255); o.write(v and 255)
    }

    private fun pezzo(o: ByteArrayOutputStream, tipo: String, dati: ByteArray) {
        int32(o, dati.size)
        val t = tipo.toByteArray(Charsets.US_ASCII)
        o.write(t)
        o.write(dati)
        val crc = CRC32()
        crc.update(t); crc.update(dati)
        int32(o, crc.value.toInt())
    }
}
