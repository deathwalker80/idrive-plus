package it.deathwalker.idriveponte

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MINI Plus / BMW Plus / Supra Plus: schermata del telefono. In alto la foto dell'auto, sotto una griglia di icone; ogni
 * icona apre la sua schermata: stato del collegamento con l'iDrive, stato vettura, dove hai parcheggiato, storico dei
 * viaggi e rifornimenti, anteprima di ogni pagina che vede l'auto, impostazioni e permessi.
 * Si aggiorna da sola ogni 1,5 s senza toccare i campi di testo (non interrompe mai la scrittura).
 */
class MainActivity : Activity() {

    // tavolozza: nero, schede grafite, colore del "+" dell'icona (azzurro MINI, giallo BMW, rosso Supra)
    private val SFONDO = Color.rgb(10, 12, 15)
    private val SCHEDA = Color.rgb(24, 28, 34)
    private val BORDO = Color.rgb(40, 46, 55)
    private val TESTO = Color.rgb(236, 240, 244)
    private val TENUE = Color.rgb(150, 160, 172)
    private val ACCENTO = Marca.ACCENTO
    private val SU_ACCENTO = Marca.SU_ACCENTO
    private val VERDE = Color.rgb(76, 200, 120)
    private val ROSSO = Color.rgb(240, 90, 80)
    private val ARANCIO = Color.rgb(250, 160, 50)

    private val mano = Handler(Looper.getMainLooper())
    private var dp = 1f

    // stato
    private lateinit var puntoAuto: View
    private lateinit var testoAuto: TextView
    private lateinit var tastoModificaAuto: TextView
    private lateinit var puntoServizio: View
    private lateinit var testoServizio: TextView
    private lateinit var puntoWifi: View
    private lateinit var testoWifi: TextView
    private lateinit var puntoIdrive: View
    private lateinit var testoIdrive: TextView
    private lateinit var testoBt: TextView
    private lateinit var testoInCorso: TextView
    private lateinit var tastoAccendi: TextView

    // parcheggio
    private lateinit var boxParcheggi: LinearLayout
    private lateinit var boxParco: LinearLayout
    private lateinit var schedaParco: View
    private var firmaParcheggi = ""
    private var firmaParco = ""
    private lateinit var schedaViaggio: View
    private lateinit var boxViaggio: LinearLayout
    private var firmaViaggio = ""
    private lateinit var immagineVettura: ImageView
    private lateinit var testoFoto: TextView
    private lateinit var tastoTogliFoto: TextView
    private var versionePorte = -1L
    private var firmaFoto = ""
    private lateinit var schedaStorico: View
    private lateinit var boxStorico: LinearLayout
    private var firmaStorico = ""
    private var storicoTutto = false
    private lateinit var schedaRifornimenti: View
    private lateinit var boxRifornimenti: LinearLayout
    private var firmaRifornimenti = ""
    private var rifornimentiTutti = false

    // pagine dell'auto
    private class Riquadro(val esito: TextView, val immagine: ImageView, var epoch: Long = -1)
    private val riquadri = HashMap<String, Riquadro>()

    // impostazioni
    private val chipRaggio = HashMap<Int, TextView>()
    private lateinit var campoLocali: EditText
    private lateinit var esitoLocali: TextView
    private lateinit var campoRiserva: EditText
    private lateinit var esitoRiserva: TextView
    private lateinit var chipPosizione: TextView
    private lateinit var chipPioggiaSi: TextView
    private lateinit var chipPioggiaNo: TextView
    private val chipPrepara = LinkedHashMap<String, TextView>()

    // permessi
    private class Permesso(val tasto: TextView)
    private val permessi = HashMap<String, Permesso>()
    private var permessiMancanti = 0

    // home a icone: ogni sezione e' una o piu' schede, aperte a tutto schermo toccando la sua icona
    private class Sezione(
        val chiave: String,
        val icona: Int,
        val titolo: String,
        val schede: List<View>,
        val visibile: () -> Boolean = { true },
        val stato: () -> Dettaglio
    )
    /** cosa mostra l'icona della home: una riga in evidenza, una o due righe sotto, l'icona del meteo se c'e' */
    private class Dettaglio(val grande: String, val piccolo: String = "", val meteo: String? = null)
    private val sezioni = ArrayList<Sezione>()
    private class Tessera(val vista: View, val icona: ImageView, val grande: TextView, val piccolo: TextView, var meteo: String? = null)
    private val tessere = HashMap<String, Tessera>()
    private lateinit var scorri: ScrollView
    private lateinit var home: LinearLayout
    private lateinit var dettaglio: LinearLayout
    private lateinit var griglia: LinearLayout
    private var firmaGriglia = ""
    private var aperta: Sezione? = null
    private lateinit var fotoCopertina: ImageView
    private lateinit var vuotoCopertina: TextView
    private lateinit var nomeCopertina: TextView
    private lateinit var statoCopertina: TextView
    private class Cella(val valore: TextView, val etichetta: TextView)
    private val celleCopertina = ArrayList<Cella>()
    private lateinit var notaCopertina: TextView
    private var firmaCopertina = ""
    private var indietro: Any? = null          // OnBackInvokedCallback (Android 13+) mentre una sezione e' aperta

    private val ripeti = object : Runnable {
        override fun run() {
            aggiornaSchermo()
            mano.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        @Suppress("DEPRECATION")
        window.statusBarColor = SFONDO
        @Suppress("DEPRECATION")
        window.navigationBarColor = SFONDO
        // home: intestazione, foto dell'auto e griglia di icone; ogni icona apre la sua schermata (dettaglio)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(20), px(16), px(28))
        }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dettaglio = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(home)
        col.addView(dettaglio)
        home.addView(intestazione())
        home.addView(copertina())
        griglia = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(griglia, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(4) })

        // ---- stato del collegamento
        val schedaInAuto = scheda(R.drawable.ic_auto, "In auto") { s ->
            val (p0, t0) = rigaStato(s, "Auto"); puntoAuto = p0; testoAuto = t0
            // Modifica compare solo quando un'auto si e' collegata almeno una volta: prima non c'e' niente da modificare
            tastoModificaAuto = TextView(this).apply {
                text = "Modifica"; setTextColor(ACCENTO); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                visibility = View.GONE
            }
            (t0.parent as LinearLayout).apply {
                addView(tastoModificaAuto)
                setOnClickListener { Parco.ultima(this@MainActivity)?.let { modificaAuto(it) } }
                isClickable = false
            }
            val (p1, t1) = rigaStato(s, "Servizio"); puntoServizio = p1; testoServizio = t1
            val (p2, t2) = rigaStato(s, "Wi-Fi della ${Marca.AUTO}"); puntoWifi = p2; testoWifi = t2
            val (p3, t3) = rigaStato(s, "iDrive"); puntoIdrive = p3; testoIdrive = t3
            testoBt = piccolo(s, "")
            testoInCorso = piccolo(s, "").apply { setTextColor(ACCENTO) }
            val tasti = rigaTasti(s)
            tastoAccendi = tasto(tasti, "Accendi", true) {
                if (Stato.servizioAttivo) PonteService.ferma(this) else PonteService.avvia(this, "a mano")
            }
            tasto(tasti, "Aggiorna tutto", false) { PonteService.aggiornaOra(this) }
        }

        // ---- viaggio in corso: compare solo mentre sei in auto (dopo resta nel riquadro del parcheggio)
        schedaViaggio = scheda(R.drawable.ic_auto, "In viaggio") { s ->
            boxViaggio = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        }

        // ---- Stato vettura: la pagina "Porte, finestrini e tetto" che vede l'iDrive, con la foto dell'auto e i suoi punti
        val schedaVettura = scheda(R.drawable.ic_auto, "Stato vettura", "Porte, finestrini e tetto nell'iDrive") { s ->
            immagineVettura = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = tondo(Color.rgb(24, 66, 84), 10)
                clipToOutline = true
                visibility = View.GONE
            }
            s.addView(immagineVettura, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            testoFoto = piccolo(s, "")
            val tasti = rigaTasti(s)
            tasto(tasti, "Scegli foto", false) { scegliFoto() }
            tasto(tasti, "Sistema i punti", false) { calibraPunti(Parco.ultima(this).orEmpty()) }
            tastoTogliFoto = tasto(tasti, "Togli foto", false) {
                Foto.togliAvatar(this, Parco.ultima(this).orEmpty())
                aggiornaSchermo()
            }
        }

        // ---- parcheggio
        val schedaParcheggio = scheda(R.drawable.ic_parcheggio, "Dove ho parcheggiato") { s ->
            boxParcheggi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        }

        // ---- storico dei viaggi e rifornimenti: compaiono dal primo viaggio finito e dal primo rifornimento
        schedaStorico = scheda(R.drawable.ic_storico, "Storico viaggi", "Ogni viaggio finito, coi dati dell'iDrive") { s ->
            boxStorico = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        }
        schedaRifornimenti = scheda(R.drawable.ic_carburante, "Rifornimenti", "Riconosciuti dal livello del serbatoio") { s ->
            boxRifornimenti = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        }

        // ---- parco vetture: compare solo quando il telefono ha visto piu' di un'auto
        schedaParco = scheda(R.drawable.ic_auto, "Le mie auto", "Ognuna col suo nome, carburante e parcheggio") { s ->
            boxParco = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
            val tasti = rigaTasti(s)
            tasto(tasti, "Aggiungi dal Bluetooth", false) { aggiungiDaBluetooth() }
        }

