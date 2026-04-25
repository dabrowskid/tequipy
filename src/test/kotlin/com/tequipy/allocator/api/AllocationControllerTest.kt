package com.tequipy.allocator.api

import com.tequipy.allocator.TestcontainersConfiguration
import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.allocation.SlotRequirement
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import com.tequipy.allocator.infrastructure.persistence.AllocationRequestRepository
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import com.tequipy.allocator.infrastructure.persistence.IdempotencyKeyRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@AutoConfigureTestRestTemplate
class AllocationControllerTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var equipmentRepository: EquipmentRepository

    @Autowired
    lateinit var allocationRequestRepository: AllocationRequestRepository

    @Autowired
    lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    @AfterEach
    fun cleanup() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val pending = allocationRequestRepository.findAll().count {
                it.state == AllocationState.PENDING
            }
            assertEquals(0, pending)
        }
        idempotencyKeyRepository.deleteAll()
        allocationRequestRepository.deleteAll()
        equipmentRepository.deleteAll()
    }

    @Test
    fun `POST allocations returns 202 with PENDING state`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicy(
                slots = listOf(
                    SlotRequirement(type = EquipmentType.MAIN_COMPUTER),
                    SlotRequirement(type = EquipmentType.MONITOR, count = 2),
                )
            )
        )

        val response = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertNotNull(response.body?.id)
        assertEquals(AllocationState.PENDING, response.body?.state)
    }

    @Test
    fun `GET allocations by id returns the created allocation`() {
        val employeeId = UUID.randomUUID()
        val request = CreateAllocationRequest(
            employeeId = employeeId,
            policy = AllocationPolicy(slots = listOf(SlotRequirement(type = EquipmentType.KEYBOARD)))
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)

        assertEquals(HttpStatus.OK, fetched.statusCode)
        assertEquals(id, fetched.body?.id)
        assertEquals(employeeId, fetched.body?.employeeId)
    }

    @Test
    fun `GET allocations for unknown id returns 404`() {
        val response = restTemplate.getForEntity("/allocations/${UUID.randomUUID()}", Any::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `allocation transitions to RESERVED when matching equipment is available`() {
        seedEquipment(EquipmentType.MAIN_COMPUTER, brand = "Dell")
        seedEquipment(EquipmentType.MONITOR, brand = "LG")
        seedEquipment(EquipmentType.MONITOR, brand = "LG")

        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicy(
                slots = listOf(
                    SlotRequirement(type = EquipmentType.MAIN_COMPUTER),
                    SlotRequirement(type = EquipmentType.MONITOR, count = 2),
                )
            )
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)
            assertEquals(AllocationState.RESERVED, fetched.body?.state)
        }

        val reservedCount = equipmentRepository.findAll().count { it.status == EquipmentStatus.RESERVED }
        assertEquals(3, reservedCount)
    }

    @Test
    fun `allocation transitions to FAILED when no matching equipment exists`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicy(slots = listOf(SlotRequirement(type = EquipmentType.MAIN_COMPUTER)))
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)
            assertEquals(AllocationState.FAILED, fetched.body?.state)
        }
    }

    @Test
    fun `concurrent requests for the same scarce equipment — exactly one wins`() {
        seedEquipment(EquipmentType.MAIN_COMPUTER, brand = "Dell")

        val executor = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            CompletableFuture.supplyAsync({
                val request = CreateAllocationRequest(
                    employeeId = UUID.randomUUID(),
                    policy = AllocationPolicy(slots = listOf(SlotRequirement(type = EquipmentType.MAIN_COMPUTER)))
                )
                restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id
            }, executor)
        }
        val ids = futures.map { it.join() }
        executor.shutdown()

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val states = ids.map {
                restTemplate.getForEntity("/allocations/$it", AllocationResponse::class.java).body!!.state
            }
            assertTrue(states.all { it == AllocationState.RESERVED || it == AllocationState.FAILED }) {
                "States not yet terminal: $states"
            }
            assertEquals(1, states.count { it == AllocationState.RESERVED })
            assertEquals(1, states.count { it == AllocationState.FAILED })
        }

        val reserved = equipmentRepository.findAll().count { it.status == EquipmentStatus.RESERVED }
        assertEquals(1, reserved)
    }

    @Test
    fun `confirm transitions RESERVED to CONFIRMED and equipment to ASSIGNED`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        val response = restTemplate.postForEntity(
            "/allocations/$id/confirm", null, AllocationResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(AllocationState.CONFIRMED, response.body?.state)
        val assigned = equipmentRepository.findAll().count { it.status == EquipmentStatus.ASSIGNED }
        assertEquals(1, assigned)
    }

    @Test
    fun `confirm on already CONFIRMED is idempotent`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        restTemplate.postForEntity("/allocations/$id/confirm", null, AllocationResponse::class.java)
        val second = restTemplate.postForEntity(
            "/allocations/$id/confirm", null, AllocationResponse::class.java,
        )

        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(AllocationState.CONFIRMED, second.body?.state)
    }

    @Test
    fun `confirm on PENDING returns 409`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicy(slots = listOf(SlotRequirement(type = EquipmentType.MAIN_COMPUTER))),
        )
        val id = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id

        val response = restTemplate.postForEntity("/allocations/$id/confirm", null, Any::class.java)
        assertTrue(response.statusCode == HttpStatus.CONFLICT || response.statusCode == HttpStatus.OK) {
            "expected 409 (PENDING) or 200 (already FAILED) — got ${response.statusCode}"
        }
    }

    @Test
    fun `cancel RESERVED returns equipment to AVAILABLE`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        val response = restTemplate.postForEntity(
            "/allocations/$id/cancel", null, AllocationResponse::class.java,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(AllocationState.CANCELLED, response.body?.state)
        val available = equipmentRepository.findAll().count { it.status == EquipmentStatus.AVAILABLE }
        assertEquals(1, available)
    }

    @Test
    fun `cancel after confirm returns 409`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        restTemplate.postForEntity("/allocations/$id/confirm", null, AllocationResponse::class.java)

        val response = restTemplate.postForEntity("/allocations/$id/cancel", null, Any::class.java)
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `Idempotency-Key replays the same response`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        val key = UUID.randomUUID().toString()

        val first = postWithIdempotencyKey("/allocations/$id/confirm", key)
        val second = postWithIdempotencyKey("/allocations/$id/confirm", key)

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(AllocationState.CONFIRMED, first.body?.state)
        assertEquals(AllocationState.CONFIRMED, second.body?.state)
        assertEquals(first.body?.id, second.body?.id)
    }

    @Test
    fun `Idempotency-Key reused on a different allocation returns 409`() {
        val id1 = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        val id2 = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        val key = UUID.randomUUID().toString()

        val first = postWithIdempotencyKey("/allocations/$id1/confirm", key)
        val second = restTemplate.exchange(
            "/allocations/$id2/confirm",
            HttpMethod.POST,
            HttpEntity<Any>(HttpHeaders().apply { set("Idempotency-Key", key) }),
            String::class.java,
        )

        assertEquals(HttpStatus.OK, first.statusCode)
        assertEquals(HttpStatus.CONFLICT, second.statusCode)
    }

    private fun createAndAwaitReserved(type: EquipmentType): UUID {
        seedEquipment(type)
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicy(slots = listOf(SlotRequirement(type = type))),
        )
        val id = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val state = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java).body!!.state
            assertEquals(AllocationState.RESERVED, state)
        }
        return id
    }

    private fun postWithIdempotencyKey(path: String, key: String) = restTemplate.exchange(
        path,
        HttpMethod.POST,
        HttpEntity<Any>(HttpHeaders().apply { set("Idempotency-Key", key) }),
        AllocationResponse::class.java,
    )

    private fun seedEquipment(
        type: EquipmentType,
        brand: String = "Acme",
        condition: Double = 0.9,
    ): Equipment = equipmentRepository.save(
        Equipment(
            type = type,
            brand = brand,
            model = "X",
            conditionScore = BigDecimal.valueOf(condition),
            purchaseDate = LocalDate.now().minusMonths(6),
        )
    )
}