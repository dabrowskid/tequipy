package com.tequipy.allocator.api

import com.tequipy.allocator.application.EquipmentService
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.UUID

data class CreateEquipmentRequest(
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val conditionScore: BigDecimal,
    val purchaseDate: LocalDate,
)

data class RetireEquipmentRequest(val reason: String)

@RestController
@RequestMapping("/equipments")
class EquipmentController(private val service: EquipmentService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateEquipmentRequest): Equipment =
        service.create(
            Equipment(
                type = request.type,
                brand = request.brand,
                model = request.model,
                conditionScore = request.conditionScore,
                purchaseDate = request.purchaseDate,
            )
        )

    @GetMapping
    fun list(
        @RequestParam type: EquipmentType? = null,
        @RequestParam status: EquipmentStatus? = null,
    ): List<Equipment> = service.findAll(type, status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Equipment = service.findById(id)

    @PostMapping("/{id}/retire")
    fun retire(@PathVariable id: UUID, @RequestBody request: RetireEquipmentRequest): Equipment =
        service.retire(id, request.reason)
}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(com.tequipy.allocator.application.EquipmentNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: com.tequipy.allocator.application.EquipmentNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found").also {
            it.type = URI.create("https://tequipy.com/errors/not-found")
        }
}
