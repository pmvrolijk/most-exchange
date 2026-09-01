package com.engine.control

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The login bootstrap, against a real embedded server rather than MockMvc.
 *
 * There is a chicken-and-egg here that only a real container exercises: `/api/auth/login` is itself
 * CSRF-protected, so a browser arriving with no cookie must be *given* a token by some earlier
 * response or it can never sign in at all. The SPA's first call is an anonymous `GET /api/auth/me`
 * that answers 401, and that 401 has to carry the cookie.
 *
 * MockMvc does not reproduce it — `SecurityMockMvcRequestPostProcessors.csrf()` hands every test a
 * token unconditionally, so a chain that never issued one to anybody would pass the entire MockMvc
 * suite. That is the shape of defect this project has been bitten by before: a test double easier
 * than reality, validating something the real thing does not do. This one runs Tomcat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthBootstrapTest : PostgresTest() {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Test
    fun `a browser with no cookies can obtain a token, sign in, and then write`() {
        // 1. Anonymous. Refused -- and issued the token that refusal makes it need.
        val anonymous = rest.getForEntity("/api/auth/me", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)

        val setCookies = anonymous.headers[HttpHeaders.SET_COOKIE].orEmpty()
        val xsrf = tokenFrom(setCookies)
        assertNotNull(xsrf, "no XSRF-TOKEN on the anonymous 401: login would be unreachable. $setCookies")
        // Readable by script on purpose: the SPA has to copy it into a header. The cookie is not
        // the credential -- the session is -- so exposing it costs nothing.
        assertTrue(
            setCookies.any { it.startsWith("XSRF-TOKEN=") && !it.contains("HttpOnly") },
            "the XSRF cookie must not be HttpOnly or the SPA cannot read it",
        )

        // 2. Sign in, presenting that token.
        val cookies = mutableListOf("XSRF-TOKEN=$xsrf")
        val login = rest.exchange(
            "/api/auth/login",
            HttpMethod.POST,
            HttpEntity(
                """{"username":"$TEST_ADMIN_USER","password":"$TEST_ADMIN_PASSWORD"}""",
                headers(cookies, xsrf),
            ),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode, "login refused: ${login.body}")
        assertTrue(login.body!!.contains(TEST_ADMIN_USER))

        // The session cookie the rest of the exchange rides on.
        val session = login.headers[HttpHeaders.SET_COOKIE].orEmpty()
            .firstOrNull { it.startsWith("JSESSIONID=") }
            ?.substringBefore(';')
        assertNotNull(session, "login established no session")
        cookies += session

        // 3. A read, and then a write -- the write being the one that proves the CSRF token and
        //    the session are both accepted together.
        val read = rest.exchange("/api/shards", HttpMethod.GET, HttpEntity<Void>(headers(cookies, xsrf)), String::class.java)
        assertEquals(HttpStatus.OK, read.statusCode)

        val write = rest.exchange(
            "/api/shards",
            HttpMethod.POST,
            HttpEntity(SHARD_JSON, headers(cookies, xsrf)),
            String::class.java,
        )
        assertEquals(HttpStatus.CREATED, write.statusCode, "write refused: ${write.body}")
    }

    @Test
    fun `the same write without the csrf header is refused, session and all`() {
        val anonymous = rest.getForEntity("/api/auth/me", String::class.java)
        val xsrf = tokenFrom(anonymous.headers[HttpHeaders.SET_COOKIE].orEmpty())!!
        val cookies = mutableListOf("XSRF-TOKEN=$xsrf")

        val login = rest.exchange(
            "/api/auth/login",
            HttpMethod.POST,
            HttpEntity(
                """{"username":"$TEST_ADMIN_USER","password":"$TEST_ADMIN_PASSWORD"}""",
                headers(cookies, xsrf),
            ),
            String::class.java,
        )
        cookies += login.headers[HttpHeaders.SET_COOKIE].orEmpty()
            .first { it.startsWith("JSESSIONID=") }.substringBefore(';')

        // A valid session, deliberately without the header. If this succeeded, any page the
        // operator happened to visit could post to this API with their credentials attached.
        val write = rest.exchange(
            "/api/shards",
            HttpMethod.POST,
            HttpEntity(SHARD_JSON, headers(cookies, csrfToken = null)),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, write.statusCode)
    }

    /**
     * HTTP Basic bypasses the token, and that is deliberate rather than an oversight: a browser
     * attaches cookies to a cross-site request automatically and an Authorization header never, so
     * a request carrying its own credentials is not a forgery risk. It is what keeps curl, `most`
     * and the e2e script able to drive this API in one call.
     */
    @Test
    fun `basic auth drives the api in a single call`() {
        val write = rest.withBasicAuth(TEST_ADMIN_USER, TEST_ADMIN_PASSWORD).exchange(
            "/api/shards",
            HttpMethod.POST,
            HttpEntity(SHARD_JSON, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            String::class.java,
        )
        assertEquals(HttpStatus.CREATED, write.statusCode, "basic auth refused: ${write.body}")

        val anonymous = rest.exchange(
            "/api/shards",
            HttpMethod.POST,
            HttpEntity(SHARD_JSON, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            String::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, anonymous.statusCode)
    }

    private fun tokenFrom(setCookies: List<String>): String? =
        setCookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")
            ?.substringBefore(';')

    private fun headers(cookies: List<String>, csrfToken: String?) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set(HttpHeaders.COOKIE, cookies.joinToString("; "))
        if (csrfToken != null) set("X-XSRF-TOKEN", csrfToken)
    }

    private companion object {
        val SHARD_JSON = """
            {"shardId":0,"orderEntryChannel":"aeron:ipc","orderEntryStreamId":20,
             "executionReportChannel":"aeron:ipc","executionReportStreamId":21}
        """.trimIndent()
    }
}
