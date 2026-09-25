plugins {
    id("astrolabe.kotlin-library")
}

description = "ASTROLABE offline evaluation: fixture runner, frozen campaigns, arms, scorecard and paired inference."

// The fixture runner executes core's FX/AX tests as a program: their classes and runtime classpath are core's test source set.
evaluationDependsOn(":core")
val coreTest: SourceSet = project(":core").extensions.getByType<SourceSetContainer>()["test"]

dependencies {
    api(project(":core"))
    // D-175 (P6.1.1): the JUnit Platform launcher runs the fixture suites outside the build; version from the pinned JUnit BOM.
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.platform.launcher)

    // Core's test classes and their runtime (core/build.gradle.kts) without resolving another project's configuration.
    testImplementation(testFixtures(project(":core")))
    testImplementation(files(coreTest.output))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    // Sample fixtures with deliberate failures run only inside the runner's own tests.
    useJUnitPlatform { excludeTags("runner-sample") }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("fixtures") {
    group = "verification"
    description = "Runs core's FX/AX fixture suites as a program and writes build/reports/fixtures/report.json (P6.1.1)."
    mainClass.set("io.astrolabe.eval.FixtureRunner")
    classpath = sourceSets["test"].runtimeClasspath - sourceSets["test"].output
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(
        "--package", "io.astrolabe",
        "--report", layout.buildDirectory.file("reports/fixtures/report.json").get().asFile.path,
        "--junit-xml", project(":core").layout.buildDirectory.dir("test-results/test").get().asFile.path,
    )
}
