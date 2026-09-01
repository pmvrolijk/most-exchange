/**
 * Mirrors of the control plane's response bodies.
 *
 * Hand-written and deliberately partial: these describe what the UI reads, not the full schema.
 * The authority is the Kotlin DTO — if a field is renamed there, this file is wrong and the
 * compiler here will not say so, so keep the names identical rather than tidied.
 */

export interface Identity {
  username: string
  roles: string[]
}

export interface Shard {
  shardId: number
  /** The GATEWAY's client endpoints, not the cluster's ingress — see Design.md §7. */
  orderEntryChannel: string
  orderEntryStreamId: number
  executionReportChannel: string
  executionReportStreamId: number
}

export interface Security {
  securityId: number
  shardId: number
  symbol: string
  isin: string
  name: string
  currency: string
  /** Fixed point, 8 implied decimals — never a float, in either language. */
  priceFloor: number
  tickSize: number
  levelCount: number
  maxOrders: number
  referencePrice?: number | null
  staticCollarBps?: number | null
  dynamicCollarBps?: number | null
}

export interface Participant {
  participantId: number
  name: string
  smpId: number
  enabled: boolean
}

export interface ShardView {
  shard: Shard
  securities: Security[]
  /** The value every process prints at boot. Differing values across nodes is a silent divergence. */
  fingerprint: string | null
  problem: string | null
}

export interface TopologyView {
  shards: ShardView[]
  universeVersion: number | null
  /** Empty is exactly the condition for publishing, so this list is both diagnosis and gate. */
  problems: string[]
}

export interface Release {
  version: number
  createdAt: string
  universeVersion: number
  directory: string
  note: string | null
  fingerprints: Record<string, string>
}

export interface HaltState {
  at: string
  collarReference: number
  attemptedPrice: number
  breachedBound: number
  aggressorSide: string
  clearedAt: string | null
}

export interface SecurityState {
  securityId: number
  shardId: number
  phase: string | null
  phaseAt: string | null
  /** A halt is visible only on L3; nothing derives it onto L1 or L2. */
  halt: HaltState | null
  lastUncrossPrice: number | null
  lastUncrossQty: number | null
  lastTradePrice: number | null
  lastTradeQty: number | null
  lastTradeAt: string | null
}

export interface DirectoryState {
  version: number
  securities: number
  shards: number[]
  lastSeenAt: string
  incompleteBroadcasts: number
}

export interface ExchangeStatus {
  connected: boolean
  detail: string
  directory: DirectoryState | null
  securities: SecurityState[]
  feedGaps: number
  eventsMissed: number
  eventsSeen: number
  routingDrift: string[]
}

export interface AuditEntry {
  id: number
  at: string
  username: string
  action: string
  target?: string | null
  detail?: string | null
  /** Sent, not applied. Operator commands are unacknowledged, so this is all the server can claim. */
  sent: boolean
  remoteAddr?: string | null
}

export interface ControlUser {
  username: string
  role: string
  enabled: boolean
}
