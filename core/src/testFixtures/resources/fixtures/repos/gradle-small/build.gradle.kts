plugins {
    kotlin("jvm") version "2.4.20"
}

// Same coordinates and versions as the outer build, so `gradle test --offline`
// resolves entirely from the Gradle cache the outer build already populated.
// The fixture carries no wrapper: tests launch it with the outer
// distribution (`Runners.gradle()`).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
