package com.engine.control

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64

/** The only role there is. Everything under `/api` requires it; there is no read-only tier yet. */
const val ROLE_ADMIN: String = "ADMIN"

/** An operator account. The hash never leaves this file's package boundary in a response body. */
data class ControlUser(
    val username: String,
    val role: String = ROLE_ADMIN,
    val enabled: Boolean = true,
)

/**
 * Accounts, in the same database as the reference data.
 *
 * Not on any engine boot path — nothing the exchange processes read comes from here. An outage
 * stops an operator logging in, which is the correct blast radius: it does not stop a node
 * starting, and it does not stop the market running the schedule it was already given.
 */
@Service
class UserRepository(private val jdbc: JdbcTemplate) {

    fun find(username: String): Pair<ControlUser, String>? =
        jdbc.query(
            "SELECT username, password_hash, role, enabled FROM control_user WHERE username = ?",
            { rs, _ ->
                ControlUser(rs.getString("username"), rs.getString("role"), rs.getBoolean("enabled")) to
                    rs.getString("password_hash")
            },
            username,
        ).firstOrNull()

    fun list(): List<ControlUser> =
        jdbc.query("SELECT username, role, enabled FROM control_user ORDER BY username") { rs, _ ->
            ControlUser(rs.getString("username"), rs.getString("role"), rs.getBoolean("enabled"))
        }

    fun count(): Long = jdbc.queryForObject("SELECT count(*) FROM control_user", Long::class.java) ?: 0L

    fun create(username: String, passwordHash: String, role: String = ROLE_ADMIN) {
        jdbc.update(
            "INSERT INTO control_user (username, password_hash, role) VALUES (?, ?, ?)",
            username, passwordHash, role,
        )
    }

    fun setPassword(username: String, passwordHash: String): Boolean =
        jdbc.update("UPDATE control_user SET password_hash = ? WHERE username = ?", passwordHash, username) == 1

    fun setEnabled(username: String, enabled: Boolean): Boolean =
        jdbc.update("UPDATE control_user SET enabled = ? WHERE username = ?", enabled, username) == 1

    fun delete(username: String): Boolean =
        jdbc.update("DELETE FROM control_user WHERE username = ?", username) == 1
}

/** Reads accounts from Postgres. Nothing here is cached: an account disabled takes effect at the next login. */
@Service
class ControlUserDetailsService(private val users: UserRepository) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val (user, hash) = users.find(username)
            ?: throw UsernameNotFoundException("no such operator")
        return User.withUsername(user.username)
            .password(hash)
            .disabled(!user.enabled)
            .authorities(SimpleGrantedAuthority("ROLE_${user.role}"))
            .build()
    }
}

/**
 * Seeds the first operator, once, and only into an empty table.
 *
 * With no password configured it generates one and prints it. An exchange control plane that
 * shipped with a known default password would be worse than the unauthenticated version it
 * replaces, because that one at least did not look protected.
 */
@Component
class AdminSeeder(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    @Value("\${control.auth.adminUser:admin}") private val adminUser: String,
    @Value("\${control.auth.adminPassword:}") private val adminPassword: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        if (users.count() > 0L) return

        val password = adminPassword.ifBlank { generatePassword() }
        users.create(adminUser, encoder.encode(password))

        if (adminPassword.isBlank()) {
            log.warn(
                "seeded operator '{}' with a generated password: {} -- set CONTROL_ADMIN_PASSWORD " +
                    "to choose one, and change this before it is anything but a dev instance",
                adminUser, password,
            )
        } else {
            log.info("seeded operator '{}' from CONTROL_ADMIN_PASSWORD", adminUser)
        }
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        /** 18 bytes is 24 base64url characters — long enough that guessing is not the attack. */
        const val PASSWORD_BYTES = 18
    }
}
