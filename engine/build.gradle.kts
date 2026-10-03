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
    mainClass.set("nl.lamia.most.exchange.core.EngineMainKt")
    // Required by Agrona 2.x and the Aeron driver on JDK 17+; see README.md.
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

// Native image. The Aeron/Agrona flags, the -march pin and the metadata-repository opt-out are
// shared by every native binary in this build and live in the root build.gradle.kts; only what is
// specific to this process is set here.
graalvmNative {
    binaries.named("main") {
        imageName.set("matching-engine")
        mainClass.set("nl.lamia.most.exchange.core.EngineMainKt")

        // Epsilon GC is staged, not the default (Design.md §7): ship on Serial GC, prove zero
        // steady-state allocation, add the CI allocation assertion, then enable this. The engine
        // is the only process this applies to -- the others are not on the matching path.
        if (providers.gradleProperty("engine.useEpsilonGc").orNull == "true") {
            buildArgs.add("--gc=epsilon")
        }
    }
}
