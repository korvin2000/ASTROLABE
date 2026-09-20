import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

// Convention plugin for every ASTROLABE library module (TODO P0.1.1, D-02): JDK 26 toolchain for
// compile and tests, Java `--release 26`, Kotlin `-Xjdk-release=26`, explicit API, ABI validation,
// Java test fixtures, JUnit Platform and Maven publication with a sources jar.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "io.astrolabe"
version = providers.gradleProperty("astrolabe.version").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
    withSourcesJar()
}

kotlin {
    explicitApi()
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
        freeCompilerArgs.addAll("-Xjdk-release=26", "-Xjsr305=strict")
    }
    // KGP 2.4: calling the block enables ABI validation (the `enabled` property was removed).
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("file.encoding", "UTF-8")
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("kotlin-test-junit5").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testFixturesImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testFixturesApi"(libs.findLibrary("kotlin-test-junit5").get())
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
