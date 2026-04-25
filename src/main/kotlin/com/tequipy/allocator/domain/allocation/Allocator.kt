package com.tequipy.allocator.domain.allocation

import com.tequipy.allocator.domain.model.Equipment
import java.time.LocalDate
import java.util.UUID

sealed interface AllocationResult {
    data class Success(val assignments: Map<Int, UUID>) : AllocationResult
    data class Failure(val reason: String) : AllocationResult
}

class Allocator {

    fun allocate(policy: AllocationPolicy, candidates: List<Equipment>): AllocationResult {
        // Expand multi-count slots into individual slot vertices
        val slots = policy.slots.flatMap { req -> (0 until req.count).map { req } }
        if (slots.isEmpty()) return AllocationResult.Success(emptyMap())

        val available = candidates.filter { it.status.name == "AVAILABLE" }

        // For each expanded slot, find eligible candidates
        val slotCandidates: List<List<Equipment>> = slots.map { req ->
            available.filter { eq ->
                eq.type == req.type && eq.conditionScore.toDouble() >= req.minCondition
            }
        }

        // Early feasibility check: each slot must have at least one candidate
        slots.forEachIndexed { i, req ->
            if (slotCandidates[i].isEmpty()) {
                return AllocationResult.Failure(
                    "No available ${req.type} with condition ≥ ${req.minCondition}"
                )
            }
        }

        // Collect the union of all candidates that appear in at least one slot's list
        val allCandidates = slotCandidates.flatten().distinctBy { it.id }
        val n = maxOf(slots.size, allCandidates.size)

        // Build cost matrix (slots as rows, candidates as columns), padded to n×n.
        // Padding rows (i >= slots.size) absorb extra candidates at zero cost.
        // Padding columns (j >= allCandidates.size) are forbidden for real slot rows.
        val INF = Int.MAX_VALUE / 2
        val cost = Array(n) { i -> IntArray(n) { if (i >= slots.size) 0 else INF } }

        val today = LocalDate.now()

        slots.forEachIndexed { si, req ->
            allCandidates.forEachIndexed { ci, eq ->
                if (eq.type == req.type && eq.conditionScore.toDouble() >= req.minCondition) {
                    // Soft score: higher is better → negate for min-cost
                    var score = 0
                    score += (eq.conditionScore.toDouble() * 100).toInt()
                    if (req.preferredBrands.isNotEmpty() && eq.brand in req.preferredBrands) score += 50
                    if (policy.preferRecent) {
                        val ageDays = today.toEpochDay() - eq.purchaseDate.toEpochDay()
                        score += maxOf(0, 365 - ageDays.toInt().coerceAtLeast(0)).coerceAtMost(30)
                    }
                    cost[si][ci] = -score
                }
            }
        }

        val assignment = hungarian(cost, n)
            ?: return AllocationResult.Failure(
                "Could not find a valid assignment for all slots (conflict or insufficient inventory)"
            )

        // Verify all real slots got feasible assignments (not padded dummy columns)
        val result = mutableMapOf<Int, UUID>()
        for (si in slots.indices) {
            val ci = assignment[si]
            if (ci >= allCandidates.size || cost[si][ci] >= INF) {
                return AllocationResult.Failure(
                    "Could not find a valid assignment for all slots (conflict or insufficient inventory)"
                )
            }
            result[si] = allCandidates[ci].id
        }

        return AllocationResult.Success(result)
    }

    /**
     * Hungarian algorithm (Kuhn–Munkres) for minimum-cost assignment on an n×n matrix.
     * Returns an int array where result[row] = column assigned to that row, or null if infeasible.
     *
     * Uses the standard O(n³) potential-based implementation.
     */
    private fun hungarian(costMatrix: Array<IntArray>, n: Int): IntArray? {
        val INF = Int.MAX_VALUE / 2
        val u = IntArray(n + 1)
        val v = IntArray(n + 1)
        // p[j] = row assigned to column j (0 = unassigned); way[j] = predecessor column in augmenting path
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

        // Build result: for each row i, find column j where p[j] == i+1
        val ans = IntArray(n)
        for (j in 1..n) {
            if (p[j] != 0) ans[p[j] - 1] = j - 1
        }
        return ans
    }
}
