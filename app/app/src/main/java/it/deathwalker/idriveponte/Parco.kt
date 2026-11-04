package it.deathwalker.idriveponte

import android.content.Context

/**
 * Parco vetture: ogni auto si riconosce dal suo nome Bluetooth ("MINI12345", "BMW 67890", ...) e si aggiunge da sola
 * la prima volta che il telefono si collega al suo Bluetooth (o a mano dalle auto associate al telefono).
 * Per ogni auto restano separati il nome da mostrare, il carburante e il parcheggio (Parcheggio).
 * Il nome e' solo un'etichetta: l'auto si riconosce sempre dal nome Bluetooth vero, che resta salvato com'e'.
 * Un nome o un carburante dato prima di aver mai collegato un'auto vale per tutte quelle che non ne hanno uno proprio.
 * L'auto "attuale" e' l'ultima che si e' collegata (o scollegata): e' quella dell'iDrive in questo momento.
 */
object Parco {

    const val MASSIMO = 24

    private const val ULTIMA = "auto_bt"            // ultimo nome Bluetooth di un'auto visto
    private const val NOTE = "auto_bt_note"         // tutti i nomi Bluetooth delle auto del parco
    private const val NOME = "nome_auto:"           // + nome Bluetooth ("" = vale per tutte)
    private const val VISTA = "auto_vista:"         // + nome Bluetooth: ultimo collegamento o scollegamento (ms)
    private const val CARBURANTE = "carburante"     // + ":" + nome Bluetooth; da solo = vale per tutte

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun note(ctx: Context): HashSet<String> = HashSet(prefs(ctx).getStringSet(NOTE, emptySet()).orEmpty())

    /** il ricevitore Bluetooth segna ogni auto che vede: entra nel parco (se non c'era) e diventa l'auto attuale */
    fun ricorda(ctx: Context, bt: String?) {
        val b = bt?.trim().orEmpty()
        if (b.isEmpty()) return
        val note = note(ctx)
        note.add(b)
        prefs(ctx).edit().putString(ULTIMA, b).putStringSet(NOTE, note).putLong(VISTA + b, System.currentTimeMillis()).apply()
    }

    /** aggiunge un'auto al parco senza farla diventare quella attuale (dalle auto associate al telefono) */
    fun aggiungi(ctx: Context, bt: String) {
        val b = bt.trim()
        if (b.isEmpty()) return
        val note = note(ctx)
        if (!note.add(b)) return
        val e = prefs(ctx).edit().putStringSet(NOTE, note)
        if (prefs(ctx).getString(ULTIMA, null) == null) e.putString(ULTIMA, b)
        e.apply()
    }

    /** toglie un'auto dal parco con nome, carburante, parcheggio, storico viaggi, rifornimenti e foto; se si ricollega al Bluetooth torna da sola */
    fun togli(ctx: Context, bt: String) {
        val b = bt.trim()
        val note = note(ctx)
        note.remove(b)
        val p = prefs(ctx)
        val e = p.edit().putStringSet(NOTE, note).remove(NOME + b).remove("$CARBURANTE:$b").remove(VISTA + b)
        if (p.getString(ULTIMA, null) == b) {
            val altra = note.maxByOrNull { p.getLong(VISTA + it, 0L) }
            if (altra != null) e.putString(ULTIMA, altra) else e.remove(ULTIMA)
        }
        e.apply()
        Parcheggio.cancella(ctx, b)
        Viaggio.cancella(ctx, b)
        Storico.cancella(ctx, b)
        Rifornimenti.cancella(ctx, b)
        Foto.cancella(ctx, b)
    }

    /** ultima auto vista; per chi aggiorna l'app la si ritrova nel parcheggio o nell'ultimo evento Bluetooth */
    fun ultima(ctx: Context): String? {
        val p = prefs(ctx)
        p.getString(ULTIMA, null)?.let { return it }
        val vecchia = listOfNotNull(p.getString("park_auto", null), p.getString("ultimo_bt", null))
            .firstNotNullOfOrNull { AutoBtReceiver.trovaAuto(it) } ?: return null
        ricorda(ctx, vecchia)
        return vecchia
    }

    /** le auto del parco, dall'ultima vista alla meno recente */
    fun elenco(ctx: Context): List<String> {
        ultima(ctx)
        val p = prefs(ctx)
        return note(ctx).sortedWith(compareByDescending<String> { p.getLong(VISTA + it, 0L) }.thenBy { it })
    }

    /** ultimo collegamento o scollegamento dell'auto (ms), 0 se mai visto da quando c'e' il parco */
    fun vista(ctx: Context, bt: String): Long = prefs(ctx).getLong(VISTA + bt.trim(), 0L)

    // ---- carburante ---------------------------------------------------------------------------------------------

    /** codice del carburante dell'auto (Fonti.CARBURANTI); null = quello per tutte */
    fun carburante(ctx: Context, bt: String?): Int {
        val p = prefs(ctx)
        val b = bt?.trim().orEmpty()
        val tutte = p.getInt(CARBURANTE, 1)
        return if (b.isEmpty()) tutte else p.getInt("$CARBURANTE:$b", tutte)
    }

    fun impostaCarburante(ctx: Context, bt: String?, codice: Int) {
        val b = bt?.trim().orEmpty()
        prefs(ctx).edit().putInt(if (b.isEmpty()) CARBURANTE else "$CARBURANTE:$b", codice).apply()
    }

    // ---- nome da mostrare ---------------------------------------------------------------------------------------

    /** il nome dato proprio a questa auto (null = quello per tutte); "" se non ce n'e' */
    fun proprio(ctx: Context, bt: String?): String = prefs(ctx).getString(NOME + bt?.trim().orEmpty(), null).orEmpty()

    /** il nome che si vede ora per questa auto, se gliene e' stato dato uno (suo o quello per tutte); "" se no */
    fun scelto(ctx: Context, bt: String?): String = proprio(ctx, bt).ifEmpty { proprio(ctx, "") }

    /** salva il nome; vuoto = torna al nome Bluetooth. Con l'auto nota il nome diventa suo e quello "per tutte" sparisce */
    fun imposta(ctx: Context, bt: String?, nome: String) {
        val n = nome.trim().replace(Regex("\\s+"), " ").take(MASSIMO)
        val b = bt?.trim().orEmpty()
        prefs(ctx).edit().apply {
            if (n.isEmpty()) remove(NOME + b) else putString(NOME + b, n)
            if (b.isNotEmpty()) remove(NOME)
        }.apply()
    }

    /** come mostrare l'auto con questo nome Bluetooth */
    fun mostra(ctx: Context, bt: String?): String {
        val b = bt?.trim().orEmpty()
        return scelto(ctx, b).ifEmpty { b }
    }

    /** sostituisce in un testo gia' scritto ("acceso da MINI12345 - ...") i nomi Bluetooth delle auto con i loro nomi */
    fun nelTesto(ctx: Context, testo: String): String {
        val note = note(ctx)
        ultima(ctx)?.let { note.add(it) }
        var t = testo
        for (bt in note.sortedByDescending { it.length }) {
            val n = mostra(ctx, bt)
            if (n != bt) t = t.replace(bt, n)
        }
        return t
    }
}
