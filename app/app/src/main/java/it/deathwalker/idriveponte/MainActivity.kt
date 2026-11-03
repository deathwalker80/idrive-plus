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
 * MINI Plus / BMW Plus / Supra Plus: schermata del telefono. Stato del collegamento con l'iDrive, dove hai parcheggiato,
 * anteprima di ogni pagina che vede l'auto, impostazioni e permessi.
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

    // permessi
    private class Permesso(val tasto: TextView)
    private val permessi = HashMap<String, Permesso>()

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
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(20), px(16), px(28))
        }
        col.addView(intestazione())

        // ---- stato del collegamento
        col.addView(scheda(R.drawable.ic_auto, "In auto") { s ->
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
        })

        // ---- viaggio in corso: compare solo mentre sei in auto (dopo resta nel riquadro del parcheggio)
        schedaViaggio = scheda(R.drawable.ic_auto, "In viaggio") { s ->
            boxViaggio = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        }
        col.addView(schedaViaggio)

        // ---- parcheggio
        col.addView(scheda(R.drawable.ic_parcheggio, "Dove ho parcheggiato") { s ->
            boxParcheggi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
        })

        // ---- parco vetture: compare solo quando il telefono ha visto piu' di un'auto
        schedaParco = scheda(R.drawable.ic_auto, "Le mie auto", "Ognuna col suo nome, carburante e parcheggio") { s ->
            boxParco = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { s.addView(it) }
            val tasti = rigaTasti(s)
            tasto(tasti, "Aggiungi dal Bluetooth", false) { aggiungiDaBluetooth() }
        }
        col.addView(schedaParco)

        // ---- pagine che vede l'iDrive
        val pagine = listOf(
            Triple("Meteo", R.drawable.ic_meteo, "Dove sei, con le allerte della Protezione Civile"),
            Triple("Notizie", R.drawable.ic_notizie, "ANSA, le prime 8 notizie con foto"),
            Triple("Notizie locali", R.drawable.ic_locali, "ANSA della regione della città scelta"),
            Triple("Carburanti", R.drawable.ic_carburante, "Prezzi ufficiali MIMIT vicino a te"),
            Triple("Traffico", R.drawable.ic_traffico, "Autostrade per l'Italia vicino a te")
        )
        for ((nome, icona, sotto) in pagine) {
            col.addView(scheda(icona, nome, sotto) { s ->
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
            })
        }

        // ---- impostazioni
        val prefs = getSharedPreferences("ponte", MODE_PRIVATE)
        col.addView(scheda(R.drawable.ic_impostazioni, "Impostazioni") { s ->
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
        })

        // ---- permessi
        col.addView(scheda(R.drawable.ic_permessi, "Permessi") { s ->
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
        })

        col.addView(TextView(this).apply {
            text = "${Marca.APP} ${Stato.VERSIONE} · dati: open-meteo.com, MET Norway, ANSA, MIMIT, Autostrade per l'Italia, Protezione Civile"
            setTextColor(TENUE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(14), 0, 0)
        })

        val scorri = ScrollView(this).apply {
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
    }

    override fun onResume() {
        super.onResume()
        mano.post(ripeti)
    }

    override fun onPause() {
        mano.removeCallbacks(ripeti)
        super.onPause()
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
            text = "Meteo, notizie, carburanti e traffico nell'iDrive · v${Stato.VERSIONE}"
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

        mostra("Meteo", Stato.meteo.esito + (if (Stato.allerta.isNotEmpty()) "\nAllerta: ${Stato.allerta}" else ""), Stato.meteo.epoch, Stato.meteo.png)
        mostra("Notizie", Stato.notizie.esito, Stato.notizie.epoch, Stato.notizie.notizie.firstOrNull()?.pagina)
        mostra("Notizie locali", Stato.locali.esito, Stato.locali.epoch, Stato.locali.notizie.firstOrNull()?.pagina)
        mostra("Carburanti", Stato.carburanti.esito, Stato.carburanti.epoch, Stato.carburanti.png)
        mostra("Traffico", Stato.traffico.esito, Stato.traffico.epoch, Stato.traffico.png)

        statoPermesso("bt", Build.VERSION.SDK_INT < 31 || ha(Manifest.permission.BLUETOOTH_CONNECT))
        statoPermesso("notifiche", Build.VERSION.SDK_INT < 33 || ha(Manifest.permission.POST_NOTIFICATIONS))
        statoPermesso("batteria", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        statoPermesso("posizione", ha(Manifest.permission.ACCESS_FINE_LOCATION))
        statoPermesso("sempre", Posizione.permessoSempre(this))
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
        ora.consumo?.let { rigaValore(box, "Consumo medio", String.format(Locale.ITALY, "%.1f km/l (computer di bordo)", 100 / it)) }
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
        testo(s, "Si cancellano nome, carburante e parcheggio di questa auto. " +
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
