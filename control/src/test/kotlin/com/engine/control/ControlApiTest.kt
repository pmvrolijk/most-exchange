package com.engine.control

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.core.io.ClassPathResource
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf

@SpringBootTest
@AutoConfigureMockMvc
// The API is behind authentication now; what this class is about is the API's own behaviour, so it
// arrives already signed in. AuthApiTest is where the door itself is tested.
@WithMockUser(roles = [ROLE_ADMIN])
class ControlApiTest : PostgresTest() {

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var json: ObjectMapper

    @Test
    fun `a shard and a security can be created and read back`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0))),
        ).andExpect(status().isCreated)

        mvc.perform(get("/api/securities/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andExpect(jsonPath("$.isin").value(ISIN_APPLE))
    }

    @Test
    fun `a domain refusal reaches the operator in the domain's own words`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0, isin = "US0378331006"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ISIN")))
    }

    @Test
    fun `a duplicate symbol is a conflict, not a bad request`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        )
        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0, "AAPL", ISIN_APPLE))),
        )
        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(2, 0, "AAPL", ISIN_MICROSOFT))),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `something absent is a 404 rather than an empty 200`() {
        mvc.perform(get("/api/shards/9")).andExpect(status().isNotFound)
        mvc.perform(get("/api/securities/9")).andExpect(status().isNotFound)
        mvc.perform(get("/api/participants/9")).andExpect(status().isNotFound)
        mvc.perform(get("/api/releases/latest")).andExpect(status().isNotFound)
        mvc.perform(delete("/api/shards/9").with(csrf())).andExpect(status().isNotFound)
        mvc.perform(
            put("/api/shards/9").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(9))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `the topology endpoint answers can I publish and what is stopping me`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        )

        mvc.perform(get("/api/topology"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.problems.length()").value(1))
            .andExpect(jsonPath("$.universeVersion").doesNotExist())

        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0))),
        )

        mvc.perform(get("/api/topology"))
            .andExpect(jsonPath("$.problems.length()").value(0))
            .andExpect(jsonPath("$.shards[0].fingerprint").isNotEmpty)
    }

    @Test
    fun `import, publish, then fetch the exact bytes a process will boot from`() {
        val sample = ClassPathResource("shard-1-securities.properties").inputStream
            .bufferedReader().use { it.readText() }

        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(1))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/import").with(csrf()).contentType(MediaType.TEXT_PLAIN).content(sample),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shardId").value(1))

        val published = mvc.perform(post("/api/releases").with(csrf()).param("note", "from the sample"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.fingerprints['1']").isNotEmpty)
            .andReturn().response.contentAsString
        val version = json.readTree(published).get("version").asLong()
        val fingerprint = json.readTree(published).get("fingerprints").get("1").asText()

        mvc.perform(get("/api/releases/$version/files/shard-1-securities.properties"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("shard.id=1")))
            // The fingerprint is written into the file as a comment, so what the release recorded
            // and what an operator reads at the top of the artifact cannot drift apart.
            .andExpect(
                content().string(org.hamcrest.Matchers.containsString("# fingerprint=$fingerprint")),
            )

        mvc.perform(get("/api/releases/$version/files/discovery.properties"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("discovery.shards=1")))
    }
}
