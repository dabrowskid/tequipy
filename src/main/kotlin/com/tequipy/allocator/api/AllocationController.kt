package com.tequipy.allocator.api

import com.tequipy.allocator.application.AllocationNotFoundException
import com.tequipy.allocator.application.GetAllocationUseCase
import com.tequipy.allocator.application.CancelAllocationUseCase
import com.tequipy.allocator.application.ConfirmAllocationUseCase
import com.tequipy.allocator.application.CreateAllocationUseCase
import com.tequipy.allocator.application.IdempotencyKeyConflictException
import com.tequipy.allocator.application.InvalidAllocationStateException
import com.tequipy.allocator.domain.allocation.AllocationPolicy
import com.tequipy.allocator.domain.model.AllocationRequest
import com.tequipy.allocator.domain.model.AllocationState
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant
import java.util.UUID

data class CreateAllocationRequest(
    val employeeId: UUID,
    val policy: AllocationPolicy,
)

data class AllocationResponse(
    val id: UUID,
    val employeeId: UUID,
    val state: AllocationState,
    val createdAt: Instant,
)

fun AllocationRequest.toResponse() = AllocationResponse(
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
    fun create(@RequestBody request: CreateAllocationRequest): AllocationResponse =
        createAllocationUseCase.execute(request.employeeId, request.policy).toResponse()

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
}
