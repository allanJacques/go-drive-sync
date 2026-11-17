# Renovação automática e recuperação de acesso — issue #9

O `AccountCredentialManager` executa operações autenticadas de uma conta salva. Usa
`Credential` e `RefreshTokenRequest` da biblioteca oficial Google OAuth já presente
nas dependências. O login inicial continua no `OAuthManager`, com PKCE/state e HTTP
assíncrono do JDK. O esquema H2 e a migração V1 permanecem iguais.

## Caminho de uma chamada

1. Aguarda o `Mutex` da conta e entra em `Dispatchers.IO`.
2. Relê a fotografia do banco e decifra tokens somente em memória.
3. Constrói uma `Credential` com JSON Desktop, endpoint fixo e relógio UTC.
4. Entrega um `HttpRequestInitializer` ao bloco da operação.
5. Antes de enviar Bearer, confere a origem e renova se faltarem até 60 s para expirar.
6. Se a API responder 401, renova e repete uma vez, desde que a requisição seja reproduzível.
7. Devolve o resultado materializado; falhas são sanitizadas e a operação libera o lock.

A origem de produção aceita é `https://www.googleapis.com`, utilizada pelo Drive SDK.
Redirects não são seguidos e tokens não são colocados na URL. O POST de renovação
não é repetido automaticamente. Uma renovação preventiva e outra em resposta a um
401 podem ocorrer na mesma chamada; a chamada à API tem no máximo duas tentativas.
Um token recém-emitido com validade menor que 60 s não gera nova renovação apenas
pela margem; vencimento efetivo continua sendo verificado.

HTTP usa timeout de conexão de 15 s e leitura de 30 s. Esses limites não são um
prazo global de toda a operação. Logs HTTP/cURL são desativados, e exceções públicas
não incluem corpo remoto, cabeçalhos, URL ou causa com tokens. Não imprima o
inicializador, requisições, credenciais ou corpos de respostas no código chamador.

## Persistência e concorrência

A resposta precisa conter access token não vazio, tipo Bearer e validade positiva.
Escopo, se informado, precisa conter `drive`. Refresh omitido preserva o anterior;
refresh novo substitui o anterior. Expiração é calculada a partir do início do POST,
para não contar o tempo de rede como validade adicional.

`AccountAuthorizationService.saveRefreshed` cifra os tokens e chama
`AccountRepository.updateTokensIfUnchanged`. Dentro do mesmo lock/transação das
outras gravações, o repositório compara ID, e-mail, cifras e expiração com a fotografia
original. Só então atualiza tokens/expiração. Alterações em preferência, criação,
última sincronização, mapeamentos e histórico são preservadas. Uma conta excluída
não é recriada. Um login mais recente não é sobrescrito por uma resposta atrasada.

Persistência ocorre antes de usar o novo token na API. Se chave/banco falhar, a chamada
é interrompida; não há fallback que mantenha o novo token apenas em memória. Se o
servidor rotacionou o refresh token e a gravação falhou, pode ser necessário autorizar
novamente depois de corrigir o armazenamento. A transação local não desfaz ações do Google.

Compartilhe **uma instância do manager por serviço/banco**. Seus locks serializam o
bloco inteiro das operações de uma conta, evitando múltiplas renovações concorrentes
nessa instância. Contas diferentes podem executar em paralelo. Instâncias distintas
continuam protegidas contra sobrescrita pela comparação no H2, mas podem fazer POSTs
concorrentes e receber `CREDENTIALS_CHANGED`. Não há coordenação distribuída.
Uma futura estratégia de downloads paralelos deverá rever a granularidade do lock.

## Contrato para o futuro cliente Drive

`execute(accountId) { initializer -> ... }` é suspenso; seu bloco é síncrono em IO.
Use o inicializador em `Drive.Builder` ou `HttpTransport.createRequestFactory` e faça
a chamada completa dentro desse bloco. Consuma e feche respostas/streams antes de
retornar. Não retenha o inicializador ou devolva uma requisição/stream para executar
depois. Não substitua interceptores, handlers de erro, limites ou política de origem.
Não invoque outra operação deste manager dentro do bloco: o Mutex não é reentrante.

`loadCredentials(id)` continua sendo somente leitura: não renova nem persiste.
`SavedAccount.isExpired()` também continua apenas consultando o vencimento.
O cliente completo `DriveApiClient` permanece na issue #12. A dependência circular
entre #9 e #12 foi resolvida entregando aqui a camada reutilizável e verificando seu
protocolo com HTTP local. Login e navegação estão integrados nas telas pela issue #10.

