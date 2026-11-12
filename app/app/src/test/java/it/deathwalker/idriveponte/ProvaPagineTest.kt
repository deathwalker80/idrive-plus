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

    // 2.33: pagine in PNG a 256 colori: molto piu' piccole e, sopra lo sfondo dell'iDrive, uguali a vista
    @Test
    fun pngLeggero() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = Disegno(ctx)
        val cartella = File(uscita, "confronto").apply { mkdirs() }
        val m = Dati.meteoDaJson(File("src/test/resources/dati/meteo.json").readText(), "La Spezia")
        val lista = Dati.notizieDaRss(File("src/test/resources/dati/rss.xml").readBytes())
        lista[0].foto = Dati.decodificaFoto(File("src/test/resources/dati/foto0.jpg").readBytes())
        val pagine = listOf<Pair<String, () -> ByteArray>>(
            "meteo" to { d.paginaMeteo(m, null) },
            "meteo_sfondo2" to { d.paginaMeteo(m, null, 2) },
            "meteo_oggi" to { d.paginaGiornoMeteo(m, 0) },
            "notizia0" to { d.paginaNotizia(lista[0]) },
            "pp_scelta2" to { d.primoPiano(lista, "Notizie", "ANSA", 2) })
        for ((nome, disegna) in pagine) {
            PngLeggero.imposta(ctx, false)
            val pieno = disegna()
            PngLeggero.imposta(ctx, true)
            val inizio = System.nanoTime()
            val leggero = disegna()
            val ms = (System.nanoTime() - inizio) / 1_000_000
            File(cartella, "${nome}_pieno.png").writeBytes(pieno)
            File(cartella, "${nome}_leggero.png").writeBytes(leggero)
            val a = BitmapFactory.decodeByteArray(pieno, 0, pieno.size)
            val b = BitmapFactory.decodeByteArray(leggero, 0, leggero.size)
            assertEquals(1280, b.width); assertEquals(480, b.height)
            // differenza media sopra uno sfondo come quello dell'iDrive (0x285A69)
            val pa = IntArray(1280 * 480); val pb = IntArray(1280 * 480)
            a.getPixels(pa, 0, 1280, 0, 0, 1280, 480); b.getPixels(pb, 0, 1280, 0, 0, 1280, 480)
            fun sopra(c: Int, sh: Int, fondo: Int): Int { val al = c ushr 24; return ((c ushr sh and 255) * al + fondo * (255 - al)) / 255 }
            var somma = 0L
            for (i in pa.indices) for ((sh, f) in listOf(16 to 0x28, 8 to 0x5A, 0 to 0x69))
                somma += kotlin.math.abs(sopra(pa[i], sh, f) - sopra(pb[i], sh, f))
            val media = somma.toDouble() / (pa.size * 3)
            println("PNG $nome: pieno ${pieno.size / 1024} KB, leggero ${leggero.size / 1024} KB (${leggero.size * 100 / pieno.size}%), " +
                "differenza media %.2f, disegno+codifica $ms ms".format(media))
            assertTrue("$nome: ${leggero.size} contro ${pieno.size}", leggero.size * 100 < pieno.size * 45)
            assertTrue("$nome: differenza media $media", media < 3.0)
        }
        PngLeggero.imposta(ctx, true)
    }

    @Test
    fun pagineEServer() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = Disegno(ctx)
        val m = Dati.meteoDaJson(File("src/test/resources/dati/meteo.json").readText(), "La Spezia")
        val meteo = d.paginaMeteo(m, Allerta("Bacini Liguri Marittimi di Levante", 1, "temporali", 2, "idrogeologico"))
        File(uscita, "meteo.png").writeBytes(meteo)
        // la pastiglia dell'allerta sta tutta nei 400 della colonna sinistra (prima "temporali" era tagliato)
        val lunga = Allerta("Bacini Liguri Marittimi di Levante", 2, "idrogeologico, temporali", 0, "", domaniNoto = false)
        assertEquals("⚠ Allerta arancione oggi", d.testoPastiglia(lunga))
        File(uscita, "meteo_allerta_lunga.png").writeBytes(d.paginaMeteo(m, lunga))
        val corta = Allerta("Bacini Liguri Marittimi di Levante", 1, "temporali", 0, "")
        assertEquals("⚠ Allerta gialla oggi · temporali", d.testoPastiglia(corta))
        val due = Allerta("Bacini Liguri Marittimi di Levante", 1, "temporali", 3, "idrogeologico")
        assertEquals("⚠ Allerta gialla oggi, rossa domani", d.testoPastiglia(due))
        // plugin Meteo v12: pagina col giorno 2 evidenziato, colonna del giorno 2, pagina di oggi e di un giorno coi
        // dettagli; i pulsanti del plugin devono stare esattamente sulle colonne disegnate
        File(uscita, "meteo_sfondo2.png").writeBytes(d.paginaMeteo(m, null, 2))
        File(uscita, "meteo_colonna2.png").writeBytes(d.colonnaMeteo(m, 2))
        File(uscita, "meteo_oggi.png").writeBytes(d.paginaGiornoMeteo(m, 0))
        File(uscita, "meteo_giorno3.png").writeBytes(d.paginaGiornoMeteo(m, 3))
        val g3 = d.giorniMeteo(m)[3]
        assertEquals(18, g3.ventoMax); assertEquals(34, g3.raffiche); assertEquals("O", g3.direzione)
        assertEquals(8, g3.ore.size); assertTrue(g3.umidita in 60..90)
        val xml = File("../../chiavetta/Meteo/Meteo.xml").readText()
        for (i in 0 until 6) {
            val r = d.rettangoloGiorno(i)
            val blocco = Regex("id=\"${200 + i}\"[\\s\\S]*?</button>").find(xml)!!.value
            fun prop(id: Int) = Regex("property id=\"$id\" value=\"(\\d+)\"").find(blocco)!!.groupValues[1].toInt()
            assertEquals("giorno $i", listOf(r[0], r[1], r[2], r[3]), listOf(prop(20), prop(21), prop(9), prop(10)))
        }
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
        for ((file, titolo) in listOf("meteo" to "Meteo", "notizie" to "Notizie", "locali" to "Notizie locali", "carburanti" to "Carburanti", "traffico" to "Traffico", "auto" to "Stato vettura"))
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

        // v13: notizie in primo piano e carburanti da scegliere con la manopola, coi pulsanti dei plugin esattamente
        // sulle righe e sulle schede disegnate
        File(uscita, "pp_scelta2.png").writeBytes(d.primoPiano(lista, "Notizie", "ANSA", 2))
        File(uscita, "pp_riga2.png").writeBytes(d.rigaNotizia(lista, "Notizie", "ANSA", 2))
        carb[1].prezzi = listOf(Prezzo("Benzina", true, 1.979), Prezzo("Benzina", false, 2.119), Prezzo("Gasolio", true, 1.899),
            Prezzo("Gasolio", false, 2.039), Prezzo("Gpl", false, 0.719))
        File(uscita, "carb_scelta1.png").writeBytes(d.paginaCarburanti(carb, "Benzina", "La Spezia", 5, 1))
        File(uscita, "carb_scheda1.png").writeBytes(d.schedaDistributore(carb, "Benzina", "La Spezia", 5, 1))
        File(uscita, "carb_distributore0.png").writeBytes(d.paginaDistributore(carb[1], "Benzina", carb.map { it.prezzo }.average()))
        File(uscita, "carb_distributore_solo.png").writeBytes(d.paginaDistributore(carb[0], "Benzina", null))
        for ((nome, righe, rett) in listOf(Triple("Notizie", 8, d::rettangoloRigaNotizia), Triple("Locali", 8, d::rettangoloRigaNotizia),
                Triple("Carburanti", 6, d::rettangoloDistributore))) {
            val testo = File("../../chiavetta/$nome/$nome.xml").readText()
            for (i in 0 until righe) {
                val r = rett(i)
                val blocco = Regex("id=\"${200 + i}\"[\\s\\S]*?</button>").find(testo)!!.value
                fun prop(id: Int) = Regex("property id=\"$id\" value=\"(\\d+)\"").find(blocco)!!.groupValues[1].toInt()
                assertEquals("$nome riga $i", r.toList(), listOf(prop(20), prop(21), prop(9), prop(10)))
            }
        }
        val tr = listOf(
            EventoTraffico("A12 GENOVA-ROMA", "Coda", "Coda di 2 km tra Chiavari e Rapallo per lavori", 57.0, 2.0, "14:21", 2),
            EventoTraffico("A11 FIRENZE-PISA NORD", "Lavori", "Restringimento di carreggiata tra Lucca e Pisa nord per lavori", 70.0, 0.0, "13:10", 1),
            EventoTraffico("A7 SERRAVALLE-GENOVA", "Tratto Chiuso", "Tratto chiuso tra Genova Bolzaneto e Bivio A7/A10 per incidente, uscita obbligatoria a Genova Bolzaneto", 82.0, 4.0, "14:02", 2)
        )
        File(uscita, "traffico.png").writeBytes(d.paginaTraffico(tr, "La Spezia", 100))
        File(uscita, "traffico_vuoto.png").writeBytes(d.paginaTraffico(emptyList(), "La Spezia", 100))
        // stato dell'auto: viaggio in corso con un finestrino aperto, poi tutto chiuso, poi senza dati
        val grezzi = mapOf(
            "driving.odometer" to """{"odometer":48213}""",
            "sensors.fuel" to """{"fuel":{"tanklevel":31,"range":437,"reserve":0}}""",
            "driving.averageConsumption" to """{"averageConsumption":{"averageConsumption1":6.4,"unit":1}}""",
            "sensors.temperatureExterior" to """{"temperatureExterior":14.5}""",
            "sensors.temperatureInterior" to """{"temperatureInterior":21}""",
            "sensors.doors" to """{"doors":{"driver":0,"passenger":0,"driverRear":0,"passengerRear":1}}""",
            "sensors.trunk" to """{"trunk":1}""",
            "sensors.lid" to """{"lid":0}""",
            "controls.windowPassengerFront" to """{"windowPassengerFront":{"position":0,"status":0,"drive":0}}""",
            "controls.windowDriverRear" to """{"windowDriverRear":{"position":0,"status":0,"drive":0}}""",
            "controls.windowPassengerRear" to """{"windowPassengerRear":{"position":127,"status":3,"drive":3}}""",
            "controls.windowDriverFront" to """{"windowDriverFront":{"status":1,"position":12,"tiltPosition":0}}""",
            "controls.sunroof" to """{"sunroof":{"status":0,"position":0,"tiltPosition":3}}""",
        )
        val adesso = System.currentTimeMillis()
        val viaggio = Viaggio.Dati("${Marca.AUTO}28359", adesso - 38 * 60000L, "Via Roma 1, La Spezia", 0L, "", 48195.6, 32.3, 31.0, 0.0, grezzi, adesso)
        val rif = Rifornimenti.Voce("${Marca.AUTO}28359", adesso - 3 * 86400000L, 34.0, 41.0, 47900.0, null, null, "", "", null, false)
        File(uscita, "auto.png").writeBytes(d.paginaAuto("La mia ${Marca.AUTO}", DatiAuto.istantanea(grezzi), viaggio, rif, adesso, 44.0))
        val chiusa = grezzi + ("controls.windowDriverFront" to """{"windowDriverFront":{"status":0,"position":0,"tiltPosition":0}}""") +
            ("controls.sunroof" to """{"sunroof":{"status":0,"position":0,"tiltPosition":0}}""")
        File(uscita, "auto_chiusa.png").writeBytes(d.paginaAuto("La mia ${Marca.AUTO}", DatiAuto.istantanea(chiusa), null, null, adesso))
        File(uscita, "auto_vuoto.png").writeBytes(d.paginaAuto("${Marca.AUTO}28359", null, null, null, 0L))
        // Stato vettura: porte, finestrini e tetto con la foto di serie (MINI Plus) o senza foto
        val foto = Foto.immagine(ctx, "")
        val ritaglio = Foto.contornoAuto(ctx, "")
        val punti = Foto.punti(ctx, "")
        val tutteChiuse = chiusa + ("sensors.doors" to """{"doors":{"driver":0,"passenger":0,"driverRear":0,"passengerRear":0}}""") +
            ("sensors.trunk" to """{"trunk":0}""") + ("controls.windowPassengerRear" to """{"windowPassengerRear":{"position":0,"status":0,"drive":0}}""")
        File(uscita, "porte.png").writeBytes(d.paginaPorte(foto, ritaglio, punti, DatiAuto.parti(grezzi), adesso))
        File(uscita, "porte_chiuse.png").writeBytes(d.paginaPorte(foto, ritaglio, punti, DatiAuto.parti(tutteChiuse), adesso))
        File(uscita, "porte_senza_foto.png").writeBytes(d.paginaPorte(null, null, punti, DatiAuto.parti(grezzi), adesso))
        File(uscita, "mini_riepilogo.png").writeBytes(d.miniaturaRiepilogo(DatiAuto.istantanea(grezzi)))
        File(uscita, "mini_porte.png").writeBytes(d.miniaturaPorte(foto, ritaglio, DatiAuto.parti(grezzi)))
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
        // storico e rifornimenti di prova per la schermata (auto dei test di pagina, non quella di ViaggioTest)
        val bt = "${Marca.AUTO}70001"
        Parco.ricorda(ctx, bt)
        val g = mapOf("driving.odometer" to """{"odometer":48213}""", "sensors.fuel" to """{"fuel":{"tanklevel":31,"range":437}}""")
        for (k in 0 until 3) {
            val p = adesso - (k + 1) * 86400000L
            Storico.registra(ctx, Viaggio.Dati(bt, p, "Via Roma 1, La Spezia", p + 27 * 60000L, "Via Aurelia 5, Sarzana",
                48195.6 - k * 40, 32.3 + k * 3, 31.0, 0.0, g, p))
        }
        Rifornimenti.livello(ctx, bt, 9.0, 47900.0, adesso - 5 * 86400000L)
        Rifornimenti.livello(ctx, bt, 43.0, 47900.0, adesso - 5 * 86400000L + 60000L)
        // Stato vettura per la scheda dell'app: le stesse pagine che vede l'iDrive
        Stato.vettura.metti(listOf(
            NotiziaPronta("Riepilogo", "", File(uscita, "auto.png").readBytes(), File(uscita, "mini_riepilogo.png").readBytes(), ByteArray(0), 1L),
            NotiziaPronta("Porte, finestrini e tetto", "", File(uscita, "porte.png").readBytes(), File(uscita, "mini_porte.png").readBytes(), ByteArray(0), 2L),
        ))
        // riassunti per le icone della home e valori dell'auto per la copertina
        Stato.meteoBreve = Stato.MeteoBreve("La Spezia", 20, "Poco nuvoloso", "poco_nuvoloso")
        Stato.carburantiBreve = "Benzina 1,975 €" to "Q8 · 1,2 km"
        Stato.trafficoBreve = "3 eventi" to "A12: Coda"
        DatiAuto.grezzi = g + ("driving.averageConsumption" to """{"averageConsumption":{"averageConsumption2":14.2,"unit":4}}""")
        val att = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val radice = att.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val sv = (radice as android.view.ViewGroup).getChildAt(0) as android.widget.ScrollView
        val col = sv.getChildAt(0)
        val scatta = { nome: String ->
            val ws = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val hs = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            col.measure(ws, hs); col.layout(0, 0, col.measuredWidth, col.measuredHeight)
            val shot = android.graphics.Bitmap.createBitmap(col.measuredWidth, col.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(shot); cv.drawColor(android.graphics.Color.rgb(10, 12, 15)); col.draw(cv)
            File(uscita, nome).outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        scatta("app.png")                  // home: foto dell'auto e griglia di icone
        att.apriSezione("vettura")
        scatta("app_vettura.png")            // una sezione aperta
        att.apriSezione("meteo")
        scatta("app_meteo.png")
    }
}
