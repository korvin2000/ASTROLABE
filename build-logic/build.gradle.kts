plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.kotlin.serialization))
}

/** Maps a version-catalog plugin alias to its Gradle plugin-marker artifact. */
fun plugin(alias: Provider<PluginDependency>): Provider<String> =
    alias.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
