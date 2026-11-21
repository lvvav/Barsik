package com.barsik.assistant

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Экран настроек в стиле запотевшего стекла: размытый фон, строки с цветными иконками
object Glass {

    class Row(
        val icon: String,
        val color: Int,
        val title: String,
        val sub: String? = null,
        val onTap: () -> Unit
    )

    fun show(act: Activity, title: String, rows: List<Row>) {
        val dp = { v: Int -> (v * act.resources.displayMetrics.density).toInt() }
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                // Матовое «запотевшее» стекло: плотная полупрозрачная заливка и светлая кромка
                setColor(Color.argb(205, 26, 28, 40))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
        }

        panel.addView(TextView(act).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Ui.font(act)
            setPadding(dp(6), 0, 0, dp(14))
        })

        rows.forEach { row ->
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.argb(22, 255, 255, 255))
                }
                setOnClickListener { d.dismiss(); row.onTap() }
            }
            // Иконка в скруглённом квадрате своего цвета
            val tile = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(row.color)
                }
                addView(TextView(act).apply {
                    text = row.icon
                    textSize = 20f
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            line.addView(tile, LinearLayout.LayoutParams(dp(44), dp(44)))

            val texts = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
            }
            texts.addView(TextView(act).apply {
                text = row.title
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Ui.font(act)
            })
            if (row.sub != null) {
                texts.addView(TextView(act).apply {
                    text = row.sub
                    setTextColor(Color.argb(170, 255, 255, 255))
                    textSize = 13f
                    typeface = Ui.font(act)
                })
            }
            line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            panel.addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        val scroll = ScrollView(act).apply { addView(panel) }
        d.setContentView(scroll)

        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            // Размытие фона за окном (Android 12+) и затемнение, чтобы стекло читалось
            if (Build.VERSION.SDK_INT >= 31) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                setBackgroundBlurRadius(dpInt(act, 48))
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        d.show()
    }

    private fun dpInt(act: Activity, v: Int) = (v * act.resources.displayMetrics.density).toInt()
}
