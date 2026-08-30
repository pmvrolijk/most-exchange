-- Reference price and collar widths.
--
-- These are NOT geometry and are deliberately absent from the published shard security file. They
-- arrive at runtime as SecurityDefinition commands through the replicated log, because every node
-- must apply them at the same log position (Design.md §7). Authoring them here records what an
-- operator seeded; it does not record what the engine currently holds, and cannot: every executing
-- uncross resets staticReference underneath us (Design.md §4.4).
--
-- They are also outside the fingerprint, which covers only what every process must agree on at
-- boot. Adding them must not change a single existing fingerprint, and does not.

ALTER TABLE security
    ADD COLUMN reference_price    BIGINT  CHECK (reference_price > 0),
    ADD COLUMN static_collar_bps  INTEGER CHECK (static_collar_bps >= 0),
    ADD COLUMN dynamic_collar_bps INTEGER CHECK (dynamic_collar_bps >= 0);
