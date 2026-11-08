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
        private const val OGNI_MS = 60 * 60 * 1000L
        private const val FLESSIBILITA_MS = 20 * 60 * 1000L

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
            if (modo == "no") { js.cancel(ID); return }
            val rete = if (modo == "wifi") JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
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
        }
    }

    @Volatile private var fermato = false

    override fun onStartJob(params: JobParameters): Boolean {
        if (Stato.servizioAttivo || modo(this) == "no") return false
        fermato = false
        Thread({
            try {
                Cache.carica(this)
                Preparazione.aggiorna(this, Disegno(this), false) { !fermato && !Stato.servizioAttivo }
                Cache.salva(this)
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
