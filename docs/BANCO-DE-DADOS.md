# Banco H2, Flyway e Exposed

A issue #4 implementa o esquema local e a issue #5 acrescenta modelos e repositórios.
O H2 roda dentro do mesmo
processo Java, sem servidor separado. Flyway executa scripts SQL versionados para
criar e atualizar o esquema. O Exposed DSL descreve tabelas e operações com tipos
Kotlin; não usamos entidades DAO nem carregamento automático de relações.

## Arquivos e responsabilidades

| Arquivo | Responsabilidade |
|---|---|
| `data/db/Database.kt` | Executar migrações antes de registrar o banco no Exposed. |
| `data/db/LegacyDatabase.kt` | Reconhecer e adotar o esquema anterior ao Flyway sem apagar dados. |
| `data/db/tables/AccountsTable.kt` | Metadados das contas Google e colunas para tokens. |
| `data/db/tables/FolderMappingsTable.kt` | Vínculos entre pastas remotas e caminhos locais. |
| `data/db/tables/SyncHistoryTable.kt` | Registros de transferências vinculados a contas. |
| `data/models/` | DTOs imutáveis e enums, independentes de Exposed e Compose. |
| `data/db/repositories/AccountRepository.kt` | CRUD de contas e escolha atômica da padrão. |
| `data/db/repositories/MappingRepository.kt` | CRUD e pausa de mapeamentos com dono imutável. |
| `data/db/repositories/HistoryRepository.kt` | Inserção, filtros e limpeza de transferências. |
| `Main.kt` | Inicializar o banco antes de iniciar o ciclo da interface. |
| `src/test/kotlin/com/godrivesync/data/db/DatabaseTest.kt` | Verificar o contrato com arquivos H2 temporários. |
| `src/test/kotlin/com/godrivesync/data/db/DatabaseMigrationTest.kt` | Testar instalação, adoção, atualização e falhas. |
| `src/test/kotlin/com/godrivesync/data/db/repositories/` | Testar contratos, rollback e concorrência dos repositórios. |
| `src/main/resources/db/migration/V1__cria_tabelas_iniciais.sql` | Criar o esquema inicial; arquivo imutável após aplicação. |

Os caminhos Kotlin de produção acima são relativos a `src/main/kotlin/com/godrivesync/`.

## Sequência de inicialização

```text
main()
  → initializeDatabase()
      → Flyway configurado para PUBLIC e classpath:db/migration
      → adoção do banco anterior, somente se reconhecido
      → Flyway.migrate(): valida histórico e aplica versões pendentes
      → Database.connect("jdbc:h2:file:./drive-sync-db")
  → leitura da aparência
  → application / Window / App
```

O Flyway acessa o H2 via JDBC e mantém a tabela `flyway_schema_history`, com versões,
tipos de execução, checksums e resultados. Em um banco vazio, executa V1; nas próximas
aberturas, valida o histórico e aplica somente scripts pendentes. As migrações ficam
no classpath e são empacotadas no JAR, sem depender de arquivos SQL externos.

`Database.connect` do Exposed só é chamado depois do sucesso das migrações. Ele
registra como obter conexões; os repositórios usam `transaction(database)`
para abrir uma conexão, consultar/gravar e concluir com commit ou rollback. Esse
identificador não representa uma conexão JDBC mantida aberta o tempo todo.

Flyway é o único responsável pelo esquema em produção. `SchemaUtils.create()` e
alterações automáticas baseadas nas Table não são executados na inicialização.
As Table Kotlin continuam necessárias para consultar/gravar, e devem acompanhar
os scripts SQL. Migrações versionadas aplicadas não devem ser editadas: uma correção
depois de distribuídas deve ser uma nova migração.

O acesso é bloqueante. Em Java/Swing, executar JDBC no `ActionListener` bloqueia a
EDT; no Compose vale a mesma preocupação. Esta criação acontece antes da UI.
Operações posteriores devem usar um dispatcher de I/O, nunca o corpo de um composable.
Se a conexão, a validação ou uma migração falhar, a exceção é propagada e a janela
não abre. O erro fica visível na saída do processo. O Exposed ainda não foi registrado.

