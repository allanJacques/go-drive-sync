// Kotlin JVM compila para bytecode Java; Compose adiciona o runtime desktop e o compilador
// transforma funções @Composable. Serialization fica disponível para futuros modelos de dados.
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.godrivesync"
version = "0.1.0-SNAPSHOT"

// Locais onde o Gradle busca bibliotecas; não são chamadas feitas pela interface em execução.
repositories {
    mavenCentral()
    google()
}

dependencies {
    // Runtime e binários gráficos adequados ao SO atual; Material 3 tem versão própria.
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")

    // Exposed descreve o esquema com SQL tipado; java-time mapeia java.time.LocalDateTime.
    // Os três módulos usam a mesma versão para manter a compatibilidade das APIs v1.
    implementation("org.jetbrains.exposed:exposed-core:1.6.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.6.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.6.0")
    // H2 é embarcado: cria o arquivo local sem instalar um servidor de banco separado.
    implementation("com.h2database:h2:2.4.240")
    // Flyway aplica os scripts SQL versionados antes da UI; Exposed continua sendo a DSL de dados.
    implementation("org.flywaydb:flyway-core:13.12.1")
    // Injeção de dependências, corrotinas (incluindo dispatcher Swing/desktop) e JSON.
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // SDKs para integração com Drive nas próximas etapas. O fluxo OAuth usa HTTP do JDK
    // para controlar state, PKCE e cancelamento; a interface ainda não chama esses clientes.
    implementation("com.google.api-client:google-api-client:2.9.2")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20260901-2.0.0")

    // JUnit 4 executa testes com H2 e OAuth local, usando dados fictícios e recursos temporários.
    testImplementation(kotlin("test-junit"))
}

// Toolchain fixa o JDK usado para compilar/executar tarefas compatíveis com essa configuração.
kotlin {
    jvmToolchain(25)
}

// MainKt é a classe gerada para funções de nível superior em Main.kt.
application {
    mainClass = "com.godrivesync.MainKt"
    // Permite acesso nativo exigido pelo backend gráfico no JDK usado pelo projeto.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Diagnóstico explícito do OAuth, separado do início da UI e do build. Não inicializa o banco
// nem salva/exibe tokens. JavaExec usa o mesmo JDK 25; --args permite informar outro JSON externo.
tasks.register<JavaExec>("oauthLogin") {
    group = "application"
    description = "Valida OAuth no navegador sem persistir ou exibir tokens."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.godrivesync.drive.auth.OAuthSmokeTestKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    workingDir = projectDir
}
