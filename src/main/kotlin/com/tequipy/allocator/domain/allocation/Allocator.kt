package com.tequipy.allocator.domain.allocation

import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import java.time.LocalDate
import java.util.UUID

sealed interface AllocationResult {
    data class Success(val assignments: Map<Int, UUID>) : AllocationResult
    data class Failure(val reason: String) : AllocationResult
}

class Allocator {

    private data class ExpandedSlot(val globalIndex: Int, val req: SlotRequirement)

    fun allocate(policy: AllocationPolicy, candidates: List<Equipment>): AllocationResult {
        if (policy.slots.isEmpty()) return AllocationResult.Success(emptyMap())

        val expanded = mutableListOf<ExpandedSlot>()
        var idx = 0
        policy.slots.forEach { req ->
            repeat(req.count) {
                expanded += ExpandedSlot(idx, req)
                idx++
            }
        }

        val today = LocalDate.now()
        val available = candidates.filter { it.status == EquipmentStatus.AVAILABLE }
        val candidatesByType = available.groupBy { it.type }
        val slotsByType = expanded.groupBy { it.req.type }

        val assignments = mutableMapOf<Int, UUID>()
        for ((type, slotsOfType) in slotsByType) {
            val pool = candidatesByType[type].orEmpty()
            val sub = if (slotsOfType.size == 1) {
                argmax(slotsOfType.single(), pool, today)
            } else {
                hungarianForType(slotsOfType, pool, today)
            } ?: return AllocationResult.Failure(
                "Could not satisfy ${slotsOfType.size} $type slot(s) from ${pool.size} candidate(s)"
            )
            assignments += sub
        }

        return AllocationResult.Success(assignments)
    }

    private fun argmax(slot: ExpandedSlot, pool: List<Equipment>, today: LocalDate): Map<Int, UUID>? {
        var bestId: UUID? = null
        var bestScore = Int.MIN_VALUE
        for (eq in pool) {
            if (!eligible(eq, slot.req)) continue
            val s = score(eq, slot.req, today)
            if (s > bestScore) {
                bestScore = s
                bestId = eq.id
            }
        }
        return bestId?.let { mapOf(slot.globalIndex to it) }
    }

    private fun hungarianForType(
        slotsOfType: List<ExpandedSlot>,
        pool: List<Equipment>,
        today: LocalDate,
    ): Map<Int, UUID>? {
        if (slotsOfType.size > pool.size) return null

        val s = slotsOfType.size
        val c = pool.size
        val n = c

        val INF = Int.MAX_VALUE / 2
        val cost = Array(n) { i -> IntArray(n) { if (i >= s) 0 else INF } }

        for (si in 0 until s) {
            val req = slotsOfType[si].req
            for (ci in 0 until c) {
                val eq = pool[ci]
                if (eligible(eq, req)) {
                    cost[si][ci] = -score(eq, req, today)
                }
            }
        }

        val assignment = hungarian(cost, n) ?: return null
        val out = mutableMapOf<Int, UUID>()
        for (si in 0 until s) {
            val ci = assignment[si]
            if (ci < 0 || ci >= c || cost[si][ci] >= INF) return null
            out[slotsOfType[si].globalIndex] = pool[ci].id
        }
        return out
    }

    private fun eligible(eq: Equipment, req: SlotRequirement): Boolean =
        eq.type == req.type && eq.conditionScore.toDouble() >= req.minCondition

    private fun score(eq: Equipment, req: SlotRequirement, today: LocalDate): Int {
        var s = (eq.conditionScore.toDouble() * 100).toInt()
        if (req.preferredBrands.isNotEmpty() && eq.brand in req.preferredBrands) s += 50
        if (req.preferRecent) {
            val ageDays = today.toEpochDay() - eq.purchaseDate.toEpochDay()
            s += maxOf(0, 365 - ageDays.toInt().coerceAtLeast(0)).coerceAtMost(30)
        }
        return s
    }

    /**
     * Hungarian algorithm (Kuhn–Munkres) for minimum-cost assignment on an n×n matrix.
     * Returns result[row] = column assigned, or null if infeasible.
     */
    private fun hungarian(costMatrix: Array<IntArray>, n: Int): IntArray? {
        val INF = Int.MAX_VALUE / 2
        val u = IntArray(n + 1)
        val v = IntArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minVal = IntArray(n + 1) { INF }
            val used = BooleanArray(n + 1)
            var feasible = true
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = INF
                var j1 = -1
                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = costMatrix[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minVal[j]) {
                            minVal[j] = cur
                            way[j] = j0
                        }
                        if (minVal[j] < delta) {
                            delta = minVal[j]
                            j1 = j
                        }
                    }
                }
                if (j1 == -1) { feasible = false; break }
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minVal[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            if (!feasible) return null
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val ans = IntArray(n)
        for (j in 1..n) {
            if (p[j] != 0) ans[p[j] - 1] = j - 1
        }
        return ans
    }
}
