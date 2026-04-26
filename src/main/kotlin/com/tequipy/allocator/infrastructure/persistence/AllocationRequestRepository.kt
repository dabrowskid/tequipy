package com.tequipy.allocator.infrastructure.persistence

import com.tequipy.allocator.domain.model.AllocationRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AllocationRequestRepository : JpaRepository<AllocationRequest, UUID> {
}
