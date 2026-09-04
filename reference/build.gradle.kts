// Shared reference data: the shard's security list and the tradable-universe directory.
// Every process reads the same definitions from the same file rather than keeping its own list.

dependencies {
    api(project(":sbe"))
    implementation(libs.aeron.all)
    implementation(libs.agrona)
    // Latency histograms for the hot-path instrumentation (Design.md §7). `implementation`, not
    // `api`: the engine and gateway use LatencyHistogram, never HdrHistogram's own types.
    implementation(libs.hdrhistogram)
}
