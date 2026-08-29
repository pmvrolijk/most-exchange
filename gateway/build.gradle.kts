plugins { application }

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
