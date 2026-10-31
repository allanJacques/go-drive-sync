plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.godrivesync"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")

    implementation("org.jetbrains.exposed:exposed-core:1.6.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.6.0")
    implementation("com.h2database:h2:2.4.240")
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("com.google.api-client:google-api-client:2.9.2")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20260901-2.0.0")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.godrivesync.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
