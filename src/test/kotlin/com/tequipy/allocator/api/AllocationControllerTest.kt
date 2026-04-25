package com.tequipy.allocator.api

import com.tequipy.allocator.TestcontainersConfiguration
import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.allocation.SlotRequirement
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@AutoConfigureTestRestTemplate
class AllocationControllerTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

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
        assertEquals(AllocationState.PENDING, fetched.body?.state)
    }

    @Test
    fun `GET allocations for unknown id returns 404`() {
        val response = restTemplate.getForEntity("/allocations/${UUID.randomUUID()}", Any::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
