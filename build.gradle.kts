plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.graalvm.native) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
        compilerOptions {
            // The engine's zero-allocation profile depends on inline functions staying
            // inline (Design.md §3.3): a boxed value class or a materialised lambda on the
            // matching path is fatal under Epsilon GC. Warnings are errors so a silent
            // "inline function cannot be inlined" never reaches a build.
            allWarningsAsErrors.set(true)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
        // Agrona 2.x reaches jdk.internal.misc.Unsafe for its buffer intrinsics, which JDK 17+
        // does not export by default. Aeron's driver needs sun.nio.ch for the same reason.
        // Any JVM running this code needs these; see README.md.
        jvmArgs(
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        )
    }
}
