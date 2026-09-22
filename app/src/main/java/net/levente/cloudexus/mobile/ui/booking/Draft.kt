package net.levente.cloudexus.mobile.ui.booking

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.levente.cloudexus.mobile.data.api.Location
import net.levente.cloudexus.mobile.data.api.Product
import net.levente.cloudexus.mobile.data.api.Warehouse
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class BookingMode(val path: String) {
    IN("stock/in"),
    OUT("stock/out"),
    TRANSFER("stock/transfer"),
}

/**
 * One line of a booking being collected. [from] is the location of a stock-in
 * or stock-out line, and the source location of a transfer; [to] is only used
 * by transfers.
 */
data class DraftLine(
    val productId: Int,
    val sku: String,
    val name: String,
    val unit: String?,
    val from: Location?,
    val to: Location?,
    val quantity: BigDecimal,
) {
    val key: String get() = "$productId:${from?.id}:${to?.id}"
}

/**
 * The lines scanned so far. Immutable: every change returns a new draft.
 * Newest first, because the line just scanned is the one the worker looks at.
 */
data class Draft(val lines: List<DraftLine> = emptyList()) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** Adds [quantity] of the product; scanning the same product at the same location again adds to that line. */
    fun add(product: Product, from: Location?, to: Location?, quantity: BigDecimal = BigDecimal.ONE): Draft {
        val line = DraftLine(product.id, product.sku, product.name, product.unit, from, to, quantity)
        val existing = lines.firstOrNull { it.key == line.key }
        val merged = existing?.copy(quantity = existing.quantity + quantity) ?: line
        return Draft(listOf(merged) + lines.filterNot { it.key == line.key })
    }

    /** Sets a line's quantity; zero or less removes the line. */
    fun setQuantity(key: String, quantity: BigDecimal): Draft =
        if (quantity.signum() <= 0) remove(key)
        else Draft(lines.map { if (it.key == key) it.copy(quantity = quantity) else it })

    fun remove(key: String): Draft = Draft(lines.filterNot { it.key == key })

    /**
     * A transfer's target shelf belongs to the whole delivery, and is usually
     * chosen after the items are scanned, so it moves every line; lines that
     * end up identical are merged. (The source shelf stays per line: it is
     * where each item was picked from.)
     */
    fun withTarget(to: Location?): Draft {
        val merged = LinkedHashMap<String, DraftLine>()
        for (line in lines.map { it.copy(to = to) }) {
            merged[line.key] = merged[line.key]?.let { it.copy(quantity = it.quantity + line.quantity) } ?: line
        }
        return Draft(merged.values.toList())
    }

    /** Everything drafted of one product, over all locations. */
    fun totalFor(productId: Int): BigDecimal =
        lines.filter { it.productId == productId }.fold(BigDecimal.ZERO) { sum, line -> sum + line.quantity }

    /**
     * The request body for the booking endpoint. Every line names its own
     * location explicitly, so the server-side defaults never come into play.
     */
    fun requestBody(mode: BookingMode, warehouse: Warehouse, toWarehouse: Warehouse?, note: String): JsonObject =
        buildJsonObject {
            if (mode == BookingMode.TRANSFER) {
                put("from_warehouse_id", warehouse.id)
                put("to_warehouse_id", requireNotNull(toWarehouse) { "a transfer needs a target warehouse" }.id)
            } else {
                put("warehouse_id", warehouse.id)
            }
            if (note.isNotBlank()) put("note", note.trim())
            put("items", buildJsonArray {
                for (line in lines) {
                    add(buildJsonObject {
                        put("product_id", line.productId)
                        put("quantity", line.quantity.toPlainString())
                        if (mode == BookingMode.TRANSFER) {
                            put("from_location_id", line.from?.id?.let(::JsonPrimitive) ?: JsonNull)
                            put("to_location_id", line.to?.id?.let(::JsonPrimitive) ?: JsonNull)
                        } else {
                            put("location_id", line.from?.id?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    })
                }
            })
        }
}

/** Parses a typed quantity: "2", "1,5" or "1.25"; positive, at most 3 decimals (the server's precision). */
fun parseQuantity(text: String): BigDecimal? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toBigDecimalOrNull() ?: return null
    if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 3 || value >= BigDecimal("100000000000")) return null
    return value.setScale(maxOf(0, value.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY)
}

/** A quantity for display: no trailing zeros, the locale's decimal separator ("1,5" in Hungarian). */
fun formatQuantity(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
    val format = DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(locale))
    return format.format(value)
}

fun formatQuantity(value: String, locale: Locale = Locale.getDefault()): String =
    value.toBigDecimalOrNull()?.let { formatQuantity(it, locale) } ?: value
