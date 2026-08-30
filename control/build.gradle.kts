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

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

application {
    applicationName = "control"
    mainClass.set("com.engine.control.ControlApplicationKt")
}
