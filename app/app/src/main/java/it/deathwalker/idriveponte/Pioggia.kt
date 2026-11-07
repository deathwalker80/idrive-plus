package it.deathwalker.idriveponte

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

/**
 * Avviso "pioggia in arrivo e hai lasciato aperto": finche' un'auto parcheggiata ha finestrini, tetto, portellone o
 * cofano aperti (ultimo stato dell'iDrive, come l'avviso Aperture), ogni mezz'ora si guarda il meteo delle prossime
 * 3 ore dove e' parcheggiata. Se arriva pioggia (o neve, o temporale) avvisa una volta per episodio.
 * Si ferma da solo quando l'auto si ricollega, quando tutto e' chiuso o dopo 3 giorni di sosta.
 * Conta sempre tutte le parti, anche quelle spente nello Stato vettura. Si spegne dalle Impostazioni.
 */
object Pioggia {

    private const val CANALE = "pioggia"
    private const val ID_NOTIFICA = 4
    private const val OGNI_MS = 30 * 60 * 1000L
    private const val SOSTA_MAX_MS = 3 * 24 * 3600 * 1000L
    private const val ORE_AVANTI = 3

    fun attivo(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).getBoolean("avviso_pioggia", true)

    fun imposta(ctx: Context, si: Boolean) {
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit().putBoolean("avviso_pioggia", si).apply()
        if (si) programma(ctx, subito = true) else annulla(ctx)
    }

    private fun intento(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4, Intent(ctx, PioggiaReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** prossimo controllo (fra mezz'ora, o fra un minuto subito dopo il parcheggio) */
    fun programma(ctx: Context, subito: Boolean = false) {
        if (!attivo(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + if (subito) 60_000L else OGNI_MS, intento(ctx))
    }

    fun annulla(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(intento(ctx))
    }

    fun togli(ctx: Context, auto: String?) {
        ctx.getSystemService(NotificationManager::class.java).cancel(auto?.trim().orEmpty(), ID_NOTIFICA)
    }

    /** le auto parcheggiate da poco con qualcosa aperto, e dove sono */
    private fun daControllare(ctx: Context): List<Triple<String, List<String>, Parcheggio.Posto>> {
        val adesso = System.currentTimeMillis()
        return Parco.elenco(ctx).mapNotNull { bt ->
            val d = Viaggio.di(ctx, bt) ?: return@mapNotNull null
            if (d.inCorso || adesso - d.aggiornato > SOSTA_MAX_MS) return@mapNotNull null
            val aperti = Aperture.rimasti(d.ora())?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val posto = Parcheggio.ultimo(ctx, bt) ?: return@mapNotNull null
            Triple(bt, aperti, posto)
        }
    }

    /** chiamato dalla sveglia: controlla le auto, avvisa, e si riprogramma solo se serve ancora */
    fun controlla(ctx: Context) {
        if (!attivo(ctx)) return
        val auto = daControllare(ctx)
        if (auto.isEmpty()) return
        val prefs = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)
        for ((bt, aperti, posto) in auto) {
            val arrivo = try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${posto.lat}&longitude=${posto.lon}" +
                    "&current=weather_code,precipitation&hourly=weather_code,precipitation_probability,precipitation" +
                    "&forecast_hours=${ORE_AVANTI + 1}&timezone=Europe%2FRome"
                primaPioggia(Rete.scaricaTesto(ctx, url))
            } catch (e: Exception) {
                null
            } ?: continue
            // una volta per episodio: niente secondo avviso nelle 6 ore dopo il primo
            val ultimo = prefs.getLong("pioggia_avvisata:$bt", 0L)
            if (System.currentTimeMillis() - ultimo < 6 * 3600 * 1000L) continue
            prefs.edit().putLong("pioggia_avvisata:$bt", System.currentTimeMillis()).apply()
            avvisa(ctx, bt, arrivo, aperti, posto)
        }
        programma(ctx)
    }

    /**
     * dal JSON di open-meteo: "adesso" se sta gia' piovendo, altrimenti l'ora ("16:00") della prima ora delle
     * prossime 3 con pioggia, neve o temporale (codice 51 e oltre, o probabilita' da 60% con almeno 0,1 mm); null = niente
     */
    fun primaPioggia(testo: String): String? {
        val j = JSONObject(testo)
        j.optJSONObject("current")?.let { c ->
            if (c.optInt("weather_code", 0) >= 51 || c.optDouble("precipitation", 0.0) >= 0.1) return "adesso"
        }
        val h = j.optJSONObject("hourly") ?: return null
        val ore = h.optJSONArray("time") ?: return null
        val codici = h.optJSONArray("weather_code")
        val prob = h.optJSONArray("precipitation_probability")
        val mm = h.optJSONArray("precipitation")
        for (i in 0 until minOf(ore.length(), ORE_AVANTI + 1)) {
            val piove = (codici?.optInt(i, 0) ?: 0) >= 51 ||
                ((prob?.optInt(i, 0) ?: 0) >= 60 && (mm?.optDouble(i, 0.0) ?: 0.0) >= 0.1)
            if (piove) return ore.optString(i).substringAfter('T').take(5)
        }
        return null
    }

    private fun avvisa(ctx: Context, auto: String, arrivo: String, aperti: List<String>, posto: Parcheggio.Posto) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            nm.createNotificationChannel(NotificationChannel(CANALE, "Pioggia con l'auto aperta", NotificationManager.IMPORTANCE_HIGH))
        }
        val nome = Parco.mostra(ctx, auto).ifEmpty { Marca.AUTO }
        val quando = if (arrivo == "adesso") "sta piovendo" else "pioggia prevista alle $arrivo"
        val apri = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val testo = "Hai lasciato aperto: ${aperti.joinToString(", ")}" + if (posto.indirizzo.isNotEmpty()) " (${posto.indirizzo})" else ""
        val n = Notification.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_meteo)
            .setContentTitle("$nome: $quando")
            .setContentText(testo)
            .setStyle(Notification.BigTextStyle().bigText(testo))
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build()
        nm.notify(auto, ID_NOTIFICA, n)
    }
}

/** la sveglia del controllo pioggia: il meteo si scarica fuori dal thread principale */
class PioggiaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val attesa = goAsync()
        Thread {
            try {
                Pioggia.controlla(ctx)
            } finally {
                attesa.finish()
            }
        }.start()
    }
}
