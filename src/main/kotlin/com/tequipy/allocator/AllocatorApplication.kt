package com.tequipy.allocator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AllocatorApplication

fun main(args: Array<String>) {
	runApplication<AllocatorApplication>(*args)
}
