# Go Drive Sync

Cliente desktop para Google Drive multi-conta com sincronização bidirecional de pastas.

## Stack

| Componente | Versão |
|---|---|
| JDK | Java 25 LTS |
| Kotlin | 2.4.x |
| Compose Multiplatform | 1.12.x (Desktop) |
| Gradle | 8.12+ |
| Banco de dados | H2 (embarcado) |
| ORM | Exposed DSL (JetBrains) |
| DI | Koin |
| Auth | OAuth 2.0 (Google Identity) |

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

Em fase de planejamento. Nenhum código implementado ainda.