## Adoção de bancos anteriores ao Flyway

Um arquivo criado pela primeira implementação da issue #4 já tem as três tabelas,
mas não possui histórico de migrações. `LegacyDatabase.kt` compara seus metadados
com uma referência H2 em memória criada a partir do SQL imutável da V1.

A comparação cobre nomes de tabelas/views de PUBLIC, colunas, tipos, tamanhos,
precisão, nullabilidade, defaults, geração de valores, PKs, FKs, índices únicos e
expressões CHECK. Nomes de constraints/índices gerados pelo H2 são desconsiderados;
suas colunas e regras são comparadas. Os registros do usuário não são lidos nem copiados.

Se a estrutura corresponder, `baseline()` registra a versão 1 sem executar a V1
novamente. Contas, mapeamentos, histórico e contadores de identidade permanecem.
As migrações V2 em diante serão aplicadas normalmente. O registro BASELINE não tem
checksum de execução da V1, pois o script não foi executado naquele arquivo.

Um banco não vazio com estrutura desconhecida é recusado antes de criar o histórico.
Não usamos `baselineOnMigrate(true)`, que poderia aceitar qualquer esquema não vazio.
Essa adoção atende somente ao esquema original da issue #4; não converte bancos
arbitrários. A referência é a V1 congelada, nunca as Table Kotlin de uma versão futura.

## Exemplo: adicionar uma coluna em uma próxima versão

Depois de aprovar o plano da atividade, criaríamos
`src/main/resources/db/migration/V2__adiciona_nome_de_exibicao.sql`:

```sql
-- Contas existentes recebem NULL; a edição do nome será tratada pela aplicação.
ALTER TABLE accounts ADD COLUMN display_name VARCHAR(255);
```

Em `AccountsTable`, acrescentaríamos a definição correspondente:

```kotlin
/** Nome opcional de exibição; contas anteriores à V2 começam sem esse valor. */
val displayName = varchar("display_name", 255).nullable()
```

Um banco novo executaria V1 e V2; um banco já na V1 executaria apenas V2. A versão
do esquema é independente da versão comercial do aplicativo e pode avançar várias
vezes entre releases. Uma coluna obrigatória precisa de um default adequado ou de
preenchimento dos dados antigos antes de aplicar NOT NULL.

Para cada mudança, testar instalação vazia, atualização de um banco anterior com
dados e reabertura sem repetir a migração. O exemplo V2 desta entrega existe apenas
em `src/test/resources/db/test-upgrade`; não é incluído no aplicativo distribuído.

## Falhas e recuperação

O aplicativo recusa checksum divergente, migração com falha e versões aplicadas
que não estejam presentes no executável atual. Isso também impede abrir, com um
executável antigo, um banco que já foi atualizado por uma versão mais nova.

DDL do H2 pode efetuar commit implícito: uma migração que falha depois de um
`ALTER TABLE` pode deixar alterações parciais. O teste de falha cobre esse cenário;
não há promessa de rollback completo nem recuperação automática.

Antes de distribuir atualizações de esquema, planeje backup e recuperação. Ao
investigar uma falha, mantenha o app fechado, preserve uma cópia do arquivo e confira
o erro, o histórico e a estrutura efetiva. Corrija o estado ou restaure um backup
compatível antes de tentar novamente. `repair` altera o histórico, não desfaz DDL
parcial; não é chamado automaticamente. `clean` está desabilitado na configuração.

## Como ler a DSL vindo de Java

