// Repositórios de plugins do build (distintos das dependências do aplicativo).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
    }
}

// Resolver de toolchains: ajuda o Gradle a localizar/provisionar o JDK solicitado.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Identidade do projeto Gradle, usada também nos artefatos de distribuição.
rootProject.name = "go-drive-sync"
