import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
    application
}

group = "cz.skybit.mrsd"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"
val exposedVersion = "1.3.0"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Ktor
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Exposed + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.34")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

application {
    mainClass.set("cz.skybit.mrsd.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("mrsd")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

// No Gradle Java toolchain: the build runs on the JVM provided by mise
// (.mise.toml pins temurin-25). We only pin the bytecode target — and Java and
// Kotlin must agree on it.
java {
    sourceCompatibility = JavaVersion.VERSION_23
    targetCompatibility = JavaVersion.VERSION_23
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_23
    }
}
