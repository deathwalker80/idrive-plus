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
    val anteprima: ByteArray,
    /** versione della riga (Stato vettura): il plugin riscarica pagina e miniatura solo quando cambia; 0 = sempre */
    val versione: Long = 0L,
)

/** una pagina singola per l'iDrive (Meteo, Carburanti, Traffico, Stato auto) */
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

/** un elenco per l'iDrive (Notizie, Notizie locali, Stato vettura) */
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
    val auto = Pagina("Stato auto")
    val notizie = Elenco("Notizie")
    val locali = Elenco("Notizie locali")
    /** Stato vettura: riga 0 Riepilogo, riga 1 Porte, finestrini e tetto (plugin Auto v9) */
    val vettura = Elenco("Stato vettura")

    /** quando l'app ha ripreso a raccogliere i dati dell'auto (secondi, DatiAuto.azzera): se cambia, il plugin
     *  capisce che il telefono e' ripartito da zero e rimanda tutti gli ultimi valori, anche quelli fermi */
    @Volatile var avvio = System.currentTimeMillis() / 1000

    /** in breve, per le icone della home dell'app: temperatura e cielo, il distributore migliore, il traffico */
    class MeteoBreve(val citta: String, val temp: Int, val cielo: String, val icona: String)
    @Volatile var meteoBreve: MeteoBreve? = null
    @Volatile var carburantiBreve: Pair<String, String>? = null     // "Benzina 1,975 €" to "Q8 · 1,2 km"
    @Volatile var trafficoBreve: Pair<String, String>? = null       // "3 eventi entro 100 km" to "A12: coda 2 km"

    @Volatile var aggiornamentoInCorso = ""     // cosa si sta scaricando adesso ("" = niente)
    @Volatile var fonteCitta = ""
    @Volatile var allerta = ""                  // riassunto dell'ultima allerta letta

    @Volatile var wifiAuto: String? = null      // IP del telefono sul Wi-Fi della macchina
    @Volatile var ultimaRichiestaAuto = 0L      // millisecondi
    @Volatile var ipAuto = ""
    @Volatile var richiesteAuto = 0L
    @Volatile var erroreServer = ""
}
