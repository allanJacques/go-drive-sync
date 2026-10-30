# Go Drive Sync — Plano do Projeto

> **Status:** Rascunho / Planejamento
> **Última atualização:** 2026-10-30
> **Aviso:** Este documento é um rascunho. Decisões podem ser revistas durante a implementação.

---

## 1. Visão Geral

**Go Drive Sync** é um cliente desktop para Google Drive que sincroniza pastas da nuvem com pastas locais. O aplicativo suporta múltiplas contas Google, mapeamento flexível de pastas (nuvem ↔ local), sincronização bidirecional ou download-only, e mantém um histórico completo de operações.

### Funcionalidades Principais

- **Multi-conta:** Login com uma ou mais contas Google simultaneamente
- **Mapeamento de pastas:** Configurar quais pastas da nuvem correspondem a quais pastas no computador
- **Sincronização bidirecional (↔):** Arquivos binários (PDF, imagens, ZIP, etc.) podem ser baixados e enviados
- **Download-only (↓):** Arquivos nativos Google (Docs, Sheets, Slides) são apenas baixados (convertidos via `files.export`)
- **Sincronização incremental:** Polling via `changes.list` para detectar mudanças sem varrer tudo
- **Histórico:** Registro de todos os downloads e uploads com status, tamanho e timestamp
- **Barra de progresso em tempo real:** Status da sincronização sempre visível

---

## 2. Stack Tecnológica — Versões

| Componente | Versão | Notas |
|---|---|---|
| **JDK** | Java 25 LTS (Eclipse Temurin/Adoptium) | LTS, suporte prolongado |
| **Kotlin** | 2.4.x | Linha estável (Out/2026) |
| **Compose Multiplatform** | 1.12.x | Desktop JVM (Out/2026) |
| **Gradle** | 8.12+ | Kotlin DSL |
| **Build plugin** | `org.jetbrains.kotlin.jvm` + `org.jetbrains.compose` | — |
| **Exposed** | 0.58.x | DSL SQL da JetBrains |
| **H2 Database** | 2.3.x | Banco embarcado, 100% Java (sem binário nativo) |
| **Google API Client** | 2.7.x | Cliente HTTP Java da Google |
| **Google Drive API v3** | v3-rev-latest | REST API |
| **Google OAuth** | 1.27.x | Biblioteca de autenticação |
| **Koin** | 3.6+ ou 4.0+ | Injeção de dependência |
| **kotlinx-coroutines** | 1.9.x | Corrotinas |
| **kotlinx-serialization** | 1.7.x | Serialização JSON (configurações) |

---

## 3. Tabelas do Banco de Dados (Exposed + H2)

### 3.1 `accounts`

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | INT (PK, AUTO_INCREMENT) | Identificador único |
| `email` | VARCHAR(255) UNIQUE NOT NULL | Email da conta Google |
| `access_token` | TEXT NOT NULL | Token OAuth 2.0 (criptografado) |
| `refresh_token` | TEXT NOT NULL | Refresh token para renovação |
| `token_expiry` | DATETIME NOT NULL | Expiração do access_token |
| `is_default` | BOOLEAN DEFAULT FALSE | Conta padrão ao abrir o app |
| `last_full_sync` | DATETIME NULLABLE | Última sincronização completa |
| `created_at` | DATETIME DEFAULT NOW | Data de criação do registro |

### 3.2 `folder_mappings`

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | INT (PK, AUTO_INCREMENT) | Identificador único |
| `account_id` | INT FK → accounts.id | Conta proprietária |
| `drive_folder_id` | VARCHAR(100) NOT NULL | ID da pasta no Google Drive |
| `drive_folder_name` | VARCHAR(255) | Nome visível da pasta na nuvem |
| `local_folder_path` | TEXT NOT NULL | Caminho absoluto no computador |
| `sync_direction` | VARCHAR(20) DEFAULT 'BIDIRECTIONAL' | `BIDIRECTIONAL` ou `DOWNLOAD_ONLY` |
| `last_sync` | DATETIME NULLABLE | Última sincronização deste mapeamento |
| `enabled` | BOOLEAN DEFAULT TRUE | Pausar/ativar mapeamento |

