package it.deathwalker.idriveponte

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** sagome generiche: composizione uguale a quella di tools/sagome/sagome.py, punti, priorita' foto/sagoma/serie, pagine */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SagomeTest {

    private val uscita = File("build/pagine/" + BuildConfig.FLAVOR + "/sagome").apply { mkdirs() }

    @Test
    fun composizioneUgualeAllAnteprima() {
        val ctx = RuntimeEnvironment.getApplication()
        val el = Sagome.elenco(ctx)
        val rosso = el.colori.first { it.nome == "Rosso" }.rgb
        val bianco = el.tetti.first { it.first == "Bianco" }.second
        val k = Sagome.componi(ctx, Sagome.Scelta("utilitaria3", rosso, bianco))!!
        val opz = BitmapFactory.Options().apply { inPremultiplied = false }
        val p = BitmapFactory.decodeFile("../../tools/sagome/anteprima/verifica_utilitaria3.png", opz)
        assertEquals(p.width, k.width)
        assertEquals(p.height, k.height)
        val a = IntArray(k.width * k.height).also { k.getPixels(it, 0, k.width, 0, 0, k.width, k.height) }
        val b = IntArray(p.width * p.height).also { p.getPixels(it, 0, p.width, 0, 0, p.width, p.height) }
        var peggiore = 0
        var dove = ""
        val conta = IntArray(256)
        for (i in a.indices) {
            val alfa = minOf(a[i] ushr 24, b[i] ushr 24)
            for (sh in intArrayOf(24, 16, 8, 0)) {
                if (sh != 24 && alfa < 64) continue          // colore dei bordi quasi trasparenti: il Bitmap lo tiene premoltiplicato
                val dd = Math.abs((a[i] shr sh and 255) - (b[i] shr sh and 255))
                conta[dd]++
                if (dd > peggiore) { peggiore = dd; dove = "x=${i % k.width} y=${i / k.width} alfa=$alfa canale=$sh k=${Integer.toHexString(a[i])} py=${Integer.toHexString(b[i])}" }
            }
        }
        println("differenza massima Kotlin/Python: $peggiore ($dove); per differenza: ${(0..peggiore).map { conta[it] }}")
        assertTrue("differenza $peggiore", peggiore <= 3)
    }

    @Test
    fun tutteLeCarrozzerieHannoLivelliEPunti() {
        val ctx = RuntimeEnvironment.getApplication()
        for (c in Sagome.elenco(ctx).carrozzerie) {
            assertTrue(c.id, Sagome.componi(ctx, Sagome.Scelta(c.id, 0xFF808080.toInt(), null)) != null)
            assertEquals(c.id, DatiAuto.Parte.values().size, Sagome.punti(ctx, c.id).size)
        }
    }

    @Test
    fun ordineFotoSagomaSerie() {
        val ctx = RuntimeEnvironment.getApplication()
        val bt = "PROVA SAGOMA"
        val serie = Foto.firma(ctx, bt)
        val s = Sagome.Scelta("suv", 0xFF1C3A80.toInt(), null)
        Foto.impostaSagoma(ctx, bt, s)
        assertTrue(Foto.haSagoma(ctx, bt))
        assertTrue(Foto.firma(ctx, bt).startsWith("s" + s.codice()))
        assertEquals(Sagome.punti(ctx, "suv")[DatiAuto.Parte.TETTO]!!.x, Foto.punti(ctx, bt)[DatiAuto.Parte.TETTO]!!.x)
        Foto.togliSagoma(ctx, bt)
        assertEquals(serie, Foto.firma(ctx, bt))                     // la foto di serie torna com'era
    }

    @Test
    fun paginePorteConLeSagome() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = Disegno(ctx)
        val el = Sagome.elenco(ctx)
        val parti = DatiAuto.Parte.values().associateWith { DatiAuto.Stato.CHIUSO }.toMutableMap()
        parti[DatiAuto.Parte.PORTA_ANT_DX] = DatiAuto.Stato.APERTO
        parti[DatiAuto.Parte.FINESTRINO_POST_DX] = DatiAuto.Stato.APERTO
        Sagome.ordinate(ctx).forEachIndexed { k, c ->
            val bt = "PROVA ${c.id}"
            val colore = el.colori[k % el.colori.size].rgb
            Foto.impostaSagoma(ctx, bt, Sagome.Scelta(c.id, colore, if (k == 0) el.tetti[1].second else null))
            val foto = Foto.immagine(ctx, bt)
            val pagina = d.paginaPorte(foto, Foto.contornoAuto(ctx, bt), Foto.punti(ctx, bt), parti, System.currentTimeMillis())
            File(uscita, "porte_${c.id}.png").writeBytes(pagina)
            File(uscita, "mini_${c.id}.png").writeBytes(d.miniaturaPorte(foto, Foto.contornoAuto(ctx, bt), parti))
            Foto.togliSagoma(ctx, bt)
        }
    }
}
