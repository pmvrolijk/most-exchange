plugins {
    application
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // The domain library, not a copy of it: every invariant -- ISIN check digits, ten securities
    // per shard, one shard per security -- is enforced by constructing the real types, and the
    // fingerprint is computed by the same code the engine prints.
    implementation(project(":reference"))
    // The control plane holds an Aeron client of its own: it sends operator commands to the
    // gateway's client channel exactly as `most` does, and watches the L3 feed. `reference` keeps
    // these as `implementation`, so they are not transitive.
    implementation(libs.aeron.all)
    implementation(libs.agrona)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    // Authentication for the REST API. The control plane can seed definitions, move sessions
    // and run a schedule that opens a market unattended; it went from a reference-data editor
    // to something that moves markets, and the security model had to move with it.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.spring.security.test)
}

application {
    applicationName = "control"
    mainClass.set("com.engine.control.ControlApplicationKt")
    // Agrona 2.x reaches jdk.internal.misc.Unsafe for its buffer intrinsics and Aeron needs
    // sun.nio.ch; without these the first UnsafeBuffer throws IllegalAccessError.
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
