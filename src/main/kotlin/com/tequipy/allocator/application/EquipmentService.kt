package com.tequipy.allocator.application

import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class EquipmentService(private val repository: EquipmentRepository) {

    fun create(equipment: Equipment): Equipment = repository.save(equipment)

    fun findById(id: UUID): Equipment = repository.findById(id).orElseThrow { EquipmentNotFoundException(id) }

    fun findAll(type: EquipmentType?, status: EquipmentStatus?): List<Equipment> =
        repository.findByTypeAndStatus(type, status)

    @Transactional
    fun retire(id: UUID, reason: String): Equipment {
        val equipment = findById(id)
        if (equipment.status == EquipmentStatus.RETIRED) return equipment
        return repository.save(equipment.retire(reason))
    }
}

class EquipmentNotFoundException(id: UUID) : RuntimeException("Equipment $id not found")
