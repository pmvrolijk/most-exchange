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

    // Everything every native image in this build needs, in one place. Four modules produce
    // native binaries and every one of them links Aeron and Agrona, so the flags below are a
    // property of the dependency stack, not of any one process -- duplicating them per module
    // is how one of them silently drifts and only fails at run time. Each module's own
    // graalvmNative block keeps only what is genuinely its own: the image name, the main class,
    // and the engine's staged Epsilon GC.
    plugins.withId("org.graalvm.buildtools.native") {
        // Aeron and Agrona are not in the reachability-metadata repository, and its schema
        // requires a newer GraalVM than this build is pinned to. Nothing is lost by not
        // consulting it, and it otherwise fails the build before native-image is even run.
        // It is a nested extension rather than a member of GraalVMExtension, so it is reached
        // through ExtensionAware here; inside a module's own script the Kotlin DSL accessor hides
        // that.
        val graalvmNative = extensions.getByName("graalvmNative")
        (graalvmNative as ExtensionAware).extensions
            .configure<org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension>(
                "metadataRepository"
            ) { enabled.set(false) }

        (graalvmNative as org.graalvm.buildtools.gradle.dsl.GraalVMExtension).run {
            binaries.named("main") {
                buildArgs.addAll(
                    "--no-fallback",
                    "-O3",
                    // Agrona 2.x reaches jdk.internal.misc.Unsafe for its buffer intrinsics and
                    // the Aeron driver reaches sun.nio.ch (README.md). On the JVM that is an
                    // --add-opens at run time; for a native image it must be an *export at image
                    // build time*, or the analysis cannot see the class, silently omits it from
                    // the image, and the first UnsafeBuffer dies with NoClassDefFoundError the
                    // moment a real Aeron CnC file exists. The -J form opens it to the builder
                    // JVM, which is what actually loads the class during analysis.
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
                    "-J--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-J--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
                    // Aeron's driver and Agrona's buffers must initialise at run time.
                    "--initialize-at-run-time=io.aeron.driver.MediaDriver,org.agrona.concurrent.UnsafeBuffer",
                    // ...but UnsafeApi cannot. Agrona 2.x reaches Unsafe through an invokedynamic
                    // call site, and resolving that site during analysis runs its <clinit>, so
                    // deferring it is not possible and it must be declared build-time rather than
                    // fought. SVM substitutes jdk.internal.misc.Unsafe with its own singleton, so
                    // nothing host-specific is baked into the image heap.
                    "--initialize-at-build-time=org.agrona.UnsafeApi",
                    "--initialize-at-build-time=kotlin.DeprecationLevel",
                    // Without this a native image takes SIGTERM's default disposition and dies on
                    // the spot: `ShutdownSignalBarrier` never releases, so the orderly shutdown
                    // every one of these processes ends with -- closing the cluster service
                    // container, the publications, the media driver attachment -- simply does not
                    // run. The JVM start scripts have this behaviour for free, which is exactly
                    // why its absence here is easy to miss: `e2e/run-e2e.sh` only checks that
                    // nothing died *during* the run, so it passed all the way through.
                    "--install-exit-handlers",
                )

                // Design.md §7: pin -march explicitly. A -march=native build SIGILLs when the
                // build host's CPU differs from production, so CI sets engine.march; local
                // builds on a different architecture leave it unset.
                providers.gradleProperty("engine.march").orNull?.let {
                    buildArgs.add("-march=$it")
                }
            }
        }
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
