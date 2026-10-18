package com.eza.hyperglow.bridge

import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

data class SpicyBridgeWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

internal fun normalizeSpicySourceRange(textLength: Int, start: Int, end: Int): Pair<Int, Int> =
    if (start == -1 && end == -1 || start >= 0 && start < end && end <= textLength) {
        start to end
    } else {
        -1 to -1
    }

internal fun normalizeSpicyBoundaryAfter(
    documentVersion: Int,
    boundaryAfter: Boolean?,
    legacyPartOfWord: Boolean?
): Boolean? = if (documentVersion >= 2) boundaryAfter else legacyPartOfWord?.not()

data class SpicyBridgeRuby(val start: Int, val end: Int, val reading: String)

data class SpicyBridgeLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

data class SpicyBridgeRow(
    val role: String,
    val startMs: Long,
    val endMs: Long,
    val fillEndMs: Long,
    val alignedRight: Boolean,
    val text: String,
    val romanized: String,
    val translated: String,
    val words: List<SpicyBridgeWord>,
    val ruby: List<SpicyBridgeRuby> = emptyList(),
    val layoutGroups: List<SpicyBridgeLayoutGroup> = emptyList()
)

data class SpicyBridgeDocument(
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val provider: String,
    val language: String,
    val type: String,
    val durationMs: Long,
    val processingVersion: Int,
    val rows: List<SpicyBridgeRow>
) {
    /**
     * 持有的文档在曲目一致、且时长与 state 相差不超过
     * [DOCUMENT_DURATION_TOLERANCE_MS] 时仍然可用。
     *
     * `producerId` 与 `generation` 是生产者在上一份文档仍在途时推进的会话记账。要求二者
     * 一致会丢弃一份无人重新请求的文档,从而让余下整首歌不计时:没有动画填充,keepalive
     * 也退化为换歌租约。到达路径仍只在 bundle 元数据与 state 匹配时才提交文档,因此这道
     * 闸门只决定一份已提交的文档是否属于 state 正在播放的曲目。
     */
    fun matches(state: SpicyBridgeState): Boolean =
        trackUri == state.trackUri && documentDurationsAgree(durationMs, state.durationMs)

    fun primaryRowAt(positionMs: Long): SpicyBridgeRow? {
        var lead: SpicyBridgeRow? = null
        var other: SpicyBridgeRow? = null
        for (row in rows) {
            if (positionMs < row.startMs || positionMs >= row.endMs) continue
            if (row.role == "LEAD") {
                if (lead == null || row.startMs >= lead.startMs) lead = row
            } else if (other == null) {
                other = row
            }
        }
        return lead ?: other
    }
}

/**
 * 哪个身份字段使一份持有的文档不再属于生产者 state,或 null 表示仍匹配。时长排最后上报,
 * 因为它是唯一在歌曲表象不变时也会漂移的字段:在曲目元数据稳定前解析出的文档会保留陈旧
 * 时长,并被丢弃整首歌。
 */
internal fun spicyBridgeDocumentMismatch(
    document: SpicyBridgeDocument,
    state: SpicyBridgeState
): String? = when {
    document.matches(state) -> null
    document.trackUri != state.trackUri ->
        "track document=${document.trackUri} state=${state.trackUri}"
    else -> "duration document=${document.durationMs} state=${state.durationMs} " +
        "track=${state.trackUri}"
}

/**
 * 生产者把曲目长度取整到整秒上报(`281000`),而 state 携带实测时长(`280757`)。实测文档
 * 相差 54–486 ms,精确比较会拒掉 53 份中的 50 份,而被拒的文档整首歌都不会被重新请求。
 */
internal const val DOCUMENT_DURATION_TOLERANCE_MS = 1_000L

/**
 * 文档自身时长是否与已接受的 state 相符。
 *
 * 未声明可用时长的文档(`0`,或曲目元数据尚未稳定的生产者版本)不算不符:约束行的就是
 * state 的实测时长,文档的身份即其曲目 URI。真实时长仍须落在
 * [DOCUMENT_DURATION_TOLERANCE_MS] 内。
 */
