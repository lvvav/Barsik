package com.barsik.assistant

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

// Короткое сообщение в стиле приложения: тёплая стеклянная плашка вместо системного тоста
object Banner {
    fun show(act: Activity, msg: String) {
        val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        val dp = act.resources.displayMetrics.density
        val tv = TextView(act).apply {
            text = msg
            setTextColor(Color.parseColor("#F5EBE0"))
            textSize = 14f
            typeface = Ui.font(act)
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = act.getDrawable(R.drawable.bg_glass_pill)
            alpha = 0f
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        ).apply {
            topMargin = (96 * dp).toInt()
            leftMargin = (24 * dp).toInt()
            rightMargin = (24 * dp).toInt()
        }
        root.addView(tv, lp)
        tv.animate().alpha(1f).setDuration(200).withEndAction {
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(300).withEndAction { root.removeView(tv) }.start()
            }, 1800)
        }.start()
    }
}