| Kotlin/Exposed | Significado |
|---|---|
| `object AccountsTable : Table("accounts")` | Um singleton de metadados da tabela, não uma conta individual. |
| `integer("id").autoIncrement()` + `PrimaryKey(id)` | Coluna inteira gerada pelo H2 e usada como chave primária. |
| `varchar("email", 255).uniqueIndex()` | Texto com limite e índice que rejeita duplicatas. |
| `reference(..., AccountsTable.id)` | Chave estrangeira validada pelo banco. |
| `.nullable()` | Permite SQL NULL e produz um tipo Kotlin anulável, como `LocalDateTime?`. |
| `.default(false)` | Metadado de default usado pelo Exposed, correspondente ao DEFAULT do SQL. |
| `.defaultExpression(CurrentDateTime)` | Expressão de data/hora do banco; deve corresponder ao SQL da migração. |
| `datetime(...)` | Usa `java.time.LocalDateTime` com o módulo `exposed-java-time`. |

As datas seguem o DATETIME do PLAN.md: data e hora sem fuso, representadas por
TIMESTAMP no H2. Elas não são instantes UTC nem preservam um offset. A futura
camada que receber horários da API precisará definir uma conversão consistente.

## Relacionamentos e regras atuais

```text
accounts.id ← folder_mappings.account_id  (obrigatório)
accounts.id ← sync_history.account_id     (obrigatório)
folder_mappings.id ← sync_history.mapping_id (opcional)
```

- Os três IDs são inteiros autoincrementais e chaves primárias.
- E-mail é obrigatório e único. Nenhuma normalização de maiúsculas/minúsculas é aplicada.
- `is_default` começa falso; `sync_direction` começa `BIDIRECTIONAL`; `enabled` começa verdadeiro.
- `created_at` e `timestamp` recebem data/hora do banco se omitidos.
- São anuláveis apenas `last_full_sync`, `last_sync`, `mapping_id` e `error_message`.
  Os demais campos são obrigatórios, alguns com valor padrão.
- `file_size` é BIGINT/Long e suporta tamanhos maiores que 2 GiB.
- Excluir uma conta ou mapeamento ainda referenciado é restringido (`RESTRICT`).
  Não há exclusão em cascata nem limpeza automática do histórico.
- Direções e status continuam VARCHAR no banco, conforme o plano. Na API Kotlin,
  enums limitam os valores aceitos. Os repositórios coordenam a conta padrão e
  conferem se o mapeamento pertence à conta do histórico.

As colunas `access_token` e `refresh_token` são TEXT e **não criptografam dados**.
A UI ainda não grava contas; o repositório já permite a persistência. Seu contrato
exige receber ambos os tokens criptografados pela futura camada de autenticação.
Não há criptografia nem gerenciamento de chaves nesta entrega; não passar tokens
reais em texto puro. Os testes usam apenas valores fictícios. `Account.toString()`
omite os tokens para evitar exposição acidental ao imprimir o DTO.

## Modelos e repositórios (issue #5)

`Account`, `FolderMapping` e `SyncHistoryEntry` são `data class` com propriedades
`val`, semelhantes a DTOs/records Java. Não são entidades gerenciadas por ORM:
`copy(...)` cria outro valor e não grava nada. Cada operação materializa as linhas
e os campos TEXT dentro da transação; o chamador recebe objetos utilizáveis depois
do commit, sem manter uma conexão aberta ou disparar consultas ao ler propriedades.

Os três IDs são `Int?`: null indica um objeto novo. `Account.createdAt` e
`SyncHistoryEntry.timestamp` aceitam null apenas para solicitar a data do banco na
inserção; os registros retornados têm esses campos preenchidos. Isso não altera
o NOT NULL do esquema. Datas informadas usam `LocalDateTime`, com a precisão de
nanossegundos existente, sem conversão automática de fuso.

Há duas noções distintas de direção:

| Enum | Valores | Uso |
|---|---|---|
| `SyncDirection` | `BIDIRECTIONAL`, `DOWNLOAD_ONLY` | Política do mapeamento. |
| `TransferDirection` | `DOWNLOAD`, `UPLOAD` | Sentido de uma operação do histórico. |
| `TransferStatus` | `SUCCESS`, `FAILED`, `SKIPPED` | Resultado da operação. |

