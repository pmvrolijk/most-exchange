rootProject.name = "most-exchange"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "sbe",          // SBE schema + generated codecs, shared by every process (Apache-2.0)
    "client",       // Adapter SDK: directory, depth feeds, order entry session (Apache-2.0)
    "reference",    // Security specs, shard registry, tradable-universe directory
    "discovery",    // Publishes the universe so adapters can route by security
    "engine",       // MatchingEngineService (Aeron Cluster) -> native image
    "market-data",  // Book Event Stream -> L1/L2/L3 feeds
    "gateway",      // Order entry: validation, cumQty reconstruction
    "tools",        // Operator CLI: browse the universe, send orders, inspect books
    "control",      // Control plane: Postgres reference data, REST API, published shard specs
)
