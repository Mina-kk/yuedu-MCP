package com.mina.legadostudio.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.mina.legadostudio.skills.SkillRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream

/** Binary SAF, separate from the text-only file.import/file.export contract. */
internal class SkillManagementSaf(private val activity: ComponentActivity) : SkillManagementDocuments {
    private class Pending {
        val result = CompletableDeferred<Uri?>()
        var returned = false
    }
    private val gate = SingleOperationGate()
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var released = false

    /** Call during Activity.onCreate, before STARTED. */
    fun register() {
        check(launcher == null)
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pending = gate.current() as? Pending ?: return@registerForActivityResult
            pending.returned = true
            if (pending.result.isCancelled) gate.finish(pending)
            else pending.result.complete(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
        }
    }

    private suspend fun <T> document(intent: Intent, block: (Uri) -> T): T? {
        val pending = Pending()
        try {
            withContext(Dispatchers.Main) {
                check(!released) { "宿主已关闭" }
                val registered = checkNotNull(launcher) { "技能 SAF 未注册" }
                check(gate.reserve(pending)) { "已有技能文件操作在进行中" }
                try { registered.launch(intent) }
                catch (error: Exception) { gate.finish(pending); throw error }
            }
            val uri = pending.result.await() ?: return null
            return withContext(Dispatchers.IO) { block(uri) }
        } finally {
            pending.result.cancel()
            // Keep cancelled callers reserved until their picker actually returns.
            if (pending.returned) gate.finish(pending)
        }
    }

    override suspend fun importPackage(repository: SkillRepository): SkillRepository.SkillInfo? = document(
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "text/markdown", "text/plain", "application/octet-stream"))
        }
    ) { uri ->
        val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
        } ?: throw IOException("无法读取所选文件名")
        require(!name.contains('/') && !name.contains('\\')) { "文件名非法" }
        val input = activity.contentResolver.openInputStream(uri) ?: throw IOException("无法打开技能包")
        input.use { repository.importPackage(name, LimitedInput(it)) }
    }

    override suspend fun exportPackage(repository: SkillRepository, id: String): Boolean {
        // Finish the immutable snapshot before launching SAF. No repository lock is
        // retained while the user chooses a destination or the provider writes it.
        val snapshot = withContext(Dispatchers.IO) { repository.snapshotPackage(id) }
        return document(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "$id.zip")
            }
        ) { uri ->
            val output = activity.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法保存技能包")
            output.use { snapshot.writeTo(it) }
            true
        } ?: false
    }

    fun release() {
        released = true
        (gate.close() as? Pending)?.result?.complete(null)
        launcher = null
    }

    private class LimitedInput(input: InputStream) : FilterInputStream(input) {
        private var count = 0L
        private fun account(n: Int) { if (n > 0) count += n; require(count <= 24L * 1024 * 1024) { "技能包超过 24 MiB" } }
        override fun read(): Int = super.read().also { if (it >= 0) account(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also(::account)
    }
}
