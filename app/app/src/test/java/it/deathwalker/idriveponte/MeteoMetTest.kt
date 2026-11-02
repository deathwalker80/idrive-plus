package it.deathwalker.idriveponte

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeteoMetTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun meteoDiRiserva() {
        // previsione vera di MET Norway per La Spezia (2 novembre 2026, primo passo 16:00 UTC)
        val adesso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse("2026-11-02T16:20:00Z")!!.time
        val m = Dati.meteoMetDaJson(File("src/test/resources/dati/met.json").readText(), "La Spezia", 44.1048, 9.829, adesso)
        assertEquals(14, m.temp)                    // 13,6 gradi
        assertEquals(12, m.percepita)
        assertEquals(2, m.codice)                   // partlycloudy_night
        assertEquals(false, m.giorno)               // alle 17:20 italiane e' gia' buio
        assertEquals(18, m.vento)                   // 5 m/s
        assertEquals(8, m.ore.size)
        assertEquals("17", m.ore[0].ora)
        assertEquals(5, m.giorni.size)
        assertTrue(m.giorni.all { it.max >= it.min })
        assertEquals("MET Norway", m.fonte)
        // alba e tramonto alla Spezia il 2 novembre: circa 06:55 e 17:10
        val (alba, tramonto) = Dati.sole(44.1048, 9.829, Calendar.getInstance(Dati.ROMA).apply { timeInMillis = adesso })
        assertTrue(alba in 410..420 && tramonto in 1025..1035)
        assertEquals(81, Dati.codiceMet("rainshowers_day"))
        assertEquals(95, Dati.codiceMet("heavyrainandthunder"))
        assertEquals(0, Dati.codiceMet("clearsky_night"))
        File("build/pagine").mkdirs()
        File("build/pagine/meteo_met.png").writeBytes(Disegno(ctx).paginaMeteo(m))
    }
}
