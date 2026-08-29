// Generates the SBE codecs from message-schema.xml. Byte offsets are never hand-written
// (Design.md §5) — every process shares these generated flyweights.

val sbeTool: Configuration by configurations.creating

dependencies {
    sbeTool(libs.sbe.tool)
    api(libs.agrona)
}

val schemaFile = layout.projectDirectory.file("src/main/resources/message-schema.xml")
val generatedDir = layout.buildDirectory.dir("generated/sbe")

val generateSbeCodecs by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates SBE codecs from message-schema.xml"

    inputs.file(schemaFile).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedDir)

    classpath = sbeTool
    mainClass.set("uk.co.real_logic.sbe.SbeTool")

    // Captured as plain values: the configuration cache cannot serialize script references.
    val outDir = generatedDir.get().asFile
    val schemaPath = schemaFile.asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider { listOf(schemaPath) })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dsbe.output.dir=${outDir.absolutePath}",
            "-Dsbe.target.language=Java",
            "-Dsbe.validation.stop.on.error=true",
            "-Dsbe.validation.warnings.fatal=true",
            "-Dsbe.generate.ir=false",
        )
    })
    doFirst { outDir.deleteRecursively() }
}

sourceSets.main {
    java.srcDir(generateSbeCodecs)
}
