# Orientações para trabalhar neste repositório

Leia PLAN.md e a issue atual antes de implementar. A instrução atual do usuário define
qual issue executar; a seção histórica de tarefa em SESSION_PROMPT.md pode estar desatualizada.

## Documentação obrigatória

O mantenedor conhece Java/Swing e está aprendendo Kotlin/Compose. Toda implementação
ou alteração de código autoral deve incluir documentação em português:

- Documentar classes, enums, funções (inclusive privadas) e composables com KDoc,
  explicando responsabilidade, estado, entradas/saídas e efeitos relevantes.
- Comentar blocos não triviais, decisões e conceitos de Compose: recomposição,
  remember, estado observável, callbacks, layouts, Modifier, efeitos e corrotinas.
- Explicar propriedades compartilhadas e configurações Gradle. Usar analogias com
  Java/Swing quando úteis, deixando claras as diferenças.
- Manter comentários coerentes com a implementação; não apenas repetir cada linha.
  Imports, comandos vetoriais repetitivos e wrappers gerados não exigem comentário individual.
- Atualizar README.md e os guias em docs/ quando houver mudanças de uso ou arquitetura.
- Documentação faz parte da entrega e deve acompanhar os commits de código.

## Git e revisão

- Não commitar diretamente em main.
- Usar branch por issue: feat/<numero>-<descricao> ou fix/<numero>-<descricao>.
- Criar commits claros, fazer push e abrir/atualizar um PR com Closes #<numero>.
- Informar o link do PR e quais verificações foram executadas.
- O merge é feito pelo usuário; não fazer merge automaticamente.
- Não incluir arquivos locais alheios à tarefa, credenciais ou artefatos gerados.

## Validação

Executar ./gradlew build após mudanças no código/configuração. Para UI, verificar os
estados afetados, temas e tamanhos relevantes. Mudanças apenas em comentários não
exigem criar testes que reproduzam a implementação. Não afirmar que há testes
unitários quando o Gradle informar test NO-SOURCE.

Guia de leitura: docs/COMPOSE-PARA-QUEM-VEM-DE-SWING.md.
Fluxo de revisão: docs/PULL-REQUESTS.md.
