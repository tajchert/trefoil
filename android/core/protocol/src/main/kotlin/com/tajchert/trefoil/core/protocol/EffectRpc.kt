package com.tajchert.trefoil.core.protocol

import kotlinx.serialization.json.*

/** Effect 4.1.2 RpcMessage / RpcSerialization.layerJson; deliberately not JSON-RPC. */
object EffectRpc {
    val json = Json { ignoreUnknownKeys = true }
    fun request(id: String, tag: String, payload: JsonObject) = buildJsonObject {
        put("_tag", "Request"); put("id", id); put("tag", tag)
        put("payload", payload); put("headers", JsonArray(emptyList()))
    }
    fun signal(tag: String, id: String? = null) = buildJsonObject {
        put("_tag", tag); if (id != null) put("requestId", id)
    }
    fun decode(text: String): List<JsonObject> {
        require(text.length <= 16 * 1024 * 1024) { "RPC frame too large" }
        return when (val value = json.parseToJsonElement(text)) {
            is JsonObject -> listOf(value)
            is JsonArray -> value.map { it.jsonObject }
            else -> error("Invalid RPC frame")
        }
    }
}
fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
