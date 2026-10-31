package it.deathwalker.idriveponte

import android.Manifest
import android.app.Activity
import android.app.Dialog
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
import android.view.inputmethod.InputMethodManager
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
 * NBT Plus: schermata del telefono. Stato del collegamento con l'iDrive, dove hai parcheggiato,
 * anteprima di ogni pagina che vede l'auto, impostazioni e permessi.
 * Si aggiorna da sola ogni 1,5 s senza toccare i campi di testo (non interrompe mai la scrittura).
 * Se cambia la marca dell'auto (Bluetooth o impostazioni) si ridisegna coi colori nuovi, ma mai mentre stai scrivendo.
 */
class MainActivity : Activity() {

    // tavolozza: nero, schede grafite, colore della marca (azzurro MINI, giallo BMW, rosso Supra, grigio chiaro se non e' nota)
    private val SFONDO = Color.rgb(10, 12, 15)
    private val SCHEDA = Color.rgb(24, 28, 34)
    private val BORDO = Color.rgb(40, 46, 55)
    private val TESTO = Color.rgb(236, 240, 244)
    private val TENUE = Color.rgb(150, 160, 172)
    private var ACCENTO = Marca.ACCENTO
    private var SU_ACCENTO = Marca.SU_ACCENTO
    private val VERDE = Color.rgb(76, 200, 120)
    private val ROSSO = Color.rgb(240, 90, 80)
    private val ARANCIO = Color.rgb(250, 160, 50)

    private val mano = Handler(Looper.getMainLooper())
    private var dp = 1f
    private var vistaScorri: ScrollView? = null
    private var marcaDisegnata: Marca.Tipo? = null

    // stato
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
    private lateinit var testoParcheggio: TextView
    private lateinit var tastoPortami: TextView

    // pagine dell'auto
    private class Riquadro(val esito: TextView, val immagine: ImageView, var epoch: Long = -1)
    private val riquadri = HashMap<String, Riquadro>()

