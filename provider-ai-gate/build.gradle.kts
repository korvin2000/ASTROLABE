plugins {
    id("astrolabe.kotlin-library")
}

description = "ASTROLABE provider adapter over AI Gate (llm-transport-sdk): HTTP, credentials, codecs, streaming, cancellation."

dependencies {
    api(project(":provider-api"))
    // D-332: `Llm` is part of the adapter's constructor, so the SDK is an API dependency of this module only.
    api(libs.ai.gate)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

// Campaign tests through the real adapter run core's process control (FFM), as core's own tests do.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Doc phase 7: billable smoke calls against real providers (LiveSmokeTest), keys from the environment; never part of `check`.
tasks.register<Test>("liveTest") {
    description = "Runs LiveSmokeTest against real providers; keys come from the environment (see the test's KDoc)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("io.astrolabe.provider.aigate.LiveSmokeTest") }
    systemProperty("astrolabe.live", "true")
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}
