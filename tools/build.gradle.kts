plugins { application }

dependencies {
    implementation(project(":reference"))
    implementation(project(":sbe"))
    implementation(libs.aeron.all)
    implementation(libs.agrona)
    implementation(libs.hdrhistogram)
}

application {
    applicationName = "most"
    mainClass.set("nl.lamia.most.exchange.tools.ToolsMainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
