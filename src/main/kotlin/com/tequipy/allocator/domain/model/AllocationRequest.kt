package com.tequipy.allocator.domain.model

import com.tequipy.allocator.domain.allocation.AllocationPolicy
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class AllocationState { PENDING, RESERVED, CONFIRMED, CANCELLED, FAILED }

@Embeddable
data class AllocationEquipmentSlot(
    @Column(name = "equipment_id", nullable = false) val equipmentId: UUID,
    @Column(name = "slot_index", nullable = false) val slotIndex: Int,
)

@Entity
@Table(name = "allocation_request")
data class AllocationRequest(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "employee_id", nullable = false)
    val employeeId: UUID,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val policy: AllocationPolicy,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: AllocationState = AllocationState.PENDING,

    @Column(name = "failure_reason")
    val failureReason: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "allocation_equipment",
        joinColumns = [JoinColumn(name = "allocation_id")],
    )
    val equipments: Set<AllocationEquipmentSlot> = emptySet(),

    @Version
    val version: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    fun reserve(assignments: Set<AllocationEquipmentSlot>): AllocationRequest = copy(
        state = AllocationState.RESERVED,
        equipments = assignments,
        updatedAt = Instant.now(),
    )

    fun confirm(): AllocationRequest = copy(state = AllocationState.CONFIRMED, updatedAt = Instant.now())

    fun cancel(): AllocationRequest = copy(
        state = AllocationState.CANCELLED,
        equipments = emptySet(),
        updatedAt = Instant.now(),
    )

    fun fail(reason: String): AllocationRequest = copy(
        state = AllocationState.FAILED,
        failureReason = reason,
        updatedAt = Instant.now(),
    )
}
