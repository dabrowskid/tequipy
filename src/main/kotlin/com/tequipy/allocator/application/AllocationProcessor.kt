package com.tequipy.allocator.application

import com.tequipy.allocator.domain.allocation.AllocationResult
import com.tequipy.allocator.domain.allocation.Allocator
import com.tequipy.allocator.domain.events.AllocationCreated
import com.tequipy.allocator.domain.model.AllocationEquipmentSlot
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.infrastructure.persistence.AllocationRequestRepository
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.UUID

@Component
class AllocationProcessor(
    private val allocationRequestRepository: AllocationRequestRepository,
    private val equipmentRepository: EquipmentRepository,
    private val allocator: Allocator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onAllocationCreated(event: AllocationCreated) {
        try {
            process(event.allocationId)
        } catch (e: Exception) {
            log.error("Failed to process allocation ${event.allocationId}", e)
        }
    }

    private fun process(allocationId: UUID) {
        val request = allocationRequestRepository.findById(allocationId).orElse(null) ?: return
        if (request.state != AllocationState.PENDING) return

        val types = request.policy.slots.map { it.type }.distinct()
        val candidates = equipmentRepository.findAndLockByStatusAndTypeIn(EquipmentStatus.AVAILABLE, types)

        when (val result = allocator.allocate(request.policy, candidates)) {
            is AllocationResult.Failure -> {
                allocationRequestRepository.save(request.fail(result.reason))
            }

            is AllocationResult.Success -> {
                val assignments = result.assignments.map { (slotIdx, equipmentId) ->
                    AllocationEquipmentSlot(equipmentId = equipmentId, slotIndex = slotIdx)
                }.toSet()
                val reservedIds = assignments.map { it.equipmentId }.toSet()
                val now = Instant.now()
                candidates.filter { it.id in reservedIds }.forEach { eq ->
                    equipmentRepository.save(eq.copy(status = EquipmentStatus.RESERVED, updatedAt = now))
                }
                allocationRequestRepository.save(request.reserve(assignments))
            }
        }
    }
}