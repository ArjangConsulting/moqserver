package com.moqserver.studio.projectformat

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A type-erased value for representing arbitrary YAML/JSON content (e.g. response bodies).
 * Equivalent to Swift's AnyCodableValue.
 */
@Serializable(with = YamlValueSerializer::class)
sealed class YamlValue {
    data object Null : YamlValue()
    data class Bool(val value: Boolean) : YamlValue()
    data class Int(val value: kotlin.Int) : YamlValue()
    data class Double(val value: kotlin.Double) : YamlValue()
    data class Str(val value: String) : YamlValue()
    data class Array(val value: List<YamlValue>) : YamlValue()
    data class Obj(val value: Map<String, YamlValue>) : YamlValue()

    fun toAny(): Any? = when (this) {
        is Null -> null
        is Bool -> value
        is Int -> value
        is Double -> value
        is Str -> value
        is Array -> value.map { it.toAny() }
        is Obj -> value.mapValues { (_, v) -> v.toAny() }
    }

    companion object {
        fun from(value: Any?): YamlValue = when (value) {
            null -> Null
            is Boolean -> Bool(value)
            is kotlin.Int -> Int(value)
            is Long -> if (value in kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE) {
                Int(
                    value.toInt(),
                )
            } else {
                Double(value.toDouble())
            }
            is kotlin.Double -> Double(value)
            is Float -> Double(value.toDouble())
            is Number -> {
                val d = value.toDouble()
                if (d == d.toLong().toDouble() && d.toLong() in kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE) {
                    Int(d.toInt())
                } else {
                    Double(d)
                }
            }
            is String -> Str(value)
            is List<*> -> Array(value.map { from(it) })
            is Map<*, *> -> Obj(value.entries.associate { (k, v) -> k.toString() to from(v) })
            else -> Str(value.toString())
        }
    }
}

object YamlValueSerializer : KSerializer<YamlValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("YamlValue")

    override fun serialize(encoder: Encoder, value: YamlValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("YamlValue can only be serialized with JSON")
        jsonEncoder.encodeJsonElement(toJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): YamlValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("YamlValue can only be deserialized from JSON")
        return fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    private fun toJsonElement(value: YamlValue): JsonElement = when (value) {
        is YamlValue.Null -> JsonNull
        is YamlValue.Bool -> JsonPrimitive(value.value)
        is YamlValue.Int -> JsonPrimitive(value.value)
        is YamlValue.Double -> JsonPrimitive(value.value)
        is YamlValue.Str -> JsonPrimitive(value.value)
        is YamlValue.Array -> JsonArray(value.value.map { toJsonElement(it) })
        is YamlValue.Obj -> JsonObject(value.value.mapValues { (_, v) -> toJsonElement(v) })
    }

    private fun fromJsonElement(element: JsonElement): YamlValue = when (element) {
        is JsonNull -> YamlValue.Null
        is JsonPrimitive -> when {
            element.isString -> YamlValue.Str(element.content)
            element.booleanOrNull != null -> YamlValue.Bool(element.booleanOrNull!!)
            element.intOrNull != null -> YamlValue.Int(element.intOrNull!!)
            element.longOrNull != null -> YamlValue.Double(element.longOrNull!!.toDouble())
            element.doubleOrNull != null -> YamlValue.Double(element.doubleOrNull!!)
            else -> YamlValue.Str(element.content)
        }
        is JsonArray -> YamlValue.Array(element.map { fromJsonElement(it) })
        is JsonObject -> YamlValue.Obj(element.entries.associate { (k, v) -> k to fromJsonElement(v) })
    }
}