internal fun documentDurationsAgree(documentMs: Long, stateMs: Long): Boolean =
    stateMs > 0L && (documentMs <= 0L || abs(documentMs - stateMs) <= DOCUMENT_DURATION_TOLERANCE_MS)

internal fun isValidSpicyBridgeDocumentTiming(
    document: SpicyBridgeDocument,
    acceptedDurationMs: Long
): Boolean = spicyBridgeDocumentTimingFault(document, acceptedDurationMs) == null

/**
 * 文档时序为何不可信,或 null 表示可信。
 *
 * 拒绝原因报字面而非折叠成布尔:被拒的文档整首歌都不会被重新请求,所以"生产者发的
 * 时长与 state 不符"和"某一行越过歌长"是两回事——这正是 keepalive 静默回退到换歌
 * 租约时的全部诊断线索。fillEndMs 语义保持 CN+ 放宽版(不得越歌长,可越本行尾)。
 *
 * 亚秒级的时长不一致属于取整而非故障,故时长按 [DOCUMENT_DURATION_TOLERANCE_MS] 比较,
 * 行界取两者时长较大者。未声明可用时长的文档改由 state 实测时长约束(生产者会在自身曲目
 * 元数据稳定前发布文档)。曲目不同、时长差超过容差、或行越过两者仍会丢弃文档。
 */
internal fun spicyBridgeDocumentTimingFault(
    document: SpicyBridgeDocument,
    acceptedDurationMs: Long
): String? {
    if (acceptedDurationMs <= 0L) return "state-duration=$acceptedDurationMs"
    if (!documentDurationsAgree(document.durationMs, acceptedDurationMs)) {
        return "duration document=${document.durationMs} state=$acceptedDurationMs"
    }
    val limitMs = maxOf(document.durationMs, acceptedDurationMs)
    document.rows.forEachIndexed { index, row ->
        if (row.startMs < 0L || row.endMs !in row.startMs..limitMs) {
            return "row[$index] start=${row.startMs} end=${row.endMs} duration=$limitMs"
        }
        // fillEndMs 允许越过本行 endMs 但不得越过歌长(上游 8422d78):数据源把
        // 跨行填充算进 fillEndMs 是合法数据,渲染端另行钳制行窗口。
        if (row.fillEndMs !in row.startMs..limitMs) {
            return "row[$index] fillEnd=${row.fillEndMs} window=${row.startMs}..$limitMs"
        }
        row.words.forEachIndexed { wordIndex, word ->
            if (word.startMs < 0L || word.endMs !in word.startMs..limitMs) {
                return "row[$index].word[$wordIndex] start=${word.startMs} end=${word.endMs} " +
                    "duration=$limitMs"
            }
        }
    }
    return null
}

/**
 * 解析出的文档为何不能成为持有文档,或 null 表示可以。
 *
 * 文档可能在尚未接受任何 state 时到达:服务同步接受 state、在工作线程解析文档,两者只有
 * 松散顺序,而曲目元数据尚未稳定的 state(零时长)会被判为无效,此时歌词文档已带真实
 * 时长。在此丢弃的文档生产者不会再发——只有文档自身变化才会重新发布——于是余下整首歌
 * 不计时、keepalive 退化为换歌租约。
 *
 * 无 state 时无从比较,只按文档自声明时长校验。这不会泄漏内容:投影拒绝使用与播放 state
 * 不匹配的文档——[SpicyBridgeDocument.matches] 按曲目 URI 与时长判定,投影会清除任何不
 * 匹配者——因此未校验的提交只有在匹配 state 到达后才有用,否则被丢弃。
 */
internal fun spicyBridgeDocumentCommitFault(
    document: SpicyBridgeDocument,
    state: SpicyBridgeState?
): String? {
    if (state == null) {
        if (document.durationMs <= 0L) return "no-state document-duration=${document.durationMs}"
        return spicyBridgeDocumentTimingFault(document, document.durationMs)
    }
    if (document.producerId != state.producerId ||
        document.generation != state.generation ||
        document.trackUri != state.trackUri
    ) {
        return "state-mismatch document=${document.generation}:${document.trackUri} " +
            "state=${state.generation}:${state.trackUri}"
    }
    return spicyBridgeDocumentTimingFault(document, state.durationMs)
}

