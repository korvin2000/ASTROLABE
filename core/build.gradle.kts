plugins {
    id("astrolabe.kotlin-library")
}

description = "ASTROLABE core: campaign controller, context compiler, cell runtime, tools, workspace, evidence, verification."

dependencies {
    api(project(":provider-api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)

    testFixturesApi(project(":provider-api"))
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

// TODO P0.6.1: `io.astrolabe.os` binds process control through `java.lang.foreign`, whose lookup
// and downcall methods are restricted. JDK 26 still only warns (`--illegal-native-access=warn`);
// granting native access keeps the test output clean and keeps the suite green once the default
// becomes `deny`. Library consumers must grant it the same way — see the KDoc on `Os`.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
