package com.engine.core

/**
 * A node declining to go on, as opposed to crashing. The process hosting the service leaves through
 * its orderly shutdown and prints only [message]: the report is the whole story, and a stack trace
 * would bury it.
 *
 * Thrown on the service's thread, where Aeron routes it to the container's error handler rather
 * than out of `launch()`. An exception thrown while a log fragment is handled is reported and then
 * *swallowed* -- the image advances and keeps polling -- so whatever throws one must also stop
 * applying state itself (CLAUDE.md, "Recovery and restore").
 */
open class NodeRefusal(report: String) : RuntimeException(report)

/**
 * Thrown when the leader's `ConfigurationAnnouncement` disagrees with what this node booted with
 * (Design.md §7, "Enforced through the log").
 *
 * Two nodes on different geometry, or a different `auctionMaxPasses`, would diverge on the first
 * order while each believed itself correct. Consensus cannot catch that, because the log they
 * disagree about is identical. Refusing is the only outcome that is never a divergence.
 */
class ConfigurationMismatch(report: String) : NodeRefusal(report)
