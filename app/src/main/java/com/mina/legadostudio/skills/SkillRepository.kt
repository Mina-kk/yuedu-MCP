package com.mina.legadostudio.skills

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SkillRepository(private val context: Context) {
    @Keep
    data class SkillInfo(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String,
        @SerializedName("builtIn") val builtIn: Boolean,
        @SerializedName("enabled") val enabled: Boolean,
    )

    private val prefs = context.getSharedPreferences("studio_skills", Context.MODE_PRIVATE)

    private val customDir = File(context.filesDir, "skills")
    // Shared across MCP/UI repository instances, including aliases of the same filesDir.
    private val stateLock = locks.computeIfAbsent(customDir.canonicalPath) { Any() }

    init { synchronized(stateLock) { customDir.mkdirs() } }

    internal fun <T> withSnapshot(block: () -> T): T = synchronized(stateLock, block)

    fun isEnabled(id: String): Boolean = synchronized(stateLock) { prefs.getBoolean("enabled_$id", true) }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(stateLock) {
            require(validId(id)) { "非法 Skill ID" }
            check(prefs.edit().putBoolean("enabled_$id", enabled).commit()) { "技能启用状态保存失败" }
        }
    }

    fun list(): List<SkillInfo> {
        synchronized(stateLock) {
            val builtInIds = builtInIds()
            val builtIn = builtInIds.map { id ->
                SkillInfo(id, readName(readAsset("skills/$id/SKILL.md")) ?: id, true, isEnabled(id))
            }
            val custom = customDir.listFiles { file -> file.isDirectory }.orEmpty().mapNotNull { dir ->
                if (dir.name in builtInIds) return@mapNotNull null
                val file = File(dir, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
                SkillInfo(dir.name, readName(file.readText()) ?: dir.name, false, isEnabled(dir.name))
            }
            return (builtIn + custom).sortedBy { it.name }
        }
    }

    fun isBuiltIn(id: String): Boolean = id in builtInIds()

    fun read(id: String): String {
        synchronized(stateLock) {
            require(validId(id)) { "非法 Skill ID" }
            if (isBuiltIn(id)) return readAsset("skills/$id/SKILL.md")
            val custom = File(customDir, "$id/SKILL.md")
            require(custom.isFile) { "Skill 不存在" }
            return custom.readText()
        }
    }

    fun readReference(id: String, relative: String): String {
        synchronized(stateLock) {
            require(validId(id)) { "非法 Skill ID" }
            require(relative.matches(Regex("[\\p{L}\\p{N}._/-]+")) && !relative.contains("..")) { "非法参考路径" }
            if (isBuiltIn(id)) return readAsset("skills/$id/$relative")
            val custom = File(customDir, "$id/$relative")
            require(custom.isFile) { "参考文件不存在" }
            return custom.readText()
        }
    }

    fun save(id: String, markdown: String) {
        synchronized(stateLock) {
            requireMutable(id)
            require(markdown.contains("name:")) { "SKILL.md 缺少 name" }
            val dir = File(customDir, id).apply { mkdirs() }
            File(dir, "SKILL.md").writeText(markdown)
        }
    }

    fun importPackage(fileName: String, input: InputStream): SkillInfo {
        synchronized(stateLock) {
            require(fileName.endsWith(".zip", true) || fileName.endsWith(".md", true)) { "请选择 .md 或 .zip 技能包" }
            return if (fileName.endsWith(".zip", true)) importZip(fileName, input) else {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(buffer.size() + count <= 2 * 1024 * 1024) { "Markdown 超过 2 MiB" }
                    buffer.write(chunk, 0, count)
                }
                val markdown = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString()
                val id = slug(fileName.substringBeforeLast('.').ifBlank { readName(markdown).orEmpty() })
                require(!File(customDir, id).exists()) { "同 ID 技能已存在，请更名后导入" }
                save(id, markdown)
                SkillInfo(id, readName(markdown) ?: id, false, isEnabled(id))
            }
        }
    }

    /** Owns private ZIP bytes; callers cannot mutate the captured package. */
    class PackageSnapshot internal constructor(private val bytes: ByteArray) {
        fun writeTo(output: OutputStream) { output.write(bytes.copyOf()) }
    }

    fun snapshotPackage(id: String): PackageSnapshot = synchronized(stateLock) {
        val bytes = java.io.ByteArrayOutputStream()
        writePackage(id, bytes)
        PackageSnapshot(bytes.toByteArray())
    }

    fun exportPackage(id: String, output: OutputStream) {
        // Never hold the repository lock across a caller-controlled sink.
        snapshotPackage(id).writeTo(output)
    }

    private fun writePackage(id: String, output: OutputStream) {
        require(validId(id)) { "非法 Skill ID" }
        // A directory without a manifest is not a package (never emit an empty ZIP).
        read(id)
        ZipOutputStream(output).use { zip ->
            if (isBuiltIn(id)) {
                exportAssetTree(zip, "skills/$id", id)
                return@use
            }
            val custom = File(customDir, id)
            require(custom.isDirectory) { "Skill 不存在" }
            custom.walkTopDown().filter(File::isFile).forEach { file ->
                val relative = file.relativeTo(custom).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("$id/$relative"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun delete(id: String): Boolean {
        synchronized(stateLock) {
            requireMutable(id)
            return File(customDir, id).takeIf(File::isDirectory)?.deleteRecursively() ?: false
        }
    }

    private fun importZip(fileName: String, input: InputStream): SkillInfo {
        val temp = File(context.cacheDir, "skill-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipInputStream(input).use { zip ->
                var entries = 0
                val paths = mutableSetOf<String>()
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 500) { "Skill 压缩包文件过多" }
                    val normalized = entry.name
                    require(!normalized.startsWith('/') && !normalized.contains('\\') && !normalized.contains(':') &&
                        normalized.trimEnd('/').split('/').none { it.isBlank() || it == "." || it == ".." }) { "Skill 压缩包路径非法" }
                    require(paths.add(normalized.trimEnd('/'))) { "Skill 压缩包存在重复路径" }
                    val target = File(temp, normalized).canonicalFile
                    require(target.path.startsWith(temp.canonicalPath + File.separator)) { "Skill 压缩包路径非法" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= 20L * 1024 * 1024) { "Skill 压缩包解压后超过 20 MiB" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifests = temp.walkTopDown().filter { it.isFile && it.name == "SKILL.md" }.toList()
            require(manifests.size == 1) { "压缩包必须包含且仅包含一个 SKILL.md" }
            val skillFile = manifests.single()
            require(skillFile.length() <= 2 * 1024 * 1024) { "Markdown 超过 2 MiB" }
            val markdown = skillFile.readText()
            require(markdown.contains("name:")) { "SKILL.md 缺少 name" }
            val sourceRoot = requireNotNull(skillFile.parentFile)
            val id = slug(if (sourceRoot == temp) fileName.substringBeforeLast('.') else sourceRoot.name)
            requireMutable(id)
            require(temp.walkTopDown().filter(File::isFile).all { it.canonicalPath.startsWith(sourceRoot.canonicalPath + File.separator) }) { "技能目录之外存在文件" }
            val destination = File(customDir, id)
            require(!destination.exists()) { "同 ID 技能已存在，请更名后导入" }
            // Stage on the destination filesystem; rename publishes the complete package atomically.
            val stage = File(customDir, ".import-${System.nanoTime()}")
            try {
                sourceRoot.copyRecursively(stage)
                check(stage.renameTo(destination)) { "技能包写入失败" }
            } finally { stage.deleteRecursively() }
            return SkillInfo(id, readName(markdown) ?: id, false, isEnabled(id))
        } finally { temp.deleteRecursively() }
    }

    private fun exportAssetTree(zip: ZipOutputStream, assetPath: String, zipPath: String) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            zip.putNextEntry(ZipEntry(zipPath))
            context.assets.open(assetPath).use { it.copyTo(zip) }
            zip.closeEntry()
        } else children.forEach { exportAssetTree(zip, "$assetPath/$it", "$zipPath/$it") }
    }

    private fun builtInIds(): Set<String> = context.assets.list("skills").orEmpty().toSet()

    private fun requireMutable(id: String) = Companion.requireMutable(id, builtInIds())

    private fun slug(value: String): String {
        val cleaned = value.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-').take(80)
        return cleaned.ifBlank { "skill-${System.currentTimeMillis()}" }
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun validId(id: String): Boolean =
            id.length in 1..80 && id !in setOf(".", "..") && id.matches(Regex("[\\p{L}\\p{N}._-]+"))

        fun requireMutable(id: String, builtInIds: Set<String>) {
            require(validId(id)) { "非法 Skill ID" }
            require(id !in builtInIds) { "内置 Skill 不可修改" }
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun readName(markdown: String): String? =
        Regex("(?m)^name:\\s*[\"']?([^\"'\\r\\n]+)").find(markdown)?.groupValues?.get(1)?.trim()
}
