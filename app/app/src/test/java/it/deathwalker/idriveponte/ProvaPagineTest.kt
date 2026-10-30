package it.deathwalker.idriveponte

import android.graphics.BitmapFactory
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProvaPagineTest {

    private val uscita = File("build/pagine/" + BuildConfig.FLAVOR).apply { mkdirs() }

    @Test
    fun zoneAllerta() {
        val z = Fonti.Zone(File("src/test/resources/dati/bollettino.json").readText())
        val spezia = Fonti.allertaDa(z, null, "La Spezia", 44.1025, 9.8241)
        println("SPEZIA: ${spezia?.zona} oggi=${spezia?.oggi} '${spezia?.testoBreve()}'")
        assertEquals("Bacini Liguri Marittimi di Levante", spezia?.zona)
        val crotone = Fonti.allertaDa(z, null, "Crotone", 39.08, 17.12)
        println("CROTONE: ${crotone?.zona} oggi=${crotone?.oggi} '${crotone?.testoBreve()}'")
        assertTrue((crotone?.oggi ?: 0) >= 1)
        val nome = Fonti.allertaDa(z, null, "La Spezia", 0.0, 0.0)
        assertEquals("Bacini Liguri Marittimi di Levante", nome?.zona)
        assertTrue(AutoBtReceiver.eAuto("MINI28359")); assertTrue(AutoBtReceiver.eAuto("BMW 12345"))
        assertTrue(!AutoBtReceiver.eAuto("Saphe Drive Mini")); assertTrue(!AutoBtReceiver.eAuto("Mini speaker"))
        assertTrue(AutoBtReceiver.eAuto("Supra 24680")); assertTrue(AutoBtReceiver.eAuto("TOYOTA SUPRA 24680")); assertTrue(AutoBtReceiver.eAuto("Toyota 24680"))
        assertTrue(!AutoBtReceiver.eAuto("Toyota Yaris")); assertTrue(!AutoBtReceiver.eAuto("BMW Motorrad"))
    }

    @Test
    fun pagineEServer() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = Disegno(ctx)
        val m = Dati.meteoDaJson(File("src/test/resources/dati/meteo.json").readText(), "La Spezia")
        val meteo = d.paginaMeteo(m, Allerta("Bacini Liguri Marittimi di Levante", 1, "temporali", 2, "idrogeologico"))
        File(uscita, "meteo.png").writeBytes(meteo)
        val b = BitmapFactory.decodeByteArray(meteo, 0, meteo.size)
        assertEquals(1280, b.width); assertEquals(480, b.height)

        val lista = Dati.notizieDaRss(File("src/test/resources/dati/rss.xml").readBytes())
        assertTrue(lista.size >= 6)
        lista[0].foto = Dati.decodificaFoto(File("src/test/resources/dati/foto0.jpg").readBytes())
        lista[0].testo = lista[0].testo + ". Testo piu' lungo di prova per vedere come va a capo la descrizione quando supera la riga e arriva alla quarta riga con i puntini finali se serve davvero tanto testo."
        val pronte = lista.mapIndexed { i, n ->
            val p = d.paginaNotizia(n); val mi = d.miniatura(n); val an = d.anteprima(n)
            File(uscita, "notizia$i.png").writeBytes(p); File(uscita, "mini$i.png").writeBytes(mi); File(uscita, "anteprima$i.png").writeBytes(an)
            NotiziaPronta(n.titolo, n.ora, p, mi, an)
        }
        val attesa = "In attesa del telefono: serve l'app ${Marca.APP} e Android Auto wireless collegato. La pagina si aggiorna da sola appena il telefono risponde."
        for ((file, titolo) in listOf("meteo" to "Meteo", "notizie" to "Notizie", "locali" to "Notizie locali", "carburanti" to "Carburanti", "traffico" to "Traffico"))
            File(uscita, "attesa_$file.png").writeBytes(d.paginaAvviso(titolo, attesa))
        File(uscita, "avviso.png").writeBytes(d.paginaAvviso("Meteo", "Meteo non disponibile in questo momento. Nuovo tentativo tra poco."))

        // loghi di prova (disegnati qui: in auto arrivano da MIMIT)
        fun logoProva(colore: Int, testo: String): android.graphics.Bitmap {
            val b = android.graphics.Bitmap.createBitmap(160, 120, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); c.drawColor(colore)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 48f; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(testo, 80f, 78f, p); return b
        }
        val carb = listOf(
            Distributore("Enercoop", "Enercoop", "Via Carducci 12, La Spezia", 1.975, true, 1.8, "30/10 09:31", 1),
            Distributore("Tamoil", "6955", "Via Fieschi 31, La Spezia", 1.979, true, 1.2, "29/10 15:09", 2, logoProva(0xFFE65100.toInt(), "T")),
            Distributore("AgipEni", "x", "Viale Italia 300, La Spezia", 1.989, true, 1.8, "30/10 08:02", 3, logoProva(0xFFF9A825.toInt(), "eni")),
            Distributore("Q8", "x", "Via Aurelia Nord 5, Sarzana", 1.995, true, 4.7, "30/10 07:40", 4, logoProva(0xFF1565C0.toInt(), "Q8")),
            Distributore("Pompe Bianche", "x", "Strada Provinciale 62 km 4,500, Ceparana", 2.019, false, 3.3, "", 5),
            Distributore("Api-Ip", "x", "Via Nazionale 12, Ceparana", 2.021, true, 2.1, "30/10 06:11", 6)
        )
        File(uscita, "carburanti.png").writeBytes(d.paginaCarburanti(carb, "Benzina", "La Spezia", 5))
        File(uscita, "carburanti_vuoto.png").writeBytes(d.paginaCarburanti(emptyList(), "GPL", "La Spezia", 3))
        val tr = listOf(
            EventoTraffico("A12 GENOVA-ROMA", "Coda", "Coda di 2 km tra Chiavari e Rapallo per lavori", 57.0, 2.0, "14:21", 2),
            EventoTraffico("A11 FIRENZE-PISA NORD", "Lavori", "Restringimento di carreggiata tra Lucca e Pisa nord per lavori", 70.0, 0.0, "13:10", 1),
            EventoTraffico("A7 SERRAVALLE-GENOVA", "Tratto Chiuso", "Tratto chiuso tra Genova Bolzaneto e Bivio A7/A10 per incidente, uscita obbligatoria a Genova Bolzaneto", 82.0, 4.0, "14:02", 2)
        )
        File(uscita, "traffico.png").writeBytes(d.paginaTraffico(tr, "La Spezia", 100))
        File(uscita, "traffico_vuoto.png").writeBytes(d.paginaTraffico(emptyList(), "La Spezia", 100))
        Stato.meteo.png = meteo; Stato.meteo.epoch = 111; Stato.notizie.notizie = pronte; Stato.notizie.epoch = 222
        val srv = Server {}
        srv.avvia()
        Thread.sleep(500)
        fun chiedi(percorso: String): ByteArray = Socket("127.0.0.1", Stato.PORTA).use { s ->
            s.getOutputStream().write("GET $percorso HTTP/1.0\r\nHost: x\r\n\r\n".toByteArray()); s.getInputStream().readBytes()
        }
        val r1 = String(chiedi("/ponte")); println(r1)
        assertTrue(r1.contains("${Stato.VERSIONE} meteo=111 notizie=222 n=${pronte.size}"))
        val r2 = String(chiedi("/notizie.txt"), Charsets.ISO_8859_1); println(r2)
        assertTrue("accenti in Latin-1", r2.contains("velocità") && r2.contains(" · "))
        assertTrue(r2.contains("NOTIZIE 222"))
        val r3 = chiedi("/meteo.png"); assertTrue(String(r3, 0, 40).startsWith("HTTP/1.0 200"))
        assertTrue(r3.size > meteo.size)
        val r4 = String(chiedi("/notizia/9.png")); assertTrue(r4.startsWith("HTTP/1.0 404"))
        val r5 = chiedi("/anteprima/0.png"); assertTrue(String(r5, 0, 40).startsWith("HTTP/1.0 200"))
        File(uscita, "risposte.txt").writeText(r1 + "\n" + r2 + "\n" + String(r3, 0, 120) + "\n" + r4)
        srv.ferma()

        // schermata dell'app (telefono 1080 px di larghezza)
        Stato.carburanti.metti(File(uscita, "carburanti.png").readBytes()); Stato.carburanti.esito = "OK alle 14:30 - Benzina, 5 distributori entro 5 km, il migliore 1,975 €"
        Stato.traffico.metti(File(uscita, "traffico.png").readBytes()); Stato.traffico.esito = "OK alle 14:30 - 3 eventi entro 100 km"
        Stato.locali.notizie = pronte; Stato.locali.epoch = 5; Stato.locali.esito = "OK alle 14:30 - 8 notizie, 1 con foto"
        Stato.meteo.esito = "OK alle 14:30 - La Spezia 20° (posizione del telefono)"; Stato.allerta = "Allerta gialla oggi, arancione domani"
        Stato.notizie.esito = "OK alle 14:30 - 8 notizie, 1 con foto"
        Stato.servizioAttivo = true; Stato.avviatoDa = "Bluetooth ${Marca.AUTO} 28359"; Stato.wifiAuto = "172.16.224.104"
        Stato.ultimaRichiestaAuto = System.currentTimeMillis(); Stato.richiesteAuto = 42
        val att = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val radice = att.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val sv = (radice as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView
        val col = sv.getChildAt(0)
        val ws = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
        val hs = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        col.measure(ws, hs); col.layout(0, 0, col.measuredWidth, col.measuredHeight)
        val shot = android.graphics.Bitmap.createBitmap(col.measuredWidth, col.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(shot); cv.drawColor(android.graphics.Color.rgb(10, 12, 15)); col.draw(cv)
        File(uscita, "app.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
