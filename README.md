# Nível do Rio — Rio do Sul (Android)

App nativo Android sem bibliotecas de terceiros em runtime. A versão 1.3.1 mantém os níveis atuais, histórico visual de níveis, chuva oficial, barragens e widget; as previsões são somente leitura de um serviço centralizado.

## Versão 1.3.1 — histórico expandido

Na tela de memorial, o histórico começa com 12 linhas. O botão **Mostrar mais** acrescenta 12 linhas por toque até mostrar todas as linhas recebidas do servidor. Um contador informa quantas estão visíveis. Quando não há outras linhas disponíveis, o botão desaparece.

O filtro **Já conferidas** mostra somente linhas em que **+1 h, +2 h e +3 h** tenham sido conferidos pelo servidor. Resultados `dentro`, `quase` e `fora` são aceitos; linhas pendentes, incompletas ou sem medição são excluídas do filtro. A filtragem acontece antes da paginação, para que registros antigos consolidados não sejam ocultados pelos recentes. Desmarcar o filtro volta a mostrar todas as linhas.

O histórico consultado corresponde às últimas **24 horas**; os indicadores de acurácia correspondem aos últimos **sete dias**. Filtrar ou expandir não recalcula níveis, erros ou percentuais. Ao abrir a tela, o app solicita uma resposta atualizada. O estado do filtro e a quantidade expandida são preservados na recriação da Activity. A grade não fica redesenhando continuamente depois que termina a animação de entrada.

## Previsões centralizadas

As previsões **+1 h, +2 h e +3 h** são apresentadas no card principal e no memorial. O aplicativo faz somente consultas GET; não calcula, registra, confere nem envia previsões localmente. Histórico e estatísticas são apresentados quando retornados pelo servidor.

O último snapshot de previsão pode ser mantido como cache offline, identificado como salvo/antigo; não há banco SQL local de previsões nem histórico de previsão persistente no aparelho. Respostas sem dados atuais, sem horizonte necessário ou com modelo incompatível aparecem como indisponíveis, sem uma previsão local substituta.

Os níveis atuais continuam vindo diretamente da fonte pública. Chuva oficial, barragens e histórico visual permanecem independentes da previsão. Chuva meteorológica opcional permanece ausente quando o servidor retorna `null`; ausência não é convertida em zero medido.

Cotas oficiais: abaixo de 4,50 m normal; 4,50 m a menos de 5,50 m atenção; 5,50 m até 6,50 m inclusive alerta; acima de 6,50 m emergência.

## Limites importantes

A previsão é experimental e não substitui avisos oficiais. O memorial exibe os fatores, margens, versão do modelo, freshness e estatísticas retornados pelo servidor. Não há promessas de acurácia incorporadas no APK. Siga sempre os avisos da Defesa Civil.

## Build e testes

Requisitos: Android SDK 35 (`platforms;android-35` e `build-tools;35.0.0`), JDK 17 ou 21 e Gradle 8.10.2 ou compatível.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

O APK de debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. A aplicação usa `minSdk 23`, `targetSdk 35`, `versionCode 14` e `versionName 1.3.1`. O GitHub Actions executa testes e compilação.

## Instalação

APKs de debug são assinados pela chave da máquina que os compilou. Se o Android rejeitar a atualização por assinatura incompatível, desinstale a versão anterior antes de instalar o novo APK. Isso não remove o histórico e as previsões armazenados no servidor, mas remove preferências e caches locais do app. Instalar pelo gerenciador de arquivos pode exigir a permissão de instalar apps desconhecidos.

## Estrutura relevante

- `DataRepository.java`: níveis diretos, histórico visual, chuva oficial/contextual e barragens.
- `HydrologyForecastClient.java`: cliente GET, parser v2, cache do último snapshot e regra de freshness.
- `ForecastHistorySelection.java`: seleção e paginação somente de dados recebidos; não calcula previsões ou acurácia.
- `ForecastCardView.java`, `ForecastActivity.java`, `ForecastScorecardView.java`: apresentação dos horizontes, filtros, indisponibilidade e estatísticas.
- `HistoryStore.java`: reserva local para o gráfico de níveis quando a série pública não responde; não é histórico de previsões.
