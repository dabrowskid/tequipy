package com.tequipy.allocator.api

import com.tequipy.allocator.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Boots the app, fetches the OpenAPI YAML rendered by springdoc, and writes it
 * to `openapi.yaml` at the repository root so the spec can be reviewed in PRs
 * and consumed by client-code generators.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@AutoConfigureTestRestTemplate
class OpenApiSpecExportTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `export OpenAPI yaml to repo root`() {
        val response = restTemplate.getForEntity("/v3/api-docs.yaml", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body ?: error("no body")
        assertThat(body)
            .contains("/allocations")
            .contains("/equipments")

        val target = Paths.get(System.getProperty("user.dir"), "openapi.yaml")
        Files.writeString(target, body)
    }
}
