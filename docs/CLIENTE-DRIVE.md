# Cliente do Google Drive

`DriveApiClient` vincula operações do SDK oficial a uma conta salva. Seu construtor
aceita `Account`, mas conserva somente o ID: `accessToken` e `refreshToken` nesse DTO
são cifras, não valores para enviar como Bearer. A fábrica `fromSavedAccount` aceita
a projeção sem tokens usada pelas telas. Ambos exigem ID positivo e o
`AccountCredentialManager` compartilhado pela aplicação.

```kotlin
// accounts e manager são os serviços compartilhados; este trecho roda numa corrotina.
val saved = accounts.listAccounts().first { it.id == accountId }
val client = DriveApiClient.fromSavedAccount(saved, manager)
val page = client.listFilesPage(pageSize = 10)
```

Construir o wrapper não acessa rede/banco. Cada operação recupera as credenciais
persistidas atuais, incluindo refresh e expiração, pelo manager. Assim, um cliente
já existente acompanha uma reconexão; uma conta removida falha antes da requisição.
Não há conta ativa exclusiva nem cópia duradoura de tokens dentro do cliente.

## Execução e renovação

O manager move o trabalho para `Dispatchers.IO`, adquire o Mutex por conta e fornece
o inicializador autenticado. O cliente cria `Drive.Builder` com esse inicializador
e Gson e executa a chamada. Na listagem, devolve os dados materializados. No download,
consome o stream da mídia sob o mesmo lock e libera a resposta antes de retornar.

Isso lembra executar SDK/JDBC em um `SwingWorker`: a UI dispara o trabalho por uma
corrotina/evento e recebe o resultado pronto. `suspend` não transforma o SDK em HTTP
assíncrono; os limites de cancelamento e timeouts são os mesmos da [renovação](RENOVACAO-DE-TOKENS.md).
Não use `runBlocking` na UI nem invoque o cliente no corpo de um composable.

O manager mantém renovação preventiva, persistência cifrada antes do uso, uma única
repetição após 401, bloqueio de redirects/origens externas e erros sanitizados.
Não abre navegador automaticamente. Compartilhe uma instância por serviço/banco;
operações da mesma conta são serializadas, inclusive durante o HTTP.

As dependências `google-api-client` e `google-api-services-drive` já existiam.
Mantemos `Credential` e Gson usados pela #9. A inclusão de
`google-auth-library-oauth2-http` e `google-http-client-jackson2`, sugerida no texto
inicial da issue #12, duplicaria os mecanismos de autenticação e JSON usados pelo projeto.
A migração V2 guarda o progresso da leitura incremental descrita a seguir.

## Ler mudanças incrementais

Use uma instância de `DriveChangeTracker(client, repository)` por conta. Chame
`poll(initialScan, process)` uma vez para cada rodada. Os dois argumentos são
funções suspensas fornecidas pelo consumidor. Elas rodam em IO e podem chamar
o cliente Drive. O agendamento e os efeitos sobre os mapeamentos cabem ao motor.

1. Se faltar uma posição no H2, o tracker chama `changes.getStartPageToken`,
   executa `initialScan` e grava o token quando esse callback conclui. A varredura
   deve cobrir todos os mapeamentos atendidos pela conta e persistir o inventário.
   Obter o token antes da varredura permite recuperar as alterações feitas durante ela.
2. Partindo dessa posição ou da última salva, `client.getChanges(pageToken)` lê
   uma página de `changes.list` do espaço Drive da conta, excluindo Shared Drives.
   O tracker entrega cada item a `process` na ordem recebida. Um `nextPageToken`
   leva à página seguinte mesmo quando a lista atual está vazia.
3. Quando todos os callbacks terminam, o tracker confirma o `newStartPageToken`
   da última página. Se uma leitura ou um callback falhar, a exceção chega ao
   chamador. A próxima rodada começa na última posição confirmada. Uma falha na
   varredura inicial exige repeti-la.

O Drive informa o estado atual dos itens. Chame `change.classify(idsConhecidos)`
com os IDs do inventário desta conta no momento de processar a mudança.

