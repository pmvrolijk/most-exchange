package com.engine.control

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
