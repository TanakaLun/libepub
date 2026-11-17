plugins {
    alias(libs.plugins.agp.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "org.libepub.render"
    // Compose 1.12.x itself requires compileSdk 37 (its AAR metadata says so),
    // and this value is what we publish as `minCompileSdk`: hosts must build
    // against SDK 37 or newer.
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    // Compose types appear in the public API (@Composable, Modifier, ImageBitmap),
    // so consumers must see these on their compile classpath.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    // Deliberately no Material dependency: the reader draws with foundation
    // primitives and takes press feedback from the host's LocalIndication, so it
    // works under Material 2, Material 3, Cupertino or a custom design system.

    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                pom {
                    name.set("libepub-render")
                    description.set(
                        "Jetpack Compose EPUB reader: paged and scroll modes, images, " +
                            "ruby, footnotes and vertical writing.",
                    )
                }
            }
        }
    }
}

// Publishing only the renderer would leave a POM pointing at a core artifact
// that is not in the repository yet, so pull the dependency along.
tasks.matching { it.name == "publishToMavenLocal" }.configureEach {
    dependsOn(":core:publishToMavenLocal")
}
