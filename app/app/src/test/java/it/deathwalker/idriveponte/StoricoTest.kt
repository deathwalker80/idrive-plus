package it.deathwalker.idriveponte

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoricoTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    /** un'auto tutta sua: Viaggio tiene i viaggi anche in memoria, non deve toccare quelli degli altri test */
    private val auto = "MINI99001"
    private val min = 60 * 1000L

    private fun valori(km: Double, litri: Double, finestrino: Int = 0) = mapOf(
        "driving.odometer" to """{"odometer":$km}""",
        "sensors.fuel" to """{"fuel":{"tanklevel":$litri,"range":${litri * 14},"reserve":0}}""",
        "sensors.doors" to """{"doors":{"driverFront":1,"passengerFront":0}}""",
        "controls.windowDriverFront" to """{"windowDriverFront":{"status":$finestrino,"position":${finestrino * 10},"tiltPosition":0}}""",
    )

    @After
    fun pulisci() {
        Viaggio.cancella(ctx, auto)
        Storico.cancella(ctx, auto)
        Rifornimenti.cancella(ctx, auto)
    }

    /** un viaggio finito: va nello storico con km, litri, partenza e arrivo; se riprende ne esce */
    @Test
    fun viaggioNelloStorico() {
        Parco.ricorda(ctx, auto)
        Viaggio.inizia(ctx, auto)
        Viaggio.impostaLuogo(ctx, auto, "Via Roma 1, La Spezia")
        Viaggio.aggiorna(ctx, auto, valori(1000.0, 30.0))
        Viaggio.aggiorna(ctx, auto, valori(1018.0, 28.6))
        // il viaggio e' partito 25 minuti fa (piu' corto di un minuto non conterebbe): lo si riscrive cosi'
        val d = Viaggio.di(ctx, auto)!!
        val indietro = Viaggio.Dati(d.auto, d.partenza - 25 * min, d.luogo, 0L, "", d.kmPartenza, d.litriPartenza, d.litriPrima, 0.0, d.grezzi, d.aggiornato)
        Viaggio.cancella(ctx, auto)
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putString("viaggio:$auto", indietro.json()).commit()
        Viaggio.chiudi(ctx, auto, "Via Aurelia 5, Sarzana")
        val s = Storico.elenco(ctx, auto)
        assertEquals(1, s.size)
        assertEquals("Via Roma 1, La Spezia", s[0].da)
        assertEquals("Via Aurelia 5, Sarzana", s[0].a)
        assertEquals(18.0, s[0].km!!, 0.01)
        assertEquals(1.4, s[0].litri!!, 0.01)
        assertEquals(18.0 / 1.4, s[0].kmLitro()!!, 0.01)
        assertTrue(s[0].durata >= 25 * min)
        // valori arrivati subito dopo lo scollegamento: aggiornano il viaggio appena chiuso
        Viaggio.aggiorna(ctx, auto, valori(1018.4, 28.5))
        assertEquals(18.4, Storico.elenco(ctx, auto)[0].km!!, 0.01)
        assertEquals("Via Aurelia 5, Sarzana", Storico.elenco(ctx, auto)[0].a)
        // ricollegata entro pochi minuti: il viaggio riprende ed esce dallo storico
        Viaggio.inizia(ctx, auto)
        assertTrue(Storico.elenco(ctx, auto).isEmpty())
        Viaggio.chiudi(ctx, auto, "Via Aurelia 7, Sarzana")
        assertEquals(1, Storico.elenco(ctx, auto).size)
        assertEquals("Via Aurelia 7, Sarzana", Storico.elenco(ctx, auto)[0].a)
        // l'auto tolta dal parco porta via anche storico e rifornimenti
        Parco.togli(ctx, auto)
        assertTrue(Storico.elenco(ctx, auto).isEmpty())
    }

    /** il serbatoio che sale: un rifornimento, anche a scatti e fra un viaggio e l'altro */
    @Test
    fun rifornimentiDalSerbatoio() {
        val t = 1_800_000_000_000L
        assertNull(Rifornimenti.livello(ctx, auto, 12.0, 5000.0, t))
        assertNull(Rifornimenti.livello(ctx, auto, 11.0, 5010.0, t + 10 * min))
        // oscillazioni piccole: niente
        assertNull(Rifornimenti.livello(ctx, auto, 13.0, 5010.0, t + 11 * min))
        assertNull(Rifornimenti.livello(ctx, auto, 11.0, 5011.0, t + 12 * min))
        // motore spento alla pompa, si riaccende un'ora dopo con 30 litri in piu': un rifornimento
        val r = Rifornimenti.livello(ctx, auto, 41.0, 5011.0, t + 72 * min)
        assertNotNull(r)
        assertEquals(30.0, r!!.litri, 0.01)
        // l'indicatore sale ancora un poco: lo stesso rifornimento si allunga
        Rifornimenti.livello(ctx, auto, 44.0, 5011.0, t + 73 * min)
        assertEquals(1, Rifornimenti.elenco(ctx, auto).size)
        assertEquals(33.0, Rifornimenti.elenco(ctx, auto)[0].litri, 0.01)
        assertEquals(44.0, Rifornimenti.elenco(ctx, auto)[0].livello, 0.01)
        assertEquals(5011.0, Rifornimenti.elenco(ctx, auto)[0].km!!, 0.01)
        // si guida e il livello scende; il giorno dopo un rifornimento che sale a scatti da 2 litri
        Rifornimenti.livello(ctx, auto, 20.0, 5400.0, t + 1440 * min)
        Rifornimenti.livello(ctx, auto, 22.0, 5400.0, t + 1441 * min)
        Rifornimenti.livello(ctx, auto, 24.0, 5400.0, t + 1442 * min)
        val r2 = Rifornimenti.livello(ctx, auto, 26.0, 5400.0, t + 1443 * min)
        assertNotNull(r2)
        assertEquals(6.0, r2!!.litri, 0.01)
        assertEquals(2, Rifornimenti.elenco(ctx, auto).size)
        // prezzo noto: spesa stimata
        val v = r.con(prezzo = 1.9)
        assertEquals(30.0 * 1.9, v.spesa()!!, 0.01)
        Rifornimenti.cancella(ctx, auto)
        assertTrue(Rifornimenti.elenco(ctx, auto).isEmpty())
    }

    /** finestrino aperto quando il telefono si scollega: avviso; le porte non contano; chiuso subito dopo: l'avviso sparisce */
    @Test
    fun avvisoAperture() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ombra = Shadows.shadowOf(nm)
        Viaggio.cancella(ctx, auto)
        Viaggio.inizia(ctx, auto)
        Viaggio.aggiorna(ctx, auto, valori(2000.0, 30.0, finestrino = 0))
        Viaggio.chiudi(ctx, auto)
        assertNull("solo la porta aperta: nessun avviso", ombra.getNotification(auto, 3))
        Viaggio.inizia(ctx, auto)
        Viaggio.aggiorna(ctx, auto, valori(2001.0, 30.0, finestrino = 2))
        Viaggio.chiudi(ctx, auto)
        val n = ombra.getNotification(auto, 3)
        assertNotNull(n)
        assertTrue(n.extras.getCharSequence("android.title").toString().contains("Finestrino ant. sx aperto"))
        // il finestrino si chiude subito dopo lo scollegamento: l'avviso sparisce
        Viaggio.aggiorna(ctx, auto, valori(2001.0, 30.0, finestrino = 0))
        assertNull(ombra.getNotification(auto, 3))
        assertEquals(listOf("finestrino ant. sx", "tetto"), Aperture.rimasti(DatiAuto.Istantanea(null, null, null, null, null, null,
            listOf("porta ant. sx", "finestrino ant. sx", "tetto"))))
    }
}
