package com.engine.control

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(val username: String, val password: String)

/** Who you are, as the server sees it. The SPA renders from this and nothing else. */
data class Identity(val username: String, val roles: List<String>)

data class CreateUserRequest(val username: String, val password: String)

data class PasswordRequest(val password: String)

/**
 * Sign in, sign out, and "who am I".
 *
 * A JSON login rather than Spring's form login: the client is an SPA, and a 302 to a login page is
 * not an answer it can use. On success the response body carries the identity so the SPA does not
 * need a second round trip to render its header.
 */
@RestController
@RequestMapping("/api/auth")
class AuthApi(private val authenticationManager: AuthenticationManager) {

    /** The session, explicitly. Spring Security 6 no longer saves the context for you. */
    private val contextRepository: SecurityContextRepository = HttpSessionSecurityContextRepository()

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<Any> {
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username, request.password),
            )
        } catch (_: AuthenticationException) {
            // One message for a wrong password, an unknown user and a disabled account alike.
            // Distinguishing them tells an attacker which half of the guess was right.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError("unauthenticated", "bad credentials") as Any)
        }

        // A fresh session for a fresh login: reusing the anonymous one is a session fixation.
        httpRequest.getSession(false)?.invalidate()
        httpRequest.getSession(true)

        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        contextRepository.saveContext(context, httpRequest, httpResponse)

        return ResponseEntity.ok(identityOf(authentication) as Any)
    }

    /**
     * Idempotent, and permitted anonymously: signing out when you are already signed out is not an
     * error, and answering 401 to it would leave a stale SPA unable to reach a clean state.
     */
    @PostMapping("/logout")
    fun logout(httpRequest: HttpServletRequest): ResponseEntity<Void> {
        httpRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().build()
    }

    /** 401 when there is no session — that is how the SPA's route guard learns to show the login form. */
    @GetMapping("/me")
    fun me(authentication: Authentication): Identity = identityOf(authentication)

    private fun identityOf(authentication: Authentication) = Identity(
        username = authentication.name,
        roles = authentication.authorities.map { it.authority.removePrefix("ROLE_") }.sorted(),
    )
}

/**
 * Operator accounts.
 *
 * Under `/api` like everything else, so it needs ADMIN — which today means any operator can create
 * another. That is the honest consequence of having exactly one role, and it is why the audit log
 * matters more than the authorisation model does at this stage.
 */
@RestController
@RequestMapping("/api/users")
class UserApi(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
) {

    @GetMapping
    fun list(): List<ControlUser> = users.list()

    @PostMapping
    fun create(@RequestBody request: CreateUserRequest): ResponseEntity<ControlUser> {
        require(request.username.isNotBlank()) { "a username is required" }
        require(request.password.length >= MIN_PASSWORD_LENGTH) {
            "a password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        users.create(request.username, encoder.encode(request.password))
        return ResponseEntity.status(HttpStatus.CREATED).body(ControlUser(request.username))
    }

    @PutMapping("/{username}/password")
    fun setPassword(
        @PathVariable username: String,
        @RequestBody request: PasswordRequest,
    ): ResponseEntity<Void> {
        require(request.password.length >= MIN_PASSWORD_LENGTH) {
            "a password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        return if (users.setPassword(username, encoder.encode(request.password))) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Deleting the last account would lock everyone out of a control plane that is still driving a
     * market on a schedule, so it is refused. Disabling it instead is available and reversible.
     */
    @DeleteMapping("/{username}")
    fun delete(@PathVariable username: String): ResponseEntity<Void> {
        check(users.count() > 1L) { "the last operator account cannot be deleted" }
        return if (users.delete(username)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PutMapping("/{username}/enabled")
    fun setEnabled(
        @PathVariable username: String,
        @RequestBody enabled: Boolean,
    ): ResponseEntity<Void> =
        if (users.setEnabled(username, enabled)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 12
    }
}
