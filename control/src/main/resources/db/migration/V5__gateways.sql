-- Gateway identity, and which participants each gateway speaks for.
--
-- This is the `reference` ParticipantRegistry expressed as rows. It was a hand-written published
-- file until now: the consensus module authenticated gateways against it and the engine bound
-- participants from it, while this database held the participants and nothing checked that the two
-- agreed. Rendering it from here is what closes that -- and, like every other artifact, the render
-- goes through the real domain type so the alphabet, the digest and the fingerprint have exactly
-- one implementation.
--
-- The secret is stored only as the SHA-256 the registry carries. It is not recoverable, which is
-- the point: rotating one issues a new secret rather than reading the old one back. Unlike
-- control_user.password_hash this cannot be BCrypt -- the cluster verifies the digest against what
-- a gateway presents, so it has to be reproducible.

CREATE TABLE gateway (
    -- Matches GatewayIdentity's own limits: 64 characters, and an alphabet that survives being
    -- encoded as `gatewayId:secret` in the Aeron credentials.
    gateway_id    VARCHAR(64) PRIMARY KEY CHECK (gateway_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    shard_id      INTEGER     NOT NULL REFERENCES shard (shard_id),
    secret_sha256 CHAR(64)    NOT NULL CHECK (secret_sha256 ~ '^[0-9a-f]{64}$'),
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE INDEX gateway_shard_idx ON gateway (shard_id);

-- participant_id is the primary key, so "a participant belongs to at most one gateway" is
-- structural here rather than checked -- the same shape as security_id being the primary key of
-- `security`. ParticipantRegistry refuses a registry that breaks it, and a constraint that cannot
-- be violated is better than a constructor that reports it.
CREATE TABLE gateway_participant (
    participant_id BIGINT      PRIMARY KEY REFERENCES participant (participant_id) ON DELETE CASCADE,
    gateway_id     VARCHAR(64) NOT NULL REFERENCES gateway (gateway_id) ON DELETE CASCADE
);

CREATE INDEX gateway_participant_gateway_idx ON gateway_participant (gateway_id);

-- The registry's own fingerprint, per release, deliberately a second column rather than a wider
-- `fingerprint`. That one is over shard geometry and is already written down in every release
-- published so far; rotating a gateway secret is not a change of geometry, and folding the two
-- together would make every recorded value wrong. Null for a shard with no gateways, which
-- publishes no registry artifact at all.
ALTER TABLE spec_release_shard ADD COLUMN registry_fingerprint VARCHAR(32);
