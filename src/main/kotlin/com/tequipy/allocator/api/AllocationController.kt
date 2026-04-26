package com.tequipy.allocator.api

import com.tequipy.allocator.application.AllocationNotFoundException
import com.tequipy.allocator.application.GetAllocationUseCase
import com.tequipy.allocator.application.CancelAllocationUseCase
import com.tequipy.allocator.application.ConfirmAllocationUseCase
import com.tequipy.allocator.application.CreateAllocationUseCase
import com.tequipy.allocator.application.IdempotencyKeyConflictException
import com.tequipy.allocator.application.InvalidAllocationStateException
import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.allocation.SlotRequirement
import com.tequipy.allocator.domain.model.AllocationRequest
import com.tequipy.allocator.domain.model.AllocationState
import com.tequipy.allocator.domain.model.EquipmentType
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant
import java.util.UUID

data class CreateAllocationRequest(
    @field:NotNull
    var employeeId: UUID?,
    @field:NotNull
    @field:Valid
    var policy: AllocationPolicyDto?,
)

data class AllocationPolicyDto(
    @field:NotEmpty
    @field:Size(max = 10)
    @field:Valid
    val slots: List<SlotRequirementDto>,
)

data class SlotRequirementDto(
    @field:NotNull
    var type: EquipmentType?,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val minCondition: Double = 0.0,
    @field:Size(max = 3)
    val preferredBrands: List<@Size(max = 3) String> = emptyList(),
    val preferRecent: Boolean = false,
    @field:Min(1) @field:Max(5)
    val count: Int = 1,
)

private fun AllocationPolicyDto.toDomain(): AllocationPolicy =
    AllocationPolicy(slots = slots.map { it.toDomain() })

private fun SlotRequirementDto.toDomain(): SlotRequirement = SlotRequirement(
    type = type!!,
    minCondition = minCondition,
    preferredBrands = preferredBrands,
    preferRecent = preferRecent,
    count = count,
)

data class AllocationResponse(
    val id: UUID,
    val employeeId: UUID,
    val state: AllocationState,
    val createdAt: Instant,
)

private fun AllocationRequest.toResponse() = AllocationResponse(
    id = id,
    employeeId = employeeId,
    state = state,
    createdAt = createdAt,
)

@RestController
@RequestMapping("/allocations")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Allocations", description = "Create, query, confirm and cancel equipment allocation requests.")
class AllocationController(
    private val createAllocationUseCase: CreateAllocationUseCase,
    private val confirmAllocationUseCase: ConfirmAllocationUseCase,
    private val cancelAllocationUseCase: CancelAllocationUseCase,
    private val getAllocationUseCase: GetAllocationUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun create(@Valid @RequestBody request: CreateAllocationRequest): AllocationResponse =
        createAllocationUseCase.execute(request.employeeId!!, request.policy!!.toDomain()).toResponse()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AllocationResponse =
        getAllocationUseCase.findById(id).toResponse()

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): AllocationResponse = confirmAllocationUseCase.execute(id, idempotencyKey).toResponse()

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): AllocationResponse = cancelAllocationUseCase.execute(id, idempotencyKey).toResponse()
}

@RestControllerAdvice
class AllocationExceptionHandler {

    @ExceptionHandler(AllocationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: AllocationNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found").also {
            it.type = URI.create("https://tequipy.com/errors/not-found")
        }

    @ExceptionHandler(InvalidAllocationStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleInvalidState(ex: InvalidAllocationStateException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Invalid state").also {
            it.type = URI.create("https://tequipy.com/errors/invalid-state")
            it.setProperty("allocationId", ex.allocationId)
            it.setProperty("currentState", ex.currentState)
            it.setProperty("operation", ex.operation)
        }

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleIdempotencyConflict(ex: IdempotencyKeyConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message ?: "Idempotency conflict").also {
            it.type = URI.create("https://tequipy.com/errors/idempotency-conflict")
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val errors = ex.bindingResult.fieldErrors.associate { fe ->
            fe.field to (fe.defaultMessage ?: "invalid")
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed").also {
            it.type = URI.create("https://tequipy.com/errors/validation")
            it.setProperty("errors", errors)
        }
    }
}