### 3.3 `sync_history`

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | INT (PK, AUTO_INCREMENT) | Identificador único |
| `account_id` | INT FK → accounts.id | Conta da operação |
| `mapping_id` | INT FK → folder_mappings.id NULLABLE | Mapeamento relacionado |
| `drive_file_id` | VARCHAR(100) | ID do arquivo no Drive |
| `file_name` | VARCHAR(255) | Nome do arquivo |
| `file_path` | TEXT | Caminho local do arquivo |
| `direction` | VARCHAR(10) NOT NULL | `DOWNLOAD` ou `UPLOAD` |
| `status` | VARCHAR(10) NOT NULL | `SUCCESS`, `FAILED`, `SKIPPED` |
| `file_size` | BIGINT | Tamanho em bytes |
| `timestamp` | DATETIME DEFAULT NOW | Momento da operação |
| `error_message` | TEXT NULLABLE | Motivo da falha (se houver) |

---

## 4. Layout e Telas

### Navegação

```
┌──────────────┬──────────────────────────────────────┐
│              │                                      │
│  Sidebar     │  Conteúdo da tela selecionada        │
│              │                                      │
│  📊 Dashboard│                                      │
│  🔄 Sincr.   │                                      │
│  📋 Histórico│                                      │
│  ⚙️ Mapeam.  │                                      │
│  👥 Contas   │                                      │
│  ⚙️ Config.  │                                      │
│              │                                      │
├──────────────┴──────────────────────────────────────┤
│  Barra inferior fixa: status da sincronização       │
└─────────────────────────────────────────────────────┘
```

### 4.1 Tela de Login / Seleção de Conta

**Propósito:** Autenticação e escolha de conta ativa.

```
┌──────────────────────────────────────────┐
│  🔵 allan@gmail.com          [Entrar]    │
│  🔵 trabalho@empresa.com     [Entrar]    │
│                                          │
│  [+ Adicionar conta Google]              │
└──────────────────────────────────────────┘
```

- Primeiro uso: apenas o botão "+ Adicionar conta Google"
- Multi-conta: lista cards com avatar e email, botão "Entrar" em cada
- Fluxo OAuth: loopback server (localhost) → abre navegador → captura código → troca por tokens → salva no banco

### 4.2 Tela de Mapeamento de Pastas

**Propósito:** Configurar quais pastas da nuvem correspondem a quais pastas locais.

```
┌──────────────────────────────────────────┐
│  Mapeamentos — allan@gmail.com            │
│                                          │
│  ☁️ Meu Drive/Nuvem → ~/nuvem            │
│     ↕ Bidirecional    [✕] [Sincronizar]  │
│                                          │
│  ☁️ Meu Drive/Fotos → ~/fotos            │
│     ↓ Download only   [✕] [Sincronizar]  │
│                                          │
│  [+ Novo mapeamento]                     │
└──────────────────────────────────────────┘
```

- Cada mapeamento mostra: nome da pasta na nuvem → caminho local
- Ícone de direção: ↕ (bidirecional) ou ↓ (download-only)
- Botões: remover (✕), sincronizar agora
- Modal de criação: seletor de pasta na nuvem (navegar estrutura do Drive) + seletor de pasta local (diálogo nativo do SO) + escolha de direção

### 4.3 Dashboard

**Propósito:** Visão geral do estado da sincronização.

