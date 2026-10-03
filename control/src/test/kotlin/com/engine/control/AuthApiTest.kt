package com.engine.control

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The door itself.
 *
 * These are written from the specification rather than from what the code returns, because the
 * failure mode that matters here is an endpoint that *looks* protected. A test that asserted only
 * "a signed-in operator can read shards" would pass just as happily with the filter chain
 * permitting everything.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest : PostgresTest() {

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var json: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun login(user: String = TEST_ADMIN_USER, password: String = TEST_ADMIN_PASSWORD) =
        mvc.perform(
            post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(LoginRequest(user, password))),
        )

    @Test
    fun `every api endpoint refuses an anonymous caller`() {
        // Named individually rather than swept, so adding an endpoint that forgot to be protected
        // is a change someone has to make to this list deliberately.
        listOf(
            "/api/shards", "/api/securities", "/api/participants", "/api/topology",
            "/api/releases", "/api/status", "/api/schedules", "/api/scheduler/runs",
            "/api/users", "/api/audit", "/api/auth/me",
        ).forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error").value("unauthenticated"))
        }
    }

    @Test
    fun `a refusal is json, not a redirect to a login page`() {
        // A 302 would be answered by the SPA's fetch as an opaque success and rendered as an empty
        // list -- an operator would see a control plane reporting no securities rather than one
        // reporting that they are signed out.
        mvc.perform(get("/api/shards"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `login establishes a session that later requests are accepted on`() {
        val session = login()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value(TEST_ADMIN_USER))
            .andExpect(jsonPath("$.roles[0]").value(ROLE_ADMIN))
            .andReturn().request.session

        assertNotNull(session)
        mvc.perform(get("/api/auth/me").session(session as MockHttpSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value(TEST_ADMIN_USER))
    }

    @Test
    fun `a wrong password and an unknown user are indistinguishable`() {
        val wrongPassword = login(password = "not the password")
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString
        val unknownUser = login(user = "nobody", password = "not the password")
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString

        assertEquals(wrongPassword, unknownUser)
    }

    @Test
    fun `a disabled account cannot sign in`() {
        jdbc.update(
            "INSERT INTO control_user (username, password_hash, enabled) VALUES (?, ?, false)",
            "suspended",
            // The hash of TEST_ADMIN_PASSWORD is irrelevant: the account is refused before it.
            "{noop}whatever",
        )
        login(user = "suspended", password = "whatever").andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout ends the session and is safe to repeat`() {
        val session = login().andReturn().request.session as MockHttpSession

        mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/auth/logout").with(csrf()))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = [ROLE_ADMIN])
    fun `a mutating request without a csrf token is rejected even when signed in`() {
        // The session cookie alone must not be enough, or any page the operator visits can post to
        // this API with their credentials attached.
        mvc.perform(
            post("/api/shards").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(roles = [ROLE_ADMIN])
    fun `an operator can be created and a short password refused`() {
        mvc.perform(
            post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(CreateUserRequest("dealer", "short"))),
        ).andExpect(status().isBadRequest)

        mvc.perform(
            post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(CreateUserRequest("dealer", "a-long-enough-password"))),
        ).andExpect(status().isCreated)

        login(user = "dealer", password = "a-long-enough-password").andExpect(status().isOk)
    }

    @Test
    @WithMockUser(roles = [ROLE_ADMIN])
    fun `the stored password is hashed, not the password`() {
        mvc.perform(
            post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(CreateUserRequest("dealer", "a-long-enough-password"))),
        ).andExpect(status().isCreated)

        val hash = jdbc.queryForObject(
            "SELECT password_hash FROM control_user WHERE username = 'dealer'",
            String::class.java,
        )
        assertNotNull(hash)
        assertTrue(hash.startsWith("{bcrypt}"), "expected a bcrypt hash, got $hash")
    }

    @Test
    @WithMockUser(roles = [ROLE_ADMIN])
    fun `the last operator account cannot be deleted`() {
        // Locking everyone out of a control plane that is still running a market on a schedule is
        // not a recoverable mistake from inside the product.
        mvc.perform(
            delete("/api/users/$TEST_ADMIN_USER").with(csrf()),
        ).andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser(username = "operator-one", roles = [ROLE_ADMIN])
    fun `a market-moving command records who asked, and whether it was even sent`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isCreated)
        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0))),
        ).andExpect(status().isCreated)

        // With no Aeron link in the suite the bytes cannot leave -- which is exactly the case the
        // audit has to represent honestly rather than record as a success.
        mvc.perform(
            post("/api/shards/0/session").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"phase":"CONTINUOUS"}"""),
        ).andExpect(status().isBadGateway)

        mvc.perform(get("/api/audit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].username").value("operator-one"))
            .andExpect(jsonPath("$[0].action").value("session"))
            .andExpect(jsonPath("$[0].target").value("shard:0"))
            .andExpect(jsonPath("$[0].sent").value(false))
    }

    @Test
    @WithMockUser(username = "operator-one", roles = [ROLE_ADMIN])
    fun `a bulk cancel is audited with the participant and the security it named`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isCreated)
        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/shards/0/participants/42/cancel-orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"securityId":1}"""),
        )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.command").value("cancel orders of participant 42 on AAPL on shard 0"))
            .andExpect(jsonPath("$.confirmed").value(false))

        mvc.perform(get("/api/audit"))
            .andExpect(jsonPath("$[0].action").value("cancel-orders"))
            .andExpect(jsonPath("$[0].target").value("shard:0 participant:42 security:1"))
            .andExpect(jsonPath("$[0].sent").value(false))
    }

    @Test
    @WithMockUser(username = "operator-one", roles = [ROLE_ADMIN])
    fun `a bulk cancel naming another shard's security is a bad request and nothing is sent`() {
        mvc.perform(
            post("/api/shards").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shardRow(0))),
        ).andExpect(status().isCreated)
        mvc.perform(
            post("/api/securities").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(securityRow(1, 0))),
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/shards/0/participants/42/cancel-orders").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"securityId":9}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid"))
    }
}
