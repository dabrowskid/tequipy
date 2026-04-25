package com.tequipy.allocator.infrastructure.persistence

import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EquipmentRepository : JpaRepository<Equipment, UUID> {

    @Query("SELECT e FROM Equipment e WHERE (:type IS NULL OR e.type = :type) AND (:status IS NULL OR e.status = :status)")
    fun findByTypeAndStatus(type: EquipmentType?, status: EquipmentStatus?): List<Equipment>

    fun findByStatusAndType(status: EquipmentStatus, type: EquipmentType): List<Equipment>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Equipment e WHERE e.status = :status AND e.type IN :types ORDER BY e.id")
    fun findAndLockByStatusAndTypeIn(
        status: EquipmentStatus,
        types: Collection<EquipmentType>,
    ): List<Equipment>
}
