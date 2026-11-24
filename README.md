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
Os modelos e repositórios de contas, mapeamentos e histórico já permitem consultar
e gravar no H2, com transações e testes de integração. A tela Contas permite autorização
pelo navegador, recuperação das contas salvas e reconexão quando necessária.
O serviço da issue #8 identifica a conta, persiste tokens criptografados e recupera
credenciais após reabrir o banco. A issue #9 acrescenta renovação automática, tratamento
de HTTP 401. A issue #10 restaura todas as contas automaticamente ao abrir o aplicativo,
sem exigir seleção ou novo login. A issue #11 acrescenta última sincronização, conta padrão
e saída com confirmação. As issues #12 e #13 acrescentam um cliente Drive por conta,
com renovação automática e listagem paginada dos arquivos e subpastas de uma pasta.
Na issue #14, o `FileHandler` passa a baixar um arquivo binário por ID e a informar o progresso em bytes.
Sincronização de arquivos e gerenciamento de mapeamentos
continuam nas atividades seguintes.

### Listagem de arquivos e pastas do Drive

`DriveApiClient.listFiles(folderId)` reúne todas as páginas e devolve os filhos diretos
da pasta, excluindo a lixeira. Use `"root"` para listar o primeiro nível do Meu Drive.
Cada item inclui nome, ID, tipo MIME e os metadados disponíveis de tamanho, alteração
e pastas pai. As propriedades `isFolder` e `isGoogleNative` identificam o tipo do item.

O diagnóstico usa a consulta básica de uma página. O comando `./gradlew driveApiCheck`
lista os IDs locais; `./gradlew driveApiCheck --args='1'` verifica a listagem para o ID
escolhido. Feche o aplicativo para liberar o H2 antes de executar o diagnóstico.
Ele pode renovar tokens, mas não transfere arquivos nem abre navegador. Veja
[Cliente Drive](docs/CLIENTE-DRIVE.md) para contrato, paginação, falhas e testes.

### Download de um arquivo binário

