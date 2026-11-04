package it.deathwalker.idriveponte

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * La foto dell'auto con gli 11 punti di ancoraggio della pagina "Porte, finestrini e tetto", da spostare col dito.
 * - si trascina il punto piu' vicino al dito; toccando la foto lontano dai punti ci si sposta il punto scelto;
 * - tenendo premuto un punto lo si segna "non visibile" (lato che la foto non mostra: anello vuoto), e viceversa.
 * I punti sono in coordinate da 0 a 1 sulla foto intera, come in Foto.Punto.
 */
@SuppressLint("ViewConstructor")
class CalibraPunti(
    ctx: Context,
    private val foto: Bitmap,
    iniziali: Map<DatiAuto.Parte, Foto.Punto>,
    private val accento: Int,
    private val quandoCambia: (DatiAuto.Parte) -> Unit,
) : View(ctx) {

    val punti = LinkedHashMap(iniziali)
    var scelto: DatiAuto.Parte = DatiAuto.Parte.values().first()
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val area = RectF()
    private var trascina: DatiAuto.Parte? = null

    private val pFoto = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pPunto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.WHITE }
    private val pNome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * dp; isFakeBoldText = true; setShadowLayer(3f * dp, 0f, 1f * dp, Color.BLACK)
    }

    private val gesti = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            val p = vicino(e.x, e.y) ?: return
            val q = punti[p] ?: return
            punti[p] = Foto.Punto(q.x, q.y, !q.nascosto)
            scelto = p
            trascina = null
            quandoCambia(p)
            invalidate()
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * foto.height.toFloat() / foto.width).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        area.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    private fun xy(q: Foto.Punto) = (area.left + q.x * area.width()) to (area.top + q.y * area.height())

    private fun vicino(x: Float, y: Float): DatiAuto.Parte? =
        punti.entries.map { (p, q) -> val (px, py) = xy(q); p to hypot(px - x, py - y) }
            .filter { it.second < 28f * dp }.minByOrNull { it.second }?.first

    override fun onDraw(c: Canvas) {
        c.drawBitmap(foto, null, area, pFoto)
        for ((p, q) in punti) {
            val (x, y) = xy(q)
            val r = (if (p == scelto) 10f else 7f) * dp
            pPunto.color = if (p == scelto) accento else Color.rgb(102, 187, 106)
            if (q.nascosto) {
                pPunto.style = Paint.Style.STROKE; pPunto.strokeWidth = 3f * dp
                c.drawCircle(x, y, r, pPunto)
            } else {
                pPunto.style = Paint.Style.FILL
                c.drawCircle(x, y, r, pPunto)
                c.drawCircle(x, y, r, pBordo)
            }
        }
        // il nome del punto scelto, accanto al punto (dentro la foto)
        punti[scelto]?.let { q ->
            val (x, y) = xy(q)
            val w = pNome.measureText(scelto.etichetta)
            val tx = (x + 14f * dp).coerceAtMost(area.right - w - 4f * dp).coerceAtLeast(4f * dp)
            val ty = if (y < 30f * dp) y + 28f * dp else y - 14f * dp
            c.drawText(scelto.etichetta, tx, ty, pNome)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesti.onTouchEvent(e)
        val x = e.x.coerceIn(area.left, area.right)
        val y = e.y.coerceIn(area.top, area.bottom)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                trascina = vicino(x, y)
                trascina?.let { scelto = it; quandoCambia(it) }
            }
            MotionEvent.ACTION_MOVE -> trascina?.let { p -> sposta(p, x, y) }
            MotionEvent.ACTION_UP -> {
                // tocco lontano dai punti: il punto scelto va li'
                if (trascina == null && e.eventTime - e.downTime < 400) sposta(scelto, x, y)
                trascina = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> trascina = null
        }
        return true
    }

    private fun sposta(p: DatiAuto.Parte, x: Float, y: Float) {
        val q = punti[p] ?: Foto.Punto(0.5f, 0.5f, false)
        punti[p] = Foto.Punto(((x - area.left) / area.width()).coerceIn(0f, 1f), ((y - area.top) / area.height()).coerceIn(0f, 1f), q.nascosto)
        invalidate()
    }
}
