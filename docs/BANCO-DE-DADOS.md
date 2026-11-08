# Banco H2 e Exposed

A issue #4 implementa o esquema local do aplicativo. O H2 roda dentro do mesmo
processo Java, sem servidor separado. O Exposed DSL descreve tabelas e operações
SQL com tipos Kotlin; não usamos entidades DAO nem carregamento automático de relações.

## Arquivos e responsabilidades

| Arquivo | Responsabilidade |
|---|---|
| `data/db/Database.kt` | Configurar a conexão e executar `SchemaUtils.create` em uma transação. |
| `data/db/tables/AccountsTable.kt` | Metadados das contas Google e colunas para tokens. |
| `data/db/tables/FolderMappingsTable.kt` | Vínculos entre pastas remotas e caminhos locais. |
| `data/db/tables/SyncHistoryTable.kt` | Registros de transferências vinculados a contas. |
| `Main.kt` | Inicializar o banco antes de iniciar o ciclo da interface. |
| `src/test/kotlin/com/godrivesync/data/db/DatabaseTest.kt` | Verificar o contrato com arquivos H2 temporários. |

Os caminhos de produção acima são relativos a `src/main/kotlin/com/godrivesync/`.

## Sequência de inicialização

```text
main()
  → initializeDatabase()
      → Database.connect("jdbc:h2:file:./drive-sync-db")
      → transaction(database)
          → SchemaUtils.create(accounts, folder_mappings, sync_history)
  → leitura da aparência
  → application / Window / App
```

`Database.connect` registra como obter conexões; a transação abre a conexão física
e executa o SQL. A transação conclui com commit ou rollback e fecha sua conexão.
O identificador `Database` retornado pode ser reutilizado pelos futuros repositórios
em `transaction(database)`. Não representa uma conexão JDBC mantida aberta o tempo todo.

`SchemaUtils.create` cria tabelas ausentes, incluindo seus índices. É seguro executar a mesma
inicialização em cada abertura: os registros existentes permanecem. Isso **não é
uma migração de esquema**: alterações futuras em colunas de tabelas já existentes
precisarão de uma estratégia própria. DDL do H2 pode efetuar commit implícito;
uma falha de criação não garante desfazer todas as tabelas já criadas.

O acesso é bloqueante. Em Java/Swing, executar JDBC no `ActionListener` bloqueia a
EDT; no Compose vale a mesma preocupação. Esta criação acontece antes da UI.
Operações posteriores devem usar um dispatcher de I/O, nunca o corpo de um composable.
Se a conexão ou o esquema falhar, a exceção é propagada, a configuração incompleta
é desregistrada do Exposed e a janela não abre. O erro fica visível na saída do processo.

## Como ler a DSL vindo de Java

| Kotlin/Exposed | Significado |
|---|---|
| `object AccountsTable : Table("accounts")` | Um singleton de metadados da tabela, não uma conta individual. |
| `integer("id").autoIncrement()` + `PrimaryKey(id)` | Coluna inteira gerada pelo H2 e usada como chave primária. |
| `varchar("email", 255).uniqueIndex()` | Texto com limite e índice que rejeita duplicatas. |
| `reference(..., AccountsTable.id)` | Chave estrangeira validada pelo banco. |
| `.nullable()` | Permite SQL NULL e produz um tipo Kotlin anulável, como `LocalDateTime?`. |
| `.default(false)` | Valor padrão definido no esquema. |
| `.defaultExpression(CurrentDateTime)` | Data/hora calculada pelo banco quando não informada. |
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
- Direções e status são VARCHAR nesta etapa, conforme o plano. Validação dos valores,
  conta padrão única e coerência entre a conta do histórico e a conta do mapeamento
  ainda dependem das futuras regras de domínio/repositórios.

As colunas `access_token` e `refresh_token` são TEXT e **não criptografam dados**.
O aplicativo ainda não grava contas. Quando o OAuth for implementado, ambos os
tokens deverão ser criptografados antes da persistência; não gravar tokens reais
em texto puro. Os testes usam apenas valores fictícios.

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
```

O build inclui quatro testes de integração JUnit 4, com diretórios temporários
limpos pelo JUnit. Eles cobrem criação do `.mv.db`, reabertura preservando dados,
IDs, campos opcionais, datas, valores padrão, arquivos grandes, e-mail duplicado,
NOT NULL, as três chaves estrangeiras e exclusões de registros referenciados.
Algumas verificações usam JDBC diretamente para comprovar que as restrições vêm do H2.
O relatório HTML fica em `build/reports/tests/test/index.html`.

## Referências oficiais

- [Tabelas e restrições no Exposed](https://www.jetbrains.com/help/exposed/working-with-tables.html)
- [Tipos de data/hora no Exposed](https://www.jetbrains.com/help/exposed/date-and-time-types.html)
