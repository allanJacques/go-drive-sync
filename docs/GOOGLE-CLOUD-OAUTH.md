# Preparar o Google Cloud para OAuth Desktop

Este guia atende à preparação da [issue #6](https://github.com/allanJacques/go-drive-sync/issues/6).
Em 13/11/2026, o mantenedor confirmou a conclusão do roteiro no Console. Na cópia
local, foram conferidos o JSON do cliente Desktop, os campos obrigatórios, os
endpoints padrão do Google, a permissão `600` e a exclusão do arquivo do Git.
API habilitada, consentimento, usuários de teste e escopo são registrados com base
nessa confirmação; essas configurações não foram consultadas diretamente no Console.
O aplicativo ainda não lê `credentials.json`: essa integração pertence à
[issue #7](https://github.com/allanJacques/go-drive-sync/issues/7).

## Configuração no Console

Mantenha o mesmo projeto selecionado durante todos os passos. Os nomes em inglês
ajudam a localizar os menus quando a tradução do Console variar.

1. Acesse o [Google Cloud Console](https://console.cloud.google.com/) com a conta
   que administrará o projeto. No seletor de projetos, escolha **Novo projeto**,
   nomeie como **Go Drive Sync**, crie e selecione-o. O ID pode manter o valor único
   sugerido pelo Google. Veja [criação de projetos](https://developers.google.com/workspace/guides/create-project).
2. Em **APIs e serviços → Biblioteca**, procure **Google Drive API** e clique em
   **Ativar**. Confira se aparece como habilitada. Essa é a API usada pelo cliente,
   conforme o [guia Java do Drive](https://developers.google.com/drive/api/quickstart/java).
3. Em **Google Auth Platform → Começar (Get started)**, informe o nome
   **Go Drive Sync**, seu e-mail de suporte e seu e-mail de contato. Escolha o
   público **Externo (External)**, revise os termos apresentados e conclua.
4. Em **Público-alvo (Audience)**, mantenha **Teste (Testing)** e adicione seu
   e-mail em **Usuários de teste**. Inclua também as outras contas que usarão o
   aplicativo durante os testes. Não publique o aplicativo nesta etapa.
5. Em **Acesso a dados (Data Access) → Adicionar ou remover escopos**, selecione
   `https://www.googleapis.com/auth/drive` e salve. Os passos de consentimento
   seguem o [guia do Google Auth Platform](https://developers.google.com/workspace/guides/configure-oauth-consent).
6. Em **Clientes (Clients) → Criar cliente**, selecione **App para computador
   (Desktop app)**, nomeie como **Go Drive Sync Desktop** e crie. Baixe o JSON
   disponibilizado na criação. O tipo correto é descrito em
   [OAuth para aplicativos Desktop](https://developers.google.com/identity/protocols/oauth2/native-app).
7. Renomeie o arquivo baixado para **credentials.json** e coloque-o na raiz da sua
   cópia do repositório, ao lado de `gradlew`. Confira o resultado com a lista abaixo.

## Escopo e modo de teste

O escopo `drive` permite ler e gerenciar todos os arquivos do Drive da conta que
autorizar o aplicativo; não fica limitado às pastas escolhidas na interface.
Foi escolhido para atender à sincronização bidirecional de pastas existentes do
PLAN.md. `drive.file` limita o acesso a arquivos criados/abertos pelo app ou
compartilhados com ele e não cobre sozinho a navegação geral planejada.
Essa escolha é uma decisão deste projeto baseada nos
[escopos oficiais do Drive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

`drive` é um escopo restrito; a distribuição pública exige avaliar os requisitos
de verificação aplicáveis. Nesta preparação, o público permanece em teste.
Declarar o escopo no Console não concede acesso: o futuro OAuthManager deverá
solicitá-lo e cada usuário autorizará sua própria conta.

Com público externo em **Testing** e o escopo `drive`, os refresh tokens expiram
em sete dias. Será necessário autorizar novamente durante o desenvolvimento;
isso não significa que o JSON do cliente expirou. Consulte a
[regra de expiração do Google](https://developers.google.com/identity/protocols/oauth2).

## Retorno local: correção dos itens Web da issue

**Desktop app** é o tipo do cliente OAuth; **Externo** é o público da tela de
consentimento. O cliente Desktop não oferece os campos de origens JavaScript e
redirect URIs autorizadas usados pelo cliente Web.

Por isso, os itens da issue sobre cadastrar `http://localhost` e
`http://localhost:8080/callback` no Console não se aplicam a esse cliente.
O OAuthManager da issue #7 deverá abrir o navegador e receber a resposta em um
servidor local, preferencialmente em `127.0.0.1`, com uma porta disponível.
Host, porta e caminho do callback deverão coincidir com o listener e com o
`redirect_uri` usado na autorização e na troca do código. A porta 8080 não é
uma configuração obrigatória do Console. Veja o
[fluxo de loopback para Desktop](https://developers.google.com/identity/protocols/oauth2/native-app).

## Arquivo local e conferência

O caminho convencionado para desenvolvimento é `<raiz-do-repositório>/credentials.json`.
Ele fica fora de `src/main/resources`, evitando incorporá-lo ao JAR pelo processamento
de recursos. A leitura desse caminho ainda será implementada na issue #7.

A lista abaixo pode ser reutilizada em novas configurações. O registro da
conferência desta entrega está no início do guia.

- [ ] Google Drive API habilitada no projeto escolhido.
- [ ] Público externo em teste, com os e-mails necessários cadastrados.
- [ ] Escopo `https://www.googleapis.com/auth/drive` salvo no acesso a dados.
- [ ] Cliente do tipo Desktop e JSON baixado.
- [ ] JSON na raiz do repositório, com objeto principal `installed` e campos
      `client_id`, `client_secret`, `auth_uri` e `token_uri` preenchidos.
      Se houver `web` ou `type: service_account`, obtenha a credencial Desktop correta;
      não converta o arquivo editando as chaves.
- [ ] Arquivo ignorado pelo Git e ausente dos arquivos rastreados.

Na raiz do repositório, execute:

```sh
git check-ignore -v credentials.json
git ls-files -- credentials.json
```

O primeiro comando deve mostrar a regra de `.gitignore`; o segundo não deve
imprimir nada. A regra já existe e também cobre `client_secret*.json`.
Esses comandos conferem o tratamento pelo Git, não a validade das credenciais.
No Linux, após salvar o arquivo, `chmod 600 credentials.json` restringe sua leitura
e escrita ao proprietário.

O JSON identifica o aplicativo e não contém os access/refresh tokens das contas.
Não o envie em commits, PRs ou mensagens. Aplicativos Desktop são clientes públicos:
o `client_secret` não substitui a proteção do fluxo; a issue #7 deverá usar PKCE.
O armazenamento criptografado dos tokens de cada conta pertence à issue #8.
Esses limites seguem o [modelo de OAuth Desktop do Google](https://developers.google.com/identity/protocols/oauth2/native-app).

Ao repetir o procedimento, conclua a etapa manual com as conferências acima.
O login real e as chamadas autenticadas serão validados nas próximas issues; `./gradlew build`
não verifica a configuração da conta no Google Cloud.
