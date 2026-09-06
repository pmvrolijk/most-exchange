---
name: wire-change
description: Use when editing sbe/src/main/resources/message-schema.xml, changing a message field, blockLength or schema version, or altering anything that crosses a process boundary (engine↔gateway, book event stream, depth feeds, discovery, snapshot format). Five defects reached working code through this path; this is the checklist that catches them.
---

# Changing the wire

`e2e/run-e2e.sh` exists because five wire defects reached working code and unit tests saw none of
them: a clobbered Aeron session header, `cumQty` derived from a terminal report, an unenforced
ladder-range invariant, a cluster session that died after 10 s idle, and processes that ignored
SIGTERM. Everything below is a response to one of those.

## Before editing

- **Never hand-write a byte offset.** Edit `sbe/src/main/resources/message-schema.xml`; codecs
  regenerate into `sbe/build/generated/sbe/`.
- Check the spare padding actually exists before assuming a field fits. `ExecutionReport`'s fields
  occupied 58 of 64 bytes — six spare, which cannot absorb one `int64`.
- Ask what the **previous version** decodes to. A field added at `sinceVersion="3"` reads as the SBE
  null value out of a v2 snapshot. Decide what that means and make it explicit — `origQty = 0` means
  *unknown* and travels to the client as `Enrichment.UNKNOWN`, never as a number nobody can justify.

## While editing

- Add fields with `sinceVersion`, bump `blockLength`, bump the schema `version` attribute.
- Ask whether the field must be **stated** rather than derived. `cumQty` is stamped by the engine
  because `origQty - leavesQty` reports a cancelled order as fully filled — a terminal report carries
  `leavesQty = 0` either way. Putting only the input on the wire re-opens that defect one layer along.
- Encoding offset depends on the publication:
  - **Cluster session** (`ClientSession.tryClaim`) — encode at
    `claim.offset() + AeronCluster.SESSION_HEADER_LENGTH`.
  - **Plain publication** (`most load`, book events) — encode at `claim.offset()`. Applying the
    cluster rule here shifts every message by 32 bytes.
- Use `tryClaim` and encode into the log buffer. Do not encode into a scratch buffer and `offer`.
- If a test fake stands in for the real thing, the fake must model the awkward part. The session
  header bug existed *because* the double was convenient.

## After editing

```sh
./gradlew :sbe:generateSbeCodecs
./gradlew build
./gradlew installDist && ./e2e/run-e2e.sh
```

`run-e2e.sh` is the check, not the build. Run it after changing anything on the wire.

If the change touches snapshot format or restore, also run `./e2e/run-restart.sh` — it is the only
check that state survives a restart, and it covers the geometry-change refusals.

## Same commit, always

- `docs/Design.md` §5 and the schema file are meant to be identical — the schema was extracted from
  the doc. Update both.
- If a rendered file format changed, `render()` and `from(Properties)` are inverses and a round-trip
  test enforces it. Keep them beside each other.
