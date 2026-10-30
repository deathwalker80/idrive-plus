package it.deathwalker.idriveponte

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Accende l'app quando il telefono si collega al Bluetooth di una MINI, BMW o Toyota Supra; quando si scollega
 * lo spegne e salva dove hai parcheggiato. Le auto si riconoscono dal nome Bluetooth ("MINI 12345", "BMW 67890", "Supra 24680", ...),
 * e dallo stesso nome si ricava la marca (colori e testi dell'app).
 */
class AutoBtReceiver : BroadcastReceiver() {

    companion object {
        /** il nome Bluetooth delle head unit e' "MINI", "BMW", "Supra" o "Toyota" seguito dal numero (es. "MINI28359",
         *  "BMW 12345", "Toyota Supra 24680"); maiuscole o minuscole non contano.
         *  cosi' non si confondono altri dispositivi col nome che contiene "Mini" (es. "Saphe Drive Mini") */
        private val NOME_AUTO = Regex("^(MINI|BMW|SUPRA|TOYOTA)[ _-]?(SUPRA[ _-]?)?\\d{3,}.*$", RegexOption.IGNORE_CASE)

        fun eAuto(nome: String?): Boolean {
            val n = nome?.trim()?.uppercase() ?: return false
            return NOME_AUTO.matches(n)
        }

        /** marca dal nome Bluetooth: MINI, BMW, oppure Supra per "Supra..." e "Toyota..."; null se non e' un'auto */
        fun marca(nome: String?): Marca.Tipo? {
            val n = nome?.trim()?.uppercase() ?: return null
            val m = NOME_AUTO.matchEntire(n) ?: return null
            return when (m.groupValues[1]) {
                "MINI" -> Marca.MINI
                "BMW" -> Marca.BMW
                else -> Marca.SUPRA
            }
        }

        fun registra(ctx: Context, testo: String) {
            ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE).edit()
                .putString("ultimo_bt", testo + " - " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY).format(java.util.Date()))
                .apply()
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val nome = try { dev?.name } catch (e: SecurityException) { null }
        val tipo = marca(nome) ?: return
        Marca.carica(ctx)

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (tipo !== Marca.attuale) Marca.imposta(ctx, tipo)
                try {
                    PonteService.avvia(ctx, "Bluetooth $nome")
                    registra(ctx, "acceso da $nome")
                } catch (e: Exception) {
                    // Android puo' negare l'avvio dallo sfondo: si vede nell'app (Batteria senza restrizioni)
                    registra(ctx, "NON acceso da $nome (${e.javaClass.simpleName})")
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                PonteService.ferma(ctx)
                // posizione e indirizzo richiedono un attimo: si lavora fuori dal thread principale
                val attesa = goAsync()
                Thread {
                    try {
                        val p = Parcheggio.salva(ctx, nome ?: Marca.AUTO)
                        registra(ctx, "spento, $nome scollegata" + if (p != null) " - parcheggio salvato" else " - posizione non disponibile")
                    } finally {
                        attesa.finish()
                    }
                }.start()
            }
        }
    }
}
