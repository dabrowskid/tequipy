package com.tequipy.allocator

import com.tequipy.allocator.domain.allocation.Allocator
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class AllocatorApplication {
	@Bean
	fun allocator(): Allocator = Allocator()
}

fun main(args: Array<String>) {
	runApplication<AllocatorApplication>(*args)
}
