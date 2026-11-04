package it.deathwalker.idriveponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Avviso quando scendi dall'auto con qualcosa rimasto aperto: finestrini, tetto, portellone o cofano, dall'ultimo stato
 * che l'iDrive ha mandato prima dello scollegamento. Le porte non contano: quando scendi la tua e' aperta per forza.
 * Una notifica per auto (tag = nome Bluetooth); sparisce da sola se arriva lo stato "chiuso" o quando l'auto si ricollega.
 */
object Aperture {

    private const val CANALE = "aperture"
    private const val ID_NOTIFICA = 3
    /** stato troppo vecchio per dire com'e' l'auto adesso */
    private const val FRESCO_MS = 10 * 60 * 1000L

    /** cosa e' rimasto aperto (senza le porte); null = l'auto non ha mandato lo stato delle aperture */
    fun rimasti(i: DatiAuto.Istantanea): List<String>? = i.aperti?.filter { !it.startsWith("porta") }

    /** viaggio appena finito (o valori arrivati subito dopo): avvisa, aggiorna o toglie l'avviso */
    fun controlla(ctx: Context, d: Viaggio.Dati?) {
        if (d == null || d.inCorso) return
        if (System.currentTimeMillis() - d.aggiornato > FRESCO_MS) return
        val aperti = rimasti(d.ora()) ?: return
        if (aperti.isEmpty()) togli(ctx, d.auto) else avvisa(ctx, d.auto, aperti)
    }

    fun togli(ctx: Context, auto: String?) {
        ctx.getSystemService(NotificationManager::class.java).cancel(auto?.trim().orEmpty(), ID_NOTIFICA)
    }

    /** "finestrino ant. sx" -> "Finestrino ant. sx aperto"; piu' cose -> "Rimasti aperti: ..." */
    fun titolo(aperti: List<String>): String =
        if (aperti.size == 1) aperti[0].replaceFirstChar { it.uppercase() } + " aperto"
        else "Rimasti aperti: " + aperti.joinToString(", ")

    private fun avvisa(ctx: Context, auto: String, aperti: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            nm.createNotificationChannel(NotificationChannel(CANALE, "Aperture rimaste aperte", NotificationManager.IMPORTANCE_HIGH))
        }
        val nome = Parco.mostra(ctx, auto).ifEmpty { Marca.AUTO }
        val apri = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_auto)
            .setContentTitle("$nome: ${titolo(aperti)}")
            .setContentText("Ultimo stato mandato dall'iDrive, quando il telefono si è scollegato.")
            .setContentIntent(apri)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(auto, ID_NOTIFICA, n)
    }
}
