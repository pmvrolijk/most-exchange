package com.engine.control

import com.engine.reference.ParticipantRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/** A refusal, in the domain's own words. */
data class ApiError(val error: String, val message: String?)

/**
 * Domain exceptions become 400s carrying their own message.
 *
 * `require` and `check` in `reference` already say exactly what is wrong — "a security must be
 * served by exactly one shard", "shard 0 hosts at most 10 securities" — and those sentences are the
 * same ones the processes would print at boot. Paraphrasing them here would give an operator two
 * different descriptions of one rule.
 */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun invalid(e: RuntimeException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("invalid", e.message))

    /** A release version whose directory is already on disk; see [ReleaseDirectoryExists]. */
    @ExceptionHandler(ReleaseDirectoryExists::class)
    fun releaseExists(e: ReleaseDirectoryExists): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError("conflict", e.message))

    /** A unique symbol or ISIN already taken, or a shard still referenced by a security. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(e: DataIntegrityViolationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError("conflict", e.mostSpecificCause.message))
}

@RestController
@RequestMapping("/api/shards")
class ShardController(private val topology: TopologyService) {

    @GetMapping
    fun list(): List<ShardRow> = topology.shards()

    @GetMapping("/{shardId}")
    fun get(@PathVariable shardId: Int): ResponseEntity<ShardRow> =
        topology.shard(shardId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PostMapping
    fun create(@RequestBody row: ShardRow): ResponseEntity<ShardRow> =
        ResponseEntity.status(HttpStatus.CREATED).body(topology.createShard(row))

    @PutMapping("/{shardId}")
    fun update(@PathVariable shardId: Int, @RequestBody row: ShardRow): ResponseEntity<ShardRow> =
        topology.updateShard(row.copy(shardId = shardId))
            ?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{shardId}")
    fun delete(@PathVariable shardId: Int): ResponseEntity<Void> =
        if (topology.deleteShard(shardId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}

@RestController
@RequestMapping("/api/securities")
class SecurityController(private val topology: TopologyService) {

    @GetMapping
    fun list(): List<SecurityRow> = topology.securities()

    @GetMapping("/{securityId}")
    fun get(@PathVariable securityId: Int): ResponseEntity<SecurityRow> =
        topology.security(securityId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    fun create(@RequestBody row: SecurityRow): ResponseEntity<SecurityRow> =
        ResponseEntity.status(HttpStatus.CREATED).body(topology.createSecurity(row))

    @PutMapping("/{securityId}")
    fun update(
        @PathVariable securityId: Int,
        @RequestBody row: SecurityRow,
    ): ResponseEntity<SecurityRow> =
        topology.updateSecurity(row.copy(securityId = securityId))
            ?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{securityId}")
    fun delete(@PathVariable securityId: Int): ResponseEntity<Void> =
        if (topology.deleteSecurity(securityId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}

@RestController
@RequestMapping("/api/participants")
class ParticipantController(private val topology: TopologyService) {

    @GetMapping
    fun list(): List<ParticipantRow> = topology.participants()

    @GetMapping("/{participantId}")
    fun get(@PathVariable participantId: Long): ResponseEntity<ParticipantRow> =
        topology.participant(participantId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    fun create(@RequestBody row: ParticipantRow): ResponseEntity<ParticipantRow> =
        ResponseEntity.status(HttpStatus.CREATED).body(topology.createParticipant(row))

    @PutMapping("/{participantId}")
    fun update(
        @PathVariable participantId: Long,
        @RequestBody row: ParticipantRow,
    ): ResponseEntity<ParticipantRow> =
        topology.updateParticipant(row.copy(participantId = participantId))
            ?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{participantId}")
    fun delete(@PathVariable participantId: Long): ResponseEntity<Void> =
        if (topology.deleteParticipant(participantId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}

/** A gateway's secret, sent once on create or rotate and never returned again. */
data class GatewaySecretRequest(val secret: String? = null)

/**
 * The secret in plaintext, returned exactly once because it is unrecoverable afterwards.
 *
 * Only the SHA-256 is stored, and unlike an operator password that cannot be BCrypt: the consensus
 * module verifies the digest against what a gateway presents, so it has to be reproducible.
 */
data class GatewaySecretIssued(val gatewayId: String, val secret: String)

/** What a gateway row grants, as the audit log records it. */
private fun grants(row: GatewayRow): String =
    "participants ${row.participants.sorted()}, cancelOnly ${row.cancelOnly.sorted()}, " +
        "operator ${row.operator}, primaryFor ${row.primaryFor.sorted()}"

/**
 * Gateway identity, and which participants each gateway speaks for.
 *
 * This is the authoring half of Design.md §1's participant registry, which was a hand-written
 * published file until now — the database held the participants, the file held the claims, and
 * nothing checked that the two agreed. Publishing a release renders the file from these rows.
 *
 * Audited, unlike the participant CRUD beside it: moving a participant between gateways decides
 * where that participant's fills are delivered, which is a market-affecting change even though no
 * order is refused by it.
 */
