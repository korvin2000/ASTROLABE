import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

// The hidden parts of a task (`acceptance/`, `reference/`, `wrong/`) never ship as open files (WP-B2): they are
// resources of this jar, which lands in the distribution's lib/ and on the test classpath. `evallive/hidden/index`
// lists every file as `<task>/<part>/<path>`; the zip is written here so dot files are kept and entries are reproducible.
val hiddenParts = setOf("acceptance", "reference", "wrong")
val hiddenJar = tasks.register("hiddenJar") {
    description = "Packages the hidden parts of the bench tasks as classpath resources."
    val source = layout.projectDirectory.dir("tasks").asFile
    val target = layout.buildDirectory.file("hidden/eval-live-hidden.jar")
    val parts = hiddenParts.toSet()
    inputs.dir(source)
    outputs.file(target)
    doLast {
        val jar = target.get().asFile
        jar.parentFile.mkdirs()
        val names = source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }
            .filter { it.split('/').getOrNull(1) in parts }.sorted().toList()
        ZipOutputStream(jar.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = LocalDateTime.of(1980, 2, 1, 0, 0) })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("evallive/hidden/index", names.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
            names.forEach { entry("evallive/hidden/$it", source.resolve(it).readBytes()) }
        }
    }
}
dependencies { runtimeOnly(files(hiddenJar)) }

// The open part of the task set ships with the distribution: `<install>/tasks/<id>/{task.json,prompt.md,base/}`.
distributions {
    main {
        contents {
            from("tasks") {
                into("tasks")
                exclude(hiddenParts.map { "*/$it/**" })
            }
        }
    }
}

// Gradle's default excludes drop `.gitignore` and other dot files from every copy; a task's base keeps its own (the
// repository as the agent gets it), so installDist lays them in by path afterwards — never those of a hidden part.
tasks.named<Sync>("installDist") {
    val source = layout.projectDirectory.dir("tasks").asFile
    val parts = hiddenParts.toSet()
    doLast {
        val target = destinationDir.resolve("tasks")
        source.walkTopDown().filter { it.isFile && it.name.startsWith(".") }
            .filter { it.relativeTo(source).invariantSeparatorsPath.split('/').getOrNull(1) !in parts }
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
