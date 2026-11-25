# Mapeamentos de pastas

Em **Contas**, clique em **Ver mapeamentos** na conta proprietária da pasta. Confira o e-mail no título. A opção **Mapeamentos** da barra lateral retoma o contexto escolhido. Ao iniciar, o aplicativo usa a conta padrão ou, na ausência dela, a primeira conta salva.

## Cadastrar uma pasta

1. Abra a pasta no Google Drive pelo navegador. Copie o ID depois de `/folders/`, até o fim do identificador. Por exemplo, em `https://drive.google.com/drive/u/0/folders/abc123?usp=sharing`, copie apenas `abc123`.
2. No aplicativo, clique em **+ Novo mapeamento**. Cole o ID e preencha o nome exibido no Drive, por exemplo **Projetos**.
3. Informe o caminho absoluto da pasta no computador, por exemplo `/home/usuario/Projetos` no Linux ou `C:\Users\Usuario\Projetos` no Windows. Clique em **Salvar**.

A tela grava a configuração no H2. A conferência do ID e do caminho verifica a sintaxe localmente. A existência e o acesso às pastas não são consultados durante o cadastro. Os seletores de pasta do Drive e do computador estão previstos nas issues #19 e #20.

## Editar, remover e sincronizar

- **Editar** abre os três campos preenchidos. **Cancelar** descarta o rascunho. A edição preserva a conta, a direção, a pausa e a data da última sincronização.
- **✕ Remover** pede confirmação e mostra as duas pastas. A confirmação exclui o vínculo cadastrado. Os arquivos locais e remotos e os registros de transferência continuam disponíveis.
- **↕ Bidirecional** é a direção inicial. Vínculos de download aparecem como **↓ Somente download**. A escolha de direção pelo formulário está prevista na #21.
- **Sincronizar agora** fica desabilitado enquanto o motor da #23 está em desenvolvimento. Cadastrar um vínculo não inicia transferências.

## Como a tela atualiza os dados

`Main` compartilha o `MappingRepository` com o login e cria o `MappingsViewModel` no escopo da janela. Ao entrar em Mapeamentos, o Compose pede a lista da conta selecionada. O modelo executa as transações em `Dispatchers.IO` e publica o estado na thread da interface. Cada abertura tem uma geração própria para que o resultado de uma leitura anterior não apareça em outra conta.

A edição grava somente as três colunas do formulário. Na remoção confirmada, `removeKeepingHistory` torna nulo o campo `mapping_id` das transferências daquele vínculo e remove o cadastro na mesma transação. O histórico mantém conta, arquivo, direção, data e resultado.
