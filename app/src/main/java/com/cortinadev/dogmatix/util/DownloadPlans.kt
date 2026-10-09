package com.cortinadev.dogmatix.util

import com.google.gson.Strictness
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Portable queue identities and per-item scheduling. Addresses and local paths never belong here. */
data class DownloadPlanItem(
    val consoleId: String,
    val fileName: String,
    val displayName: String,
    val condition: DownloadCondition? = null
) {
    val key: Pair<String, String> get() = consoleId to fileName
}

data class DownloadPlan(val items: List<DownloadPlanItem>)

enum class DownloadPlanStatus { READY, MISSING, UNAVAILABLE, RESTRICTED, ALREADY_QUEUED, OWNED, NAME_CONFLICT }

/** Local facts, evaluated again at confirmation so stale previews cannot bypass restrictions. */
data class DownloadPlanAvailability(
    val indexed: Boolean,
    val sourceAvailable: Boolean = false,
    val allowed: Boolean = true,
    val owned: Boolean = false
)

object DownloadPlans {
    const val FORMAT = "dogmatix-download-plan"
    const val VERSION = 1
    const val MAX_ITEMS = 3_000
    const val MAX_BYTES = 2 * 1024 * 1024

    /** Array order is the relative order; no device-global queue positions are exported. */
    fun encode(plan: DownloadPlan): String {
        validate(plan)
        val text = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            add("items", JsonArray().apply {
                plan.items.forEach { item -> add(JsonObject().apply {
                    addProperty("consoleId", item.consoleId)
                    addProperty("fileName", item.fileName)
                    addProperty("displayName", item.displayName)
                    item.condition?.let { condition -> add("condition", JsonObject().apply {
                        addProperty("kind", condition.kind.name)
                        if (condition.kind == ConditionKind.AT_TIME) {
                            addProperty("minuteOfDay", condition.minuteOfDay)
                            addProperty("atMillis", condition.atMillis)
                        }
                    }) }
                }) }
            })
        }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Plan too large" }
        return text
    }

    /** A bounded read, even when a document provider reports no length. Invalid UTF-8 is rejected. */
    fun read(input: InputStream): DownloadPlan {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "Plan too large" }
        }
        val bytes = output.toByteArray()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return decode(text)
    }

    /** Streaming schema validation rejects extra fields, duplicate keys and deeply nested input. */
    fun decode(text: String): DownloadPlan {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Plan too large" }
        val reader = JsonReader(StringReader(text.removePrefix("\uFEFF"))).apply { strictness = Strictness.STRICT }
        return reader.use {
            var format: String? = null
            var version: Long? = null
            var items: List<DownloadPlanItem>? = null
            it.objectFields { field -> when (field) {
                "format" -> format = it.string()
                "version" -> version = it.number()
                "items" -> {
                    val result = mutableListOf<DownloadPlanItem>()
                    it.beginArray()
                    while (it.hasNext()) {
                        require(result.size < MAX_ITEMS) { "Too many items" }
                        result += it.item()
                    }
                    it.endArray()
                    items = result
                }
                else -> error("Unexpected field")
            } }
            require(it.peek() == JsonToken.END_DOCUMENT) { "Trailing content" }
            require(format == FORMAT && version == VERSION.toLong()) { "Unknown plan" }
            DownloadPlan(requireNotNull(items)).also(::validate)
        }
    }

    /** Existing rows and duplicate filename slots are never overwritten by importing a plan. */
    fun statuses(
        plan: DownloadPlan,
        availability: Map<Pair<String, String>, DownloadPlanAvailability>,
        existingNames: Set<String>
    ): List<DownloadPlanStatus> {
        val reserved = mutableSetOf<String>()
        return plan.items.map { item ->
            val local = availability[item.key]
            when {
                local == null || !local.indexed -> DownloadPlanStatus.MISSING
                !local.allowed -> DownloadPlanStatus.RESTRICTED
                item.fileName in existingNames -> DownloadPlanStatus.ALREADY_QUEUED
                local.owned -> DownloadPlanStatus.OWNED
                !local.sourceAvailable -> DownloadPlanStatus.UNAVAILABLE
                !reserved.add(item.fileName) -> DownloadPlanStatus.NAME_CONFLICT
                else -> DownloadPlanStatus.READY
            }
        }
    }

    private fun validate(plan: DownloadPlan) {
        require(plan.items.isNotEmpty() && plan.items.size <= MAX_ITEMS) { "Empty or oversized plan" }
        val seen = mutableSetOf<Pair<String, String>>()
        plan.items.forEach { item ->
            require(item.consoleId.validText(128) && item.consoleId.all { it.isLetterOrDigit() || it == '_' || it == '-' })
            require(item.fileName.validText(1024) && item.fileName !in setOf(".", "..") && '/' !in item.fileName && '\\' !in item.fileName)
            require(item.displayName.validText(512))
            require(seen.add(item.key)) { "Duplicate game" }
            item.condition?.let { condition ->
                require(condition.minuteOfDay in 0..1439)
                if (condition.kind == ConditionKind.AT_TIME) require(condition.atMillis > 0)
                else require(condition.atMillis == 0L && condition.minuteOfDay == 0)
            }
        }
    }

    private fun String.validText(max: Int) = isNotBlank() && length <= max && none { it.isISOControl() }

    private fun JsonReader.item(): DownloadPlanItem {
        var console: String? = null
        var fileName: String? = null
        var display: String? = null
        var condition: DownloadCondition? = null
        objectFields { field -> when (field) {
            "consoleId" -> console = string()
            "fileName" -> fileName = string()
            "displayName" -> display = string()
            "condition" -> condition = if (peek() == JsonToken.NULL) { nextNull(); null } else condition()
            else -> error("Unexpected item field")
        } }
        return DownloadPlanItem(requireNotNull(console), requireNotNull(fileName), requireNotNull(display), condition)
    }

    private fun JsonReader.condition(): DownloadCondition {
        var kind: ConditionKind? = null
        var minute = 0L
        var at = 0L
        objectFields { field -> when (field) {
            "kind" -> kind = ConditionKind.valueOf(string())
            "minuteOfDay" -> minute = number()
            "atMillis" -> at = number()
            else -> error("Unexpected condition field")
        } }
        require(minute in 0L..1439L)
        return DownloadCondition(requireNotNull(kind), minute.toInt(), at)
    }

    private inline fun JsonReader.objectFields(read: (String) -> Unit) {
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val field = nextName()
            require(seen.add(field)) { "Duplicate field" }
            read(field)
        }
        endObject()
    }

    private fun JsonReader.string(): String { require(peek() == JsonToken.STRING); return nextString() }
    private fun JsonReader.number(): Long {
        require(peek() == JsonToken.NUMBER)
        val number = nextString()
        require(number.matches(Regex("0|[1-9][0-9]*")))
        return requireNotNull(number.toLongOrNull())
    }
}