        // ---- pagine che vede l'iDrive
        val pagine = listOf(
            Triple("Meteo", R.drawable.ic_meteo, "Dove sei, con le allerte della Protezione Civile"),
            Triple("Notizie", R.drawable.ic_notizie, "ANSA, le prime 8 notizie con foto"),
            Triple("Notizie locali", R.drawable.ic_locali, "ANSA della regione della città scelta"),
            Triple("Carburanti", R.drawable.ic_carburante, "Prezzi ufficiali MIMIT vicino a te"),
            Triple("Traffico", R.drawable.ic_traffico, "Autostrade per l'Italia vicino a te"),
            Triple("Stato vettura · Riepilogo", R.drawable.ic_auto, "Autonomia, km, viaggio e temperature dall'iDrive")
        )
        val schedePagine = HashMap<String, View>()
        for ((nome, icona, sotto) in pagine) {
            schedePagine[nome] = scheda(icona, nome, sotto) { s ->
                val esito = piccolo(s, "")
                val img = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(24, 70, 90), Color.rgb(40, 105, 125))
                    ).apply { cornerRadius = px(10).toFloat() }
                    clipToOutline = true
                    visibility = View.GONE
                }
                s.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) })
                riquadri[nome] = Riquadro(esito, img)
            }
        }

        // ---- impostazioni
        val prefs = getSharedPreferences("ponte", MODE_PRIVATE)
        val schedaImpostazioni = scheda(R.drawable.ic_impostazioni, "Impostazioni") { s ->
            etichetta(s, "Distributori entro")
            val r2 = rigaChip(s)
            for (km in listOf(3, 5, 10, 20)) chipRaggio[km] = chip(r2, "$km km") {
                prefs.edit().putInt("raggio_carburanti", km).apply(); aggiornaChip(); PonteService.aggiornaOra(this)
            }

            etichetta(s, "Città per le notizie locali")
            campoLocali = campo(s, prefs.getString("locali_citta", "La Spezia") ?: "")
            val t1 = rigaTasti(s)
            tasto(t1, "Salva città", false) { salvaCitta(campoLocali, true) }
            esitoLocali = piccolo(s, "Notiziario: ANSA " + (prefs.getString("locali_regione", "Liguria") ?: ""))

            etichetta(s, "Meteo, carburanti e traffico")
            val r3 = rigaChip(s)
            chipPosizione = chip(r3, "") {
                prefs.edit().putBoolean("usa_posizione", !prefs.getBoolean("usa_posizione", true)).apply(); aggiornaChip()
            }
            etichetta(s, "Città di riserva (se la posizione non c'è)")
            campoRiserva = campo(s, prefs.getString("citta", "La Spezia") ?: "")
            val t2 = rigaTasti(s)
            tasto(t2, "Salva città", false) { salvaCitta(campoRiserva, false) }
            esitoRiserva = piccolo(s, "")

            etichetta(s, "Prepara le pagine anche lontano dall'auto")
            val r5 = rigaChip(s)
            for ((modo, nome) in listOf("si" to "Sì", "wifi" to "Solo Wi-Fi", "no" to "No")) chipPrepara[modo] = chip(r5, nome) {
                PreparaJob.imposta(this, modo); aggiornaChip()
            }
            piccolo(s, "Circa ogni ora meteo, notizie, carburanti e traffico si aggiornano da soli, così quando sali " +
                "in auto l'iDrive li ha già pronti. Con Sì usa anche i dati mobili (qualche MB all'ora).")

            etichetta(s, "Avviso pioggia con l'auto aperta")
            val r4 = rigaChip(s)
            chipPioggiaSi = chip(r4, "Sì") { Pioggia.imposta(this, true); aggiornaChip() }
            chipPioggiaNo = chip(r4, "No") { Pioggia.imposta(this, false); aggiornaChip() }
            piccolo(s, "Se scendi lasciando aperti finestrini, tetto, portellone o cofano e dove hai parcheggiato " +
                "sta per piovere, ti avvisa.")
        }

        // ---- permessi
        val schedaPermessi = scheda(R.drawable.ic_permessi, "Permessi") { s ->
            permesso(s, "bt", "Bluetooth", "per accendersi quando sali in auto") {
                chiedi(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }
            permesso(s, "notifiche", "Notifiche", "parcheggio e stato del collegamento") {
                chiedi(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            permesso(s, "batteria", "Batteria senza restrizioni", "per non essere fermata da Android") { chiediBatteria() }
            permesso(s, "posizione", "Posizione precisa", "meteo, carburanti, traffico e parcheggio") {
                chiedi(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            permesso(s, "sempre", "Posizione anche in auto", "scegli \"Consenti sempre\"") {
                if (!Posizione.permesso(this)) chiedi(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else chiedi(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }

        // ---- le icone della home, sempre in quest'ordine; viaggio, storico, rifornimenti e parco compaiono solo quando servono
        val pag = { n: String -> listOf(schedePagine.getValue(n)) }
        sezioni += Sezione("auto", R.drawable.ic_auto, "In auto", listOf(schedaInAuto)) { dettaglioInAuto() }
        sezioni += Sezione("viaggio", R.drawable.ic_auto, "In viaggio", listOf(schedaViaggio), { schedaViaggio.visibility != View.GONE }) {
            dettaglioViaggio()
        }
        sezioni += Sezione("vettura", R.drawable.ic_auto, "Stato vettura", listOf(schedaVettura) + pag("Stato vettura · Riepilogo")) {
            dettaglioVettura()
        }
        sezioni += Sezione("parcheggio", R.drawable.ic_parcheggio, "Parcheggio", listOf(schedaParcheggio)) { dettaglioParcheggio() }
        sezioni += Sezione("storico", R.drawable.ic_storico, "Viaggi", listOf(schedaStorico)) { dettaglioStorico() }
        sezioni += Sezione("rifornimenti", R.drawable.ic_carburante, "Rifornimenti", listOf(schedaRifornimenti)) { dettaglioRifornimenti() }
        sezioni += Sezione("parco", R.drawable.ic_auto, "Le mie auto", listOf(schedaParco), { schedaParco.visibility != View.GONE }) {
            Dettaglio("${Parco.elenco(this).size} auto", "in uso: " + Parco.mostra(this, Parco.ultima(this)))
        }
        sezioni += Sezione("meteo", R.drawable.ic_meteo, "Meteo", pag("Meteo")) { dettaglioMeteo() }
        sezioni += Sezione("notizie", R.drawable.ic_notizie, "Notizie", pag("Notizie")) {
            dettaglioNotizie(Stato.notizie, "ANSA", "le prime 8 notizie d'Italia")
        }
        sezioni += Sezione("locali", R.drawable.ic_locali, "Notizie locali", pag("Notizie locali")) {
            val p = getSharedPreferences("ponte", MODE_PRIVATE)
            dettaglioNotizie(Stato.locali, p.getString("locali_citta", "La Spezia") ?: "", "ANSA " + (p.getString("locali_regione", "") ?: ""))
        }
        sezioni += Sezione("carburanti", R.drawable.ic_carburante, "Carburanti", pag("Carburanti")) {
            Stato.carburantiBreve?.let { Dettaglio(it.first, it.second) }
                ?: Dettaglio(Fonti.CARBURANTI[Parco.carburante(this, Parco.ultima(this))] ?: "Carburanti", "prezzi MIMIT vicino a te")
        }
        sezioni += Sezione("traffico", R.drawable.ic_traffico, "Traffico", pag("Traffico")) {
            Stato.trafficoBreve?.let { Dettaglio(it.first, it.second) } ?: Dettaglio("Traffico", "Autostrade per l'Italia vicino a te")
        }
        sezioni += Sezione("impostazioni", R.drawable.ic_impostazioni, "Impostazioni", listOf(schedaImpostazioni)) {
            val p = getSharedPreferences("ponte", MODE_PRIVATE)
            Dettaglio("Distributori entro ${p.getInt("raggio_carburanti", 5)} km", "Notizie locali: " + (p.getString("locali_citta", "La Spezia") ?: ""))
        }
        sezioni += Sezione("permessi", R.drawable.ic_permessi, "Permessi", listOf(schedaPermessi)) {
            Dettaglio(if (permessiMancanti == 0) "Tutti concessi" else "$permessiMancanti da concedere", "Bluetooth, notifiche, batteria, posizione")
        }

        home.addView(TextView(this).apply {
            text = "${Marca.APP} ${Stato.VERSIONE} · dati: open-meteo.com, MET Norway, ANSA, MIMIT, Autostrade per l'Italia, Protezione Civile"
            setTextColor(TENUE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(14), 0, 0)
        })

        scorri = ScrollView(this).apply {
            setBackgroundColor(SFONDO)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // il contenuto scorre solo nello spazio tra le barre di sistema: sotto la barra di stato (orologio e icone)
            // e sopra quella di navigazione resta il fondo dell'app, senza scritte che ci passano sotto
            clipToPadding = true
            addView(col, MATCH_PARENT, WRAP_CONTENT)
        }
        // da Android 15 l'app disegna anche sotto la barra di stato e quella di navigazione:
        // il contenuto si sposta di quanto sono alte, cosi' niente finisce sotto le barre
        scorri.setOnApplyWindowInsetsListener { v, insets ->
            val barre = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            else null
            @Suppress("DEPRECATION")
            val su = barre?.top ?: insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val giu = barre?.bottom ?: insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            val sx = barre?.left ?: insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            val dx = barre?.right ?: insets.systemWindowInsetRight
            v.setPadding(sx, su, dx, giu)
            insets
        }
        setContentView(scorri)
        aggiornaChip()
        // le ultime pagine salvate (anche preparate lontano dall'auto) e il lavoro di ogni ora che le tiene fresche
        Thread({ Cache.carica(this) }, "cache").start()
        PreparaJob.programma(this)
    }

    override fun onResume() {
        super.onResume()
        mano.post(ripeti)
    }

    override fun onPause() {
        mano.removeCallbacks(ripeti)
        super.onPause()
    }

    @Deprecated("Activity semplice, senza AndroidX")
    override fun onBackPressed() {
        if (aperta != null) chiudiSezione()
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ============================================================================ home a icone ====
    /** foto dell'auto in alto (quella di serie nella MINI Plus, altrimenti la foto scelta), col nome e lo stato */
    private fun copertina(): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(30, 36, 44), SCHEDA)
            ).apply { cornerRadius = px(18).toFloat(); setStroke(px(1), BORDO) }
            setPadding(px(14), px(14), px(14), px(14))
            isClickable = true
            setOnClickListener { apriSezione("vettura") }
        }
        fotoCopertina = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        c.addView(fotoCopertina, LinearLayout.LayoutParams(MATCH_PARENT, px(170)))
        vuotoCopertina = TextView(this).apply {
            text = "Tocca qui per mettere la foto della tua auto"
            setTextColor(TENUE); textSize = 14f; gravity = Gravity.CENTER
            background = tondo(Color.rgb(16, 19, 23), 14, BORDO)
            visibility = View.GONE
        }
        c.addView(vuotoCopertina, LinearLayout.LayoutParams(MATCH_PARENT, px(110)))
        nomeCopertina = TextView(this).apply {
            setTextColor(TESTO); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(2), px(10), 0, 0)
        }
        c.addView(nomeCopertina)
        statoCopertina = TextView(this).apply { setTextColor(TENUE); textSize = 13f; setPadding(px(2), px(2), 0, 0) }
        c.addView(statoCopertina)
        // carburante, autonomia, chilometri e consumo: in diretta dall'iDrive, altrimenti com'era l'auto al parcheggio
        // due per riga: sul telefono quattro in fila non ci stanno ("48.213 km")
        var valori = LinearLayout(this)
        for ((k, nome) in listOf("Carburante", "Autonomia", "Chilometri", "Consumo").withIndex()) {
            if (k % 2 == 0) {
                valori = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(if (k == 0) 12 else 8), 0, 0) }
                c.addView(valori)
            }
            val cella = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = tondo(Color.rgb(16, 19, 23), 12, BORDO)
                setPadding(px(10), px(8), px(6), px(8))
            }
            val v = TextView(this).apply { setTextColor(TESTO); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }
            val e = TextView(this).apply { text = nome; setTextColor(TENUE); textSize = 11f; maxLines = 1 }
            cella.addView(v); cella.addView(e)
            valori.addView(cella, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (valori.childCount > 0) marginStart = px(8) })
            celleCopertina += Cella(v, e)
        }
        notaCopertina = TextView(this).apply { setTextColor(TENUE); textSize = 11f; setPadding(px(2), px(6), 0, 0) }
        c.addView(notaCopertina)
        return c
    }

    /** icona della griglia (due per riga): simbolo nel colore della marca e nome, poi una riga in evidenza e due sotto */
    private fun tessera(z: Sezione): Tessera {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tondo(SCHEDA, 16, BORDO)
            setPadding(px(12), px(12), px(12), px(14))
            isClickable = true
            setOnClickListener { apriSezione(z.chiave) }
        }
        val testa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fondo = FrameLayout(this).apply { background = tondo(Color.rgb(34, 40, 48), 12) }
        val ic = ImageView(this).apply {
            setImageResource(z.icona)
            imageTintList = ColorStateList.valueOf(ACCENTO)
        }
        fondo.addView(ic, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
        testa.addView(fondo, LinearLayout.LayoutParams(px(40), px(40)))
        testa.addView(TextView(this).apply {
            text = z.titolo; setTextColor(TENUE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(px(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        t.addView(testa)
        val grande = TextView(this).apply {
            setTextColor(TESTO); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, px(10), 0, 0)
        }
        t.addView(grande)
        val piccolo = TextView(this).apply {
            setTextColor(TENUE); textSize = 12.5f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, px(2), 0, 0)
        }
        t.addView(piccolo)
        return Tessera(t, ic, grande, piccolo)
    }

    /** icona del meteo (la stessa delle pagine dell'iDrive), dagli assets */
    private fun iconaMeteo(nome: String): android.graphics.Bitmap? = try {
        assets.open("meteo/$nome.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** la griglia si ricompone solo quando compare o sparisce un'icona (3 per riga, sempre nello stesso ordine) */
    private fun aggiornaHome() {
        val visibili = sezioni.filter { it.visibile() }
        val firma = visibili.joinToString(",") { it.chiave }
        if (firma != firmaGriglia) {
            firmaGriglia = firma
            griglia.removeAllViews()
            tessere.clear()
            visibili.chunked(2).forEach { gruppo ->
                val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0 until 2) {
                    val lp = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = px(10) }
                    val z = gruppo.getOrNull(k)
                    if (z == null) { riga.addView(View(this), lp); continue }
                    val t = tessera(z)
                    tessere[z.chiave] = t
                    riga.addView(t.vista, lp)
                }
                griglia.addView(riga, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) })
            }
        }
        for (z in visibili) {
            val t = tessere[z.chiave] ?: continue
            val d = z.stato()
            if (t.grande.text.toString() != d.grande) t.grande.text = d.grande
            if (t.piccolo.text.toString() != d.piccolo) t.piccolo.text = d.piccolo
            if (d.meteo != t.meteo) {
                t.meteo = d.meteo
                val b = d.meteo?.let { iconaMeteo(it) }
                if (b != null) { t.icona.imageTintList = null; t.icona.setImageBitmap(b) }
                else { t.icona.setImageResource(z.icona); t.icona.imageTintList = ColorStateList.valueOf(ACCENTO) }
            }
        }

        // copertina: la foto si ridisegna solo quando cambia
        val bt = Parco.ultima(this)
        val firma2 = "$bt/${Foto.firma(this, bt)}"
        if (firma2 != firmaCopertina) {
            firmaCopertina = firma2
            val foto = Foto.immagine(this, bt)
            fotoCopertina.setImageBitmap(foto)
            fotoCopertina.visibility = if (foto != null) View.VISIBLE else View.GONE
            vuotoCopertina.visibility = if (foto != null) View.GONE else View.VISIBLE
        }
        nomeCopertina.text = bt?.let { Parco.mostra(this, it) } ?: Marca.APP
        val recente = Stato.ultimaRichiestaAuto != 0L && System.currentTimeMillis() - Stato.ultimaRichiestaAuto < 2 * 60 * 1000L
        statoCopertina.text = listOf(
            if (Stato.servizioAttivo) "Acceso" else "Spento",
            if (recente) "iDrive collegato" else if (Stato.servizioAttivo) "in attesa dell'iDrive" else "si accende col Bluetooth",
            statoVettura()
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        val dati = datiAuto(bt)
        val i = dati?.first
        val testi = listOf(
            i?.litri?.let { String.format(Locale.ITALY, "%.0f l", it) },
            i?.autonomia?.let { String.format(Locale.ITALY, "%.0f km", it) },
            i?.km?.let { String.format(Locale.ITALY, "%,.0f km", it) },
            i?.kmLitro?.let { String.format(Locale.ITALY, "%.1f km/l", it) }
        )
        celleCopertina.forEachIndexed { k, c -> (testi[k] ?: "–").let { if (c.valore.text.toString() != it) c.valore.text = it } }
        notaCopertina.text = when {
            dati == null -> "I dati arrivano dall'iDrive quando sei in auto con l'app accesa."
            dati.second == 0L -> "Dati dell'iDrive in diretta"
            else -> "Dati dell'iDrive al parcheggio, ${quandoBreve(dati.second)}"
        }
    }

    /** gli ultimi valori dell'auto: in diretta (0) o quelli salvati alla fine dell'ultimo viaggio (con l'ora) */
    private fun datiAuto(bt: String?): Pair<DatiAuto.Istantanea, Long>? {
        val mostra = PartiVisibili.mostrate(this, bt)
        if (DatiAuto.grezzi.isNotEmpty()) return DatiAuto.istantanea(DatiAuto.grezzi, mostra) to 0L
        val v = Viaggio.di(this, bt) ?: return null
        if (v.grezzi.isEmpty()) return null
        // al parcheggio le porte non contano: l'ultimo stato arriva quando scendi, con la tua porta aperta, e
        // l'iDrive si spegne prima che tu la chiuda
        return DatiAuto.istantanea(v.grezzi, mostra - DatiAuto.PORTE_PARTI) to v.aggiornato
    }

    /** "oggi 17:06", "ieri 17:06", "lun 02/11 17:06" */
    private fun quandoBreve(ms: Long): String {
        val oggi = inizioGiorno(System.currentTimeMillis())
        val ora = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(ms))
        return when {
            ms >= oggi -> "oggi $ora"
            ms >= oggi - 86400000L -> "ieri $ora"
            else -> SimpleDateFormat("EEE dd/MM", Locale.ITALY).format(Date(ms)) + " $ora"
        }
    }

    private fun inizioGiorno(ms: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- cosa mostra ogni icona della home
    private fun dettaglioInAuto(): Dettaglio {
        val recente = Stato.ultimaRichiestaAuto != 0L && System.currentTimeMillis() - Stato.ultimaRichiestaAuto < 2 * 60 * 1000L
        return Dettaglio(
            if (Stato.servizioAttivo) "Acceso" else "Spento",
            when {
                recente -> "iDrive collegato"
                Stato.servizioAttivo -> "in attesa dell'iDrive"
                else -> "si accende col Bluetooth della ${Marca.AUTO}"
            }
        )
    }

    private fun dettaglioViaggio(): Dettaglio {
        val v = Viaggio.di(this, Parco.ultima(this))?.takeIf { it.inCorso } ?: return Dettaglio("In corso")
        return Dettaglio(
            listOfNotNull(durata(v.durata()), v.km()?.let { km(it) }).joinToString(" · "),
            if (v.luogo.isNotEmpty()) "da ${v.luogo}" else "partito alle " + SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(v.partenza))
        )
    }

    private fun dettaglioVettura(): Dettaglio {
        val dati = datiAuto(Parco.ultima(this))
        val aperti = dati?.first?.aperti ?: return Dettaglio("Porte e finestrini", "lo stato arriva dall'iDrive quando sei in auto")
        val quando = if (dati.second == 0L) "in diretta" else "al parcheggio, ${quandoBreve(dati.second)}, porte escluse"
        return if (aperti.isEmpty()) Dettaglio("Tutto chiuso", "Porte, finestrini e tetto · $quando")
        else Dettaglio(if (aperti.size == 1) "1 apertura" else "${aperti.size} aperture", aperti.joinToString(", ") + " · $quando")
    }

    private fun dettaglioParcheggio(): Dettaglio {
        val p = Parcheggio.tutti(this).firstOrNull() ?: return Dettaglio("Nessuno", "si salva da solo quando scendi ${Marca.dalla()}")
        val dove = p.indirizzo.ifEmpty { String.format(Locale.ITALY, "%.5f, %.5f", p.lat, p.lon) }
        val via = dove.substringBefore(',').trim()
        val resto = dove.substringAfter(',', "").trim()
        return Dettaglio(via, listOf(resto, quandoBreve(p.quando)).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    private fun dettaglioStorico(): Dettaglio {
        val voci = Storico.tutti(this)
        val ultimo = voci.firstOrNull() ?: return Dettaglio("Nessun viaggio", "si registrano da soli quando scendi ${Marca.dalla()}")
        val mese = voci.filter { it.partenza >= inizioMese() }
        val kmMese = mese.mapNotNull { it.km }
        val grande = (if (mese.size == 1) "1 viaggio" else "${mese.size} viaggi") + " questo mese" +
            if (kmMese.isNotEmpty()) " · " + km(kmMese.sum()) else ""
        val dove = if (ultimo.a.isNotEmpty()) " → " + ultimo.a.substringBefore(',') else ""
        return Dettaglio(grande, "Ultimo: ${quandoBreve(ultimo.partenza)}" + (ultimo.km?.let { ", " + km(it) } ?: "") + dove)
    }

    private fun dettaglioRifornimenti(): Dettaglio {
        val r = Rifornimenti.tutti(this).firstOrNull() ?: return Dettaglio("Nessuno", "riconosciuti dal livello del serbatoio")
        return Dettaglio(
            String.format(Locale.ITALY, "%.0f litri", r.litri),
            listOf(quandoBreve(r.quando), r.marchio.ifEmpty { r.luogo.substringBefore(',') },
                r.prezzo?.let { String.format(Locale.ITALY, "%.3f €/l", it) } ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        )
    }

    private fun dettaglioMeteo(): Dettaglio {
        val m = Stato.meteoBreve ?: return Dettaglio("Meteo", if (Stato.servizioAttivo) "in aggiornamento" else "si aggiorna quando l'app è accesa")
        val allerta = Stato.allerta.takeIf { it.startsWith("Allerta") }
        return Dettaglio("${m.temp}° · ${m.cielo}", allerta ?: listOf(m.citta, quando(Stato.meteo.esito)).filter { it.isNotEmpty() }.joinToString(" · "), m.icona)
    }

    private fun dettaglioNotizie(e: Elenco, titolo: String, vuoto: String): Dettaglio {
        val n = e.notizie.firstOrNull() ?: return Dettaglio(titolo, vuoto)
        return Dettaglio(titolo + if (n.ora.isNotEmpty()) " · ${n.ora}" else "", n.titolo)
    }

    /** apre a tutto schermo le schede di una sezione; Indietro (o il gesto) torna alla home */
    internal fun apriSezione(chiave: String) {
        val z = sezioni.firstOrNull { it.chiave == chiave } ?: return
        aperta = z
        dettaglio.removeAllViews()
        val su = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2), 0, px(4))
        }
        su.addView(TextView(this).apply {
            text = "‹  Home"; setTextColor(TESTO); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = tondo(Color.TRANSPARENT, 20, Color.rgb(90, 98, 110))
            setPadding(px(16), px(9), px(18), px(9))
            isClickable = true
            setOnClickListener { chiudiSezione() }
        })
        dettaglio.addView(su)
        for (v in z.schede) {
            (v.parent as? ViewGroup)?.removeView(v)
            dettaglio.addView(v)
        }
        home.visibility = View.GONE
        dettaglio.visibility = View.VISIBLE
        scorri.scrollTo(0, 0)
        if (Build.VERSION.SDK_INT >= 33 && indietro == null) {
            val cb = android.window.OnBackInvokedCallback { chiudiSezione() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            indietro = cb
        }
        aggiornaSchermo()
    }

    private fun chiudiSezione() {
        aperta = null
        dettaglio.removeAllViews()
        dettaglio.visibility = View.GONE
        home.visibility = View.VISIBLE
        scorri.scrollTo(0, 0)
        if (Build.VERSION.SDK_INT >= 33) {
            (indietro as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        indietro = null
        aggiornaSchermo()
    }

    /** "tutto chiuso" o "2 aperture" dagli ultimi dati dell'iDrive; vuoto se non ne sono ancora arrivati */
    private fun statoVettura(): String {
        val n = datiAuto(Parco.ultima(this))?.first?.aperti?.size ?: return ""
        return if (n == 0) "tutto chiuso" else if (n == 1) "1 apertura" else "$n aperture"
    }

    /** "alle 14:30" dall'esito di una pagina ("OK alle 14:30 - ..."), "errore" se l'ultimo giro e' andato male */
    private fun quando(esito: String): String = when {
        esito.startsWith("ERRORE") -> "errore"
        else -> Regex("alle (\\d{1,2}:\\d{2})").find(esito)?.let { "alle " + it.groupValues[1] } ?: ""
    }

    // ============================================================================ costruzione ====
    private fun px(v: Int) = (v * dp).toInt()

    private fun tondo(colore: Int, raggio: Int, bordo: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(colore)
            cornerRadius = px(raggio).toFloat()
            if (bordo != 0) setStroke(px(1), bordo)
        }

    private fun intestazione(): View {
        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(4), px(4), px(14))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = tondo(Color.BLACK, 16, BORDO)
            clipToOutline = true
        }
        riga.addView(logo, LinearLayout.LayoutParams(px(64), px(64)))
        val testi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
        testi.addView(TextView(this).apply {
            text = Marca.APP; setTextColor(TESTO); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        })
        testi.addView(TextView(this).apply {
            text = "Meteo, notizie, carburanti, traffico e stato vettura nell'iDrive · v${Stato.VERSIONE}"
            setTextColor(TENUE); textSize = 13f
        })
        riga.addView(testi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return riga
    }

    private fun scheda(icona: Int, titolo: String, sottotitolo: String = "", contenuto: (LinearLayout) -> Unit): View {
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tondo(SCHEDA, 16, BORDO)
            setPadding(px(16), px(14), px(16), px(16))
        }
        val testa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ic = ImageView(this).apply {
            setImageResource(icona)
            imageTintList = ColorStateList.valueOf(ACCENTO)
        }
        testa.addView(ic, LinearLayout.LayoutParams(px(22), px(22)))
        val tt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), 0, 0, 0) }
        tt.addView(TextView(this).apply { text = titolo; setTextColor(TESTO); textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
        if (sottotitolo.isNotEmpty()) tt.addView(TextView(this).apply { text = sottotitolo; setTextColor(TENUE); textSize = 12f })
        testa.addView(tt)
        s.addView(testa)
        s.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, px(8)))
        contenuto(s)
        val fuori = FrameLayout(this)
        fuori.addView(s)
        fuori.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(12) }
        return fuori
    }

    private fun rigaStato(s: ViewGroup, etichetta: String): Pair<View, TextView> {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4), 0, px(4))
        }
        val punto = View(this).apply { background = tondo(TENUE, 6) }
        r.addView(punto, LinearLayout.LayoutParams(px(12), px(12)))
        r.addView(
            TextView(this).apply { text = etichetta; setTextColor(TENUE); textSize = 14f; setPadding(px(10), 0, 0, 0) },
            LinearLayout.LayoutParams(px(140), WRAP_CONTENT)
        )
        val v = TextView(this).apply { setTextColor(TESTO); textSize = 14f }
        r.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        s.addView(r)
        return punto to v
    }

    private fun testo(s: ViewGroup, t: String) = TextView(this).apply {
        text = t; setTextColor(TESTO); textSize = 15f; setLineSpacing(0f, 1.15f)
    }.also { s.addView(it) }

    private fun piccolo(s: ViewGroup, t: String) = TextView(this).apply {
        text = t; setTextColor(TENUE); textSize = 13f; setPadding(0, px(4), 0, 0)
    }.also { s.addView(it) }

    private fun etichetta(s: ViewGroup, t: String) = TextView(this).apply {
        text = t; setTextColor(TENUE); textSize = 13f; setPadding(0, px(12), 0, px(6))
    }.also { s.addView(it) }

    private fun rigaTasti(s: ViewGroup) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(12), 0, 0)
    }.also { s.addView(it) }

    private fun tasto(riga: LinearLayout, t: String, principale: Boolean, azione: () -> Unit): TextView {
        val b = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (principale) SU_ACCENTO else TESTO)
            background = if (principale) tondo(ACCENTO, 22) else tondo(Color.TRANSPARENT, 22, Color.rgb(90, 98, 110))
            setPadding(px(18), px(11), px(18), px(11))
            isClickable = true
            setOnClickListener { azione() }
        }
        riga.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (riga.childCount > 0) marginStart = px(10) })
        return b
    }

    private fun rigaChip(s: ViewGroup) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { s.addView(it) }

    private fun chip(riga: LinearLayout, t: String, azione: () -> Unit): TextView {
        val c = TextView(this).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER
            setPadding(px(8), px(9), px(8), px(9))
            isClickable = true
            setOnClickListener { azione() }
        }
        riga.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (riga.childCount > 0) marginStart = px(8) })
        return c
    }

    private fun scegli(c: TextView, acceso: Boolean) {
        c.setTextColor(if (acceso) SU_ACCENTO else TESTO)
        c.typeface = if (acceso) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.background = if (acceso) tondo(ACCENTO, 18) else tondo(Color.rgb(34, 40, 48), 18, BORDO)
    }

    private fun aggiornaChip() {
        val p = getSharedPreferences("ponte", MODE_PRIVATE)
        val r = p.getInt("raggio_carburanti", 5)
        chipRaggio.forEach { (k, v) -> scegli(v, k == r) }
        val gps = p.getBoolean("usa_posizione", true)
        scegli(chipPosizione, gps)
        chipPosizione.text = if (gps) "Dove sei (GPS del telefono)" else "Sempre la città di riserva"
        scegli(chipPioggiaSi, Pioggia.attivo(this))
        scegli(chipPioggiaNo, !Pioggia.attivo(this))
        val prepara = PreparaJob.modo(this)
        chipPrepara.forEach { (k, v) -> scegli(v, k == prepara) }
    }

    private fun campo(s: ViewGroup, valore: String): EditText {
        val e = EditText(this).apply {
            setText(valore)
            setTextColor(TESTO); setHintTextColor(TENUE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            background = tondo(Color.rgb(16, 19, 23), 12, BORDO)
            setPadding(px(14), px(11), px(14), px(11))
        }
        s.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return e
    }

    private fun permesso(s: ViewGroup, chiave: String, nome: String, perche: String, azione: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(6))
        }
        val tt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tt.addView(TextView(this).apply { text = nome; setTextColor(TESTO); textSize = 15f })
        tt.addView(TextView(this).apply { text = perche; setTextColor(TENUE); textSize = 12f })
        r.addView(tt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val b = TextView(this).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { azione() }
        }
        r.addView(b, LinearLayout.LayoutParams(px(104), WRAP_CONTENT))
        s.addView(r)
        permessi[chiave] = Permesso(b)
    }

    // ============================================================================ stato ====
    private fun punto(v: View, colore: Int) {
        (v.background as GradientDrawable).setColor(colore)
    }

    private fun aggiornaSchermo() {
        val prefs = getSharedPreferences("ponte", MODE_PRIVATE)
        val ora = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

        punto(puntoAuto, ACCENTO)
        val autoNota = Parco.ultima(this)
        testoAuto.text = if (autoNota != null) Parco.mostra(this, autoNota) else "non ancora collegata"
        tastoModificaAuto.visibility = if (autoNota != null) View.VISIBLE else View.GONE
        (tastoModificaAuto.parent as View).isClickable = autoNota != null
        punto(puntoServizio, if (Stato.servizioAttivo) VERDE else TENUE)
        testoServizio.text = if (Stato.servizioAttivo) "acceso (${Parco.nelTesto(this, Stato.avviatoDa)})" else "spento: si accende da solo col Bluetooth della ${Marca.AUTO}"
        tastoAccendi.text = if (Stato.servizioAttivo) "Spegni" else "Accendi"
        val wifi = Stato.wifiAuto
        punto(puntoWifi, if (wifi != null) VERDE else TENUE)
        testoWifi.text = wifi?.let { "collegato ($it)" } ?: "no (serve Android Auto wireless)"
        val recente = System.currentTimeMillis() - Stato.ultimaRichiestaAuto < 2 * 60 * 1000L
        punto(puntoIdrive, if (Stato.ultimaRichiestaAuto == 0L) TENUE else if (recente) VERDE else ARANCIO)
        testoIdrive.text = if (Stato.ultimaRichiestaAuto == 0L) "nessuna richiesta"
        else "ultima richiesta ${ora.format(Date(Stato.ultimaRichiestaAuto))} (${Stato.richiesteAuto} in tutto)"
        testoBt.text = "Bluetooth: " + (prefs.getString("ultimo_bt", null)?.let { Parco.nelTesto(this, it) } ?: "nessun evento ancora") +
            (if (Stato.erroreServer.isNotEmpty()) "\nServer: ${Stato.erroreServer}" else "")
        val inCorso = Stato.aggiornamentoInCorso
        testoInCorso.text = if (inCorso.isNotEmpty()) "Sto aggiornando: $inCorso..." else ""
        testoInCorso.visibility = if (inCorso.isNotEmpty()) View.VISIBLE else View.GONE

        disegnaParcheggi()
        disegnaParco()
        disegnaViaggio()
        disegnaVettura()
        disegnaStorico()
        disegnaRifornimenti()

        mostra("Meteo", Stato.meteo.esito + (if (Stato.allerta.isNotEmpty()) "\nAllerta: ${Stato.allerta}" else ""), Stato.meteo.epoch, Stato.meteo.png)
        mostra("Notizie", Stato.notizie.esito, Stato.notizie.epoch, Stato.notizie.notizie.firstOrNull()?.pagina)
        mostra("Notizie locali", Stato.locali.esito, Stato.locali.epoch, Stato.locali.notizie.firstOrNull()?.pagina)
        mostra("Carburanti", Stato.carburanti.esito, Stato.carburanti.epoch, Stato.carburanti.png)
        mostra("Traffico", Stato.traffico.esito, Stato.traffico.epoch, Stato.traffico.png)
        mostra("Stato vettura · Riepilogo", Stato.auto.esito, Stato.auto.epoch, Stato.auto.png)

        permessiMancanti = 0
        statoPermesso("bt", Build.VERSION.SDK_INT < 31 || ha(Manifest.permission.BLUETOOTH_CONNECT))
        statoPermesso("notifiche", Build.VERSION.SDK_INT < 33 || ha(Manifest.permission.POST_NOTIFICATIONS))
        statoPermesso("batteria", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        statoPermesso("posizione", ha(Manifest.permission.ACCESS_FINE_LOCATION))
        statoPermesso("sempre", Posizione.permessoSempre(this))
        aggiornaHome()
    }

    private fun mostra(nome: String, esito: String, epoch: Long, png: ByteArray?) {
        val r = riquadri[nome] ?: return
        r.esito.text = esito
        r.esito.setTextColor(if (esito.startsWith("ERRORE")) ROSSO else TENUE)
        if (epoch == r.epoch) return
        r.epoch = epoch
        if (png == null) {
            r.immagine.visibility = View.GONE
            return
        }
        r.immagine.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
        r.immagine.visibility = View.VISIBLE
    }

    private fun statoPermesso(chiave: String, ok: Boolean) {
        if (!ok) permessiMancanti++
        val p = permessi[chiave] ?: return
        p.tasto.text = if (ok) "Concesso" else "Consenti"
        p.tasto.setTextColor(if (ok) VERDE else SU_ACCENTO)
        p.tasto.background = if (ok) tondo(Color.TRANSPARENT, 16, VERDE) else tondo(ACCENTO, 16)
        p.tasto.isEnabled = !ok
    }

    private fun ha(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun chiedi(lista: Array<String>) {
        requestPermissions(lista, 1)
    }

    @Suppress("BatteryLife")
    private fun chiediBatteria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** un riquadro per ogni auto parcheggiata; si ridisegna solo quando cambia qualcosa, cosi' non disturba chi tocca */
    private fun disegnaParcheggi() {
        val posti = Parcheggio.tutti(this)
        val firma = posti.joinToString("|") { "${it.auto}/${it.quando}/${Parco.mostra(this, it.auto)}/${Viaggio.di(this, it.auto)?.aggiornato}" }
        if (firma == firmaParcheggi && boxParcheggi.childCount > 0) return
        firmaParcheggi = firma
        boxParcheggi.removeAllViews()
        if (posti.isEmpty()) {
            testo(boxParcheggi, "Ancora nessun parcheggio salvato: si salva da solo quando scendi ${Marca.dalla()}.")
            val tasti = rigaTasti(boxParcheggi)
            tasto(tasti, "Portami ${Marca.alla()}", false) {}.apply { isEnabled = false; setTextColor(TENUE) }
            return
        }
        posti.forEachIndexed { i, p ->
            if (p.auto.isNotEmpty() || posti.size > 1) titoletto(boxParcheggi, Parco.mostra(this, p.auto).ifEmpty { Marca.AUTO }, i > 0)
            testo(boxParcheggi, Parcheggio.descrizione(p))
            // il viaggio finito con questo parcheggio: com'era l'auto quando sei sceso
            Viaggio.di(this, p.auto)?.takeIf { !it.inCorso && kotlin.math.abs(it.arrivo - p.quando) < 10 * 60 * 1000L }?.let { v ->
                boxParcheggi.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, px(6)))
                righeViaggio(boxParcheggi, v)
            }
            val tasti = rigaTasti(boxParcheggi)
            tasto(tasti, "Portami ${Marca.alla(p.auto)}", true) { Parcheggio.portami(this, p) }
        }
    }

    /** l'elenco delle auto con Modifica e Togli, sempre nello stesso ordine; si ridisegna solo quando cambia qualcosa.
     *  Con una sola auto la scheda non c'e': nome e carburante si cambiano da Modifica nella riga Auto */
    private fun disegnaParco() {
        val auto = Parco.elenco(this)
        schedaParco.visibility = if (auto.size > 1) View.VISIBLE else View.GONE
        val attuale = Parco.ultima(this)
        val firma = auto.joinToString("|") { "$it/${Parco.mostra(this, it)}/${Parco.carburante(this, it)}/${Parco.vista(this, it)}" } + "/$attuale"
        if (auto.size < 2 || (firma == firmaParco && boxParco.childCount > 0)) return
        firmaParco = firma
        boxParco.removeAllViews()
        val ora = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)
        auto.forEachIndexed { i, bt ->
            val nome = Parco.mostra(this, bt)
            titoletto(boxParco, nome, i > 0)
            val vista = Parco.vista(this, bt)
            piccolo(boxParco, listOfNotNull(
                if (nome != bt) bt else null,
                Fonti.CARBURANTI[Parco.carburante(this, bt)],
                if (bt == attuale) "in uso adesso" else null,
                if (vista > 0) "ultimo collegamento ${ora.format(Date(vista))}" else null
            ).joinToString(" · "))
            val tasti = rigaTasti(boxParco)
            tasto(tasti, "Modifica", false) { modificaAuto(bt) }
            tasto(tasti, "Togli", false) { togliAuto(bt) }
        }
    }

    /** il viaggio dell'auto attuale mentre e' in corso; si ridisegna quando arrivano dati nuovi o passa un minuto */
    private fun disegnaViaggio() {
        val bt = Parco.ultima(this)
        val v = Viaggio.di(this, bt)?.takeIf { it.inCorso }
        schedaViaggio.visibility = if (v != null) View.VISIBLE else View.GONE
        if (v == null) return
        val firma = "${v.auto}/${v.partenza}/${v.aggiornato}/${v.luogo}/${System.currentTimeMillis() / 60000}/${Parco.mostra(this, bt)}"
        if (firma == firmaViaggio && boxViaggio.childCount > 0) return
        firmaViaggio = firma
        boxViaggio.removeAllViews()
        if (Parco.elenco(this).size > 1) titoletto(boxViaggio, Parco.mostra(this, bt), false)
        righeViaggio(boxViaggio, v)
    }

    // ============================================================================ stato vettura ====
    private val SCEGLI_FOTO = 7

    /** anteprima della pagina Porte (quella che vede l'iDrive) e stato della foto dell'auto attuale */
    private fun disegnaVettura() {
        val bt = Parco.ultima(this).orEmpty()
        val porte = Stato.vettura.notizie.getOrNull(1)
        if (porte != null && porte.versione != versionePorte) {
            versionePorte = porte.versione
            immagineVettura.setImageBitmap(BitmapFactory.decodeByteArray(porte.pagina, 0, porte.pagina.size))
            immagineVettura.visibility = View.VISIBLE
        }
        val firma = "$bt/${Foto.firma(this, bt)}/${Stato.servizioAttivo}"
        if (firma == firmaFoto) return
        firmaFoto = firma
        val avatar = Foto.haAvatar(this, bt)
        testoFoto.text = when {
            avatar -> "Foto: la tua. Sistema i punti perché ogni pallino cada sulla parte giusta dell'auto."
            Foto.haFotoDiSerie(this) -> "Foto: quella di serie della ${Marca.AUTO}. Puoi sceglierne una della tua auto."
            else -> "Nessuna foto: scegline una della tua auto (meglio con lo sfondo trasparente, vista di 3/4 dal lato destro)."
        } + if (porte == null) "\nL'anteprima compare quando l'app è accesa." else ""
        tastoTogliFoto.isEnabled = avatar
        tastoTogliFoto.setTextColor(if (avatar) TESTO else TENUE)
    }

    private fun scegliFoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, SCEGLI_FOTO)
        } catch (e: Exception) {
            testoFoto.text = "Nessuna app per scegliere le foto su questo telefono."
        }
    }

    @Deprecated("Activity semplice, senza AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != SCEGLI_FOTO || resultCode != RESULT_OK || uri == null) return
        val bt = Parco.ultima(this).orEmpty()
        testoFoto.text = "Sto preparando la foto..."
        Thread({
            val errore = try { Foto.impostaAvatar(this, bt, uri) } catch (e: Throwable) { e.javaClass.simpleName }
            runOnUiThread {
                firmaFoto = ""
                aggiornaSchermo()
                if (errore == null) calibraPunti(bt) else testoFoto.text = "Non riesco a usare questa foto: $errore. Provane un'altra."
            }
        }, "foto").start()
    }

    /** sposta gli 11 punti sulla foto: trascina, tocca per spostare il punto scelto, tieni premuto per "non visibile" */
    private fun calibraPunti(bt: String) {
        val foto = Foto.immagine(this, bt)
        if (foto == null) { scegliFoto(); return }
        val (d, s) = finestra(R.drawable.ic_auto, "Sistema i punti")
        piccolo(s, "Scegli una parte qui sotto, poi tocca sulla foto dove si trova (o trascina il suo pallino). " +
            "Tieni premuto un pallino se quella parte è sul lato che la foto non mostra: diventa un anello vuoto. " +
            "Con Mostra / Non mostrare scegli se quella parte compare nell'iDrive e nell'app (gli avvisi la contano sempre).")
        val chips = LinkedHashMap<DatiAuto.Parte, TextView>()
        val spente = PartiVisibili.spente(this, bt).toMutableSet()
        lateinit var vista: CalibraPunti
        lateinit var tastoMostra: TextView
        lateinit var tastoNascondi: TextView
        lateinit var notaParte: TextView
        // parte scelta evidenziata; le parti spente (o che l'auto non ha) piu' tenui; sotto, Mostra / Non mostrare
        val segna = { p: DatiAuto.Parte ->
            chips.forEach { (k, v) ->
                scegli(v, k == p)
                v.alpha = if (k in spente || PartiVisibili.nonSegnalata(this, bt, k)) 0.45f else 1f
            }
            scegli(tastoMostra, p !in spente)
            scegli(tastoNascondi, p in spente)
            notaParte.text = when {
                PartiVisibili.nonSegnalata(this, bt, p) -> "${p.etichetta}: la tua auto non manda questo dato, quindi non compare comunque."
                p in spente -> "${p.etichetta}: non compare nell'iDrive né nell'app."
                else -> "${p.etichetta}: compare nell'iDrive e nell'app."
            }
        }
        vista = CalibraPunti(this, foto, Foto.punti(this, bt), ACCENTO) { p -> segna(p) }
        s.addView(vista, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(10) })
        // le 11 parti, tre per riga, nell'ordine della pagina
        var riga: LinearLayout? = null
        DatiAuto.Parte.values().forEachIndexed { k, p ->
            if (k % 3 == 0) riga = rigaChip(s).apply { setPadding(0, px(8), 0, 0) }
            chips[p] = chip(riga!!, p.etichetta) { vista.scelto = p; segna(p) }.apply { textSize = 12f }
        }
        val ultima = riga!!
        while (ultima.childCount < 3) ultima.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(8) })
        val rigaMostra = rigaChip(s).apply { setPadding(0, px(14), 0, 0) }
        tastoMostra = chip(rigaMostra, "Mostra") { spente.remove(vista.scelto); segna(vista.scelto) }
        tastoNascondi = chip(rigaMostra, "Non mostrare") { spente.add(vista.scelto); segna(vista.scelto) }
        notaParte = TextView(this).apply { setTextColor(TENUE); textSize = 13f; setPadding(0, px(6), 0, 0) }
        s.addView(notaParte)
        segna(vista.scelto)
        val tasti = rigaTasti(s)
        tasto(tasti, "Salva", true) {
            Foto.salvaPunti(this, bt, vista.punti)
            PartiVisibili.salvaSpente(this, bt, spente)
            d.dismiss()
            firmaFoto = ""
            aggiornaSchermo()
        }
        tasto(tasti, "Predefiniti", false) {
            Foto.ripristinaPunti(this, bt)
            PartiVisibili.salvaSpente(this, bt, emptySet())
            d.dismiss()
            calibraPunti(bt)
        }
        tasto(tasti, "Annulla", false) { d.dismiss() }
        mostraFinestra(d, s)
    }

    private fun durata(ms: Long): String {
        val minuti = ms / 60000
        return if (minuti < 60) "$minuti min" else "${minuti / 60} h ${"%02d".format(minuti % 60)} min"
    }

    private fun km(v: Double): String = String.format(Locale.ITALY, if (v < 10) "%.1f km" else "%,.0f km", v)

    private fun separatore(s: ViewGroup) {
        s.addView(View(this).apply { setBackgroundColor(BORDO) }, LinearLayout.LayoutParams(MATCH_PARENT, px(1)).apply {
            topMargin = px(10); bottomMargin = px(8)
        })
    }

    /** una voce di elenco: riga in grassetto, poi le righe di dettaglio (le vuote si saltano) */
    private fun voce(s: ViewGroup, titolo: String, vararg righe: String) {
        s.addView(TextView(this).apply { text = titolo; setTextColor(TESTO); textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
        for (r in righe) if (r.isNotEmpty()) s.addView(TextView(this).apply {
            text = r; setTextColor(TENUE); textSize = 13f; setPadding(0, px(2), 0, 0)
        })
    }

    /** dall'inizio del mese in corso (ms) */
    private fun inizioMese(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** gli ultimi viaggi finiti (5, o tutti), col riassunto del mese; si ridisegna solo quando cambia qualcosa */
    private fun disegnaStorico() {
        val voci = Storico.tutti(this)
        if (voci.isEmpty()) {
            if (firmaStorico != "vuoto") {
                firmaStorico = "vuoto"
                boxStorico.removeAllViews()
                testo(boxStorico, "Ancora nessun viaggio: ogni viaggio si registra da solo quando scendi ${Marca.dalla()} " +
                    "(il telefono si scollega dal Bluetooth), con i km e i litri se l'iDrive li ha mandati.")
            }
            return
        }
        val piuAuto = Parco.elenco(this).size > 1
        val firma = voci.joinToString("|") { "${it.auto}/${it.partenza}/${it.arrivo}/${it.km}/${it.litri}/${it.a}" } +
            "/$storicoTutto/$piuAuto/" + voci.map { it.auto }.distinct().joinToString(",") { Parco.mostra(this, it) }
        if (voci.isEmpty() || (firma == firmaStorico && boxStorico.childCount > 0)) return
        firmaStorico = firma
        boxStorico.removeAllViews()

        val mese = voci.filter { it.partenza >= inizioMese() }
        val kmMese = mese.mapNotNull { it.km }
        val litriMese = mese.mapNotNull { it.litri }
        rigaValore(boxStorico, "Questo mese", listOfNotNull(
            if (mese.size == 1) "1 viaggio" else "${mese.size} viaggi",
            if (kmMese.isNotEmpty()) km(kmMese.sum()) else null,
            if (litriMese.isNotEmpty()) String.format(Locale.ITALY, "%.1f l", litriMese.sum()) else null
        ).joinToString(" · "))

        val giorno = SimpleDateFormat("EEE dd/MM", Locale.ITALY)
        val ora = SimpleDateFormat("HH:mm", Locale.ITALY)
        val mostrati = if (storicoTutto) voci else voci.take(5)
        for (v in mostrati) {
            separatore(boxStorico)
            val titolo = giorno.format(Date(v.partenza)).replaceFirstChar { it.uppercase() } +
                " · ${ora.format(Date(v.partenza))} → ${ora.format(Date(v.arrivo))}" +
                if (piuAuto) " · ${Parco.mostra(this, v.auto).ifEmpty { Marca.AUTO }}" else ""
            val luoghi = if (v.da.isEmpty() && v.a.isEmpty()) "" else "${v.da.ifEmpty { "?" }} → ${v.a.ifEmpty { "?" }}"
            val dettagli = listOfNotNull(
                v.km?.let { km(it) },
                durata(v.durata),
                v.litri?.let { String.format(Locale.ITALY, "%.1f l", it) },
                v.kmLitro()?.let { String.format(Locale.ITALY, "%.1f km/l", it) },
                if (v.rifornito > 0) "con rifornimento" else null
            ).joinToString(" · ")
            voce(boxStorico, titolo, luoghi, dettagli)
        }
        val tasti = rigaTasti(boxStorico)
        tasto(tasti, if (storicoTutto) "Mostra meno" else "Mostra tutti (${voci.size})", false) {
            storicoTutto = !storicoTutto; aggiornaSchermo()
        }.apply { if (voci.size <= 5) { isEnabled = false; setTextColor(TENUE) } }
        tasto(tasti, "Cancella", false) {
            chiediCancella("Cancellare lo storico?", "Si cancellano tutti i viaggi salvati" + (if (piuAuto) " di tutte le auto" else "") + ".") {
                (Parco.elenco(this) + "").distinct().forEach { Storico.cancella(this, it) }
            }
        }
    }

    /** gli ultimi rifornimenti (5, o tutti) con prezzo, spesa stimata e km dal precedente */
    private fun disegnaRifornimenti() {
        val voci = Rifornimenti.tutti(this)
        if (voci.isEmpty()) {
            if (firmaRifornimenti != "vuoto") {
                firmaRifornimenti = "vuoto"
                boxRifornimenti.removeAllViews()
                testo(boxRifornimenti, "Ancora nessun rifornimento: si riconoscono da soli quando il livello del serbatoio " +
                    "che manda l'iDrive sale di qualche litro.")
            }
            return
        }
        val piuAuto = Parco.elenco(this).size > 1
        val firma = voci.joinToString("|") { "${it.auto}/${it.quando}/${it.litri}/${it.luogo}/${it.prezzo}" } +
            "/$rifornimentiTutti/$piuAuto/" + voci.map { it.auto }.distinct().joinToString(",") { Parco.mostra(this, it) }
        if (voci.isEmpty() || (firma == firmaRifornimenti && boxRifornimenti.childCount > 0)) return
        firmaRifornimenti = firma
        boxRifornimenti.removeAllViews()

        val mese = voci.filter { it.quando >= inizioMese() }
        val spese = mese.mapNotNull { it.spesa() }
        rigaValore(boxRifornimenti, "Questo mese", listOfNotNull(
            if (mese.size == 1) "1 rifornimento" else "${mese.size} rifornimenti",
            if (mese.isNotEmpty()) String.format(Locale.ITALY, "%.0f l", mese.sumOf { it.litri }) else null,
            if (spese.isNotEmpty()) String.format(Locale.ITALY, "circa %.0f €", spese.sum()) else null
        ).joinToString(" · "))

        val quando = SimpleDateFormat("EEE dd/MM 'alle' HH:mm", Locale.ITALY)
        val mostrati = if (rifornimentiTutti) voci else voci.take(5)
        for (r in mostrati) {
            separatore(boxRifornimenti)
            val titolo = quando.format(Date(r.quando)).replaceFirstChar { it.uppercase() } +
                String.format(Locale.ITALY, " · %.0f l", r.litri) +
                if (piuAuto) " · ${Parco.mostra(this, r.auto).ifEmpty { Marca.AUTO }}" else ""
            val dove = listOf(r.marchio, r.luogo).filter { it.isNotEmpty() }.joinToString(", ")
                .ifEmpty { if (r.lat == null) "posizione non disponibile" else "" }
            // km dal rifornimento precedente della stessa auto (contachilometri dell'iDrive)
            val precedente = voci.filter { it.auto == r.auto && it.quando < r.quando }.maxByOrNull { it.quando }
            val percorsi = if (r.km != null && precedente?.km != null && r.km > precedente.km) r.km - precedente.km else null
            val dettagli = listOfNotNull(
                r.prezzo?.let { String.format(Locale.ITALY, "%.3f €/l", it) + if (r.self) " self" else "" },
                r.spesa()?.let { String.format(Locale.ITALY, "circa %.0f €", it) },
                percorsi?.let { km(it) + " dal precedente" },
                String.format(Locale.ITALY, "%.0f l nel serbatoio dopo", r.livello)
            ).joinToString(" · ")
            voce(boxRifornimenti, titolo, dove, dettagli)
        }
        piccolo(boxRifornimenti, "Prezzo del distributore più vicino (MIMIT), se il telefono era a meno di 300 m: la spesa è una stima.")
        val tasti = rigaTasti(boxRifornimenti)
        tasto(tasti, if (rifornimentiTutti) "Mostra meno" else "Mostra tutti (${voci.size})", false) {
            rifornimentiTutti = !rifornimentiTutti; aggiornaSchermo()
        }.apply { if (voci.size <= 5) { isEnabled = false; setTextColor(TENUE) } }
        tasto(tasti, "Cancella", false) {
            chiediCancella("Cancellare i rifornimenti?", "Si cancellano tutti i rifornimenti salvati" + (if (piuAuto) " di tutte le auto" else "") + ".") {
                (Parco.elenco(this) + "").distinct().forEach { Rifornimenti.cancella(this, it) }
            }
        }
    }

    private fun chiediCancella(titolo: String, testo: String, azione: () -> Unit) {
        val (d, s) = finestra(R.drawable.ic_storico, titolo)
        testo(s, "$testo Non si possono recuperare.").setPadding(0, px(8), 0, 0)
        val tasti = rigaTasti(s)
        tasto(tasti, "Cancella", true) { azione(); d.dismiss(); aggiornaSchermo() }
        tasto(tasti, "Annulla", false) { d.dismiss() }
        mostraFinestra(d, s)
    }

    /** le righe del viaggio: partenza, durata e, coi dati dell'auto dall'iDrive, km, carburante, temperature e aperture */
    private fun righeViaggio(box: LinearLayout, v: Viaggio.Dati) {
        val oggi = SimpleDateFormat("ddMMyyyy", Locale.ITALY).format(Date())
        val giorno = SimpleDateFormat("ddMMyyyy", Locale.ITALY).format(Date(v.partenza))
        val quando = SimpleDateFormat(if (giorno == oggi) "HH:mm" else "dd/MM 'alle' HH:mm", Locale.ITALY).format(Date(v.partenza))
        rigaValore(box, "Partenza", (if (v.luogo.isNotEmpty()) "${v.luogo}, " else "") + "alle $quando")
        val minuti = v.durata() / 60000
        rigaValore(box, "Durata", if (minuti < 60) "$minuti min" else "${minuti / 60} h ${"%02d".format(minuti % 60)} min")
        if (v.grezzi.isEmpty()) {
            piccolo(box, "Km, carburante, temperature e aperture arrivano dall'iDrive (plugin Meteo della chiavetta aggiornata) " +
                "quando il telefono è sul Wi-Fi dell'auto.")
            return
        }
        val ora = v.ora()
        v.km()?.let { rigaValore(box, "Km percorsi", String.format(Locale.ITALY, if (it < 10) "%.1f km" else "%.0f km", it)) }
        v.litri()?.let {
            rigaValore(box, "Carburante consumato", String.format(Locale.ITALY, "%.1f l", it) + if (v.rifornito > 0) " (con rifornimento)" else "")
        }
        ora.litri?.let { l ->
            rigaValore(box, "Nel serbatoio", String.format(Locale.ITALY, "%.0f l", l) +
                (ora.autonomia?.let { String.format(Locale.ITALY, ", autonomia %.0f km", it) } ?: ""))
        }
        ora.kmLitro?.let { rigaValore(box, "Consumo medio", String.format(Locale.ITALY, "%.1f km/l (computer di bordo)", it)) }
        val temperature = listOfNotNull(
            ora.esterna?.let { String.format(Locale.ITALY, "esterna %.0f °C", it) },
            ora.interna?.let { String.format(Locale.ITALY, "interna %.0f °C", it) }
        )
        if (temperature.isNotEmpty()) rigaValore(box, "Temperature", temperature.joinToString(", "))
        ora.aperti?.let { a ->
            if (a.isEmpty()) rigaValore(box, "Aperture", "tutto chiuso")
            else rigaValore(box, "Aperture", "aperti: " + a.joinToString(", "), evidenzia = true)
        }
    }

    /** etichetta a sinistra e valore a destra, come le righe dello stato (senza pallino) */
    private fun rigaValore(s: ViewGroup, etichetta: String, valore: String, evidenzia: Boolean = false) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(3), 0, px(3))
        }
        r.addView(
            TextView(this).apply { text = etichetta; setTextColor(TENUE); textSize = 14f },
            LinearLayout.LayoutParams(px(150), WRAP_CONTENT)
        )
        r.addView(
            TextView(this).apply {
                text = valore; textSize = 14f
                setTextColor(if (evidenzia) ARANCIO else TESTO)
                if (evidenzia) typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        s.addView(r)
    }

    private fun titoletto(s: ViewGroup, t: String, staccato: Boolean) = TextView(this).apply {
        text = t; setTextColor(TESTO); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, if (staccato) px(18) else 0, 0, px(2))
    }.also { s.addView(it) }

    /** finestra con icona e titolo nello stile delle schede; il contenuto va aggiunto a s, poi mostraFinestra */
    private fun finestra(icona: Int, titolo: String): Pair<Dialog, LinearLayout> {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tondo(SCHEDA, 16, BORDO)
            setPadding(px(20), px(18), px(20), px(18))
        }
        val testa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        testa.addView(ImageView(this).apply {
            setImageResource(icona)
            imageTintList = ColorStateList.valueOf(ACCENTO)
        }, LinearLayout.LayoutParams(px(22), px(22)))
        testa.addView(TextView(this).apply {
            text = titolo; setTextColor(TESTO); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(10), 0, 0, 0)
        })
        s.addView(testa)
        s.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, px(4)))
        return d to s
    }

    private fun mostraFinestra(d: Dialog, s: LinearLayout) {
        d.setContentView(s)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - px(32), px(440)), WRAP_CONTENT)
    }

    /** nome (solo da mostrare) e carburante di un'auto del parco (solo auto gia' collegate almeno una volta) */
    private fun modificaAuto(bt: String) {
        val (d, s) = finestra(R.drawable.ic_auto, Parco.mostra(this, bt))
        etichetta(s, "Nome nell'app")
        val nome = campo(s, Parco.scelto(this, bt)).apply {
            hint = bt
            filters = arrayOf(InputFilter.LengthFilter(Parco.MASSIMO))
            setSelection(text.length)
        }
        piccolo(s, "Al posto di \"$bt\". Lascia vuoto per tornare al nome Bluetooth. " +
            "Cambia solo ciò che vedi nell'app: l'auto si riconosce sempre dal Bluetooth.")
        etichetta(s, "Carburante (distributori nell'iDrive)")
        var carburante = Parco.carburante(this, bt)
        val chips = HashMap<Int, TextView>()
        val r = rigaChip(s)
        for ((codice, n) in Fonti.CARBURANTI) chips[codice] = chip(r, n) {
            carburante = codice
            chips.forEach { (k, v) -> scegli(v, k == carburante) }
        }
        chips.forEach { (k, v) -> scegli(v, k == carburante) }
        val salva = {
            val prima = Parco.carburante(this, bt)
            Parco.imposta(this, bt, nome.text.toString())
            Parco.impostaCarburante(this, bt, carburante)
            d.dismiss()
            aggiornaSchermo()
            if (carburante != prima && bt == Parco.ultima(this) && Stato.servizioAttivo) PonteService.aggiornaOra(this)
        }
        nome.setOnEditorActionListener { _, azione, _ -> if (azione == EditorInfo.IME_ACTION_DONE) { salva(); true } else false }
        val tasti = rigaTasti(s)
        tasto(tasti, "Salva", true) { salva() }
        tasto(tasti, "Annulla", false) { d.dismiss() }
        mostraFinestra(d, s)
    }

    private fun togliAuto(bt: String) {
        val (d, s) = finestra(R.drawable.ic_auto, "Togliere ${Parco.mostra(this, bt)}?")
        testo(s, "Si cancellano nome, carburante, parcheggio, storico dei viaggi e rifornimenti di questa auto. " +
            "Se il telefono si ricollega al suo Bluetooth ($bt), torna nell'elenco da sola.").setPadding(0, px(8), 0, 0)
        val tasti = rigaTasti(s)
        tasto(tasti, "Togli", true) { Parco.togli(this, bt); d.dismiss(); aggiornaSchermo() }
        tasto(tasti, "Annulla", false) { d.dismiss() }
        mostraFinestra(d, s)
    }

    /** le auto gia' associate al telefono (nome Bluetooth da auto) che non sono ancora nell'elenco */
    private fun aggiungiDaBluetooth() {
        val (d, s) = finestra(R.drawable.ic_auto, "Aggiungi dal Bluetooth")
        if (Build.VERSION.SDK_INT >= 31 && !ha(Manifest.permission.BLUETOOTH_CONNECT)) {
            testo(s, "Serve il permesso Bluetooth: concedilo nel riquadro Permessi, poi riprova.").setPadding(0, px(8), 0, 0)
        } else {
            val gia = Parco.elenco(this).toSet()
            val nuove = try {
                getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                    .mapNotNull { it.name?.trim() }.filter { AutoBtReceiver.eAuto(it) && it !in gia }.distinct().sorted()
            } catch (e: SecurityException) {
                emptyList()
            }
            if (nuove.isEmpty()) {
                testo(s, "Nessuna auto nuova fra i dispositivi associati al telefono. Ogni auto si aggiunge comunque da sola " +
                    "la prima volta che il telefono si collega al suo Bluetooth.").setPadding(0, px(8), 0, 0)
            } else {
                piccolo(s, "Auto associate al telefono e non ancora nell'elenco:")
                for (n in nuove) {
                    val riga = rigaTasti(s)
                    tasto(riga, n, false) { Parco.aggiungi(this, n); d.dismiss(); aggiornaSchermo() }
                }
            }
        }
        val tasti = rigaTasti(s)
        tasto(tasti, "Chiudi", false) { d.dismiss() }
        mostraFinestra(d, s)
    }

    /** cerca la citta' con la geocodifica di open-meteo; locali = notizie locali (salva anche regione e provincia) */
    private fun salvaCitta(campo: EditText, locali: Boolean) {
        val nome = campo.text.toString().trim()
        val esito = if (locali) esitoLocali else esitoRiserva
        if (nome.isEmpty()) return
        esito.text = "Cerco \"$nome\"..."
        Thread {
            val testo = try {
                val c = Dati.cercaCitta(this, nome)
                if (c == null) "Città \"$nome\" non trovata."
                else {
                    val e = getSharedPreferences("ponte", MODE_PRIVATE).edit()
                    if (locali) {
                        e.putString("locali_citta", c.nome).putString("locali_regione", c.regione).putString("locali_provincia", c.provincia)
                    } else {
                        e.putString("citta", c.nome).putFloat("lat", c.lat.toFloat()).putFloat("lon", c.lon.toFloat())
                    }
                    e.apply()
                    PonteService.aggiornaOra(this)
                    if (locali) {
                        if (Fonti.feedRegione(c.regione) == null) "Salvata ${c.nome}, ma la regione \"${c.regione}\" non ha un notiziario ANSA."
                        else "Salvata: ${c.nome} (${c.provincia}) - notiziario ANSA ${c.regione}"
                    } else {
                        "Salvata: ${c.nome} (%.3f, %.3f)".format(Locale.ITALY, c.lat, c.lon)
                    }
                }
            } catch (e: Exception) {
                "Ricerca non riuscita: ${e.message}"
            }
            runOnUiThread { esito.text = testo }
        }.start()
    }
}
