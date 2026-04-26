package com.tequipy.allocator.application

import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CreateEquipmentUseCase(private val repository: EquipmentRepository) {
    fun execute(equipment: Equipment): Equipment = repository.save(equipment)
}

@Service
class GetEquipmentUseCase(private val repository: EquipmentRepository) {
    fun findById(id: UUID): Equipment =
        repository.findById(id).orElseThrow { EquipmentNotFoundException(id) }
}

@Service
class ListEquipmentUseCase(private val repository: EquipmentRepository) {
    fun findAll(type: EquipmentType?, status: EquipmentStatus?): List<Equipment> =
        repository.findByTypeAndStatus(type, status)
}

@Service
class RetireEquipmentUseCase(
    private val repository: EquipmentRepository,
    private val getEquipment: GetEquipmentUseCase,
) {
    @Transactional
    fun execute(id: UUID, reason: String): Equipment {
        val equipment = getEquipment.findById(id)
        return when (equipment.status) {
            EquipmentStatus.RETIRED -> equipment
            EquipmentStatus.RESERVED, EquipmentStatus.ASSIGNED ->
                throw EquipmentInUseException(id, equipment.status)
            EquipmentStatus.AVAILABLE -> repository.save(equipment.retire(reason))
        }
    }
}

class EquipmentNotFoundException(id: UUID) : RuntimeException("Equipment $id not found")

class EquipmentInUseException(val equipmentId: UUID, val currentStatus: EquipmentStatus) :
    RuntimeException("Equipment $equipmentId cannot be retired while $currentStatus")