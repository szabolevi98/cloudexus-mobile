package net.levente.cloudexus.mobile.ui.booking

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.Product
import net.levente.cloudexus.mobile.data.api.Warehouse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class DraftTest {
    private val monitor = Product(id = 12, sku = "PRD-0012", name = "24\" monitor", unit = "szett")
    private val paper = Product(id = 13, sku = "PRD-0013", name = "A4 papír", unit = "csomag")
    private val shelfA = Location(id = 24, warehouseId = 1, code = "B-03-04")
    private val shelfB = Location(id = 18, warehouseId = 1, code = "B-02-02")
    private val central = Warehouse(id = 1, name = "Központi raktár")
    private val szeged = Warehouse(id = 3, name = "Szegedi raktár")

    @Test
    fun `scanning the same product at the same location adds to one line`() {
        val draft = Draft().add(monitor, shelfA, null).add(monitor, shelfA, null).add(monitor, shelfA, null)

        assertEquals(1, draft.lines.size)
        assertEquals(BigDecimal(3), draft.lines.single().quantity)
    }

    @Test
    fun `the same product on another shelf is its own line`() {
        val draft = Draft().add(monitor, shelfA, null).add(monitor, shelfB, null)

        assertEquals(2, draft.lines.size)
        assertEquals(BigDecimal(2), draft.totalFor(monitor.id))
    }

    @Test
    fun `the line scanned last comes first`() {
        val draft = Draft().add(monitor, shelfA, null).add(paper, shelfA, null).add(monitor, shelfA, null)

        assertEquals(listOf(monitor.id, paper.id), draft.lines.map { it.productId })
    }

    @Test
    fun `setting a quantity to zero removes the line`() {
        val draft = Draft().add(monitor, shelfA, null).add(paper, null, null)
        val key = draft.lines.first { it.productId == monitor.id }.key

        val changed = draft.setQuantity(key, BigDecimal.ZERO)

        assertEquals(listOf(paper.id), changed.lines.map { it.productId })
        assertEquals(draft.lines.size, 2) // the original is untouched
    }

    @Test
    fun `a transfer target chosen later applies to every line and merges duplicates`() {
        val target = Location(id = 101, warehouseId = 3, code = "C-02-01")
        val draft = Draft()
            .add(monitor, shelfA, null)
            .add(monitor, shelfA, target)
            .add(paper, shelfB, null)
            .withTarget(target)

        assertEquals(2, draft.lines.size)
        assertTrue(draft.lines.all { it.to == target })
        assertEquals(BigDecimal(2), draft.lines.first { it.productId == monitor.id }.quantity)
        assertEquals(shelfB, draft.lines.first { it.productId == paper.id }.from)
    }

    @Test
    fun `stock in body names the warehouse and each line's location`() {
        val body = Draft().add(paper, null, null).add(monitor, shelfA, null, BigDecimal("1.5"))
            .requestBody(BookingMode.IN, central, null, "  Szállítólevél 118  ")

        assertEquals(1, body["warehouse_id"]!!.jsonPrimitive.int)
        assertEquals("Szállítólevél 118", body["note"]!!.jsonPrimitive.content)
        val items = body["items"] as JsonArray
        val first = items[0] as JsonObject
        assertEquals(12, first["product_id"]!!.jsonPrimitive.int)
        assertEquals("1.5", first["quantity"]!!.jsonPrimitive.content)
        assertEquals(24, first["location_id"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, (items[1] as JsonObject)["location_id"])
        assertFalse(body.containsKey("from_warehouse_id"))
    }

    @Test
    fun `transfer body carries both warehouses and both locations per line`() {
        val target = Location(id = 101, warehouseId = 3, code = "C-02-01")
        val body = Draft().add(monitor, shelfA, target).requestBody(BookingMode.TRANSFER, central, szeged, "")

        assertEquals(1, body["from_warehouse_id"]!!.jsonPrimitive.int)
        assertEquals(3, body["to_warehouse_id"]!!.jsonPrimitive.int)
        assertFalse(body.containsKey("warehouse_id"))
        assertFalse(body.containsKey("note"))
        val line = (body["items"] as JsonArray)[0] as JsonObject
        assertEquals(24, line["from_location_id"]!!.jsonPrimitive.int)
        assertEquals(101, line["to_location_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `quantities parse with comma or dot, positive, at most three decimals`() {
        assertEquals(BigDecimal("1.5"), parseQuantity("1,5"))
        assertEquals(BigDecimal("2.125"), parseQuantity(" 2.125 "))
        assertEquals(BigDecimal("100"), parseQuantity("100"))
        assertEquals(BigDecimal("3"), parseQuantity("3.000"))
        assertNull(parseQuantity("0"))
        assertNull(parseQuantity("-1"))
        assertNull(parseQuantity("1.0001"))
        assertNull(parseQuantity("abc"))
        assertNull(parseQuantity(""))
        assertNull(parseQuantity("100000000000"))
    }

    @Test
    fun `quantities display without trailing zeros in the locale's format`() {
        val hu = Locale.forLanguageTag("hu-HU")
        assertEquals("1,5", formatQuantity("1.500", hu))
        assertEquals("134", formatQuantity("134.000", hu))
        assertEquals("1.5", formatQuantity(BigDecimal("1.5"), Locale.ENGLISH))
        assertTrue(formatQuantity("-17.000", hu).endsWith("17"))
    }
}
