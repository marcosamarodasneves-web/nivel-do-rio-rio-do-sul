# Nível do Rio — Rio do Sul (Android)

App nativo Android sem bibliotecas de terceiros em runtime. A versão 1.3.2 mantém os níveis atuais, histórico visual de níveis, chuva oficial, barragens e widget; as previsões são somente leitura de um serviço centralizado.

## Versão 1.3.2 — contrato adaptativo e histórico expandido

Na tela de memorial, o histórico começa com 12 linhas. O botão **Mostrar mais** acrescenta 12 linhas por toque até mostrar todas as linhas recebidas do servidor. Um contador informa quantas estão visíveis. Quando não há outras linhas disponíveis, o botão desaparece.

O filtro **Já conferidas** mostra somente linhas em que **+1 h, +2 h e +3 h** tenham sido conferidos pelo servidor. Resultados `dentro`, `quase` e `fora` são aceitos; linhas pendentes, incompletas ou sem medição são excluídas do filtro. A filtragem acontece antes da paginação, para que registros antigos consolidados não sejam ocultados pelos recentes. Desmarcar o filtro volta a mostrar todas as linhas.

O histórico consultado corresponde às últimas **24 horas**. O período dos indicadores de acurácia e a retenção esperada são lidos de `accuracy_days` e `retention_days` na resposta; o fallback legado é de sete dias, e a resposta adaptativa publicada informa **60 dias** para ambos. A tela mostra esses períodos recebidos pelo servidor. Filtrar ou expandir não recalcula níveis, erros ou percentuais. Ao abrir a tela, o app solicita uma resposta atualizada. O estado do filtro e a quantidade expandida são preservados na recriação da Activity. A grade não fica redesenhando continuamente depois que termina a animação de entrada.

## Previsões centralizadas

As previsões **+1 h, +2 h e +3 h** são apresentadas no card principal e no memorial. O aplicativo faz somente consultas GET; não calcula, registra, confere nem envia previsões localmente. Histórico e estatísticas são apresentados quando retornados pelo servidor. A versão 1.3.2 aceita `deterministic-v2` e `adaptive-v3`; a resposta adaptativa é marcada como **“Aprendizado adaptativo · experimental”** e não promete precisão.

O Android escolhe a versão declarada em `model_version` na raiz e só consome `latest`, `forecasts` e `accuracy` cuja versão seja igual à selecionada. Linhas sem `model_version` são aceitas apenas como compatibilidade de respostas v2 legadas. Se uma resposta raiz ainda declarar `deterministic-v2`, ela é exibida como fallback v2 até a publicação adaptativa; versões nunca são misturadas em uma mesma grade ou estatística.

O último snapshot de previsão pode ser mantido como cache offline, identificado como salvo/antigo; não há banco SQL local de previsões nem histórico de previsão persistente no aparelho. Respostas sem dados atuais, sem horizonte necessário ou com modelo incompatível aparecem como indisponíveis, sem uma previsão local substituta.

Os níveis atuais continuam vindo diretamente da fonte pública. Chuva oficial, barragens e histórico visual permanecem independentes da previsão. Chuva meteorológica opcional permanece ausente quando o servidor retorna `null`; ausência não é convertida em zero medido. No início do aprendizado adaptativo, a acurácia pode aparecer como **sem dados**: isso não é convertido em zero nem em percentual local. Os resultados aparecem conforme os horizontes vencem e são conferidos; **não é necessário esperar 60 dias**. Sessenta dias é a janela máxima de retenção e estatística, não o prazo inicial de treinamento.

Cotas oficiais: abaixo de 4,50 m normal; 4,50 m a menos de 5,50 m atenção; 5,50 m até 6,50 m inclusive alerta; acima de 6,50 m emergência.

## Limites importantes

A previsão é experimental e não substitui avisos oficiais. O memorial exibe os fatores, margens, versão do modelo, freshness e estatísticas retornados pelo servidor. Não há promessas de acurácia incorporadas no APK. Siga sempre os avisos da Defesa Civil.

## Build e testes

Requisitos: Android SDK 35 (`platforms;android-35` e `build-tools;35.0.0`), JDK 17 ou 21 e Gradle 8.10.2 ou compatível.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

O APK de debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. A aplicação usa `minSdk 23`, `targetSdk 35`, `versionCode 15` e `versionName 1.3.2`. O GitHub Actions executa testes e compilação.

## Instalação

APKs de debug são assinados pela chave da máquina que os compilou. Se o Android rejeitar a atualização por assinatura incompatível, desinstale a versão anterior antes de instalar o novo APK. Isso não remove o histórico e as previsões armazenados no servidor, mas remove preferências e caches locais do app. Instalar pelo gerenciador de arquivos pode exigir a permissão de instalar apps desconhecidos.

## Estrutura relevante

- `DataRepository.java`: níveis diretos, histórico visual, chuva oficial/contextual e barragens.
- `HydrologyForecastClient.java`: cliente GET, parser estrito v2/adaptive-v3, cache do último snapshot e regra de freshness.
- `ForecastHistorySelection.java`: seleção e paginação somente de dados recebidos; não calcula previsões ou acurácia.
- `ForecastCardView.java`, `ForecastActivity.java`, `ForecastScorecardView.java`: apresentação dos horizontes, filtros, indisponibilidade e estatísticas.
- `HistoryStore.java`: reserva local para o gráfico de níveis quando a série pública não responde; não é histórico de previsões.
