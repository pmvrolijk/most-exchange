package com.engine.control

import com.engine.reference.OperatorCommands
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Optional overrides; omitted fields fall back to what the security carries. */
data class DefinitionRequest(
    val referencePrice: Long? = null,
    val staticCollarBps: Int? = null,
    val dynamicCollarBps: Int? = null,
)

data class SessionRequest(val phase: String, val tradingDate: Int? = null)

data class PurgeRequest(val tradingDate: Int? = null)

/** A reopen may re-seed one security's reference price on the way through. */
data class ReopenRequest(
    val securityId: Int? = null,
    val referencePrice: Long? = null,
    val staticCollarBps: Int? = null,
    val dynamicCollarBps: Int? = null,
    val tradingDate: Int? = null,
)

/**
 * Live cluster control.
 *
 * Every response separates **sent** from **confirmed**, because operator commands are not
 * acknowledged: the engine applies or rejects them without replying, and the control plane learns
 * what happened only by watching the book event stream afterwards. A `SessionChanged` confirms a
 * phase; nothing at all confirms a `SecurityDefinition`, so those always report `confirmed: false`
 * and say why. An API that returned a bare 200 for both would be claiming knowledge it does not
 * have.
 *
 * `202 Accepted` rather than `200 OK` for the same reason: the request was accepted for delivery.
 */
@RestController
@RequestMapping("/api")
class OperationsApi(
    private val operations: OperationsService,
    private val link: ClusterLink,
    private val clusterAdmin: ClusterAdmin,
    private val audit: OperatorAudit,
) {

    /** What the exchange is actually doing, as opposed to what the database says it should be. */
    @GetMapping("/status")
    fun status(): ExchangeStatus = link.status()

    @PostMapping("/securities/{securityId}/definition")
    fun define(
        @PathVariable securityId: Int,
        @RequestBody(required = false) request: DefinitionRequest?,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> = accepted(
        operations.define(
            securityId = securityId,
            referencePrice = request?.referencePrice,
            staticCollarBps = request?.staticCollarBps,
            dynamicCollarBps = request?.dynamicCollarBps,
        ),
        action = "define",
        target = "security:$securityId",
        http = http,
    )

    /**
     * Shard-wide, and the path says so. There is no per-security session command: the engine
     * applies the phase to every book it hosts.
     */
    @PostMapping("/shards/{shardId}/session")
    fun session(
        @PathVariable shardId: Int,
        @RequestBody request: SessionRequest,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> {
        val phase = OperatorCommands.parsePhase(request.phase)
        val result = request.tradingDate
            ?.let { operations.session(shardId, phase, it) }
            ?: operations.session(shardId, phase)
        return accepted(result, "session", "shard:$shardId", http)
    }

    @PostMapping("/shards/{shardId}/purge")
    fun purge(
        @PathVariable shardId: Int,
        @RequestBody(required = false) request: PurgeRequest?,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> {
        val result = request?.tradingDate
            ?.let { operations.purge(shardId, it) }
            ?: operations.purge(shardId)
        return accepted(result, "purge", "shard:$shardId", http)
    }

    /**
     * The halt-recovery runbook: re-seed the definition, then `PRE_OPEN → OPEN_AUCTION →
     * CONTINUOUS`.
     *
     * Order matters and is not obvious. `staticReference` is only reset by an *executing* uncross,
     * but orders are accepted from `PRE_OPEN` onward — so if the halt moved price outside the old
     * static band, re-seeding afterwards is too late: the orders needed to reopen are already being
     * rejected by the stale collar, and the auction that would have fixed it never gets any.
     */
    @PostMapping("/shards/{shardId}/reopen")
    fun reopen(
        @PathVariable shardId: Int,
        @RequestBody(required = false) request: ReopenRequest?,
        http: HttpServletRequest,
    ): ResponseEntity<ReopenResult> {
        val result = operations.reopen(
            shardId = shardId,
            referencePrice = request?.referencePrice,
            staticCollarBps = request?.staticCollarBps,
            dynamicCollarBps = request?.dynamicCollarBps,
            securityId = request?.securityId,
            tradingDate = request?.tradingDate
                ?: OperatorCommands.tradingDateOf(java.time.LocalDate.now()),
        )
        audit.record(
            action = "reopen",
            target = "shard:$shardId",
            detail = result.steps.joinToString("; ") { it.command },
            sent = result.succeeded,
            request = http,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
    }

    /**
     * Asks the shard to republish its books as level images.
     *
     * The recovery lever for market data, which keeps no snapshot of its own: it derives depth from
     * the book event stream, so a restart leaves it with no book and no way to learn one. Changes
     * no book and moves no market, so unlike its neighbours on this controller it needs no
     * confirmation dialog spelling out what it will do to a live market.
     */
    @PostMapping("/shards/{shardId}/book-image")
    fun bookImage(
        @PathVariable shardId: Int,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> {
        val result = operations.requestBookImage(shardId)
        return accepted(result, "book-image", "shard:$shardId", http)
    }

    /**
     * Asks the shard's cluster to take a snapshot.
     *
     * The odd one out in this controller, twice over. It does not go to the gateway -- a snapshot
     * is a cluster admin request, not a message for the log (see [ClusterAdmin]). And it is the
     * only command here that can honestly report `confirmed`, because Aeron answers it; the other
     * four are applied or rejected in silence.
     *
     * Worth taking one before any restart that reapplies geometry: without it the next start
     * replays the log from the last snapshot, which after a session's trading is the difference
     * between seconds and a very long time.
     */
    @PostMapping("/shards/{shardId}/snapshot")
    fun snapshot(
        @PathVariable shardId: Int,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> {
        val result = clusterAdmin.snapshot(shardId)
        return accepted(result, "snapshot", "shard:$shardId", http)
    }

    /**
     * 502 when the bytes never left: the control plane is reachable but the exchange is not, which
     * is a different problem from a malformed request and should not read as one.
     */
    private fun accepted(
        result: CommandResult,
        action: String,
        target: String,
        http: HttpServletRequest,
    ): ResponseEntity<CommandResult> {
        // Recorded whether or not the bytes left, and `sent` says which. A command the control
        // plane could not deliver is exactly as interesting to an investigation as one it did.
        audit.record(action, target, result.detail, result.sent, http)
        return if (result.sent) {
            ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
        } else {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(result)
        }
    }
}
