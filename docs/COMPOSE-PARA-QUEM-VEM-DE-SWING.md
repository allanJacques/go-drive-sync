# Entendendo este projeto vindo de Java/Swing

Este guia acompanha a interface da issue #3, a inicialização do banco da issue #4
e os modelos/repositórios da issue #5. O fluxo OAuth da issue #7 é independente
das telas e tem diagnóstico de console próprio. A issue #8 acrescenta um serviço
de contas com persistência criptografada, também independente da UI. A issue #9
acrescenta o manager de credenciais renováveis e a recuperação de autorização por ID.
A issue #10 conecta esses serviços às telas e restaura todas as contas ao abrir.
Leia os comentários KDoc acima das
funções no IntelliJ; eles também aparecem na documentação rápida do editor.

## A principal mudança

Em Swing, você normalmente cria componentes, guarda referências e chama setters:
`label.setText(...)`, `panel.add(...)`, `revalidate()` e `repaint()`.

Em Compose, uma função descreve como a tela deve estar **para o estado atual**.
Quando um estado observado muda, o Compose pode executar novamente as partes que
precisam atualizar sua descrição. Isso se chama **recomposição**. Não significa
recriar a janela inteira nem redesenhar tudo a cada alteração.

Não coloque gravações, downloads ou carregamento de arquivos diretamente no corpo
de uma função composable: esse corpo pode executar várias vezes. Neste projeto,
Main usa um efeito para persistir a aparência fora da thread da interface.

## Comparação de conceitos

| Java/Swing | Neste projeto Compose | Diferença importante |
|---|---|---|
| `JFrame` | `application` e `Window`, em Main.kt | O ciclo de vida é controlado pelo Compose Desktop. |
| `JPanel` e layout manager | `Row`, `Column`, `Box`, `AppPanel` | Funções descrevem uma árvore; não retornam JPanels. |
| `JLabel` / `JButton` | `Text` / `Button` / `TextButton` | Propriedades são parâmetros, vindos do estado. |
| `ActionListener` | Callback `onClick = { ... }` | A lambda comunica uma intenção ao dono do estado. |
| `CardLayout` | `when(destination)` em DestinationScreen | O ramo anterior sai da composição; seu estado local não é automaticamente preservado. |
| `JScrollPane` | `verticalScroll`, `ScrollState`, `VerticalScrollbar` | Conteúdo e barra compartilham o estado de rolagem. |
| `paintComponent(Graphics)` | `Canvas` / `drawWithCache` | Canvas trabalha com coordenadas em pixels; a tela usa dp. |
| `UIManager` / Look and Feel | `MaterialTheme` / `CompositionLocal` | O tema vale para a subárvore envolvida, sem configurar cada filho manualmente. |
| `SwingWorker` | Corrotina com `withContext(Dispatchers.IO)` | A operação bloqueante sai da thread da UI; corrotina sozinha não implica outra thread. |
| `java.util.prefs.Preferences` | `AppearancePreferences` | É a mesma API Java, encapsulada para este aplicativo. |

## Ordem sugerida de leitura

1. [Main.kt](../src/main/kotlin/com/godrivesync/Main.kt): inicializa o H2, lê a
   preferência, guarda o tema escolhido e abre a janela.
2. [App.kt](../src/main/kotlin/com/godrivesync/App.kt): organiza a tela e mantém o
   destino selecionado. Repassa estado e callbacks aos filhos.
3. [AppDestination.kt](../src/main/kotlin/com/godrivesync/ui/navigation/AppDestination.kt)
   e [Sidebar.kt](../src/main/kotlin/com/godrivesync/ui/components/Sidebar.kt): catálogo
   de telas e menu que comunica os cliques ao App.
4. [DestinationScreen.kt](../src/main/kotlin/com/godrivesync/ui/screens/DestinationScreen.kt):
   escolhe o conteúdo. [DashboardScreen.kt](../src/main/kotlin/com/godrivesync/ui/screens/DashboardScreen.kt)
   monta os cartões e atalhos; os indicadores ainda são estáticos.
