package com.cortinadev.dogmatix.util

import com.google.gson.*

/** Stable backup format; malformed rules never replace a collection's existing contents. */
object SmartCollectionRules {
    fun decode(text: String?): Map<Long, SmartCollectionRule>? = runCatching {
        val root = JsonParser.parseString(text ?: "{}").asJsonObject
        root.entrySet().associate { (key, value) ->
            val id = key.toLong().also { require(it > 0) }
            val o = value.asJsonObject
            fun strings(key: String): Set<String> = o.get(key)?.let { field ->
                field.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.toSet()
            }.orEmpty()
            fun string(key: String, fallback: String = "") = o.get(key)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
            } ?: fallback
            fun year(key: String): Int? = o.get(key)?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asString.toInt()
            }
            val rule = SmartCollectionRule(strings("consoles"), strings("languages"), string("genre"), year("fromYear"), year("toYear"), string("played", "ANY"))
            require(rule.valid)
            id to rule
        }
    }.getOrNull()
    fun encode(rules: Map<Long, SmartCollectionRule>) = JsonObject().apply {
        rules.forEach { (id, rule) ->
            require(id > 0 && rule.valid)
            add(id.toString(), JsonObject().apply {
                add("consoles", JsonArray().apply { rule.consoles.sorted().forEach(::add) })
                add("languages", JsonArray().apply { rule.languages.sorted().forEach(::add) })
                addProperty("genre", rule.genre)
                rule.fromYear?.let { addProperty("fromYear", it) }
                rule.toYear?.let { addProperty("toYear", it) }
                addProperty("played", rule.played)
            })
        }
    }.toString()
}
