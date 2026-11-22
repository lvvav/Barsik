package com.barsik.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

// Звонки по сотовой связи: контакты (включая SIM, если Android их показывает), история и вызов номера
object Dialer {

    data class Contact(val name: String, val number: String)
    data class Call(val name: String, val number: String, val type: Int, val date: Long)

    fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun contacts(ctx: Context): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out.add(Contact(name, num))
            }
        }
        return out.distinctBy { it.number }
    }

    fun callLog(ctx: Context, limit: Int = 60): List<Call> {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<Call>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(Call(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    // Поиск по имени: сначала точное совпадение начала имени, потом любое вхождение
    fun findByName(ctx: Context, query: String): Contact? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = contacts(ctx)
        return all.firstOrNull { it.name.lowercase().startsWith(q) }
            ?: all.firstOrNull { it.name.lowercase().contains(q) }
    }

    // Звонок: прямой, если разрешён; иначе открывается системный набор с номером
    fun call(ctx: Context, number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action = if (has(ctx, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        ctx.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun typeName(t: Int) = when (t) {
        CallLog.Calls.INCOMING_TYPE -> "входящий"
        CallLog.Calls.OUTGOING_TYPE -> "исходящий"
        CallLog.Calls.MISSED_TYPE -> "пропущенный"
        else -> "звонок"
    }

    fun dateText(ms: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(java.util.Date(ms))
}
