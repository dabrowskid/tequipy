package com.tequipy.allocator.domain.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EquipmentType { MAIN_COMPUTER, MONITOR, KEYBOARD, MOUSE }
enum class EquipmentStatus { AVAILABLE, RESERVED, ASSIGNED, RETIRED }

@Entity
@Table(name = "equipment")
data class Equipment(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: EquipmentType,

    @Column(nullable = false)
    val brand: String,

    @Column(nullable = false)
    val model: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: EquipmentStatus = EquipmentStatus.AVAILABLE,

    @Column(name = "condition_score", nullable = false)
    val conditionScore: BigDecimal,

    @Column(name = "purchase_date", nullable = false)
    val purchaseDate: LocalDate,

    @Column(name = "retired_reason")
    val retiredReason: String? = null,

    @Column(name = "retired_at")
    val retiredAt: Instant? = null,

    @Version
    val version: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    fun retire(reason: String): Equipment = copy(
        status = EquipmentStatus.RETIRED,
        retiredReason = reason,
        retiredAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
