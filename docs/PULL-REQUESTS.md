# Pull Requests neste projeto

Um Pull Request (PR) é uma proposta de integrar os commits de uma branch em outra.
Ele reúne descrição, diferenças de código, discussão e resultados de verificações.
Abrir um PR não faz o merge: a main só recebe as alterações quando a integração é confirmada.

## Os quatro passos diferentes

| Ação | Resultado |
|---|---|
| Commit | Registra uma versão no histórico local da branch. |
| Push | Envia os commits para a mesma branch no GitHub. |
| Abrir PR | Cria a proposta de incorporar essa branch à main. |
| Merge | Integra a proposta aprovada à main. Neste projeto, é decisão do usuário. |

Na issue #3, a origem é `feat/3-temas-sidebar` e o destino é `main`.
Novos commits enviados para essa mesma branch atualizam o PR aberto; não é preciso
criar outro PR para cada ajuste da mesma atividade.

## Como abrir pelo site

1. Faça commit na branch da issue e push dessa branch.
2. No repositório, abra **Pull requests → New pull request**. Depois de um push,
   também pode aparecer o atalho **Compare & pull request**.
3. Selecione **base: main** (destino) e **compare: feat/3-temas-sidebar** (origem).
4. Confira a lista de commits e os arquivos alterados.
5. Clique em **Create pull request**, preencha título e descrição e confirme a criação.
6. Inclua `Closes #3` na descrição: ao integrar na branch padrão, o GitHub pode fechar
   automaticamente a issue associada.

A descrição deve dizer qual problema foi resolvido, o comportamento resultante,
como foi validado e o que continua fora do escopo.

## O que significa “para revisão”

Um PR normal está disponível para leitura e discussão. Um **Draft** indica trabalho
em andamento; quando concluído, pode ser marcado como **Ready for review**.
Solicitar uma pessoa específica no campo **Reviewers** é um passo separado e depende
dos acessos disponíveis. Criar o PR não significa que alguém já revisou ou aprovou.

Você pode usar o PR para conferir o próprio projeto, mesmo trabalhando sozinho.
Não precisa inventar um segundo revisor nem aprovar formalmente o próprio PR.

## Como revisar e fazer o merge

1. Leia a descrição na página do PR.
2. Abra **Files changed**. Linhas adicionadas e removidas mostram o que mudou.
3. Abra **Commits** para entender as etapas do trabalho.
4. Execute o aplicativo e confira o comportamento que importa para você.
5. Se quiser ajustes, peça aqui no chat ou registre comentários no PR. Novos commits
   na branch atualizam a proposta. Não clique em merge enquanto faltar algo.
6. Quando estiver satisfeito e não houver bloqueios, use **Merge pull request** e
   confirme. Dependendo da configuração, também podem aparecer squash ou rebase.
7. Se apropriado, apague a branch remota pelo botão oferecido pelo GitHub após o merge.

Se não houver CI configurada, o GitHub não inventa testes automáticos: leia a seção
de validação e execute os comandos relevantes. Uma mensagem de “sem conflitos”
significa possibilidade de integração, não comprovação de que o código funciona.

Para atualizar a cópia local após o merge, primeiro confira `git status` e preserve
trabalho não commitado. Com a árvore adequada para trocar de branch:

```bash
git switch main
git pull --ff-only origin main
```

A próxima atividade deve partir da main atualizada em uma nova branch.

## Referências oficiais

- [Criar um Pull Request](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request)
- [Documentação de Pull Requests](https://docs.github.com/en/pull-requests)