Em uma corrotina, use `FileHandler(client).downloadFile(fileId, destino, onProgress)`.
O destino é um `Path`. O callback recebe os bytes gravados e o tamanho total, quando
disponível. Um arquivo local existente só é substituído quando a transferência termina
e a cópia passa pela verificação de tamanho. Veja o [exemplo de download e as regras de repetição](docs/CLIENTE-DRIVE.md#baixar-um-arquivo-binário).

### Conexão de contas (issue #10)

- Sem contas salvas, abra **Adicionar conta Google** e conclua o consentimento no navegador.
- Com contas salvas, o aplicativo abre o Dashboard e verifica o acesso de cada conta em segundo plano.
- **Contas** mostra e-mail, avatar (ou inicial) e status; **Reconectar** aparece quando a autorização precisa ser refeita.
- **Ver mapeamentos** muda somente o contexto visual; não desativa outras contas.
- Após adicionar, o app encaminha para Mapeamentos se não houver pastas configuradas, ou Dashboard se já houver.

As três contas de um usuário permanecem salvas após encerrar/reabrir. O motor futuro
retomará os mapeamentos habilitados de todas elas automaticamente (#23/#24).
Nesta versão ainda não há transferências nem execução na bandeja; fechar a janela encerra o app.
Veja [Conexão de contas](docs/LOGIN-E-CONTAS.md) para estados, testes e roteiro de validação manual.

### Gerenciamento de contas (issue #11)

- **Contas** usa cartões compactos com avatar, e-mail, acesso, última sincronização completa
  e selo de padrão. As ações têm ícones e botões com contorno, distribuídos conforme a largura.
  Sem data salva, mostra **Ainda não sincronizada**; esta versão ainda não sincroniza arquivos.
- **Definir como padrão** salva uma preferência visual única, usada como contexto inicial de
  Mapeamentos. Uma escolha explícita de contexto prevalece durante a janela; todas as contas
  continuam conectadas. Sem padrão, usa a primeira conta disponível.
- **Sair da conta** abre uma confirmação em linguagem simples, com o e-mail em destaque
  e botões Cancelar/Sair da conta. A operação remove conta, tokens,
  mapeamentos e histórico associados numa transação. Cancelar mantém todos os dados.
  Arquivos locais e do Drive são preservados. Nenhuma conta substituta vira padrão automaticamente.
- A saída é local; revogar a autorização no Google será tratado na issue #42.
  Após remover a última conta, a tela oferece adicionar uma conta novamente.

Detalhes de estado, concorrência e validação estão em [Contas](docs/LOGIN-E-CONTAS.md).

### Preparação do Google Cloud (issue #6)

O [guia de Google Cloud e OAuth Desktop](docs/GOOGLE-CLOUD-OAUTH.md) explica como
habilitar a Drive API, configurar o consentimento em modo de teste e obter a
credencial do tipo **Desktop app**. Em 13/11/2026, o mantenedor confirmou a conclusão
do roteiro no Console. O JSON local foi conferido quanto à estrutura Desktop,
campos obrigatórios, permissões e exclusão do Git.

Salve o JSON baixado como `credentials.json` na raiz local do repositório, ao lado
de `gradlew`, fora de `src/main/resources`. O arquivo já é ignorado pelo Git.
O OAuthManager da issue #7 lê esse arquivo ao iniciar uma autorização. A execução
normal da interface chama o fluxo pelos botões Adicionar/Reconectar. O guia esclarece o callback local
e os limites do modo de teste.

### Autorização OAuth (issue #7)

Para validar o fluxo com sua conta de teste, execute na raiz do projeto:

```sh
./gradlew oauthLogin
# Opcional: arquivo externo em outro caminho.
./gradlew oauthLogin --args='/caminho/credentials.json'
```

O navegador padrão abre a seleção da conta e o consentimento para o escopo `drive`.
Conclua em até cinco minutos e confira o resultado no terminal. O diagnóstico
confirma recebimento dos dois tokens e informa apenas a expiração; não exibe tokens,
não os salva e não inicializa o banco. Autorizar concede acesso ao aplicativo no Google,
mesmo que o diagnóstico descarte os tokens ao encerrar.

O retorno no navegador exibe uma página com o logo e as cores Aurora. **Resposta
recebida** confirma o callback; o resultado final da troca de tokens aparece no
terminal. O [diagrama do fluxo OAuth](docs/GOOGLE-CLOUD-OAUTH.md#quem-faz-cada-chamada-diagrama-do-fluxo-oauth)
explica as chamadas entre aplicativo, navegador, Google e servidor local.

`OAuthManager().authorize()` é uma função suspensa que devolve `OAuthCredentials`
(access token, refresh token e expiração UTC) em memória. O fluxo usa PKCE S256,
`state`, acesso offline, servidor em `127.0.0.1` com porta livre e encerramento em
sucesso, falha, cancelamento ou timeout. A ausência de refresh token é um erro explícito;
o manager não reutiliza credenciais de autorizações anteriores. O serviço da issue #8
criptografa os tokens antes de passá-los ao repositório.

Os testes OAuth usam JSON fictício, callbacks HTTP locais e endpoint de tokens simulado.
Não exigem credenciais reais nem validam a configuração do Google Cloud. Consulte
o [guia do fluxo e diagnóstico](docs/GOOGLE-CLOUD-OAUTH.md#oauthmanager-issue-7).

### Contas e tokens criptografados (issue #8)

```sh
./gradlew oauthAccountLogin
# Opcional: JSON Desktop fora do projeto.
./gradlew oauthAccountLogin --args='/caminho/credentials.json'
```

Este diagnóstico abre o H2, valida/cria a chave local, solicita autorização no navegador,
identifica o e-mail pelo Drive e salva os dois tokens com AES-256-GCM. Em seguida,
recupera as credenciais em memória e lista e-mail, preferência e expiração. Não imprime
tokens. Uma nova autorização do mesmo e-mail atualiza a conta preservando seus vínculos.
O diagnóstico `oauthLogin` continua sem persistência.

A chave fica em `~/.go-drive-sync/token-key-v1`, fora do repositório e do H2, com acesso
restrito ao proprietário (diretório 700/arquivo 600 em POSIX; ACL exclusiva onde disponível).
Ela **não é** o JSON OAuth. Faça backup protegido da chave e do banco: sem a chave original,
as credenciais não podem ser recuperadas. A proteção não cobre alguém com acesso à chave
e ao banco, inclusive processos executados como seu usuário.

`AccountAuthorizationService` oferece `authorizeAndSave()`, `listAccounts()` sem tokens
e `loadCredentials(id)` para o futuro cliente da API. A expiração é persistida em UTC;
`SavedAccount.isExpired()` informa o vencimento, sem renovar automaticamente. Detalhes,
falhas e roteiro manual estão no [guia de armazenamento seguro](docs/TOKENS-E-CONTAS.md).

### Renovação automática (issue #9)

`AccountCredentialManager.execute(id) { initializer -> ... }` executa chamadas do SDK
em `Dispatchers.IO`, renova tokens vencidos ou próximos do vencimento e trata HTTP 401
com uma repetição. A renovação usa `Credential` da biblioteca oficial e grava tokens
criptografados/expiração UTC antes de usar o novo acesso. Compartilhe um manager por
serviço/banco. O cliente Drive usa esse inicializador a cada página e o manager
serializa as requisições da mesma conta.

```sh
# Lista IDs e expirações das contas já salvas.
./gradlew oauthRefreshCheck
# Consulta mínima de leitura do Drive, renovando se necessário.
./gradlew oauthRefreshCheck --args='1'
# Permite abrir OAuth caso o refresh token tenha sido revogado/expirado.
./gradlew oauthRefreshCheck --args='1 --reauthorize'
# JSON externo opcional após o ID.
./gradlew oauthRefreshCheck --args='1 "/caminho com espaços/credentials.json" --reauthorize'
```

Substitua `1` pelo ID listado. O diagnóstico usa o banco/chave locais e pode atualizar
as credenciais. Sem `--reauthorize`, nunca abre o navegador. Com a opção, só abre
quando a resposta exige novo login; selecionar outro e-mail é recusado antes de salvar.
Falhas temporárias não exigem novo consentimento. Nenhum token ou corpo HTTP é impresso.
O roteiro e os limites estão em [Renovação e recuperação](docs/RENOVACAO-DE-TOKENS.md).

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
abrir a janela principal. A inicialização agora mostra um diálogo de erro com botão OK:
se o H2 estiver ocupado, orienta fechar outra instância ou desconectar ferramentas como
o DBeaver; outras falhas recebem uma mensagem de inicialização distinta. Após confirmar
ou fechar o aviso, o processo encerra com status 1. Sem ambiente gráfico, a mesma mensagem
é apresentada no terminal. O diálogo usa Swing, antes de carregar os temas do Compose.

O banco continua em modo embarcado. A avaliação de acesso simultâneo e da política de
múltiplas instâncias está na [issue #47](https://github.com/allanJacques/go-drive-sync/issues/47),
na milestone M8. Alterações futuras de esquema devem acrescentar scripts V2, V3 etc.; não editar V1.

Em uma instalação nova, o aplicativo cria tabelas vazias. As colunas TEXT não criptografam
por conta própria: o `AccountAuthorizationService` cifra ambos os tokens antes de gravá-los.

`./gradlew build` também executa os testes de integração com bancos H2 temporários,
sem usar o arquivo local do aplicativo. Veja [Banco H2, Flyway e Exposed](docs/BANCO-DE-DADOS.md)
para entender as tabelas, os relacionamentos, como adicionar uma coluna e como recuperar uma falha.

### Repositórios de dados

Crie `AccountRepository(database)`, `MappingRepository(database)` e
`HistoryRepository(database)` com o retorno de `initializeDatabase()`. Eles usam
transações Exposed e devolvem modelos Kotlin imutáveis com IDs e datas preenchidos.
As operações são bloqueantes; chamadas posteriores à abertura da janela devem usar
`Dispatchers.IO`, fora da thread da interface.

- Contas: criação/atualização por ID, busca por e-mail, listagem, escolha da padrão e exclusão.
- Mapeamentos: criação, consulta por conta, atualização, pausa/ativação e exclusão.
- Histórico: inserção, filtros por conta/direção e remoção estritamente anterior a uma data.

As exclusões respeitam os relacionamentos do banco; dependentes não são apagados
automaticamente pelo CRUD. A saída confirmada usa `deleteWithRelatedData`, uma operação
explícita que remove histórico, mapeamentos e conta na mesma transação. O CRUD recebe tokens já criptografados pelo serviço de autenticação;
não cifra valores por conta própria. Consulte os [contratos e exemplos dos repositórios](docs/BANCO-DE-DADOS.md#modelos-e-repositórios-issue-5)
para entender retornos, erros, concorrência e a distinção entre as direções de mapeamento e transferência.

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
- [Google Cloud e OAuth Desktop](docs/GOOGLE-CLOUD-OAUTH.md): configuração manual, escopo, callback local e localização das credenciais.
- [Como trabalhar com Pull Requests](docs/PULL-REQUESTS.md): commit, push, revisão e merge pelo site.
- [Regras de planejamento, documentação e trabalho](AGENTS.md): apresentar plano e aguardar aprovação antes de implementar; documentação em português acompanha as alterações.
