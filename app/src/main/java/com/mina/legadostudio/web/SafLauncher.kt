package com.mina.legadostudio.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SAF 文件往返（系统文件选择器）。
 *
 * 契约要求：**只接受内容、不接受路径**。前端给 `{name,text,mime}`，宿主用
 * `ACTION_CREATE_DOCUMENT` 让用户选位置；导入用 `ACTION_OPEN_DOCUMENT` 读回文本。
 * 不申请存储权限、不落临时文件、不把路径回给前端。
 *
 * 取消语义：调用方（bridge）在 [Cancelled] 时回 `{saved:false}` / `{cancelled:true}`，
 * 而不是抛内部错误。
 */
internal class SafLauncher(private val activity: Activity) {

    class Cancelled : RuntimeException("用户取消")

    private var exportLauncher: ActivityResultLauncher<Intent>? = null
    private var importLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingExport: PendingExport? = null
    private var pendingImport: CompletableDeferred<Uri?>? = null

    private class PendingExport(val text: String, val deferred: CompletableDeferred<Uri?>)

    fun register() {
        exportLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = pendingExport
            pendingExport = null
            if (result.resultCode != Activity.RESULT_OK) {
                pending?.deferred?.complete(null)
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                pending?.deferred?.complete(null)
                return@registerForActivityResult
            }
            // 写入放在调用方线程池（bridge 在 IO 上等待），不阻塞主线程。
            Thread({
                val target = pending
                if (target == null) return@Thread
                val failure = runCatching {
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(target.text.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("无法打开目标文件")
                }.exceptionOrNull()
                if (failure == null) target.deferred.complete(uri) else target.deferred.completeExceptionally(failure)
            }, "studio-saf-export").start()
        }
        importLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val deferred = pendingImport
            pendingImport = null
            deferred?.complete(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
        }
    }

    suspend fun export(name: String, text: String, mime: String): Uri {
        val launcher = exportLauncher ?: throw IllegalStateException("SAF 未注册")
        val deferred = CompletableDeferred<Uri?>()
        pendingExport = PendingExport(text, deferred)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            launcher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            pendingExport = null
            throw IllegalStateException("系统没有可用的文件保存界面")
        }
        val uri = deferred.await()
        return uri ?: throw Cancelled()
    }

    /** @return (文件名, 文本)；用户取消时抛 [Cancelled]。 */
    suspend fun import(): Pair<String, String> {
        val launcher = importLauncher ?: throw IllegalStateException("SAF 未注册")
        val deferred = CompletableDeferred<Uri?>()
        pendingImport = deferred
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "text/*"))
        }
        try {
            launcher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            pendingImport = null
            throw IllegalStateException("系统没有可用的文件选择界面")
        }
        val uri = deferred.await() ?: throw Cancelled()
        val name = queryDisplayName(uri) ?: "import.json"
        val text = readText(uri)
        return name to text
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
    }.getOrNull()

    /** 读取上限与 bridge 请求预算一致：超过 2MB 直接拒绝，不做静默截断。 */
    private fun readText(uri: Uri): String {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) throw IOException("文件超过 ${MAX_IMPORT_BYTES / 1024}KB 上限")
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: throw IOException("无法读取所选文件")
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        /** 与 [WebBridgeProtocol.MAX_REQUEST_CHARS] 同量级：UTF-8 下 2MB 字节即上限。 */
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }
}
