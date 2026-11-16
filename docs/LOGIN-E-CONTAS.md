# Conexão de contas — issue #10

O aplicativo mantém todas as contas adicionadas. Ao reabrir, recupera a lista do H2
e abre Dashboard sem depender de resposta do Google. Sem contas, apresenta o convite
para adicionar uma conta no menu Contas. Erro ao ler o banco oferece recarregamento.

## Ações e estados

| Ação/estado | Comportamento |
|---|---|
| Adicionar conta Google | Abre OAuth, identifica o e-mail e salva tokens criptografados; o mesmo e-mail atualiza a conta existente. |
| Verificando acesso | Consulta perfil/renova credenciais em segundo plano, sem bloquear a janela ou outras contas. |
| Acesso confirmado | A consulta autenticada funcionou; não significa que arquivos foram sincronizados. |
| Reconectar | Exige nova autorização da mesma identidade quando a renovação retorna `invalid_grant`. |
| Tentar novamente | Repete a verificação daquela conta após falha temporária/configuração; não abre navegador automaticamente. |
| Cancelar autorização | Cancela o fluxo OAuth e relê a lista; se o commit já ocorreu, a conta permanece salva. |
| Ver mapeamentos | Mostra o contexto da conta escolhida, sem desativar nenhuma outra. A criação de pastas ainda é futura. |

Após adicionar/reconectar com sucesso, o destino é Mapeamentos se a conta não possui
mapeamentos, ou Dashboard caso possua. A reabertura com contas salvas sempre vai ao
Dashboard. A preferência de conta padrão não restringe a lista recuperada.

## Contrato multi-conta

Não existe botão Entrar nem conta ativa exclusiva. Selecionar uma conta é somente
uma escolha de visualização/configuração. A decisão aprovada para o motor das
issues #23/#24 é retomar todos os mapeamentos habilitados automaticamente, com
limite de concorrência e isolamento de falhas. Esta entrega prepara a interface;
não inicia transferências nem cria o motor de sincronização.

Fechar a janela ainda encerra o aplicativo. Fechar para bandeja é a issue #32;
intervalo e iniciar com o sistema pertencem à #30. Remoção, preferência padrão
na interface e demais operações de gerenciamento pertencem à #11/#42.

## Arquitetura e ciclo de vida

`Main` cria `AccountAuthorizationService`, um `AccountCredentialManager` compartilhado
e `LoginAccountService`. A janela mantém `LoginViewModel` com `remember`; seu scope
filho usa a thread gráfica, e os serviços deslocam I/O para `Dispatchers.IO`.
`DisposableEffect` cancela os trabalhos ao encerrar. O manager existente serializa
operações da mesma conta, permitindo independência entre contas.

`LoginState` expõe `StateFlow` somente leitura. `collectAsState` torna os valores
observáveis pelo Compose; callbacks iniciam operações. É semelhante ao modelo de
tela com listeners em Swing, sem guardar componentes ou usar setters de widgets.
`LaunchedEffect` aplica/consome pedidos de navegação; recomposição e troca de tema
não iniciam novos consentimentos. `start()` é idempotente.

O carregamento local termina antes das verificações remotas. Até três verificações
ocorrem simultaneamente, cada uma com estado e cancelamento próprios. Uma resposta
antiga cancelada não pode substituir o estado de uma conta reconectada. O cancelamento
do HTTP síncrono de perfil/refresh aguarda resposta ou timeout, conforme o contrato
da issue #9; a janela continua responsiva.

O estado visual usa `SavedAccount`, nunca `Account` com cifras nem `OAuthCredentials`.
Mensagens remotas/corpos HTTP não são apresentados. O OAuth continua com JSON Desktop
externo; nenhuma dependência ou migração de banco foi acrescentada.

## Avatares

`GoogleAccountProfileClient` consulta `about?fields=user(emailAddress,photoLink)` via
manager e confirma o e-mail. A imagem é opcional: ausência ou falha mantém a inicial
do e-mail. [Contrato oficial de User](https://developers.google.com/workspace/drive/api/reference/rest/v3/User).

`AccountPhotoLoader` usa outro cliente HTTP, sem Bearer, apenas HTTPS de
`googleusercontent.com`/`ggpht.com` e seus subdomínios, sem redirects. Limita a resposta
a 1 MiB, conexão a 5 s, requisição a 10 s e imagens a 2048 × 2048 antes da decodificação.
Produz miniatura de até 96 px em IO. Formatos não suportados pelo ImageIO também usam
iniciais. O bitmap permanece em memória durante a janela; recarregar/verificar pode
atualizá-lo. Não há cache em disco nem nova coluna para foto.

## Validação automatizada

```sh
./gradlew build
```

- `LoginViewModelTest`: três contas restauradas antes da rede, independência de falhas,
  navegação, duplo clique, retry, autorização negada/timeout, cancelamento após commit e fechamento.
- `GoogleAccountProfileClientTest`: HTTP local autenticado, identidade divergente,
  foto ausente, resposta inválida e três contas/mapeamentos após reabrir H2 temporário.
- `AccountPhotoLoaderTest`: restrições de origem, redução e rejeição de imagem inválida/grande.
- `LoginScreenTest`: Compose raster/semântica nos sete temas em 900 × 640 e 1120 × 800,
  estados vazio/carregando/erro/OAuth/contas, ações e abertura automática do Dashboard.
  Evidências PNG são geradas em `build/login-ui`, ignorado pelo Git.

Os testes usam contas fictícias, sem navegador, Google real ou banco do usuário.
Renderização raster não comprova comportamento do navegador do sistema nem do gerenciador de janelas.

## Roteiro manual com Google real

1. Com `credentials.json` Desktop configurado conforme o guia OAuth, execute `./gradlew run`.
2. Em Contas, adicione uma conta e conclua o consentimento; confira e-mail e destino após salvar.
3. Adicione outras contas; confira foto/inicial e verificação individual.
4. Encerre/reabra: confira Dashboard e todas as contas sem escolher Entrar nem autorizar novamente.
5. Sem rede, confira que a lista permanece acessível; restaure a rede e use Tentar novamente.
6. Cancele uma autorização e confira retorno à interface. Para uma conta que realmente exigir
   reconexão, use Reconectar e selecione a mesma identidade; não revogue contas apenas para testar.

Este roteiro requer interação do mantenedor com o consentimento Google. Não deve
ser confundido com o OAuth real validado anteriormente no diagnóstico da issue #7.