Cancelamento da corrotina é conferido antes das etapas, depois do HTTP e antes da
gravação. HTTP síncrono pode permanecer aguardando resposta/timeout; não promete
interrupção imediata do socket. Cancelamento não desfaz um commit já concluído.

## Falhas e nova autorização

| Resultado | Ação do chamador |
|---|---|
| `REAUTHORIZATION_REQUIRED` | O Google devolveu `invalid_grant`; oferecer `reauthorize(id)`. |
| `TEMPORARY` | Falha de comunicação, HTTP 429 ou 5xx na renovação; permitir tentar depois. |
| `CONFIGURATION` | Cliente OAuth recusado ou origem não permitida; corrigir configuração. |
| `INVALID_RESPONSE` | Credenciais incompletas/inválidas ou resposta de erro inesperada; não substituir tokens. |
| `CREDENTIALS_CHANGED` | Outro login/refresh/exclusão venceu a corrida; reler a conta numa nova operação. |
| `ACCOUNT_NOT_FOUND` | Atualizar a lista de contas; nenhum POST é feito. |
| `API_REJECTED` | A API recusou a operação; `statusCode` informa o HTTP, sem corpo remoto. |
| `TokenProtectionException` / `AccountAuthorizationException` | Corrigir chave/armazenamento; não apagar a conta. |

Um segundo 401 é `API_REJECTED`, sem loop nem tentativa adicional de login. HTTP 403
da API também não desencadeia refresh. Falhas não apagam tokens, contas ou vínculos.
Uma resposta malformada que o parser HTTP não consiga ler pode ser reportada como
falha de comunicação; em ambos os casos o banco permanece intacto.

`AccountAuthorizationService.reauthorize(id)` usa o mesmo OAuth inicial, mas exige
o e-mail da conta solicitada e compara a fotografia antes de salvar. Selecionar outra
conta no navegador é recusado antes da persistência. Remover/alterar a conta durante
a autorização também impede sobrescrita. A UI dispara esse método pelo botão Reconectar,
nunca por recomposição. Uma conta com falha não bloqueia a lista nem as outras contas.

## Diagnóstico manual

```sh
./gradlew oauthRefreshCheck
./gradlew oauthRefreshCheck --args='1'
./gradlew oauthRefreshCheck --args='1 --reauthorize'
./gradlew oauthRefreshCheck --args='1 "/caminho com espaços/credentials.json" --reauthorize'
```

Sem argumentos, lista os IDs das contas existentes. Troque `1` pelo ID desejado.
O diagnóstico usa H2 e chave locais, consulta apenas `about.get?fields=user(emailAddress)`
e exibe e-mail/expiração persistida. Pode atualizar as credenciais, sem alterar arquivos
do Drive. Não faz parte de `build` nem da inicialização da janela.

Sem `--reauthorize`, não abre navegador. Com a opção, abre OAuth somente quando a
renovação exige re-login; depois de salvar, repete a verificação de acesso uma vez.
Se não houver conta salva, use primeiro `./gradlew oauthAccountLogin`.

Para observar a renovação real, execute a verificação quando o access token salvo
estiver vencido/próximo do vencimento e confira a nova expiração; não edite o banco
real para forçar o cenário. Revogação é simulada nos testes. O roteiro manual com
Google depende de uma conta e interação no navegador e não é validado pelo build.

## Verificações automatizadas

```sh
./gradlew test --tests com.godrivesync.drive.auth.AccountCredentialManagerTest
./gradlew build
```

Os testes usam servidor loopback, H2, chave e tokens fictícios. Exercitam token válido,
vencimento/margem, refresh omitido/rotacionado, vida curta, 401 e limite de repetição,
`invalid_grant`, configuração recusada, 429/5xx, resposta inválida, persistência após
reabrir, preservação de metadados/FKs, concorrência por conta, isolamento entre contas,
login/exclusão durante refresh, cancelamento, perda de chave, origem externa e redirects.
Os testes do serviço também cobrem reautorização da mesma identidade e remoção durante
o navegador simulado. Os testes da UI da issue #10 estão descritos em [Conexão de contas](LOGIN-E-CONTAS.md).

Referências: [Credential oficial](https://docs.cloud.google.com/java/docs/reference/google-oauth-client/latest/com.google.api.client.auth.oauth2.Credential),
[Google OAuth Client para Java](https://developers.google.com/api-client-library/java/google-oauth-java-client/oauth2).
