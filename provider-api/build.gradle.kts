plugins {
    id("astrolabe.kotlin-library")
}

description = "ASTROLABE provider API: item model, capabilities, adapter SPI, usage and price types. No networking; never depends on core."

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
