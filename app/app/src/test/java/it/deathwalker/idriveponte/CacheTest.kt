package it.deathwalker.idriveponte

import android.app.job.JobScheduler
import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** pagine salvate e ritrovate come le aveva lasciate un processo chiuso da Android */
    @Test
    fun paginePronteDopoUnRiavvio() {
        val meteo = byteArrayOf(1, 2, 3, 4)
        Stato.meteo.metti(meteo); Stato.meteo.ultimo = 1_000L; Stato.meteo.esito = "OK alle 10:28 - prova"
        Stato.notizie.metti(listOf(
            NotiziaPronta("Titolo uno", "10:00", byteArrayOf(5), byteArrayOf(6), byteArrayOf(7)),
            NotiziaPronta("Titolo due", "11:00", byteArrayOf(8, 9), ByteArray(0), byteArrayOf(10), 42L)
        ))
        Stato.notizie.ultimo = 2_000L; Stato.notizie.esito = "OK alle 10:30 - 2 notizie"
        Stato.meteoBreve = Stato.MeteoBreve("Città", 11, "Nuvoloso", "nuvoloso")
        Stato.carburantiBreve = "Gasolio 2,187 €" to "PompeBianche · 1,0 km"
        Stato.trafficoBreve = null
        Cache.salva(ctx)

        // processo nuovo: niente in memoria
        Stato.meteo.png = null; Stato.meteo.ultimo = 0L; Stato.meteo.esito = ""
        Stato.notizie.notizie = emptyList(); Stato.notizie.ultimo = 0L
        Stato.meteoBreve = null; Stato.carburantiBreve = null
        Cache.dimentica()
        Cache.carica(ctx)

        assertArrayEquals(meteo, Stato.meteo.png)
        assertEquals(1_000L, Stato.meteo.ultimo)
        assertEquals("OK alle 10:28 - prova", Stato.meteo.esito)
        val n = Stato.notizie.notizie
        assertEquals(2, n.size)
        assertEquals("Titolo due", n[1].titolo)
        assertEquals(42L, n[1].versione)
        assertArrayEquals(byteArrayOf(8, 9), n[1].pagina)
        assertEquals(0, n[1].mini.size)
        assertEquals(2_000L, Stato.notizie.ultimo)
        assertEquals("Nuvoloso", Stato.meteoBreve?.cielo)
        assertEquals("PompeBianche · 1,0 km", Stato.carburantiBreve?.second)
        assertNull(Stato.trafficoBreve)
    }

    /** il lavoro di ogni ora: c'e' con "Sì" e "Solo Wi-Fi", sparisce con "No" */
    @Test
    fun lavoroProgrammatoSecondoLeImpostazioni() {
        val js = ctx.getSystemService(JobScheduler::class.java)
        PreparaJob.imposta(ctx, "si")
        assertNotNull(js.allPendingJobs.firstOrNull { it.service.className == PreparaJob::class.java.name })
        PreparaJob.imposta(ctx, "wifi")
        assertEquals(1, js.allPendingJobs.count { it.service.className == PreparaJob::class.java.name })
        PreparaJob.imposta(ctx, "no")
        assertNull(js.allPendingJobs.firstOrNull { it.service.className == PreparaJob::class.java.name })
    }
}
