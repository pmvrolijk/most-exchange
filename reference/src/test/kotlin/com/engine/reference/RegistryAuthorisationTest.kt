package com.engine.reference

import io.aeron.cluster.codecs.AdminRequestEncoder
import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.cluster.codecs.BackupQueryEncoder
import io.aeron.cluster.codecs.MessageHeaderEncoder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design.md §1: a snapshot requested through consensus is an admin request, granted on a node with a
 * registry only to an `operator=true` principal; anything else is refused except the backup and
 * standby traffic Aeron's default allows. Aeron's own default grants no snapshot request at all.
 */
class RegistryAuthorisationTest {

    private var inForce = ParticipantRegistry(
        shardId = 0,
        gateways = listOf(
            GatewayIdentity("gw-0", ParticipantRegistry.sha256Hex("a"), listOf(7L)),
            GatewayIdentity("control", ParticipantRegistry.sha256Hex("c"), emptyList(), operator = true),
        ),
    )
    private val authorisation = RegistryAuthorisationService { inForce }

    private fun snapshotBy(principal: String?) = authorisation.isAuthorised(
        MessageHeaderEncoder.SCHEMA_ID,
        AdminRequestEncoder.TEMPLATE_ID,
        AdminRequestType.SNAPSHOT,
        principal?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0),
    )

    @Test
    fun `an operator may request a snapshot`() {
        assertTrue(snapshotBy("control"))
    }

    @Test
    fun `a gateway that is not an operator may not`() {
        assertFalse(snapshotBy("gw-0"))
    }

    @Test
    fun `an unknown or empty principal may not`() {
        assertFalse(snapshotBy("gw-9"))
        assertFalse(snapshotBy(null))
    }

    /** Read per request, like the authenticator, so a reload takes effect at once. */
    @Test
    fun `revoking operator in a reload revokes the right to snapshot`() {
        assertTrue(snapshotBy("control"))
        inForce = inForce.copy(
            gateways = inForce.gateways.map { if (it.gatewayId == "control") it.copy(operator = false, participants = listOf(8L)) else it },
        )
        assertFalse(snapshotBy("control"))
    }

    /** What Aeron's default allows stays allowed, for anyone: cluster backup and standby nodes. */
    @Test
    fun `backup traffic is still allowed as Aeron's default allows it`() {
        assertTrue(
            authorisation.isAuthorised(
                MessageHeaderEncoder.SCHEMA_ID, BackupQueryEncoder.TEMPLATE_ID, null, ByteArray(0),
            )
        )
    }
}
