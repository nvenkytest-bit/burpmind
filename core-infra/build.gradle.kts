plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-app"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // YAML for the prompt library
    implementation("com.charleskorn.kaml:kaml:0.65.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
