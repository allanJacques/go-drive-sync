# Cliente base do Google Drive — issue #12

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
e Gson, executa a chamada e devolve dados materializados. O serviço Drive e os
objetos de requisição não saem desse escopo. Futuras operações com streams também
deverão consumi-los e fechá-los antes de retornar.

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
inicial da issue, não é necessária: duplicaria mecanismos de autenticação/JSON.
Não há mudança de esquema, migração ou interface nesta entrega.

## Listagem básica e limites

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
`pageSize` aceita 1..1000; token fornecido não pode estar em branco. O cliente não
acumula todas as páginas nem repete automaticamente buscas incompletas.

Metadados obrigatórios ausentes e continuação em branco geram `INVALID_RESPONSE`.
JSON que o SDK não consiga analisar segue a sanitização genérica do manager.
HTTP 403/429/5xx da API produz `API_REJECTED` com o status, sem backoff nesta etapa;
401 permite apenas a repetição definida pelo manager. `invalid_grant` no refresh
produz `REAUTHORIZATION_REQUIRED`. Nenhuma dessas falhas apaga a conta.

Nomes, IDs e tokens de página são dados da conta: não registre respostas completas
em logs. `DriveFilesPage.toString()` mostra apenas contagem e indicadores. Não há
download, upload, exportação, sincronização ou suporte a Shared Drives nesta etapa.

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
./gradlew build
```

Os testes usam H2 e chave temporários, credenciais fictícias e transporte HTTP
simulado da biblioteca Google, sem sockets. O SDK Drive, os interceptores, parsing
JSON, renovação e persistência são reais. Cobrem parâmetros da consulta, páginas
vazias/continuação, vencimento, 401/limite de repetição, isolamento entre contas,
reconexão, exclusão, validação de entradas, recusas, resposta incompleta, redirects,
origens externas e cancelamento antes do HTTP.

Referências oficiais:

- [files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list)
- [Exemplo Java com Drive.Builder](https://developers.google.com/workspace/drive/api/quickstart/java)
