# Go Drive Sync

Cliente desktop para Google Drive multi-conta com sincronização bidirecional de pastas.

## Stack

| Componente | Versão |
|---|---|
| JDK | Java 25 LTS |
| Kotlin JVM / Compose Compiler / Serialization Plugin | 2.4.21 |
| Compose Multiplatform Desktop | 1.12.2 |
| Material 3 | 1.9.0 (estável) |
| Gradle Wrapper | 9.7.0 |
| Foojay Resolver | 1.0.0 |
| Exposed (core + JDBC) | 1.6.0 |
| H2 | 2.4.240 |
| Koin (DSL) | 4.2.2 |
| kotlinx-coroutines (core + Swing) | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 |
| Google API Client | 2.9.2 |
| Google OAuth Client / Jetty | 1.39.0 |
| Google Drive SDK | v3-rev20260901-2.0.0 |

## Desenvolvimento

Use JDK 25 tanto para executar o Gradle quanto para compilar e executar o aplicativo.
O projeto usa `jvmToolchain(25)`; no IntelliJ, selecione JDK 25 em **Project SDK** e **Gradle JVM**.
O `org.gradle.java.home` em `gradle.properties` aponta para a instalação local
`/home/allan/.local/opt/jdk-25`; ajuste esse caminho em outras máquinas.

```sh
./gradlew --version
./gradlew build
./gradlew run
```

O Material 3 tem versão independente do Compose e está fixado na versão estável 1.9.0.
O Koin utiliza a DSL, sem plugin de compilador adicional.

## Funcionalidades

- Multi-conta Google
- Mapeamento flexível de pastas (nuvem ↔ local)
- Sincronização bidirecional e download-only
- Download de arquivos nativos Google (Docs → .docx, Sheets → .xlsx, Slides → .pptx)
- Histórico completo de operações
- Sincronização automática com polling incremental

## Estrutura

```
src/main/kotlin/com/godrivesync/
├── Main.kt              # Entry point
├── App.kt               # Root composable + navegação
├── data/
│   ├── db/              # Exposed + H2: tabelas e repositórios
│   └── models/          # Data classes
├── drive/
│   ├── auth/            # OAuth 2.0 (loopback server)
│   ├── api/             # Google Drive API client
│   └── sync/            # SyncEngine + FileHandler
└── ui/
    ├── theme/           # Material 3
    ├── components/      # Sidebar, ProgressBar
    └── screens/         # Login, Dashboard, Mappings, History, Accounts, Settings
```

## Planejamento

Veja [PLAN.md](PLAN.md) para a especificação completa e [Issues](https://github.com/allanJacques/go-drive-sync/issues) para o backlog de tarefas.

## Status

Scaffold Gradle e janela inicial Compose implementados. As dependências da stack estão
declaradas; sincronização, autenticação e persistência ainda estão em planejamento.