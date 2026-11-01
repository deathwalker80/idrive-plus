package it.deathwalker.idriveponte

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViaggioTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun valori(km: Double, litri: Double, finestrino: Int = 0) = mapOf(
        "driving.odometer" to """{"odometer":$km}""",
        "sensors.fuel" to """{"fuel":{"tanklevel":$litri,"range":${litri * 14},"reserve":0}}""",
        "driving.averageConsumption" to """{"averageConsumption":{"averageConsumption1":6.3,"unit":1}}""",
        "sensors.temperatureExterior" to """{"temperatureExterior":14.5}""",
        "sensors.temperatureInterior" to """{"temperatureInterior":21}""",
        "sensors.doors" to """{"doors":{"driverFront":0,"passengerFront":0,"driverRear":0,"passengerRear":0}}""",
        "sensors.trunk" to """{"trunk":0}""",
        "controls.windowDriverFront" to """{"windowDriverFront":{"status":$finestrino,"position":${finestrino * 10},"tiltPosition":0}}""",
        "controls.sunroof" to """{"sunroof":{"status":0,"position":0,"tiltPosition":3}}""",
    )

    @Test
    fun letturaDeiValori() {
        val i = DatiAuto.istantanea(valori(12345.0, 30.0, finestrino = 1))
        assertEquals(12345.0, i.km!!, 0.01)
        assertEquals(30.0, i.litri!!, 0.01)
        assertEquals(420.0, i.autonomia!!, 0.01)
        assertEquals(6.3, i.consumo!!, 0.01)
        assertEquals(14.5, i.esterna!!, 0.01)
        assertEquals(listOf("finestrino ant. sx", "tetto (sollevato)"), i.aperti)
        // senza nessuno stato delle aperture: null, non "tutto chiuso"
        assertNull(DatiAuto.istantanea(mapOf("driving.odometer" to """{"odometer":1}""")).aperti)
        assertEquals(emptyList<String>(), DatiAuto.istantanea(mapOf("sensors.doors" to """{"doors":{"driverFront":0}}""")).aperti)
    }

    @Test
    fun viaggioConRifornimento() {
        Viaggio.inizia(ctx, "MINI28359")
        Viaggio.impostaLuogo(ctx, "MINI28359", "Via Roma 1, La Spezia")
        Viaggio.aggiorna(ctx, "MINI28359", valori(12345.0, 30.0))
        Viaggio.aggiorna(ctx, "MINI28359", valori(12360.0, 29.0))
        Viaggio.aggiorna(ctx, "MINI28359", valori(12361.0, 45.0))      // rifornimento di 16 litri
        Viaggio.aggiorna(ctx, "MINI28359", valori(12400.0, 42.0))
        val v = Viaggio.di(ctx, "MINI28359")!!
        assertTrue(v.inCorso)
        assertEquals("Via Roma 1, La Spezia", v.luogo)
        assertEquals(55.0, v.km()!!, 0.01)
        assertEquals(4.0, v.litri()!!, 0.01)                          // 30 + 16 - 42

        Viaggio.chiudi(ctx, "MINI28359")
        assertFalse(Viaggio.di(ctx, "MINI28359")!!.inCorso)
        // ricollegata subito dopo: lo stesso viaggio continua
        Viaggio.inizia(ctx, "MINI28359")
        assertEquals(55.0, Viaggio.di(ctx, "MINI28359")!!.km()!!, 0.01)
        // l'auto tolta dal parco porta via anche il viaggio
        Parco.ricorda(ctx, "MINI28359")
        Parco.togli(ctx, "MINI28359")
        assertNull(Viaggio.di(ctx, "MINI28359"))
    }
}
