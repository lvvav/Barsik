package com.barsik.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

// Кот на экране: спит (глаза закрыты, плывут «z») и просыпается (глаза открыты, при слушании — пульс)
class CatView(ctx: Context) : View(ctx) {

    enum class State { SLEEP, AWAKE, LISTEN }

    var state = State.SLEEP
        private set

    private val fill = Paint(Paint.ANTI_ALIAS).apply { color = Color.parseColor("#F2B77A") }
    private val ink = Paint(Paint.ANTI_ALIAS).apply {
        color = Color.parseColor("#1A120C")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val inkFill = Paint(Paint.ANTI_ALIAS).apply { color = Color.parseColor("#1A120C") }
    private val pulse = Paint(Paint.ANTI_ALIAS).apply {
        color = Color.parseColor("#F2B77A")
        style = Paint.Style.STROKE
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS).apply { color = Color.parseColor("#F5EBE0") }

    private var t = 0f

    init {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = it.animatedFraction; invalidate() }
            start()
        }
    }

    fun setState(s: State) {
        if (state != s) {
            state = s
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return
        val cx = w / 2
        val cy = h * 0.55f
        val wave = sin(t * 2 * PI).toFloat()
        // Дыхание: во сне медленнее и глубже, когда не спит — почти незаметно
        val breath = if (state == State.SLEEP) 1f + wave * 0.035f else 1f + wave * 0.012f

        c.save()
        c.scale(breath, breath, cx, cy)

        // Уши
        val leftEar = Path().apply {
            moveTo(cx - w * 0.34f, cy - h * 0.06f)
            lineTo(cx - w * 0.30f, cy - h * 0.38f)
            lineTo(cx - w * 0.06f, cy - h * 0.20f)
            close()
        }
        val rightEar = Path().apply {
            moveTo(cx + w * 0.34f, cy - h * 0.06f)
            lineTo(cx + w * 0.30f, cy - h * 0.38f)
            lineTo(cx + w * 0.06f, cy - h * 0.20f)
            close()
        }
        c.drawPath(leftEar, fill)
        c.drawPath(rightEar, fill)

        // Голова
        c.drawOval(RectF(cx - w * 0.36f, cy - h * 0.26f, cx + w * 0.36f, cy + h * 0.30f), fill)

        val eyeY = cy - h * 0.02f
        val eyeX = w * 0.15f
        val eyeR = w * 0.045f
        ink.strokeWidth = w * 0.03f
        if (state == State.SLEEP) {
            // Закрытые глаза: дуги
            c.drawArc(RectF(cx - eyeX - eyeR, eyeY - eyeR, cx - eyeX + eyeR, eyeY + eyeR), 200f, 140f, false, ink)
            c.drawArc(RectF(cx + eyeX - eyeR, eyeY - eyeR, cx + eyeX + eyeR, eyeY + eyeR), 200f, 140f, false, ink)
        } else {
            c.drawCircle(cx - eyeX, eyeY, eyeR * 1.2f, inkFill)
            c.drawCircle(cx + eyeX, eyeY, eyeR * 1.2f, inkFill)
        }

        // Нос и рот
        c.drawCircle(cx, cy + h * 0.08f, w * 0.03f, inkFill)
        if (state == State.LISTEN) {
            c.drawCircle(cx, cy + h * 0.16f, w * 0.035f, inkFill)
        } else {
            c.drawArc(RectF(cx - w * 0.08f, cy + h * 0.10f, cx + w * 0.08f, cy + h * 0.22f), 20f, 140f, false, ink)
        }
        c.restore()

        // Пульс вокруг кота, пока он слушает
        if (state == State.LISTEN) {
            pulse.strokeWidth = w * 0.02f
            pulse.alpha = ((1f - t) * 200).toInt()
            c.drawCircle(cx, cy, w * (0.44f + t * 0.16f), pulse)
        }

        // Спит — над головой плывут «z»
        if (state == State.SLEEP) {
            zPaint.textSize = w * 0.16f
            for (i in 0 until 2) {
                val phase = (t + i * 0.5f) % 1f
                zPaint.alpha = ((1f - phase) * 220).toInt()
                c.drawText("z", cx + w * 0.30f + i * w * 0.08f, cy - h * 0.34f - phase * h * 0.2f, zPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}
