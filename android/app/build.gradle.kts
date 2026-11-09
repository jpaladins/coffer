// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.build.api.artifact.SingleArtifact
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.coffer"
    compileSdk = 37
    defaultConfig {
        // Store id (fixed forever after the first upload); the code package stays app.coffer.
        applicationId = "com.coffer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0a"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The release key lives outside the repository (scripts/new-keystore.ps1).
    // Without keystore.properties the release APK is built unsigned (F-Droid
    // signs its own builds; reproducibility checks compare unsigned APKs).
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties()
        FileInputStream(keystoreProps).use { props.load(it) }
        signingConfigs {
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // No Google-encrypted dependency blob: F-Droid and reproducible builds need it off.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** Fails the build if the merged manifest breaks ADR 0004 (no network, no backup). */
abstract class VerifyManifest : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = manifest.get().asFile.readText()
        val problems = buildList {
            if ("android.permission.INTERNET" in text) add("INTERNET permission present")
            if (Regex("permission\\.[A-Z_]*STORAGE").containsMatchIn(text)) add("storage permission present")
            if (!text.contains("android:allowBackup=\"false\"")) add("allowBackup is not false")
        }
        if (problems.isNotEmpty()) throw GradleException("Manifest policy violated: $problems")
        report.get().asFile.writeText("ok\n")
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<VerifyManifest>("verify${cap}Manifest") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/manifest-${variant.name}.txt"))
        }
        tasks.matching { it.name == "check" || it.name == "assemble$cap" }.configureEach { dependsOn(task) }
    }
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":core:data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
