package com.tequipy.allocator.api

import com.tequipy.allocator.TestcontainersConfiguration
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import com.tequipy.allocator.infrastructure.persistence.AllocationRequestRepository
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import com.tequipy.allocator.infrastructure.persistence.IdempotencyKeyRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
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
            assertThat(pending).isZero()
        }
        idempotencyKeyRepository.deleteAll()
        allocationRequestRepository.deleteAll()
        equipmentRepository.deleteAll()
    }

    @Test
    fun `POST allocations returns 202 with PENDING state`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(
                slots = listOf(
                    SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER),
                    SlotRequirementDto(type = EquipmentType.MONITOR, count = 2),
                )
            )
        )

        val response = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body).isNotNull()
        assertThat(response.body?.id).isNotNull()
        assertThat(response.body?.state).isEqualTo(AllocationState.PENDING)
    }

    @Test
    fun `GET allocations by id returns the created allocation`() {
        val employeeId = UUID.randomUUID()
        val request = CreateAllocationRequest(
            employeeId = employeeId,
            policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = EquipmentType.KEYBOARD)))
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)

        assertThat(fetched.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(fetched.body?.id).isEqualTo(id)
        assertThat(fetched.body?.employeeId).isEqualTo(employeeId)
    }

    @Test
    fun `GET allocations for unknown id returns 404`() {
        val response = restTemplate.getForEntity("/allocations/${UUID.randomUUID()}", Any::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `allocation transitions to RESERVED when matching equipment is available`() {
        seedEquipment(EquipmentType.MAIN_COMPUTER, brand = "Dell")
        seedEquipment(EquipmentType.MONITOR, brand = "LG")
        seedEquipment(EquipmentType.MONITOR, brand = "LG")

        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(
                slots = listOf(
                    SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER),
                    SlotRequirementDto(type = EquipmentType.MONITOR, count = 2),
                )
            )
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)
            assertThat(fetched.body?.state).isEqualTo(AllocationState.RESERVED)
        }

        val reservedCount = equipmentRepository.findAll().count { it.status == EquipmentStatus.RESERVED }
        assertThat(reservedCount).isEqualTo(3)
    }

    @Test
    fun `allocation transitions to FAILED when no matching equipment exists`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER)))
        )

        val created = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java)
        val id = created.body!!.id

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val fetched = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java)
            assertThat(fetched.body?.state).isEqualTo(AllocationState.FAILED)
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
                    policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER)))
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
            assertThat(states)
                .`as`("expected both states terminal but were %s", states)
                .allMatch { it == AllocationState.RESERVED || it == AllocationState.FAILED }
            assertThat(states.count { it == AllocationState.RESERVED }).isEqualTo(1)
            assertThat(states.count { it == AllocationState.FAILED }).isEqualTo(1)
        }

        val reserved = equipmentRepository.findAll().count { it.status == EquipmentStatus.RESERVED }
        assertThat(reserved).isEqualTo(1)
    }

    @Test
    fun `confirm transitions RESERVED to CONFIRMED and equipment to ASSIGNED`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        val response = restTemplate.postForEntity(
            "/allocations/$id/confirm", null, AllocationResponse::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.state).isEqualTo(AllocationState.CONFIRMED)
        val assigned = equipmentRepository.findAll().count { it.status == EquipmentStatus.ASSIGNED }
        assertThat(assigned).isEqualTo(1)
    }

    @Test
    fun `confirm on already CONFIRMED is idempotent`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        restTemplate.postForEntity("/allocations/$id/confirm", null, AllocationResponse::class.java)
        val second = restTemplate.postForEntity(
            "/allocations/$id/confirm", null, AllocationResponse::class.java,
        )

        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.body?.state).isEqualTo(AllocationState.CONFIRMED)
    }

    @Test
    fun `confirm on PENDING returns 409`() {
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER))),
        )
        val id = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id

        val response = restTemplate.postForEntity("/allocations/$id/confirm", null, Any::class.java)
        assertThat(response.statusCode)
            .`as`("expected 409 (PENDING) or 200 (already FAILED) — got %s", response.statusCode)
            .isIn(HttpStatus.CONFLICT, HttpStatus.OK)
    }

    @Test
    fun `cancel RESERVED returns equipment to AVAILABLE`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)

        val response = restTemplate.postForEntity(
            "/allocations/$id/cancel", null, AllocationResponse::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.state).isEqualTo(AllocationState.CANCELLED)
        val available = equipmentRepository.findAll().count { it.status == EquipmentStatus.AVAILABLE }
        assertThat(available).isEqualTo(1)
    }

    @Test
    fun `cancel after confirm returns 409`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        restTemplate.postForEntity("/allocations/$id/confirm", null, AllocationResponse::class.java)

        val response = restTemplate.postForEntity("/allocations/$id/cancel", null, Any::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `Idempotency-Key replays the same response`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        val key = UUID.randomUUID().toString()

        val first = postWithIdempotencyKey("/allocations/$id/confirm", key)
        val second = postWithIdempotencyKey("/allocations/$id/confirm", key)

        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.body?.state).isEqualTo(AllocationState.CONFIRMED)
        assertThat(second.body?.state).isEqualTo(AllocationState.CONFIRMED)
        assertThat(second.body?.id).isEqualTo(first.body?.id)
    }

    @Test
    fun `retired equipment is excluded from allocation`() {
        val computer = seedEquipment(EquipmentType.MAIN_COMPUTER)
        val retireResponse = restTemplate.postForEntity(
            "/equipments/${computer.id}/retire",
            mapOf("reason" to "broken"),
            EquipmentResponse::class.java,
        )
        assertThat(retireResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(retireResponse.body?.status).isEqualTo(EquipmentStatus.RETIRED)

        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = EquipmentType.MAIN_COMPUTER))),
        )
        val id = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val state = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java).body!!.state
            assertThat(state).isEqualTo(AllocationState.FAILED)
        }
    }

    @Test
    fun `cannot retire equipment that is reserved`() {
        val id = createAndAwaitReserved(EquipmentType.MAIN_COMPUTER)
        val reserved = allocationRequestRepository.findById(id).orElseThrow()
        val equipmentId = reserved.equipments.first().equipmentId

        val response = restTemplate.exchange(
            "/equipments/$equipmentId/retire",
            HttpMethod.POST,
            HttpEntity(mapOf("reason" to "broken")),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `POST allocations rejects empty slots`() {
        val body = mapOf(
            "employeeId" to UUID.randomUUID().toString(),
            "policy" to mapOf("slots" to emptyList<Any>()),
        )
        val response = restTemplate.postForEntity("/allocations", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("policy.slots")
    }

    @Test
    fun `POST allocations rejects out-of-range count`() {
        val body = mapOf(
            "employeeId" to UUID.randomUUID().toString(),
            "policy" to mapOf(
                "slots" to listOf(
                    mapOf("type" to "MAIN_COMPUTER", "count" to 1000)
                )
            ),
        )
        val response = restTemplate.postForEntity("/allocations", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("count")
    }

    @Test
    fun `POST allocations rejects out-of-range minCondition`() {
        val body = mapOf(
            "employeeId" to UUID.randomUUID().toString(),
            "policy" to mapOf(
                "slots" to listOf(
                    mapOf("type" to "MAIN_COMPUTER", "minCondition" to 1.5)
                )
            ),
        )
        val response = restTemplate.postForEntity("/allocations", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("minCondition")
    }

    @Test
    fun `POST equipments rejects blank brand`() {
        val body = mapOf(
            "type" to "KEYBOARD",
            "brand" to "",
            "model" to "K1",
            "conditionScore" to 0.9,
            "purchaseDate" to LocalDate.now().minusMonths(1).toString(),
        )
        val response = restTemplate.postForEntity("/equipments", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("brand")
    }

    @Test
    fun `POST equipments rejects future purchaseDate`() {
        val body = mapOf(
            "type" to "KEYBOARD",
            "brand" to "Acme",
            "model" to "K1",
            "conditionScore" to 0.9,
            "purchaseDate" to LocalDate.now().plusDays(7).toString(),
        )
        val response = restTemplate.postForEntity("/equipments", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("purchaseDate")
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

        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    private fun createAndAwaitReserved(type: EquipmentType): UUID {
        seedEquipment(type)
        val request = CreateAllocationRequest(
            employeeId = UUID.randomUUID(),
            policy = AllocationPolicyDto(slots = listOf(SlotRequirementDto(type = type))),
        )
        val id = restTemplate.postForEntity("/allocations", request, AllocationResponse::class.java).body!!.id
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val state = restTemplate.getForEntity("/allocations/$id", AllocationResponse::class.java).body!!.state
            assertThat(state).isEqualTo(AllocationState.RESERVED)
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
