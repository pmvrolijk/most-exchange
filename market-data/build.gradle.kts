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
    mainClass.set("com.engine.marketdata.MarketDataMainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

// Native image, for the same reason the engine has one: a container that starts in milliseconds and
// holds no JIT warm-up. Unlike the engine this process is not on the matching path, so the staged
// Epsilon plan (Design.md §7) does not apply -- these ship on Serial GC and stay there.
graalvmNative {
    binaries.named("main") {
        imageName.set("market-data")
        mainClass.set("com.engine.marketdata.MarketDataMainKt")

        buildArgs.addAll(
            "--no-fallback",
            "-O3",
            // Aeron's driver and Agrona's buffers must initialise at run time.
            "--initialize-at-run-time=io.aeron.driver.MediaDriver,org.agrona.concurrent.UnsafeBuffer",
            "--initialize-at-build-time=kotlin.DeprecationLevel",
        )

        // Design.md §7: pin -march explicitly. A -march=native build SIGILLs when the build host's
        // CPU differs from production.
        providers.gradleProperty("engine.march").orNull?.let {
            buildArgs.add("-march=$it")
        }
    }
}
