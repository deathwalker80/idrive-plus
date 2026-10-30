package it.deathwalker.idriveponte

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale

/**
 * Posizione del telefono per il meteo "dove sei": ultima posizione nota (nessun GPS acceso apposta,
 * mentre guidi con Android Auto la posizione e' sempre fresca). Se manca, si usa la citta' salvata nell'app.
 */
object Posizione {

    private const val VECCHIA_MS = 6 * 60 * 60 * 1000L

    fun permesso(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun permessoSempre(ctx: Context): Boolean =
        permesso(ctx) && ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** la posizione piu' recente tra quelle che il telefono conosce gia', se non piu' vecchia di 6 ore */
    fun ultima(ctx: Context): Location? {
        if (!permesso(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val fornitori = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) fornitori.add(LocationManager.FUSED_PROVIDER)
        fornitori.add(LocationManager.NETWORK_PROVIDER)
        fornitori.add(LocationManager.GPS_PROVIDER)
        fornitori.add(LocationManager.PASSIVE_PROVIDER)
        var migliore: Location? = null
        for (f in fornitori) {
            val l = try { lm.getLastKnownLocation(f) } catch (e: Exception) { null } ?: continue
            if (migliore == null || l.time > migliore.time) migliore = l
        }
        val m = migliore ?: return null
        return if (System.currentTimeMillis() - m.time < VECCHIA_MS) m else null
    }

    /** citta' per il meteo: posizione del telefono con il nome del comune, altrimenti null */
    fun citta(ctx: Context): Citta? {
        val l = ultima(ctx) ?: return null
        val nome = try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale.ITALY).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        } catch (e: Exception) {
            null
        }
        return Citta(nome ?: "Posizione attuale", l.latitude, l.longitude)
    }
}
