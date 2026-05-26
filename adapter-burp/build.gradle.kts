plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-app"))
    implementation(project(":core-infra"))
    implementation(project(":ui-swing"))

    // Montoya is a *compileOnly* dependency: Burp provides it at runtime.
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("net.portswigger.burp.extensions:montoya-api:2025.5")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("burpmind")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    // Burp loads extensions as a single JAR. Shadow bundles all transitive deps
    // (except Montoya, which is compileOnly).
}

tasks.named("build") { dependsOn("shadowJar") }
