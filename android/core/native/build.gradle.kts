// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.coffer.ffi"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Rust core -> jniLibs + generated UniFFI Kotlin bindings.
val buildNative by tasks.registering(Exec::class) {
    val repo = rootProject.projectDir.parentFile
    inputs.files(fileTree(repo.resolve("core")) { include("**/*.rs", "**/Cargo.toml", "**/uniffi.toml"); exclude("target/**") })
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    outputs.dir(layout.projectDirectory.dir("src/main/kotlin/app/coffer/ffi"))
    commandLine(
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", repo.resolve("scripts/build-native.ps1").absolutePath,
    )
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    api("${libs.jna.get()}@aar")
}