5. [SettingsScreen.kt](../src/main/kotlin/com/godrivesync/ui/screens/SettingsScreen.kt):
   galeria dos sete temas, seleção e mensagem de falha ao salvar.
6. [Theme.kt](../src/main/kotlin/com/godrivesync/ui/theme/Theme.kt),
   [ThemePalettes.kt](../src/main/kotlin/com/godrivesync/ui/theme/ThemePalettes.kt) e
   [AppStyle.kt](../src/main/kotlin/com/godrivesync/ui/theme/AppStyle.kt): fornecimento
   do tema, definições de cores e identificadores persistidos.
7. [Panels.kt](../src/main/kotlin/com/godrivesync/ui/components/Panels.kt) e
   [AppIcons.kt](../src/main/kotlin/com/godrivesync/ui/components/AppIcons.kt): componentes
   reutilizáveis e desenhos vetoriais.
8. [AppearancePreferences.kt](../src/main/kotlin/com/godrivesync/data/preferences/AppearancePreferences.kt):
   leitura e gravação no perfil do usuário.
9. [Database.kt](../src/main/kotlin/com/godrivesync/data/db/Database.kt): configura
   a conexão H2 e aplica as migrações Flyway antes da janela. O [guia do banco](BANCO-DE-DADOS.md)
   explica a DSL do Exposed, a adoção de bancos antigos e as migrações com analogias a JDBC.
