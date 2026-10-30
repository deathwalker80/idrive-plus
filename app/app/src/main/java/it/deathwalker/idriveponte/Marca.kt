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
}
