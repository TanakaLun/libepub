plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.libepub.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.libepub.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    sourceSets.getByName("main").assets.directories.add(rootProject.projectDir.resolve("assets").absolutePath)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":render"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
