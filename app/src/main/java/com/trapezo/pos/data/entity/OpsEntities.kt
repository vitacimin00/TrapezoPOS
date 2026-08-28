package com.trapezo.pos.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shifts",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["status"]),
        Index(value = ["openedAt"]),
        // SQLite UNIQUE permits many NULLs but only one OPEN sentinel (=1).
        Index(value = ["openGuard"], unique = true)
    ]
)
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val userNameSnapshot: String = "",
    val openingCash: Long,
    val totalCashSales: Long = 0,
    val totalNonCashSales: Long = 0,
    val cashIn: Long = 0,
    val cashOut: Long = 0,
    val expectedCash: Long = 0,
    val actualCash: Long = 0,
    val difference: Long = 0,
    val openedAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    val status: String = "OPEN",
    @ColumnInfo(defaultValue = "NULL") val openGuard: Int? = 1
)

@Entity(
    tableName = "cash_movements",
    indices = [Index(value = ["shiftId"]), Index(value = ["createdAt"])],
    foreignKeys = [ForeignKey(
        entity = ShiftEntity::class,
        parentColumns = ["id"],
        childColumns = ["shiftId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CashMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long,
    val type: String,
    val amount: Long,
    val note: String = "",
    val userId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "audit_logs", indices = [Index(value = ["userId"]), Index(value = ["createdAt"])])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long? = null,
    val action: String,
    val referenceType: String = "",
    val referenceId: Long? = null,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings", indices = [Index(value = ["key"], unique = true)])
data class SettingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String
)
