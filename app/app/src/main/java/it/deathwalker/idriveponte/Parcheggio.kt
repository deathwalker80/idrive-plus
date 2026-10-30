package it.deathwalker.idriveponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dove ho parcheggiato: quando il telefono si scollega dal Bluetooth dell'auto si salva la posizione
 * (l'ultima nota del telefono, fresca perche' fino a un attimo prima eri in auto) e compare una notifica
 * con "Portami alla MINI/BMW/Supra" (navigazione a piedi).
 */
object Parcheggio {

    private const val CANALE = "parcheggio"
    private const val ID_NOTIFICA = 2
    private const val FRESCA_MS = 15 * 60 * 1000L

    class Posto(val lat: Double, val lon: Double, val quando: Long, val indirizzo: String, val auto: String, val precisione: Float)

    fun salva(ctx: Context, auto: String): Posto? {
        val l = Posizione.ultima(ctx) ?: return null
        if (System.currentTimeMillis() - l.time > FRESCA_MS) return null       // posizione troppo vecchia: meglio niente
        val indirizzo = try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.ITALY).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            if (a == null) "" else listOfNotNull(
                listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifEmpty { null },
                a.locality
            ).joinToString(", ")
        } catch (e: Exception) {
            ""
        }
        val p = Posto(l.latitude, l.longitude, System.currentTimeMillis(), indirizzo, auto, if (l.hasAccuracy()) l.accuracy else 0f)
        ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit()
            .putFloat("park_lat", p.lat.toFloat()).putFloat("park_lon", p.lon.toFloat())
            .putLong("park_quando", p.quando).putString("park_indirizzo", p.indirizzo)
            .putString("park_auto", p.auto).putFloat("park_precisione", p.precisione)
            .apply()
        notifica(ctx, p)
        return p
    }

    fun ultimo(ctx: Context): Posto? {
        val pr = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)
        val quando = pr.getLong("park_quando", 0L)
        if (quando == 0L) return null
        return Posto(
            pr.getFloat("park_lat", 0f).toDouble(), pr.getFloat("park_lon", 0f).toDouble(), quando,
            pr.getString("park_indirizzo", "") ?: "", pr.getString("park_auto", "") ?: "", pr.getFloat("park_precisione", 0f)
        )
    }

    fun descrizione(p: Posto): String {
        val ora = SimpleDateFormat("dd/MM 'alle' HH:mm", Locale.ITALY).format(Date(p.quando))
        val dove = p.indirizzo.ifEmpty { String.format(Locale.ITALY, "%.5f, %.5f", p.lat, p.lon) }
        return "$dove\nparcheggiata il $ora" + (if (p.precisione > 0) String.format(Locale.ITALY, " (precisione %.0f m)", p.precisione) else "")
    }

    /** navigazione a piedi fino all'auto (Google Maps), o in ripiego qualsiasi app di mappe */
    fun portami(ctx: Context, p: Posto) {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=w"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(maps)
        } catch (e: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Marca.AUTO})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun notifica(ctx: Context, p: Posto) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            val ch = NotificationChannel(CANALE, "Dove ho parcheggiato", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val vai = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Marca.AUTO})"))
        val pi = PendingIntent.getActivity(ctx, 1, vai, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val apri = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_parcheggio)
            .setContentTitle("${p.auto.ifEmpty { Marca.AUTO }} parcheggiata")
            .setContentText(p.indirizzo.ifEmpty { "Tocca per vedere dove" })
            .setContentIntent(apri)
            .addAction(Notification.Action.Builder(null, "Portami alla ${Marca.AUTO}", pi).build())
            .setAutoCancel(false)
            .build()
        nm.notify(ID_NOTIFICA, n)
    }
}
