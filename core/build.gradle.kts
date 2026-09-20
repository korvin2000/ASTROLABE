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
