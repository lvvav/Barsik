package com.barsik.assistant

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Экран настроек: плотное тёплое стекло, секции-островки с заголовками, векторные иконки в тёплых плитках
object Glass {

    private const val ICON_COLOR = 0xFFF2B77A.toInt()
    private const val TILE_COLOR = 0xFF3A281C.toInt()
    private const val HEADER_COLOR = 0xFFB8A08A.toInt()

    class Row(
        val icon: Int,            // id векторного drawable (ic_*)
        val title: String,
        val sub: String? = null,
        val onTap: () -> Unit
    )

    fun show(act: Activity, title: String, rows: List<Row>) {
        showSections(act, title, listOf(null to rows))
    }

    // sections: пары (заголовок секции или null, строки); каждая секция — отдельный островок
    fun showSections(act: Activity, title: String, sections: List<Pair<String?, List<Row>>>) {
        val dp = { v: Int -> (v * act.resources.displayMetrics.density).toInt() }
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                // Плотное матовое стекло: текст под ним не просвечивает
                setColor(Color.argb(242, 26, 18, 12))
                setStroke(dp(1), Color.argb(60, 242, 215, 184))
            }
        }

        panel.addView(TextView(act).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Ui.font(act)
            setPadding(dp(6), 0, 0, dp(12))
        })

        sections.forEach { (header, rows) ->
            if (header != null) {
                panel.addView(TextView(act).apply {
                    text = header.uppercase()
                    setTextColor(HEADER_COLOR)
                    textSize = 12f
                    letterSpacing = 0.08f
                    typeface = Ui.font(act)
                    setPadding(dp(8), dp(12), 0, dp(6))
                })
            }
            val island = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.argb(170, 42, 30, 22))
                    setStroke(dp(1), Color.argb(36, 242, 215, 184))
                }
            }
            rows.forEachIndexed { i, row ->
                island.addView(buildLine(act, d, row, dp), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = dp(2) })
            }
            panel.addView(island, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
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

    private fun buildLine(act: Activity, d: Dialog, row: Row, dp: (Int) -> Int): LinearLayout {
        val line = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.TRANSPARENT)
            }
            isClickable = true
            setOnClickListener { d.dismiss(); row.onTap() }
        }
        // Векторная иконка в тёплой скруглённой плитке
        val tile = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(TILE_COLOR)
            }
            addView(ImageView(act).apply {
                setImageResource(row.icon)
                setColorFilter(ICON_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        line.addView(tile, LinearLayout.LayoutParams(dp(40), dp(40)))

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
                setTextColor(Color.argb(160, 255, 245, 235))
                textSize = 13f
                typeface = Ui.font(act)
            })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return line
    }

    private fun dpInt(act: Activity, v: Int) = (v * act.resources.displayMetrics.density).toInt()
}
