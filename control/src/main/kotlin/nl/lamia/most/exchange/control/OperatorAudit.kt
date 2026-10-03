package nl.lamia.most.exchange.control

import jakarta.servlet.http.HttpServletRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** One market-moving action, and who asked for it. */
data class AuditEntry(
    val id: Long,
    val at: Instant,
    val username: String,
    val action: String,
    val target: String?,
    val detail: String?,
    val sent: Boolean,
    val remoteAddr: String?,
)

/**
 * The record of who asked.
 *
 * Operator commands are unacknowledged by design (Design.md §7): the engine applies or rejects a
 * `SecurityDefinition`, a `SessionTransition` or a `PurgeExpiredOrders` without replying, and a
 * rejection only increments a counter. Nothing downstream will ever be able to say who moved a
 * market, so this table is the only account of it that exists — and it records `sent`, not
 * `applied`, because sent is all the control plane can honestly claim.
 *
 * It is written on the way out of the REST layer rather than inside `OperationsService`, so the
 * service stays a plain domain object with no notion of a principal and its tests need no security
 * context.
 */
@Service
class OperatorAudit(private val jdbc: JdbcTemplate) {

    fun record(action: String, target: String?, detail: String?, sent: Boolean, request: HttpServletRequest?) {
        jdbc.update(
            "INSERT INTO operator_audit (username, action, target, detail, sent, remote_addr) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            currentUser(),
            action,
            target?.take(TARGET_MAX),
            detail?.take(DETAIL_MAX),
            sent,
            request?.remoteAddr?.take(ADDR_MAX),
        )
    }

    fun recent(limit: Int): List<AuditEntry> =
        jdbc.query(
            "SELECT id, at, username, action, target, detail, sent, remote_addr " +
                "FROM operator_audit ORDER BY at DESC, id DESC LIMIT ?",
            { rs, _ ->
                AuditEntry(
                    id = rs.getLong("id"),
                    at = rs.getTimestamp("at").toInstant(),
                    username = rs.getString("username"),
                    action = rs.getString("action"),
                    target = rs.getString("target"),
                    detail = rs.getString("detail"),
                    sent = rs.getBoolean("sent"),
                    remoteAddr = rs.getString("remote_addr"),
                )
            },
            limit,
        )

    /**
     * There is normally a principal, because every path that writes here is behind authentication.
     * The fallback names the alternative honestly rather than attributing an unattended action to
     * whoever last logged in. Unattended transitions are the scheduler's, and it keeps its own
     * fuller record in `schedule_run` -- including what it deliberately did *not* do, which this
     * table has no shape for.
     */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: SYSTEM_ACTOR

    private companion object {
        const val SYSTEM_ACTOR = "system"
        const val TARGET_MAX = 64
        const val DETAIL_MAX = 512
        const val ADDR_MAX = 64
    }
}

@RestController
@RequestMapping("/api/audit")
class AuditApi(private val audit: OperatorAudit) {

    @GetMapping
    fun recent(@RequestParam(defaultValue = "100") limit: Int): List<AuditEntry> =
        audit.recent(limit.coerceIn(1, MAX_LIMIT))

    private companion object {
        const val MAX_LIMIT = 1000
    }
}
