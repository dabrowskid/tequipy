package com.tequipy.allocator.api

import com.tequipy.allocator.application.AllocationNotFoundException
import com.tequipy.allocator.application.AllocationQueryService
import com.tequipy.allocator.application.CreateAllocationUseCase
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
class AllocationController(
    private val createAllocationUseCase: CreateAllocationUseCase,
    private val allocationQueryService: AllocationQueryService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun create(@RequestBody request: CreateAllocationRequest): AllocationResponse =
        createAllocationUseCase.execute(request.employeeId, request.policy).toResponse()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AllocationResponse =
        allocationQueryService.findById(id).toResponse()
}

@RestControllerAdvice
class AllocationExceptionHandler {

    @ExceptionHandler(AllocationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: AllocationNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found").also {
            it.type = URI.create("https://tequipy.com/errors/not-found")
        }
}
