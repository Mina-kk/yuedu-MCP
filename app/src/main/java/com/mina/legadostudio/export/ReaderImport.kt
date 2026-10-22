package com.mina.legadostudio.export

import android.app.Activity
import android.content.Context
import android.content.Intent

/** 把工坊书源导入到本机阅读：回环 JSON + legado://import/bookSource。 */
object ReaderImport {
    const val PREFS = "import"
    const val LAST_READER = "lastReader"
    const val TTL_MS = 2L * 60L * 1000L

    fun lastPackage(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_READER, "").orEmpty()

    fun remember(context: Context, packageName: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_READER, packageName)
            .apply()
    }

    fun resolve(context: Context): List<ReaderCatalog.App> =
        ReaderCatalog.resolve(context.packageManager, lastPackage(context))

    fun launch(context: Context, sourceJson: String, packageName: String) {
        val json = SourceImportPayload.arrayJson(sourceJson)
        val url = ReaderImportService.prepare(context, json, TTL_MS)
        try {
            val view = ReaderCatalog.importIntent(packageName, url)
            if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            remember(context, packageName)
        } catch (error: Exception) {
            // 只撤销本次端点：并行的其它导入仍在阅读里等拉取，不能被一起杀掉。
            ReaderImportService.cancel(context, url)
            throw error
        }
    }
}