| Resultado | Interpretação |
|---|---|
| `NEW` | Item acessível cujo ID ainda não consta no inventário. Inclui arquivos recém-compartilhados com a conta. |
| `MODIFIED` | Item acessível já conhecido. Os metadados atuais incluem nome e pastas pai para detectar movimentos. |
| `TRASHED` | O Drive marcou o arquivo como enviado à lixeira. |
| `REMOVED` | O ID saiu da lista da conta. Pode indicar exclusão definitiva ou perda de acesso. |

O consumidor deve reconciliar por ID, verificar se a mudança afeta os mapeamentos
e persistir o resultado antes de retornar. Uma interrupção entre esse efeito e a
gravação do token pode entregar o mesmo item de novo. Por exemplo, se uma inclusão
já foi aplicada, a repetição encontra o ID conhecido e deve atualizar o registro
existente. A camada de leitura apenas informa as remoções.

[Guia de mudanças do Drive](https://developers.google.com/workspace/drive/api/guides/manage-changes) ·
[Esquema e confirmação do token](BANCO-DE-DADOS.md#posição-de-leitura-do-drive)

## Listar os filhos de uma pasta

`listFiles(folderId)` devolve um `List<DriveFileSummary>` com os filhos imediatos da
pasta. Use o ID salvo em um mapeamento ou `"root"` para o primeiro nível do Meu Drive.
Para abrir uma subpasta, passe o ID dela em uma nova chamada.

```kotlin
val itens = client.listFiles("root")
val subpastas = itens.filter { it.isFolder }
```

O cliente usa `'<folderId>' in parents and trashed = false` e pede até 1.000 itens
por página. Ele segue o token mesmo se uma página vier vazia. Cada requisição passa
pelo manager da mesma conta, que libera o bloqueio entre páginas. Depois da última
página, o chamador recebe os itens na ordem enviada pelo Drive. Uma pasta vazia
produz uma lista vazia.

| Campo | Valor na listagem por pasta |
|---|---|
| `id`, `name`, `mimeType` | ID do item, nome de exibição e tipo MIME do Drive. |
| `size` | Tamanho em bytes (`Long?`). Zero representa um arquivo vazio e `null` indica que o Drive omitiu o tamanho. |
| `modifiedTime` | Instante da última alteração (`Instant?`), ou `null` se a API omitir o campo. |
| `parents` | IDs das pastas pai informadas pelo Drive. A lista fica vazia quando o campo é omitido. |

`isFolder` identifica o tipo exato `application/vnd.google-apps.folder`.
`isGoogleNative` reconhece o prefixo `application/vnd.google-apps.`, que inclui
documentos, pastas e atalhos. Uma pasta, portanto, satisfaz as duas propriedades.

Uma busca marcada com `incompleteSearch` produz a falha `TEMPORARY`. Uma continuação
em branco ou já usada produz `INVALID_RESPONSE`. Se alguma página falhar ou a
corrotina for cancelada, a operação descarta o resultado acumulado. Uma nova chamada
começa pela primeira página. Consulte o [guia de consultas da API do Drive](https://developers.google.com/workspace/drive/api/guides/search-files).

## Baixar um arquivo binário

Para salvar um PDF, uma foto ou outro arquivo binário, passe o cliente da conta ao
`FileHandler`, o ID do item ao `downloadFile` e um `Path` para o arquivo de destino.
O chamador escolhe o caminho e decide quando substituir a versão local.

```kotlin
// Dentro de uma corrotina, com o client da conta e o ID do PDF escolhido.
val destino = Path.of("downloads", "relatorio.pdf")
FileHandler(client).downloadFile(fileId, destino) { gravados, total ->
    val progresso = if (total != null && total > 0) {
        "${(100.0 * gravados / total).toInt()}% de $total bytes"
    } else {
        "$gravados bytes"
    }
    println(progresso)
}
// O retorno da função confirma que o arquivo completo já está no destino.
```

Cada tentativa consulta o tipo MIME, a capacidade `canDownload` e o tamanho.
Pastas, atalhos, documentos nativos do Google e itens com download bloqueado encerram
a operação antes da requisição de mídia. O SDK usa `files.get` com `alt=media` e o
aplicativo grava os blocos à medida que chegam. [Guia de downloads do Drive](https://developers.google.com/workspace/drive/api/guides/manage-downloads)

O callback roda em IO. Ele recebe zero ao iniciar a cópia e o número acumulado de
bytes após cada gravação. Para atualizar a interface, o ViewModel pode publicar esses
valores em um `StateFlow` observado pelo Compose. Total zero representa arquivo vazio.
Se o Drive omitir o tamanho, o cliente usa o `Content-Length` quando ele corresponde
ao conteúdo sem compressão. Sem essas medidas, o total é `null` e a interface pode
mostrar os bytes recebidos com uma barra indeterminada.

O serviço cria as pastas pais que faltarem e grava em um temporário na pasta do
destino. Depois de fechar a saída e conferir o tamanho conhecido, publica a cópia
com uma movimentação atômica. Em um sistema de arquivos que recuse essa operação,
o download termina com erro. Uma falha ou um cancelamento antes da troca preserva
o arquivo local anterior e remove o temporário. O cancelamento é verificado entre
blocos e antes da troca. Uma leitura HTTP bloqueada pode aguardar o timeout do manager.

Uma desconexão, um tamanho divergente, HTTP 408/429/500/502/503/504 ou HTTP 403
com motivo de limite de requisições permitem até três tentativas no total.
As esperas são de um e dois segundos, com o lock da conta liberado. A nova tentativa
relê o tamanho e reinicia a contagem de bytes. Erros de permissão, arquivo inexistente
e falhas de escrita encerram a operação. O manager continua responsável pela renovação
de credenciais e pela repetição de um GET após HTTP 401.

## Enviar um arquivo local

Em uma corrotina, passe um arquivo regular legível e o ID da pasta de destino
(`"root"` indica o Meu Drive). O nome remoto vem do último componente do caminho.
Cada chamada bem-sucedida cria um item com seu próprio ID, inclusive se outro item
na pasta já tiver o mesmo nome.

```kotlin
val origem = Path.of("documentos", "Relatório.pdf")
val fileId = FileHandler(client).uploadFile(origem, "root") { enviados, total ->
    println("$enviados de $total bytes")
}
```

O callback roda em IO, como o trabalho de fundo de um `SwingWorker`. Um ViewModel
pode publicar os valores em um `StateFlow` para a tela. O primeiro valor é zero.
Até 5.000.000 bytes, o SDK envia metadados e conteúdo em um único POST multipart.
O próximo valor é o total após a resposta com o ID. Um arquivo vazio informa
`(0, 0)` uma vez e também recebe um ID.

Acima do limite, o SDK abre uma sessão e envia blocos de até 1 MiB. O progresso
intermediário reflete a posição confirmada pelo Drive. Depois de uma falha de
transporte durante um PUT, o cliente pode consultar essa posição na mesma sessão
e continuar do primeiro byte pendente. A sessão vale durante a chamada, inclusive
quando o Drive confirma só parte de um bloco. O chamador deve manter o conteúdo
local estável durante a leitura. [Guia de uploads do Drive](https://developers.google.com/workspace/drive/api/guides/manage-uploads)

Cancelar interrompe as próximas requisições. Uma requisição em andamento pode
terminar no Drive antes de o chamador receber o resultado. Se a confirmação
final se perder e o resultado continuar incerto, confira o destino antes de
repetir a criação. O ID retornado fica disponível ao chamador para a sincronização.

## Exportar um documento nativo

Em uma corrotina, consulte `listExportFormats()` para obter os formatos da conta.
`formatsFor` devolve as opções para Docs, Sheets ou Slides. Cada opção inclui o
MIME usado na requisição, o nome para exibição e a extensão conhecida. Um novo
MIME aparece com o próprio identificador, mesmo sem tradução no aplicativo.
A lista vem de `about.get(fields=exportFormats)`.
[Formatos de exportação do Drive](https://developers.google.com/workspace/drive/api/guides/ref-export-formats)

```kotlin
val formatos = client.listExportFormats()
val opcoesDaPlanilha = formatos.formatsFor(GoogleDocumentType.SHEETS)
val handler = FileHandler(client)
val base = Path.of("downloads", "Orçamento")
val tipo = GoogleDocumentType.SHEETS.mimeType

// As escolhas do usuário são independentes. Use o mesmo catálogo para as duas saídas.
val ods = handler.exportFile(id, tipo, base, "application/vnd.oasis.opendocument.spreadsheet", formatos)
val csv = handler.exportFile(id, tipo, base, "text/csv", formatos)
```

Cada retorno é o caminho absoluto publicado. No exemplo, os resultados são
`Orçamento.ods` e `Orçamento.csv`. CSV e TSV contêm a primeira aba da planilha.
Sem formato explícito, Docs usa DOCX, Sheets usa XLSX e Slides usa PPTX. Sem
catálogo explícito, a operação consulta o Drive. Ao compartilhar um catálogo,
use uma consulta recente da mesma conta para a rodada de sincronização.

O nome-base mantém pontos e extensões já informados. Por exemplo, um PDF com o
nome-base `Ata.v2` vira `Ata.v2.pdf` e `Ata.PDF` conserva a extensão. Para um MIME
novo sem extensão conhecida, o aplicativo usa `.export-<identificador>.bin`.
O identificador usa os primeiros 12 bytes do SHA-256 do MIME em hexadecimal.
Isso mantém um caminho estável e distinto por formato entre sincronizações.
O conteúdo gravado é o que o Drive converteu.

Cada chamada pede uma saída com `files.export`, que limita o conteúdo exportado a
10 MB. A transferência usa a cópia em blocos, o cancelamento e a troca atômica
descritos no download binário. Falhas transitórias na mídia seguem a mesma política
de repetição. O tamanho do original pode diferir do tamanho convertido.
[Referência de files.export](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/export)

### Quando a conversão falha

| Situação | Resultado para o chamador |
|---|---|
| O catálogo válido da conta omite o formato pedido | `DriveExportException` com motivo `EXPORT_FORMAT_UNSUPPORTED`, etapa `CATALOG` e status HTTP nulo. A verificação termina antes da transferência. |
| O Drive recusa explicitamente a conversão na requisição | Mesmo motivo, etapa `EXPORT`, MIME de origem e destino e status HTTP. O cliente consulta o catálogo novamente e o devolve em `refreshedCatalog` quando disponível. Se a conversão ainda constar ali, a recusa se aplica àquele arquivo. |
| O Drive informa arquivo não exportável, tamanho excessivo ou falta de permissão | A exceção contém o motivo específico da recusa. |
| Falha de rede, consulta inválida ou erro HTTP genérico | O cliente propaga a falha da operação. A seleção de formatos continua válida para reavaliação. |

A #22 usará o vínculo de cada cópia local. A fila da #23 poderá tratar cada saída
separadamente. A #24 reavaliará os formatos pendentes e a #30 permitirá salvar a
seleção. A gravação da ocorrência no H2 pertence à #28. A última cópia íntegra
permanece disponível após uma falha.

## Renomeação local de um arquivo binário

Considere um mapeamento bidirecional em que `A.pdf` já foi sincronizado e o
nome `B.pdf` está livre. Se você renomear o arquivo para `B.pdf` no computador,
o motor de sincronização previsto removerá o item de `A.pdf` no Drive pelo ID
conhecido e criará `B.pdf` com um novo ID. Quem recebeu o link de `A.pdf`
precisará de um novo link para abrir `B.pdf`.

O motor deverá reconhecer a ausência do caminho antigo mesmo se a mudança
ocorrer com o aplicativo fechado. Na primeira sincronização, um arquivo presente
apenas no Drive é candidato a download. Em mapeamentos somente download e para
cópias exportadas de Docs, Sheets ou Slides, a direção continua sendo do Drive
para o computador. [Decisão e fluxo de sincronização](../PLAN.md#52-fluxo-de-sincronização-syncengine)

## Consultar uma página para diagnóstico

`listFilesPage(pageSize = 10, pageToken = null)` faz uma chamada `files.list`, com
`spaces=drive`, `corpora=user`, `includeItemsFromAllDrives=false` e `trashed=false`.
Esse corpus pode incluir itens compartilhados com o usuário fora de Shared Drives;
não significa apenas filhos da raiz do Meu Drive. A consulta não percorre pastas.

| Campo | Significado |
|---|---|
| `files` | Lista de `DriveFileSummary`, com ID, nome e MIME type de arquivo ou pasta. |
| `nextPageToken` | Continuação opaca para a mesma conta/consulta; null indica fim. |
| `incompleteSearch` | Informa que a resposta não representa uma busca completa. |

Uma página pode estar vazia e ainda ter continuação. Passe seu token à chamada
seguinte; não use apenas `files.isEmpty()` para decidir que a listagem terminou.
`pageSize` aceita 1..1000 e um token fornecido deve conter pelo menos um caractere
visível. Essa consulta pede apenas ID, nome e tipo MIME de cada item. Os demais
campos de `DriveFileSummary` recebem seus valores padrão.

Metadados obrigatórios ausentes e continuação em branco geram `INVALID_RESPONSE`.
JSON que o SDK não consiga analisar segue a sanitização genérica do manager.
HTTP 403/429/5xx da API produz `API_REJECTED` com o status, sem backoff nesta etapa;
401 permite apenas a repetição definida pelo manager. `invalid_grant` no refresh
produz `REAUTHORIZATION_REQUIRED`. Nenhuma dessas falhas apaga a conta.

Nomes, IDs e tokens de página são dados da conta: não registre respostas completas
em logs. `DriveFilesPage.toString()` mostra apenas contagem e indicadores.

## Diagnóstico com uma conta real

Feche o aplicativo antes do diagnóstico para liberar o H2 embarcado. Use uma conta
já adicionada e o JSON Desktop externo configurado anteriormente:

```sh
./gradlew driveApiCheck
./gradlew driveApiCheck --args='1'
./gradlew driveApiCheck --args='1 "/caminho com espaços/credentials.json"'
```

Sem argumentos, mostra IDs/e-mails locais. Com ID, lista até dez itens e imprime
apenas contagem, existência de próxima página e indicador de busca incompleta.
Uma resposta vazia também confirma a chamada autenticada. Pode renovar/persistir
tokens, mas não altera arquivos remotos/locais nem abre consentimento. Se exigir
reconexão, use a ação Reconectar da tela Contas e execute novamente.

Esse diagnóstico é separado de `build` e da inicialização da janela. A validação
real depende da conta/configuração do mantenedor e não é comprovada pelos testes.

## Verificações automatizadas

```sh
./gradlew test --tests com.godrivesync.drive.api.DriveApiClientTest
./gradlew test --tests com.godrivesync.drive.sync.FileHandlerTest
./gradlew build
```

Os testes usam H2 e chave temporários, credenciais fictícias e transporte HTTP
simulado da biblioteca Google, sem sockets. O SDK Drive, os interceptores, parsing
JSON, renovação e persistência são reais. Cobrem parâmetros da consulta, páginas
vazias/continuação, vencimento, 401/limite de repetição, isolamento entre contas,
reconexão, exclusão, validação de entradas, recusas, resposta incompleta, redirects,
origens externas e cancelamento antes do HTTP. Os testes por pasta também conferem
o filtro enviado em cada página, a conversão de datas e tamanhos, os tipos MIME,
uma página intermediária vazia, ciclos de continuação e falhas após a primeira página.

Os testes de download escrevem em diretórios temporários e comparam os bytes
publicados com a mídia simulada. Cobrem progresso por bloco, arquivo vazio, total
desconhecido, substituição, cancelamento, desconexão durante a leitura, divergência
de tamanho, classificação das falhas e limite de tentativas.

Os testes de upload conferem o conteúdo multipart para zero e 5.000.000 bytes.
Com 5.000.001 bytes, simulam a perda da resposta de um bloco que o servidor
recebeu em parte e comparam todo o conteúdo após a retomada. Também exercitam
cancelamento, recusa de criação e recuperação da confirmação do último bloco.

A suíte também consulta o catálogo simulado, exporta os três padrões Office e várias
saídas do mesmo documento e verifica os caminhos e bytes gravados. Os testes de
recusa conferem o motivo e a etapa, a nova consulta de capacidades e a conservação
do destino anterior.

Referências oficiais:

- [files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list)
- [Exemplo Java com Drive.Builder](https://developers.google.com/workspace/drive/api/quickstart/java)
