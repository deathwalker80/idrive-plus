package it.deathwalker.idriveponte

/**
 * Stato condiviso tra servizio, server HTTP e schermata.
 * I dati pronti per l'iDrive sono sempre sostituiti in blocco (mai modificati sul posto),
 * cosi' il server puo' leggerli da un altro thread senza lock.
 */
class NotiziaPronta(
    val titolo: String,
    val ora: String,
    val pagina: ByteArray,
    val mini: ByteArray,
    val anteprima: ByteArray
)

/** una pagina singola per l'iDrive (Meteo, Carburanti, Traffico) */
class Pagina(val nome: String) {
    @Volatile var png: ByteArray? = null
    @Volatile var epoch = 0L                    // secondi; cambia a ogni nuova pagina
    @Volatile var esito = "mai aggiornato"
    @Volatile var ultimo = 0L                   // millisecondi dell'ultimo tentativo riuscito

    fun metti(nuova: ByteArray) {
        png = nuova
        epoch = System.currentTimeMillis() / 1000
    }
}

/** un elenco di notizie per l'iDrive (Notizie, Notizie locali) */
class Elenco(val nome: String) {
    @Volatile var notizie: List<NotiziaPronta> = emptyList()
    @Volatile var epoch = 0L
    @Volatile var esito = "mai aggiornate"
    @Volatile var ultimo = 0L

    fun metti(nuove: List<NotiziaPronta>) {
        notizie = nuove
        epoch = System.currentTimeMillis() / 1000
    }
}

object Stato {
    const val VERSIONE = BuildConfig.VERSION_NAME
    const val PORTA = 8765

    @Volatile var servizioAttivo = false
    @Volatile var avviatoDa = ""                // "Bluetooth <nome auto>" oppure "a mano"

    val meteo = Pagina("Meteo")
    val carburanti = Pagina("Carburanti")
    val traffico = Pagina("Traffico")
    val notizie = Elenco("Notizie")
    val locali = Elenco("Notizie locali")

    @Volatile var aggiornamentoInCorso = ""     // cosa si sta scaricando adesso ("" = niente)
    @Volatile var fonteCitta = ""
    @Volatile var allerta = ""                  // riassunto dell'ultima allerta letta

    @Volatile var wifiAuto: String? = null      // IP del telefono sul Wi-Fi della macchina
    @Volatile var ultimaRichiestaAuto = 0L      // millisecondi
    @Volatile var ipAuto = ""
    @Volatile var richiesteAuto = 0L
    @Volatile var erroreServer = ""
}