```
┌──────────────────────────────────────────┐
│  📦 Total sincronizado: 4.2 GB           │
│  📁 2 mapeamentos configurados           │
│  🟢 Última sincronização: 5 min atrás    │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │ ☁️ Nuvem → ~/nuvem                 │  │
│  │ 🟢 OK — 12:05                     │  │
│  ├────────────────────────────────────┤  │
│  │ ☁️ Fotos → ~/fotos                 │  │
│  │ 🔴 Erro — 3 arquivos pendentes     │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

### 4.4 Tela de Sincronização (Progresso)

**Propósito:** Mostrar o que está sendo sincronizado no momento.

```
┌──────────────────────────────────────────┐
│  🔄 Sincronizando Nuvem...               │
│                                          │
│  ████████████░░░░░░░  67%               │
│  relatorio.pdf ✓                         │
│  planilha.xlsx ⏳ 2.3 MB/s              │
│  fotos/ferias.jpg ⏳                     │
└──────────────────────────────────────────┘
```

### 4.5 Histórico

**Propósito:** Mostrar todas as operações de download/upload realizadas.

```
┌──────────────────────────────────────────┐
│  Filtro: [Conta ▼] [Tipo ▼]             │
│                                          │
│  ↓ relatorio.pdf                2.1 MB   │
│     12:05 · Sucesso                      │
│  ↑ foto_perfil.jpg              0.5 MB   │
│     11:58 · Sucesso                      │
│  ↓ docs/apresentacao.pptx       5.0 MB   │
│     11:45 · Falhou (erro de rede)        │
└──────────────────────────────────────────┘
```

- Filtros por: conta, direção (download/upload), status (sucesso/falha/pulado)
- Cada linha: ícone de direção, nome do arquivo, tamanho, horário, status
- Scroll infinito ou paginação conforme volume

### 4.6 Gerenciamento de Contas

**Propósito:** Gerenciar múltiplas contas Google.

```
┌──────────────────────────────────────────┐
│  🔵 allan@gmail.com           [Padrão]   │
│     Última sinc: 12:05                   │
│     [Sair da conta]                      │
│  ────────────────────────────────────    │
│  🔵 trabalho@empresa.com                 │
│     Última sinc: ontem                   │
│     [Sair da conta]                      │
│  ────────────────────────────────────    │
│  [+ Adicionar conta]                     │
└──────────────────────────────────────────┘
```

- Listar contas com email, avatar, status da última sincronização
- Marcar/desmarcar como conta padrão
- Remover conta (logout)
- Adicionar nova conta (abre fluxo OAuth)

### 4.7 Configurações

**Propósito:** Preferências gerais do aplicativo.

```
┌──────────────────────────────────────────┐
│  Sincronização automática:               │
│  Intervalo: [5 min ▼]                    │
│  [✓] Iniciar com o sistema               │
│                                          │
│  Notificações:                           │
│  [✓] Ao concluir sincronização           │
│  [ ] Ao falhar                           │
│                                          │
│  Limpeza:                                │
│  Manter histórico por: [30 dias ▼]       │
│  [Limpar histórico agora]                │
└──────────────────────────────────────────┘
```

---

## 5. Arquitetura

### 5.1 Estrutura de Diretórios

```
go-drive-sync/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── PLAN.md
├── src/
│   └── main/
│       ├── kotlin/com/godrivesync/
│       │   ├── Main.kt
│       │   ├── App.kt
│       │   ├── data/
│       │   │   ├── db/
│       │   │   │   ├── Database.kt
│       │   │   │   ├── tables/
│       │   │   │   │   ├── AccountsTable.kt
│       │   │   │   │   ├── FolderMappingsTable.kt
│       │   │   │   │   └── SyncHistoryTable.kt
│       │   │   │   └── repositories/
│       │   │   │       ├── AccountRepository.kt
│       │   │   │       ├── MappingRepository.kt
│       │   │   │       └── HistoryRepository.kt
│       │   │   └── models/
│       │   │       ├── Account.kt
│       │   │       ├── FolderMapping.kt
│       │   │       ├── SyncDirection.kt
│       │   │       └── SyncHistoryEntry.kt
│       │   ├── drive/
│       │   │   ├── auth/
│       │   │   │   └── OAuthManager.kt
│       │   │   ├── api/
│       │   │   │   └── DriveApiClient.kt
│       │   │   └── sync/
│       │   │       ├── SyncEngine.kt
│       │   │       └── FileHandler.kt
│       │   └── ui/
│       │       ├── theme/
│       │       │   └── Theme.kt
│       │       ├── components/
│       │       │   ├── Sidebar.kt
│       │       │   ├── ProgressBar.kt
│       │       │   └── common/
│       │       └── screens/
│       │           ├── login/
│       │           │   ├── LoginScreen.kt
│       │           │   └── LoginViewModel.kt
│       │           ├── dashboard/
│       │           │   ├── DashboardScreen.kt
│       │           │   └── DashboardViewModel.kt
│       │           ├── mappings/
│       │           │   ├── MappingsScreen.kt
│       │           │   └── MappingsViewModel.kt
│       │           ├── sync/
│       │           │   ├── SyncScreen.kt
│       │           │   └── SyncViewModel.kt
│       │           ├── history/
│       │           │   ├── HistoryScreen.kt
│       │           │   └── HistoryViewModel.kt
│       │           ├── accounts/
│       │           │   ├── AccountsScreen.kt
│       │           │   └── AccountsViewModel.kt
│       │           └── settings/
│       │               ├── SettingsScreen.kt
│       │               └── SettingsViewModel.kt
│       └── resources/
└── gradle/
    └── wrapper/
```

### 5.2 Fluxo de Sincronização (SyncEngine)

```
SyncEngine (corrotina principal)
  │
  ├── Para cada conta:
  │     │
  │     ├── Para cada mapeamento (pasta nuvem → pasta local):
  │     │     │
  │     │     ├── 1. Listar arquivos remotos (files.list + folderId query)
  │     │     ├── 2. Listar arquivos locais (java.io.File.walk)
  │     │     ├── 3. Comparar (nome + modifiedTime + tamanho):
  │     │     │     ├── remote mais novo → download
  │     │     │     ├── local mais novo → upload (se bidirecional)
  │     │     │     ├── só no remote → download
  │     │     │     └── só no local → upload (se bidirecional)
  │     │     ├── 4. Executar operações em paralelo (corrotinas)
  │     │     └── 5. Registrar no sync_history
  │     │
  │     └── Polling: changes.list (usando pageToken incremental)
  │
  └── Aguardar intervalo configurado → repetir
