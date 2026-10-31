package it.deathwalker.idriveponte

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NomeAutoTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun nomeSoloVisivo() {
        // nessun nome: si vede quello Bluetooth
        assertEquals("MINI12345", NomeAuto.mostra(ctx, "MINI12345"))

        // nome dato prima di collegare un'auto: vale per tutte
        NomeAuto.imposta(ctx, null, "  La   mia Cooper ")
        assertEquals("La mia Cooper", NomeAuto.mostra(ctx, "MINI12345"))

        // l'auto si collega: il nome diventa suo, il riconoscimento della marca non cambia
        NomeAuto.ricorda(ctx, "MINI12345")
        assertEquals("MINI12345", NomeAuto.ultima(ctx))
        NomeAuto.imposta(ctx, "MINI12345", "Lucy")
        assertEquals("Lucy", NomeAuto.mostra(ctx, "MINI12345"))
        assertEquals("BMW 67890", NomeAuto.mostra(ctx, "BMW 67890"))
        assertEquals(Marca.MINI, AutoBtReceiver.marca("MINI12345"))
        assertEquals("acceso da Lucy - 31/10 19:40", NomeAuto.nelTesto(ctx, "acceso da MINI12345 - 31/10 19:40"))

        // troppo lungo: si taglia; vuoto: torna il nome Bluetooth
        NomeAuto.imposta(ctx, "MINI12345", "x".repeat(40))
        assertEquals(NomeAuto.MASSIMO, NomeAuto.mostra(ctx, "MINI12345").length)
        NomeAuto.imposta(ctx, "MINI12345", "")
        assertEquals("MINI12345", NomeAuto.mostra(ctx, "MINI12345"))
    }

    @Test
    fun autoRitrovataDaiDatiVecchi() {
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit()
            .putString("ultimo_bt", "spento, BMW 67890 scollegata - parcheggio salvato - 30/10 08:12").apply()
        assertEquals("BMW 67890", NomeAuto.ultima(ctx))
        assertEquals("MINI28359", AutoBtReceiver.trovaAuto("acceso da MINI28359 - 31/10 19:00"))
    }
}
