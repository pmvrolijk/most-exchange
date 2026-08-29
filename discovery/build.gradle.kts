plugins { application }

dependencies {
    implementation(project(":reference"))
    implementation(project(":sbe"))
    implementation(libs.aeron.all)
    implementation(libs.agrona)
}

application {
    mainClass.set("com.engine.discovery.DiscoveryMainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
