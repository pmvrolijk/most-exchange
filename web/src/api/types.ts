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

/**
 * A gateway process's identity on a shard. The secret is never carried here: only its SHA-256 is
 * stored, and the plaintext exists exactly once, in a GatewaySecretIssued.
 */
export interface Gateway {
  gatewayId: string
  shardId: number
  enabled: boolean
  participants: number[]
}

export interface GatewaySecretIssued {
  gatewayId: string
  secret: string
}

export interface ShardView {
  shard: Shard
  securities: Security[]
  /** The value every process prints at boot. Differing values across nodes is a silent divergence. */
  fingerprint: string | null
  /** The participant registry's own hash, deliberately separate from the geometry fingerprint. */
  registryFingerprint: string | null
  problem: string | null
}

export interface TopologyView {
  shards: ShardView[]
  /**
   * A string, not a number, and it must stay one. The universe version is a 64-bit hash, and JSON
   * numbers are doubles here: parsed as a number it loses its low digits, so a console would show
   * an identity the exchange never produced. Compared and displayed, never arithmetic.
   */
  universeVersion: string | null
  /** Empty is exactly the condition for publishing, so this list is both diagnosis and gate. */
  problems: string[]
}

export interface Release {
  version: number
  createdAt: string
  /** A string for the reason on TopologyView.universeVersion. */
  universeVersion: string
  directory: string
  note: string | null
  fingerprints: Record<string, string>
  /** Present only for shards that registered a gateway; see Gateway. */
  registryFingerprints?: Record<string, string>
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
  /** A string for the reason on TopologyView.universeVersion. */
  version: string
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

/**
 * The outcome of one operator command.
 *
 * `sent` and `confirmed` are separate in the Kotlin and stay separate here. Operator commands are
 * unacknowledged — the engine applies or rejects them without replying — so `sent` claims only
 * that the bytes left, and `confirmed` means the control plane afterwards saw the effect on the L3
 * feed. A definition can never be confirmed; nothing acknowledges one.
 */
export interface CommandResult {
  command: string
  sent: boolean
  confirmed: boolean
  detail: string
}

export interface ReopenResult {
  shardId: number
  securities: string[]
  steps: CommandResult[]
  succeeded: boolean
  /** Said every time: a session transition is shard-wide, so a reopen moves every book on it. */
  warning: string | null
}

export interface ImportResult {
  shardId: number
  fingerprint: string
  inserted: string[]
  updated: string[]
}

export interface ScheduleEntry {
  at: string
  phase: string
}

export interface Holiday {
  date: string
  description?: string | null
}

export interface Schedule {
  name: string
  zone: string
  weekdays: string[]
  entries: ScheduleEntry[]
  purgeTime: string | null
  enabled: boolean
  holidays: Holiday[]
}

export interface ScheduleRun {
  id: number
  at: string
  shardId: number
  action: string
  phase: string | null
  tradingDate: number | null
  sent: boolean
  confirmed: boolean
  detail: string | null
}

/** What one reconciliation tick decided for one shard, including what it refused to do. */
export interface ScheduleDecision {
  shardId: number
  schedule: string
  expectedPhase: string
  observedPhase: string | null
  actions: CommandResult[]
  skipped: string | null
}

export interface BookLevel {
  /** Fixed point, 8 implied decimals. Safe as a JS number up to ~90 million; format, never compute. */
  price: number
  qty: number
  orders: number
}

/**
 * One book at one instant, conflated by the control plane.
 *
 * `synchronised` is the field that matters: false means the depth here is **not** the book — no
 * snapshot has arrived yet, or a gap invalidated it — and the levels are empty for that reason
 * rather than because there is no liquidity. The two must never render the same way.
 */
export interface BookImage {
  securityId: number
  symbol: string | null
  shardId: number | null
  version: number
  at: string
  synchronised: boolean
  state: 'WAITING' | 'BUILDING' | 'SYNCHRONISED'
  bids: BookLevel[]
  asks: BookLevel[]
  bestBid: number | null
  bestAsk: number | null
  spread: number | null
  lastTradePrice: number | null
  lastTradeQty: number
  bidLevelsTotal: number
  askLevelsTotal: number
  /** From L3, joined on by the control plane: depth alone cannot say the market is shut. */
  phase: string | null
  halted: boolean
}

export interface DepthFeedStatus {
  subscribed: boolean
  detail: string
  messagesSeen: number
  /** Milliseconds since the last feed message. Subscribed and silent is a real, reachable state. */
  silentMs: number | null
  securities: number[]
  synchronised: number
  snapshotsApplied: number
  snapshotsDiscarded: number
  desynchronisations: number
  gapsDetected: number
  messagesMissed: number
}
