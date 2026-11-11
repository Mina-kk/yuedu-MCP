package com.mina.legadostudio.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * SAF 文件往返（系统文件选择器）。
 *
 * 契约要求：**只接受内容、不接受路径**。前端给 `{name,text,mime}`，宿主用
 * `ACTION_CREATE_DOCUMENT` 让用户选位置；导入用 `ACTION_OPEN_DOCUMENT` 读回文本。
 * 不申请存储权限、不落临时文件、不把路径回给前端。
 *
 * 生命周期与并发（`.tasks/host-remote-build.md` 第 10 条）：
 * - 必须挂在 [ComponentActivity] 上（`registerForActivityResult` 只在 ComponentActivity 可用），
 *   在 `onCreate` 里 [register] 一次（早于 STARTED，否则会抛 IllegalStateException）；
 * - **单 pending**：同一时刻只允许一个导出/导入，并发调用直接抛 [Busy]，不排队不覆盖；
 * - 启动在主线程，读写走 IO 调度器（不裸开 Thread）；
 * - 取消/销毁时 [release] 结算所有 pending（抛 [Cancelled]），不会永久挂起；
 * - 导入按 UTF-8 **严格解码**（非法字节报错，不静默替换成 U+FFFD），上限 2MiB。
 *
 * 取消语义：调用方（bridge）在 [Cancelled] 时回 `{saved:false}` / `{cancelled:true}`，
 * 而不是抛内部错误。
 */
internal class SafLauncher(private val activity: ComponentActivity) {

    /** 用户取消（系统选择器直接返回）。 */
    class Cancelled : RuntimeException("用户取消")

    /** 已有 pending 请求：并发调用被明确拒绝，而不是覆盖前一个 deferred。 */
    class Busy : RuntimeException("已有文件操作在进行中")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var exportLauncher: ActivityResultLauncher<Intent>? = null
    private var importLauncher: ActivityResultLauncher<Intent>? = null

    private class Pending(val exporting: Boolean, val deferred: CompletableDeferred<Uri?> = CompletableDeferred()) {
        @Volatile var pickerReturned = false
    }
    private val operations = SingleOperationGate()

    @Volatile private var released = false

    /** 必须在 onCreate 中调用（registerForActivityResult 的生命周期约束）。 */
    fun register() {
        if (exportLauncher != null) return
        exportLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = (operations.current() as? Pending)?.takeIf { it.exporting }
                ?: return@registerForActivityResult
            pending.pickerReturned = true
            if (pending.deferred.isCancelled) operations.finish(pending)
            else pending.deferred.complete(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
        }
        importLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = (operations.current() as? Pending)?.takeIf { !it.exporting }
                ?: return@registerForActivityResult
            pending.pickerReturned = true
            if (pending.deferred.isCancelled) operations.finish(pending)
            else pending.deferred.complete(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
        }
    }

    /** 导出文本；用户取消抛 [Cancelled]，IO 失败抛 [IOException]。 */
    suspend fun export(name: String, text: String, mime: String): Uri {
        val pending = Pending(exporting = true)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            withContext(Dispatchers.Main) {
                if (released) throw Cancelled()
                val launcher = exportLauncher ?: throw IllegalStateException("SAF 未注册")
                if (!operations.reserve(pending)) throw Busy()
                try { launcher.launch(intent) } catch (error: Exception) {
                    operations.finish(pending)
                    throw error
                }
            }
            val uri = pending.deferred.await() ?: throw Cancelled()
            withContext(Dispatchers.IO) { writeText(uri, text) }
            return uri
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("系统没有可用的文件保存界面")
        } catch (error: IllegalStateException) {
            throw Cancelled()
        } finally {
            pending.deferred.cancel()
            // A cancelled caller cannot release a still-open picker to a later request.
            if (pending.pickerReturned) operations.finish(pending)
        }
    }

    /** Internal file only; no JS-supplied paths. Keep reservation through copy and readback. */
    suspend fun exportFile(file: java.io.File, name: String, mime: String): Uri {
        val pending = Pending(exporting = true)
        try {
            withContext(Dispatchers.Main) {
                if (released) throw Cancelled()
                val launcher = exportLauncher ?: error("SAF 未注册")
                if (!operations.reserve(pending)) throw Busy()
                try {
                    launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
                    })
                } catch (error: Exception) { operations.finish(pending); throw error }
            }
            val uri = pending.deferred.await() ?: throw Cancelled()
            withContext(Dispatchers.IO) {
                val expected = java.security.MessageDigest.getInstance("SHA-256")
                activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    java.security.DigestInputStream(file.inputStream(), expected).use { it.copyTo(output) }
                } ?: throw IOException("无法打开目标文件")
                val actual = java.security.MessageDigest.getInstance("SHA-256")
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; actual.update(buffer, 0, n) }
                } ?: throw IOException("无法回读目标文件")
                if (!expected.digest().contentEquals(actual.digest())) throw IOException("导出回读不一致")
            }
            return uri
        } finally {
            pending.deferred.cancel()
            if (pending.pickerReturned) operations.finish(pending)
        }
    }

    /** @return (文件名, 文本)；用户取消时抛 [Cancelled]。 */
    suspend fun import(): Pair<String, String> {
        val pending = Pending(exporting = false)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "text/*"))
        }
        try {
            withContext(Dispatchers.Main) {
                if (released) throw Cancelled()
                val launcher = importLauncher ?: throw IllegalStateException("SAF 未注册")
                if (!operations.reserve(pending)) throw Busy()
                try { launcher.launch(intent) } catch (error: Exception) {
                    operations.finish(pending)
                    throw error
                }
            }
            val uri = pending.deferred.await() ?: throw Cancelled()
            return withContext(Dispatchers.IO) { (queryDisplayName(uri) ?: "import.json") to readText(uri) }
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("系统没有可用的文件选择界面")
        } catch (error: IllegalStateException) {
            throw Cancelled()
        } finally {
            pending.deferred.cancel()
            if (pending.pickerReturned) operations.finish(pending)
        }
    }

    /**
     * Activity 销毁时结算：所有等待方收到 [Cancelled]，launcher 引用清空。
     * 幂等。
     */
    fun release() {
        if (released) return
        released = true
        (operations.close() as? Pending)?.deferred?.complete(null)
        exportLauncher = null
        importLauncher = null
        scope.cancel()
    }

    private fun writeText(uri: Uri, text: String) {
        val output = activity.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开目标文件")
        output.use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
    }.getOrNull()

    /**
     * 读取上限与 bridge 请求预算一致：超过 2MB 直接拒绝，不做静默截断；
     * 非法 UTF-8 报错（严格解码），不把坏字节变成替换字符回给前端。
     */
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
        return decodeUtf8Strict(bytes)
    }

    companion object {
        /** 与 [WebBridgeProtocol.MAX_REQUEST_BYTES] 同量级：UTF-8 下 2MB 字节即上限。 */
        const val MAX_IMPORT_BYTES = WebBridgeProtocol.MAX_REQUEST_BYTES

        /** 严格 UTF-8 解码：非法/截断字节抛 IOException，不做替换。 */
        fun decodeUtf8Strict(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (error: CharacterCodingException) {
                throw IOException("文件不是合法 UTF-8 文本", error)
            }
        }
    }
}
