package net.levente.cloudexus.mobile.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var api: ApiClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server.start()
        baseUrl = server.url("/cloudexus/web").toString().trimEnd('/')
        api = ApiClient(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(), json)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())
    }

    private val connection get() = Connection(baseUrl, "cxu_token", "en")

    @Test
    fun `login posts the credentials and returns the token and user`() = runTest {
        respond(201, """{"data":{"token":"cxu_abc","expires_at":"2026-12-21 21:28:53","user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user"}}}""")

        val result = api.login(baseUrl, "pdateszt", "Teszt1234", "Emulator")

        assertEquals("cxu_abc", result.token)
        assertEquals("PDA Teszt", result.user.fullName)
        val request = server.takeRequest()
        assertEquals("/cloudexus/web/api/auth/login", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"device_name\":\"Emulator\""))
    }

    @Test
    fun `a wrong password at login is an Http 401, not an expired session`() = runTest {
        respond(401, """{"error":{"status":401,"message":"Invalid username or password."}}""")

        try {
            api.login(baseUrl, "pdateszt", "rossz", "Emulator")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun `a user with two-step sign-in on is asked for the code, and it is sent the second time`() = runTest {
        respond(403, """{"error":{"status":403,"message":"A two-step sign-in code is required.","details":{"two_factor_required":true}}}""")
        respond(201, """{"data":{"token":"cxu_abc","expires_at":"2026-12-21 21:28:53","user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user"}}}""")

        try {
            api.login(baseUrl, "pdateszt", "Teszt1234", "Emulator")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertEquals(403, e.status)
            assertTrue(e.twoFactorRequired)
            assertTrue(e.lineProblems().isEmpty())
        }
        assertTrue(!server.takeRequest().body!!.utf8().contains("\"code\""))

        assertEquals("cxu_abc", api.login(baseUrl, "pdateszt", "Teszt1234", "Emulator", "492817").token)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"code\":\"492817\""))
    }

    @Test
    fun `a wrong two-step code is a 401 that still says a code is wanted`() = runTest {
        respond(401, """{"error":{"status":401,"message":"That two-step sign-in code is not right.","details":{"two_factor_required":true}}}""")

        try {
            api.login(baseUrl, "pdateszt", "Teszt1234", "Emulator", "000000")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertEquals(401, e.status)
            assertTrue(e.twoFactorRequired)
        }
    }

    @Test
    fun `an ordinary refusal is not taken for a two-step question`() = runTest {
        respond(403, """{"error":{"status":403,"message":"Forbidden."}}""")

        try {
            api.login(baseUrl, "pdateszt", "Teszt1234", "Emulator")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertTrue(!e.twoFactorRequired)
        }
    }

    @Test
    fun `a rejected token elsewhere means the session expired`() = runTest {
        respond(401, """{"error":{"status":401,"message":"Invalid or missing API token."}}""")

        try {
            api.me(connection)
            fail("expected an exception")
        } catch (e: ApiException.Unauthorized) {
            // expected
        }
    }

    @Test
    fun `lookup sends the code and language as query parameters and the bearer token`() = runTest {
        respond(200, """{"data":{"id":12,"sku":"PRD-0012","barcode":"599/1","name":"Monitor","unit":"szett","matched_by":"barcode","stock_total":"243.000","stock":[{"warehouse_id":1,"warehouse_name":"Központi","location_id":null,"location_code":null,"quantity":"42.000"}]}}""")

        val product = api.lookup(connection, "599/1")!!

        assertEquals(12, product.id)
        assertNull(product.stock.single().locationId)
        val request = server.takeRequest()
        assertEquals("599/1", request.url.queryParameter("code"))
        assertEquals("en", request.url.queryParameter("language"))
        assertEquals("Bearer cxu_token", request.headers["Authorization"])
    }

    @Test
    fun `an unknown code is null, not an error`() = runTest {
        respond(404, """{"error":{"status":404,"message":"No active product with this barcode or SKU."}}""")

        assertNull(api.lookup(connection, "000"))
    }

    @Test
    fun `lists are read page by page`() = runTest {
        respond(200, """{"data":[{"id":1,"name":"A"},{"id":2,"name":"B"}],"meta":{"page":1,"per_page":2,"total":3,"total_pages":2}}""")
        respond(200, """{"data":[{"id":3,"name":"C"}],"meta":{"page":2,"per_page":2,"total":3,"total_pages":2}}""")

        val warehouses = api.warehouses(connection)

        assertEquals(listOf(1, 2, 3), warehouses.map { it.id })
        assertEquals("1", server.takeRequest().url.queryParameter("page"))
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun `a booking sends the Idempotency-Key and maps line details and shortages`() = runTest {
        respond(422, """{"error":{"status":422,"message":"Not enough stock.","details":[{"product_id":12,"sku":"PRD-0012","available":"3.000","requested":"5.000"},{"index":1,"message":"Unknown or inactive product_id."}]}}""")

        try {
            api.book(connection, "stock/out", buildJsonObject { put("warehouse_id", 1) }, "key-12345678")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertEquals("3.000" to "5.000", e.shortages()[12])
            assertEquals("Unknown or inactive product_id.", e.lineProblems()[1])
        }
        val request = server.takeRequest()
        assertEquals("key-12345678", request.headers["Idempotency-Key"])
        assertEquals("/cloudexus/web/api/stock/out", request.url.encodedPath)
    }

    @Test
    fun `me carries the role and its permissions`() = runTest {
        respond(200, """{"data":{"user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user","role_code":"viewer","role_name":"Csak olvasó","permissions":["dashboard.view","stock.view"]},"expires_at":"2026-12-21 21:28:53"}}""")

        val user = api.me(connection)

        assertEquals("Csak olvasó", user.roleName)
        assertEquals(false, user.canMoveStock)
        assertTrue(user.can("stock.view"))
    }

    @Test
    fun `a server older than roles sends no permissions, and nothing is hidden`() = runTest {
        respond(200, """{"data":{"user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user"},"expires_at":"2026-12-21 21:28:53"}}""")

        val user = api.me(connection)

        assertNull(user.permissions)
        assertTrue(user.canMoveStock)
    }

    @Test
    fun `a booking the role does not allow is an Http 403`() = runTest {
        respond(403, """{"error":{"status":403,"message":"Your role does not allow this: stock.move."}}""")

        try {
            api.book(connection, "stock/in", buildJsonObject { put("warehouse_id", 1) }, "key-12345678")
            fail("expected an exception")
        } catch (e: ApiException.Http) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun `a booking receipt counts movements and transfer lines`() = runTest {
        respond(201, """{"data":{"type":"in","movements":[{"id":1},{"id":2}]}}""")
        assertEquals(2, api.book(connection, "stock/in", buildJsonObject { }, "key-12345678").data.lineCount)

        respond(201, """{"data":{"transfers":[{"product_id":1}]}}""")
        assertEquals(1, api.book(connection, "stock/transfer", buildJsonObject { }, "key-87654321").data.lineCount)
    }

    @Test
    fun `a page that is not the API is a bad response`() = runTest {
        respond(200, "<html>It works!</html>")

        try {
            api.me(connection)
            fail("expected an exception")
        } catch (e: ApiException.BadResponse) {
            // expected
        }
    }

    @Test
    fun `no server at all is a network error`() = runTest {
        server.close()

        try {
            api.me(connection)
            fail("expected an exception")
        } catch (e: ApiException.Network) {
            // expected
        }
    }

    @Test
    fun `server addresses are normalised the way people type them`() {
        assertEquals("https://cloudexus.levente.net", ApiClient.normalizeBaseUrl("cloudexus.levente.net"))
        assertEquals("https://cloudexus.levente.net", ApiClient.normalizeBaseUrl(" https://cloudexus.levente.net/ "))
        assertEquals("https://cloudexus.levente.net", ApiClient.normalizeBaseUrl("https://cloudexus.levente.net/api"))
        assertEquals("http://10.0.2.2/cloudexus/web", ApiClient.normalizeBaseUrl("http://10.0.2.2/cloudexus/web/"))
        assertNull(ApiClient.normalizeBaseUrl(""))
        assertNull(ApiClient.normalizeBaseUrl("ftp://example.com"))
    }
}
