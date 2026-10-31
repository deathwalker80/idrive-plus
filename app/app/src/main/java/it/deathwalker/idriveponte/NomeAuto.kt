package it.deathwalker.idriveponte

import android.content.Context

/**
 * Nome dell'auto da mostrare nell'app al posto di quello Bluetooth ("MINI12345"). E' solo un'etichetta:
 * l'auto e la marca si riconoscono sempre dal nome Bluetooth vero, che resta salvato com'e'.
 * Ogni auto puo' avere il suo nome; un nome dato prima di aver mai collegato un'auto vale per tutte quelle
 * che non ne hanno uno proprio.
 */
object NomeAuto {

    const val MASSIMO = 24

    private const val ULTIMA = "auto_bt"            // ultimo nome Bluetooth di un'auto visto
    private const val NOTE = "auto_bt_note"         // tutti i nomi Bluetooth di auto visti
    private const val NOME = "nome_auto:"           // + nome Bluetooth ("" = vale per tutte)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    /** il ricevitore Bluetooth segna ogni auto che vede, cosi' l'app sa a chi dare il nome */
    fun ricorda(ctx: Context, bt: String?) {
        val b = bt?.trim().orEmpty()
        if (b.isEmpty()) return
        val p = prefs(ctx)
        val note = HashSet(p.getStringSet(NOTE, emptySet()).orEmpty())
        if (p.getString(ULTIMA, null) == b && b in note) return
        note.add(b)
        p.edit().putString(ULTIMA, b).putStringSet(NOTE, note).apply()
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
        val note = HashSet(prefs(ctx).getStringSet(NOTE, emptySet()).orEmpty())
        ultima(ctx)?.let { note.add(it) }
        var t = testo
        for (bt in note.sortedByDescending { it.length }) {
            val n = mostra(ctx, bt)
            if (n != bt) t = t.replace(bt, n)
        }
        return t
    }
}
