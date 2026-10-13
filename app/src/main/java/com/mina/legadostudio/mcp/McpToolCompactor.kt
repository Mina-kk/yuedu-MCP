package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * `?compact=1` 精简 tools/list 的纯函数变换（不依赖 Android，可在 JVM 单测中直接跑）。
 *
 * 约定：tools/list 里工具名称、顺序、schema 结构（type/required/enum/数值边界等约束）逐字不变，
 * 只把每个工具描述与入参 `description` 替换为 [truncate] 的精简结果；完整定义（原描述 +
 * 原 schema + annotations）经 `studio://tools/{name}` 资源模板读回，不增加 tool 数量。
 */
internal object McpToolCompactor {

    /** 初始化 URL 上的开关：新会话 `POST /mcp?compact=1` 走精简目录。 */
    const val QUERY_PARAM = "compact"
    const val QUERY_VALUE = "1"

    /** 完整工具定义的只读资源模板（URI RFC 6570 变量 {name}）。 */
    const val RESOURCE_URI_TEMPLATE = "studio://tools/{name}"
    const val RESOURCE_URI_PREFIX = "studio://tools/"
    const val RESOURCE_NAME = "tool-definition"
    const val RESOURCE_DESCRIPTION = "按名读取 MCP 工具的完整定义（name/description/inputSchema/annotations）；精简模式下 tools/list 只带截断描述"
    const val RESOURCE_MIME = "application/json"

    /** 单条描述的长度上限；句子边界在上限以内时按边界截断。 */
    const val MAX_DESCRIPTION_CHARS = 100

    private const val ELLIPSIS = "…"
    const val TRUNCATED_NOTE = "（完整定义见 studio://tools/{name}）"

    /**
     * 精简一段描述：
     * - 句末 `。`/`；`（含英文 `;` 与换行，同样视作可截断边界）之前的部分 ≤ [maxChars] → 保留到该边界为止；
     * - 单句超过上限或没有边界 → 硬截到上限并追加 `…`，只丢尾部细节（完整文本随时可由
     *   `studio://tools/{name}` 读回，关键约束一般都在句首，不发生绝对差错信息丢失）；
     * - 已是单句且 ≤ 上限、或截断结果与原句相同 → 原样返回（同一条规则也保证默认路径逐字不变）。
     */
    fun truncate(text: String?, maxChars: Int = MAX_DESCRIPTION_CHARS): String? {
        if (text == null) return null
        val boundary = boundaryIndex(text)
        if (text.length <= maxChars && boundary == null) return text
        if (boundary != null && boundary + 1 <= maxChars) {
            return text.substring(0, boundary + 1)
        }
        return text.take(maxChars) + ELLIPSIS
    }

    /** 首个句末边界（`。`/`；`/`;`/换行）下标；没有返回 null。英文句点不算边界，避免误截 `a.b` 类标识符。 */
    private fun boundaryIndex(text: String): Int? {
        val idx = text.indexOfFirst { it == '。' || it == '；' || it == ';' || it == '\n' }
        return if (idx >= 0) idx else null
    }

    /** 深拷贝 schema：properties 里每个参数的 `description` 换成精简版，其余键逐字保留。 */
    fun compactSchema(schema: ToolSchema): ToolSchema {
        val properties = schema.properties ?: return schema
        val compacted = buildJsonObject {
            properties.forEach { (key, value) ->
                val property = value as? JsonObject
                val description = property?.get("description") as? JsonPrimitive
                if (property == null || description == null || !description.isString) {
                    put(key, value)
                } else {
                    put(key, JsonObject(property + ("description" to JsonPrimitive(truncate(description.content)))))
                }
            }
        }
        return schema.copy(properties = compacted)
    }

    /**
     * 生成注册的精简版工具：描述精简（被硬截断时尾部再补一条资源指针，保证客户端知道去哪拿完整定义）、
     * schema 仅入参描述精简，annotations 与其余字段原样。
     */
    fun compactTool(tool: Tool): Tool {
        val short = truncate(tool.description).orEmpty()
        val description = if (short.endsWith(ELLIPSIS)) "$short$TRUNCATED_NOTE" else short
        return tool.copy(description = description, inputSchema = compactSchema(tool.inputSchema))
    }

    /**
     * `studio://tools/{name}` 的响应体：`{"name","description","inputSchema","annotations"}`，
     * 内容是该工具注册时的完整版本；annotations 依 SDK 序列化输出，缺省时为 JSON null。
     */
    fun resourcePayload(tool: Tool): String {
        val json = McpJson
        val payload = buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("inputSchema", json.encodeToJsonElement(ToolSchema.serializer(), tool.inputSchema))
            put("annotations", tool.annotations?.let { json.encodeToJsonElement(ToolAnnotations.serializer(), it) } ?: JsonNull)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }
}
