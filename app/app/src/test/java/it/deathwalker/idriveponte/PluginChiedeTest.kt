package it.deathwalker.idriveponte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Compatibilita' con i plugin gia' installati: chi interroga /ponte si presenta come "iDrive-<plugin>/1" e la sua
 * sezione resta tra quelle da aggiornare anche se non richiede la pagina (ha gia' quell'epoch).
 */
class PluginChiedeTest {

    @Test
    fun ogniPluginHaLaSuaSezione() {
        assertEquals("Meteo", sezioneDelPlugin("iDrive-Meteo/1"))
        assertEquals("Notizie", sezioneDelPlugin("iDrive-Notizie/1"))
        assertEquals("Notizie locali", sezioneDelPlugin("iDrive-Locali/1"))
        assertEquals("Carburanti", sezioneDelPlugin("iDrive-Carburanti/1"))
        assertEquals("Traffico", sezioneDelPlugin("iDrive-Traffico/1"))
        assertEquals("Auto", sezioneDelPlugin("iDrive-Auto/1"))
    }

    @Test
    fun chiNonSiPresentaNonAggiungeNiente() {
        assertNull(sezioneDelPlugin(""))
        assertNull(sezioneDelPlugin("Mozilla/5.0"))
        assertNull(sezioneDelPlugin("iDrive-PlusTile/1"))
    }
}
