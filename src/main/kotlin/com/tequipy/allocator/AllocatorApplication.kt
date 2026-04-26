package com.tequipy.allocator

import com.tequipy.allocator.domain.allocation.Allocator
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
@OpenAPIDefinition(
	info = Info(
		title = "Tequipy Allocator API",
		version = "0.1.0",
		description = "Allocates equipment bundles to employees against a slot-based policy.",
	),
	servers = [Server(url = "http://localhost:8080", description = "Local")],
)
class AllocatorApplication {
	@Bean
	fun allocator(): Allocator = Allocator()
}

fun main(args: Array<String>) {
	runApplication<AllocatorApplication>(*args)
}
