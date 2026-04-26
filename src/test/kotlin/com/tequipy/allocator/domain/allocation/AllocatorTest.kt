package com.tequipy.allocator.domain.allocation

import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AllocatorTest {

    private val allocator = Allocator()

    private fun equipment(
        type: EquipmentType,
        conditionScore: Double = 0.9,
        brand: String = "Generic",
        purchaseDate: LocalDate = LocalDate.now().minusYears(1),
        status: EquipmentStatus = EquipmentStatus.AVAILABLE,
    ) = Equipment(
        id = UUID.randomUUID(),
        type = type,
        brand = brand,
        model = "Model-X",
        status = status,
        conditionScore = BigDecimal(conditionScore),
        purchaseDate = purchaseDate,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `happy path - single slot assigned`() {
        val pc = equipment(EquipmentType.MAIN_COMPUTER)
        val policy = AllocationPolicy(slots = listOf(SlotRequirement(EquipmentType.MAIN_COMPUTER)))
        val result = allocator.allocate(policy, listOf(pc))
        assertThat(result).isInstanceOf(AllocationResult.Success::class.java)
        val success = result as AllocationResult.Success
        assertThat(success.assignments).containsEntry(0, pc.id)
    }

    @Test
    fun `cross-slot monitor scenario - greedy would fail but matching succeeds`() {
        // Two monitors needed. Monitor A has condition 0.9, Monitor B has condition 0.7.
        // Slot 1 requires minCondition 0.8 (only A qualifies).
        // Slot 2 requires minCondition 0.5 (both qualify).
        // Greedy might pick A for slot 2 first, leaving slot 1 unsatisfiable.
        // Hungarian must assign A→slot1, B→slot2.
        val monitorA = equipment(EquipmentType.MONITOR, conditionScore = 0.9)
        val monitorB = equipment(EquipmentType.MONITOR, conditionScore = 0.7)

        val policy = AllocationPolicy(
            slots = listOf(
                SlotRequirement(EquipmentType.MONITOR, minCondition = 0.8),
                SlotRequirement(EquipmentType.MONITOR, minCondition = 0.5),
            )
        )

        val result = allocator.allocate(policy, listOf(monitorA, monitorB))
        assertThat(result).`as`("Expected success but got %s", result).isInstanceOf(AllocationResult.Success::class.java)
        val success = result as AllocationResult.Success
        assertThat(success.assignments)
            .hasSize(2)
            .containsEntry(0, monitorA.id)
            .containsEntry(1, monitorB.id)
        assertThat(success.assignments.values).containsExactlyInAnyOrder(monitorA.id, monitorB.id)
    }

    @Test
    fun `infeasible - returns failure when not enough equipment`() {
        val pc = equipment(EquipmentType.MAIN_COMPUTER)
        val policy = AllocationPolicy(
            slots = listOf(
                SlotRequirement(EquipmentType.MAIN_COMPUTER),
                SlotRequirement(EquipmentType.MAIN_COMPUTER),
            )
        )
        val result = allocator.allocate(policy, listOf(pc))
        assertThat(result).`as`("Expected failure but got %s", result).isInstanceOf(AllocationResult.Failure::class.java)
    }

    @Test
    fun `infeasible - condition too high`() {
        val pc = equipment(EquipmentType.MAIN_COMPUTER, conditionScore = 0.5)
        val policy = AllocationPolicy(
            slots = listOf(SlotRequirement(EquipmentType.MAIN_COMPUTER, minCondition = 0.8))
        )
        val result = allocator.allocate(policy, listOf(pc))
        assertThat(result).isInstanceOf(AllocationResult.Failure::class.java)
    }

    @Test
    fun `brand preference - preferred brand chosen when available`() {
        val apple = equipment(EquipmentType.MAIN_COMPUTER, brand = "Apple", conditionScore = 0.7)
        val dell = equipment(EquipmentType.MAIN_COMPUTER, brand = "Dell", conditionScore = 0.9)

        val policy = AllocationPolicy(
            slots = listOf(SlotRequirement(EquipmentType.MAIN_COMPUTER, preferredBrands = listOf("Apple")))
        )

        val result = allocator.allocate(policy, listOf(apple, dell)) as AllocationResult.Success
        assertThat(result.assignments).containsEntry(0, apple.id)
    }

    @Test
    fun `prefer recent - newer equipment selected`() {
        val old = equipment(EquipmentType.KEYBOARD, purchaseDate = LocalDate.now().minusYears(5))
        val new = equipment(EquipmentType.KEYBOARD, purchaseDate = LocalDate.now().minusMonths(1))

        val policy = AllocationPolicy(
            slots = listOf(SlotRequirement(EquipmentType.KEYBOARD, preferRecent = true)),
        )

        val result = allocator.allocate(policy, listOf(old, new)) as AllocationResult.Success
        assertThat(result.assignments).containsEntry(0, new.id)
    }

    @Test
    fun `count expansion - two monitors from count=2 slot`() {
        val m1 = equipment(EquipmentType.MONITOR)
        val m2 = equipment(EquipmentType.MONITOR)

        val policy = AllocationPolicy(
            slots = listOf(SlotRequirement(EquipmentType.MONITOR, count = 2))
        )

        val result = allocator.allocate(policy, listOf(m1, m2)) as AllocationResult.Success
        assertThat(result.assignments).hasSize(2)
        assertThat(result.assignments.values).containsExactlyInAnyOrder(m1.id, m2.id)
    }

    @Test
    fun `retired equipment is not assigned`() {
        val active = equipment(EquipmentType.MOUSE)
        val retired = equipment(EquipmentType.MOUSE, status = EquipmentStatus.RETIRED)

        val policy = AllocationPolicy(slots = listOf(SlotRequirement(EquipmentType.MOUSE)))
        val result = allocator.allocate(policy, listOf(active, retired)) as AllocationResult.Success
        assertThat(result.assignments).containsEntry(0, active.id)
    }

    @Test
    fun `empty policy returns empty success`() {
        val result = allocator.allocate(AllocationPolicy(emptyList()), listOf(equipment(EquipmentType.MOUSE)))
        assertThat(result).isEqualTo(AllocationResult.Success(emptyMap()))
    }

    @Test
    fun `tie breaking - higher condition score wins when no other preference`() {
        val good = equipment(EquipmentType.KEYBOARD, conditionScore = 0.95)
        val fair = equipment(EquipmentType.KEYBOARD, conditionScore = 0.7)

        val policy = AllocationPolicy(slots = listOf(SlotRequirement(EquipmentType.KEYBOARD)))
        val result = allocator.allocate(policy, listOf(good, fair)) as AllocationResult.Success
        assertThat(result.assignments).containsEntry(0, good.id)
    }
}
