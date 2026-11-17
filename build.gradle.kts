plugins {
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.agp.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Coordinates of the published libraries: org.libepub:core / org.libepub:render.
// Override the version with -PlibepubVersion=… when publishing a new build.
allprojects {
    group = "org.libepub"
    version = providers.gradleProperty("libepubVersion").getOrElse("0.1.0")
}
