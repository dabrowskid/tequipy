package com.tequipy.allocator

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<AllocatorApplication>().with(TestcontainersConfiguration::class).run(*args)
}
