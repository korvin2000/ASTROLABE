plugins {
    id("astrolabe.kotlin-library")
}

description = "ASTROLABE tier-1 symbol index: tree-sitter outlines, spans, imports and syntax checks (optional module)."

dependencies {
    api(project(":core"))
    // D-175, D-210: bundled JNI natives for windows-x64, linux-x64 (glibc) and macOS; nothing else depends on them.
    implementation(libs.tree.sitter.runtime)
    implementation(libs.tree.sitter.python)
    implementation(libs.tree.sitter.javascript)
    implementation(libs.tree.sitter.typescript)
    implementation(libs.tree.sitter.tsx)
    implementation(libs.tree.sitter.java)
    implementation(libs.tree.sitter.kotlin)

    testImplementation(testFixtures(project(":core")))
}

// The grammars load through JNI (`System.load`), which JDK 26 restricts like FFM; grant it as `core` does.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
