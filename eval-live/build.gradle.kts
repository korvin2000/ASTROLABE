plugins {
    id("astrolabe.kotlin-library")
    application
}

description = "ASTROLABE headless live runner: bench tasks through the product attempt on a live provider, accepted outside the agent's reach."

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-ai-gate"))
    implementation(libs.ai.gate)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // A CLI binds core's SLF4J logging itself; warnings and errors go to stderr.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("io.astrolabe.evallive.MainKt")
    applicationName = "eval-live"
    // FFM in core's `os` package; the bench reports through its own output, so the logger stays at warnings.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}

// The task set ships with the distribution: `<install>/tasks/<id>/`.
distributions {
    main {
        contents {
            from("tasks") { into("tasks") }
        }
    }
}

// Gradle's default excludes drop `.gitignore` and other dot files from every copy; a task's base keeps its own (the
// repository as the agent gets it), so installDist lays them in by path afterwards.
tasks.named<Sync>("installDist") {
    val source = layout.projectDirectory.dir("tasks").asFile
    doLast {
        val target = destinationDir.resolve("tasks")
        source.walkTopDown().filter { it.isFile && it.name.startsWith(".") }
            .forEach { it.copyTo(target.resolve(it.relativeTo(source)), overwrite = true) }
    }
}

// `-PbenchDir=<dir>` installs the distribution there; results and everything else outside bin/, lib/, tasks/ stay.
providers.gradleProperty("benchDir").orNull?.let { dir ->
    tasks.named<Sync>("installDist") {
        destinationDir = file(dir)
        preserve {
            include("**")
            exclude("bin/**", "lib/**", "tasks/**")
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("evallive.tasks", layout.projectDirectory.dir("tasks").asFile.absolutePath)
    inputs.dir("tasks")
}
