package it.deathwalker.idriveponte

import android.content.Context

/**
 * Quali parti (porte, finestrini, tetto, portellone, cofano) si vedono nello Stato vettura, auto per auto:
 *  - si tolgono da sole quelle che l'auto non ha: mandate sempre con valore non valido e mai con uno valido
 *    (il tetto sulle auto senza tetto apribile); appena arriva un valore valido tornano;
 *  - l'utente puo' spegnerne altre (Stato vettura, Sistema i punti).
 * Vale per la pagina Porte dell'iDrive, il Riepilogo e l'app; gli avvisi (rimasto aperto, pioggia) contano sempre tutto.
 */
object PartiVisibili {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)
    private fun kOk(bt: String) = "parti_ok:${bt.trim()}"
    private fun kKo(bt: String) = "parti_ko:${bt.trim()}"
    private fun kSpente(bt: String) = "parti_spente:${bt.trim()}"

    private fun leggi(ctx: Context, chiave: String): Set<DatiAuto.Parte> {
        val s = prefs(ctx).getStringSet(chiave, emptySet()).orEmpty()
        return DatiAuto.Parte.values().filter { it.chiave in s }.toSet()
    }

    private fun scrivi(ctx: Context, chiave: String, parti: Set<DatiAuto.Parte>) {
        prefs(ctx).edit().putStringSet(chiave, parti.map { it.chiave }.toSet()).apply()
    }

    /** dai valori appena arrivati: ricorda quali parti l'auto manda valide e quali no (scrive solo se cambia) */
    fun registra(ctx: Context, bt: String?, grezzi: Map<String, String>) {
        if (grezzi.isEmpty()) return
        val b = bt.orEmpty()
        val ok = leggi(ctx, kOk(b))
        val ko = leggi(ctx, kKo(b))
        val nuoviOk = ok + DatiAuto.parti(grezzi).keys
        val nuoviKo = ko + DatiAuto.nonValide(grezzi)
        if (nuoviOk != ok) scrivi(ctx, kOk(b), nuoviOk)
        if (nuoviKo != ko) scrivi(ctx, kKo(b), nuoviKo)
    }

    /** l'auto manda questa parte sempre non valida: non ce l'ha */
    fun nonSegnalata(ctx: Context, bt: String?, p: DatiAuto.Parte): Boolean =
        p in leggi(ctx, kKo(bt.orEmpty())) && p !in leggi(ctx, kOk(bt.orEmpty()))

    fun spente(ctx: Context, bt: String?): Set<DatiAuto.Parte> = leggi(ctx, kSpente(bt.orEmpty()))

    fun salvaSpente(ctx: Context, bt: String?, parti: Set<DatiAuto.Parte>) = scrivi(ctx, kSpente(bt.orEmpty()), parti)

    /** le parti da mostrare: tutte, meno quelle che l'auto non ha e quelle spente dall'utente */
    fun mostrate(ctx: Context, bt: String?): Set<DatiAuto.Parte> {
        val b = bt.orEmpty()
        val ok = leggi(ctx, kOk(b))
        val ko = leggi(ctx, kKo(b))
        val spente = leggi(ctx, kSpente(b))
        return DatiAuto.Parte.values().filter { !(it in ko && it !in ok) && it !in spente }.toSet()
    }

    /** firma per ridisegnare la pagina quando cambia qualcosa */
    fun firma(ctx: Context, bt: String?): String = mostrate(ctx, bt).joinToString(",") { it.chiave }

    /** l'auto tolta dal parco */
    fun cancella(ctx: Context, bt: String) {
        prefs(ctx).edit().remove(kOk(bt)).remove(kKo(bt)).remove(kSpente(bt)).apply()
    }
}
