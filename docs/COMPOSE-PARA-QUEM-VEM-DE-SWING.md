# Entendendo este projeto vindo de Java/Swing

Este guia acompanha a interface da issue #3, a inicialização do banco da issue #4
e os modelos/repositórios da issue #5. O fluxo OAuth da issue #7 é independente
das telas e tem diagnóstico de console próprio.
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
`val` e `data class` não tornam esses objetos observáveis pelo Compose: a futura
camada de estado da tela precisará publicar os resultados das consultas.

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
e a janela não abre. Consultas futuras feitas após a janela abrir deverão sair da
thread da interface, usando `Dispatchers.IO`, como a gravação das preferências.

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
Login nas telas, sincronização e histórico real na UI ainda não existem; os
repositórios ainda não foram conectados às telas. Os testes do banco não validam essas etapas nem a interface.
`test NO-SOURCE`, se aparecer em outra configuração, significa que o Gradle não
encontrou testes para executar; não equivale a testes aprovados.

## OAuth sem bloquear a interface

`OAuthManager.authorize()` é uma função suspensa: lê configuração e abre o navegador
em `Dispatchers.IO`, aguarda um callback com `CompletableDeferred` e troca o código
usando o cliente HTTP assíncrono do JDK. Em Swing, isso lembra separar o trabalho
de fundo do `ActionListener`; uma corrotina suspensa não ocupa uma thread esperando.

O manager não é um composable e ainda não está ligado às telas. Na futura integração,
dispare o login por um evento e mantenha seu estado no dono da tela; não chame o
fluxo diretamente durante composição. Cancelar a corrotina fecha o listener e cancela
a requisição de token. Cada tentativa tem state e PKCE próprios, sem estado global.

`OAuthCredentials` transporta dados, como um DTO Java, mas não é estado observável
do Compose nem entidade persistida. Contém tokens em memória e omite-os em `toString`.
Antes de persistir, a issue #8 deve criptografá-los. O `runBlocking` do diagnóstico
é apropriado à entrada de console; usá-lo num callback da UI congelaria a janela.
Veja o [contrato OAuth e diagnóstico](GOOGLE-CLOUD-OAUTH.md#oauthmanager-issue-7).
