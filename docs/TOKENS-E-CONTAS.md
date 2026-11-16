# Contas e tokens criptografados — issue #8

O `AccountAuthorizationService` integra a autorização já existente com o banco.
Ele é independente da UI: pode ser chamado por uma corrotina de uma futura tela,
ou pelo diagnóstico de console. Não implementa renovação automática nem sincronização.

## Fluxo e responsabilidades

1. Verifica a chave local; só cria uma quando o banco não tem contas e ela está ausente.
2. Chama `OAuthManager.authorize()` para obter tokens e validade.
3. Consulta `about.get?fields=user(emailAddress)` com Bearer no cabeçalho, usando o
   escopo `drive` já concedido. Redirects não são seguidos. O e-mail vem do Google,
   não de um campo digitado pelo usuário.
4. Relê a chave, cifra acesso e renovação e grava ambos na mesma transação.
5. Retorna `SavedAccount`: ID, e-mail, expiração e preferência, sem tokens.

| Componente | Responsabilidade |
|---|---|
| `AccountAuthorizationService` | Orquestra o fluxo, move disco/JDBC para `Dispatchers.IO` e protege mensagens de erro. |
| `GoogleAccountClient` | Identifica o e-mail via HTTP assíncrono cancelável; timeout de requisição de 30 s e conexão de 15 s. |
| `TokenCipher` | Cifra/decifra AES-GCM autenticado, sem estado mutável compartilhado. |
| `LocalTokenKeyStore` | Cria/lê chave AES de 32 bytes com permissões do proprietário. |
| `AccountRepository.saveAuthorizedTokens` | Insere por e-mail ou atualiza somente tokens/expiração sob o lock transacional existente. |
| `SavedAccount` | Projeção segura para a seleção de contas; `isExpired()` consulta o vencimento. |

Reautorizar o mesmo e-mail mantém ID, conta padrão, data de criação, última sincronização,
mapeamentos e histórico. Novas contas seguem o default SQL `is_default=false`.
A busca por e-mail continua exata, sem conversão para minúsculas. Autorizações concorrentes
da mesma conta no processo resultam em um único registro e um par coerente de tokens.

Falhas de identificação/cifra anteriores à transação não alteram contas. Cancelamento
de HTTP é propagado; cancelar depois do commit não desfaz a gravação. O serviço não
registra tokens, respostas remotas ou causas SQL nas mensagens públicas. Não habilite
logs de cabeçalhos HTTP nem imprima propriedades de `OAuthCredentials`.

## Cifra e chave

O formato `v1:<Base64>` contém IV aleatório de 12 bytes, conteúdo cifrado e tag de
autenticação de 16 bytes. AES usa chave de 256 bits. Cada operação cria outro IV;
criptografar o mesmo token duas vezes gera envelopes diferentes. O contexto autenticado
inclui versão, e-mail e finalidade (acesso/renovação), impedindo que cifras sejam trocadas
entre contas ou entre colunas sem falha de autenticação.

O formato não aceita texto puro, versões desconhecidas, truncamento, adulteração,
chave incorreta ou contexto diferente. A falha não devolve conteúdo parcialmente decifrado.
O IV e a tag podem acompanhar a cifra no banco; a chave deve ficar separada.

Local padrão: `~/.go-drive-sync/token-key-v1`, onde `~` é `user.home` da JVM.
Não confundir com `credentials.json`, que identifica o aplicativo OAuth Desktop.
A chave é um arquivo binário aleatório, sem senha fixa embutida no programa.

- POSIX: diretório `700` e arquivo `600`, pertencentes ao usuário atual.
- Sistemas com ACL: criação com regra ALLOW exclusiva do proprietário, validada na leitura.
- Sistemas que não suportam essas permissões/atributos iniciais falham explicitamente;
  não há fallback para arquivo público. O caminho POSIX é exercitado nos testes Linux;
  o caminho ACL precisa de validação em Windows antes de declarar suporte validado.

Permissões são aplicadas na criação, antes de escrever a chave. Arquivos existentes
não são sobrescritos; links simbólicos no diretório privado ou no arquivo são recusados.
Instâncias do mesmo processo compartilham um lock; `CREATE_NEW` impede substituir
arquivo criado por outro processo. Uma disputa entre processos ou interrupção na primeira
escrita pode causar erro; não se aceita uma chave parcial. O uso normal é um processo
com H2 embarcado, como nos repositórios existentes.

