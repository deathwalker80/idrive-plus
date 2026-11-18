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

/** Il meteo per il riquadro vivo (plugin PlusTile da plustile11): un'immagine 360x225 a tavolozza, sotto i 64 KB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RiquadroTest {

    private val uscita = File("build/pagine/" + BuildConfig.FLAVOR).apply { mkdirs() }

    @Test
    fun immagineDelRiquadro() {
        val d = Disegno(RuntimeEnvironment.getApplication())
        val m = Dati.meteoDaJson(File("src/test/resources/dati/meteo.json").readText(), "La Spezia")
        val casi = listOf(
            "riquadro_giorno" to (Dati.iconaMeteo(m.codice, true) to cieloRiquadro(Dati.cielo(m.codice))),
            "riquadro_temporale" to ("temporale_intenso" to "Thunderstorm"),
            "riquadro_testo_lungo" to ("pioggia_neve" to "Freezing drizzle with a very long description"))
        for ((nome, caso) in casi) {
            val png = d.riquadroMeteo(m, caso.first, caso.second)
            File(uscita, "$nome.png").writeBytes(png)
            println("RIQUADRO $nome: ${png.size} byte")
            // firma PNG, 360x225, 8 bit, tipo 3 (tavolozza)
            assertEquals(0x89, png[0].toInt() and 0xFF)
            assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
            assertEquals(3, png[25].toInt())
            assertEquals(8, png[24].toInt())
            val b = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(360, b.width)
            assertEquals(225, b.height)
            assertTrue("troppo grande per il plugin: ${png.size}", png.size in 1024 until 64 * 1024)
        }
    }
}