internal data class SpicyBridgeDocumentMetadata(
    val documentVersion: Int,
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val compressedBytes: Int
) {
    val sessionIdentity = SpicyDocumentSessionIdentity(producerId, generation, trackUri)

    companion object {
        fun from(bundle: Bundle): SpicyBridgeDocumentMetadata? {
            val metadata = SpicyBridgeDocumentMetadata(
                documentVersion = bundle.getInt("documentVersion", -1),
                producerId = bundle.getString("producerId").orEmpty(),
                generation = bundle.getInt("generation", -1),
                trackUri = bundle.getString("trackUri").orEmpty(),
                compressedBytes = bundle.getInt("compressedBytes", -1)
            )
            return metadata.takeIf(::isValidSpicyBridgeDocumentMetadata)
        }
    }
}

internal fun isValidSpicyBridgeDocumentMetadata(metadata: SpicyBridgeDocumentMetadata): Boolean =
    metadata.documentVersion in 1..2 &&
        metadata.producerId.isNotBlank() && metadata.producerId.length <= 64 &&
        metadata.generation >= 0 &&
        metadata.trackUri.startsWith("spotify:track:") && metadata.trackUri.length <= 512 &&
        metadata.compressedBytes in 1..MAX_DOCUMENT_COMPRESSED_BYTES

internal data class SpicyDocumentSessionIdentity(
    val producerId: String,
    val generation: Int,
    val trackUri: String
)

internal class SpicyDocumentCommitOrder {
    private var session: SpicyDocumentSessionIdentity? = null
    private var revision = -1L

    @Synchronized
    fun accept(identity: SpicyDocumentSessionIdentity, nextRevision: Long): Boolean {
        if (nextRevision < 0L) return false
        if (identity == session && nextRevision <= revision) return false
        session = identity
        revision = nextRevision
        return true
    }
}

private const val MAX_DOCUMENT_COMPRESSED_BYTES = 1024 * 1024
private const val MAX_DOCUMENT_UNCOMPRESSED_BYTES = 4 * 1024 * 1024

internal fun readBoundedSpicyDocumentGzip(
    input: InputStream,
    expectedCompressedBytes: Int
): ByteArray {
    if (expectedCompressedBytes !in 1..MAX_DOCUMENT_COMPRESSED_BYTES) {
        throw IOException("invalid compressed document size")
    }
    val exactInput = ExactLengthInputStream(input, expectedCompressedBytes)
    val output = ByteArrayOutputStream()
    GZIPInputStream(exactInput).use { gzip ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = gzip.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_DOCUMENT_UNCOMPRESSED_BYTES) {
                throw IOException("document exceeds limit")
            }
            output.write(buffer, 0, read)
        }
    }
    if (exactInput.bytesRead != expectedCompressedBytes) {
        throw IOException("compressed document size mismatch")
    }
    return output.toByteArray()
}

private class ExactLengthInputStream(
    private val input: InputStream,
    private val expectedBytes: Int
) : InputStream() {
    var bytesRead: Int = 0
        private set

    override fun read(): Int {
        if (bytesRead >= expectedBytes) return -1
        val value = input.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead >= expectedBytes) return -1
        val remaining = expectedBytes - bytesRead
        val read = input.read(buffer, offset, minOf(length, remaining))
        if (read > 0) bytesRead += read
        return read
    }

    override fun close() {
        input.close()
    }
}

object SpicyBridgeDocumentStore {
    private const val DOCUMENT_VERSION = 2
    private const val MIN_DOCUMENT_VERSION = 1
    private const val MAX_COMPRESSED_BYTES = MAX_DOCUMENT_COMPRESSED_BYTES
    private const val MAX_ROWS = 5_000
    private const val MAX_WORDS = 20_000
    private const val MAX_LAYOUT_GROUPS = 20_000
    private const val MAX_TEXT = 8_192