```

### 5.3 Lógica de Download por Tipo de Arquivo

| Tipo | Método | Comportamento |
|---|---|---|
| Arquivo binário (.pdf, .jpg, .zip, etc.) | `files.get?alt=media` | Download normal, byte a byte |
| Google Docs | `files.export?mimeType=application/vnd.openxmlformats-officedocument.wordprocessingml.document` | Converte para .docx |
| Google Sheets | `files.export?mimeType=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Converte para .xlsx |
| Google Slides | `files.export?mimeType=application/vnd.openxmlformats-officedocument.presentationml.presentation` | Converte para .pptx |

### 5.4 Regra de Direção por Tipo

| Tipo de arquivo | Download (nuvem → local) | Upload (local → nuvem) |
|---|---|---|
| Arquivos binários | ✅ | ✅ (se bidirecional) |
| Arquivos nativos Google | ✅ (via export) | ❌ (não suportado — converteria para arquivo comum, perdendo vínculo com o original) |

---

## 6. Endpoints da Google Drive API Utilizados

| Funcionalidade | Endpoint | Descrição |
|---|---|---|
| Listar arquivos | `GET /files?q='folderId' in parents` | Lista conteúdo de uma pasta |
| Baixar arquivo | `GET /files/{id}?alt=media` | Download de arquivo binário |
| Exportar arquivo nativo | `GET /files/{id}/export?mimeType=...` | Converte Docs/Sheets/Slides |
| Enviar arquivo | `POST /files` com upload resumable | Upload com retomada |
| Detectar mudanças | `GET /changes?pageToken=...` | Sincronização incremental |
| Informações da conta | `GET /about?fields=storageQuota` | Espaço usado/disponível |
| Criar pasta | `POST /files` com mimeType folder | Para uploads que precisam de pasta nova |

### Por que polling e não webhook?

O endpoint `changes.watch` exige uma URL pública com HTTPS (webhook) para receber notificações push. Um app desktop não possui URL pública. A alternativa recomendada pela Google é **polling** via `changes.list` a cada N minutos. Com 3 contas e intervalo de 5 minutos, são ~864 chamadas/dia — muito abaixo do limite de 12.000/100s.

---

## 7. Funcionalidades Fora do Escopo (MVP)

- Suporte a Shared Drives (apenas My Drive no MVP)
- Sincronização seletiva por tipo/extensão de arquivo
- Agendamento por horário específico (apenas intervalo fixo)
- Criptografia de arquivos locais
- Suporte mobile (Android/iOS)

---

## 8. Avisos de Implementação

1. **Os tokens OAuth devem ser criptografados** em disco (access_token + refresh_token). Não salvar em plain text.
2. **Conflitos de sincronização:** Se um arquivo for modificado nos dois lados entre sincronizações, a versão do Drive prevalece, e o arquivo local é renomeado com sufixo `_conflict`.
3. **Arquivos deletados:** Se um arquivo existe localmente mas não está mais no Drive, considerar: deletar local (padrão) ou mover para uma pasta `_removed`.
4. **Arquivos grandes:** Usar upload resumable (chunked) para arquivos > 5 MB.
5. **Throttling:** Respeitar rate limits da API com backoff exponencial em caso de 403/429.
6. **Primeira sincronização:** Será sempre completa (full scan), pois não há pageToken ainda.

---

## 9. Histórico de Decisões

| Data | Decisão | Motivo |
|---|---|---|
| 2026-10-30 | Banco H2 (não SQLite) | 100% Java, sem binário nativo, facilita empacotamento cross-platform |
| 2026-10-30 | Exposed DSL (não DAO) | Mais controle sobre SQL, menos overhead que ORM completo |
| 2026-10-30 | Compose Multiplatform (não JavaFX) | Nativo Kotlin, visual moderno, futuro do ecossistema JetBrains |
| 2026-10-30 | Java 25 LTS | LTS = estabilidade e suporte prolongado |
| 2026-10-30 | Polling (não webhook) | App desktop não tem URL pública para receber push notifications |
| 2026-10-30 | Nome: Go Drive Sync | Padrão "Go Drive X", curto e descritivo |