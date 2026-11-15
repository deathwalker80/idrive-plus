package it.deathwalker.idriveponte

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit

/**
 * Toglie lo sfondo alla foto dell'auto scelta dall'utente, sul telefono (ML Kit Subject Segmentation, Google Play
 * services): la foto non esce dal telefono. Il modello lo scarica Google Play services la prima volta.
 * Si chiama da un thread (non dal thread dello schermo): aspetta il modello e il risultato.
 */
object Scontorno {

    class NonRiuscito(motivo: String) : Exception(motivo)

    private const val ATTESA_MODELLO_S = 120L
    private const val ATTESA_RISULTATO_S = 60L

    fun togliSfondo(ctx: Context, foto: Bitmap): Bitmap {
        val opzioni = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        val segmentatore = try {
            SubjectSegmentation.getClient(opzioni)
        } catch (e: Throwable) {
            throw NonRiuscito("servizi Google Play non disponibili su questo telefono")
        }
        try {
            // il modello: se non c'e' ancora lo scarica Google Play services (e si aspetta)
            try {
                val moduli = ModuleInstall.getClient(ctx)
                val disponibile = Tasks.await(moduli.areModulesAvailable(segmentatore), ATTESA_MODELLO_S, TimeUnit.SECONDS).areModulesAvailable()
                if (!disponibile) {
                    Tasks.await(moduli.installModules(ModuleInstallRequest.newBuilder().addApi(segmentatore).build()), ATTESA_MODELLO_S, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                throw NonRiuscito("il telefono non riesce a scaricare il modello (${e.javaClass.simpleName}); serve la connessione")
            }
            val risultato = try {
                Tasks.await(segmentatore.process(InputImage.fromBitmap(foto, 0)), ATTESA_RISULTATO_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw NonRiuscito("errore del riconoscimento (${e.javaClass.simpleName})")
            }
            return risultato.foregroundBitmap ?: throw NonRiuscito("nessuna auto riconosciuta nella foto")
        } finally {
            try { segmentatore.close() } catch (e: Exception) { }
        }
    }
}
