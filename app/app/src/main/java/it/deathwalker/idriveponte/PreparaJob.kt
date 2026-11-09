package it.deathwalker.idriveponte

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Pagine pronte anche lontano dall'auto: circa ogni ora (quando decide Android, con la rete e la batteria non
 * scarica) scarica meteo, notizie, notizie locali, carburanti e traffico e le salva (Cache), cosi' quando sali in
 * macchina l'iDrive le ha subito. Con l'auto collegata non fa niente: ci pensa il servizio.
 * Impostazioni: "si" (qualsiasi rete), "wifi" (solo Wi-Fi o reti senza limiti), "no".
 */
class PreparaJob : JobService() {

    companion object {
        private const val ID = 7
        private const val ID_SUBITO = 8
        private const val OGNI_MS = 60 * 60 * 1000L
        private const val FLESSIBILITA_MS = 20 * 60 * 1000L
        private const val VECCHIO_MS = 90 * 60 * 1000L

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

        /** l'ultima volta che ha preparato le pagine lontano dall'auto (0 = mai) e cosa ha aggiornato */
        fun ultimo(ctx: Context): Pair<Long, String> =
            prefs(ctx).getLong("prepara_quando", 0L) to (prefs(ctx).getString("prepara_esito", "") ?: "")

        private fun rete(modo: String) = if (modo == "wifi") JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY

        /** un giro appena c'e' la rete, senza aspettare l'ora (app appena installata o aperta dopo tanto) */
        fun subito(ctx: Context) {
            val modo = modo(ctx)
            if (modo == "no" || Stato.servizioAttivo) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(
                JobInfo.Builder(ID_SUBITO, ComponentName(ctx, PreparaJob::class.java))
                    .setRequiredNetworkType(rete(modo))
                    .build()
            )
        }

        /** all'apertura dell'app: se l'ultimo giro e' di piu' di un'ora e mezza fa, uno subito */
        fun forseSubito(ctx: Context) {
            if (System.currentTimeMillis() - ultimo(ctx).first > VECCHIO_MS) subito(ctx)
        }

        fun modo(ctx: Context): String =
            ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).getString("prepara", "si") ?: "si"

        fun imposta(ctx: Context, modo: String) {
            ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putString("prepara", modo).apply()
            programma(ctx, rifai = true)
        }

        /** programma (o toglie) il lavoro; rifai = false lascia stare quello gia' programmato col modo giusto */
        fun programma(ctx: Context, rifai: Boolean = false) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val modo = modo(ctx)
            if (modo == "no") { js.cancel(ID); js.cancel(ID_SUBITO); return }
            val rete = rete(modo)
            val esistente = js.getPendingJob(ID)
            @Suppress("DEPRECATION")
            if (!rifai && esistente != null && esistente.networkType == rete) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, PreparaJob::class.java))
                    .setRequiredNetworkType(rete)
                    .setRequiresBatteryNotLow(true)
                    .setPeriodic(OGNI_MS, FLESSIBILITA_MS)
                    .setPersisted(true)
                    .build()
            )
            subito(ctx)
        }
    }

    @Volatile private var fermato = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (Stato.servizioAttivo || modo(this) == "no") return false
        fermato = false
        Marca.carica(this)                  // MINI, BMW o Supra: colori e nomi delle pagine
        Thread({
            try {
                val inizio = System.currentTimeMillis()
                Cache.carica(this)
                Preparazione.aggiorna(this, Disegno(this), false) { !fermato && !Stato.servizioAttivo }
                Cache.salva(this)
                // cosa e' stato aggiornato (o perche' no): si vede nelle Impostazioni dell'app
                val fatte = listOf("meteo" to Stato.meteo.ultimo, "notizie" to Stato.notizie.ultimo,
                    "traffico" to Stato.traffico.ultimo, "carburanti" to Stato.carburanti.ultimo,
                    "notizie locali" to Stato.locali.ultimo).filter { it.second >= inizio }.map { it.first }
                val errori = listOf(Stato.meteo.esito, Stato.notizie.esito, Stato.traffico.esito, Stato.carburanti.esito,
                    Stato.locali.esito).count { it.startsWith("ERRORE") }
                val esito = when {
                    fatte.isNotEmpty() -> fatte.joinToString(", ")
                    errori > 0 -> "nessuna sezione: errori di rete"
                    else -> "niente da rifare, era tutto recente"
                }
                prefs(this).edit().putLong("prepara_quando", System.currentTimeMillis()).putString("prepara_esito", esito).apply()
            } catch (e: Exception) {
                // la prossima volta
            } finally {
                Stato.aggiornamentoInCorso = ""
                jobFinished(params, false)
            }
        }, "prepara").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        fermato = true
        return false
    }
}
