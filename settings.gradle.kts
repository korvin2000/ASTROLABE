pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "astrolabe"

include(":provider-api", ":core", ":eval", ":index-treesitter")

// D-332: the AI Gate transport (llm-transport-sdk) builds from its checkout as a composite build until it is published.
// `:provider-ai-gate` exists only where that checkout is present (default: the sibling `../llm-transport-sdk/llm`,
// or `-Pastrolabe.aiGateBuild=<path>`), so a checkout without it builds and tests everything else unchanged.
val aiGateBuild = file(providers.gradleProperty("astrolabe.aiGateBuild").getOrElse("../llm-transport-sdk/llm"))
if (aiGateBuild.resolve("settings.gradle.kts").isFile) {
    includeBuild(aiGateBuild)
    include(":provider-ai-gate")
    // ASTROLABE 2.0 A0: the headless live runner drives the real adapter, so it exists only where the gate does.
    include(":eval-live")
} else {
    logger.lifecycle("provider-ai-gate and eval-live skipped: no AI Gate build at $aiGateBuild")
}
