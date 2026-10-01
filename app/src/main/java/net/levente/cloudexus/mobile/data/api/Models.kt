package net.levente.cloudexus.mobile.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val email: String = "",
    val role: String = "user",
    @SerialName("role_code") val roleCode: String? = null,
    @SerialName("role_name") val roleName: String? = null,
    /**
     * What the user's role may do, the keys of the web app's permission matrix.
     * Null from a server older than roles: then nothing is hidden, and the
     * server still decides every request.
     */
    val permissions: List<String>? = null,
) {
    fun can(permission: String): Boolean = permissions == null || permission in permissions

    /** Stock in, out and transfers. */
    val canMoveStock: Boolean get() = can(STOCK_MOVE)

    companion object {
        const val STOCK_MOVE = "stock.move"
        const val STOCKTAKING = "stocktaking.manage"
    }
}

@Serializable
data class LoginResult(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    val user: User,
)

@Serializable
data class Warehouse(
    val id: Int,
    val name: String,
    val address: String? = null,
    @SerialName("is_active") val isActive: Int = 1,
)

@Serializable
data class Location(
    val id: Int,
    @SerialName("warehouse_id") val warehouseId: Int,
    val code: String,
    val name: String? = null,
    @SerialName("is_active") val isActive: Int = 1,
)

@Serializable
data class Language(
    val code: String,
    @SerialName("is_active") val isActive: Int = 1,
)

@Serializable
data class StockRow(
    @SerialName("warehouse_id") val warehouseId: Int,
    @SerialName("warehouse_name") val warehouseName: String,
    @SerialName("location_id") val locationId: Int? = null,
    @SerialName("location_code") val locationCode: String? = null,
    val quantity: String,
)

@Serializable
data class Product(
    val id: Int,
    val sku: String,
    val barcode: String? = null,
    val name: String,
    val unit: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("matched_by") val matchedBy: String = "barcode",
    @SerialName("stock_total") val stockTotal: String = "0",
    val stock: List<StockRow> = emptyList(),
    val price: String? = null,
    @SerialName("sale_price") val salePrice: String? = null,
    @SerialName("vat_rate") val vatRate: String? = null,
    @SerialName("min_stock") val minStock: String? = null,
    /** Null from a server older than 2026-10, which did not say. */
    @SerialName("below_min_stock") val belowMinStock: Boolean? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class Envelope<T>(val data: T)

@Serializable
data class Page<T>(val data: List<T>, val meta: PageMeta)

@Serializable
data class PageMeta(
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
)


/** One of the user's own movements of the day. */
@Serializable
data class MyMovement(
    val id: Int,
    val type: String,
    @SerialName("warehouse_id") val warehouseId: Int,
    @SerialName("warehouse_name") val warehouseName: String,
    @SerialName("location_code") val locationCode: String? = null,
    @SerialName("product_id") val productId: Int,
    val sku: String,
    @SerialName("product_name") val productName: String,
    val unit: String? = null,
    val quantity: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Shelf(
    @SerialName("location_id") val locationId: Int? = null,
    @SerialName("location_code") val locationCode: String? = null,
    val quantity: String,
)

/** A confirmed customer order waiting to be picked. */
@Serializable
data class PickTask(
    val id: Int,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("order_date") val orderDate: String? = null,
    @SerialName("partner_name") val partnerName: String,
    @SerialName("line_count") val lineCount: Int = 0,
    @SerialName("total_quantity") val totalQuantity: String = "0",
)

@Serializable
data class PickLine(
    @SerialName("product_id") val productId: Int,
    val sku: String,
    val barcode: String? = null,
    @SerialName("product_name") val productName: String,
    val unit: String? = null,
    val quantity: String,
    @SerialName("in_warehouse") val inWarehouse: String = "0",
    val shelves: List<Shelf> = emptyList(),
)

@Serializable
data class PickOrder(
    val id: Int,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("partner_name") val partnerName: String,
    val lines: List<PickLine>,
)

/** A confirmed purchase order with goods still to come. */
@Serializable
data class ReceiveTask(
    val id: Int,
    @SerialName("po_number") val poNumber: String,
    @SerialName("order_date") val orderDate: String? = null,
    @SerialName("partner_name") val partnerName: String,
    @SerialName("line_count") val lineCount: Int = 0,
    val ordered: String = "0",
    val received: String = "0",
    @SerialName("partly_received") val partlyReceived: Boolean = false,
)

@Serializable
data class ReceiveLine(
    @SerialName("product_id") val productId: Int,
    val sku: String,
    val barcode: String? = null,
    @SerialName("product_name") val productName: String,
    val unit: String? = null,
    val ordered: String,
    val received: String,
    val remaining: String,
)

@Serializable
data class ReceiveOrder(
    val id: Int,
    @SerialName("po_number") val poNumber: String,
    @SerialName("partner_name") val partnerName: String,
    val lines: List<ReceiveLine>,
)
