package cn.dumate.a6studio.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import cn.dumate.a6studio.platform.nowMillis
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * A single visual block. Children are keyed by slot name ("body", "else", "catch"…),
 * matching the original web model so existing .a6proj.json files still load.
 */
@Serializable
data class Block(
    val id: String,
    val type: String,
    val params: Map<String, ParamValue> = emptyMap(),
    val children: Map<String, List<Block>> = emptyMap()
)

/**
 * Parameter values are dynamically typed in the original model (text / number / bool),
 * so we keep the JSON union rather than coercing eagerly.
 */
@Serializable(with = ParamValueSerializer::class)
sealed interface ParamValue {
    data class Text(val value: String) : ParamValue
    data class Num(val value: Double) : ParamValue
    data class Bool(val value: Boolean) : ParamValue

    /** String form used by code generation, mirroring JS `String(v)`. */
    fun asText(): String = when (this) {
        is Text -> value
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> if (value) "true" else "false"
    }

    fun asNumber(fallback: Double = 0.0): Double = when (this) {
        is Num -> value
        is Text -> value.trim().toDoubleOrNull() ?: fallback
        is Bool -> if (value) 1.0 else 0.0
    }

    fun asBool(): Boolean = when (this) {
        is Bool -> value
        is Num -> value != 0.0
        is Text -> value.isNotEmpty()
    }

    fun isBlank(): Boolean = this is Text && value.isBlank()

    companion object {
        fun of(raw: String?): ParamValue = Text(raw ?: "")
        fun of(raw: Number?): ParamValue = Num(raw?.toDouble() ?: 0.0)
        fun of(raw: Boolean?): ParamValue = Bool(raw == true)
    }
}

object ParamValueSerializer : kotlinx.serialization.KSerializer<ParamValue> {
    private val delegate = JsonPrimitive.serializer()

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: ParamValue) {
        val prim = when (value) {
            is ParamValue.Text -> JsonPrimitive(value.value)
            // JSON has no Infinity/NaN, and encoding one throws — which made the whole project
            // unsavable. Write it as text so the save goes through and codegen still sees it.
            is ParamValue.Num -> if (value.value.isFinite()) JsonPrimitive(value.value) else JsonPrimitive(value.asText())
            is ParamValue.Bool -> JsonPrimitive(value.value)
        }
        delegate.serialize(encoder, prim)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ParamValue {
        val prim = delegate.deserialize(decoder)
        if (!prim.isString) {
            val numeric = prim.content.toDoubleOrNull()
            if (numeric != null) return ParamValue.Num(numeric)
            if (prim.content == "true" || prim.content == "false") return ParamValue.Bool(prim.content == "true")
        }
        return ParamValue.Text(prim.content)
    }
}

@Serializable
data class StudioModule(
    val id: String,
    val name: String,
    val args: String = "",
    val desc: String = "",
    val children: Map<String, List<Block>> = mapOf("body" to emptyList())
)

@Serializable
data class TestCase(
    val id: String,
    val name: String,
    val code: String
)

@Serializable
data class DeviceEntry(
    val url: String,
    val name: String = ""
)

@Serializable
data class BridgeSettings(
    val url: String = "",
    val devices: List<DeviceEntry> = emptyList()
)

@Serializable
data class Project(
    val name: String = "未命名项目",
    val root: Block = rootBlock(),
    val modules: List<StudioModule> = emptyList(),
    val tests: List<TestCase> = emptyList(),
    val bridge: BridgeSettings = BridgeSettings(),
    @SerialName("createdAt") val createdAt: Long = 0L,
    /**
     * R-2：工程被外部 MCP 补丁改过、且用户尚未确认。随工程一起落盘，重启/重新导入后仍然有效，
     * 真机运行前必须先确认。默认 false，旧工程文件无此字段时按 false 读取（向后兼容）。
     * 撤销到补丁前的快照时会自然恢复为 false。
     */
    @SerialName("externalPatchPending") val externalPatchPending: Boolean = false
) {
    companion object {
        fun rootBlock() = Block(
            id = "root",
            type = "program",
            params = emptyMap(),
            children = mapOf("body" to emptyList())
        )
    }
}

/**
 * Stable, collision-resistant ids, shaped like the web `b_<ts>_<n>` scheme.
 *
 * The old implementation bumped a shared `counter`, which is not thread-safe: two blocks created
 * from different threads in the same millisecond could receive the same id and then collide in
 * selection/undo. A random numeric suffix keeps the exact `b_<ts>_<digits>` shape the legacy
 * parity fixture normalises, without any shared mutable state.
 */
object Ids {
    fun next(): String = "b_" + nowMillis().toString(36) + "_" + Random.nextInt(1, 1_000_000_000)

    fun nextWith(prefix: String): String =
        prefix + "_" + nowMillis().toString(36) + "_" + Random.nextInt(1, 1_000_000_000)

    fun randomHex(len: Int = 8): String =
        (1..len).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
}