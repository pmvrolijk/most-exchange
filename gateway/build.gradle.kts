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
    mainClass.set("com.engine.gateway.GatewayMainKt")
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
        imageName.set("order-gateway")
        mainClass.set("com.engine.gateway.GatewayMainKt")
    }
}