Esta abordagem protege a exposição isolada do banco. Não protege contra administrador,
processos com o mesmo usuário ou alguém que copie banco **e** chave. Não usa cofre do SO,
não cifra e-mails/metadados do H2 e não implementa rotação automática da chave.

## Backup e recuperação

Faça backup consistente do H2 com o aplicativo encerrado e guarde a chave correspondente
em local separado e protegido. Nunca envie chave, banco ou tokens ao Git ou a logs.
Se restaurar o banco em outra máquina, restaure também a chave original com permissões
restritas; uma chave nova não decifra os tokens antigos.

Chave perdida, inválida ou com permissões amplas gera `TokenProtectionException`.
Se há contas no banco, o serviço não cria uma substituta. A listagem continua disponível,
pois não depende de decifrar tokens. Restaure a chave e corrija as permissões; não apague
o banco como primeira tentativa, pois ele também guarda mapeamentos e histórico.
Sem backup da chave será necessário um procedimento explícito de recuperação/reautorização
das contas; esta entrega não apaga registros nem oferece um reset destrutivo automático.

Dados legados de testes em texto puro não são migrados automaticamente para tokens válidos.
Não houve persistência real no diagnóstico da issue #7. A V1 do Flyway permanece intacta:
as colunas TEXT já comportam o envelope e TIMESTAMP(9) preserva a precisão da expiração.

## Expiração e uso pela futura interface/API

`OAuthCredentials.expiresAt` e `SavedAccount.expiresAt` são `Instant` UTC. Ao gravar,
o serviço converte para `LocalDateTime` com `ZoneOffset.UTC`; ao ler, faz a conversão
inversa com o mesmo fuso. O SQL continua sem informação de fuso. `created_at` e datas
de sincronização mantêm a semântica anterior e não são convertidos por esta entrega.

`isExpired()` considera vencido no instante exato do limite. A validade é do access token,
não do refresh token. `loadCredentials(id)` retorna null para ID inexistente e devolve
tokens em memória com sua expiração, inclusive vencida; o futuro cliente deverá tratar
renovação antes de enviar uma requisição. O OAuthManager continua exigindo refresh token
na nova autorização, sem reaproveitar concessões incompletas.

```kotlin
// Criar uma vez com o identificador do H2 já inicializado; não recriar banco a cada evento.
val service = AccountAuthorizationService(AccountRepository(database))

/** Carrega metadados para o dono do estado da tela, sem enviar tokens para o Compose. */
suspend fun carregarContas(): List<SavedAccount> = service.listAccounts()

/** Disparada por um evento de login; a UI publica o resultado no seu estado observável. */
suspend fun adicionarConta(): SavedAccount = service.authorizeAndSave()
```

As classes acima pertencem a `com.godrivesync.drive.auth`, `data.db.repositories`
e `data.models`. Em Compose, dispare o serviço por callback/corrotina, nunca no corpo
do composable: recomposição repetiria o login. Como em SwingWorker, o serviço separa
I/O do trabalho da thread gráfica; somente declarar `suspend` não faria essa separação.

## Diagnóstico e testes

```sh
./gradlew oauthAccountLogin
./gradlew oauthAccountLogin --args='"/caminho com espaços/credentials.json"'
```

O diagnóstico cria/abre `drive-sync-db.mv.db` na raiz do projeto, cria/valida a chave,
abre o consentimento e confirma persistência e recuperação em memória. Exibe e-mail,
ID, preferência e expiração; nunca tokens. Ele é explícito e não faz parte de `build`
ou da inicialização da janela. `oauthLogin` continua apenas validando o OAuth sem banco.

Roteiro manual: autorize uma conta de teste, observe a confirmação, encerre o diagnóstico
e execute novamente com a mesma conta. Confira que há um único ID; uma segunda conta
deverá gerar outro. Confirme que `oauthLogin` ainda informa que não salva tokens.
Não abra nem compartilhe o arquivo da chave para conferir seu conteúdo.

```sh
./gradlew build
```

Os testes automáticos usam somente tokens fictícios, HTTP loopback, H2 e chaves temporárias.
Cobrem recuperação após reabrir, UTC/nanossegundos, vencimento, atualização de conta com
FK, concorrência, corrupção, troca de contexto, permissões POSIX, chave perdida/incorreta,
erros HTTP/JSON, redirects e cancelamento. Não validam consentimento real do Google,
integração gráfica ou ACL Windows. O consentimento real exige interação no navegador.

Referências: [Drive about.get](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get),
[User.emailAddress](https://developers.google.com/workspace/drive/api/reference/rest/v3/User),
[Cipher do JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/Cipher.html).