    // impostazioni
    private val chipMarca = HashMap<String, TextView>()
    private val chipCarburante = HashMap<Int, TextView>()
    private val chipRaggio = HashMap<Int, TextView>()
    private lateinit var campoNome: EditText
    private lateinit var esitoNome: TextView
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
        Marca.carica(this)
        costruisci()
    }

    /** costruisce tutta la schermata coi colori della marca attuale */
    private fun costruisci() {
        marcaDisegnata = Marca.attuale
        ACCENTO = Marca.ACCENTO
        SU_ACCENTO = Marca.SU_ACCENTO
        riquadri.clear(); chipMarca.clear(); chipCarburante.clear(); chipRaggio.clear(); permessi.clear()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(20), px(16), px(28))
        }
        col.addView(intestazione())

        // ---- stato del collegamento
        col.addView(scheda(R.drawable.ic_auto, "In auto") { s ->
            val (p1, t1) = rigaStato(s, "Servizio"); puntoServizio = p1; testoServizio = t1
            val (p2, t2) = rigaStato(s, "Wi-Fi ${Marca.della()}"); puntoWifi = p2; testoWifi = t2
            val (p3, t3) = rigaStato(s, "iDrive"); puntoIdrive = p3; testoIdrive = t3
            testoBt = piccolo(s, "")
            testoInCorso = piccolo(s, "").apply { setTextColor(ACCENTO) }
            val tasti = rigaTasti(s)
            tastoAccendi = tasto(tasti, "Accendi", true) {
                if (Stato.servizioAttivo) PonteService.ferma(this) else PonteService.avvia(this, "a mano")
            }
            tasto(tasti, "Aggiorna tutto", false) { PonteService.aggiornaOra(this) }
        })

        // ---- parcheggio
        col.addView(scheda(R.drawable.ic_parcheggio, "Dove ho parcheggiato") { s ->
            testoParcheggio = testo(s, "")
            val tasti = rigaTasti(s)
            tastoPortami = tasto(tasti, "Portami ${Marca.alla()}", true) { Parcheggio.ultimo(this)?.let { Parcheggio.portami(this, it) } }
        })

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
            etichetta(s, "Auto (si riconosce da sola dal Bluetooth)")
            val r0 = rigaChip(s)
            for (tipo in Marca.TUTTE) chipMarca[tipo.codice] = chip(r0, tipo.auto) {
                // hai toccato la marca, quindi non stai scrivendo: il campo perde il fuoco (il testo resta) e si ridisegna subito
                currentFocus?.clearFocus()
                Marca.imposta(this, tipo); ridisegnaPerMarca()
            }

            etichetta(s, "Nome dell'auto nell'app")
            val bt = NomeAuto.ultima(this)
            campoNome = campo(s, NomeAuto.scelto(this, bt)).apply {
                hint = bt ?: "es. La mia ${Marca.AUTO}"
                filters = arrayOf(InputFilter.LengthFilter(NomeAuto.MASSIMO))
                setOnEditorActionListener { _, azione, _ -> if (azione == EditorInfo.IME_ACTION_DONE) { salvaNome(); true } else false }
            }
            val t0 = rigaTasti(s)
            tasto(t0, "Salva nome", false) { salvaNome() }
            esitoNome = piccolo(s, spiegaNome(bt))

            etichetta(s, "Carburante")
            val r1 = rigaChip(s)
            for ((codice, nome) in Fonti.CARBURANTI) chipCarburante[codice] = chip(r1, nome) {
                prefs.edit().putInt("carburante", codice).apply(); aggiornaChip(); PonteService.aggiornaOra(this)
            }
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
                informativaPosizione { chiedi(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            }
            permesso(s, "sempre", "Posizione anche in auto", "scegli \"Consenti sempre\"") {
                informativaPosizione {
                    if (!Posizione.permesso(this)) chiedi(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else chiedi(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
        })

        col.addView(TextView(this).apply {
            text = "${Marca.APP} ${Stato.VERSIONE} · dati: open-meteo.com, ANSA, MIMIT, Autostrade per l'Italia, Protezione Civile"
            setTextColor(TENUE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(14), 0, 0)
        })

        val scorri = ScrollView(this).apply {
            setBackgroundColor(SFONDO)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
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
        vistaScorri = scorri
        aggiornaChip()
    }

    /** marca cambiata: si ricostruisce la schermata tenendo testi scritti, esiti e punto di scorrimento.
     *  Se stai scrivendo in un campo non si tocca niente: ci si riprova al giro successivo. */
    private fun ridisegnaPerMarca() {
        if (campoLocali.hasFocus() || campoRiserva.hasFocus()) return
        val y = vistaScorri?.scrollY ?: 0
        val locali = campoLocali.text.toString()
        val riserva = campoRiserva.text.toString()
        val esLocali = esitoLocali.text
        val esRiserva = esitoRiserva.text
        costruisci()
        campoLocali.setText(locali)
        campoRiserva.setText(riserva)
        esitoLocali.text = esLocali
        esitoRiserva.text = esRiserva
        vistaScorri?.let { v -> v.post { v.scrollTo(0, y) } }
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
        chipMarca.forEach { (k, v) -> scegli(v, k == Marca.attuale.codice) }
        val carb = p.getInt("carburante", 1)
        chipCarburante.forEach { (k, v) -> scegli(v, k == carb) }
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
        if (Marca.attuale !== marcaDisegnata) ridisegnaPerMarca()
        val prefs = getSharedPreferences("ponte", MODE_PRIVATE)
        val ora = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

        punto(puntoServizio, if (Stato.servizioAttivo) VERDE else TENUE)
        testoServizio.text = if (Stato.servizioAttivo) "acceso (${NomeAuto.nelTesto(this, Stato.avviatoDa)})" else "spento: si accende da solo col Bluetooth ${Marca.della()}"
        tastoAccendi.text = if (Stato.servizioAttivo) "Spegni" else "Accendi"
        val wifi = Stato.wifiAuto
        punto(puntoWifi, if (wifi != null) VERDE else TENUE)
        testoWifi.text = wifi?.let { "collegato ($it)" } ?: "no (serve Android Auto wireless)"
        val recente = System.currentTimeMillis() - Stato.ultimaRichiestaAuto < 2 * 60 * 1000L
        punto(puntoIdrive, if (Stato.ultimaRichiestaAuto == 0L) TENUE else if (recente) VERDE else ARANCIO)
        testoIdrive.text = if (Stato.ultimaRichiestaAuto == 0L) "nessuna richiesta"
        else "ultima richiesta ${ora.format(Date(Stato.ultimaRichiestaAuto))} (${Stato.richiesteAuto} in tutto)"
        testoBt.text = "Bluetooth: " + (prefs.getString("ultimo_bt", null)?.let { NomeAuto.nelTesto(this, it) } ?: "nessun evento ancora") +
            (if (Stato.erroreServer.isNotEmpty()) "\nServer: ${Stato.erroreServer}" else "")
        val inCorso = Stato.aggiornamentoInCorso
        testoInCorso.text = if (inCorso.isNotEmpty()) "Sto aggiornando: $inCorso..." else ""
        testoInCorso.visibility = if (inCorso.isNotEmpty()) View.VISIBLE else View.GONE

        val p = Parcheggio.ultimo(this)
        testoParcheggio.text = p?.let { Parcheggio.descrizione(it) }
            ?: "Ancora nessun parcheggio salvato: si salva da solo quando scendi ${Marca.dalla()}."
        tastoPortami.isEnabled = p != null
        tastoPortami.setTextColor(if (p != null) SU_ACCENTO else TENUE)
        tastoPortami.background = if (p != null) tondo(ACCENTO, 22) else tondo(Color.TRANSPARENT, 22, BORDO)

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

    /** informativa ben visibile prima di chiedere la posizione: Google Play la vuole per la posizione in background.
     *  "Continua" porta alla richiesta di Android, "No, grazie" chiude senza chiedere niente. */
    private fun informativaPosizione(poi: () -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tondo(SCHEDA, 16, BORDO)
            setPadding(px(20), px(18), px(20), px(18))
        }
        val testa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        testa.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_permessi)
            imageTintList = ColorStateList.valueOf(ACCENTO)
        }, LinearLayout.LayoutParams(px(22), px(22)))
        testa.addView(TextView(this).apply {
            text = "Uso della posizione"; setTextColor(TESTO); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(10), 0, 0, 0)
        })
        s.addView(testa)
        s.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, px(10)))
        testo(s, "${Marca.APP} raccoglie i dati sulla posizione del telefono per mostrare nell'iDrive meteo, allerte, " +
            "distributori e traffico della zona in cui ti trovi e per salvare dove hai parcheggiato, " +
            "anche quando l'app è chiusa o non in uso.")
        piccolo(s, "Le coordinate vanno solo a open-meteo.com e a MIMIT Osservaprezzi per avere i dati della zona; " +
            "il parcheggio resta sul telefono. Nessun dato va allo sviluppatore. " +
            "Se non consenti, l'app usa la città di riserva e non salva il parcheggio.").setPadding(0, px(10), 0, 0)
        val tasti = rigaTasti(s)
        tasto(tasti, "Continua", true) { d.dismiss(); poi() }
        tasto(tasti, "No, grazie", false) { d.dismiss() }
        d.setContentView(s)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout(minOf(resources.displayMetrics.widthPixels - px(32), px(440)), WRAP_CONTENT)
    }

    @Suppress("BatteryLife")
    private fun chiediBatteria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun spiegaNome(bt: String?): String =
        (if (bt != null) "Al posto di \"$bt\". Vuoto = torna al nome Bluetooth." else "Vale per l'auto che collegherai.") +
            " Cambia solo ciò che vedi nell'app: l'auto si riconosce sempre dal Bluetooth."

    /** nome solo da mostrare: l'auto resta riconosciuta dal suo nome Bluetooth */
    private fun salvaNome() {
        val bt = NomeAuto.ultima(this)
        NomeAuto.imposta(this, bt, campoNome.text.toString())
        val nome = NomeAuto.scelto(this, bt)
        campoNome.setText(nome); campoNome.setSelection(nome.length); campoNome.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(campoNome.windowToken, 0)
        esitoNome.text = if (nome.isEmpty()) "Nome tolto: si vede " + (bt?.let { "\"$it\"" } ?: "il nome Bluetooth") + "."
        else "Salvato: si vede \"$nome\"" + (bt?.let { " al posto di \"$it\"" } ?: "") + "."
        aggiornaSchermo()
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
