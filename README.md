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
| Exposed (core + JDBC + Java Time) | 1.6.0 |
| H2 | 2.4.240 |
| Flyway (migrações SQL) | 13.12.1 |
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
│   ├── db/              # Flyway + Exposed + H2: migrações, tabelas e repositórios
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

Scaffold Gradle, banco H2 com três tabelas, sete temas Material 3 e navegação lateral implementados. A sidebar
alterna entre Dashboard, Sincronizar, Histórico, Mapeamentos, Contas e Configurações.
As telas exibem estados iniciais; autenticação, sincronização e repositórios para
consultar/gravar os dados do usuário ainda estão em planejamento.

### Banco de dados local

Ao executar `./gradlew run`, o aplicativo inicializa o H2 antes de abrir a janela
e aplica os scripts Flyway em `src/main/resources/db/migration`. A migração V1 cria
`accounts`, `folder_mappings` e `sync_history`; Exposed continua sendo a DSL de dados.
A URL `jdbc:h2:file:./drive-sync-db` gera `drive-sync-db.mv.db` no diretório de
execução (a raiz do projeto ao usar essa tarefa Gradle). Não é preciso instalar
um servidor de banco. Abrir o aplicativo novamente preserva os dados existentes.

O arquivo do banco e eventuais arquivos de diagnóstico `.trace.db` são ignorados
pelo Git. Flyway registra as migrações em `flyway_schema_history`, aplica somente
as pendentes e valida checksums das já executadas. Bancos da primeira implementação
da issue #4 são adotados como V1 após conferência estrutural, preservando seus dados.
Um esquema desconhecido, uma migração com falha ou um banco de versão futura impede
abrir a janela. Alterações futuras devem acrescentar scripts V2, V3 etc.; não editar V1.

Em uma instalação nova, o aplicativo cria tabelas vazias; os campos de tokens não implementam
criptografia. O fluxo futuro de autenticação deverá criptografar ambos os tokens
antes de gravá-los, conforme o PLAN.md.

`./gradlew build` também executa os testes de integração com bancos H2 temporários,
sem usar o arquivo local do aplicativo. Veja [Banco H2, Flyway e Exposed](docs/BANCO-DE-DADOS.md)
para entender as tabelas, os relacionamentos, como adicionar uma coluna e como recuperar uma falha.

### Aparência

Execute `./gradlew run` e abra **Configurações → Aparência**, ou clique no nome do
tema no topo da janela. A troca é imediata e mantém a tela selecionada.

| Família | Escuro | Claro |
|---|---|---|
| Grafite e roxo, painéis sólidos | Obsidiana | Obsidiana Clara |
| Violeta e lavanda, painéis translúcidos | Aurora | Aurora Clara |
| Verde azulado e menta | Maré | Maré Clara |

O sétimo tema, **Tangerina**, usa laranja, pêssego, rosa, ameixa e turquesa, sem
superfícies pretas ou brancas. **Aurora** é o padrão no primeiro uso.

A preferência é salva pelo Java Preferences no perfil do usuário
(`com/godrivesync/appearance`, chave `theme`) e restaurada antes de abrir a janela.
As gravações acontecem fora da thread da interface. Se houver falha ao salvar,
Configurações permite tentar novamente. Uma preferência ausente ou desconhecida
retorna ao tema Aurora.

Todos os temas compartilham as mesmas telas e componentes. A transparência é
interna, sobre o fundo do aplicativo; a janela mantém os controles do sistema.
O fundo usa um gradiente estático com cache, sem desfoque ou animação contínua.

## Leitura e contribuição

- [Compose para quem vem de Java/Swing](docs/COMPOSE-PARA-QUEM-VEM-DE-SWING.md): mapa dos arquivos, estado, layouts, eventos, efeitos e temas.
- [Banco H2, Flyway e Exposed](docs/BANCO-DE-DADOS.md): esquema, migrações versionadas, arquivo local e testes de integração.
- [Como trabalhar com Pull Requests](docs/PULL-REQUESTS.md): commit, push, revisão e merge pelo site.
- [Regras de planejamento, documentação e trabalho](AGENTS.md): apresentar plano e aguardar aprovação antes de implementar; documentação em português acompanha as alterações.