Os dois enums de transferência ficam em `SyncHistoryEntry.kt`. Seus nomes são o
contrato persistido: não renomear constantes sem tratar os dados existentes.
Um valor desconhecido introduzido por SQL externo causa erro explícito de leitura;
não é convertido silenciosamente para um padrão.

### Contratos públicos

| Operação | Resultado e regras |
|---|---|
| `AccountRepository.save(account)` | ID null insere; ID existente atualiza. Retorna a fotografia salva. Preserva `createdAt` em atualizações; ID inexistente causa `IllegalArgumentException`. Não faz upsert por e-mail. |
| `findByEmail(email)` / `findAll()` | Busca exata, retornando null se ausente; lista por ID crescente, incluindo contas não padrão. |
| `setDefault(id)` | Escolhe uma única padrão; false se o ID não existir, mantendo a escolha anterior. |
| `AccountRepository.delete(id)` | true se removeu, false se ausente; não elege outra padrão. Dependentes impedem exclusão. |
| `MappingRepository.save(mapping)` | Exige ID null e conta existente; retorna o mapeamento com ID gerado. |
| `findByAccountId(accountId)` em mapeamentos | Lista por ID crescente, incluindo pausados. Conta sem registros retorna lista vazia. |
| `update(mapping)` | Exige ID preenchido; retorna false se ausente. Troca de conta é recusada; outros campos são atualizados. |
| `setEnabled(id, enabled)` / `MappingRepository.delete(id)` | true se atingiu o registro, false se ausente. `setEnabled` altera somente a pausa; histórico vinculado impede exclusão. |
| `HistoryRepository.insert(entry)` | Exige ID null e tamanho não negativo. Confere existência e dono do mapeamento opcional; retorna ID/data preenchidos. |
| `findByAccountId(accountId)` em histórico | Lista por data decrescente; ID decrescente desempata datas iguais. |
| `findByDirection(direction, accountId = null)` | Mesma ordenação; null inclui todas as contas, ID informado combina os dois filtros. |
| `deleteOlderThan(cutoff)` | Remove de todas as contas apenas datas menores que o limite e retorna a quantidade; preserva a data exata do limite. |

O H2 continua garantindo UNIQUE, NOT NULL e FKs, propagando erros de persistência
como `ExposedSQLException`. Validações de argumentos do repositório causam
`IllegalArgumentException`. Nenhuma falha é convertida em sucesso ou lista vazia.
O histórico não oferece update, e as listas ainda não implementam paginação.

### Transações e concorrência

Cada método usa explicitamente o `Database` recebido pelo construtor. Assim,
conectar outro banco no mesmo processo não desvia uma operação para ele. Uma
transação conclui as mudanças juntas ou faz rollback quando ocorre uma exceção.
Por exemplo, se salvar uma nova conta padrão falhar por e-mail duplicado, a conta
padrão anterior permanece marcada.

As gravações de contas (`save`, `setDefault`, `delete`) usam um `ReentrantLock`
compartilhado entre instâncias até o commit/rollback. Ele também cobre a corrida
entre duas primeiras inserções, quando ainda não existem linhas para bloquear.
Esse lock serializa as gravações de contas do processo, inclusive entre bancos
distintos; consultas e operações dos outros repositórios continuam independentes.

Esses três métodos devem ser chamados **fora de uma transação Exposed externa**:
uma transação aninhada poderia adiar o commit para depois de liberar o lock. A API
recusa esse uso com `IllegalStateException`. Execute cada gravação de conta como
uma unidade completa. A garantia de no máximo uma padrão vale para gravações pela
API no processo; SQL direto ou outros processos não participam do lock. O banco
permanece no modo H2 embarcado, sem acesso simultâneo por múltiplos processos.
Salvar `isDefault=false` ou remover a padrão pode deixar nenhuma conta selecionada.

O dono de um mapeamento não muda após a criação. Isso preserva a coerência de seu
histórico e evita que uma edição concorrente mude a conta entre a validação e a
inserção de uma transferência. Remoções concorrentes continuam protegidas pelas FKs.
Para remover uma conta referenciada, a camada chamadora precisa definir como tratar
os dependentes; o CRUD não apaga histórico nem mapeamentos em cascata.

