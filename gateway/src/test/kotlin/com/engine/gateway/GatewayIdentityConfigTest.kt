package com.engine.gateway

import com.engine.reference.GatewayIdentity
import com.engine.reference.ParticipantRegistry
import com.engine.reference.SecuritySpec
import com.engine.reference.ShardSpec
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The gateway's half of the participant binding is one line on the wire -- the credentials it
 * presents -- so everything worth testing here is what it refuses to start with.
 *
 * The failure being defended against is quiet: a gateway that fails to authenticate as itself
 * connects anonymously, trades perfectly well, and loses only the fills of whichever of its
 * participants have gone quiet. That is invisible until someone reconciles a `cumQty`, which is
 * why a half-configured identity has to stop the process instead.
 */
class GatewayIdentityConfigTest {

    private val shard = ShardSpec(
        shardId = 1,
        securities = listOf(
            SecuritySpec(
                securityId = 1,
                symbol = "ACME",
                isin = "US0378331005",
                name = "Acme",
                currency = "USD",
                priceFloor = 0L,
                tickSize = 1L,
                levelCount = 1024,
                maxOrders = 512,
            ),
        ),
    )

    private val registry = ParticipantRegistry(
        shardId = 1,
        gateways = listOf(
            GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L, 101L)),
        ),
    )

    private fun properties(vararg entries: Pair<String, String>): Properties =
        Properties().apply { entries.forEach { (k, v) -> setProperty(k, v) } }

    private fun config(
        registry: ParticipantRegistry? = this.registry,
        vararg entries: Pair<String, String>,
    ) = GatewayConfig.from(properties(*entries), shard, registry)

    @Test
    fun `an id and a secret produce credentials and an identity`() {
        val config = config(
            entries = arrayOf(
                GatewayConfig.GATEWAY_ID to "gw-a",
                GatewayConfig.CREDENTIAL_TOKEN to "north",
            ),
        )

        assertEquals("gw-a" to "north", config.credentials())
        assertEquals(listOf(100L, 101L), config.identity()?.participants)
        assertEquals(true, registry.verify("gw-a", "north"))
    }

    /** Design.md §1: the gateway re-reads the registry like the nodes, on the same default. */
    @Test
    fun `the registry is re-read every five seconds unless told otherwise`() {
        val defaulted = config(
            entries = arrayOf(
                GatewayConfig.GATEWAY_ID to "gw-a",
                GatewayConfig.CREDENTIAL_TOKEN to "north",
                GatewayConfig.PARTICIPANT_REGISTRY to "/releases/current/shard-1-participants.properties",
            ),
        )
        val tuned = config(
            entries = arrayOf(
                GatewayConfig.GATEWAY_ID to "gw-a",
                GatewayConfig.CREDENTIAL_TOKEN to "north",
                GatewayConfig.PARTICIPANT_REGISTRY_RELOAD_MS to "250",
            ),
        )

        assertEquals(5_000L, defaulted.registryReloadMs)
        assertEquals("/releases/current/shard-1-participants.properties", defaulted.participantRegistryFile)
        assertEquals(250L, tuned.registryReloadMs)
    }

    @Test
    fun `no id at all connects anonymously, exactly as before this existed`() {
        val config = config(registry = null)

        assertNull(config.credentials())
        assertNull(config.identity())
    }

    @Test
    fun `an id with no secret refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(entries = arrayOf(GatewayConfig.GATEWAY_ID to "gw-a"))
        }
        assertContains(failure.message!!, GatewayConfig.CREDENTIAL_TOKEN)
    }

    @Test
    fun `a secret with no id refuses to start`() {
        assertFailsWith<IllegalArgumentException> {
            config(entries = arrayOf(GatewayConfig.CREDENTIAL_TOKEN to "north"))
        }
    }

    @Test
    fun `an id with no registry to check it against refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(
                registry = null,
                entries = arrayOf(
                    GatewayConfig.GATEWAY_ID to "gw-a",
                    GatewayConfig.CREDENTIAL_TOKEN to "north",
                ),
            )
        }
        assertContains(failure.message!!, GatewayConfig.PARTICIPANT_REGISTRY)
    }

    /** A typo here would otherwise surface as a rejected cluster connection with no explanation. */
    @Test
    fun `an id the registry has never heard of refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(
                entries = arrayOf(
                    GatewayConfig.GATEWAY_ID to "gw-b",
                    GatewayConfig.CREDENTIAL_TOKEN to "north",
                ),
            )
        }
        assertContains(failure.message!!, "gw-a")
    }

    @Test
    fun `a registry published for another shard refuses to start`() {
        val otherShard = ParticipantRegistry(
            shardId = 2,
            gateways = listOf(
                GatewayIdentity("gw-a", ParticipantRegistry.sha256Hex("north"), listOf(100L)),
            ),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            config(
                registry = otherShard,
                entries = arrayOf(
                    GatewayConfig.GATEWAY_ID to "gw-a",
                    GatewayConfig.CREDENTIAL_TOKEN to "north",
                ),
            )
        }
        assertContains(failure.message!!, "shard 2")
    }

    /**
     * A secret in a properties file next to endpoints and stream ids ends up in a repository, so
     * the file form is the one to use -- and a secret in a file almost always ends with a newline
     * nobody typed.
     */
    @Test
    fun `the secret can come from a file, trailing newline and all`() {
        val file = kotlin.io.path.createTempFile(suffix = ".secret").toFile()
        file.deleteOnExit()
        file.writeText("north\n")

        val config = config(
            entries = arrayOf(
                GatewayConfig.GATEWAY_ID to "gw-a",
                GatewayConfig.CREDENTIAL_TOKEN_FILE to file.absolutePath,
            ),
        )

        assertEquals("gw-a" to "north", config.credentials())
    }

    @Test
    fun `a named secret file that is not there refuses to start`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            config(
                entries = arrayOf(
                    GatewayConfig.GATEWAY_ID to "gw-a",
                    GatewayConfig.CREDENTIAL_TOKEN_FILE to "/no/such/secret",
                ),
            )
        }
        assertContains(failure.message!!, GatewayConfig.CREDENTIAL_TOKEN_FILE)
    }
}