10. `data/models/` e `data/db/repositories/`: DTOs imutáveis e acesso transacional
    aos dados. O [contrato dos repositórios](BANCO-DE-DADOS.md#modelos-e-repositórios-issue-5)
    explica retornos, atualizações, filtros, exclusões e limites de concorrência.

## Kotlin que aparece no código

- `val`: referência somente para leitura; `var`: permite atribuição.
- `data class`: classe de dados com operações como `copy` e comparação geradas.
- `object`: instância única, usada no catálogo de ícones.
- `enum class`: conjunto fechado de opções, como telas e temas.
- `String?`: pode conter null. `?:` fornece um valor alternativo; `?.` acessa somente
  quando o objeto não é null.
- `() -> Unit`: função sem argumentos e sem resultado útil, semelhante a um callback
  `void`. `(AppStyle) -> Unit` recebe um tema.
- `{ style = it }`: lambda; `it` é o nome implícito do único argumento.
- `content: @Composable ColumnScope.() -> Unit`: função que descreve filhos e recebe
  o escopo de uma Column, permitindo funções como `Modifier.weight`.
- Parâmetros nomeados, como `selected = true`, evitam depender da posição.
- `by`: delegação. Com estado Compose permite escrever `style = novoTema` em vez
  de acessar explicitamente `state.value`.
- `use`: fecha um recurso, como try-with-resources em Java.

Nos modelos do banco, `id: Int? = null` representa um registro novo. O objeto
retornado por `save` tem o ID gerado: guarde esse retorno para editar depois.
`account.copy(isDefault = true)` cria outro DTO; apenas `AccountRepository.save`
persiste a alteração. `MappingRepository.save` cria, enquanto `update` edita.
`val` e `data class` não tornam esses objetos observáveis pelo Compose: o ViewModel
publica os resultados em StateFlow e a tela usa collectAsState para observá-los.

Os repositórios lembram classes Java de acesso a dados, encapsulando JDBC com a
DSL Exposed. São bloqueantes e devem ser chamados em `Dispatchers.IO` após abrir
a janela. As gravações de contas já delimitam a própria transação e recusam uma
transação externa, para manter a proteção da conta padrão até o commit. Não chame
persistência no corpo de um composable: uma recomposição poderia repetir a gravação.

## Estado, memória e persistência são coisas diferentes

`mutableStateOf` cria um valor observável. Quem lê esse valor durante a composição
pode ser atualizado quando ele mudar. `remember` mantém o valor entre recomposições
na mesma posição da árvore. Ele não grava no disco e não garante sobrevivência
quando o componente sai da composição.

O tema pertence a Main; o destino pertence a App; a posição de rolagem pertence ao
conteúdo da tela. Os filhos recebem os valores e callbacks. Essa organização é
chamada **elevação do estado** (state hoisting): existe uma fonte de verdade para
cada escolha, evitando seleções divergentes entre menu e conteúdo.

Fluxo de uma escolha de tema:

```text
Clique no cartão em SettingsScreen
  → onStyleChange(novoTema)
  → Main altera style
  → GoDriveSyncTheme fornece as novas cores
  → os componentes que dependem delas são atualizados
  → snapshotFlow observa a mudança
  → gravação sequencial em Dispatchers.IO
  → Preferences.put + flush
```

O coletor pode agrupar alterações muito rápidas; ele não é um histórico de todos os
cliques. Uma falha de gravação mantém o tema visual e mostra a opção de tentar
novamente. A leitura seguinte usa Aurora se não houver uma preferência válida.
Não é uma garantia transacional contra encerramento abrupto do processo durante a gravação.

## Efeitos e ciclo de vida

A inicialização do banco ocorre em `main`, antes de `application`. Essa sequência
bloqueia apenas a thread de entrada enquanto a UI ainda não existe; uma recomposição
não executa migrações novamente. Se a validação/migração falhar, o erro é propagado
e a janela principal não abre. A entrada desktop mostra um `JOptionPane` antes de encerrar;
consultas após a janela abrir saem da thread da interface,
usando `Dispatchers.IO`, como a leitura de contas e a gravação das preferências.

`LaunchedEffect(Unit)` inicia uma corrotina vinculada àquela composição. Como a chave
é constante, uma recomposição comum não reinicia o efeito. Ao sair da composição,
o efeito é cancelado. Por isso o código relança `CancellationException`, em vez de
tratá-la como erro de armazenamento.

`snapshotFlow { style to saveRevision }` transforma as leituras desses estados em
um fluxo. O contador permite repetir a gravação sem escolher outro tema. `collect`
aguarda cada gravação terminar, evitando gravadores concorrentes neste fluxo.

`key(destination)` dá identidade ao trecho da tela. Ao trocar o destino, o estado
de rolagem criado dentro desse trecho é recriado. Ao trocar somente o tema, a chave
permanece igual e a posição de rolagem pode ser preservada.

## Layout e desenho

`Row` organiza horizontalmente, `Column` verticalmente e `Box` permite sobreposição.
`weight(1f)` divide o espaço disponível no eixo do layout pai. Na linha de métricas,
três pesos iguais dividem a largura; no corpo principal, o peso ocupa a altura que
resta depois de cabeçalho e rodapé.

`Modifier` é uma cadeia de instruções de tamanho, espaçamento, desenho e interação.
A ordem importa: aplicar fundo antes do padding pode cobrir uma região diferente de
aplicá-lo depois. `padding` cria espaço; `spacedBy` define distância entre filhos;
`fillMaxSize` usa os limites disponíveis impostos pelo pai.

`dp` é uma unidade de layout independente da densidade. `sp` é usado para texto e
considera a escala de fonte. No Canvas, `toPx()` converte dp para pixels. A miniatura
dos temas usa proporções de largura/altura, e os ícones usam um viewport de 24 × 24.

`Surface` combina forma, fundo e cor de conteúdo. `contentColor` evita herdar uma cor
de texto inadequada. `contentDescription = null` marca os ícones decorativos que já
possuem texto correspondente; funções sem texto precisam de descrição acessível.
`semantics`, `Role.Tab`, `Role.RadioButton` e `heading()` descrevem o papel dos elementos
para acessibilidade e inspeção automatizada; não desenham esses elementos.

## Temas e desempenho

`Color(0xAARRGGBB)` usa alpha, vermelho, verde e azul. Alpha FF é opaco. Alpha menor
nos painéis permite ver o gradiente interno. A janela do sistema permanece opaca.

Os nomes `onSurface`, `primaryContainer` etc. são papéis: texto de superfície, fundo
de botão e assim por diante. Componentes usam esses papéis para que os sete temas
compartilhem o mesmo layout. `@Immutable` é um contrato ao compilador/runtime sobre
a estabilidade dos dados; não congela objetos mutáveis automaticamente.

O PNG é carregado com remember. Os vetores e paletas são criados uma vez. O pincel
do fundo usa drawWithCache e não há animação ou blur contínuo. Isso reduz trabalho
repetido, mas não substitui medições com dados reais quando a sincronização existir.

## Build e limites atuais

`settings.gradle.kts` configura identidade, plugins e resolução do JDK.
`build.gradle.kts` configura compilação, bibliotecas e execução. `gradle.properties`
define opções locais do Gradle; seu limite de heap não é o limite de memória do app.

```bash
./gradlew build
./gradlew run
```

O H2 recebe as três tabelas pela migração V1 do Flyway. Os testes em
`src/test/kotlin/com/godrivesync/data/db/DatabaseTest.kt` e `DatabaseMigrationTest.kt`
usam arquivos temporários e verificam persistência, defaults, restrições, adoção do
banco anterior, adição de coluna e falhas de atualização/checksum.
Os testes em `src/test/kotlin/com/godrivesync/data/db/repositories/` verificam os
contratos de CRUD, filtros, rollback, concorrência e limpeza. O OAuthManager da
issue #7 já realiza autorização pelo navegador, validada separadamente com
`./gradlew oauthLogin`; testes em `drive/auth/` usam HTTP local e dados fictícios.
Login nas telas está conectado na issue #10; sincronização e histórico real na UI
continuam futuros. Os testes do banco não validam a interface; `LoginScreenTest`
renderiza a UI em superfície raster e verifica ações semânticas, sem Google real.
`test NO-SOURCE`, se aparecer em outra configuração, significa que o Gradle não
encontrou testes para executar; não equivale a testes aprovados.

## OAuth sem bloquear a interface

`OAuthManager.authorize()` é uma função suspensa: lê configuração e abre o navegador
em `Dispatchers.IO`, aguarda um callback com `CompletableDeferred` e troca o código
usando o cliente HTTP assíncrono do JDK. Em Swing, isso lembra separar o trabalho
de fundo do `ActionListener`; uma corrotina suspensa não ocupa uma thread esperando.

O manager não é um composable. LoginViewModel dispara login por um evento e mantém
seu estado fora da composição; não chame o
fluxo diretamente durante composição. Cancelar a corrotina fecha o listener e cancela
a requisição de token. Cada tentativa tem state e PKCE próprios, sem estado global.

`OAuthCredentials` transporta dados, como um DTO Java, mas não é estado observável
do Compose nem entidade persistida. Contém tokens em memória e omite-os em `toString`.
Antes de persistir, `AccountAuthorizationService` criptografa ambos os tokens. O `runBlocking` do diagnóstico
é apropriado à entrada de console; usá-lo num callback da UI congelaria a janela.
Veja o [contrato OAuth e diagnóstico](GOOGLE-CLOUD-OAUTH.md#oauthmanager-issue-7).

## Serviço de contas e credenciais

`AccountAuthorizationService` lembra uma camada de serviço Java: coordena OAuth,
consulta do e-mail, criptografia e DAO/repositório. A UI chama `authorizeAndSave()`
por um evento e atualiza seu estado com o resultado. `listAccounts()` retorna
`SavedAccount`, DTO sem tokens; `loadCredentials(id)` entrega tokens apenas à camada
que construirá o cliente da API. Não coloque credenciais em estado visual ou logs.

O serviço usa `withContext(Dispatchers.IO)` para arquivos/JDBC, sem exigir que cada
callback de UI cuide dessa troca. A chamada HTTP de identificação é assíncrona e
cancelável. Cancelar depois de uma transação ter sido commitada não apaga a conta.
Essas operações não pertencem ao corpo de um composable, pois recomposição pode
executá-lo novamente. `LoginState` modela carregamento, autorização e falhas por conta.

`TokenCipher` encapsula a API Java `javax.crypto`, usando AES-GCM: cifra o conteúdo
e detecta adulteração. `LocalTokenKeyStore` guarda a chave separada do banco;
`remember` não substitui esse armazenamento, pois só mantém valores em memória.
`tokenExpiry` no H2 usa `LocalDateTime` interpretado explicitamente como UTC;
o serviço devolve `Instant`, evitando depender do fuso da máquina para o vencimento.
Veja [Tokens e contas](TOKENS-E-CONTAS.md) para contratos, backup, falhas e diagnóstico.

## Renovação automática e exclusão mútua (issue #9)

`AccountCredentialManager.execute(id) { initializer -> ... }` é semelhante a um serviço
Java que executa o trabalho de um `SwingWorker`. Ele move SDK síncrono/JDBC para IO;
o chamador da UI inicia uma corrotina por evento e publica o resultado no estado
observável. Não use `runBlocking`, nem execute a operação no corpo do composable.

Um `Mutex` por conta permite aguardar sem bloquear a thread gráfica e serializa os
blocos dessa conta, inclusive a chamada HTTP. Compartilhe uma instância do manager
por banco/serviço; contas diferentes não disputam esse lock. `remember` pode manter
uma referência no dono apropriado da aplicação, mas não substitui o H2 nem deve criar
outro manager a cada tela. `Main` compartilha o manager com a UI da issue #10.

A biblioteca oficial `Credential` executa o grant de renovação. O inicializador
anexa Bearer, limita a repetição de 401 e desliga logs HTTP. O novo token só é usado
após a gravação cifrada. A comparação com a fotografia lida funciona como uma
atualização otimista em JDBC: se houve outro login ou exclusão, o resultado atrasado
é recusado. Preferência e vínculos são preservados.

`CredentialException.reason` permite apresentar ações distintas: tentar novamente,
corrigir configuração ou pedir nova autorização. `reauthorize(id)` reabre o OAuth
para a mesma conta; não é chamado por recomposição nem por uma falha de rede comum.
O diagnóstico usa a opção explícita `--reauthorize`; a UI usa o botão Reconectar,
somente por ação do usuário. Consulte [Renovação e recuperação](RENOVACAO-DE-TOKENS.md).

O HTTP desta camada é síncrono com timeouts: cancelar a corrotina não encerra o socket
imediatamente. O cancelamento é conferido entre etapas e antes da gravação, mas não
desfaz um commit já concluído. Isso difere do cliente JDK assíncrono do login inicial.

## Estado de todas as contas e navegação (issue #10)

`LoginViewModel` mantém `StateFlow<LoginState>` somente leitura. `App` coleta esse
fluxo com `collectAsState`, como observar um modelo compartilhado por listeners em
Swing. Uma nova fotografia atualiza os cartões sem recriar a sessão OAuth.
`remember` preserva o ViewModel durante a janela; `DisposableEffect` fecha seu scope.
`SupervisorJob` permite que falhas de uma conta não cancelem as verificações das outras.

`LaunchedEffect(login)` inicia a restauração local, idempotente. Outro efeito aplica
e consome o pedido de navegação. Assim, trocar tema ou recompor a tela não abre
navegador nem navega novamente por um evento antigo. Serviços fazem I/O fora da EDT.

`mappingAccountId` é só contexto visual: todas as contas continuam disponíveis.
Não existe seleção exclusiva nem login obrigatório ao reabrir. O Dashboard aparece
assim que o armazenamento local é lido, enquanto os perfis são consultados em segundo
plano. Foto indisponível não é falha de autenticação. Veja [Conexão de contas](LOGIN-E-CONTAS.md).

## Aviso antes de iniciar o Compose

`initializeDesktopDatabase` envolve somente a preparação do banco. Quando ela falha,
`DatabaseStartup.kt` classifica o erro e mostra um `JOptionPane` com OK. Nesse ponto
não existe composição nem janela principal: o uso de Swing evita criar uma sessão
Compose apenas para informar por que a inicialização não pôde continuar.

O diálogo é modal: `SwingUtilities.invokeAndWait` coloca sua criação na EDT e aguarda
o usuário confirmar/fechar. Se o chamador já estiver na EDT, a chamada é direta para
evitar deadlock. O trabalho de banco continua na thread de entrada, fora da EDT.
Depois do aviso, Main recebe null e encerra com status 1. Não cria serviços de contas
nem tenta operar com banco parcialmente inicializado. O tema é o Look and Feel do Swing,
independente da preferência de aparência do Compose, ainda não carregada.

## Gerenciamento e confirmação (issue #11)

`AccountsScreen` recebe dois estados: a lista compartilhada do login e o estado de diálogo
em `AccountsViewModel`. Isso lembra um modelo Swing compartilhado por duas apresentações,
sem duplicar o cadastro em dois lugares. O slot `accountActions` é uma função composable
que acrescenta botões à linha de ações do cartão existente; não cria outro fluxo OAuth.
`FlowRow` lembra um FlowLayout de Swing: usa a largura disponível e passa os itens para
outra linha quando necessário. Assim, os botões ficam compactos sem perder texto ou área
de clique. O cabeçalho agrupa avatar, e-mail e selo; metadados também usam quebra adaptável.
`FilledTonalButton` destaca Mapeamentos, enquanto `OutlinedButton` delimita as ações secundárias.
Ícones vetoriais compartilham os traços do menu; `contentDescription = null` evita repetir
o nome acessível que o texto do botão já fornece. As cores vêm do MaterialTheme.

`AlertDialog` aparece quando `AccountsState.removal` contém uma conta. Cancelar limpa esse
campo; confirmar envia um evento ao ViewModel. A composição só descreve a aparência:
nenhuma remoção ocorre porque a tela recompôs. `rememberScrollState` mantém a rolagem
do texto do diálogo entre recomposições; o estado de confirmação pertence à janela.

`manageAccount` publica a alteração na fonte compartilhada depois do sucesso no banco.
`managing` bloqueia OAuth, releitura e gravação concorrentes, como desabilitar ações Swing
até o resultado do worker. O serviço usa `Dispatchers.IO` para JDBC. `NonCancellable`
abrange somente a transação local e sua publicação: fechar a janela não cancela a entrega
de um commit já realizado. Não deve envolver consentimento no navegador ou espera de rede.

Ao remover a conta, o job de perfil é cancelado; a verificação `ensureActive` descarta
respostas atrasadas. O `App` observa a lista para corrigir o contexto de Mapeamentos,
sem transformar conta padrão em sessão exclusiva. Veja [Contas](LOGIN-E-CONTAS.md).

## Cliente Drive e fábrica (issue #12)

`DriveApiClient.fromSavedAccount(conta, manager)` é uma função no `companion object`,
semelhante a uma fábrica estática Java. Cria um wrapper que guarda somente o ID;
os tokens atuais são lidos pelo manager em cada operação. O construtor alternativo
aceita `Account`, cujos tokens são cifras e não devem ser usados como Bearer.

`listFilesPage()` é suspensa e devolve DTOs com os dados já consumidos. O serviço
Java `Drive` é construído e usado dentro do escopo autenticado em IO. Como no
`SwingWorker`, o chamador recebe o resultado, sem carregar conexões para a UI.
O wrapper não é estado observável; a futura tela publicará as páginas no seu
ViewModel. Veja [Cliente Drive](CLIENTE-DRIVE.md) para paginação e diagnóstico.
