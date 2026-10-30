package it.deathwalker.idriveponte

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/** Download con la connessione del telefono che ha davvero internet (dati mobili o Wi-Fi di casa). */
object Rete {

    private const val AGENTE = "Mozilla/5.0 (Linux; Android) iDrivePonte/" + Stato.VERSIONE

    /** La rete del telefono con internet verificato: mai il Wi-Fi della macchina, che internet non ce l'ha. */
    fun reteConInternet(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val tutte = cm.allNetworks
        var ripiego: Network? = null
        for (n in tutte) {
            val cap = cm.getNetworkCapabilities(n) ?: continue
            if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return n
            if (ripiego == null) ripiego = n
        }
        return ripiego ?: cm.activeNetwork
    }

    fun scarica(ctx: Context, indirizzo: String, timeoutMs: Int = 10000, massimo: Int = 6 * 1024 * 1024): ByteArray {
        val url = URL(indirizzo)
        val rete = reteConInternet(ctx)
        val conn = (rete?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", AGENTE)
            conn.setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
            val codice = conn.responseCode
            if (codice !in 200..299) throw IllegalStateException("HTTP $codice da ${url.host}")
            conn.inputStream.use { ingresso ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = ingresso.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > massimo) throw IllegalStateException("risposta troppo grande da ${url.host}")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun scaricaTesto(ctx: Context, indirizzo: String, timeoutMs: Int = 10000): String =
        String(scarica(ctx, indirizzo, timeoutMs), Charsets.UTF_8)

    /** IP del telefono sul Wi-Fi della macchina (172.16.224.x Android Auto wireless, 172.16.222.x Wi-Fi Direct). */
    fun ipWifiAuto(): String? {
        return try {
            val interfacce = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfacce) {
                if (!ni.isUp) continue
                for (a in ni.inetAddresses) {
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (ip.startsWith("172.16.224.") || ip.startsWith("172.16.222.")) return ip
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
