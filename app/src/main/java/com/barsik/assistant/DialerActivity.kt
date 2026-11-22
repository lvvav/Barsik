package com.barsik.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Экран звонков: контакты, история и набор номера. Всё в тёплом стиле приложения
class DialerActivity : Activity() {

    private lateinit var tabsRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var padPanel: LinearLayout
    private lateinit var padField: TextView
    private val tabViews = mutableMapOf<String, TextView>()
    private var mode = "contacts"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            background = android.graphics.drawable.ColorDrawable(0xFF1A130E.toInt())
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "Звонки"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Ui.font(this@DialerActivity)
            setPadding(dp(4), 0, 0, dp(12))
        })

        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("contacts" to "Контакты", "history" to "История", "pad" to "Набор").forEach { (key, label) ->
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Ui.font(this@DialerActivity)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { setMode(key) }
            }
            tabViews[key] = tv
            tabsRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
        }
        root.addView(tabsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        search = EditText(this).apply {
            hint = "Поиск по имени или номеру"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Ui.font(this@DialerActivity)
            setSingleLine(true)
            background = rounded(0x662A1E16, 16, 0x40F2D7B8)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        padPanel = buildPad()
        root.addView(padPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val need = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 201)

        setMode("contacts")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun setMode(key: String) {
        mode = key
        tabViews.forEach { (k, tv) ->
            tv.background = if (k == key) rounded(0xFFF2B77A.toInt(), 14) else rounded(0x332A1E16, 14, 0x33F2D7B8)
            tv.setTextColor(if (k == key) 0xFF1A120C.toInt() else Color.WHITE)
        }
        padPanel.visibility = if (key == "pad") View.VISIBLE else View.GONE
        search.visibility = if (key == "pad") View.GONE else View.VISIBLE
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (mode == "pad") return
        val q = search.text.toString().trim()
        when (mode) {
            "contacts" -> {
                if (!Dialer.has(this, Manifest.permission.READ_CONTACTS)) return note("Разреши доступ к контактам, чтобы видеть их здесь")
                val items = Dialer.contacts(this).filter { q.isEmpty() || it.name.contains(q, true) || it.number.contains(q) }
                if (items.isEmpty()) return note("Контактов не найдено")
                items.forEach { addRow(it.name, it.number, it.number) { Dialer.call(this, it.number) } }
            }
            "history" -> {
                if (!Dialer.has(this, Manifest.permission.READ_CALL_LOG)) return note("Разреши доступ к журналу звонков")
                val items = Dialer.callLog(this).filter { q.isEmpty() || it.name.contains(q, true) || it.number.contains(q) }
                if (items.isEmpty()) return note("История пуста")
                items.forEach { c ->
                    val title = c.name.ifBlank { c.number }
                    addRow(title, "${Dialer.typeName(c.type)} · ${Dialer.dateText(c.date)}", title) { Dialer.call(this, c.number) }
                }
            }
        }
    }

    private fun note(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Ui.font(this@DialerActivity)
            setPadding(0, dp(40), 0, 0)
        })
    }

    private fun addRow(title: String, sub: String, avatarSource: String, onTap: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0x552A1E16, 18, 0x22F2D7B8)
            isClickable = true
            setOnClickListener { onTap() }
        }
        val tile = TextView(this).apply {
            text = avatarSource.firstOrNull()?.uppercase() ?: "?"
            gravity = Gravity.CENTER
            setTextColor(0xFF1A120C.toInt())
            textSize = 18f
            typeface = Ui.font(this@DialerActivity)
            background = rounded(0xFFF2B77A.toInt(), 22)
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Ui.font(this@DialerActivity)
        })
        texts.addView(TextView(this).apply {
            text = sub
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            typeface = Ui.font(this@DialerActivity)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun buildPad(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        padField = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = Ui.font(this@DialerActivity)
            setPadding(0, dp(8), 0, dp(12))
        }
        panel.addView(padField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listOf("1 2 3", "4 5 6", "7 8 9", "* 0 #").forEach { line ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.split(" ").forEach { k ->
                r.addView(TextView(this).apply {
                    text = k
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    typeface = Ui.font(this@DialerActivity)
                    background = rounded(0x552A1E16, 40, 0x22F2D7B8)
                    setOnClickListener { padField.text = padField.text.toString() + k }
                }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            panel.addView(r)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bottom.addView(TextView(this).apply {
            text = "⌫"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { padField.text = padField.text.toString().dropLast(1) }
        })
        bottom.addView(TextView(this).apply {
            text = "Позвонить"
            gravity = Gravity.CENTER
            setTextColor(0xFF1A120C.toInt())
            textSize = 17f
            typeface = Ui.font(this@DialerActivity)
            background = rounded(0xFFF2B77A.toInt(), 30)
            setPadding(dp(36), dp(14), dp(36), dp(14))
            setOnClickListener {
                val num = padField.text.toString()
                if (num.isNotBlank()) Dialer.call(this@DialerActivity, num)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })
        panel.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return panel
    }
}
