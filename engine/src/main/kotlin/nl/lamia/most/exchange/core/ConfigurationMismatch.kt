package nl.lamia.most.exchange.core

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

/**
 * Thrown when the log carries an order or a cancel in a layout this build cannot read (Design.md §5,
 * "Client order ids"). Version 8 changed both in place, so a version 7 one decoded as version 8 would
 * be a different order. It can only be a log tail written under version 7, or a gateway that was not
 * upgraded, and every node meets it at the same position, so every node refuses it.
 */
class SchemaMismatch(report: String) : NodeRefusal(report)
