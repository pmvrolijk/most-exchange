// The adapter SDK, Apache-2.0 (client/LICENSE): what a process outside this project needs to reach
// the exchange -- the tradable-universe directory, the depth feeds, and order entry with its report
// sequence, resend fence and mass status (docs/Adapters.md).
//
// It depends on the codecs and the Aeron client and on nothing else here. `reference` is the
// exchange's own reference data, AGPL, and depends on this module -- never the other way round.

dependencies {
    api(project(":sbe"))
    api(libs.aeron.client)
    api(libs.agrona)
}