    private val mutableState = MutableStateFlow<SpicyBridgeDocument?>(null)
    val state = mutableState.asStateFlow()
    private val commitOrder = SpicyDocumentCommitOrder()

    /** null 表示文档已提交;非 null 为丢弃原因。 */
    internal fun accept(
        metadata: SpicyBridgeDocumentMetadata,
        descriptor: ParcelFileDescriptor,
        arrivalRevision: Long
    ): String? {
        descriptor.use { fd ->
            if (metadata.documentVersion !in MIN_DOCUMENT_VERSION..DOCUMENT_VERSION ||
                metadata.compressedBytes !in 1..MAX_COMPRESSED_BYTES
            ) {
                return "metadata version=${metadata.documentVersion} bytes=${metadata.compressedBytes}"
            }
            val producerId = metadata.producerId
            val generation = metadata.generation
            val trackUri = metadata.trackUri

            // 尚无 state 时不再直接丢弃:服务同步接受 state、在工作线程解析文档,
            // 二者只有松散顺序,且曲目元数据未稳定的 state(零时长)会被判为无效,
            // 此时文档已带真实时长。无 state 的提交只按文档自声明时长校验(见
            // [spicyBridgeDocumentCommitFault]),投影仍会拒绝与播放 state 不匹配者。
            val bridgeState = SpicyBridgeStore.state.value
            if (bridgeState != null && (
                    bridgeState.producerId != producerId ||
                        bridgeState.generation != generation ||
                        bridgeState.trackUri != trackUri
                    )
            ) {
                return "state-mismatch document=$generation:$trackUri " +
                    "state=${bridgeState.generation}:${bridgeState.trackUri}"
            }

            val bytes = readBoundedSpicyDocumentGzip(
                ParcelFileDescriptor.AutoCloseInputStream(fd),
                metadata.compressedBytes
            )
            val root = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (error: Exception) {
                // gzip 体不是合法 JSON 对象(截断/乱码/非对象)时给出命名拒绝,
                // 而不是让异常逃出 accept() 的命名拒绝体系。
                return "malformed json ${error.message ?: error.javaClass.simpleName}"
            }
            val documentVersion = root.requiredInt("version")
            if (documentVersion != metadata.documentVersion ||
                root.requiredString("producerId") != producerId ||
                root.requiredInt("generation") != generation ||
                root.requiredString("trackUri") != trackUri
            ) return "payload-identity-mismatch"

            val rowsJson = root["rows"]?.jsonArray ?: return "malformed rows-missing"
            if (rowsJson.size > MAX_ROWS) return "oversized rows=${rowsJson.size}"
            var wordCount = 0
            var layoutGroupCount = 0
            val rows = rowsJson.map { element ->
                val row = element.jsonObject
                val wordsJson = row["words"]?.jsonArray ?: JsonArray(emptyList())
                wordCount += wordsJson.size
                if (wordCount > MAX_WORDS) return "oversized words=$wordCount"
                val startMs = row.requiredLong("startMs")
                val endMs = row.requiredLong("endMs")
                val fillEndMs = row.requiredLong("fillEndMs")
                if (startMs < 0L || endMs < startMs || fillEndMs < startMs) {
                    return "malformed row start=$startMs end=$endMs fillEnd=$fillEndMs"
                }
                SpicyBridgeRow(
                    role = row.requiredString("role").also {
                        if (it !in setOf("LEAD", "BACKGROUND", "INTERLUDE")) {
                            return "malformed role=$it"
                        }
                    },
                    startMs = startMs,
                    endMs = endMs,
                    fillEndMs = fillEndMs,
                    alignedRight = row.requiredBoolean("alignedRight"),
                    text = row.boundedString("text"),
                    romanized = row.boundedString("romanized"),
                    translated = row.boundedString("translated"),
                    words = wordsJson.map { wordElement ->
                        val word = wordElement.jsonObject
                        val wordStart = word.requiredLong("startMs")
                        val wordEnd = word.requiredLong("endMs")
                        if (wordStart < 0L || wordEnd < wordStart) {
                            return "malformed word start=$wordStart end=$wordEnd"
                        }
                        val sourceRange = normalizeSpicySourceRange(
                            row.requiredString("text").length,
                            word.optionalInt("sourceStart", -1),
                            word.optionalInt("sourceEnd", -1)
                        )
                        SpicyBridgeWord(
                            word.boundedString("text"),
                            word.boundedString("romanized"),
                            wordStart,
                            wordEnd,
                            normalizeSpicyBoundaryAfter(
                                documentVersion,
                                word["boundaryAfter"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                                word["partOfWord"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                            ) ?: return "malformed word-boundary",
                            sourceRange.first,
                            sourceRange.second
                        )
                    },
                    ruby = (row["furigana"]?.jsonArray ?: JsonArray(emptyList())).map { rubyElement ->
                        val ruby = rubyElement.jsonObject
                        val start = ruby.requiredInt("start")
                        val end = ruby.requiredInt("end")
                        if (start < 0 || end < start) return "malformed furigana $start..$end"
                        SpicyBridgeRuby(start, end, ruby.boundedString("reading"))
                    },
                    layoutGroups = (row["layoutGroups"]?.jsonArray ?: JsonArray(emptyList())).map { groupElement ->
                        layoutGroupCount++
                        if (layoutGroupCount > MAX_LAYOUT_GROUPS) {
                            return "oversized layoutGroups=$layoutGroupCount"
                        }
                        val group = groupElement.jsonObject
                        val start = group.requiredInt("start")
                        val end = group.requiredInt("end")
                        if (start < 0 || end <= start || end > row.requiredString("text").length) {
                            return "malformed layoutGroup $start..$end"
                        }
                        SpicyBridgeLayoutGroup(
                            start,
                            end,
                            group.boundedString("kind"),
                            group.requiredBoolean("keepTogether"),
                            group.requiredDouble("confidence").coerceIn(0.0, 1.0)
                        )
                    }
                )
            }

            val document = SpicyBridgeDocument(
                producerId,
                generation,
                trackUri,
                root.boundedString("provider"),
                root.boundedString("language"),
                root.boundedString("type"),
                root.requiredLong("durationMs"),
                root.requiredInt("processingVersion"),
                rows
            )
            return commit(metadata, arrivalRevision, document)
        }
    }

    @Synchronized
    private fun commit(
        metadata: SpicyBridgeDocumentMetadata,
        arrivalRevision: Long,
        document: SpicyBridgeDocument
    ): String? {
        spicyBridgeDocumentCommitFault(document, SpicyBridgeStore.state.value)?.let {
            return "commit-$it"
        }
        if (!commitOrder.accept(metadata.sessionIdentity, arrivalRevision)) {
            return "commit-order revision=$arrivalRevision"
        }
        mutableState.value = document
        return null
    }

    @Synchronized
    fun clear(producerId: String, generation: Long) {
        mutableState.value?.let {
            if (it.producerId == producerId && generation >= it.generation) mutableState.value = null
        }
    }

    @Synchronized
    fun clear() {
        mutableState.value = null
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull ?: error("missing $key")
    private fun JsonObject.boundedString(key: String): String =
        requiredString(key).also { if (it.length > MAX_TEXT) error("oversized $key") }
    private fun JsonObject.requiredLong(key: String): Long =
        get(key)?.jsonPrimitive?.long ?: error("missing $key")
    private fun JsonObject.requiredInt(key: String): Int =
        get(key)?.jsonPrimitive?.int ?: error("missing $key")
    private fun JsonObject.optionalInt(key: String, fallback: Int): Int =
        get(key)?.jsonPrimitive?.int ?: fallback
    private fun JsonObject.requiredBoolean(key: String): Boolean =
        get(key)?.jsonPrimitive?.boolean ?: error("missing $key")
    private fun JsonObject.requiredDouble(key: String): Double =
        get(key)?.jsonPrimitive?.double ?: error("missing $key")
}
