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
