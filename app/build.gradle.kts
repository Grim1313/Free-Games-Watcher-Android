plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "bz.fgw.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "bz.fgw.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.0.0"
        testInstrumentationRunner = "bz.fgw.android.BetaTestRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    bundle.language.enableSplit = false
    sourceSets.getByName("androidTest").assets.directories.add("schemas")
}

ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}

// Both variants export the same Room schema to the checked-in directory.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.room3:room3-runtime:3.0.3")
    implementation("androidx.sqlite:sqlite-framework:2.7.1")
    ksp("androidx.room3:room3-compiler:3.0.3")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}
