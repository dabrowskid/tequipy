package com.tequipy.allocator.application

import com.tequipy.allocator.domain.model.AllocationRequest
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.IdempotencyKey
import com.tequipy.allocator.domain.model.IdempotencyKeyId
import com.tequipy.allocator.domain.model.IdempotentOperation
import com.tequipy.allocator.infrastructure.persistence.AllocationRequestRepository
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import com.tequipy.allocator.infrastructure.persistence.IdempotencyKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ConfirmAllocationUseCase(
    private val allocationRequestRepository: AllocationRequestRepository,
    private val equipmentRepository: EquipmentRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
) {
    @Transactional
    fun execute(allocationId: UUID, idempotencyKey: String?): AllocationRequest {
        idempotencyKey?.let { key ->
            replayIfPresent(key, IdempotentOperation.CONFIRM, allocationId)?.let { return it }
        }

        val request = allocationRequestRepository.findById(allocationId)
            .orElseThrow { AllocationNotFoundException(allocationId) }

        val updated = when (request.state) {
            AllocationState.CONFIRMED -> request
            AllocationState.RESERVED -> {
                val now = Instant.now()
                val ids = request.equipments.map { it.equipmentId }
                equipmentRepository.findAllById(ids).forEach { eq ->
                    equipmentRepository.save(eq.copy(status = EquipmentStatus.ASSIGNED, updatedAt = now))
                }
                allocationRequestRepository.save(request.confirm())
            }
            else -> throw InvalidAllocationStateException(allocationId, request.state, "confirm")
        }

        recordKey(idempotencyKey, IdempotentOperation.CONFIRM, updated)
        return updated
    }

    private fun replayIfPresent(
        key: String,
        op: IdempotentOperation,
        allocationId: UUID,
    ): AllocationRequest? {
        val stored = idempotencyKeyRepository.findById(IdempotencyKeyId(key, op)).orElse(null) ?: return null
        if (stored.allocationId != allocationId) {
            throw IdempotencyKeyConflictException(key, op)
        }
        return allocationRequestRepository.findById(stored.allocationId).orElseThrow {
            AllocationNotFoundException(stored.allocationId)
        }
    }

    private fun recordKey(key: String?, op: IdempotentOperation, request: AllocationRequest) {
        if (key.isNullOrBlank()) return
        idempotencyKeyRepository.save(
            IdempotencyKey(
                id = IdempotencyKeyId(key, op),
                allocationId = request.id,
                state = request.state,
            )
        )
    }
}

@Service
class CancelAllocationUseCase(
    private val allocationRequestRepository: AllocationRequestRepository,
    private val equipmentRepository: EquipmentRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
) {
    @Transactional
    fun execute(allocationId: UUID, idempotencyKey: String?): AllocationRequest {
        idempotencyKey?.let { key ->
            val stored = idempotencyKeyRepository.findById(IdempotencyKeyId(key, IdempotentOperation.CANCEL))
                .orElse(null)
            if (stored != null) {
                if (stored.allocationId != allocationId) {
                    throw IdempotencyKeyConflictException(key, IdempotentOperation.CANCEL)
                }
                return allocationRequestRepository.findById(stored.allocationId).orElseThrow {
                    AllocationNotFoundException(stored.allocationId)
                }
            }
        }

        val request = allocationRequestRepository.findById(allocationId)
            .orElseThrow { AllocationNotFoundException(allocationId) }

        val updated = when (request.state) {
            AllocationState.CANCELLED -> request
            AllocationState.PENDING, AllocationState.RESERVED -> {
                val now = Instant.now()
                val ids = request.equipments.map { it.equipmentId }
                if (ids.isNotEmpty()) {
                    equipmentRepository.findAllById(ids).forEach { eq ->
                        equipmentRepository.save(eq.copy(status = EquipmentStatus.AVAILABLE, updatedAt = now))
                    }
                }
                allocationRequestRepository.save(request.cancel())
            }
            else -> throw InvalidAllocationStateException(allocationId, request.state, "cancel")
        }

        if (!idempotencyKey.isNullOrBlank()) {
            idempotencyKeyRepository.save(
                IdempotencyKey(
                    id = IdempotencyKeyId(idempotencyKey, IdempotentOperation.CANCEL),
                    allocationId = updated.id,
                    state = updated.state,
                )
            )
        }
        return updated
    }
}

class InvalidAllocationStateException(
    val allocationId: UUID,
    val currentState: AllocationState,
    val operation: String,
) : RuntimeException("Cannot $operation allocation $allocationId in state $currentState")

class IdempotencyKeyConflictException(
    key: String,
    operation: IdempotentOperation,
) : RuntimeException("Idempotency-Key '$key' already used for $operation on a different allocation")