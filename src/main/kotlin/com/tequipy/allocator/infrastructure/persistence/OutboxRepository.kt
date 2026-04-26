package com.tequipy.allocator.infrastructure.persistence

import com.tequipy.allocator.domain.model.OutboxEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OutboxRepository : JpaRepository<OutboxEntry, UUID> {
}
