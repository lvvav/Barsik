package com.barsik.assistant

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

// Общий шрифт приложения: Manrope из assets, если файл есть, иначе системный
object Ui {
    private var cached: Typeface? = null

    fun font(ctx: Context): Typeface {
        cached?.let { return it }
        val f = try {
            Typeface.createFromAsset(ctx.assets, "manrope.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cached = f
        return f
    }

    fun apply(v: View, f: Typeface) {
        if (v is TextView) v.typeface = f
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) apply(v.getChildAt(i), f)
        }
    }
}
