package net.levente.cloudexus.mobile.data.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Everything that can go wrong talking to the server, in the shape the UI needs. */
sealed class ApiException(message: String) : Exception(message) {

    /**
     * The request may or may not have reached the server (timeout, lost
     * connection). For a booking this means "maybe booked": retry with the
     * same Idempotency-Key, never with a new one.
     */
    class Network(cause: Throwable) : ApiException(cause.message ?: "network error")

    /** The token is no longer valid: the user has to sign in again. */
    class Unauthorized(message: String) : ApiException(message)

    /** The server answered with an error; nothing was booked. */
    class Http(val status: Int, message: String, val details: JsonArray?) : ApiException(message) {

        /** Per-line problems of a booking, keyed by the index into items. */
        fun lineProblems(): Map<Int, String> = details.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            index to (obj["message"]?.jsonPrimitive?.contentOrNull ?: "")
        }.toMap()

        /** Stock shortages of a booking: product id to (available, requested). */
        fun shortages(): Map<Int, Pair<String, String>> = details.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val productId = obj["product_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val available = obj["available"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val requested = obj["requested"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            productId to (available to requested)
        }.toMap()
    }

    /** The server sent something that is not the Cloudexus API (wrong URL, proxy page). */
    class BadResponse(message: String) : ApiException(message)
}
