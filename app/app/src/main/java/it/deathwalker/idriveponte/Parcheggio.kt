package it.deathwalker.idriveponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dove ho parcheggiato, per ogni auto del parco: quando il telefono si scollega dal Bluetooth di un'auto si salva
 * la sua posizione (l'ultima nota del telefono, fresca perche' fino a un attimo prima eri in auto) e compare una
 * notifica con "Portami alla MINI/BMW/Supra" (navigazione a piedi). Scendere da un'auto non tocca il parcheggio
 * delle altre. "" = auto senza nome Bluetooth leggibile.
 */
object Parcheggio {

    private const val CANALE = "parcheggio"
    private const val ID_NOTIFICA = 2
    private const val FRESCA_MS = 15 * 60 * 1000L
    private val CAMPI = listOf("lat", "lon", "quando", "indirizzo", "precisione")

    class Posto(val lat: Double, val lon: Double, val quando: Long, val indirizzo: String, val auto: String, val precisione: Float)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    private fun k(campo: String, bt: String) = "park_$campo:$bt"

    /** il parcheggio unico delle versioni precedenti passa all'auto a cui apparteneva */
    private fun migra(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains("park_quando")) return
        val vecchia = p.getString("park_auto", "") ?: ""
        val bt = AutoBtReceiver.trovaAuto(vecchia) ?: p.getString("auto_bt", null) ?: ""
        p.edit()
            .putFloat(k("lat", bt), p.getFloat("park_lat", 0f)).putFloat(k("lon", bt), p.getFloat("park_lon", 0f))
            .putLong(k("quando", bt), p.getLong("park_quando", 0L)).putString(k("indirizzo", bt), p.getString("park_indirizzo", "") ?: "")
            .putFloat(k("precisione", bt), p.getFloat("park_precisione", 0f))
            .remove("park_lat").remove("park_lon").remove("park_quando").remove("park_indirizzo").remove("park_auto").remove("park_precisione")
            .apply()
        if (bt.isNotEmpty()) Parco.aggiungi(ctx, bt)                            // l'auto entra nel parco (se non c'era)
    }

    fun salva(ctx: Context, auto: String?): Posto? {
        val bt = auto?.trim().orEmpty()
        val l = Posizione.ultima(ctx) ?: return null
        if (System.currentTimeMillis() - l.time > FRESCA_MS) return null       // posizione troppo vecchia: meglio niente
        val indirizzo = Posizione.indirizzo(ctx, l.latitude, l.longitude)
        migra(ctx)
        val p = Posto(l.latitude, l.longitude, System.currentTimeMillis(), indirizzo, bt, if (l.hasAccuracy()) l.accuracy else 0f)
        prefs(ctx).edit()
            .putFloat(k("lat", bt), p.lat.toFloat()).putFloat(k("lon", bt), p.lon.toFloat())
            .putLong(k("quando", bt), p.quando).putString(k("indirizzo", bt), p.indirizzo)
            .putFloat(k("precisione", bt), p.precisione)
            .apply()
        notifica(ctx, p)
        return p
    }

    /** parcheggio di un'auto, null se non ce n'e' */
    fun ultimo(ctx: Context, auto: String?): Posto? {
        migra(ctx)
        val bt = auto?.trim().orEmpty()
        val pr = prefs(ctx)
        val quando = pr.getLong(k("quando", bt), 0L)
        if (quando == 0L) return null
        return Posto(
            pr.getFloat(k("lat", bt), 0f).toDouble(), pr.getFloat(k("lon", bt), 0f).toDouble(), quando,
            pr.getString(k("indirizzo", bt), "") ?: "", bt, pr.getFloat(k("precisione", bt), 0f)
        )
    }

    /** i parcheggi di tutte le auto, dal piu' recente */
    fun tutti(ctx: Context): List<Posto> {
        migra(ctx)
        val auto = Parco.elenco(ctx) + ""
        return auto.mapNotNull { ultimo(ctx, it) }.sortedByDescending { it.quando }
    }

    fun cancella(ctx: Context, auto: String) {
        val bt = auto.trim()
        val e = prefs(ctx).edit()
        for (c in CAMPI) e.remove(k(c, bt))
        e.apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(bt, ID_NOTIFICA)
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
            ctx.startActivity(Intent(Intent.ACTION_VIEW, geo(ctx, p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun geo(ctx: Context, p: Posto): Uri =
        Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(Parco.mostra(ctx, p.auto).ifEmpty { Marca.AUTO })})")

    /** una notifica per auto (tag = nome Bluetooth): il parcheggio di un'auto non copre quello di un'altra */
    private fun notifica(ctx: Context, p: Posto) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            val ch = NotificationChannel(CANALE, "Dove ho parcheggiato", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val codice = p.auto.hashCode() and 0xffff
        val pi = PendingIntent.getActivity(ctx, 0x10000 + codice, Intent(Intent.ACTION_VIEW, geo(ctx, p)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val apri = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_parcheggio)
            .setContentTitle("${Parco.mostra(ctx, p.auto).ifEmpty { Marca.AUTO }} parcheggiata")
            .setContentText(p.indirizzo.ifEmpty { "Tocca per vedere dove" })
            .setContentIntent(apri)
            .addAction(Notification.Action.Builder(null, "Portami ${Marca.alla(p.auto)}", pi).build())
            .setAutoCancel(false)
            .build()
        nm.notify(p.auto, ID_NOTIFICA, n)
    }
}
