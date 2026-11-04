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
import java.net.Socket
import java.net.URLEncoder

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
        assertEquals(100 / 6.3, i.kmLitro!!, 0.01)
        assertEquals(14.5, i.esterna!!, 0.01)
        assertEquals(listOf("tetto (sollevato)", "finestrino ant. sx"), i.aperti)
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

    private fun chiedi(percorso: String): String {
        Socket("127.0.0.1", Stato.PORTA).use { s ->
            s.getOutputStream().write("GET $percorso HTTP/1.0\r\nHost: telefono\r\n\r\n".toByteArray())
            return s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    @Test
    fun datiDalPlugin() {
        // il plugin Meteo manda un valore per richiesta, come fa ponte_plugin.cpp (mandaDatiAuto)
        DatiAuto.azzera()
        var ricevuti: Map<String, String> = emptyMap()
        val server = Server {}
        server.datiAuto = { ricevuti = it }
        server.avvia()
        try {
            var risposta = ""
            for (i in 0 until 50) {
                risposta = try { chiedi("/auto?n=sensors.fuel&v=" + URLEncoder.encode("""{"fuel":{"tanklevel":30,"range":420}}""", "UTF-8")) } catch (e: Exception) { "" }
                if (risposta.isNotEmpty()) break
                Thread.sleep(100)
            }
            assertTrue(risposta, risposta.startsWith("HTTP/1.0 200"))
            assertEquals("""{"fuel":{"tanklevel":30,"range":420}}""", ricevuti["sensors.fuel"])
            assertEquals(30.0, DatiAuto.istantanea(DatiAuto.grezzi).litri!!, 0.01)
            // proprieta' sconosciuta o valore che non e' JSON: rifiutati
            assertTrue(chiedi("/auto?n=vehicle.VIN&v=%7B%7D").startsWith("HTTP/1.0 400"))
            assertTrue(chiedi("/auto?n=sensors.trunk&v=ciao").startsWith("HTTP/1.0 400"))
            assertEquals(1, DatiAuto.grezzi.size)
            // Stato vettura (plugin Auto v9): due righe, la versione dopo il TAB; /ponte con vettura= e avvio=
            val png = byteArrayOf(1, 2, 3)
            Stato.vettura.metti(listOf(
                NotiziaPronta("Riepilogo", "", png, png, ByteArray(0), 1001L),
                NotiziaPronta("Porte, finestrini e tetto", "", png, png, ByteArray(0), 1002L),
            ))
            val lista = chiedi("/vettura/notizie.txt").substringAfter("\r\n\r\n")
            assertEquals("NOTIZIE ${Stato.vettura.epoch} 2\nRiepilogo\t1001\nPorte, finestrini e tetto\t1002\n", lista)
            assertTrue(chiedi("/vettura/notizia/1.png").startsWith("HTTP/1.0 200"))
            assertTrue(chiedi("/vettura/notizia/2.png").startsWith("HTTP/1.0 404"))
            val ponte = chiedi("/ponte")
            assertTrue(ponte, ponte.contains("vettura=${Stato.vettura.epoch} avvio=${Stato.avvio}"))
        } finally {
            server.ferma()
        }
    }

    /** formati veri della MINI F60 (Meteo.log 4/11): porte driver/passenger, finestrini status 3 = non valido, km/l */
    @Test
    fun formatiVeriDellaMini() {
        val g = mapOf(
            "sensors.doors" to """{"doors":{"driver":0,"passenger":1,"driverRear":0,"passengerRear":0}}""",
            "sensors.trunk" to """{"trunk":0}""",
            "sensors.lid" to """{"lid":0}""",
            "controls.sunroof" to """{"sunroof":{"openPosition":255,"tiltPosition":255,"status":3}}""",
            "controls.windowDriverFront" to """{"windowDriverFront":{"position":19,"status":1,"drive":1}}""",
            "controls.windowPassengerFront" to """{"windowPassengerFront":{"position":127,"status":3,"drive":3}}""",
            "driving.averageConsumption" to """{"averageConsumption":{"averageConsumption1":13.8,"averageConsumption2":14.2,"unit":4}}""",
        )
        val parti = DatiAuto.parti(g)
        assertEquals(DatiAuto.Stato.APERTO, parti[DatiAuto.Parte.PORTA_ANT_DX])
        assertEquals(DatiAuto.Stato.CHIUSO, parti[DatiAuto.Parte.PORTA_ANT_SX])
        assertEquals(DatiAuto.Stato.APERTO, parti[DatiAuto.Parte.FINESTRINO_ANT_SX])
        assertNull("status 3: non valido", parti[DatiAuto.Parte.FINESTRINO_ANT_DX])
        assertNull("tetto 255/status 3: non valido", parti[DatiAuto.Parte.TETTO])
        assertEquals("aperta", DatiAuto.parola(DatiAuto.Parte.PORTA_ANT_DX, DatiAuto.Stato.APERTO))
        val i = DatiAuto.istantanea(g)
        assertEquals(14.2, i.kmLitro!!, 0.01)
        assertEquals(listOf("porta ant. dx", "finestrino ant. sx"), i.aperti)
    }
}
