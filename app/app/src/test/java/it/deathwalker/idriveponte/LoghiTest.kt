package it.deathwalker.idriveponte

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoghiTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun loghiDelMinistero() {
        // estratto dell'elenco vero di MIMIT (registry/alllogos): stesso formato, base64 senza prefisso
        val cartella = File(ctx.filesDir, "loghi").apply { mkdirs() }
        val salvati = Fonti.salvaLoghi(File("src/test/resources/dati/loghi_mimit.json").readText(), cartella)
        assertEquals(10, salvati)                                  // "Bandiera non selezionata" non ha logo
        File(cartella, ".scaricati").writeText("prova")             // niente rete nel test
        // i marchi come li scrive la ricerca dei distributori vicino alla Spezia
        for (m in listOf("AgipEni", "Api-Ip", "PompeBianche", "COIL", "Ala", "Enercoop", "Esso", "Q8", "Tamoil"))
            assertNotNull(m, Fonti.logo(ctx, m))

        val carb = listOf(
            Distributore("Enercoop", "x", "Via Carducci 12, La Spezia", 1.975, true, 1.8, "30/10 09:31", 1),
            Distributore("Tamoil", "x", "Via Fieschi 31, La Spezia", 1.979, true, 1.2, "29/10 15:09", 2),
            Distributore("AgipEni", "x", "Viale Italia 300, La Spezia", 1.989, true, 1.8, "30/10 08:02", 3),
            Distributore("Q8", "x", "Via Aurelia Nord 5, Sarzana", 1.995, true, 4.7, "30/10 07:40", 4),
            Distributore("PompeBianche", "x", "Strada Provinciale 62, Ceparana", 2.019, false, 3.3, "", 5),
            Distributore("Api-Ip", "x", "Via Nazionale 12, Ceparana", 2.021, true, 2.1, "30/10 06:11", 6),
        )
        for (d in carb) d.logo = Fonti.logo(ctx, d.marchio)
        File("build/pagine").mkdirs()
        File("build/pagine/carburanti_loghi_veri.png").writeBytes(Disegno(ctx).paginaCarburanti(carb, "Benzina", "La Spezia", 5))
    }
}