@RestController
@RequestMapping("/api/gateways")
class GatewayController(
    private val topology: TopologyService,
    private val audit: OperatorAudit,
) {

    @GetMapping
    fun list(): List<GatewayRow> = topology.gateways()

    @GetMapping("/{gatewayId}")
    fun get(@PathVariable gatewayId: String): ResponseEntity<GatewayRow> =
        topology.gateway(gatewayId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /**
     * Creates the gateway and issues a generated secret, which is the only time the plaintext
     * exists anywhere.
     *
     * Deliberately not accepting one here. A caller that needs a *specific* secret — a deployment
     * migrating an existing hand-written registry — follows with [rotate], which takes it in the
     * request body: a secret in a query string is a secret in an access log.
     */
    @PostMapping
    fun create(
        @RequestBody row: GatewayRow,
        http: HttpServletRequest,
    ): ResponseEntity<GatewaySecretIssued> {
        val issued = newSecret()
        topology.createGateway(row.copy(secretSha256 = ParticipantRegistry.sha256Hex(issued)))
        audit.record(
            "gateway.create", "gateway:${row.gatewayId}",
            "shard ${row.shardId}, ${grants(row)}", true, http,
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(GatewaySecretIssued(row.gatewayId, issued))
    }

    @PutMapping("/{gatewayId}")
    fun update(
        @PathVariable gatewayId: String,
        @RequestBody row: GatewayRow,
        http: HttpServletRequest,
    ): ResponseEntity<GatewayRow> {
        val updated = topology.updateGateway(row.copy(gatewayId = gatewayId))
            ?: return ResponseEntity.notFound().build()
        audit.record(
            "gateway.update", "gateway:$gatewayId",
            "shard ${row.shardId}, ${grants(row)}, enabled ${row.enabled}", true, http,
        )
        return ResponseEntity.ok(updated)
    }

    /**
     * Rotation, separate from the entity update for the reason a password is: an edit that
     * happens to omit the secret must not silently clear it.
     */
    @PutMapping("/{gatewayId}/secret")
    fun rotate(
        @PathVariable gatewayId: String,
        @RequestBody request: GatewaySecretRequest,
        http: HttpServletRequest,
    ): ResponseEntity<GatewaySecretIssued> {
        val issued = request.secret ?: newSecret()
        require(issued.length >= MIN_SECRET_LENGTH) {
            "a gateway secret must be at least $MIN_SECRET_LENGTH characters"
        }
        if (!topology.rotateGatewaySecret(gatewayId, issued)) return ResponseEntity.notFound().build()
        // The old secret keeps working until the release carrying the new one is published and the
        // nodes have re-read it, which is the whole point of the reload: no node restart.
        audit.record(
            "gateway.rotateSecret", "gateway:$gatewayId",
            "in force once the next release is published and reloaded", true, http,
        )
        return ResponseEntity.ok(GatewaySecretIssued(gatewayId, issued))
    }

    @DeleteMapping("/{gatewayId}")
    fun delete(
        @PathVariable gatewayId: String,
        http: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!topology.deleteGateway(gatewayId)) return ResponseEntity.notFound().build()
        audit.record("gateway.delete", "gateway:$gatewayId", null, true, http)
        return ResponseEntity.noContent().build()
    }

    /** The registry this shard would publish, rendered exactly as the release will render it. */
    @GetMapping("/registry/{shardId}", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun registry(@PathVariable shardId: Int): ResponseEntity<String> =
        topology.participantRegistry(shardId)?.let { ResponseEntity.ok(it.render()) }
            ?: ResponseEntity.notFound().build()

    private companion object {
        const val MIN_SECRET_LENGTH = 16
        val RANDOM = java.security.SecureRandom()

        /** 256 bits of entropy, hex, so it survives the `gatewayId:secret` credential encoding. */
        fun newSecret(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

@RestController
@RequestMapping("/api")
class TopologyController(private val topology: TopologyService, private val importer: SpecImporter) {

    /**
     * The draft, with its fingerprints and whatever is wrong with it. Reports every problem rather
     * than the first: a half-built topology is the normal state while someone is editing it, and
     * `problems` being empty is exactly the condition for publishing.
     */
    @GetMapping("/topology")
    fun topology(): TopologyView = topology.view()

    /** Seeds from an existing shard security file, so a deployment need not be retyped. */
    @PostMapping("/import", consumes = [MediaType.TEXT_PLAIN_VALUE])
    fun import(@RequestBody body: String): ImportResult = importer.import(body)
}

@RestController
@RequestMapping("/api/releases")
class ReleaseController(private val publisher: ReleasePublisher) {

    @GetMapping
    fun list(): List<ReleaseRow> = publisher.releases()

    @GetMapping("/{version}")
    fun get(@PathVariable version: Long): ResponseEntity<ReleaseRow> =
        publisher.release(version)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/latest")
    fun latest(): ResponseEntity<ReleaseRow> =
        publisher.latest()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PostMapping
    fun publish(@RequestParam(required = false) note: String?): ResponseEntity<ReleaseRow> =
        ResponseEntity.status(HttpStatus.CREATED).body(publisher.publish(note))

    /**
     * The artifact itself, served as text so a deploy step can pull the exact bytes a process will
     * boot from rather than depending on a shared mount.
     */
    @GetMapping("/{version}/files/{name}", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun file(@PathVariable version: Long, @PathVariable name: String): ResponseEntity<String> =
        publisher.artifact(version, name)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
