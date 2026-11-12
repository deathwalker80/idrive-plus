package it.deathwalker.idriveponte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Allerta della Protezione Civile e giorno del bollettino. Zone vere (solo "Bacini Liguri Marittimi di Levante",
 * quella della Spezia) dei bollettini dell'11 novembre 2026 ore 15:40 e del 12 novembre 2026 ore 15:00:
 *   11/11: oggi (11) gialla, domani (12) arancione
 *   12/11: oggi (12) arancione, domani (13) arancione
 * Il difetto: il 12 mattina, prima del bollettino nuovo, l'app diceva "gialla oggi, arancione domani".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllertaGiornoTest {

    private fun zone(nome: String) = Fonti.Zone(File("src/test/resources/dati/allerta_$nome.json").readText())

    private fun ora(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).apply { timeZone = TimeZone.getTimeZone("Europe/Rome") }.parse(s)!!.time

    private val b11 = "20261111_1540"
    private val b12 = "20261112_1500"

    private fun allerta(boll: String, adesso: Long) =
        Fonti.allertaDelGiorno(zone("${boll}_today"), zone("${boll}_tomorrow"), boll, adesso, "La Spezia", 44.1025, 9.8241)!!

    @Test
    fun bollettinoDiOggi() {
        val a = allerta(b11, ora("2026-11-11 18:00"))
        assertEquals(1, a.oggi)
        assertEquals(2, a.domani)
        assertTrue(a.domaniNoto)
        assertEquals("Allerta gialla oggi, arancione domani", a.testoBreve())
    }

    @Test
    fun bollettinoDiIeriPrimaDelNuovo() {
        // 12 novembre mattina: l'ultimo bollettino e' quello dell'11, il suo "domani" e' oggi
        val a = allerta(b11, ora("2026-11-12 10:00"))
        assertEquals(2, a.oggi)
        assertEquals(0, a.domani)
        assertFalse(a.domaniNoto)
        assertEquals("Allerta arancione oggi · idrogeologico, temporali", a.testoBreve())
    }

    @Test
    fun bollettinoNuovo() {
        val a = allerta(b12, ora("2026-11-12 18:00"))
        assertEquals(2, a.oggi)
        assertEquals(2, a.domani)
        assertTrue(a.domaniNoto)
    }

    @Test
    fun bollettinoVecchio() {
        try {
            allerta(b11, ora("2026-11-13 10:00"))
            fail("un bollettino di due giorni fa non vale")
        } catch (e: BollettinoNonValido) {
            assertTrue(e.message!!.contains(b11))
        }
    }

    @Test
    fun giornoDelBollettino() {
        assertEquals(0, Fonti.giornoBollettino(b12, ora("2026-11-12 00:05")))
        assertEquals(1, Fonti.giornoBollettino(b11, ora("2026-11-12 00:05")))
        assertEquals(1, Fonti.giornoBollettino(b11, ora("2026-11-12 23:55")))
        assertEquals(-1, Fonti.giornoBollettino(b11, ora("2026-11-13 00:05")))
        assertEquals(-1, Fonti.giornoBollettino("", ora("2026-11-12 10:00")))
        // cambio dell'ora legale (29 marzo 2026)
        assertEquals(1, Fonti.giornoBollettino("20260328_1500", ora("2026-03-29 10:00")))
    }

    @Test
    fun ogniQuantoRileggere() {
        val tre = 3 * 60 * 60 * 1000L
        val venti = 20 * 60 * 1000L
        assertEquals(tre, Fonti.intervalloBollettino(b11, ora("2026-11-12 14:00")))
        assertEquals(venti, Fonti.intervalloBollettino(b11, ora("2026-11-12 15:30")))
        assertEquals(tre, Fonti.intervalloBollettino(b12, ora("2026-11-12 15:30")))
        assertEquals(venti, Fonti.intervalloBollettino("", ora("2026-11-12 18:00")))
    }
}