### Chamada a partir de uma futura tela

```kotlin
import com.godrivesync.data.db.repositories.AccountRepository
import com.godrivesync.data.models.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database

/** Carrega fotografias das contas sem bloquear a thread de eventos da interface. */
suspend fun carregarContas(database: Database): List<Account> = withContext(Dispatchers.IO) {
    AccountRepository(database).findAll()
}
```

O `database` vem de `initializeDatabase()`, chamado uma vez na inicialização; não
rode migrações a cada consulta. O exemplo prepara a futura integração com a UI:
os composables atuais ainda não chamam esses repositórios. Apenas declarar uma
função `suspend` não muda de thread; `withContext(Dispatchers.IO)` faz essa troca.
Depois da consulta, o dono do estado poderá publicar a lista para o Compose.

## Execução e arquivo local

```sh
./gradlew run
```

A URL padrão gera `drive-sync-db.mv.db` no diretório de execução. Na tarefa `run`,
isso é a raiz do projeto; ao executar uma distribuição em outro diretório, o arquivo
será criado naquele local. Não é um caminho fixo no perfil do usuário.
Os arquivos `.mv.db` e `.trace.db` já são ignorados pelo Git. Não versionar o banco.

Para conferir a issue, execute o app, feche a janela e verifique a presença do arquivo.
Abra novamente para confirmar que o mesmo banco é reutilizado. Para inspecioná-lo
em uma ferramenta JDBC, feche o app e use a URL acima a partir do mesmo diretório,
ou use o caminho absoluto, sem usuário/senha configurados. Evite abrir outra instância
do app sobre o mesmo arquivo: esta etapa usa o modo H2 embarcado, sem `AUTO_SERVER`.

## Validação automatizada

```sh
./gradlew build
./gradlew test --tests com.godrivesync.data.db.DatabaseTest
./gradlew test --tests 'com.godrivesync.data.db.repositories.*'
```

O build inclui testes de integração JUnit 4, com diretórios temporários
limpos pelo JUnit. Os dez testes originais cobrem criação do `.mv.db`, reabertura preservando dados,
IDs, campos opcionais, datas, valores padrão, arquivos grandes, e-mail duplicado,
NOT NULL, as três chaves estrangeiras e exclusões de registros referenciados.
Também conferem histórico/checksum, adoção de banco antigo com dados, recusa de
esquemas alterados, adição de coluna sem perda de registros, recusa de banco de
versão futura e falha parcial de migração. Algumas verificações usam JDBC diretamente
para comprovar que as restrições vêm do H2.

Os testes dos repositórios acrescentam CRUD completo, filtros entre contas,
direções/status tipados, ordenação determinística, data de criação preservada,
limpeza com fronteira de nanossegundos, rollback da conta padrão, concorrência
entre instâncias, banco explícito, recusa de transação externa nas gravações de
conta, dono imutável e remoção explícita dos dependentes. São testes de integração
com H2 real, sem rede, credenciais Google ou interação com a interface.

A fixture `src/test/resources/db/legacy/issue4.sql` preserva somente o esquema do
commit `69f8928`, sem usuários/senhas/registros exportados. Ela não deve acompanhar
mudanças nas Table atuais: representa o banco antigo que precisa continuar migrável.
O relatório HTML fica em `build/reports/tests/test/index.html`.

## Referências oficiais

- [Tabelas e restrições no Exposed](https://www.jetbrains.com/help/exposed/working-with-tables.html)
- [Tipos de data/hora no Exposed](https://www.jetbrains.com/help/exposed/date-and-time-types.html)
- [Operações CRUD no Exposed](https://www.jetbrains.com/help/exposed/dsl-crud-operations.html)
- [Transações no Exposed](https://www.jetbrains.com/help/exposed/transactions.html)
- [Flyway: migrações versionadas](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [Flyway: API Java](https://documentation.red-gate.com/flyway/reference/usage/api-java)
- [Flyway: adoção por baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)
