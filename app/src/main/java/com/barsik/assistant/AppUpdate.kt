package com.barsik.assistant

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

// Обновление внутри приложения: APK качается системным загрузчиком и открывается установщиком
object AppUpdate {
    const val APK_URL = "https://github.com/lvvav/Barsik/releases/download/latest/Barsik.apk"

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val file = File(app.getExternalFilesDir(null), "Barsik.apk")
        file.delete()

        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(APK_URL))
                .setTitle("Барсик: обновление")
                .setDescription("Скачиваю новую версию")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, null, "Barsik.apk")
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                app.unregisterReceiver(this)
                val ok = dm.query(DownloadManager.Query().setFilterById(id)).use { q ->
                    q.moveToFirst() &&
                        q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                }
                if (ok) install(app, file)
                else Toast.makeText(app, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        Toast.makeText(app, "Качаю обновление, прогресс в шторке", Toast.LENGTH_SHORT).show()
    }

    fun install(ctx: Context, file: File) {
        // На Android 8+ установка из приложения требует отдельного разрешения
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(ctx, "Разреши установку из Барсика, потом нажми «Проверить обновления»", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
