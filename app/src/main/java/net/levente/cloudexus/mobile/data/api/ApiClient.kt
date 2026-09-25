package net.levente.cloudexus.mobile.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Where to send requests, and as whom. */
data class Connection(val baseUrl: String, val token: String, val language: String?)

/**
 * The Cloudexus REST API, as far as the warehouse app needs it (see
 * cloudexus/web/API.md). Every call runs on the IO dispatcher and throws
 * [ApiException] on failure.
 */
class ApiClient(private val http: OkHttpClient, private val json: Json) {

    /**
     * [code] is the two-step sign-in code, for a user who has it turned on:
     * without it the server answers 403 with [ApiException.Http.twoFactorRequired].
     */
    suspend fun login(baseUrl: String, username: String, password: String, deviceName: String, code: String? = null): LoginResult {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("device_name", deviceName)
            if (code != null) put("code", code)
        }
        val request = Request.Builder()
            .url(url(baseUrl, "auth/login", null))
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request, Envelope.serializer(LoginResult.serializer()), unauthorizedIsHttp = true).data
    }

    suspend fun me(connection: Connection): User =
        get(connection, "auth/me", emptyMap(), MeResponse.serializer()).data.user

    suspend fun logout(connection: Connection) {
        val request = authorized(connection, "auth/logout", emptyMap())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        execute(request, JsonObject.serializer())
    }

    /** Language codes the server has, so ?language= is only sent when it exists there. */
    suspend fun languages(baseUrl: String, token: String): List<String> =
        allPages(Connection(baseUrl, token, null), "languages", emptyMap(), Language.serializer())
            .filter { it.isActive == 1 }
            .map { it.code }

    suspend fun warehouses(connection: Connection): List<Warehouse> =
        allPages(connection, "warehouses", mapOf("status" to "active"), Warehouse.serializer())

    suspend fun locations(connection: Connection, warehouseId: Int): List<Location> =
        allPages(connection, "warehouses/$warehouseId/locations", mapOf("status" to "active"), Location.serializer())

    /** The active product with this barcode or SKU, or null when there is none. */
    suspend fun lookup(connection: Connection, code: String): Product? = try {
        get(connection, "products/lookup", mapOf("code" to code), Envelope.serializer(Product.serializer())).data
    } catch (e: ApiException.Http) {
        if (e.status == 404) null else throw e
    }

    /**
     * Books a stock movement: [path] is stock/in, stock/out or stock/transfer.
     * Returns the number of lines the server booked.
     */
    suspend fun book(connection: Connection, path: String, body: JsonObject, idempotencyKey: String): BookingReceipt {
        val request = authorized(connection, path, emptyMap())
            .header("Idempotency-Key", idempotencyKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request, BookingReceipt.serializer())
    }

    private suspend fun <T> get(connection: Connection, path: String, query: Map<String, String>, serializer: KSerializer<T>): T =
        execute(authorized(connection, path, query).get().build(), serializer)

    private suspend fun <T> allPages(connection: Connection, path: String, query: Map<String, String>, item: KSerializer<T>): List<T> {
        val rows = mutableListOf<T>()
        var page = 1
        do {
            val result = get(connection, path, query + mapOf("page" to "$page", "per_page" to "200"), Page.serializer(item))
            rows += result.data
            page++
        } while (page <= result.meta.totalPages)
        return rows
    }

    private fun authorized(connection: Connection, path: String, query: Map<String, String>): Request.Builder {
        val withLanguage = if (connection.language != null) query + ("language" to connection.language) else query
        return Request.Builder()
            .url(url(connection.baseUrl, path, withLanguage))
            .header("Authorization", "Bearer ${connection.token}")
    }

    private suspend fun <T> execute(request: Request, serializer: KSerializer<T>, unauthorizedIsHttp: Boolean = false): T =
        withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request.newBuilder().header("Accept", "application/json").build()).execute()
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }

            response.use {
                val text = try {
                    it.body.string()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }

                if (!it.isSuccessful) {
                    val error = parseError(text) ?: throw ApiException.BadResponse("HTTP ${it.code}")
                    if (it.code == 401 && !unauthorizedIsHttp) {
                        throw ApiException.Unauthorized(error.second)
                    }
                    throw ApiException.Http(it.code, error.second, error.third)
                }

                try {
                    json.decodeFromString(serializer, text)
                } catch (e: SerializationException) {
                    throw ApiException.BadResponse(e.message ?: "unexpected response")
                } catch (e: IllegalArgumentException) {
                    throw ApiException.BadResponse(e.message ?: "unexpected response")
                }
            }
        }

    /** {"error": {"status", "message", "details"?}} as (status, message, details), or null. */
    private fun parseError(text: String): Triple<Int, String, JsonElement?>? = try {
        val error = json.parseToJsonElement(text).jsonObject["error"]?.jsonObject ?: return null
        Triple(
            error["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            error["message"]?.jsonPrimitive?.contentOrNull ?: "",
            error["details"],
        )
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The server address as typed by a person: "cloudexus.example.com" gets
         * https://, and a trailing slash or a pasted "/api" suffix is dropped.
         * Returns null when it is not a usable http(s) URL.
         */
        fun normalizeBaseUrl(input: String): String? {
            var value = input.trim()
            if (value.isEmpty()) return null
            if (!value.contains("://")) value = "https://$value"
            value = value.trimEnd('/').removeSuffix("/api").trimEnd('/')
            val parsed = value.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            return value
        }

        internal fun url(baseUrl: String, path: String, query: Map<String, String>?): HttpUrl {
            val builder = ("$baseUrl/api/$path").toHttpUrlOrNull()?.newBuilder()
                ?: throw ApiException.BadResponse("invalid server URL")
            query?.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            return builder.build()
        }
    }
}

@kotlinx.serialization.Serializable
private data class MeData(val user: User)

@kotlinx.serialization.Serializable
private data class MeResponse(val data: MeData)

/** What the app keeps from a booking response: how many movements or transfer lines were booked. */
@kotlinx.serialization.Serializable
data class BookingReceipt(val data: BookingReceiptData)

@kotlinx.serialization.Serializable
data class BookingReceiptData(
    val movements: List<JsonObject> = emptyList(),
    val transfers: List<JsonObject> = emptyList(),
) {
    val lineCount: Int get() = movements.size + transfers.size
}
