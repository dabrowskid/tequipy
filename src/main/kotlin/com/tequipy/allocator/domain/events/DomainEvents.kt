package com.tequipy.allocator.domain.events

import java.time.Instant
import java.util.UUID

sealed interface DomainEvent {
    val occurredAt: Instant
}

data class EquipmentRetired(
    val equipmentId: UUID,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
