package com.tequipy.allocator.domain.allocation

import com.tequipy.allocator.domain.model.EquipmentType

data class AllocationPolicy(
    val slots: List<SlotRequirement>,
    val preferRecent: Boolean = false,
)

data class SlotRequirement(
    val type: EquipmentType,
    val minCondition: Double = 0.0,
    val preferredBrands: List<String> = emptyList(),
    val count: Int = 1,
)
