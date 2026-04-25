package com.tequipy.allocator.domain.model

import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class IdempotentOperation { CONFIRM, CANCEL }

@Embeddable
data class IdempotencyKeyId(
    @Column(name = "key", nullable = false)
    val key: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false)
    val operation: IdempotentOperation,
) : Serializable

@Entity
@Table(name = "idempotency_key")
data class IdempotencyKey(
    @EmbeddedId
    val id: IdempotencyKeyId,

    @Column(name = "allocation_id", nullable = false)
    val allocationId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    val state: AllocationState,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)