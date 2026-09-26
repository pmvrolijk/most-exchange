-- What a gateway may do, not only whom it speaks for (Design.md §1, "Enforcement, at the gateway").
--
-- The gateway now enforces the registry: `participants` may place and cancel, `cancelOnly` may
-- cancel and not place, and `operator` may send operator commands. An operator-only identity --
-- operator and no participants at all -- is how the control plane and the CLI are named to a node
-- that refuses anonymous sessions.

ALTER TABLE gateway ADD COLUMN operator BOOLEAN NOT NULL DEFAULT FALSE;

-- A participant may now be listed on several gateways, a primary and a failover. V5 made
-- participant_id alone the key, which refused that -- and, less visibly, confined a participant to
-- one gateway on one shard anywhere. The key is the pair.
ALTER TABLE gateway_participant DROP CONSTRAINT gateway_participant_pkey;
ALTER TABLE gateway_participant ADD PRIMARY KEY (participant_id, gateway_id);

-- May cancel, may not place: revocation made graceful. False for every row that exists, which is
-- what those rows meant.
ALTER TABLE gateway_participant ADD COLUMN cancel_only BOOLEAN NOT NULL DEFAULT FALSE;

-- The gateway a participant is bound to when more than one listing gateway is connected. Unique per
-- participant *per shard*, which a constraint here cannot see without the gateway's shard, so the
-- service checks it; the published registry is refused by `reference` if a shard has none where it
-- needs one.
ALTER TABLE gateway_participant ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE;
