package com.tequipy.allocator.api

import com.tequipy.allocator.application.CreateEquipmentUseCase
import com.tequipy.allocator.application.GetEquipmentUseCase
import com.tequipy.allocator.application.ListEquipmentUseCase
import com.tequipy.allocator.application.RetireEquipmentUseCase
import com.tequipy.allocator.domain.model.Equipment
import com.tequipy.allocator.domain.model.EquipmentStatus
import com.tequipy.allocator.domain.model.EquipmentType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.UUID

data class CreateEquipmentRequest(
    @field:NotNull
    val type: EquipmentType?,
    @field:NotBlank @field:Size(max = 100)
    val brand: String,
    @field:NotBlank @field:Size(max = 100)
    val model: String,
    @field:NotNull
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val conditionScore: BigDecimal?,
    @field:NotNull
    @field:PastOrPresent
    val purchaseDate: LocalDate?,
)

data class RetireEquipmentRequest(
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
)

data class EquipmentResponse(
    val id: UUID,
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val status: EquipmentStatus,
    val conditionScore: BigDecimal,
    val purchaseDate: LocalDate,
    val retiredReason: String?,
    val retiredAt: java.time.Instant?,
)

private fun Equipment.toResponse() = EquipmentResponse(
    id = id,
    type = type,
    brand = brand,
    model = model,
    status = status,
    conditionScore = conditionScore,
    purchaseDate = purchaseDate,
    retiredReason = retiredReason,
    retiredAt = retiredAt,
)

@RestController
@RequestMapping("/equipments")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Equipments", description = "Manage the equipment inventory.")
class EquipmentController(
    private val createEquipmentUseCase: CreateEquipmentUseCase,
    private val getEquipmentUseCase: GetEquipmentUseCase,
    private val listEquipmentUseCase: ListEquipmentUseCase,
    private val retireEquipmentUseCase: RetireEquipmentUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateEquipmentRequest): EquipmentResponse =
        createEquipmentUseCase.execute(
            Equipment(
                type = request.type!!,
                brand = request.brand,
                model = request.model,
                conditionScore = request.conditionScore!!,
                purchaseDate = request.purchaseDate!!,
            )
        ).toResponse()

    @GetMapping
    fun list(
        @RequestParam type: EquipmentType? = null,
        @RequestParam status: EquipmentStatus? = null,
    ): List<EquipmentResponse> = listEquipmentUseCase.findAll(type, status).map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): EquipmentResponse = getEquipmentUseCase.findById(id).toResponse()

    @PostMapping("/{id}/retire")
    fun retire(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RetireEquipmentRequest,
    ): EquipmentResponse = retireEquipmentUseCase.execute(id, request.reason).toResponse()
}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(com.tequipy.allocator.application.EquipmentNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: com.tequipy.allocator.application.EquipmentNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found").also {
            it.type = URI.create("https://tequipy.com/errors/not-found")
        }

    @ExceptionHandler(com.tequipy.allocator.application.EquipmentInUseException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleInUse(ex: com.tequipy.allocator.application.EquipmentInUseException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Equipment in use").also {
            it.type = URI.create("https://tequipy.com/errors/equipment-in-use")
            it.setProperty("equipmentId", ex.equipmentId)
            it.setProperty("currentStatus", ex.currentStatus)
        }
}
