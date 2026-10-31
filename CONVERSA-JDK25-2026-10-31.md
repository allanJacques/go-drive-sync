# Conversa sobre a stack e a migração para JDK 25

Data: 31/10/2026 — America/Cuiaba.

Registro das mensagens do usuário e das respostas finais do assistente nesta conversa. Mensagens de progresso, chamadas de ferramentas e metadados do aplicativo foram omitidos. Links locais foram adaptados para caminhos relativos ao projeto.

## Usuário

não tem como usar o jdk 25 nessa stack? me fale a versão de cada coisa

*A solicitação foi repetida após interrupções da conversa.*

## Assistente

**Sim, dá para usar o JDK 25 nessa stack.** O projeto já declara `jvmToolchain(25)`, mas está inconsistente: o Gradle está na versão **8.12** e configurado para rodar com **JDK 23**. O suporte oficial ao Java 25, tanto para executar o Gradle quanto para toolchains, começa no **Gradle 9.1.0**. [Matriz oficial](https://docs.gradle.org/current/userguide/compatibility.html)

As versões configuradas hoje são:

| Componente | Versão |
|---|---|
| JDK solicitado pelo projeto | **25** |
| JDK 25 instalado localmente | **25.0.5.0.3**, Oracle |
| JDK configurado para executar o Gradle | **23** |
| Gradle Wrapper | **8.12** |
| Kotlin JVM | **2.4.21** |
| Plugin Compose Compiler | **2.4.21** |
| Compose Multiplatform Desktop | **1.12.2** |
| Material 3 | Via `compose.material3`, sem versão explícita |
| Foojay — resolução de toolchains | **0.10.0** |
| Aplicação | **0.1.0-SNAPSHOT** |

Esses dados estão no [build.gradle.kts](build.gradle.kts), [gradle.properties](gradle.properties) e [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties).

As demais bibliotecas aparecem apenas no [planejamento](PLAN.md), **ainda sem dependências declaradas no build**:

| Componente | Versão planejada |
|---|---|
| Exposed | 0.58.x |
| H2 | 2.3.x |
| Google API Client | 2.7.x |
| Google Drive API | v3 — revisão não fixada |
| Google OAuth | 1.27.x |
| Koin | 3.6+ ou 4.0+ — ainda indefinida |
| kotlinx-coroutines | 1.9.x |
| kotlinx-serialization | 1.7.x |

Para alinhar tudo ao **JDK 25**, é necessário atualizar o Wrapper para uma versão compatível, ajustar `org.gradle.java.home` e a JVM do Gradle no IntelliJ, e validar o build. **Não alterei os arquivos nem executei o build nesta consulta.**

## Usuário

mas as versões do compose e do kotlin vão funcionar tudo na jdk 25? quais versões vc me recomendaria para cada coisa? verifica as compatibilidades.

## Assistente

**Sim: Kotlin 2.4.21 e Compose Multiplatform 1.12.2 podem ser usados com JDK 25. Eu manteria essas duas versões e atualizaria o Gradle.** Verifiquei as documentações oficiais; isso confirma a compatibilidade declarada, mas ainda não é uma validação do aplicativo executando.

As compatibilidades principais são:

| Combinação | Verificação |
|---|---|
| **Kotlin 2.4.21 + Java 25** | Kotlin gera bytecode Java 25 desde a versão 2.3.0. [Documentação](https://kotlinlang.org/docs/whatsnew23.html) |
| **Kotlin 2.4.21 + Gradle** | Faixa plenamente suportada: **7.6.3 a 9.7.0**. [Matriz Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) |
| **Gradle + Java 25** | Suporte para executar o Gradle e usar toolchains a partir de **9.1.0**. [Matriz Gradle](https://docs.gradle.org/current/userguide/compatibility.html) |
| **Compose 1.12.2 + Kotlin** | A versão atual do Compose é compatível com a atual do Kotlin; o **Compose Compiler deve ter a mesma versão do Kotlin**. [Matriz Compose](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) |
| **Compose Desktop + JDK** | Exige JDK **11+** para execução e **17+** para empacotamento. Java 25 atende aos requisitos; a página não apresenta uma certificação individual para cada JDK. [Requisitos](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) |

**Minha recomendação para este projeto:**

| Componente | Versão recomendada | Motivo |
|---|---|---|
| JDK | **25 LTS** | Usar no Gradle, toolchain e execução |
| Gradle Wrapper | **9.7.0** | Está na interseção das faixas suportadas por Java 25 e Kotlin 2.4.21 |
| Kotlin JVM | **2.4.21** | Manter |
| Compose Compiler | **2.4.21** | Alinhado ao Kotlin |
| Compose Multiplatform | **1.12.2** | Manter |
| Foojay Resolver | **1.0.0** | Inclui ajustes para Gradle 9. [Release](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention) |
| Exposed | **1.6.0** | Linha atual, com `exposed-core` e `exposed-jdbc` |
| H2 | **2.4.240** | Par usado pelo guia oficial do Exposed. [Exposed + H2](https://www.jetbrains.com/help/exposed/get-started-with-exposed.html) |
| Koin | **4.2.2**, usando DSL | Bibliotecas baseadas em Kotlin 2.3.20. [Versões](https://insert-koin.io/docs/support/releases/) |
| kotlinx-coroutines | **1.11.0** | Release estável baseada em Kotlin 2.2.20. [Release](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| kotlinx-serialization-json | **1.11.0** | Release estável baseada em Kotlin 2.3.20. [Release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.11.0) |
| Plugin Kotlin Serialization | **2.4.21** | Acompanha o compilador Kotlin |
| Google API Client | **2.9.2** | Release publicada atual. [Releases](https://github.com/googleapis/google-api-java-client/releases) |
| Google OAuth Client / Jetty | **1.39.0** | Para o fluxo de autenticação desktop. [Releases](https://github.com/googleapis/google-oauth-java-client/releases) |
| Google Drive SDK | **v3-rev20260901-2.0.0** | Revisão concreta documentada no repositório oficial. [SDK](https://github.com/googleapis/google-api-java-client-services/tree/main/clients/google-api-services-drive/v3) |

Há dois detalhes que eu trataria separadamente:

- **Material 3:** tem versionamento independente. Para priorizar estabilidade, eu partiria da **1.9.0 estável**, explicitamente fixada, e validaria com Compose 1.12.2. A tabela atual do Compose lista **1.12.0-alpha03**, portanto não devemos supor que todos os componentes são estáveis porque o Compose é estável. [Versionamento](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html), [componentes publicados](https://github.com/JetBrains/compose-multiplatform/releases).
- **Koin:** usaria a DSL sem adicionar o compiler plugin inicialmente. A lista de versões verificadas desse plugin ainda não inclui Kotlin **2.4.21**. [Compatibilidade do plugin](https://github.com/InsertKoinIO/koin-compiler-plugin).

Para as bibliotecas auxiliares, **não encontrei uma matriz que certifique todo esse conjunto especificamente no JDK 25**. São escolhas compatíveis pelos requisitos e versões-base publicados; a confirmação prática exige compilar, abrir a interface, testar banco/OAuth e gerar o pacote desktop. Não alterei o projeto nesta consulta.

## Usuário

consegue mudar pra mim por favor

## Assistente

Atualizei o projeto para **JDK 25 + Gradle 9.7.0**, mantendo **Kotlin 2.4.21 e Compose 1.12.2**.

- Apliquei as dependências recomendadas no [build.gradle.kts](build.gradle.kts).
- Alinhei o IntelliJ ao JDK 25 e atualizei a documentação.
- **Build e distribuição passaram.**
- A janela Compose abriu no display virtual; houve fallback gráfico por ausência de OpenGL nesse ambiente.

Banco e OAuth ainda não foram testados funcionalmente. No IntelliJ, recarregue o projeto Gradle para reconhecer as alterações.

## Usuário

salve essa conversa nossa na pasta do projeto por favor
