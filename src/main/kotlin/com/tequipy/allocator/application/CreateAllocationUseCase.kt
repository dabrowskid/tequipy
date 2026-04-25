package com.tequipy.allocator.application

import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.model.AllocationRequest
import com.tequipy.allocator.domain.model.OutboxEntry
import com.tequipy.allocator.infrastructure.persistence.AllocationRequestRepository
import com.tequipy.allocator.infrastructure.persistence.OutboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CreateAllocationUseCase(
    private val allocationRequestRepository: AllocationRequestRepository,
    private val outboxRepository: OutboxRepository,
) {
    @Transactional
    fun execute(employeeId: UUID, policy: AllocationPolicy): AllocationRequest {
        val request = AllocationRequest(employeeId = employeeId, policy = policy)
        val saved = allocationRequestRepository.save(request)

        outboxRepository.save(
            OutboxEntry(
                aggregateType = "AllocationRequest",
                aggregateId = saved.id,
                eventType = "AllocationCreated",
                payload = mapOf(
                    "allocationId" to saved.id.toString(),
                    "employeeId" to saved.employeeId.toString(),
                ),
            )
        )

        return saved
    }
}

@Service
class AllocationQueryService(private val repository: AllocationRequestRepository) {
    fun findById(id: UUID): AllocationRequest = repository.findById(id).orElseThrow {
        AllocationNotFoundException(id)
    }
}

class AllocationNotFoundException(id: UUID) : RuntimeException("Allocation $id not found")
