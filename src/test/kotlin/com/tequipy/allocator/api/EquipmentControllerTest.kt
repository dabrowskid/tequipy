package com.tequipy.allocator.api

import com.tequipy.allocator.TestcontainersConfiguration
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import com.tequipy.allocator.infrastructure.persistence.EquipmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@AutoConfigureTestRestTemplate
class EquipmentControllerTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var equipmentRepository: EquipmentRepository

    @AfterEach
    fun cleanup() {
        equipmentRepository.deleteAll()
    }

    @Test
    fun `POST equipments creates equipment and returns 201`() {
        val request = mapOf(
            "type" to "MAIN_COMPUTER",
            "brand" to "Dell",
            "model" to "XPS-15",
            "conditionScore" to 0.95,
            "purchaseDate" to LocalDate.now().minusMonths(3).toString(),
        )

        val response = restTemplate.postForEntity("/equipments", request, EquipmentResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body).isNotNull
        assertThat(response.body?.id).isNotNull
        assertThat(response.body?.type).isEqualTo(EquipmentType.MAIN_COMPUTER)
        assertThat(response.body?.brand).isEqualTo("Dell")
        assertThat(response.body?.model).isEqualTo("XPS-15")
        assertThat(response.body?.status).isEqualTo(EquipmentStatus.AVAILABLE)
        assertThat(response.body?.conditionScore).isEqualByComparingTo(BigDecimal("0.95"))
        assertThat(response.body?.retiredReason).isNull()
        assertThat(response.body?.retiredAt).isNull()
    }

    @Test
    fun `GET equipments by id returns the equipment`() {
        val seeded = seedEquipment(EquipmentType.KEYBOARD, brand = "Logitech")

        val response = restTemplate.getForEntity("/equipments/${seeded.id}", EquipmentResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.id).isEqualTo(seeded.id)
        assertThat(response.body?.brand).isEqualTo("Logitech")
        assertThat(response.body?.type).isEqualTo(EquipmentType.KEYBOARD)
    }

    @Test
    fun `GET equipments for unknown id returns 404`() {
        val response = restTemplate.getForEntity("/equipments/${UUID.randomUUID()}", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `GET equipments without filters returns all`() {
        seedEquipment(EquipmentType.MAIN_COMPUTER)
        seedEquipment(EquipmentType.MONITOR)
        seedEquipment(EquipmentType.KEYBOARD)

        val response = restTemplate.exchange(
            "/equipments",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<List<EquipmentResponse>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasSize(3)
    }

    @Test
    fun `GET equipments filters by type`() {
        seedEquipment(EquipmentType.MAIN_COMPUTER)
        seedEquipment(EquipmentType.MONITOR)
        seedEquipment(EquipmentType.MONITOR)

        val response = restTemplate.exchange(
            "/equipments?type=MONITOR",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<List<EquipmentResponse>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasSize(2)
        assertThat(response.body).allMatch { it.type == EquipmentType.MONITOR }
    }

    @Test
    fun `GET equipments filters by status`() {
        val available = seedEquipment(EquipmentType.KEYBOARD)
        val retired = seedEquipment(EquipmentType.KEYBOARD)
        equipmentRepository.save(retired.retire("end of life"))

        val response = restTemplate.exchange(
            "/equipments?status=AVAILABLE",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<List<EquipmentResponse>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasSize(1)
        assertThat(response.body?.first()?.id).isEqualTo(available.id)
    }

    @Test
    fun `GET equipments filters by both type and status`() {
        seedEquipment(EquipmentType.MOUSE)
        seedEquipment(EquipmentType.KEYBOARD)
        val target = seedEquipment(EquipmentType.MOUSE, brand = "Razer")

        val response = restTemplate.exchange(
            "/equipments?type=MOUSE&status=AVAILABLE",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<List<EquipmentResponse>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).hasSize(2)
        assertThat(response.body).allMatch {
            it.type == EquipmentType.MOUSE && it.status == EquipmentStatus.AVAILABLE
        }
        assertThat(response.body?.map { it.id }).contains(target.id)
    }

    @Test
    fun `POST equipments retire transitions AVAILABLE to RETIRED`() {
        val seeded = seedEquipment(EquipmentType.MOUSE)

        val response = restTemplate.postForEntity(
            "/equipments/${seeded.id}/retire",
            mapOf("reason" to "broken"),
            EquipmentResponse::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.status).isEqualTo(EquipmentStatus.RETIRED)
        assertThat(response.body?.retiredReason).isEqualTo("broken")
        assertThat(response.body?.retiredAt).isNotNull
    }

    @Test
    fun `POST equipments retire on already RETIRED is idempotent`() {
        val seeded = seedEquipment(EquipmentType.MOUSE)
        equipmentRepository.save(seeded.retire("first reason"))

        val response = restTemplate.postForEntity(
            "/equipments/${seeded.id}/retire",
            mapOf("reason" to "second reason"),
            EquipmentResponse::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.status).isEqualTo(EquipmentStatus.RETIRED)
        assertThat(response.body?.retiredReason).isEqualTo("first reason")
    }

    @Test
    fun `POST equipments retire for unknown id returns 404`() {
        val response = restTemplate.postForEntity(
            "/equipments/${UUID.randomUUID()}/retire",
            mapOf("reason" to "broken"),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `POST equipments rejects missing type`() {
        val body = mapOf(
            "brand" to "Acme",
            "model" to "K1",
            "conditionScore" to 0.9,
            "purchaseDate" to LocalDate.now().minusMonths(1).toString(),
        )
        val response = restTemplate.postForEntity("/equipments", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("type")
    }

    @Test
    fun `POST equipments rejects out-of-range conditionScore`() {
        val body = mapOf(
            "type" to "KEYBOARD",
            "brand" to "Acme",
            "model" to "K1",
            "conditionScore" to 1.5,
            "purchaseDate" to LocalDate.now().minusMonths(1).toString(),
        )
        val response = restTemplate.postForEntity("/equipments", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("conditionScore")
    }

    @Test
    fun `POST equipments rejects blank model`() {
        val body = mapOf(
            "type" to "KEYBOARD",
            "brand" to "Acme",
            "model" to "   ",
            "conditionScore" to 0.9,
            "purchaseDate" to LocalDate.now().minusMonths(1).toString(),
        )
        val response = restTemplate.postForEntity("/equipments", body, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("model")
    }

    @Test
    fun `POST equipments retire rejects blank reason`() {
        val seeded = seedEquipment(EquipmentType.MOUSE)
        val response = restTemplate.exchange(
            "/equipments/${seeded.id}/retire",
            HttpMethod.POST,
            HttpEntity(mapOf("reason" to "")),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("reason")
    }

    private fun seedEquipment(
        type: EquipmentType,
        brand: String = "Acme",
        condition: Double = 0.9,
    ): Equipment = equipmentRepository.save(
        Equipment(
            type = type,
            brand = brand,
            model = "Model-X",
            conditionScore = BigDecimal.valueOf(condition),
            purchaseDate = LocalDate.now().minusMonths(6),
        )
    )
}
