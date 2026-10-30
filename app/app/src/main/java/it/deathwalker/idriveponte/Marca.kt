package it.deathwalker.idriveponte

import android.content.Context

/**
 * NBT Plus e' un'app sola per MINI, BMW e Toyota Supra: la marca si riconosce dal nome Bluetooth dell'auto
 * (AutoBtReceiver) oppure si sceglie nelle impostazioni, e resta salvata. Cambiano il nome dell'auto nei testi
 * ("Portami alla MINI") e il colore dell'accento: azzurro MINI, giallo BMW, rosso Supra.
 * Finche' non c'e' una marca si parla di "auto" con un accento neutro.
 */
object Marca {

    const val APP = "NBT Plus"

    class Tipo(
        val codice: String,     // "mini", "bmw", "supra" (salvato nelle preferenze); "" = nessuna
        val auto: String,       // nome nei testi
        val accento: Int,       // colore del "+" e dei tasti
        val suAccento: Int      // testo sopra il colore (nero su azzurro, giallo e grigio chiaro, bianco sul rosso)
    )

    val MINI = Tipo("mini", "MINI", 0xFF29B6F6.toInt(), 0xFF000000.toInt())
    val BMW = Tipo("bmw", "BMW", 0xFFFFCE00.toInt(), 0xFF000000.toInt())
    val SUPRA = Tipo("supra", "Supra", 0xFFEB0A1E.toInt(), 0xFFFFFFFF.toInt())
    val NESSUNA = Tipo("", "Auto", 0xFFE8ECF0.toInt(), 0xFF000000.toInt())
    val TUTTE = listOf(MINI, BMW, SUPRA)

    @Volatile var attuale: Tipo = NESSUNA

    val AUTO: String get() = attuale.auto
    val ACCENTO: Int get() = attuale.accento
    val SU_ACCENTO: Int get() = attuale.suAccento

    /** "alla MINI" / "all'auto" */
    fun alla(): String = if (attuale === NESSUNA) "all'auto" else "alla ${attuale.auto}"

    /** "della MINI" / "dell'auto" */
    fun della(): String = if (attuale === NESSUNA) "dell'auto" else "della ${attuale.auto}"

    /** "dalla MINI" / "dall'auto" */
    fun dalla(): String = if (attuale === NESSUNA) "dall'auto" else "dalla ${attuale.auto}"

    fun daCodice(codice: String?): Tipo = TUTTE.firstOrNull { it.codice == codice } ?: NESSUNA

    /** legge la marca salvata: va chiamata all'avvio di schermata, servizio e ricevitore Bluetooth */
    fun carica(ctx: Context) {
        attuale = daCodice(ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).getString("marca", null))
    }

    fun imposta(ctx: Context, tipo: Tipo) {
        attuale = tipo
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putString("marca", tipo.codice).apply()
    }
}
