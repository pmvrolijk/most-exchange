plugins {
    alias(libs.plugins.graalvm.native)
    application
}

dependencies {
    implementation(project(":sbe"))
    implementation(project(":reference"))
    implementation(libs.aeron.all)
    implementation(libs.agrona)
}

application {
    mainClass.set("com.engine.core.EngineMainKt")
    // Required by Agrona 2.x and the Aeron driver on JDK 17+; see README.md.
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

graalvmNative {
    binaries.named("main") {
        imageName.set("matching-engine")
        mainClass.set("com.engine.core.EngineMainKt")

        buildArgs.addAll(
            "--no-fallback",
            "-O3",
            // Aeron's driver and Agrona's buffers must initialise at run time.
            "--initialize-at-run-time=io.aeron.driver.MediaDriver,org.agrona.concurrent.UnsafeBuffer",
            "--initialize-at-build-time=kotlin.DeprecationLevel",
        )

        // Design.md §7: pin -march explicitly. A -march=native build SIGILLs when the build
        // host's CPU differs from production, so CI sets engine.march; local builds on a
        // different architecture leave it unset.
        providers.gradleProperty("engine.march").orNull?.let {
            buildArgs.add("-march=$it")
        }

        // Epsilon GC is staged, not the default (Design.md §7): ship on Serial GC, prove
        // zero steady-state allocation, add the CI allocation assertion, then enable this.
        if (providers.gradleProperty("engine.useEpsilonGc").orNull == "true") {
            buildArgs.add("--gc=epsilon")
        }
    }
}
