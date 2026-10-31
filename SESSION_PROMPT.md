# SESSION PROMPT — Go Drive Sync

> Copie este arquivo inteiro e cole em uma nova sessão do OpenCode dentro da pasta do projeto.

---

## INSTRUÇÕES PARA A SESSÃO

Estou trabalhando no projeto **Go Drive Sync**, um cliente desktop para Google Drive multi-conta.

### Primeiro passo obrigatório

Leia o arquivo `PLAN.md` na raiz do projeto. Ele contém a especificação completa: stack de versões, estrutura de diretórios, tabelas do banco, layout das telas, arquitetura de sincronização, e todas as decisões tomadas até agora.

### Segundo passo

Leia o issue atual que eu quero implementar. O número está no título da issue no GitHub.

### Workflow (obrigatório)

- **NÃO** commitar direto no `main`
- Toda implementação deve ser feita em uma branch `feat/<issue-number>-<descricao-curta>`
- Após implementar, abrir um Pull Request com "Closes #X" na descrição (X = número da issue)
- O merge deve ser feito pelo usuário (não faça merge automaticamente)

### Stack do Projeto

| Componente | Versão |
|---|---|
| JDK | Java 25 LTS |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 (Desktop JVM) |
| Gradle | 9.7.0 (Kotlin DSL) |
| Banco | H2 2.4.240 (embarcado, 100% Java) |
| ORM | Exposed DSL 1.6.0 |
| DI | Koin 4.2.2 (DSL) |
| Auth | Google OAuth 2.0 (loopback server) |
| Coroutines | kotlinx-coroutines 1.11.0 |

### Estrutura do Projeto

```
src/main/kotlin/com/godrivesync/
├── Main.kt                 # Entry point
├── App.kt                  # Root composable + navegação (sidebar)
├── data/
│   ├── db/
│   │   ├── Database.kt     # Conexão H2
│   │   ├── tables/         # AccountsTable, FolderMappingsTable, SyncHistoryTable
│   │   └── repositories/   # AccountRepository, MappingRepository, HistoryRepository
│   └── models/             # Account, FolderMapping, SyncDirection, SyncHistoryEntry
├── drive/
│   ├── auth/OAuthManager.kt
│   ├── api/DriveApiClient.kt
│   └── sync/               # SyncEngine, FileHandler
└── ui/
    ├── theme/Theme.kt      # Material 3
    ├── components/          # Sidebar, ProgressBar
    └── screens/             # login, dashboard, mappings, sync, history, accounts, settings
```

### Banco de Dados — Três Tabelas

1. **accounts**: id, email, access_token, refresh_token, token_expiry, is_default, last_full_sync, created_at
2. **folder_mappings**: id, account_id (FK), drive_folder_id, drive_folder_name, local_folder_path, sync_direction, last_sync, enabled
3. **sync_history**: id, account_id (FK), mapping_id (FK nullable), drive_file_id, file_name, file_path, direction, status, file_size, timestamp, error_message

### Repositório

- **GitHub:** https://github.com/allanJacques/go-drive-sync
- **Project (Kanban):** https://github.com/users/allanJacques/projects/4
- **Milestones:** 7 milestones (M1 a M7), cada uma com issues atribuídas

---

## TAREFA ATUAL

Implementar a **Issue #1 — 1.1 Scaffold Gradle + Compose Multiplatform (janela vazia)**.

### O que fazer

1. Criar a branch: `git checkout -b feat/1-scaffold`
2. Configurar a estrutura do projeto Gradle (Kotlin DSL)
3. Criar `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
4. Configurar o Gradle Wrapper com versão 9.7.0
5. Adicionar plugins: `kotlin-jvm`, `compose`, `application`
6. Adicionar dependências do Compose Multiplatform Desktop (compose-desktop, material3)
7. Criar `src/main/kotlin/com/godrivesync/Main.kt` — janela Compose vazia que abre
8. Verificar que `./gradlew run` compila e abre uma janela vazia
9. Configurar Java 25 LTS como target (sourceCompatibility, jvmToolchain)
10. `git push origin feat/1-scaffold`
11. Abrir Pull Request no GitHub com:
    - Título: "1.1 - Scaffold Gradle + Compose Multiplatform"
    - Descrição: "Closes #1"

### Configuração esperada do build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
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
    implementation(compose.material3)
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "com.godrivesync.MainKt"
}
```

### Configuração esperada do settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
    }
}

rootProject.name = "go-drive-sync"
```

### Verificação

```bash
./gradlew run
# Deve abrir uma janela escura vazia com o título "Go Drive Sync"
```

---

### Se você completar esta tarefa

1. Faça push e abra o PR
2. Depois, pronto para começar a **Issue #3 — 1.2 Configurar tema Material 3 + sidebar** (próximo milestone)