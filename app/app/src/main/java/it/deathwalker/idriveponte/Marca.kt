package it.deathwalker.idriveponte

/**
 * Quello che cambia tra MINI Plus, BMW Plus e Supra Plus: arriva dalla marca scelta in app/build.gradle
 * (productFlavors mini / bmw / supra). Tutto il resto del codice e' lo stesso.
 */
object Marca {
    const val AUTO = BuildConfig.MARCA_AUTO               // "MINI", "BMW", "Supra": nei testi ("Portami alla ...")
    const val APP = BuildConfig.MARCA_APP                 // "MINI Plus", "BMW Plus", "Supra Plus"
    const val ACCENTO = BuildConfig.MARCA_ACCENTO         // colore del "+": azzurro MINI, giallo BMW, rosso Supra
    const val SU_ACCENTO = BuildConfig.MARCA_SU_ACCENTO   // testo sopra il colore (nero su azzurro e giallo, bianco sul rosso)

    /** marca di un'auto del parco dal suo nome Bluetooth ("BMW 67890" -> "BMW"); senza nome, quella dell'app */
    fun di(bt: String?): String {
        val b = bt?.trim()?.uppercase().orEmpty()
        return when {
            b.startsWith("MINI") -> "MINI"
            b.startsWith("BMW") -> "BMW"
            b.startsWith("SUPRA") || b.startsWith("TOYOTA") -> "Supra"
            else -> AUTO
        }
    }

    /** "alla MINI" / "dalla BMW": la marca dell'auto bt, o quella dell'app */
    fun alla(bt: String? = null): String = "alla ${di(bt)}"

    fun dalla(bt: String? = null): String = "dalla ${di(bt)}"
}
