package com.engine.control

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Authentication for the control plane.
 *
 * One role. An authenticated operator has full access to everything under `/api`; there is no
 * read-only tier, and the `role` column exists so that adding one later is a data change rather
 * than a migration.
 *
 * The reason this module needed authentication and the others did not: it is the only process that
 * can *decide* something. The engine, gateway, market-data and discovery processes apply a
 * replicated log or forward bytes; this one seeds a `SecurityDefinition`, moves a shard's session,
 * purges, reopens a halted security and runs a calendar that opens a market unattended. Those are
 * unacknowledged commands (Design.md §7) — the engine applies or rejects them in silence — so
 * nothing downstream will ever question who sent one. The question has to be settled here.
 */
@Configuration
class SecurityConfig {

    /**
     * `Delegating`, so the stored hash carries its own algorithm prefix. Moving off BCrypt later
     * is then a re-encode on next login rather than a flag day.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(
        users: UserDetailsService,
        encoder: PasswordEncoder,
    ): AuthenticationManager = ProviderManager(
        DaoAuthenticationProvider(users).apply { setPasswordEncoder(encoder) },
    )

    @Bean
    @Suppress("LongMethod")
    fun filterChain(http: HttpSecurity, json: ObjectMapper): SecurityFilterChain {
        // Opting out of Spring Security's deferred token resolution, and this single line is the
        // whole login bootstrap. `/api/auth/login` is itself CSRF-protected, so a browser arriving
        // with no cookie has to be *given* a token by an earlier response or it can never sign in
        // at all -- and with the token deferred, no response issues one until something asks for
        // it, which nothing anonymous does. Resolving eagerly puts XSRF-TOKEN on every response,
        // including the anonymous 401 the SPA opens with. `AuthBootstrapTest` runs a real Tomcat
        // over exactly that sequence, because MockMvc hands every test a token and so cannot tell
        // this working from this being broken for every real browser.
        val csrfHandler = CsrfTokenRequestAttributeHandler().apply { setCsrfRequestAttributeName(null) }

        http
            .csrf { csrf ->
                csrf
                    // Readable by script on purpose: the SPA copies it into X-XSRF-TOKEN. The
                    // cookie is not the credential — the session is — so exposing it costs nothing.
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    // A request carrying its own Authorization header is not a cross-site forgery
                    // risk: a browser attaches cookies to a cross-site request automatically and an
                    // Authorization header never. Exempting Basic keeps curl, `most` and the e2e
                    // script able to drive the API in one call without a cookie-jar dance.
                    .ignoringRequestMatchers(BasicAuthRequestMatcher)
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .authorizeHttpRequests { auth ->
                auth
                    // The only two anonymous endpoints. `/api/auth/me` is deliberately NOT here:
                    // the SPA uses its 401 to learn it has no session.
                    .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                    .requestMatchers("/error").permitAll()
                    .anyRequest().hasRole(ROLE_ADMIN)
            }
            .httpBasic { }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint(jsonEntryPoint(json))
                ex.accessDeniedHandler(jsonAccessDeniedHandler(json))
            }

        return http.build()
    }

    /**
     * 401 as JSON, never a redirect to a login page and never a `WWW-Authenticate` prompt.
     *
     * A browser challenge popping up over the SPA would be the wrong answer to "your session
     * expired", and a script driving this API wants the same shape of error body it gets from
     * every other refusal here.
     */
    private fun jsonEntryPoint(json: ObjectMapper) = AuthenticationEntryPoint { _, response, _ ->
        writeError(json, response, HttpStatus.UNAUTHORIZED, "unauthenticated", "sign in first")
    }

    private fun jsonAccessDeniedHandler(json: ObjectMapper) = AccessDeniedHandler { _, response, e ->
        writeError(json, response, HttpStatus.FORBIDDEN, "forbidden", e.message)
    }

    private fun writeError(
        json: ObjectMapper,
        response: jakarta.servlet.http.HttpServletResponse,
        status: HttpStatus,
        error: String,
        message: String?,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        json.writeValue(response.outputStream, ApiError(error, message))
    }

    private object BasicAuthRequestMatcher : RequestMatcher {
        override fun matches(request: HttpServletRequest): Boolean =
            request.getHeader("Authorization")?.startsWith("Basic ") == true
    }
}
