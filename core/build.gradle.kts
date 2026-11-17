import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

// Published for Android hosts as well, so stay on Java 17 bytecode (D8 handles
// it on every supported AGP) instead of the JDK 21 default.
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    implementation(libs.kxml2)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("libepub-core")
                description.set(
                    "EPUB 2/3 parsing, CSS cascade, text layout and pagination. " +
                        "Pure JVM: no Android or Compose dependency.",
                )
            }
        }
    }
}
