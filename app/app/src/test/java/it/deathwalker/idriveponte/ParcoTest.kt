package it.deathwalker.idriveponte

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParcoTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun nomeSoloVisivo() {
        // nessun nome: si vede quello Bluetooth
        assertEquals("MINI12345", Parco.mostra(ctx, "MINI12345"))

        // nome dato prima di collegare un'auto: vale per tutte
        Parco.imposta(ctx, null, "  La   mia Cooper ")
        assertEquals("La mia Cooper", Parco.mostra(ctx, "MINI12345"))

        // l'auto si collega: il nome diventa suo, il riconoscimento della marca non cambia
        Parco.ricorda(ctx, "MINI12345")
        assertEquals("MINI12345", Parco.ultima(ctx))
        Parco.imposta(ctx, "MINI12345", "Lucy")
        assertEquals("Lucy", Parco.mostra(ctx, "MINI12345"))
        assertEquals("BMW 67890", Parco.mostra(ctx, "BMW 67890"))
        assertEquals(true, AutoBtReceiver.eAuto("MINI12345"))
        assertEquals("acceso da Lucy - 31/10 19:40", Parco.nelTesto(ctx, "acceso da MINI12345 - 31/10 19:40"))

        // troppo lungo: si taglia; vuoto: torna il nome Bluetooth
        Parco.imposta(ctx, "MINI12345", "x".repeat(40))
        assertEquals(Parco.MASSIMO, Parco.mostra(ctx, "MINI12345").length)
        Parco.imposta(ctx, "MINI12345", "")
        assertEquals("MINI12345", Parco.mostra(ctx, "MINI12345"))
    }

    @Test
    fun autoRitrovataDaiDatiVecchi() {
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit()
            .putString("ultimo_bt", "spento, BMW 67890 scollegata - parcheggio salvato - 30/10 08:12").apply()
        assertEquals("BMW 67890", Parco.ultima(ctx))
        assertEquals("MINI28359", AutoBtReceiver.trovaAuto("acceso da MINI28359 - 31/10 19:00"))
    }

    @Test
    fun parcoVetture() {
        // due auto: l'ultima collegata e' quella attuale, ognuna col suo carburante
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putInt("carburante", 2).apply()   // vecchia impostazione unica
        Parco.ricorda(ctx, "MINI28359")
        Thread.sleep(5)
        Parco.ricorda(ctx, "BMW 67890")
        assertEquals(listOf("BMW 67890", "MINI28359"), Parco.elenco(ctx))
        assertEquals("BMW 67890", Parco.ultima(ctx))
        assertEquals(2, Parco.carburante(ctx, "MINI28359"))          // eredita quella di prima
        Parco.impostaCarburante(ctx, "MINI28359", 1)
        assertEquals(1, Parco.carburante(ctx, "MINI28359"))
        assertEquals(2, Parco.carburante(ctx, "BMW 67890"))

        // aggiunta dal Bluetooth: entra nell'elenco ma non diventa l'auto attuale
        Parco.aggiungi(ctx, "MINI 11111")
        assertEquals("BMW 67890", Parco.ultima(ctx))
        assertEquals(3, Parco.elenco(ctx).size)

        // togli: via nome, carburante e parcheggio; l'attuale passa all'altra auto piu' recente
        Parco.imposta(ctx, "BMW 67890", "Ufficio")
        Parco.togli(ctx, "BMW 67890")
        assertEquals("MINI28359", Parco.ultima(ctx))
        assertEquals("BMW 67890", Parco.mostra(ctx, "BMW 67890"))
        assertEquals(listOf("MINI28359", "MINI 11111"), Parco.elenco(ctx))
    }

    @Test
    fun parcheggioPerAuto() {
        // il parcheggio unico delle versioni precedenti passa all'auto a cui apparteneva
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit()
            .putFloat("park_lat", 44.1f).putFloat("park_lon", 9.8f).putLong("park_quando", 1000L)
            .putString("park_indirizzo", "Via Roma 1, La Spezia").putString("park_auto", "MINI28359").apply()
        val p = Parcheggio.ultimo(ctx, "MINI28359")!!
        assertEquals("Via Roma 1, La Spezia", p.indirizzo)
        assertEquals("MINI28359", Parco.ultima(ctx))
        assertNull(Parcheggio.ultimo(ctx, "BMW 67890"))
        assertEquals(1, Parcheggio.tutti(ctx).size)
        Parco.togli(ctx, "MINI28359")
        assertNull(Parcheggio.ultimo(ctx, "MINI28359"))
        assertEquals(0, Parcheggio.tutti(ctx).size)
    }
}
