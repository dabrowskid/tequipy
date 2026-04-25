package com.tequipy.allocator.infrastructure.persistence

import com.tequipy.allocator.domain.model.IdempotencyKey
import com.tequipy.allocator.domain.model.IdempotencyKeyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, IdempotencyKeyId>