package com.tequipy.allocator.benchmark

import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.allocation.Allocator
import com.tequipy.allocator.domain.allocation.SlotRequirement
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class AllocatorBenchmark {

    @Param("1000", "5000", "10000")
    var inventorySize: Int = 0

    private lateinit var allocator: Allocator
    private lateinit var candidates: List<Equipment>
    private lateinit var singleSlotPolicy: AllocationPolicy
    private lateinit var multiSlotPolicy: AllocationPolicy
    private lateinit var countExpandedPolicy: AllocationPolicy

    @Setup
    fun setup() {
        allocator = Allocator()
        val rng = Random(42)
        val brands = listOf("Dell", "Apple", "HP", "Lenovo", "Acer")
        val types = EquipmentType.entries.toTypedArray()
        candidates = (0 until inventorySize).map { i ->
            Equipment(
                id = UUID.randomUUID(),
                type = types[i % types.size],
                brand = brands[rng.nextInt(brands.size)],
                model = "Model-$i",
                status = EquipmentStatus.AVAILABLE,
                conditionScore = BigDecimal.valueOf(0.5 + rng.nextDouble() * 0.5)
                    .setScale(2, java.math.RoundingMode.HALF_UP),
                purchaseDate = LocalDate.now().minusDays(rng.nextInt(1825).toLong()),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        }

        singleSlotPolicy = AllocationPolicy(
            slots = listOf(SlotRequirement(EquipmentType.MAIN_COMPUTER, minCondition = 0.7)),
        )

        multiSlotPolicy = AllocationPolicy(
            slots = listOf(
                SlotRequirement(EquipmentType.MAIN_COMPUTER, minCondition = 0.7, preferRecent = true),
                SlotRequirement(EquipmentType.MONITOR, minCondition = 0.6, preferRecent = true),
                SlotRequirement(EquipmentType.KEYBOARD),
                SlotRequirement(EquipmentType.MOUSE),
            ),
        )

        countExpandedPolicy = AllocationPolicy(
            slots = listOf(
                SlotRequirement(EquipmentType.MAIN_COMPUTER, minCondition = 0.7),
                SlotRequirement(EquipmentType.MONITOR, minCondition = 0.6, count = 2),
                SlotRequirement(EquipmentType.KEYBOARD, preferredBrands = listOf("Apple")),
            ),
        )
    }

    @Benchmark
    fun singleSlot(bh: Blackhole) {
        bh.consume(allocator.allocate(singleSlotPolicy, candidates))
    }

    @Benchmark
    fun multiSlot(bh: Blackhole) {
        bh.consume(allocator.allocate(multiSlotPolicy, candidates))
    }

    @Benchmark
    fun countExpanded(bh: Blackhole) {
        bh.consume(allocator.allocate(countExpandedPolicy, candidates))
    }
}