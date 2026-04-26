package com.tequipy.allocator.domain.events

import java.time.Instant
import java.util.UUID

sealed interface DomainEvent {
    val occurredAt: Instant
}

data class AllocationCreated(
    val allocationId: UUID,
    val employeeId: UUID,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
